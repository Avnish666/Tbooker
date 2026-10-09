package com.example.Tbooker.service;

import com.example.Tbooker.config.JwtProperties;
import com.example.Tbooker.dto.AuthResponse;
import com.example.Tbooker.entity.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {

	private final JwtEncoder encoder;
	private final JwtProperties properties;
	private final Clock clock;

	public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public AuthResponse issue(User user) {
		var now = clock.instant();
		var claims = JwtClaimsSet.builder().issuer(properties.issuer())
				.subject(user.getId().toString()).audience(List.of(properties.audience()))
				.issuedAt(now).notBefore(now).expiresAt(now.plus(properties.ttl()))
				.id(UUID.randomUUID().toString()).claim("role", user.getRole().name()).build();
		String token = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
		return new AuthResponse(token, "Bearer", properties.ttl().toSeconds());
	}
}
