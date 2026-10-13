package com.example.Tbooker.messaging;

import com.example.Tbooker.config.MessagingProperties;
import com.example.Tbooker.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Component
@ConditionalOnProperty(name="messaging.enabled", havingValue="true", matchIfMissing=true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final KafkaOperations<Object,Object> kafka;
    private final MessagingProperties properties;
    private final OutboxMetrics metrics;
    private final Executor completions;
    // Local backpressure only. Cross-instance correctness comes from PostgreSQL leases and fencing.
    private final Semaphore capacity;

    public OutboxPublisher(OutboxRepository outbox, KafkaOperations<Object,Object> kafka,
            MessagingProperties properties, OutboxMetrics metrics,
            @Qualifier("outboxCompletionExecutor") Executor completions) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.completions = completions;
        this.capacity = new Semaphore(properties.batchSize());
    }

    @Transactional(propagation=Propagation.NEVER)
    public int publishBatch() {
        int slots = capacity.drainPermits();
        if (slots == 0) { return 0; }
        List<OutboxRepository.Claim> claims;
        try { claims = outbox.claim(slots, properties.lease()); }
        catch (RuntimeException failure) { capacity.release(slots); throw failure; }
        capacity.release(slots - claims.size());
        for (var claim : claims) {
            try {
                kafka.send(properties.topic(), claim.bookingId().toString(), claim.payload().getBytes(StandardCharsets.UTF_8))
                        .whenCompleteAsync((result, error) -> finish(claim, error), completions);
            } catch (RuntimeException failure) { finish(claim, failure); }
        }
        return claims.size();
    }

    public int inFlightCount() { return properties.batchSize() - capacity.availablePermits(); }

    private void finish(OutboxRepository.Claim claim, Throwable error) {
        try {
            if (error == null) {
                if (outbox.published(claim)) { metrics.published(claim.createdAt()); }
            } else {
                metrics.publishFailed();
                outbox.failed(claim, properties.retryDelay(claim.attempts()), error.getClass().getSimpleName());
            }
        } catch (RuntimeException persistenceFailure) {
            // Acknowledgment may have succeeded. Do not assume failure means Kafka did not accept it.
            // Leave the lease to expire; a later publisher resends with the SAME event ID.
            metrics.stateFailed();
            log.warn("Outbox state update failed for event {}; lease recovery will retry ({})",
                    claim.eventId(), persistenceFailure.getClass().getSimpleName());
        } finally { capacity.release(); }
    }
}
