package com.example.Tbooker.service;

import com.example.Tbooker.config.MockPaymentProperties;
import com.example.Tbooker.dto.PaymentResponse;
import com.example.Tbooker.entity.Payment;
import com.example.Tbooker.entity.PaymentConfirmationStatus;
import com.example.Tbooker.entity.PaymentMethod;
import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.entity.ReservationStatus;
import jakarta.persistence.EntityManager;
import com.example.Tbooker.exception.GatewayVerificationException;
import com.example.Tbooker.exception.PaymentConflictException;
import com.example.Tbooker.exception.ReservationConflictException;
import com.example.Tbooker.exception.ResourceNotFoundException;
import com.example.Tbooker.payment.GatewayResult;
import com.example.Tbooker.payment.PaymentCommand;
import com.example.Tbooker.payment.PaymentSnapshot;
import com.example.Tbooker.repository.PaymentRepository;
import com.example.Tbooker.repository.ReservationRepository;
import com.example.Tbooker.repository.ShowSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Database work only. Gateway calls must remain outside this bean's transactions. */
@Service
public class PaymentTransactions {
	private final PaymentRepository payments;
	private final ReservationRepository reservations;
	private final ShowSeatRepository seats;
	private final SeatInventoryService inventory;
	private final MockPaymentProperties properties;
	private final EntityManager entityManager;

	public PaymentTransactions(PaymentRepository payments, ReservationRepository reservations,
			ShowSeatRepository seats, SeatInventoryService inventory, MockPaymentProperties properties,
			EntityManager entityManager) {
		this.payments = payments;
		this.reservations = reservations;
		this.seats = seats;
		this.inventory = inventory;
		this.properties = properties;
		this.entityManager = entityManager;
	}

	public record Prepared(PaymentSnapshot payment, boolean created) { }

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Prepared prepare(Long userId, Long reservationId, PaymentMethod method, String key) {
		if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) {
			throw new IllegalArgumentException("Idempotency-Key must contain 1-100 letters, digits, '.', '_', ':', or '-'");
		}
		var existing = payments.findByUserIdAndIdempotencyKey(userId, key);
		if (existing.isPresent()) { return replay(existing.get(), reservationId, method); }
		Long seatId = reservations.findOwnedSeatId(reservationId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));
		var now = inventory.lockAndExpire(seatId);
		// A concurrent identical initiation may have committed while this request waited.
		existing = payments.findByUserIdAndIdempotencyKey(userId, key);
		if (existing.isPresent()) { return replay(existing.get(), reservationId, method); }
		var reservation = reservations.findByIdAndUserId(reservationId, userId).orElseThrow();
		if (reservation.getStatus() != ReservationStatus.HELD || !reservation.getExpiresAt().isAfter(now)) {
			throw new ReservationConflictException("Payment requires an active reservation");
		}
		if (payments.hasUnresolvedOrSuccessfulAttempt(reservationId)) {
			throw new PaymentConflictException("This reservation already has a pending or successful payment; retry its original key");
		}
		int inserted = payments.insertAttempt(userId, reservationId, method.name(), properties.amountMinor(),
				key, UUID.randomUUID(), now);
		// The unique key also arbitrates same-owner keys used concurrently for different seats.
		var stored = payments.findByUserIdAndIdempotencyKey(userId, key).orElseThrow();
		var prepared = replay(stored, reservationId, method);
		return new Prepared(prepared.payment(), inserted == 1);
	}

	private Prepared replay(Payment payment, Long reservationId, PaymentMethod method) {
		if (!payment.getReservation().getId().equals(reservationId) || payment.getMethod() != method) {
			throw new PaymentConflictException("Idempotency-Key was already used with different parameters");
		}
		return new Prepared(snapshot(payment), false);
	}

	@Transactional(readOnly = true)
	public PaymentSnapshot snapshot(Long id) { return snapshot(required(id)); }

	@Transactional(readOnly = true)
	public PaymentResponse get(Long id, Long userId) {
		return response(payments.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment", id)));
	}

	@Transactional(readOnly = true)
	public List<PaymentResponse> history(Long userId) {
		return payments.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(this::response).toList();
	}

	// Internal trusted boundary: only the processor supplies independently verified provider data.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void recordVerified(Long id, GatewayResult result) {
		Long seatId = required(id).getReservation().getShowSeat().getId();
		seats.lockById(seatId).orElseThrow();
		// Clear any pre-lock managed data: all outcome decisions must see the committed winner.
		payments.flush();
		entityManager.clear();
		var payment = required(id);
		validateReceipt(payment, result);
		if (result.version() < payment.getProviderVersion()) { return; }
		if (result.version() == payment.getProviderVersion()) {
			if (result.status() != payment.getStatus()) {
				throw new GatewayVerificationException("Conflicting provider result at the same version");
			}
			return;
		}
		// A success is never undone by delayed failure/pending messages; refunds are a separate workflow.
		if (payment.getStatus() == PaymentStatus.SUCCESS) { return; }
		if (payment.getStatus() == PaymentStatus.FAILED && result.status() == PaymentStatus.PENDING) { return; }
		payments.recordOutcome(id, result.status().name(), result.providerReference(), result.version());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void requireReconciliation(Long id) {
		Long seatId = required(id).getReservation().getShowSeat().getId();
		seats.lockById(seatId).orElseThrow();
		payments.markReconciliation(id); // Cannot overwrite a concurrent successful confirmation.
	}

	private void validateReceipt(Payment payment, GatewayResult result) {
		if (result == null || !payment.getGatewayRequestId().equals(result.requestId())
				|| payment.getAmountMinor() != result.amountMinor() || !payment.getCurrency().equals(result.currency())
				|| result.status() == null || result.version() <= 0 || result.providerReference() == null
				|| result.providerReference().isBlank() || result.providerReference().length() > 100
				|| (payment.getProviderReference() != null && !payment.getProviderReference().equals(result.providerReference()))) {
			throw new GatewayVerificationException("Provider receipt does not match the payment attempt");
		}
	}

	private Payment required(Long id) {
		return payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment", id));
	}

	private PaymentSnapshot snapshot(Payment payment) {
		return new PaymentSnapshot(payment.getId(), payment.getUser().getId(), payment.getReservation().getId(),
				payment.getReservation().getShowSeat().getId(), new PaymentCommand(payment.getGatewayRequestId(),
				payment.getMethod(), payment.getAmountMinor(), payment.getCurrency()), payment.getStatus(), payment.getConfirmationStatus());
	}

	private PaymentResponse response(Payment payment) {
		var reservation = payment.getReservation();
		Long bookingId = payment.getConfirmationStatus() == PaymentConfirmationStatus.CONFIRMED
				&& reservation.getBooking() != null ? reservation.getBooking().getId() : null;
		return new PaymentResponse(payment.getId(), reservation.getId(), payment.getMethod(), payment.getAmountMinor(),
				payment.getCurrency(), payment.getStatus(), payment.getConfirmationStatus(), payment.getProviderReference(),
				bookingId, payment.getCreatedAt(), payment.getUpdatedAt());
	}
}
