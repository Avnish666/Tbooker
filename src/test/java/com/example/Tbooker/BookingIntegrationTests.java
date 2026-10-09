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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
// Deliberately no test-level transaction: requests must commit/roll back independently.
class BookingIntegrationTests extends SecurityTestSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private final MockMvc mockMvc;
	private final JdbcTemplate jdbcTemplate;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;
	private final Flyway flyway;

	private Long showId;
	private Long userId;
	private Long otherUserId;
	private Long showSeatId;

	@Autowired
	BookingIntegrationTests(MockMvc mockMvc, JdbcTemplate jdbcTemplate, DataSource dataSource,
			ObjectMapper objectMapper, Flyway flyway) {
		this.mockMvc = mockMvc;
		this.jdbcTemplate = jdbcTemplate;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
		this.flyway = flyway;
	}

	@BeforeEach
	void resetIsolatedTestDatabase() {
		showId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows", Long.class);
		jdbcTemplate.update("DELETE FROM tbooker.bookings");
		jdbcTemplate.update("DELETE FROM tbooker.show_seats WHERE show_id <> ?", showId);
		jdbcTemplate.update("DELETE FROM tbooker.movie_shows WHERE id <> ?", showId);
		jdbcTemplate.update("UPDATE tbooker.show_seats SET status = 'AVAILABLE'");
		userId = jdbcTemplate.queryForObject("SELECT id FROM tbooker.users WHERE email = 'alex@example.test'", Long.class);
		otherUserId = jdbcTemplate.queryForObject("SELECT id FROM tbooker.users WHERE email = 'priya@example.test'", Long.class);
		showSeatId = jdbcTemplate.queryForObject("""
				SELECT ss.id FROM tbooker.show_seats ss JOIN tbooker.seats s ON s.id = ss.seat_id
				WHERE ss.show_id = ? AND s.seat_number = 'A1'
				""", Long.class, showId);
	}

	@Test
	void createsConfirmedBookingAndCommitsSeatChange() throws Exception {
		Instant before = Instant.now().minusSeconds(1);
		var response = book(showId, userId, showSeatId)
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.userId").value(userId))
				.andExpect(jsonPath("$.showId").value(showId))
				.andExpect(jsonPath("$.showSeatId").value(showSeatId))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.user").doesNotExist())
				.andExpect(jsonPath("$.showSeat").doesNotExist())
				.andReturn().getResponse();
		var body = objectMapper.readTree(response.getContentAsString());
		assertThat(body.get("createdAt").asText()).endsWith("Z");
		Instant createdAt = Instant.parse(body.get("createdAt").asText());
		assertThat(createdAt).isBetween(before, Instant.now());
		var persisted = jdbcTemplate.queryForMap("SELECT * FROM tbooker.bookings WHERE id = ?", body.get("id").asLong());
		assertThat(persisted).containsEntry("user_id", userId).containsEntry("show_seat_id", showSeatId)
				.containsEntry("status", "CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM tbooker.bookings WHERE id = ?",
				OffsetDateTime.class, body.get("id").asLong()).toInstant()).isEqualTo(createdAt);
		assertThat(bookingCount()).isEqualTo(1);
		assertThat(seatStatus()).isEqualTo("BOOKED");
		mockMvc.perform(get("/api/shows/{id}/seats", showId))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("BOOKED"));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void secondBookingForSameSeatReturnsConflict(boolean differentUser) throws Exception {
		book(showId, userId, showSeatId).andExpect(status().isCreated());
		book(showId, differentUser ? otherUserId : userId, showSeatId)
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409));
		assertThat(bookingCount()).isEqualTo(1);
		assertThat(seatStatus()).isEqualTo("BOOKED");
	}

	@Test
	void nonexistentSeatReturnsNotFoundWithoutChanges() throws Exception {
		book(showId, userId, Long.MAX_VALUE)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Show seat " + Long.MAX_VALUE + " was not found"));
		assertNoBookingOrSeatChange();
	}

	@Test
	void nonexistentUserReturnsNotFoundWithoutChanges() throws Exception {
		book(showId, Long.MAX_VALUE, showSeatId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("User " + Long.MAX_VALUE + " was not found"));
		assertNoBookingOrSeatChange();
	}

	@Test
	void nonexistentShowReturnsNotFoundWithoutChanges() throws Exception {
		book(Long.MAX_VALUE, userId, showSeatId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Show " + Long.MAX_VALUE + " was not found"));
		assertNoBookingOrSeatChange();
	}

	@Test
	void seatFromAnotherShowReturnsBadRequestWithoutChanges() throws Exception {
		Long otherShowId = createOtherShow();
		book(otherShowId, userId, showSeatId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Show seat " + showSeatId + " does not belong to show " + otherShowId));
		assertNoBookingOrSeatChange();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"userId\":1}", "{\"userId\":1,\"showSeatId\":1}",
			"{\"showSeatId\":0}", "{\"showSeatId\":-1}", "{\"showSeatId\":null}",
			"{\"showSeatId\":1.5}", "{\"showSeatId\":\"abc\"}", "{\"showSeatId\":9223372036854775808}",
			"{\"showSeatId\":true}", "[]", "{\"showSeatId\":{}}", "{\"showSeatId\":1,\"status\":\"CONFIRMED\"}",
			"{broken", "", "null"})
	void invalidRequestBodyReturnsBadRequest(String body) throws Exception {
		mockMvc.perform(post("/api/shows/{showId}/bookings", showId)
				.header("Authorization", bearer(userId))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertNoBookingOrSeatChange();
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc"})
	void invalidShowIdReturnsBadRequest(String id) throws Exception {
		mockMvc.perform(post("/api/shows/{showId}/bookings", id)
				.header("Authorization", bearer(userId))
				.contentType(MediaType.APPLICATION_JSON).content(requestBody(showSeatId)))
				.andExpect(status().isBadRequest());
		assertNoBookingOrSeatChange();
	}

	@ParameterizedTest(name = "{0} INSERT failure rolls back both writes and permits retry")
	@ValueSource(strings = {"BEFORE", "AFTER"})
	void failureDuringInsertRollsBackSeatUpdateAndAllowsRetry(String triggerTiming) throws Exception {
		// Both timings run after the seat claim; AFTER also runs after the booking row was inserted.
		jdbcTemplate.execute("""
				CREATE FUNCTION tbooker.fail_test_booking_insert() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				    RAISE EXCEPTION 'Forced booking insert failure'
				        USING ERRCODE = '23514', CONSTRAINT = 'test_booking_insert_failure';
				END $$;
				CREATE TRIGGER fail_test_booking_insert %s INSERT ON tbooker.bookings
				FOR EACH ROW EXECUTE FUNCTION tbooker.fail_test_booking_insert();
				""".formatted(triggerTiming));
		try {
			book(showId, userId, showSeatId)
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.detail").value("The request could not be completed"))
					.andExpect(jsonPath("$.trace").doesNotExist());
			assertNoBookingOrSeatChange();
		}
		finally {
			jdbcTemplate.execute("DROP TRIGGER fail_test_booking_insert ON tbooker.bookings");
			jdbcTemplate.execute("DROP FUNCTION tbooker.fail_test_booking_insert()");
		}
		book(showId, otherUserId, showSeatId).andExpect(status().isCreated());
		assertThat(bookingCount()).isEqualTo(1);
		assertThat(seatStatus()).isEqualTo("BOOKED");
	}

	@Test
	void uniqueConstraintProtectsSeatEvenIfAvailabilityWasIncorrectlyReset() throws Exception {
		book(showId, userId, showSeatId).andExpect(status().isCreated());
		// Simulate an out-of-band data error. The unique booking constraint is the backstop.
		jdbcTemplate.update("UPDATE tbooker.show_seats SET status = 'AVAILABLE' WHERE id = ?", showSeatId);
		book(showId, otherUserId, showSeatId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("This show seat already has a confirmed booking"));
		assertThat(bookingCount()).isEqualTo(1);
		// The second transaction's conditional update must have rolled back as well.
		assertThat(seatStatus()).isEqualTo("AVAILABLE");
	}

	@Test
	void databaseRejectsDuplicateBookingsDirectly() throws Exception {
		book(showId, userId, showSeatId).andExpect(status().isCreated());
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO tbooker.bookings (user_id, show_seat_id, status, created_at)
				VALUES (?, ?, 'CONFIRMED', CURRENT_TIMESTAMP)
				""", otherUserId, showSeatId)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(bookingCount()).isEqualTo(1);
	}

	@Test
	void twoContendingRequestsProduceExactlyOneConfirmedBooking() throws Exception {
		try (var executor = Executors.newFixedThreadPool(2); var blocker = dataSource.getConnection()) {
			lockSeat(blocker);
			var first = executor.submit(() -> book(showId, userId, showSeatId).andReturn().getResponse().getStatus());
			var second = executor.submit(() -> book(showId, otherUserId, showSeatId).andReturn().getResponse().getStatus());
			awaitBlockedUpdates(2);
			blocker.commit();
			int firstStatus = first.get(15, TimeUnit.SECONDS);
			int secondStatus = second.get(15, TimeUnit.SECONDS);
			assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(201, 409);
			assertThat(bookingCount()).isEqualTo(1);
			assertThat(seatStatus()).isEqualTo("BOOKED");
			assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM tbooker.bookings", Long.class))
					.isEqualTo(firstStatus == 201 ? userId : otherUserId);
		}
	}

	@Test
	void waitingRequestCanBookWhenCompetingTransactionRollsBack() throws Exception {
		try (var executor = Executors.newSingleThreadExecutor(); var blocker = dataSource.getConnection()) {
			blocker.setAutoCommit(false);
			try (var statement = blocker.prepareStatement("UPDATE tbooker.show_seats SET status = 'BOOKED' WHERE id = ?")) {
				statement.setLong(1, showSeatId);
				assertThat(statement.executeUpdate()).isEqualTo(1);
			}
			var waiting = executor.submit(() -> book(showId, userId, showSeatId).andReturn().getResponse().getStatus());
			awaitBlockedUpdates(1);
			blocker.rollback();
			assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo(201);
			assertThat(bookingCount()).isEqualTo(1);
			assertThat(seatStatus()).isEqualTo("BOOKED");
		}
	}

	@Test
	void samePhysicalSeatCanBeBookedForTwoDifferentShows() throws Exception {
		Long otherShowId = createOtherShow();
		Long otherShowSeatId = jdbcTemplate.queryForObject("""
				INSERT INTO tbooker.show_seats (show_id, seat_id, screen_id)
				SELECT ?, seat_id, screen_id FROM tbooker.show_seats WHERE id = ? RETURNING id
				""", Long.class, otherShowId, showSeatId);
		book(showId, userId, showSeatId).andExpect(status().isCreated());
		book(otherShowId, otherUserId, otherShowSeatId).andExpect(status().isCreated());
		assertThat(bookingCount()).isEqualTo(2);
	}

	@Test
	void developmentSeedDoesNotUndoConfirmedBooking() throws Exception {
		book(showId, userId, showSeatId).andExpect(status().isCreated());
		jdbcTemplate.execute(new ClassPathResource("db/dev/R__sample_catalogue.sql")
				.getContentAsString(StandardCharsets.UTF_8));
		assertThat(bookingCount()).isEqualTo(1);
		assertThat(seatStatus()).isEqualTo("BOOKED");
	}

	@Test
	void repeatedFlywayMigrationsPreserveCatalogueAndConfirmedBooking() throws Exception {
		book(showId, userId, showSeatId).andExpect(status().isCreated());
		var historyBefore = jdbcTemplate.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
		var rowsBefore = snapshotApplicationRows();
		for (int run = 0; run < 3; run++) {
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
			assertThat(flyway.info().pending()).isEmpty();
			assertThat(jdbcTemplate.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank"))
					.isEqualTo(historyBefore);
			assertThat(snapshotApplicationRows()).isEqualTo(rowsBefore);
		}
		assertThat(bookingCount()).isEqualTo(1);
		assertThat(seatStatus()).isEqualTo("BOOKED");
	}

	private Map<String, List<Map<String, Object>>> snapshotApplicationRows() {
		Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
		for (String table : new String[] {"users", "movies", "theatres", "screens", "seats", "movie_shows", "show_seats", "bookings"}) {
			rows.put(table, jdbcTemplate.queryForList("SELECT * FROM tbooker." + table + " ORDER BY id"));
		}
		return rows;
	}

	private ResultActions book(Long requestedShowId, Long requestedUserId, Long requestedShowSeatId) throws Exception {
		return mockMvc.perform(post("/api/shows/{showId}/bookings", requestedShowId)
				.header("Authorization", bearer(requestedUserId))
				.contentType(MediaType.APPLICATION_JSON).content(requestBody(requestedShowSeatId)));
	}

	private String requestBody(Long requestedShowSeatId) {
		return "{\"showSeatId\":" + requestedShowSeatId + "}";
	}

	private Long createOtherShow() {
		return jdbcTemplate.queryForObject("""
				INSERT INTO tbooker.movie_shows (movie_id, screen_id, start_time)
				SELECT movie_id, screen_id, start_time + INTERVAL '3 hours' FROM tbooker.movie_shows
				WHERE id = ? RETURNING id
				""", Long.class, showId);
	}

	private int bookingCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.bookings", Integer.class);
	}

	private String seatStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM tbooker.show_seats WHERE id = ?", String.class, showSeatId);
	}

	private void assertNoBookingOrSeatChange() {
		assertThat(bookingCount()).isZero();
		assertThat(seatStatus()).isEqualTo("AVAILABLE");
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
		assertThat(blocked).as("requests blocked on the PostgreSQL conditional update").isEqualTo(expected);
	}

}
