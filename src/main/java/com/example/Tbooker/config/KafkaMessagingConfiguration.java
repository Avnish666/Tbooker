package com.example.Tbooker.config;

import com.example.Tbooker.event.InvalidBookingEventException;
import com.example.Tbooker.messaging.OutboxMetrics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Duration;

@Configuration
@EnableKafka
@ConditionalOnProperty(name="messaging.enabled", havingValue="true", matchIfMissing=true)
public class KafkaMessagingConfiguration {
    @Bean
    KafkaAdmin.NewTopics bookingTopics(MessagingProperties p) {
        return new KafkaAdmin.NewTopics(topic(p.topic(), p), topic(p.deadLetterTopic(), p));
    }
    private NewTopic topic(String name, MessagingProperties p) {
        return TopicBuilder.name(name).partitions(p.partitions()).replicas(p.replicas())
                .config("retention.ms", "604800000").config("cleanup.policy", "delete")
                // Leave room for diagnostic headers when copying any source record to the DLT.
                .config("max.message.bytes", name.equals(p.topic()) ? "65536" : "1048576").build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object,Object> bookingKafkaListenerContainerFactory(
            ConsumerFactory<Object,Object> consumers, KafkaTemplate<Object,Object> template,
            MessagingProperties p, OutboxMetrics metrics) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(p.deadLetterTopic(), record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(7));
        recoverer.setTimeoutBuffer(1000);
        recoverer.setLogRecoveryRecord(false);
        var backoff = new ExponentialBackOffWithMaxRetries(p.consumerRetries());
        backoff.setInitialInterval(200);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2000);
        var errors = new DefaultErrorHandler((ConsumerAwareRecordRecoverer) (record, consumer, exception) -> {
            recoverer.accept(record, consumer, exception); // Throws if DLT publication is not acknowledged.
            metrics.deadLettered();
        }, backoff);
        errors.addNotRetryableExceptions(InvalidBookingEventException.class, DataIntegrityViolationException.class);
        errors.setRetryListeners((record, exception, attempt) -> metrics.consumerFailed());
        var factory = new ConcurrentKafkaListenerContainerFactory<Object,Object>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.setMissingTopicsFatal(false);
        return factory;
    }

    @Bean
    ThreadPoolTaskExecutor outboxCompletionExecutor(MessagingProperties p) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(p.batchSize());
        executor.setThreadNamePrefix("outbox-completion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
