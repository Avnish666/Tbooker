package com.example.Tbooker.controller;

import com.example.Tbooker.dto.BookingResponse;
import com.example.Tbooker.dto.ReservationRequest;
import com.example.Tbooker.dto.ReservationResponse;
import com.example.Tbooker.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api")
public class ReservationController {

	private final ReservationService service;

	public ReservationController(ReservationService service) {
		this.service = service;
	}

	@PostMapping("/shows/{showId}/reservations")
	public ResponseEntity<ReservationResponse> create(@PathVariable @Positive Long showId,
			@Valid @RequestBody ReservationRequest request, @AuthenticationPrincipal Jwt principal) {
		var response = service.create(showId, Long.valueOf(principal.getSubject()), request);
		return ResponseEntity.created(URI.create("/api/reservations/" + response.id())).body(response);
	}

	@GetMapping("/reservations/{id}")
	public ReservationResponse get(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt principal) {
		return service.get(id, Long.valueOf(principal.getSubject()));
	}

	@PostMapping("/reservations/{id}/confirmation")
	public BookingResponse confirm(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt principal) {
		return service.confirm(id, Long.valueOf(principal.getSubject()));
	}

	@GetMapping("/reservations/{id}/booking")
	public BookingResponse getBooking(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt principal) {
		return service.getBooking(id, Long.valueOf(principal.getSubject()));
	}

	@DeleteMapping("/reservations/{id}")
	public ResponseEntity<Void> cancel(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt principal) {
		service.cancel(id, Long.valueOf(principal.getSubject()));
		return ResponseEntity.noContent().build();
	}

}
