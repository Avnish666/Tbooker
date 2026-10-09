package com.example.Tbooker.repository;

import com.example.Tbooker.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	@EntityGraph(attributePaths = {"showSeat", "showSeat.movieShow"})
	List<Booking> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	@EntityGraph(attributePaths = {"showSeat", "showSeat.movieShow"})
	Optional<Booking> findByIdAndUserId(Long id, Long userId);
}
