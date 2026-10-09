package com.example.Tbooker.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresIn) {
	@Override
	public String toString() {
		return "AuthResponse[token redacted]";
	}
}
