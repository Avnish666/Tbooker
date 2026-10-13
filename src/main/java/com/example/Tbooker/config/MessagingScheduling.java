package com.example.Tbooker.config;

import com.example.Tbooker.messaging.OutboxMetrics;
import com.example.Tbooker.messaging.OutboxPublisher;
import com.example.Tbooker.repository.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="messaging", name={"enabled","scheduling-enabled"}, havingValue="true", matchIfMissing=true)
public class MessagingScheduling {
    private final OutboxPublisher publisher;
    private final OutboxRepository outbox;
    private final OutboxMetrics metrics;
    private final KafkaAdmin admin;
    public MessagingScheduling(OutboxPublisher publisher, OutboxRepository outbox, OutboxMetrics metrics, KafkaAdmin admin) {
        this.publisher = publisher; this.outbox = outbox; this.metrics = metrics; this.admin = admin;
    }
    @Scheduled(fixedDelayString="${messaging.poll-ms:1000}", initialDelayString="${messaging.poll-ms:1000}")
    public void publish() { publisher.publishBatch(); }

    @Scheduled(fixedDelayString="${messaging.metrics-ms:10000}")
    public void sampleBacklog() { metrics.pending(outbox.pendingCount()); }

    @Scheduled(fixedDelayString="${messaging.topic-check-ms:30000}", initialDelayString="${messaging.topic-check-ms:30000}")
    public void ensureTopics() {
        // Recover topic provisioning if Kafka was absent during application startup.
        admin.initialize();
    }
}
