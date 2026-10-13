package com.example.Tbooker.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetrics {
    private final MeterRegistry meters;
    private final AtomicLong pending = new AtomicLong();
    public OutboxMetrics(MeterRegistry meters) {
        this.meters = meters;
        meters.gauge("booking.outbox.pending", pending);
    }
    public void pending(long count) { pending.set(count); }
    public void publishFailed() { meters.counter("booking.outbox.publish.failures").increment(); }
    public void stateFailed() { meters.counter("booking.outbox.state.failures").increment(); }
    public void published(Instant createdAt) {
        meters.timer("booking.outbox.publish.delay").record(Duration.ofMillis(Math.max(0, Duration.between(createdAt, Instant.now()).toMillis())));
    }
    public void consumerFailed() { meters.counter("booking.consumer.failures").increment(); }
    public void deadLettered() { meters.counter("booking.consumer.dead.letters").increment(); }
}
