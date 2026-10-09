package com.example.Tbooker.exception;

public class DuplicateEmailException extends RuntimeException {
	public DuplicateEmailException() {
		super("Email is already registered");
	}
}
