package com.example.Tbooker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "reservations.cleanup.delay-ms=50")
@ActiveProfiles("dev")
@Testcontainers
@DirtiesContext
class ReservationCleanupIntegrationTests extends SecurityTestSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private final JdbcTemplate jdbc;

	@Autowired
	ReservationCleanupIntegrationTests(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Test
	void scheduledCallbackReleasesExpiredSeatWithoutAnApiRequest() {
		Long seatId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats", Long.class);
		jdbc.update("UPDATE tbooker.show_seats SET status = 'HELD' WHERE id = ?", seatId);
		jdbc.update("""
				INSERT INTO tbooker.reservations (user_id, show_seat_id, status, created_at, expires_at)
				VALUES ((SELECT MIN(id) FROM tbooker.users), ?, 'HELD',
				    clock_timestamp() - interval '301 seconds', clock_timestamp() - interval '1 second')
				""", seatId);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(jdbc.queryForObject("SELECT status FROM tbooker.reservations WHERE show_seat_id = ?",
					String.class, seatId)).isEqualTo("EXPIRED");
			assertThat(jdbc.queryForObject("SELECT status FROM tbooker.show_seats WHERE id = ?",
					String.class, seatId)).isEqualTo("AVAILABLE");
		});
	}
}
