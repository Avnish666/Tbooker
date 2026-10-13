package com.example.Tbooker.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Separate messaging probe. Booking readiness remains readinessState + PostgreSQL. */
@Component("kafkaConnectivity")
public class KafkaConnectivityHealth implements HealthIndicator {
    private final KafkaAdmin admin;
    private final MessagingProperties properties;
    public KafkaConnectivityHealth(KafkaAdmin admin, MessagingProperties properties) {
        this.admin = admin; this.properties = properties;
    }
    @Override public Health health() {
        if (!properties.enabled()) { return Health.up().withDetail("enabled", false).build(); }
        var client = AdminClient.create(admin.getConfigurationProperties());
        try {
            client.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
            return Health.up().build();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return Health.down().build();
        } finally { client.close(Duration.ofMillis(200)); }
    }
}
