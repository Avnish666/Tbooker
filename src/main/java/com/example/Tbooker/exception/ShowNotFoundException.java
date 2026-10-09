package com.example.Tbooker.exception;

public class ShowNotFoundException extends ResourceNotFoundException {

	public ShowNotFoundException(Long showId) {
		super("Show", showId);
	}

}
