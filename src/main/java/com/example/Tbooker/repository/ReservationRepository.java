package com.example.Tbooker.repository;

import com.example.Tbooker.entity.Reservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	@EntityGraph(attributePaths = {"showSeat", "showSeat.movieShow", "booking"})
	Optional<Reservation> findByIdAndUserId(Long id, Long userId);

	// Scalar lookup avoids putting an entity into the persistence context before waiting for its seat lock.
	@Query("select r.showSeat.id from Reservation r where r.id = :id and r.user.id = :userId")
	Optional<Long> findOwnedSeatId(@Param("id") Long id, @Param("userId") Long userId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tbooker.reservations SET status = 'EXPIRED'
			WHERE show_seat_id = :seatId AND status = 'HELD' AND expires_at <= :now
			""", nativeQuery = true)
	int expire(@Param("seatId") Long seatId, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tbooker.reservations SET status = 'CONFIRMED', booking_id = :bookingId, confirmed_payment_id = :paymentId
			WHERE id = :id AND user_id = :userId AND status = 'HELD' AND expires_at > :now
			""", nativeQuery = true)
	int confirm(@Param("id") Long id, @Param("userId") Long userId,
			@Param("bookingId") Long bookingId, @Param("paymentId") Long paymentId, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tbooker.reservations SET status = 'CANCELLED'
			WHERE id = :id AND user_id = :userId AND status = 'HELD' AND expires_at > :now
			""", nativeQuery = true)
	int cancel(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

}
