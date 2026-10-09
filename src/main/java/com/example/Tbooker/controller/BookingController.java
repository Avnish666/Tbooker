package com.example.Tbooker.controller;

import com.example.Tbooker.dto.BookingRequest;
import com.example.Tbooker.dto.BookingResponse;
import com.example.Tbooker.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BookingController {

	private final BookingService bookingService;

	public BookingController(BookingService bookingService) {
		this.bookingService = bookingService;
	}

	@PostMapping("/shows/{showId}/bookings")
	public ResponseEntity<BookingResponse> book(@PathVariable @Positive Long showId,
			@Valid @RequestBody BookingRequest request, @AuthenticationPrincipal Jwt principal) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(bookingService.book(showId, Long.valueOf(principal.getSubject()), request));
	}

	@GetMapping("/bookings")
	public List<BookingResponse> getBookings(@AuthenticationPrincipal Jwt principal) {
		return bookingService.getBookings(Long.valueOf(principal.getSubject()));
	}

	@GetMapping("/bookings/{bookingId}")
	public BookingResponse getBooking(@PathVariable @Positive Long bookingId, @AuthenticationPrincipal Jwt principal) {
		return bookingService.getBooking(bookingId, Long.valueOf(principal.getSubject()));
	}

}
