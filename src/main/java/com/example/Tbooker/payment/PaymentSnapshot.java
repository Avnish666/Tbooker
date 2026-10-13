package com.example.Tbooker.payment;

import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.entity.PaymentConfirmationStatus;

public record PaymentSnapshot(Long id, Long userId, Long reservationId, Long showSeatId,
		PaymentCommand command, PaymentStatus status, PaymentConfirmationStatus confirmationStatus) { }
