package com.example.Tbooker.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("messaging")
public record MessagingProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("tbooker.booking-confirmed.v1") @Pattern(regexp="[a-zA-Z0-9][a-zA-Z0-9._-]{0,248}") String topic,
        @DefaultValue("tbooker.booking-confirmed.v1.DLT") @Pattern(regexp="[a-zA-Z0-9][a-zA-Z0-9._-]{0,248}") String deadLetterTopic,
        @DefaultValue("tbooker-notifications-v1") @NotBlank String notificationGroup,
        @DefaultValue("tbooker-analytics-v1") @NotBlank String analyticsGroup,
        @DefaultValue("3") @Min(1) @Max(64) int partitions,
        @DefaultValue("1") @Min(1) @Max(9) int replicas,
        @DefaultValue("20") @Min(1) @Max(100) int batchSize,
        @DefaultValue("PT30S") Duration lease,
        @DefaultValue("PT1S") Duration retryInitial,
        @DefaultValue("PT1M") Duration retryMax,
        @DefaultValue("3") @Min(0) @Max(10) int consumerRetries) {
    @AssertTrue(message="Messaging names must be distinct and durations positive, ordered, and at most one day")
    public boolean isConfigurationValid() {
        return topic != null && deadLetterTopic != null && !topic.equals(deadLetterTopic)
                && notificationGroup != null && !notificationGroup.equals(analyticsGroup)
                && valid(lease) && valid(retryInitial) && valid(retryMax) && retryMax.compareTo(retryInitial) >= 0;
    }
    private boolean valid(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0 && value.compareTo(Duration.ofDays(1)) <= 0;
    }
    public Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(16, Math.max(0, attempt - 1));
        return Duration.ofMillis(Math.min(retryMax.toMillis(), retryInitial.toMillis() * multiplier));
    }
}
