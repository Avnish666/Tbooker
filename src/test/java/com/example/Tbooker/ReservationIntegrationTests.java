package com.example.Tbooker;

import com.example.Tbooker.service.ReservationService;
import com.example.Tbooker.service.SeatInventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Import;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.hikari.maximum-pool-size=24", "reservations.cleanup.enabled=false"})
@Import(PaymentTestFixture.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
// Requests/services own real transactions. Cleanup is invoked explicitly for deterministic tests.
class ReservationIntegrationTests extends SecurityTestSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private final MockMvc mvc;
	private final PaymentTestFixture payments;
	private final JdbcTemplate jdbc;
	private final DataSource dataSource;
	private final ObjectMapper json;
	private final SeatInventoryService inventory;
	private final ReservationService reservations;
	private final TransactionTemplate transaction;
	private final Flyway flyway;
	private final int port;
	private Long showId;
	private Long seatId;
	private Long userId;
	private Long otherUserId;

	@Autowired
	ReservationIntegrationTests(MockMvc mvc, JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json,
			SeatInventoryService inventory, ReservationService reservations, PlatformTransactionManager transactions,
			Flyway flyway, PaymentTestFixture payments, @LocalServerPort int port) {
		this.mvc = mvc;
		this.payments = payments;
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.json = json;
		this.inventory = inventory;
		this.reservations = reservations;
		this.transaction = new TransactionTemplate(transactions);
		this.flyway = flyway;
		this.port = port;
	}

	@BeforeEach
	void reset() {
		payments.clear();
		jdbc.update("DELETE FROM tbooker.reservations");
		jdbc.update("DELETE FROM tbooker.bookings");
		jdbc.update("UPDATE tbooker.show_seats SET status = 'AVAILABLE'");
		showId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows", Long.class);
		seatId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats WHERE show_id = ?", Long.class, showId);
		userId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email = 'alex@example.test'", Long.class);
		otherUserId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email = 'priya@example.test'", Long.class);
	}

	@Test
	void createsFiveMinuteHoldWithPrincipalOwnershipAndPublicInventory() throws Exception {
		var response = hold(userId).andExpect(status().isCreated())
				.andExpect(header().exists("Location")).andReturn().getResponse();
		var body = json.readTree(response.getContentAsString());
		assertThat(body.get("status").asText()).isEqualTo("HELD");
		assertThat(Duration.between(Instant.parse(body.get("createdAt").asText()),
				Instant.parse(body.get("expiresAt").asText()))).isEqualTo(Duration.ofMinutes(5));
		assertThat(body.get("bookingId").isNull()).isTrue();
		assertThat(jdbc.queryForObject("SELECT user_id FROM tbooker.reservations", Long.class)).isEqualTo(userId);
		assertSeat("HELD", 0);
		mvc.perform(get("/api/shows/{id}/seats", showId)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].status").value("HELD"));
		mvc.perform(get(response.getHeader("Location")).header("Authorization", bearer(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(body.get("id").asLong()));
		hold(userId).andExpect(status().isConflict());
		hold(otherUserId).andExpect(status().isConflict());
		directBook(otherUserId).andExpect(status().isConflict());
		directBook(userId).andExpect(status().isConflict());
	}

	@Test
	void confirmsOnceAndRetriesReturnSamePermanentBooking() throws Exception {
		long id = holdId();
		JsonNode first = response(confirm(id, userId).andExpect(status().isOk()));
		assertThat(first.get("userId").asLong()).isEqualTo(userId);
		assertThat(first.get("showSeatId").asLong()).isEqualTo(seatId);
		assertThat(first.get("status").asText()).isEqualTo("CONFIRMED");
		makeExpired(id);
		assertThat(response(confirm(id, userId).andExpect(status().isOk()))).isEqualTo(first);
		assertThat(inventory.expireBatch()).isZero();
		cancel(id, userId).andExpect(status().isConflict());
		hold(otherUserId).andExpect(status().isConflict());
		assertSeat("BOOKED", 1);
		view(id, userId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.bookingId").value(first.get("id").asLong()));
		mvc.perform(get("/api/bookings/{id}", first.get("id").asLong()).header("Authorization", bearer(otherUserId)))
				.andExpect(status().isNotFound());
	}

	@Test
	void expirationDoesNotDependOnCleanupAndOldOwnerCannotDisturbNewHold() throws Exception {
		long oldId = holdId();
		makeExpired(oldId);
		view(oldId, userId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
		mvc.perform(get("/api/shows/{id}/seats", showId)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].status").value("AVAILABLE"));
		confirm(oldId, userId).andExpect(status().isConflict());
		cancel(oldId, userId).andExpect(status().isConflict());
		assertThat(count("bookings")).isZero();
		long next = response(hold(otherUserId).andExpect(status().isCreated())).get("id").asLong();
		assertThat(storedState(oldId)).isEqualTo("EXPIRED");
		confirm(oldId, userId).andExpect(status().isConflict());
		cancel(oldId, userId).andExpect(status().isConflict());
		assertThat(storedState(next)).isEqualTo("HELD");
		confirm(next, otherUserId).andExpect(status().isOk());
		assertSeat("BOOKED", 1);
	}

	@Test
	void expiredHoldRequiresAReplacementReservationAndVerifiedPayment() throws Exception {
		long id = holdId();
		makeExpired(id);
		directBook(otherUserId).andExpect(status().isConflict());
		long replacement = response(hold(otherUserId).andExpect(status().isCreated())).get("id").asLong();
		confirm(replacement, otherUserId).andExpect(status().isOk());
		assertThat(storedState(id)).isEqualTo("EXPIRED");
		confirm(id, userId).andExpect(status().isConflict());
		assertSeat("BOOKED", 1);
	}

	@Test
	void cancellationIsRepeatableAndDoesNotReleaseReplacementHold() throws Exception {
		long id = holdId();
		cancel(id, userId).andExpect(status().isNoContent());
		assertSeat("AVAILABLE", 0);
		view(id, userId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		long next = response(hold(otherUserId).andExpect(status().isCreated())).get("id").asLong();
		cancel(id, userId).andExpect(status().isNoContent());
		confirm(id, userId).andExpect(status().isConflict());
		assertSeat("HELD", 0);
		assertThat(storedState(next)).isEqualTo("HELD");
	}

	@Test
	void cleanupExpiresOnlyExpiredHoldsAndIsRepeatable() throws Exception {
		long id = holdId();
		assertThat(inventory.expireBatch()).isZero();
		makeExpired(id);
		assertThat(inventory.expireBatch()).isEqualTo(1);
		assertThat(inventory.expireBatch()).isZero();
		assertThat(storedState(id)).isEqualTo("EXPIRED");
		assertSeat("AVAILABLE", 0);
		hold(otherUserId).andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = {"USER", "ADMIN"})
	void ownershipIsRequiredForViewConfirmAndCancelEvenForAdmin(String role) throws Exception {
		long id = holdId();
		mvc.perform(get("/api/reservations/{id}", id).header("Authorization", bearer(otherUserId, role)))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/reservations/{id}/confirmation", id).header("Authorization", bearer(otherUserId, role)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/reservations/{id}", id).header("Authorization", bearer(otherUserId, role)))
				.andExpect(status().isNotFound());
		assertSeat("HELD", 0);
	}

	@Test
	void reservationRoutesRequireRealBearerAuthentication() throws Exception {
		long id = holdId();
		for (String token : List.of("", "Bearer malformed", "Bearer " + sign(claims(userId, "USER")
				.issuedAt(Instant.now().minusSeconds(600)).notBefore(Instant.now().minusSeconds(600))
				.expiresAt(Instant.now().minusSeconds(60)).build()))) {
			mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", token)
					.contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());
			mvc.perform(get("/api/reservations/{id}", id).header("Authorization", token)).andExpect(status().isUnauthorized());
			mvc.perform(post("/api/reservations/{id}/confirmation", id).header("Authorization", token)).andExpect(status().isUnauthorized());
			mvc.perform(delete("/api/reservations/{id}", id).header("Authorization", token)).andExpect(status().isUnauthorized());
		}
		assertSeat("HELD", 0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"showSeatId\":0}", "{\"showSeatId\":-1}", "{\"showSeatId\":null}",
			"{\"showSeatId\":1.5}", "{\"showSeatId\":1,\"userId\":1}", "{\"showSeatId\":1,\"expiresAt\":\"2030-01-01T00:00:00Z\"}"})
	void rejectsInvalidInputAndOwnerOrExpiryInjection(String request) throws Exception {
		mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(userId))
				.contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest());
		assertSeat("AVAILABLE", 0);
		assertThat(count("reservations")).isZero();
	}

	@Test
	void missingResourcesAndWrongShowAreRejected() throws Exception {
		hold(Long.MAX_VALUE).andExpect(status().isNotFound());
		mvc.perform(post("/api/shows/{id}/reservations", Long.MAX_VALUE).header("Authorization", bearer(userId))
				.contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isNotFound());
		mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(userId))
				.contentType(MediaType.APPLICATION_JSON).content("{\"showSeatId\":" + Long.MAX_VALUE + "}"))
				.andExpect(status().isNotFound());
		Long otherShow = jdbc.queryForObject("""
				INSERT INTO tbooker.movie_shows (movie_id, screen_id, start_time)
				SELECT movie_id, screen_id, start_time + interval '7 days' FROM tbooker.movie_shows
				WHERE id = ? RETURNING id
				""", Long.class, showId);
		try {
			mvc.perform(post("/api/shows/{id}/reservations", otherShow).header("Authorization", bearer(userId))
					.contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
		} finally {
			jdbc.update("DELETE FROM tbooker.movie_shows WHERE id = ?", otherShow);
		}
		view(Long.MAX_VALUE, userId).andExpect(status().isNotFound());
		confirm(Long.MAX_VALUE, userId).andExpect(status().isNotFound());
		cancel(Long.MAX_VALUE, userId).andExpect(status().isNotFound());
		assertSeat("AVAILABLE", 0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"reservations", "bookings", "confirmation"})
	void databaseFailuresRollBackAllStateAndPermitRetry(String stage) throws Exception {
		long id = stage.equals("reservations") ? 0 : holdId();
		String table = stage.equals("bookings") ? "bookings" : "reservations";
		String event = stage.equals("confirmation") ? "UPDATE" : "INSERT";
		jdbc.execute("""
				CREATE FUNCTION tbooker.fail_reservation_test() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'Forced rollback' USING ERRCODE = '23514'; END $$;
				CREATE TRIGGER fail_reservation_test AFTER %s ON tbooker.%s
				FOR EACH ROW EXECUTE FUNCTION tbooker.fail_reservation_test();
				""".formatted(event, table));
		try {
			if (id == 0) {
				hold(userId).andExpect(status().isInternalServerError());
				assertSeat("AVAILABLE", 0);
				assertThat(count("reservations")).isZero();
			} else {
				confirm(id, userId).andExpect(status().isInternalServerError());
				assertSeat("HELD", 0);
				assertThat(storedState(id)).isEqualTo("HELD");
			}
		} finally {
			jdbc.execute("DROP TRIGGER fail_reservation_test ON tbooker." + table);
			jdbc.execute("DROP FUNCTION tbooker.fail_reservation_test()");
		}
		if (id == 0) { id = holdId(); }
		confirm(id, userId).andExpect(status().isOk());
		assertSeat("BOOKED", 1);
	}

	@Test
	void databaseRejectsDuplicateHoldsAndMismatchedBookingOwnership() throws Exception {
		long id = holdId();
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO tbooker.reservations (user_id, show_seat_id, status, created_at, expires_at)
				SELECT ?, show_seat_id, status, created_at, expires_at FROM tbooker.reservations WHERE id = ?
				""", otherUserId, id)).isInstanceOf(DataIntegrityViolationException.class);
		long bookingId = response(confirm(id, userId).andExpect(status().isOk())).get("id").asLong();
		assertThatThrownBy(() -> jdbc.update("UPDATE tbooker.reservations SET user_id = ? WHERE booking_id = ?",
				otherUserId, bookingId)).isInstanceOf(DataIntegrityViolationException.class);
		assertSeat("BOOKED", 1);
	}

	@Test
	void repeatedMigrationsPreserveHoldAndBookingHistory() throws Exception {
		long id = holdId();
		confirm(id, userId).andExpect(status().isOk());
		var before = jdbc.queryForList("SELECT * FROM tbooker.reservations ORDER BY id");
		for (int i = 0; i < 3; i++) {
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
			assertThat(jdbc.queryForList("SELECT * FROM tbooker.reservations ORDER BY id")).isEqualTo(before);
		}
		assertSeat("BOOKED", 1);
	}

	@Test
	void upgradingV4PreservesPermanentBookingsAndCredentials() throws Exception {
		jdbc.execute("CREATE DATABASE phase7_upgrade");
		String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/phase7_upgrade";
		try {
			Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
					.defaultSchema("public").target("4").load().migrate();
			List<String> before = new ArrayList<>();
			String snapshot = "SELECT row_to_json(b)::text FROM tbooker.bookings b UNION ALL "
					+ "SELECT row_to_json(u)::text FROM tbooker.users u UNION ALL "
					+ "SELECT row_to_json(s)::text FROM tbooker.show_seats s ORDER BY 1";
			try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
					var statement = connection.createStatement()) {
				statement.execute(new ClassPathResource("db/dev/R__sample_catalogue.sql").getContentAsString(StandardCharsets.UTF_8));
				statement.execute("INSERT INTO tbooker.bookings (user_id, show_seat_id, status, created_at) "
						+ "SELECT (SELECT MIN(id) FROM tbooker.users), MIN(id), 'CONFIRMED', clock_timestamp() FROM tbooker.show_seats");
				statement.execute("UPDATE tbooker.show_seats SET status = 'BOOKED' WHERE id = (SELECT MIN(id) FROM tbooker.show_seats)");
				try (var rows = statement.executeQuery(snapshot)) { while (rows.next()) { before.add(rows.getString(1)); } }
			}
			var upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).defaultSchema("public").target("5").load();
			assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
			assertThat(upgrade.migrate().migrationsExecuted).isZero();
			List<String> after = new ArrayList<>();
			try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
					var statement = connection.createStatement(); var rows = statement.executeQuery(snapshot)) {
				while (rows.next()) { after.add(rows.getString(1)); }
			}
			assertThat(after).isEqualTo(before);
		} finally { jdbc.execute("DROP DATABASE phase7_upgrade WITH (FORCE)"); }
	}

	@Test
	void cleanupSkipsAnExpiredLockedSeatAndRevisitsItOnNextRun() throws Exception {
		long id = holdId();
		makeExpired(id);
		try (var blocker = dataSource.getConnection()) {
			lockSeat(blocker);
			assertThat(inventory.expireBatch()).isZero();
			assertThat(storedState(id)).isEqualTo("HELD");
			blocker.commit();
		}
		assertThat(inventory.expireBatch()).isEqualTo(1);
		assertSeat("AVAILABLE", 0);
	}

	@Test
	void concurrentReclaimsReplaceAnExpiredHoldOnlyOnce() throws Exception {
		long oldId = holdId();
		makeExpired(oldId);
		try (var client = HttpClient.newHttpClient()) {
			var results = contend(List.of(
					() -> send(client, "/api/shows/" + showId + "/reservations", userId, body()),
					() -> send(client, "/api/shows/" + showId + "/reservations", otherUserId, body())));
			assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
		}
		assertThat(storedState(oldId)).isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.reservations WHERE status = 'HELD'", Integer.class)).isEqualTo(1);
		assertThat(count("reservations")).isEqualTo(2);
		assertSeat("HELD", 0);
	}

	@RepeatedTest(3)
	void twentyHttpRequestsHoldExactlyOneSeat() throws Exception {
		List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
		try (var client = HttpClient.newHttpClient()) {
			for (int i = 0; i < 20; i++) {
				Long owner = i % 2 == 0 ? userId : otherUserId;
				requests.add(() -> send(client, "/api/shows/" + showId + "/reservations", owner, body()));
			}
			var results = contend(requests);
			assertThat(results.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
			assertThat(results.stream().filter(r -> r.statusCode() == 409)).hasSize(19);
		}
		assertThat(count("reservations")).isEqualTo(1);
		assertSeat("HELD", 0);
	}

	@Test
	void simultaneousConfirmationsReturnSameBooking() throws Exception {
		long id = holdId();
		payments.verifyOnly(userId, id);
		try (var client = HttpClient.newHttpClient()) {
			List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				requests.add(() -> send(client, "/api/reservations/" + id + "/confirmation", userId, ""));
			}
			var results = contend(requests);
			for (var result : results) { assertThat(result.statusCode()).isEqualTo(200); }
			assertThat(results.stream().map(HttpResponse::body).distinct()).hasSize(1);
		}
		assertSeat("BOOKED", 1);
	}

	@Test
	void directBookingAndHoldCannotBothWin() throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var results = contend(List.of(
					() -> send(client, "/api/shows/" + showId + "/reservations", userId, body()),
					() -> send(client, "/api/shows/" + showId + "/bookings", otherUserId, body())), 1);
			assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
			assertThat(count("bookings") + count("reservations")).isEqualTo(1);
		}
	}

	@Test
	void confirmationWaitingOnLockMustRecheckTimeAndCannotBeatExpiration() throws Exception {
		long id = holdId();
		payments.verifyOnly(userId, id);
		var executor = Executors.newFixedThreadPool(2);
		try (var blocker = dataSource.getConnection()) {
			lockSeat(blocker);
			var waiting = executor.submit(() -> confirm(id, userId).andReturn().getResponse().getStatus());
			awaitBlocked(1);
			// Expiry is after the waiting transaction began, but before it can acquire the row.
			try (var update = blocker.prepareStatement("UPDATE tbooker.reservations SET expires_at = clock_timestamp() WHERE id = ?")) {
				update.setLong(1, id);
				update.executeUpdate();
			}
			var cleanup = executor.submit(inventory::expireBatch);
			blocker.commit();
			assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo(409);
			cleanup.get(15, TimeUnit.SECONDS);
			inventory.expireBatch();
			assertThat(storedState(id)).isEqualTo("EXPIRED");
			assertSeat("AVAILABLE", 0);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void cleanupSkipsUncommittedConfirmationAndNeverReleasesBookedSeat() throws Exception {
		long id = holdId();
		payments.verifyOnly(userId, id);
		jdbc.update("UPDATE tbooker.reservations SET expires_at = clock_timestamp() + interval '2 seconds' WHERE id = ?", id);
		var executor = Executors.newSingleThreadExecutor();
		try {
			transaction.executeWithoutResult(tx -> {
				reservations.confirm(id, userId);
				// The confirmation decision was valid, but the deadline passes before commit.
				// Cleanup sees the previously committed HELD row as expired and must skip its locked seat.
				await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
						"SELECT expires_at <= clock_timestamp() FROM tbooker.reservations WHERE id = ?", Boolean.class, id));
				try {
					assertThat(executor.submit(inventory::expireBatch).get(10, TimeUnit.SECONDS)).isZero();
				} catch (Exception failure) { throw new IllegalStateException(failure); }
			});
			assertThat(inventory.expireBatch()).isZero();
			assertThat(storedState(id)).isEqualTo("CONFIRMED");
			assertSeat("BOOKED", 1);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void simultaneousCancelAndConfirmHaveOneTerminalOutcome() throws Exception {
		long id = holdId();
		payments.verifyOnly(userId, id);
		try (var client = HttpClient.newHttpClient()) {
			var results = contend(List.of(
					() -> send(client, "/api/reservations/" + id + "/confirmation", userId, ""),
					() -> client.send(HttpRequest.newBuilder(uri("/api/reservations/" + id))
							.header("Authorization", bearer(userId)).timeout(Duration.ofSeconds(30)).DELETE().build(),
							HttpResponse.BodyHandlers.ofString())));
			assertThat(results.stream().filter(r -> r.statusCode() == 409)).hasSize(1);
			if (storedState(id).equals("CONFIRMED")) { assertSeat("BOOKED", 1); }
			else { assertThat(storedState(id)).isEqualTo("CANCELLED"); assertSeat("AVAILABLE", 0); }
		}
	}

	private List<HttpResponse<String>> contend(List<Callable<HttpResponse<String>>> calls) throws Exception {
		return contend(calls, calls.size());
	}

	private List<HttpResponse<String>> contend(List<Callable<HttpResponse<String>>> calls, int expectedBlocked) throws Exception {
		var executor = Executors.newFixedThreadPool(calls.size());
		var ready = new CountDownLatch(calls.size());
		var start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> futures = new ArrayList<>();
		try (var blocker = dataSource.getConnection()) {
			lockSeat(blocker);
			for (var call : calls) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Start latch timeout"); }
					return call.call();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			awaitBlocked(expectedBlocked);
			blocker.commit();
			List<HttpResponse<String>> responses = new ArrayList<>();
			for (var future : futures) { responses.add(future.get(30, TimeUnit.SECONDS)); }
			return responses;
		} finally {
			start.countDown();
			futures.forEach(future -> future.cancel(true));
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void lockSeat(Connection connection) throws Exception {
		connection.setAutoCommit(false);
		try (var query = connection.prepareStatement("SELECT id FROM tbooker.show_seats WHERE id = ? FOR UPDATE")) {
			query.setLong(1, seatId);
			try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); }
		}
	}

	private void awaitBlocked(int expected) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		int blocked = 0;
		while (System.nanoTime() < deadline) {
			blocked = jdbc.queryForObject("""
					SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()
					AND wait_event_type = 'Lock' AND query LIKE '%tbooker.show_seats%' AND query LIKE '%FOR UPDATE%'
					""", Integer.class);
			if (blocked == expected) { return; }
			Thread.sleep(25);
		}
		assertThat(blocked).as("all contenders reached PostgreSQL row locking").isEqualTo(expected);
	}

	private HttpResponse<String> send(HttpClient client, String path, Long owner, String body) throws Exception {
		return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", bearer(owner))
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
	private String body() { return "{\"showSeatId\":" + seatId + "}"; }
	private ResultActions hold(Long owner) throws Exception {
		return mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(owner))
				.contentType(MediaType.APPLICATION_JSON).content(body()));
	}
	private ResultActions directBook(Long owner) throws Exception {
		return mvc.perform(post("/api/shows/{id}/bookings", showId).header("Authorization", bearer(owner))
				.contentType(MediaType.APPLICATION_JSON).content(body()));
	}
	private long holdId() throws Exception { return response(hold(userId).andExpect(status().isCreated())).get("id").asLong(); }
	private ResultActions confirm(long id, Long owner) throws Exception {
		if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tbooker.reservations WHERE id=? AND user_id=? AND status='HELD' AND expires_at > clock_timestamp())",
				Boolean.class, id, owner) && jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.payments WHERE reservation_id=?", Integer.class, id) == 0) {
			payments.verifyOnly(owner, id);
		}
		return mvc.perform(post("/api/reservations/{id}/confirmation", id).header("Authorization", bearer(owner)));
	}
	private ResultActions cancel(long id, Long owner) throws Exception {
		return mvc.perform(delete("/api/reservations/{id}", id).header("Authorization", bearer(owner)));
	}
	private ResultActions view(long id, Long owner) throws Exception {
		return mvc.perform(get("/api/reservations/{id}", id).header("Authorization", bearer(owner)));
	}
	private JsonNode response(ResultActions action) throws Exception {
		return json.readTree(action.andReturn().getResponse().getContentAsString());
	}
	private void makeExpired(long id) {
		jdbc.update("UPDATE tbooker.reservations SET created_at = clock_timestamp() - interval '301 seconds', "
				+ "expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
	}
	private String storedState(long id) {
		return jdbc.queryForObject("SELECT status FROM tbooker.reservations WHERE id = ?", String.class, id);
	}
	private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM tbooker." + table, Integer.class); }
	private void assertSeat(String state, int bookings) {
		assertThat(jdbc.queryForObject("SELECT status FROM tbooker.show_seats WHERE id = ?", String.class, seatId)).isEqualTo(state);
		assertThat(count("bookings")).isEqualTo(bookings);
	}
}
