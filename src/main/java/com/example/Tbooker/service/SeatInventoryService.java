package com.example.Tbooker.service;

import com.example.Tbooker.exception.ResourceNotFoundException;
import com.example.Tbooker.repository.ReservationRepository;
import com.example.Tbooker.repository.ShowSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Lock inventory first; change reservation and booking state only while holding that lock. */
@Service
public class SeatInventoryService {

	private final ShowSeatRepository seats;
	private final ReservationRepository reservations;

	public SeatInventoryService(ShowSeatRepository seats, ReservationRepository reservations) {
		this.seats = seats;
		this.reservations = reservations;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Instant lockAndExpire(Long seatId) {
		seats.lockById(seatId).orElseThrow(() -> new ResourceNotFoundException("Show seat", seatId));
		// CURRENT_TIMESTAMP is transaction-start time and may be stale after a lock wait.
		Instant now = seats.databaseTime();
		expireLockedSeat(seatId, now);
		return now;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int expireBatch() {
		var expiredSeats = seats.lockExpiredSeats();
		int count = 0;
		for (Long seatId : expiredSeats) {
			count += expireLockedSeat(seatId, seats.databaseTime());
		}
		return count;
	}

	private int expireLockedSeat(Long seatId, Instant now) {
		int expired = reservations.expire(seatId, now);
		if (expired == 1 && seats.releaseHeld(seatId) != 1) {
			throw new IllegalStateException("Expired reservation has inconsistent seat inventory");
		}
		return expired;
	}

}
