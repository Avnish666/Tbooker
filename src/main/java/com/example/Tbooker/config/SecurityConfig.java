package com.example.Tbooker.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	@Bean
	SecretKey jwtSigningKey(JwtProperties properties) {
		byte[] key;
		try {
			key = Base64.getDecoder().decode(properties.secret());
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("JWT_SECRET must be a Base64-encoded random signing key");
		}
		if (key.length < 32) {
			throw new IllegalArgumentException("JWT_SECRET must decode to at least 32 random bytes");
		}
		return new SecretKeySpec(key, "HmacSHA256");
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties, Clock clock) {
		var decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
		var timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(30));
		timestampValidator.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestampValidator,
				new JwtIssuerValidator(properties.issuer()), jwt -> validateClaims(jwt, properties, clock)));
		return decoder;
	}

	private OAuth2TokenValidatorResult validateClaims(Jwt jwt, JwtProperties properties, Clock clock) {
		boolean validSubject;
		try {
			validSubject = jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]*")
					&& Long.parseLong(jwt.getSubject()) > 0;
		}
		catch (NumberFormatException exception) {
			validSubject = false;
		}
		Object role = jwt.getClaims().get("role");
		Instant issuedAt = jwt.getIssuedAt();
		Instant expiresAt = jwt.getExpiresAt();
		if (validSubject && ("USER".equals(role) || "ADMIN".equals(role))
				&& jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
				&& issuedAt != null && expiresAt != null && expiresAt.isAfter(issuedAt)
				&& !issuedAt.isAfter(clock.instant().plusSeconds(30))) {
			return OAuth2TokenValidatorResult.success();
		}
		return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt ->
				List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
		String[] catalogue = {"/api/shows/**", "/api/movies/**", "/api/theatres/**", "/api/screens/**", "/api/seats/**"};
		return http
				// Only Authorization-header bearer tokens are supported, never cookie authentication.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
						.requestMatchers("/api/v1/health", "/actuator/health", "/actuator/health/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/shows", "/api/shows/*", "/api/shows/*/seats").permitAll()
						.requestMatchers(HttpMethod.HEAD, "/api/shows", "/api/shows/*", "/api/shows/*/seats").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/shows/*/bookings").hasAnyRole("USER", "ADMIN")
						.requestMatchers(HttpMethod.GET, "/api/bookings", "/api/bookings/*").hasAnyRole("USER", "ADMIN")
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, catalogue).hasRole("ADMIN")
						.requestMatchers(HttpMethod.PUT, catalogue).hasRole("ADMIN")
						.requestMatchers(HttpMethod.PATCH, catalogue).hasRole("ADMIN")
						.requestMatchers(HttpMethod.DELETE, catalogue).hasRole("ADMIN")
						.anyRequest().denyAll())
				.exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
						.authenticationEntryPoint(problems).accessDeniedHandler(problems))
				.build();
	}
}
