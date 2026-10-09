package com.example.Tbooker.exception;

public class SeatAlreadyBookedException extends RuntimeException {

	public SeatAlreadyBookedException(Long showSeatId) {
		super("Show seat " + showSeatId + " is already booked");
	}

}
