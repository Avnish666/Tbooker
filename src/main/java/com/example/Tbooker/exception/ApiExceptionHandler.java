package com.example.Tbooker.exception;

import org.hibernate.exception.ConstraintViolationException;
import com.example.Tbooker.config.OperationalFailures;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private final MeterRegistry metrics;
	private final OperationalFailures failures;

	public ApiExceptionHandler(MeterRegistry metrics, OperationalFailures failures) {
		this.metrics = metrics;
		this.failures = failures;
	}

	@ExceptionHandler(PaymentConflictException.class)
	public ProblemDetail handlePaymentConflict(PaymentConflictException exception) {
		metrics.counter("payments.conflicts").increment();
		return problem(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(GatewayVerificationException.class)
	public ProblemDetail handleGatewayVerification(GatewayVerificationException exception) {
		metrics.counter("payments.verification.failures").increment();
		return problem(HttpStatus.BAD_GATEWAY, "The payment provider result could not be verified; retry the original payment key");
	}

	@ExceptionHandler(ReservationConflictException.class)
	ProblemDetail handleReservationConflict(ReservationConflictException exception) {
		metrics.counter("reservations.conflicts").increment();
		return problem(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(DuplicateEmailException.class)
	public ProblemDetail handleDuplicateEmail(DuplicateEmailException exception) {
		return problem(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ProblemDetail handleBadCredentials(BadCredentialsException exception) {
		return problem(HttpStatus.UNAUTHORIZED, "Invalid email or password");
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleResourceNotFound(ResourceNotFoundException exception) {
		return problem(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler(InvalidBookingRequestException.class)
	public ProblemDetail handleInvalidBooking(InvalidBookingRequestException exception) {
		return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(SeatAlreadyBookedException.class)
	public ProblemDetail handleAlreadyBooked(SeatAlreadyBookedException exception) {
		return problem(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& "23505".equals(violation.getSQLState())
					&& "uq_reservations_held_seat".equals(violation.getConstraintName())) {
				metrics.counter("reservations.conflicts").increment();
				return problem(HttpStatus.CONFLICT, "This show seat already has a hold");
			}
			if (cause instanceof ConstraintViolationException violation
					&& "23505".equals(violation.getSQLState())
					&& "uq_users_email".equals(violation.getConstraintName())) {
				return problem(HttpStatus.CONFLICT, "Email is already registered");
			}
			if (cause instanceof ConstraintViolationException violation
					&& "23505".equals(violation.getSQLState())
					&& "uq_bookings_show_seat".equals(violation.getConstraintName())) {
				return problem(HttpStatus.CONFLICT,
						"This show seat already has a confirmed booking");
			}
		}
		// Other constraint failures are not evidence that the seat is already booked.
		failures.record(OperationalFailures.Operation.DATABASE_REQUEST, exception);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR,
				"The request could not be completed");
	}

	@ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
	public ProblemDetail handleDatabaseFailure(RuntimeException exception) {
		failures.record(OperationalFailures.Operation.DATABASE_REQUEST, exception);
		boolean unavailable = exception instanceof DataAccessResourceFailureException
				|| exception instanceof TransientDataAccessException
				|| exception instanceof CannotCreateTransactionException;
		return problem(unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
				"The request could not be completed; check its outcome before retrying");
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (body instanceof ProblemDetail detail) detail.setProperty("correlationId", MDC.get("correlationId"));
		return super.createResponseEntity(body, headers, status, request);
	}

	private ProblemDetail problem(HttpStatus status, String message) {
		var detail = ProblemDetail.forStatusAndDetail(status, message);
		detail.setProperty("correlationId", MDC.get("correlationId"));
		return detail;
	}

}
