package com.example.Tbooker.service;

import com.example.Tbooker.dto.PaymentRequest;
import com.example.Tbooker.dto.PaymentResponse;
import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.payment.PaymentStrategyRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration is deliberately outside database transactions. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentService {
	private final PaymentTransactions transactions;
	private final PaymentStrategyRegistry registry;
	private final PaymentResultProcessor results;

	public PaymentService(PaymentTransactions transactions, PaymentStrategyRegistry registry, PaymentResultProcessor results) {
		this.transactions = transactions;
		this.registry = registry;
		this.results = results;
	}

	public record Initiation(PaymentResponse payment, boolean created) { }

	public Initiation initiate(Long userId, Long reservationId, PaymentRequest request, String key) {
		var prepared = transactions.prepare(userId, reservationId, request.method(), key);
		var payment = prepared.payment();
		if (payment.status() == PaymentStatus.PENDING) {
			// Retries use the same persisted gateway request ID, including after an uncertain response.
			registry.get(payment.command().method()).charge(payment.command());
			results.process(payment.id());
		} else if (payment.status() == PaymentStatus.SUCCESS) {
			results.confirmKnownSuccess(payment.id());
		}
		return new Initiation(transactions.get(payment.id(), userId), prepared.created());
	}
}
