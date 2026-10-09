package com.example.Tbooker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTests {

	@ParameterizedTest
	@ValueSource(strings = {"", "not base64", "c2hvcnQ="})
	void refusesEmptyMalformedOrShortSigningKeys(String secret) {
		var properties = new JwtProperties(secret, "tbooker", "tbooker-api", Duration.ofMinutes(15));
		assertThatThrownBy(() -> new SecurityConfig().jwtSigningKey(properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JWT_SECRET");
	}

	@ParameterizedTest
	@ValueSource(strings = {"PT0S", "PT30S", "PT61M"})
	void refusesUnboundedOrTooShortTokenLifetimes(String ttl) {
		assertThatThrownBy(() -> new JwtProperties("unused", "tbooker", "tbooker-api", Duration.parse(ttl)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TTL");
	}

	@Test
	void missingSecretCannotBindConfiguration() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
				.withPropertyValues("security.jwt.issuer=tbooker", "security.jwt.audience=tbooker-api", "security.jwt.ttl=PT15M")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void configurationStringNeverIncludesSigningSecret() {
		byte[] key = new byte[32];
		new java.security.SecureRandom().nextBytes(key);
		String secret = Base64.getEncoder().encodeToString(key);
		var properties = new JwtProperties(secret, "tbooker", "tbooker-api", Duration.ofMinutes(15));
		assertThat(properties.toString()).doesNotContain(secret);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JwtProperties.class)
	static class PropertiesConfiguration {
	}
}
