package com.example.Tbooker.service;

import com.example.Tbooker.dto.BookingRequest;
import com.example.Tbooker.dto.BookingResponse;
import com.example.Tbooker.entity.Booking;
import com.example.Tbooker.exception.InvalidBookingRequestException;
import com.example.Tbooker.exception.ResourceNotFoundException;
import com.example.Tbooker.exception.SeatAlreadyBookedException;
import com.example.Tbooker.exception.ShowNotFoundException;
import com.example.Tbooker.repository.BookingRepository;
import com.example.Tbooker.repository.MovieShowRepository;
import com.example.Tbooker.repository.ShowSeatRepository;
import com.example.Tbooker.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BookingService {

	private final UserRepository userRepository;
	private final MovieShowRepository movieShowRepository;
	private final ShowSeatRepository showSeatRepository;
	private final BookingRepository bookingRepository;

	public BookingService(UserRepository userRepository, MovieShowRepository movieShowRepository,
			ShowSeatRepository showSeatRepository, BookingRepository bookingRepository) {
		this.userRepository = userRepository;
		this.movieShowRepository = movieShowRepository;
		this.showSeatRepository = showSeatRepository;
		this.bookingRepository = bookingRepository;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BookingResponse book(Long showId, Long userId, BookingRequest request) {
		if (!movieShowRepository.existsById(showId)) {
			throw new ShowNotFoundException(showId);
		}
		if (!userRepository.existsById(userId)) {
			throw new ResourceNotFoundException("User", userId);
		}
		var showSeat = showSeatRepository.findById(request.showSeatId())
				.orElseThrow(() -> new ResourceNotFoundException("Show seat", request.showSeatId()));
		if (!showId.equals(showSeat.getMovieShow().getId())) {
			throw new InvalidBookingRequestException(showId, request.showSeatId());
		}

		// Do not decide availability from the entity loaded above: it can already be stale.
		if (showSeatRepository.bookIfAvailable(showId, request.showSeatId()) != 1) {
			throw new SeatAlreadyBookedException(request.showSeatId());
		}

		// The native update cleared the persistence context. Obtain fresh references by ID.
		var booking = new Booking(userRepository.getReferenceById(userId),
				showSeatRepository.getReferenceById(request.showSeatId()),
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		bookingRepository.saveAndFlush(booking);
		// The transaction interceptor commits before the controller can send 201.
		return new BookingResponse(booking.getId(), userId, showId, request.showSeatId(),
				booking.getStatus(), booking.getCreatedAt());
	}

	@Transactional(readOnly = true)
	public List<BookingResponse> getBookings(Long userId) {
		return bookingRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
				.map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public BookingResponse getBooking(Long bookingId, Long userId) {
		return bookingRepository.findByIdAndUserId(bookingId, userId).map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
	}

	private BookingResponse toResponse(Booking booking) {
		var showSeat = booking.getShowSeat();
		return new BookingResponse(booking.getId(), booking.getUser().getId(), showSeat.getMovieShow().getId(),
				showSeat.getId(), booking.getStatus(), booking.getCreatedAt());
	}

}
