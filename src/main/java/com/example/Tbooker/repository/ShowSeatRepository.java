package com.example.Tbooker.repository;

import com.example.Tbooker.entity.ShowSeat;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

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
