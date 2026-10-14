package com.example.Tbooker.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/** Count every failure, but bound warning volume and never log exception messages/SQL. */
@Component
public class OperationalFailures {

	public enum Operation { DATABASE_REQUEST, PAYMENT_CONFIRMATION, SCHEDULED_TASK }
	private static final Logger log = LoggerFactory.getLogger(OperationalFailures.class);
	private static final long INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
	private final MeterRegistry metrics;
	private final EnumMap<Operation, AtomicLong> nextWarning = new EnumMap<>(Operation.class);

	public OperationalFailures(MeterRegistry metrics) {
		this.metrics = metrics;
		for (Operation operation : Operation.values()) nextWarning.put(operation, new AtomicLong(System.nanoTime()));
	}

	public void record(Operation operation, Throwable failure) {
		metrics.counter("tbooker.operation.failures", "operation", operation.name()).increment();
		var next = nextWarning.get(operation);
		long now = System.nanoTime();
		long scheduled = next.get();
		if (now - scheduled >= 0 && next.compareAndSet(scheduled, now + INTERVAL_NANOS)) {
			log.warn("Operation {} failed ({}); warnings limited to one per operation per 30 seconds; inspect metrics and persisted state",
					operation, failure.getClass().getSimpleName());
		}
	}
}
