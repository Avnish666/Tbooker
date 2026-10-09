package com.example.Tbooker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.datasource.hikari.maximum-pool-size=24")
@ActiveProfiles("dev")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
// HTTP requests own their transactions; the test must not have an outer transaction.
class BookingConcurrencyIntegrationTests extends SecurityTestSupport {

	private static final int REQUEST_COUNT = 20;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private final JdbcTemplate jdbcTemplate;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;
	private final int port;

	private final List<Long> userIds = new ArrayList<>();
	private Long showId;
	private Long physicalSeatId;
	private Long showSeatId;

	@Autowired
	BookingConcurrencyIntegrationTests(JdbcTemplate jdbcTemplate, DataSource dataSource,
			ObjectMapper objectMapper, @LocalServerPort int port) {
		this.jdbcTemplate = jdbcTemplate;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
		this.port = port;
	}

	@BeforeEach
	void createIndependentFixture() {
		String runId = UUID.randomUUID().toString();
		showId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows", Long.class);
		Long screenId = jdbcTemplate.queryForObject("SELECT screen_id FROM tbooker.movie_shows WHERE id = ?",
				Long.class, showId);
		physicalSeatId = jdbcTemplate.queryForObject(
				"INSERT INTO tbooker.seats (screen_id, seat_number) VALUES (?, ?) RETURNING id",
				Long.class, screenId, "RACE-" + runId.substring(0, 8));
		showSeatId = jdbcTemplate.queryForObject(
				"INSERT INTO tbooker.show_seats (show_id, seat_id, screen_id) VALUES (?, ?, ?) RETURNING id",
				Long.class, showId, physicalSeatId, screenId);
		for (int i = 0; i < REQUEST_COUNT; i++) {
			userIds.add(jdbcTemplate.queryForObject(
					"INSERT INTO tbooker.users (name, email) VALUES (?, ?) RETURNING id",
					Long.class, "Concurrent User " + i, "race-" + runId + "-" + i + "@example.test"));
		}
	}

	@AfterEach
	void removeOnlyThisFixture() {
		if (showSeatId != null) {
			jdbcTemplate.update("DELETE FROM tbooker.bookings WHERE show_seat_id = ?", showSeatId);
			jdbcTemplate.update("DELETE FROM tbooker.show_seats WHERE id = ?", showSeatId);
		}
		if (physicalSeatId != null) {
			jdbcTemplate.update("DELETE FROM tbooker.seats WHERE id = ?", physicalSeatId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM tbooker.users WHERE id = ?", userId);
		}
	}

	@RepeatedTest(value = 3, name = "20 simultaneous bookings, repetition {currentRepetition}/{totalRepetitions}")
	void twentyRequestsConfirmExactlyOneBooking() throws Exception {
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tbooker.show_seats WHERE id = ?",
				String.class, showSeatId)).isEqualTo("AVAILABLE");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.bookings", Integer.class)).isZero();

		CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
		List<Future<BookingAttempt>> futures = new ArrayList<>();
		List<BookingAttempt> attempts = new ArrayList<>();
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1).build()) {
			// 20 request connections + this lock owner + the observer fit in the test-only pool of 24.
			try (var blocker = dataSource.getConnection()) {
				lockSeat(blocker);
				for (Long userId : userIds) {
					futures.add(executor.submit(() -> {
						ready.countDown();
						if (!start.await(15, TimeUnit.SECONDS)) {
							throw new IllegalStateException("Start latch was not released");
						}
						var request = HttpRequest.newBuilder()
								.uri(URI.create("http://127.0.0.1:" + port + "/api/shows/" + showId + "/bookings"))
								.timeout(Duration.ofSeconds(30))
								.header("Content-Type", "application/json")
								.header("Authorization", bearer(userId))
								.POST(HttpRequest.BodyPublishers.ofString(
										"{\"showSeatId\":" + showSeatId + "}"))
								.build();
						return new BookingAttempt(userId, client.send(request, HttpResponse.BodyHandlers.ofString()));
					}));
				}
				assertThat(ready.await(10, TimeUnit.SECONDS)).as("all 20 workers are ready").isTrue();
				start.countDown();
				awaitBlockedUpdates(REQUEST_COUNT);
				blocker.commit();

				long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
				for (Future<BookingAttempt> future : futures) {
					attempts.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
				}
			}
		}
		finally {
			start.countDown();
			for (Future<BookingAttempt> future : futures) {
				future.cancel(true);
			}
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("request executor terminates").isTrue();
		}

		assertThat(attempts).hasSize(REQUEST_COUNT);
		var winners = attempts.stream().filter(attempt -> attempt.response().statusCode() == 201).toList();
		var conflicts = attempts.stream().filter(attempt -> attempt.response().statusCode() == 409).toList();
		assertThat(winners).hasSize(1);
		assertThat(conflicts).hasSize(REQUEST_COUNT - 1);
		for (BookingAttempt conflict : conflicts) {
			assertThat(conflict.response().headers().firstValue("Content-Type").orElse(""))
					.startsWith("application/problem+json");
			var problem = objectMapper.readTree(conflict.response().body());
			assertThat(problem.get("status").asInt()).isEqualTo(409);
			assertThat(problem.get("detail").asText()).isEqualTo("Show seat " + showSeatId + " is already booked");
		}
		BookingAttempt winner = winners.getFirst();
		var booking = objectMapper.readTree(winner.response().body());
		assertThat(booking.get("status").asText()).isEqualTo("CONFIRMED");
		assertThat(booking.get("userId").asLong()).isEqualTo(winner.userId());
		assertThat(booking.get("showSeatId").asLong()).isEqualTo(showSeatId);
		assertThat(booking.get("showId").asLong()).isEqualTo(showId);

		// These observations use separate connections after every HTTP transaction completed.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.bookings", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForMap("SELECT id, user_id, status FROM tbooker.bookings WHERE show_seat_id = ?",
				showSeatId)).containsEntry("id", booking.get("id").asLong())
				.containsEntry("user_id", winner.userId()).containsEntry("status", "CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tbooker.show_seats WHERE id = ?",
				String.class, showSeatId)).isEqualTo("BOOKED");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM (
				    SELECT show_seat_id FROM tbooker.bookings GROUP BY show_seat_id HAVING COUNT(*) > 1
				) duplicates
				""", Integer.class)).isZero();
	}

	private void lockSeat(Connection blocker) throws Exception {
		blocker.setAutoCommit(false);
		try (var statement = blocker.prepareStatement("SELECT id FROM tbooker.show_seats WHERE id = ? FOR UPDATE")) {
			statement.setLong(1, showSeatId);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
			}
		}
	}

	private void awaitBlockedUpdates(int expected) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		int blocked = 0;
		while (System.nanoTime() < deadline) {
			blocked = jdbcTemplate.queryForObject("""
					SELECT COUNT(*) FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					AND query LIKE '%UPDATE tbooker.show_seats%'
					""", Integer.class);
			if (blocked == expected) {
				return;
			}
			Thread.sleep(25);
		}
		assertThat(blocked).as("all 20 independent requests reached the PostgreSQL update").isEqualTo(expected);
	}

	private record BookingAttempt(Long userId, HttpResponse<String> response) {
	}

}
