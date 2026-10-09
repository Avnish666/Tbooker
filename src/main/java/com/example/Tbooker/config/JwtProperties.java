package com.example.Tbooker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(@NotBlank String secret, @NotBlank String issuer,
		@NotBlank String audience, @NotNull Duration ttl) {

	public JwtProperties {
		if (ttl != null && (ttl.compareTo(Duration.ofMinutes(1)) < 0
				|| ttl.compareTo(Duration.ofHours(1)) > 0)) {
			throw new IllegalArgumentException("JWT TTL must be between one minute and one hour");
		}
	}

	@Override
	public String toString() {
		return "JwtProperties[signing key redacted]";
	}
}
