package com.example.Tbooker.controller;

import com.example.Tbooker.dto.PaymentRequest;
import com.example.Tbooker.dto.PaymentResponse;
import com.example.Tbooker.service.PaymentService;
import com.example.Tbooker.service.PaymentTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PaymentController {
	private final PaymentService service;
	private final PaymentTransactions transactions;
	public PaymentController(PaymentService service, PaymentTransactions transactions) {
		this.service = service;
		this.transactions = transactions;
	}

	@PostMapping("/reservations/{reservationId}/payments")
	public ResponseEntity<PaymentResponse> initiate(@PathVariable @Positive Long reservationId,
			@RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String key,
			@Valid @RequestBody PaymentRequest request, @AuthenticationPrincipal Jwt principal) {
		var result = service.initiate(Long.valueOf(principal.getSubject()), reservationId, request, key);
		return ResponseEntity.status(result.created() ? 201 : 200)
				.location(URI.create("/api/payments/" + result.payment().id())).body(result.payment());
	}

	@GetMapping("/payments/{id}")
	public PaymentResponse get(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt principal) {
		return transactions.get(id, Long.valueOf(principal.getSubject()));
	}

	@GetMapping("/payments")
	public List<PaymentResponse> history(@AuthenticationPrincipal Jwt principal) {
		return transactions.history(Long.valueOf(principal.getSubject()));
	}
}
