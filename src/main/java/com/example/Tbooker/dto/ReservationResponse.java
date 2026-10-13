package com.example.Tbooker.dto;

import com.example.Tbooker.entity.ReservationStatus;

import java.time.Instant;

public record ReservationResponse(Long id, Long showId, Long showSeatId, ReservationStatus status,
		Instant createdAt, Instant expiresAt, Long bookingId) {
}
