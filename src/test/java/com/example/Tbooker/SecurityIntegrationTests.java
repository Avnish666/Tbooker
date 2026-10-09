package com.example.Tbooker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class SecurityIntegrationTests extends SecurityTestSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private static final String PASSWORD = "Correct-horse-57!";
	private final MockMvc mockMvc;
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final PasswordEncoder passwordEncoder;
	private final JwtDecoder decoder;
	private Long showId;
	private Long seatId;
	private Long sampleUserId;

	@Autowired
	SecurityIntegrationTests(MockMvc mockMvc, JdbcTemplate jdbc, ObjectMapper mapper,
			PasswordEncoder passwordEncoder, JwtDecoder decoder) {
		this.mockMvc = mockMvc;
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.passwordEncoder = passwordEncoder;
		this.decoder = decoder;
	}

	@BeforeEach
	void resetDedicatedDatabase() {
		jdbc.update("DELETE FROM tbooker.bookings");
		jdbc.update("UPDATE tbooker.show_seats SET status = 'AVAILABLE'");
		jdbc.update("DELETE FROM tbooker.users WHERE email NOT IN ('alex@example.test', 'priya@example.test')");
		sampleUserId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email = 'alex@example.test'", Long.class);
		showId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows", Long.class);
		seatId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats WHERE show_id = ?", Long.class, showId);
	}

	@Test
	void registrationStoresOnlySaltedBcryptHashAndAlwaysCreatesUser() throws Exception {
		register("New.User@Example.test", PASSWORD).andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("new.user@example.test"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
		register("second@example.test", PASSWORD).andExpect(status().isCreated());
		String hash = jdbc.queryForObject("SELECT password_hash FROM tbooker.users WHERE email = ?",
				String.class, "new.user@example.test");
		assertThat(hash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
		assertThat(jdbc.queryForObject("SELECT password_hash FROM tbooker.users WHERE email = ?",
				String.class, "second@example.test")).isNotEqualTo(hash);
	}

	@Test
	void duplicateEmailIsCaseInsensitiveAndDoesNotReplaceCredentials() throws Exception {
		register("duplicate@example.test", PASSWORD).andExpect(status().isCreated());
		String hash = jdbc.queryForObject("SELECT password_hash FROM tbooker.users WHERE email = ?",
				String.class, "duplicate@example.test");
		register("DUPLICATE@EXAMPLE.TEST", "Different-pass-82!").andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Email is already registered"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.users WHERE lower(email) = ?",
				Integer.class, "duplicate@example.test")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT password_hash FROM tbooker.users WHERE email = ?",
				String.class, "duplicate@example.test")).isEqualTo(hash);
	}

	@Test
	void simultaneousRegistrationsCannotDuplicateEmail() throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Registration start timed out");
				return register("race@example.test", PASSWORD).andReturn().getResponse().getStatus();
			})).toList();
			try {
				assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			}
			finally {
				start.countDown();
			}
			assertThat(List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(201, 409);
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.users WHERE email = 'race@example.test'",
				Integer.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "short", "                                                                ",
			"ééééééééééééééééééééééééééééééééééééé",
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijk"})
	void rejectsInvalidOrBcryptTruncatedPasswords(String password) throws Exception {
		register("invalid@example.test", password).andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.users WHERE email = 'invalid@example.test'",
				Integer.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"name\":\"User\",\"email\":\"bad-email\",\"password\":\"Correct-horse-57!\"}",
			"{\"name\":\" \",\"email\":\"ok@example.test\",\"password\":\"Correct-horse-57!\"}",
			"{\"name\":\"User\",\"email\":\"ok@example.test\",\"password\":null}",
			"{\"name\":\"User\",\"email\":\"ok@example.test\",\"password\":\"Correct-horse-57!\",\"role\":\"ADMIN\"}"})
	void rejectsInvalidRegistrationAndRoleInjection(String body) throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void successfulLoginIssuesValidatedTokenAndBookingUsesItsSubject() throws Exception {
		var registered = register("login@example.test", PASSWORD).andExpect(status().isCreated()).andReturn();
		long id = mapper.readTree(registered.getResponse().getContentAsString()).get("id").asLong();
		var result = login("LOGIN@EXAMPLE.TEST", PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().doesNotExist("Set-Cookie")).andReturn();
		String token = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
		var jwt = decoder.decode(token);
		assertThat(jwt.getSubject()).isEqualTo(Long.toString(id));
		assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
		assertThat(jwt.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
		assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
		book("Bearer " + token).andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value(id));
		assertThat(jdbc.queryForObject("SELECT user_id FROM tbooker.bookings", Long.class)).isEqualTo(id);
	}

	@Test
	void invalidPasswordAndUnknownEmailUseTheSameGenericFailure() throws Exception {
		register("login@example.test", PASSWORD).andExpect(status().isCreated());
		for (String email : List.of("login@example.test", "unknown@example.test", "alex@example.test")) {
			login(email, "Wrong-password-42!").andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.detail").value("Invalid email or password"))
					.andExpect(jsonPath("$.accessToken").doesNotExist());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"email\":\"ok@example.test\"}",
			"{\"email\":\"bad-email\",\"password\":\"Correct-horse-57!\"}",
			"{\"email\":\"ok@example.test\",\"password\":null}"})
	void malformedLoginIsRejectedWithoutIssuingToken(String body) throws Exception {
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc"})
	void invalidBookingLookupIdsReturnBadRequest(String id) throws Exception {
		mockMvc.perform(get("/api/bookings/{id}", id).header("Authorization", bearer(sampleUserId)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void publicCatalogueAndHealthDoNotRequireJwt() throws Exception {
		for (String path : List.of("/api/shows", "/api/shows/" + showId,
				"/api/shows/" + showId + "/seats", "/api/v1/health", "/actuator/health/readiness")) {
			mockMvc.perform(get(path)).andExpect(status().isOk());
		}
	}

	@Test
	void missingJwtCannotBookOrReadBookings() throws Exception {
		mockMvc.perform(post("/api/shows/{id}/bookings", showId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showSeatId\":" + seatId + "}"))
				.andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
		for (String path : List.of("/api/bookings", "/api/bookings/1")) {
			mockMvc.perform(get(path)).andExpect(status().isUnauthorized())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertUnchangedInventory();
	}

	@ParameterizedTest
	@ValueSource(strings = {"malformed", "wrong-key", "expired", "future", "issuer", "audience",
			"subject", "overflow-subject", "role", "missing-expiry", "missing-issued-at", "unsigned"})
	void rejectsInvalidJwtBeforeBooking(String scenario) throws Exception {
		var claims = claims(sampleUserId, "USER");
		var now = Instant.now();
		switch (scenario) {
			case "expired" -> claims.issuedAt(now.minusSeconds(600)).notBefore(now.minusSeconds(600)).expiresAt(now.minusSeconds(120));
			case "future" -> claims.issuedAt(now.plusSeconds(120)).notBefore(now.plusSeconds(120)).expiresAt(now.plusSeconds(900));
			case "issuer" -> claims.issuer("different-issuer");
			case "audience" -> claims.audience(List.of("different-service"));
			case "subject" -> claims.subject("not-a-user-id");
			case "overflow-subject" -> claims.subject("9223372036854775808");
			case "role" -> claims.claim("role", "SUPERUSER");
			case "missing-expiry" -> claims.claims(values -> values.remove("exp"));
			case "missing-issued-at" -> claims.claims(values -> values.remove("iat"));
			default -> { }
		}
		String token = sign(claims.build());
		if (scenario.equals("wrong-key")) {
			byte[] differentKey = new byte[32];
			new SecureRandom().nextBytes(differentKey);
			token = signWithKey(claims.build(), differentKey);
		}
		if (scenario.equals("malformed")) token = "not-a-jwt";
		if (scenario.equals("unsigned")) {
			token = "eyJhbGciOiJub25lIn0." + token.split("\\.")[1] + ".";
		}
		book("Bearer " + token).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.trace").doesNotExist());
		assertUnchangedInventory();
	}

	@Test
	void rejectsClientSuppliedBookingOwner() throws Exception {
		mockMvc.perform(post("/api/shows/{id}/bookings", showId).header("Authorization", bearer(sampleUserId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showSeatId\":" + seatId + ",\"userId\":9223372036854775807}"))
				.andExpect(status().isBadRequest());
		assertUnchangedInventory();
	}

	@Test
	void bookingListsAndDetailsAreRestrictedToOwnerEvenForAdmin() throws Exception {
		register("owner@example.test", PASSWORD).andExpect(status().isCreated());
		register("other@example.test", PASSWORD).andExpect(status().isCreated());
		String owner = loginToken("owner@example.test");
		String other = loginToken("other@example.test");
		var result = book(owner).andExpect(status().isCreated()).andReturn();
		long bookingId = mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
		mockMvc.perform(get("/api/bookings").header("Authorization", owner))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(bookingId));
		mockMvc.perform(get("/api/bookings/{id}", bookingId).header("Authorization", owner))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(bookingId));
		for (String token : List.of(other, bearer(sampleUserId, "ADMIN"))) {
			mockMvc.perform(get("/api/bookings").header("Authorization", token))
					.andExpect(status().isOk()).andExpect(content().json("[]"));
			mockMvc.perform(get("/api/bookings/{id}", bookingId).header("Authorization", token))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Booking " + bookingId + " was not found"));
		}
		mockMvc.perform(get("/api/bookings/{id}", Long.MAX_VALUE).header("Authorization", owner))
				.andExpect(status().isNotFound());
	}

	@Test
	void catalogueWritesRequireAdminButCrudRemainsUnimplemented() throws Exception {
		register("admin@example.test", PASSWORD).andExpect(status().isCreated());
		String originalUserToken = loginToken("admin@example.test");
		mockMvc.perform(post("/api/shows").header("Authorization", originalUserToken))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
		// Trusted operator provisioning is outside the public API. Re-login picks up the role.
		jdbc.update("UPDATE tbooker.users SET role = 'ADMIN' WHERE email = 'admin@example.test'");
		String admin = loginToken("admin@example.test");
		assertThat(decoder.decode(admin.substring(7)).getClaimAsString("role")).isEqualTo("ADMIN");
		mockMvc.perform(post("/api/shows").header("Authorization", admin))
				.andExpect(status().isMethodNotAllowed());
		mockMvc.perform(post("/api/shows").header("Authorization", originalUserToken))
				.andExpect(status().isForbidden());
		book(admin).andExpect(status().isCreated());
		mockMvc.perform(get("/api/bookings").header("Authorization", admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void bearerTokensAreNotAcceptedFromQueryParametersOrCookies() throws Exception {
		String token = bearer(sampleUserId).substring(7);
		mockMvc.perform(get("/api/bookings").param("access_token", token))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/bookings").cookie(new jakarta.servlet.http.Cookie("access_token", token)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void databaseRejectsInvalidRolesAndMalformedPasswordHashes() {
		assertThatThrownBy(() -> jdbc.update("UPDATE tbooker.users SET role = 'SUPERUSER' WHERE id = ?", sampleUserId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE tbooker.users SET password_hash = 'plaintext' WHERE id = ?", sampleUserId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void upgradingVersionThreePreservesLegacyUsersAndBookingsWithoutInventingPasswords() throws Exception {
		String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/phase6_upgrade";
		jdbc.execute("CREATE DATABASE phase6_upgrade");
		try {
			Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
					.defaultSchema("public").target("3").load().migrate();
			try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
					var statement = connection.createStatement()) {
				statement.execute(new ClassPathResource("db/dev/R__sample_catalogue.sql").getContentAsString(StandardCharsets.UTF_8));
				statement.execute("INSERT INTO tbooker.bookings (user_id, show_seat_id, status, created_at) "
						+ "SELECT (SELECT MIN(id) FROM tbooker.users), MIN(id), 'CONFIRMED', CURRENT_TIMESTAMP FROM tbooker.show_seats");
				statement.execute("UPDATE tbooker.show_seats SET status = 'BOOKED' WHERE id = (SELECT MIN(id) FROM tbooker.show_seats)");
			}
			var flyway = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).defaultSchema("public").load();
			assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
					var statement = connection.createStatement();
					var result = statement.executeQuery("SELECT COUNT(*) FROM tbooker.bookings b "
						+ "JOIN tbooker.users u ON u.id = b.user_id JOIN tbooker.show_seats s ON s.id = b.show_seat_id "
						+ "WHERE u.password_hash IS NULL AND u.role = 'USER' AND s.status = 'BOOKED' AND b.status = 'CONFIRMED'")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getInt(1)).isEqualTo(1);
			}
		}
		finally {
			jdbc.execute("DROP DATABASE phase6_upgrade WITH (FORCE)");
		}
	}

	private ResultActions register(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("name", "Test User", "email", email, "password", password))));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("email", email, "password", password))));
	}

	private String loginToken(String email) throws Exception {
		var response = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse();
		return "Bearer " + mapper.readTree(response.getContentAsString()).get("accessToken").asText();
	}

	private ResultActions book(String authorization) throws Exception {
		return mockMvc.perform(post("/api/shows/{id}/bookings", showId).header("Authorization", authorization)
				.contentType(MediaType.APPLICATION_JSON).content("{\"showSeatId\":" + seatId + "}"));
	}

	private void assertUnchangedInventory() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.bookings", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM tbooker.show_seats WHERE id = ?", String.class, seatId))
				.isEqualTo("AVAILABLE");
	}
}
