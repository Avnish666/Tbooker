package com.example.Tbooker.event;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable V1 fact; contains no email address, token, password, or payment credentials. */
public record BookingConfirmedEvent(@NotNull UUID eventId,
        @NotNull @Pattern(regexp = "BookingConfirmed") String eventType,
        @Min(1) @Max(1) int version, @NotNull @Positive Long bookingId,
        @NotNull @Positive Long userId, @NotNull @Positive Long showId,
        @NotNull @Positive Long showSeatId, @NotNull Instant occurredAt) {
    public static final String TYPE = "BookingConfirmed";
}
