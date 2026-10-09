package com.example.Tbooker.exception;

public class InvalidBookingRequestException extends RuntimeException {

	public InvalidBookingRequestException(Long showId, Long showSeatId) {
		super("Show seat " + showSeatId + " does not belong to show " + showId);
	}

}
