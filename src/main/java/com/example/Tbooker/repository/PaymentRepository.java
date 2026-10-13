package com.example.Tbooker.repository;

import com.example.Tbooker.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
	Optional<Payment> findByIdAndUserId(Long id, Long userId);
	List<Payment> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	@Query(value = "SELECT EXISTS(SELECT 1 FROM tbooker.payments WHERE reservation_id = :id AND status IN ('PENDING', 'SUCCESS'))", nativeQuery = true)
	boolean hasUnresolvedOrSuccessfulAttempt(@Param("id") Long reservationId);

	@Query(value = "SELECT id FROM tbooker.payments WHERE reservation_id = :id AND user_id = :userId AND status = 'SUCCESS' ORDER BY id LIMIT 1", nativeQuery = true)
	Optional<Long> findSuccessfulId(@Param("id") Long reservationId, @Param("userId") Long userId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO tbooker.payments (user_id, reservation_id, method, amount_minor, currency,
				idempotency_key, gateway_request_id, status, confirmation_status, created_at, updated_at)
			VALUES (:userId, :reservationId, :method, :amount, 'INR', :key, :requestId, 'PENDING', 'NOT_READY', :now, :now)
			ON CONFLICT ON CONSTRAINT uq_payments_owner_key DO NOTHING
			""", nativeQuery = true)
	int insertAttempt(@Param("userId") Long userId, @Param("reservationId") Long reservationId,
			@Param("method") String method, @Param("amount") long amount, @Param("key") String key,
			@Param("requestId") UUID requestId, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tbooker.payments SET status = :status, provider_reference = :reference, provider_version = :version,
				confirmation_status = CASE WHEN :status = 'SUCCESS' THEN 'PENDING' ELSE 'NOT_READY' END,
				updated_at = clock_timestamp()
			WHERE id = :id AND provider_version < :version AND status <> 'SUCCESS'
			""", nativeQuery = true)
	int recordOutcome(@Param("id") Long id, @Param("status") String status, @Param("reference") String reference,
			@Param("version") long version);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "UPDATE tbooker.payments SET confirmation_status = 'CONFIRMED', updated_at = clock_timestamp() WHERE id = :id AND status = 'SUCCESS'", nativeQuery = true)
	int markConfirmed(@Param("id") Long id);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tbooker.payments SET confirmation_status = 'RECONCILIATION_REQUIRED', updated_at = clock_timestamp()
			WHERE id = :id AND status = 'SUCCESS' AND confirmation_status <> 'CONFIRMED'
			""", nativeQuery = true)
	int markReconciliation(@Param("id") Long id);
}
