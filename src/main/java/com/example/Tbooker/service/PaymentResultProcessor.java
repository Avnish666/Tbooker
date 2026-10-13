package com.example.Tbooker.service;

import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.entity.PaymentConfirmationStatus;
import com.example.Tbooker.exception.PaymentConflictException;
import com.example.Tbooker.exception.ReservationConflictException;
import com.example.Tbooker.payment.PaymentStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Trusted internal notification/reconciliation boundary. There is deliberately no callback HTTP endpoint. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentResultProcessor {
	private static final Logger log = LoggerFactory.getLogger(PaymentResultProcessor.class);
	private final PaymentTransactions transactions;
	private final PaymentStrategyRegistry registry;
	private final ReservationService reservations;

	public PaymentResultProcessor(PaymentTransactions transactions, PaymentStrategyRegistry registry, ReservationService reservations) {
		this.transactions = transactions;
		this.registry = registry;
		this.reservations = reservations;
	}

	public void process(Long paymentId) {
		var payment = transactions.snapshot(paymentId);
		// Treat any notification as a hint: retrieve the authoritative provider result independently.
		var result = registry.get(payment.command().method()).verify(payment.command());
		transactions.recordVerified(paymentId, result); // Commits the financial outcome separately.
		confirmKnownSuccess(paymentId);
	}

	public void confirmKnownSuccess(Long paymentId) {
		var payment = transactions.snapshot(paymentId);
		if (payment.status() != PaymentStatus.SUCCESS || payment.confirmationStatus() == PaymentConfirmationStatus.CONFIRMED) { return; }
		try {
			reservations.confirmForPayment(payment.reservationId(), payment.userId(), payment.id());
		} catch (ReservationConflictException | PaymentConflictException unavailable) {
			transactions.requireReconciliation(paymentId);
		} catch (DataAccessException failure) {
			// The successful provider outcome is already committed. Leave PENDING for a safe retry.
			log.warn("Booking confirmation for payment {} requires retry after database failure", paymentId, failure);
		}
	}
}
