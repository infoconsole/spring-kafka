/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;
import org.springframework.kafka.listener.config.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.TopicPartitionInitialOffset;
import org.springframework.kafka.support.TopicPartitionInitialOffset.SeekPosition;
import org.springframework.scheduling.SchedulingAwareRunnable;
import org.springframework.util.Assert;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

/**
 * Single-threaded Message listener container using the Java {@link Consumer} supporting
 * auto-partition assignment or user-configured assignment.
 * <p>
 * With the latter, initial partition offsets can be provided.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Gary Russell
 * @author Murali Reddy
 * @author Marius Bogoevici
 * @author Martin Dam
 * @author Artem Bilan
 * @author Loic Talhouarne
 */
public class KafkaMessageListenerContainer<K, V> extends AbstractMessageListenerContainer<K, V> {

	private final ConsumerFactory<K, V> consumerFactory;

	private final TopicPartitionInitialOffset[] topicPartitions;

	private ListenerConsumer listenerConsumer;

	private ListenableFuture<?> listenerConsumerFuture;

	private GenericMessageListener<?> listener;

	private GenericAcknowledgingMessageListener<?> acknowledgingMessageListener;

	private String clientIdSuffix;

	/**
	 * Construct an instance with the supplied configuration properties.
	 * @param consumerFactory the consumer factory.
	 * @param containerProperties the container properties.
	 */
	public KafkaMessageListenerContainer(ConsumerFactory<K, V> consumerFactory,
			ContainerProperties containerProperties) {
		this(consumerFactory, containerProperties, (TopicPartitionInitialOffset[]) null);
	}

	/**
	 * Construct an instance with the supplied configuration properties and specific
	 * topics/partitions/initialOffsets.
	 * @param consumerFactory the consumer factory.
	 * @param containerProperties the container properties.
	 * @param topicPartitions the topics/partitions; duplicates are eliminated.
	 */
	public KafkaMessageListenerContainer(ConsumerFactory<K, V> consumerFactory,
			ContainerProperties containerProperties, TopicPartitionInitialOffset... topicPartitions) {
		super(containerProperties);
		Assert.notNull(consumerFactory, "A ConsumerFactory must be provided");
		this.consumerFactory = consumerFactory;
		if (topicPartitions != null) {
			this.topicPartitions = Arrays.copyOf(topicPartitions, topicPartitions.length);
		}
		else {
			this.topicPartitions = containerProperties.getTopicPartitions();
		}
	}

	/**
	 * Set a suffix to add to the {@code client.id} consumer property (if the consumer
	 * factory supports it).
	 * @param clientIdSuffix the suffix to add.
	 * @since 1.0.6
	 */
	public void setClientIdSuffix(String clientIdSuffix) {
		this.clientIdSuffix = clientIdSuffix;
	}

	/**
	 * Return the {@link TopicPartition}s currently assigned to this container,
	 * either explicitly or by Kafka; may be null if not assigned yet.
	 * @return the {@link TopicPartition}s currently assigned to this container,
	 * either explicitly or by Kafka; may be null if not assigned yet.
	 */
	public Collection<TopicPartition> getAssignedPartitions() {
		if (this.listenerConsumer.definedPartitions != null) {
			return Collections.unmodifiableCollection(this.listenerConsumer.definedPartitions.keySet());
		}
		else if (this.listenerConsumer.assignedPartitions != null) {
			return Collections.unmodifiableCollection(this.listenerConsumer.assignedPartitions);
		}
		else {
			return null;
		}
	}

	@Override
	protected void doStart() {
		if (isRunning()) {
			return;
		}
		ContainerProperties containerProperties = getContainerProperties();

		if (!this.consumerFactory.isAutoCommit()) {
			AckMode ackMode = containerProperties.getAckMode();
			if (ackMode.equals(AckMode.COUNT) || ackMode.equals(AckMode.COUNT_TIME)) {
				Assert.state(containerProperties.getAckCount() > 0, "'ackCount' must be > 0");
			}
			if ((ackMode.equals(AckMode.TIME) || ackMode.equals(AckMode.COUNT_TIME))
					&& containerProperties.getAckTime() == 0) {
				containerProperties.setAckTime(5000);
			}
		}

		Object messageListener = containerProperties.getMessageListener();
		Assert.state(messageListener != null, "A MessageListener is required");
		if (messageListener instanceof GenericAcknowledgingMessageListener) {
			this.acknowledgingMessageListener = (GenericAcknowledgingMessageListener<?>) messageListener;
		}
		else if (messageListener instanceof GenericMessageListener) {
			this.listener = (GenericMessageListener<?>) messageListener;
		}
		else {
			throw new IllegalStateException("messageListener must be 'MessageListener' "
					+ "or 'AcknowledgingMessageListener', not " + messageListener.getClass().getName());
		}
		if (containerProperties.getConsumerTaskExecutor() == null) {
			SimpleAsyncTaskExecutor consumerExecutor = new SimpleAsyncTaskExecutor(
					(getBeanName() == null ? "" : getBeanName()) + "-C-");
			containerProperties.setConsumerTaskExecutor(consumerExecutor);
		}
		this.listenerConsumer = new ListenerConsumer(this.listener, this.acknowledgingMessageListener);
		setRunning(true);
		this.listenerConsumerFuture = containerProperties
				.getConsumerTaskExecutor()
				.submitListenable(this.listenerConsumer);
	}

	@Override
	protected void doStop(final Runnable callback) {
		if (isRunning()) {
			this.listenerConsumerFuture.addCallback(new ListenableFutureCallback<Object>() {

				@Override
				public void onFailure(Throwable e) {
					KafkaMessageListenerContainer.this.logger.error("Error while stopping the container: ", e);
					if (callback != null) {
						callback.run();
					}
				}

				@Override
				public void onSuccess(Object result) {
					if (KafkaMessageListenerContainer.this.logger.isDebugEnabled()) {
						KafkaMessageListenerContainer.this.logger
								.debug(KafkaMessageListenerContainer.this + " stopped normally");
					}
					if (callback != null) {
						callback.run();
					}
				}
			});
			setRunning(false);
			this.listenerConsumer.consumer.wakeup();
		}
	}

	private void publishIdleContainerEvent(long idleTime) {
		if (getApplicationEventPublisher() != null) {
			getApplicationEventPublisher().publishEvent(new ListenerContainerIdleEvent(
					KafkaMessageListenerContainer.this, idleTime, getBeanName(), getAssignedPartitions()));
		}
	}

	@Override
	public String toString() {
		return "KafkaMessageListenerContainer [id=" + getBeanName()
				+ (this.clientIdSuffix != null ? ", clientIndex=" + this.clientIdSuffix : "")
				+ ", topicPartitions=" + getAssignedPartitions()
				+ "]";
	}


	private final class ListenerConsumer implements SchedulingAwareRunnable, ConsumerSeekCallback {

		private final Log logger = LogFactory.getLog(ListenerConsumer.class);

		private final Object theListener;

		private final ContainerProperties containerProperties = getContainerProperties();

		private final OffsetCommitCallback commitCallback = this.containerProperties.getCommitCallback() != null
				? this.containerProperties.getCommitCallback()
				: new LoggingCommitCallback();

		private final Consumer<K, V> consumer;

		private final Map<String, Map<Integer, Long>> offsets = new HashMap<>();

		private final MessageListener<K, V> listener;

		private final AcknowledgingMessageListener<K, V> acknowledgingMessageListener;

		private final BatchMessageListener<K, V> batchListener;

		private final BatchAcknowledgingMessageListener<K, V> batchAcknowledgingMessageListener;

		private final boolean isBatchListener;

		private final boolean autoCommit = KafkaMessageListenerContainer.this.consumerFactory.isAutoCommit();

		private final boolean isManualAck = this.containerProperties.getAckMode().equals(AckMode.MANUAL);

		private final boolean isManualImmediateAck =
				this.containerProperties.getAckMode().equals(AckMode.MANUAL_IMMEDIATE);

		private final boolean isAnyManualAck = this.isManualAck || this.isManualImmediateAck;

		private final boolean isRecordAck = this.containerProperties.getAckMode().equals(AckMode.RECORD);

		private final boolean isBatchAck = this.containerProperties.getAckMode().equals(AckMode.BATCH);

		private final BlockingQueue<ConsumerRecord<K, V>> acks = new LinkedBlockingQueue<>();

		private final BlockingQueue<TopicPartitionInitialOffset> seeks = new LinkedBlockingQueue<>();

		private final ErrorHandler errorHandler;

		private final BatchErrorHandler batchErrorHandler;

		private volatile Map<TopicPartition, OffsetMetadata> definedPartitions;

		private volatile Collection<TopicPartition> assignedPartitions;

		private int count;

		private long last;

		@SuppressWarnings("unchecked")
		ListenerConsumer(GenericMessageListener<?> listener, GenericAcknowledgingMessageListener<?> ackListener) {
			Assert.state(!this.isAnyManualAck || !this.autoCommit,
					"Consumer cannot be configured for auto commit for ackMode " + this.containerProperties.getAckMode());
			@SuppressWarnings("deprecation")
			final Consumer<K, V> consumer =
					KafkaMessageListenerContainer.this.consumerFactory instanceof
									org.springframework.kafka.core.ClientIdSuffixAware
							? ((org.springframework.kafka.core.ClientIdSuffixAware<K, V>) KafkaMessageListenerContainer
									.this.consumerFactory)
										.createConsumer(KafkaMessageListenerContainer.this.clientIdSuffix)
							: KafkaMessageListenerContainer.this.consumerFactory.createConsumer();

			this.theListener = listener == null ? ackListener : listener;
			ConsumerRebalanceListener rebalanceListener = createRebalanceListener(consumer);

			if (KafkaMessageListenerContainer.this.topicPartitions == null) {
				if (this.containerProperties.getTopicPattern() != null) {
					consumer.subscribe(this.containerProperties.getTopicPattern(), rebalanceListener);
				}
				else {
					consumer.subscribe(Arrays.asList(this.containerProperties.getTopics()), rebalanceListener);
				}
			}
			else {
				List<TopicPartitionInitialOffset> topicPartitions =
						Arrays.asList(KafkaMessageListenerContainer.this.topicPartitions);
				this.definedPartitions = new HashMap<>(topicPartitions.size());
				for (TopicPartitionInitialOffset topicPartition : topicPartitions) {
					this.definedPartitions.put(topicPartition.topicPartition(),
							new OffsetMetadata(topicPartition.initialOffset(), topicPartition.isRelativeToCurrent()));
				}
				consumer.assign(new ArrayList<>(this.definedPartitions.keySet()));
			}
			this.consumer = consumer;
			GenericErrorHandler<?> errHandler = this.containerProperties.getGenericErrorHandler();
			if (this.theListener instanceof BatchAcknowledgingMessageListener) {
				this.listener = null;
				this.batchListener = null;
				this.acknowledgingMessageListener = null;
				this.batchAcknowledgingMessageListener = (BatchAcknowledgingMessageListener<K, V>) this.theListener;
				this.isBatchListener = true;
			}
			else if (this.theListener instanceof AcknowledgingMessageListener) {
				this.listener = null;
				this.acknowledgingMessageListener = (AcknowledgingMessageListener<K, V>) this.theListener;
				this.batchListener = null;
				this.batchAcknowledgingMessageListener = null;
				this.isBatchListener = false;
			}
			else if (this.theListener instanceof BatchMessageListener) {
				this.listener = null;
				this.batchListener = (BatchMessageListener<K, V>) this.theListener;
				this.acknowledgingMessageListener = null;
				this.batchAcknowledgingMessageListener = null;
				this.isBatchListener = true;
			}
			else if (this.theListener instanceof MessageListener) {
				this.listener = (MessageListener<K, V>) this.theListener;
				this.batchListener = null;
				this.acknowledgingMessageListener = null;
				this.batchAcknowledgingMessageListener = null;
				this.isBatchListener = false;
			}
			else {
				throw new IllegalArgumentException("Listener must be one of 'MessageListener', "
						+ "'BatchMessageListener', 'AcknowledgingMessageListener', "
						+ "'BatchAcknowledgingMessageListener', not " + this.theListener.getClass().getName());
			}
			if (this.isBatchListener) {
				validateErrorHandler(true);
				this.errorHandler = new LoggingErrorHandler();
				this.batchErrorHandler = errHandler == null ? new BatchLoggingErrorHandler()
						: (BatchErrorHandler) errHandler;
			}
			else {
				validateErrorHandler(false);
				this.errorHandler = errHandler == null ? new LoggingErrorHandler() : (ErrorHandler) errHandler;
				this.batchErrorHandler = new BatchLoggingErrorHandler();
			}
			Assert.state(!this.isBatchListener || !this.isRecordAck, "Cannot use AckMode.RECORD with a batch listener");
		}

		public ConsumerRebalanceListener createRebalanceListener(final Consumer<K, V> consumer) {
			return new ConsumerRebalanceListener() {

				@Override
				public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
					getContainerProperties().getConsumerRebalanceListener().onPartitionsRevoked(partitions);
					// Wait until now to commit, in case the user listener added acks
					commitManualAcks();
				}

				@Override
				public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
					ListenerConsumer.this.assignedPartitions = partitions;
					if (!ListenerConsumer.this.autoCommit) {
						// Commit initial positions - this is generally redundant but
						// it protects us from the case when another consumer starts
						// and rebalance would cause it to reset at the end
						// see https://github.com/spring-projects/spring-kafka/issues/110
						Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
						for (TopicPartition partition : partitions) {
							offsets.put(partition, new OffsetAndMetadata(consumer.position(partition)));
						}
						if (ListenerConsumer.this.logger.isDebugEnabled()) {
							ListenerConsumer.this.logger.debug("Committing on assignment: " + offsets);
						}
						if (KafkaMessageListenerContainer.this.getContainerProperties().isSyncCommits()) {
							ListenerConsumer.this.consumer.commitSync(offsets);
						}
						else {
							ListenerConsumer.this.consumer.commitAsync(offsets,
									KafkaMessageListenerContainer.this.getContainerProperties().getCommitCallback());
						}
					}
					if (ListenerConsumer.this.theListener instanceof ConsumerSeekAware) {
						seekPartitions(partitions, false);
					}
					getContainerProperties().getConsumerRebalanceListener().onPartitionsAssigned(partitions);
				}

			};
		}

		private void seekPartitions(Collection<TopicPartition> partitions, boolean idle) {
			Map<TopicPartition, Long> current = new HashMap<>();
			for (TopicPartition topicPartition : partitions) {
				current.put(topicPartition, ListenerConsumer.this.consumer.position(topicPartition));
			}
			ConsumerSeekCallback callback = new ConsumerSeekCallback() {

				@Override
				public void seek(String topic, int partition, long offset) {
					ListenerConsumer.this.consumer.seek(new TopicPartition(topic, partition), offset);
				}

				@Override
				public void seekToBeginning(String topic, int partition) {
					ListenerConsumer.this.consumer.seekToBeginning(
							Collections.singletonList(new TopicPartition(topic, partition)));
				}

				@Override
				public void seekToEnd(String topic, int partition) {
					ListenerConsumer.this.consumer.seekToEnd(
							Collections.singletonList(new TopicPartition(topic, partition)));
				}

			};
			if (idle) {
				((ConsumerSeekAware) ListenerConsumer.this.theListener).onIdleContainer(current, callback);
			}
			else {
				((ConsumerSeekAware) ListenerConsumer.this.theListener).onPartitionsAssigned(current, callback);
			}
		}

		private void validateErrorHandler(boolean batch) {
			GenericErrorHandler<?> errHandler = this.containerProperties.getGenericErrorHandler();
			if (this.errorHandler == null) {
				return;
			}
			Type[] genericInterfaces = errHandler.getClass().getGenericInterfaces();
			boolean ok = false;
			for (Type t : genericInterfaces) {
				if (t.equals(ErrorHandler.class)) {
					ok = !batch;
					break;
				}
				else if (t.equals(BatchErrorHandler.class)) {
					ok = batch;
					break;
				}
			}
			Assert.state(ok, "Error handler is not compatible with the message listener, expecting an instance of "
					+ (batch ? "BatchErrorHandler" : "ErrorHandler") + " not " + errHandler.getClass().getName());
		}

		@Override
		public boolean isLongLived() {
			return true;
		}

		@Override
		public void run() {
			if (this.theListener instanceof ConsumerSeekAware) {
				((ConsumerSeekAware) this.theListener).registerSeekCallback(this);
			}
			this.count = 0;
			this.last = System.currentTimeMillis();
			if (isRunning() && this.definedPartitions != null) {
				initPartitionsIfNeeded();
			}
			long lastReceive = System.currentTimeMillis();
			long lastAlertAt = lastReceive;
			while (isRunning()) {
				try {
					if (!this.autoCommit) {
						processCommits();
					}
					processSeeks();
					ConsumerRecords<K, V> records = this.consumer.poll(this.containerProperties.getPollTimeout());
					if (records != null && this.logger.isDebugEnabled()) {
						this.logger.debug("Received: " + records.count() + " records");
					}
					if (records != null && records.count() > 0) {
						if (this.containerProperties.getIdleEventInterval() != null) {
							lastReceive = System.currentTimeMillis();
						}
						invokeListener(records);
					}
					else {
						if (this.containerProperties.getIdleEventInterval() != null) {
							long now = System.currentTimeMillis();
							if (now > lastReceive + this.containerProperties.getIdleEventInterval()
									&& now > lastAlertAt + this.containerProperties.getIdleEventInterval()) {
								publishIdleContainerEvent(now - lastReceive);
								lastAlertAt = now;
								if (this.theListener instanceof ConsumerSeekAware) {
									seekPartitions(getAssignedPartitions(), true);
								}
							}
						}
					}
				}
				catch (WakeupException e) {
					// Ignore, we're stopping
				}
				catch (Exception e) {
					if (this.containerProperties.getGenericErrorHandler() != null) {
						this.containerProperties.getGenericErrorHandler().handle(e, null);
					}
					else {
						this.logger.error("Container exception", e);
					}
				}
			}
			commitManualAcks();
			try {
				this.consumer.unsubscribe();
			}
			catch (WakeupException e) {
				// No-op. Continue process
			}
			this.consumer.close();
			if (this.logger.isInfoEnabled()) {
				this.logger.info("Consumer stopped");
			}
		}

		private void commitManualAcks() {
			processCommits();
			if (this.offsets.size() > 0) {
				// we always commit after stopping the invoker
				commitIfNecessary();
			}
		}

		/**
		 * Process any acks that have been queued.
		 */
		private void handleAcks() {
			ConsumerRecord<K, V> record = this.acks.poll();
			while (record != null) {
				if (this.logger.isTraceEnabled()) {
					this.logger.trace("Ack: " + record);
				}
				processAck(record);
				record = this.acks.poll();
			}
		}

		private void processAck(ConsumerRecord<K, V> record) {
			if (this.isManualImmediateAck) {
				try {
					ackImmediate(record);
				}
				catch (WakeupException e) {
					// ignore - not polling
				}
			}
			else {
				addOffset(record);
			}
		}

		private void ackImmediate(ConsumerRecord<K, V> record) {
			Map<TopicPartition, OffsetAndMetadata> commits = Collections.singletonMap(
					new TopicPartition(record.topic(), record.partition()),
					new OffsetAndMetadata(record.offset() + 1));
			if (ListenerConsumer.this.logger.isDebugEnabled()) {
				ListenerConsumer.this.logger.debug("Committing: " + commits);
			}
			if (this.containerProperties.isSyncCommits()) {
				ListenerConsumer.this.consumer.commitSync(commits);
			}
			else {
				ListenerConsumer.this.consumer.commitAsync(commits,
						ListenerConsumer.this.commitCallback);
			}
		}

		private void invokeListener(final ConsumerRecords<K, V> records) {
			if (this.isBatchListener) {
				invokeBatchListener(records);
			}
			else {
				invokeRecordListener(records);
			}
		}

		private void invokeBatchListener(final ConsumerRecords<K, V> records) {
			List<ConsumerRecord<K, V>> recordList = new LinkedList<ConsumerRecord<K, V>>();
			Iterator<ConsumerRecord<K, V>> iterator = records.iterator();
			while (iterator.hasNext()) {
				recordList.add(iterator.next());
			}
			if (recordList.size() > 0) {
				try {
					if (this.batchAcknowledgingMessageListener != null) {
						this.batchAcknowledgingMessageListener.onMessage(recordList,
								this.isAnyManualAck
										? new ConsumerBatchAcknowledgment(recordList)
										: null);
					}
					else {
						this.batchListener.onMessage(recordList);
					}
					if (!this.isAnyManualAck && !this.autoCommit) {
						for (ConsumerRecord<K, V> record : getHighestOffsetRecords(recordList)) {
							this.acks.put(record);
						}
					}
				}
				catch (Exception e) {
					if (this.containerProperties.isAckOnError() && !this.autoCommit) {
						for (ConsumerRecord<K, V> record : getHighestOffsetRecords(recordList)) {
							this.acks.add(record);
						}
					}
					try {
						this.batchErrorHandler.handle(e, records);
					}
					catch (Exception ee) {
						this.logger.error("Error handler threw an exception", ee);
					}
					catch (Error er) { //NOSONAR
						this.logger.error("Error handler threw an error", er);
						throw er;
					}
				}
			}
		}

		private void invokeRecordListener(final ConsumerRecords<K, V> records) {
			Iterator<ConsumerRecord<K, V>> iterator = records.iterator();
			while (iterator.hasNext()) {
				final ConsumerRecord<K, V> record = iterator.next();
				if (this.logger.isTraceEnabled()) {
					this.logger.trace("Processing " + record);
				}
				try {
					if (this.acknowledgingMessageListener != null) {
						this.acknowledgingMessageListener.onMessage(record,
								this.isAnyManualAck
										? new ConsumerAcknowledgment(record)
										: null);
					}
					else {
						this.listener.onMessage(record);
					}
					if (!this.isAnyManualAck && !this.autoCommit) {
						this.acks.add(record);
					}
				}
				catch (Exception e) {
					if (this.containerProperties.isAckOnError() && !this.autoCommit) {
						this.acks.add(record);
					}
					try {
						this.errorHandler.handle(e, record);
					}
					catch (Exception ee) {
						this.logger.error("Error handler threw an exception", ee);
					}
					catch (Error er) { //NOSONAR
						this.logger.error("Error handler threw an error", er);
						throw er;
					}
				}
			}
		}

		private void processCommits() {
			handleAcks();
			this.count += this.acks.size();
			long now;
			AckMode ackMode = this.containerProperties.getAckMode();
			if (!this.isManualImmediateAck) {
				if (!this.isManualAck) {
					updatePendingOffsets();
				}
				boolean countExceeded = this.count >= this.containerProperties.getAckCount();
				if (this.isManualAck || this.isBatchAck || this.isRecordAck
						|| (ackMode.equals(AckMode.COUNT) && countExceeded)) {
					if (this.logger.isDebugEnabled() && ackMode.equals(AckMode.COUNT)) {
						this.logger.debug("Committing in AckMode.COUNT because count " + this.count
								+ " exceeds configured limit of " + this.containerProperties.getAckCount());
					}
					commitIfNecessary();
					this.count = 0;
				}
				else {
					now = System.currentTimeMillis();
					boolean elapsed = now - this.last > this.containerProperties.getAckTime();
					if (ackMode.equals(AckMode.TIME) && elapsed) {
						if (this.logger.isDebugEnabled()) {
							this.logger.debug("Committing in AckMode.TIME " +
									"because time elapsed exceeds configured limit of " +
									this.containerProperties.getAckTime());
						}
						commitIfNecessary();
						this.last = now;
					}
					else if (ackMode.equals(AckMode.COUNT_TIME) && (elapsed || countExceeded)) {
						if (this.logger.isDebugEnabled()) {
							if (elapsed) {
								this.logger.debug("Committing in AckMode.COUNT_TIME " +
										"because time elapsed exceeds configured limit of " +
										this.containerProperties.getAckTime());
							}
							else {
								this.logger.debug("Committing in AckMode.COUNT_TIME " +
										"because count " + this.count + " exceeds configured limit of" +
										this.containerProperties.getAckCount());
							}
						}

						commitIfNecessary();
						this.last = now;
						this.count = 0;
					}
				}
			}
		}

		private void processSeeks() {
			TopicPartitionInitialOffset offset = this.seeks.poll();
			while (offset != null) {
				if (this.logger.isTraceEnabled()) {
					this.logger.trace("Seek: " + offset);
				}
				try {
					SeekPosition position = offset.getPosition();
					if (position == null) {
						this.consumer.seek(offset.topicPartition(), offset.initialOffset());
					}
					else if (position.equals(SeekPosition.BEGINNING)) {
						this.consumer.seekToBeginning(Collections.singletonList(offset.topicPartition()));
					}
					else {
						this.consumer.seekToEnd(Collections.singletonList(offset.topicPartition()));
					}
				}
				catch (Exception e) {
					this.logger.error("Exception while seeking " + offset, e);
				}
				offset = this.seeks.poll();
			}
		}

		private void initPartitionsIfNeeded() {
			/*
			 * Note: initial position setting is only supported with explicit topic assignment.
			 * When using auto assignment (subscribe), the ConsumerRebalanceListener is not
			 * called until we poll() the consumer.
			 */
			for (Entry<TopicPartition, OffsetMetadata> entry : this.definedPartitions.entrySet()) {
				TopicPartition topicPartition = entry.getKey();
				OffsetMetadata metadata = entry.getValue();
				Long offset = metadata.offset;
				if (offset != null) {
					long newOffset = offset;

					if (offset < 0) {
						if (!metadata.relativeToCurrent) {
							this.consumer.seekToEnd(Arrays.asList(topicPartition));
						}
						newOffset = Math.max(0, this.consumer.position(topicPartition) + offset);
					}
					else if (metadata.relativeToCurrent) {
						newOffset = this.consumer.position(topicPartition) + offset;
					}

					try {
						this.consumer.seek(topicPartition, newOffset);
						if (this.logger.isDebugEnabled()) {
							this.logger.debug("Reset " + topicPartition + " to offset " + newOffset);
						}
					}
					catch (Exception e) {
						this.logger.error("Failed to set initial offset for " + topicPartition
								+ " at " + newOffset + ". Position is " + this.consumer.position(topicPartition), e);
					}
				}
			}
		}

		private void updatePendingOffsets() {
			ConsumerRecord<K, V> record = this.acks.poll();
			while (record != null) {
				addOffset(record);
				record = this.acks.poll();
			}
		}

		private void addOffset(ConsumerRecord<K, V> record) {
			if (!this.offsets.containsKey(record.topic())) {
				this.offsets.put(record.topic(), new HashMap<Integer, Long>());
			}

			Map<Integer, Long> highestOffsetMap = this.offsets.get(record.topic());
			Long offset = highestOffsetMap.get(record.partition());

			if (offset == null || record.offset() > offset) {
				highestOffsetMap.put(record.partition(), record.offset());
			}
		}

		private void commitIfNecessary() {
			Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
			for (Entry<String, Map<Integer, Long>> entry : this.offsets.entrySet()) {
				for (Entry<Integer, Long> offset : entry.getValue().entrySet()) {
					commits.put(new TopicPartition(entry.getKey(), offset.getKey()),
							new OffsetAndMetadata(offset.getValue() + 1));
				}
			}
			this.offsets.clear();
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Commit list: " + commits);
			}
			if (!commits.isEmpty()) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Committing: " + commits);
				}
				try {
					if (this.containerProperties.isSyncCommits()) {
						this.consumer.commitSync(commits);
					}
					else {
						this.consumer.commitAsync(commits, this.commitCallback);
					}
				}
				catch (WakeupException e) {
					// ignore - not polling
					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Woken up during commit");
					}
				}
			}
		}

		private Collection<ConsumerRecord<K, V>> getHighestOffsetRecords(List<ConsumerRecord<K, V>> records) {
			Map<TopicPartition, ConsumerRecord<K, V>> highestOffsetMap = new HashMap<>();
			for (ConsumerRecord<K, V> record : records) {
				TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
				ConsumerRecord<K, V> consumerRecord = highestOffsetMap.get(topicPartition);
				if (consumerRecord == null || record.offset() > consumerRecord.offset()) {
					highestOffsetMap.put(topicPartition, record);
				}
			}
			return highestOffsetMap.values();
		}

		@Override
		public void seek(String topic, int partition, long offset) {
			this.seeks.add(new TopicPartitionInitialOffset(topic, partition, offset));
		}

		@Override
		public void seekToBeginning(String topic, int partition) {
			this.seeks.add(new TopicPartitionInitialOffset(topic, partition, SeekPosition.BEGINNING));
		}

		@Override
		public void seekToEnd(String topic, int partition) {
			this.seeks.add(new TopicPartitionInitialOffset(topic, partition, SeekPosition.END));
		}

		private final class ConsumerAcknowledgment implements Acknowledgment {

			private final ConsumerRecord<K, V> record;

			private ConsumerAcknowledgment(ConsumerRecord<K, V> record) {
				this.record = record;
			}

			@Override
			public void acknowledge() {
				Assert.state(ListenerConsumer.this.isAnyManualAck,
						"A manual ackmode is required for an acknowledging listener");
				processAck(this.record);
			}

			@Override
			public String toString() {
				return "Acknowledgment for " + this.record;
			}

		}

		private final class ConsumerBatchAcknowledgment implements Acknowledgment {

			private final List<ConsumerRecord<K, V>> records;

			private ConsumerBatchAcknowledgment(List<ConsumerRecord<K, V>> records) {
				// make a copy in case the listener alters the list
				this.records = new LinkedList<ConsumerRecord<K, V>>(records);
			}

			@Override
			public void acknowledge() {
				Assert.state(ListenerConsumer.this.isAnyManualAck,
						"A manual ackmode is required for an acknowledging listener");
				for (ConsumerRecord<K, V> record : getHighestOffsetRecords(this.records)) {
					processAck(record);
				}
			}

			@Override
			public String toString() {
				return "Acknowledgment for " + this.records;
			}

		}

	}

	private static final class LoggingCommitCallback implements OffsetCommitCallback {

		private static final Log logger = LogFactory.getLog(LoggingCommitCallback.class);

		LoggingCommitCallback() {
			super();
		}

		@Override
		public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
			if (exception != null) {
				logger.error("Commit failed for " + offsets, exception);
			}
			else if (logger.isDebugEnabled()) {
				logger.debug("Commits for " + offsets + " completed");
			}
		}

	}

	private static final class OffsetMetadata {

		private final Long offset;

		private final boolean relativeToCurrent;

		OffsetMetadata(Long offset, boolean relativeToCurrent) {
			this.offset = offset;
			this.relativeToCurrent = relativeToCurrent;
		}

	}

}
