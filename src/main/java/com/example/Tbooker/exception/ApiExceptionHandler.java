package com.example.Tbooker.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(DuplicateEmailException.class)
	public ProblemDetail handleDuplicateEmail(DuplicateEmailException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ProblemDetail handleBadCredentials(BadCredentialsException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleResourceNotFound(ResourceNotFoundException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler(InvalidBookingRequestException.class)
	public ProblemDetail handleInvalidBooking(InvalidBookingRequestException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(SeatAlreadyBookedException.class)
	public ProblemDetail handleAlreadyBooked(SeatAlreadyBookedException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& "23505".equals(violation.getSQLState())
					&& "uq_users_email".equals(violation.getConstraintName())) {
				return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email is already registered");
			}
			if (cause instanceof ConstraintViolationException violation
					&& "23505".equals(violation.getSQLState())
					&& "uq_bookings_show_seat".equals(violation.getConstraintName())) {
				return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
						"This show seat already has a confirmed booking");
			}
		}
		// Other constraint failures are not evidence that the seat is already booked.
		log.error("Unexpected database integrity violation", exception);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"The request could not be completed");
	}

}
