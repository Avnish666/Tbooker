package com.example.Tbooker.dto;

import com.example.Tbooker.entity.PaymentMethod;
import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.entity.PaymentConfirmationStatus;
import java.time.Instant;

public record PaymentResponse(Long id, Long reservationId, PaymentMethod method, long amountMinor,
		String currency, PaymentStatus status, PaymentConfirmationStatus confirmationStatus,
		String providerReference, Long bookingId, Instant createdAt, Instant updatedAt) { }
