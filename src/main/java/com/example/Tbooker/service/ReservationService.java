package com.example.Tbooker.service;

import com.example.Tbooker.dto.BookingResponse;
import com.example.Tbooker.dto.ReservationRequest;
import com.example.Tbooker.dto.ReservationResponse;
import com.example.Tbooker.entity.Booking;
import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.exception.PaymentConflictException;
import com.example.Tbooker.repository.PaymentRepository;
import com.example.Tbooker.entity.Reservation;
import com.example.Tbooker.entity.ReservationStatus;
import com.example.Tbooker.exception.InvalidBookingRequestException;
import com.example.Tbooker.exception.ReservationConflictException;
import com.example.Tbooker.exception.ResourceNotFoundException;
import com.example.Tbooker.exception.ShowNotFoundException;
import com.example.Tbooker.repository.BookingRepository;
import com.example.Tbooker.repository.MovieShowRepository;
import com.example.Tbooker.repository.ReservationRepository;
import com.example.Tbooker.repository.ShowSeatRepository;
import com.example.Tbooker.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ReservationService {

	private final ReservationRepository reservations;
	private final ShowSeatRepository seats;
	private final MovieShowRepository shows;
	private final UserRepository users;
	private final BookingRepository bookings;
	private final SeatInventoryService inventory;
	private final PaymentRepository payments;
	private final BookingEventRecorder events;

	public ReservationService(ReservationRepository reservations, ShowSeatRepository seats,
			MovieShowRepository shows, UserRepository users, BookingRepository bookings, SeatInventoryService inventory, PaymentRepository payments, BookingEventRecorder events) {
		this.reservations = reservations;
		this.seats = seats;
		this.shows = shows;
		this.users = users;
		this.bookings = bookings;
		this.inventory = inventory;
		this.payments = payments;
		this.events = events;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ReservationResponse create(Long showId, Long userId, ReservationRequest request) {
		if (!shows.existsById(showId)) {
			throw new ShowNotFoundException(showId);
		}
		if (!users.existsById(userId)) {
			throw new ResourceNotFoundException("User", userId);
		}
		var seat = seats.findById(request.showSeatId())
				.orElseThrow(() -> new ResourceNotFoundException("Show seat", request.showSeatId()));
		if (!seat.getMovieShow().getId().equals(showId)) {
			throw new InvalidBookingRequestException(showId, request.showSeatId());
		}
		Instant now = inventory.lockAndExpire(request.showSeatId());
		if (seats.holdIfAvailable(request.showSeatId()) != 1) {
			throw new ReservationConflictException("This show seat is held or already booked");
		}
		var reservation = new Reservation(users.getReferenceById(userId),
				seats.getReferenceById(request.showSeatId()), now, now.plusSeconds(300));
		reservations.saveAndFlush(reservation);
		return toResponse(reservation, now);
	}

	@Transactional(readOnly = true)
	public ReservationResponse get(Long id, Long userId) {
		var reservation = owned(id, userId);
		return toResponse(reservation, seats.databaseTime());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BookingResponse confirm(Long id, Long userId) {
		Long seatId = ownedSeatId(id, userId);
		Instant now = inventory.lockAndExpire(seatId);
		var reservation = owned(id, userId);
		// Grandfather existing Phase 7 confirmations; never fabricate a charge for history.
		if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
			return bookingResponse(reservation.getBooking());
		}
		requireActive(reservation, now);
		Long paymentId = payments.findSuccessfulId(id, userId)
				.orElseThrow(() -> new PaymentConflictException("A verified successful payment is required"));
		return confirmLocked(reservation, userId, paymentId, now);
	}

	/** Called only by the internal payment result processor; revalidates all persisted proof. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BookingResponse confirmForPayment(Long id, Long userId, Long paymentId) {
		Instant now = inventory.lockAndExpire(ownedSeatId(id, userId));
		return confirmLocked(owned(id, userId), userId, paymentId, now);
	}

	private BookingResponse confirmLocked(Reservation reservation, Long userId, Long paymentId, Instant now) {
		Long id = reservation.getId();
		var payment = payments.findByIdAndUserId(paymentId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
		if (!payment.getReservation().getId().equals(id) || payment.getStatus() != PaymentStatus.SUCCESS) {
			throw new PaymentConflictException("A verified successful payment for this reservation is required");
		}
		if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
			if (!paymentId.equals(reservation.getConfirmedPaymentId())) {
				throw new PaymentConflictException("Reservation was confirmed using another payment");
			}
			return bookingResponse(reservation.getBooking());
		}
		requireActive(reservation, now);
		Long seatId = reservation.getShowSeat().getId();
		if (seats.bookHeld(seatId) != 1) {
			throw new ReservationConflictException("This reservation no longer holds the seat");
		}
		var booking = bookings.saveAndFlush(new Booking(users.getReferenceById(userId), seats.getReferenceById(seatId), now));
		if (reservations.confirm(id, userId, booking.getId(), paymentId, now) != 1 || payments.markConfirmed(paymentId) != 1) {
			throw new ReservationConflictException("This reservation is no longer active");
		}
		var response = bookingResponse(bookings.findById(booking.getId()).orElseThrow());
		events.confirmed(response); // Same transaction as inventory, booking, reservation, and payment link.
		return response;
	}

	@Transactional(readOnly = true)
	public BookingResponse getBooking(Long id, Long userId) {
		var reservation = owned(id, userId);
		if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
			throw new ReservationConflictException("This reservation does not have a confirmed booking");
		}
		return bookingResponse(reservation.getBooking());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void cancel(Long id, Long userId) {
		Long seatId = ownedSeatId(id, userId);
		Instant now = inventory.lockAndExpire(seatId);
		var reservation = owned(id, userId);
		if (reservation.getStatus() == ReservationStatus.CANCELLED) {
			return;
		}
		requireActive(reservation, now);
		if (reservations.cancel(id, userId, now) != 1 || seats.releaseHeld(seatId) != 1) {
			throw new ReservationConflictException("This reservation is no longer active");
		}
	}

	private Long ownedSeatId(Long id, Long userId) {
		return reservations.findOwnedSeatId(id, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
	}

	private Reservation owned(Long id, Long userId) {
		return reservations.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
	}

	private void requireActive(Reservation reservation, Instant now) {
		if (reservation.getStatus() != ReservationStatus.HELD || !reservation.getExpiresAt().isAfter(now)) {
			throw new ReservationConflictException("This reservation is expired or no longer active");
		}
	}

	private ReservationResponse toResponse(Reservation reservation, Instant now) {
		var status = reservation.getStatus();
		if (status == ReservationStatus.HELD && !reservation.getExpiresAt().isAfter(now)) {
			status = ReservationStatus.EXPIRED;
		}
		return new ReservationResponse(reservation.getId(), reservation.getShowSeat().getMovieShow().getId(),
				reservation.getShowSeat().getId(), status, reservation.getCreatedAt(), reservation.getExpiresAt(),
				reservation.getBooking() == null ? null : reservation.getBooking().getId());
	}

	private BookingResponse bookingResponse(Booking booking) {
		return new BookingResponse(booking.getId(), booking.getUser().getId(),
				booking.getShowSeat().getMovieShow().getId(), booking.getShowSeat().getId(),
				booking.getStatus(), booking.getCreatedAt());
	}

}
