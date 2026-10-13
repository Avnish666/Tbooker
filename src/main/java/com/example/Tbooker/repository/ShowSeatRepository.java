package com.example.Tbooker.repository;

import com.example.Tbooker.entity.ShowSeat;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

	interface Availability {
		Long getId();
		Long getSeatId();
		String getSeatNumber();
		String getStatus();
	}

	@Query(value = """
			SELECT s.id, s.seat_id AS seatId, p.seat_number AS seatNumber,
			    CASE WHEN s.status = 'HELD' AND r.expires_at <= clock_timestamp()
			         THEN 'AVAILABLE' ELSE s.status END AS status
			FROM tbooker.show_seats s JOIN tbooker.seats p ON p.id = s.seat_id
			LEFT JOIN tbooker.reservations r ON r.show_seat_id = s.id AND r.status = 'HELD'
			WHERE s.show_id = :showId ORDER BY p.seat_number, s.id
			""", nativeQuery = true)
	List<Availability> findAvailabilityByShowId(@Param("showId") Long showId);

	@Query(value = "SELECT id FROM tbooker.show_seats WHERE id = :id FOR UPDATE", nativeQuery = true)
	Optional<Long> lockById(@Param("id") Long id);

	@Query(value = "SELECT clock_timestamp()", nativeQuery = true)
	Instant databaseTime();

	@Query(value = """
			SELECT s.id FROM tbooker.show_seats s
			JOIN tbooker.reservations r ON r.show_seat_id = s.id
			WHERE s.status = 'HELD' AND r.status = 'HELD' AND r.expires_at <= clock_timestamp()
			ORDER BY r.expires_at, s.id LIMIT 100 FOR UPDATE OF s SKIP LOCKED
			""", nativeQuery = true)
	List<Long> lockExpiredSeats();

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "UPDATE tbooker.show_seats SET status = 'HELD' WHERE id = :id AND status = 'AVAILABLE'", nativeQuery = true)
	int holdIfAvailable(@Param("id") Long id);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "UPDATE tbooker.show_seats SET status = 'AVAILABLE' WHERE id = :id AND status = 'HELD'", nativeQuery = true)
	int releaseHeld(@Param("id") Long id);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "UPDATE tbooker.show_seats SET status = 'BOOKED' WHERE id = :id AND status = 'HELD'", nativeQuery = true)
	int bookHeld(@Param("id") Long id);

	@EntityGraph(attributePaths = "seat")
	List<ShowSeat> findByMovieShowIdOrderBySeatSeatNumberAscIdAsc(Long showId);

	// Must run inside the booking service's transaction. The row count decides the winner.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE tbooker.show_seats
			SET status = 'BOOKED'
			WHERE id = :showSeatId AND show_id = :showId AND status = 'AVAILABLE'
			""", nativeQuery = true)
	int bookIfAvailable(@Param("showId") Long showId, @Param("showSeatId") Long showSeatId);

}
