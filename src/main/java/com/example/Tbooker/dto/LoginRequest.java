package com.example.Tbooker.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record LoginRequest(
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(max = 72) String password) {

	@JsonIgnore
	@AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
	public boolean isPasswordWithinBcryptLimit() {
		return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}

	@Override
	public String toString() {
		return "LoginRequest[credentials redacted]";
	}
}
