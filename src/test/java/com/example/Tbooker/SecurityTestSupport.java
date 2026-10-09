package com.example.Tbooker;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** Real signed test tokens and a random, process-local key; no authentication mocks. */
public abstract class SecurityTestSupport {

	private static final byte[] KEY = new byte[32];

	static {
		new SecureRandom().nextBytes(KEY);
	}

	@DynamicPropertySource
	static void jwtProperties(DynamicPropertyRegistry registry) {
		registry.add("security.jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
		registry.add("security.jwt.issuer", () -> "tbooker");
		registry.add("security.jwt.audience", () -> "tbooker-api");
		registry.add("security.jwt.ttl", () -> "PT15M");
	}

	protected String bearer(Long userId) {
		return bearer(userId, "USER");
	}

	protected String bearer(Long userId, String role) {
		return "Bearer " + sign(claims(userId, role).build());
	}

	protected JwtClaimsSet.Builder claims(Long userId, String role) {
		var now = Instant.now();
		return JwtClaimsSet.builder().issuer("tbooker").subject(userId.toString())
				.audience(List.of("tbooker-api")).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(900))
				.claim("role", role);
	}

	protected String sign(JwtClaimsSet claims) {
		return signWithKey(claims, KEY);
	}

	protected String signWithKey(JwtClaimsSet claims, byte[] key) {
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}
}
