package com.example.Tbooker.dto;

import com.example.Tbooker.entity.BookingStatus;

import java.time.Instant;

public record BookingResponse(Long id, Long userId, Long showId, Long showSeatId,
		BookingStatus status, Instant createdAt) {
}
