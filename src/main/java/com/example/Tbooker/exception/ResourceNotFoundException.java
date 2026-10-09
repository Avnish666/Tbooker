package com.example.Tbooker.exception;

public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String resource, Long id) {
		super(resource + " " + id + " was not found");
	}

}
