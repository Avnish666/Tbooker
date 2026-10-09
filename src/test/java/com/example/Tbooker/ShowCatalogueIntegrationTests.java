package com.example.Tbooker;

import com.example.Tbooker.entity.Movie;
import com.example.Tbooker.entity.MovieShow;
import com.example.Tbooker.entity.Screen;
import com.example.Tbooker.entity.Seat;
import com.example.Tbooker.entity.ShowSeat;
import com.example.Tbooker.entity.ShowSeatStatus;
import com.example.Tbooker.entity.Theatre;
import com.example.Tbooker.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
@Transactional
class ShowCatalogueIntegrationTests extends SecurityTestSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private final MockMvc mockMvc;
	private final JdbcTemplate jdbcTemplate;
	private final EntityManager entityManager;

	@Autowired
	ShowCatalogueIntegrationTests(MockMvc mockMvc, JdbcTemplate jdbcTemplate, EntityManager entityManager) {
		this.mockMvc = mockMvc;
		this.jdbcTemplate = jdbcTemplate;
		this.entityManager = entityManager;
	}

	@Test
	void listsShowsWithMovieAndVenueDetails() throws Exception {
		mockMvc.perform(get("/api/shows"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(sampleShowId()))
				.andExpect(jsonPath("$[0].movieTitle").value("The Sample Adventure"))
				.andExpect(jsonPath("$[0].durationMinutes").value(120))
				.andExpect(jsonPath("$[0].theatreName").value("Sample Cinema"))
				.andExpect(jsonPath("$[0].screenName").value("Screen 1"))
				.andExpect(jsonPath("$[0].city").value("Bengaluru"))
				.andExpect(jsonPath("$[0].startTime").value("2030-01-01T18:00:00Z"));
	}

	@Test
	void getsSingleShowAsDto() throws Exception {
		mockMvc.perform(get("/api/shows/{id}", sampleShowId()))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.movieId").isNumber())
				.andExpect(jsonPath("$.screenId").isNumber())
				.andExpect(jsonPath("$.theatreId").isNumber())
				.andExpect(jsonPath("$.movieTitle").value("The Sample Adventure"))
				.andExpect(jsonPath("$.movie").doesNotExist())
				.andExpect(jsonPath("$.screen").doesNotExist());
	}

	@Test
	void listsEightShowSeatsInSeatNumberOrder() throws Exception {
		var response = mockMvc.perform(get("/api/shows/{id}/seats", sampleShowId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(8)));
		for (int i = 0; i < 8; i++) {
			response.andExpect(jsonPath("$[" + i + "].seatNumber").value("A" + (i + 1)))
					.andExpect(jsonPath("$[" + i + "].showId").value(sampleShowId()))
					.andExpect(jsonPath("$[" + i + "].seatId").isNumber())
					.andExpect(jsonPath("$[" + i + "].status").value("AVAILABLE"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/seats"})
	void missingShowReturnsNotFoundProblem(String suffix) throws Exception {
		mockMvc.perform(get("/api/shows/9223372036854775807" + suffix))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("Show 9223372036854775807 was not found"));
	}

	@ParameterizedTest
	@CsvSource({"0,''", "-1,''", "abc,''", "9223372036854775808,''", "0,/seats", "abc,/seats"})
	void invalidShowIdsReturnBadRequest(String id, String suffix) throws Exception {
		mockMvc.perform(get("/api/shows/" + id + suffix))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void catalogueHasNoWriteEndpoint() throws Exception {
		mockMvc.perform(post("/api/shows").header("Authorization", bearer(1L, "ADMIN")))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void persistsAllEntityRelationshipsAndHandlesShowWithoutSeats() throws Exception {
		var user = new User("Integration User", "integration@example.test");
		var movie = new Movie("Another Movie", 90);
		var theatre = new Theatre("Another Theatre", "Pune");
		entityManager.persist(user);
		entityManager.persist(movie);
		entityManager.persist(theatre);
		var screen = new Screen(theatre, "Screen 2");
		entityManager.persist(screen);
		var seat = new Seat(screen, "B1");
		entityManager.persist(seat);
		var show = new MovieShow(movie, screen, Instant.parse("2030-01-02T10:00:00Z"));
		entityManager.persist(show);
		entityManager.flush();
		mockMvc.perform(get("/api/shows/{id}/seats", show.getId()))
				.andExpect(status().isOk()).andExpect(content().json("[]"));
		var showSeat = new ShowSeat(show, seat);
		entityManager.persist(showSeat);
		entityManager.flush();
		entityManager.clear();

		var loaded = entityManager.find(ShowSeat.class, showSeat.getId());
		assertThat(loaded.getStatus()).isEqualTo(ShowSeatStatus.AVAILABLE);
		assertThat(loaded.getMovieShow().getMovie().getTitle()).isEqualTo("Another Movie");
		assertThat(loaded.getSeat().getScreen().getTheatre().getCity()).isEqualTo("Pune");
		assertThat(entityManager.find(User.class, user.getId()).getEmail()).isEqualTo("integration@example.test");
	}

	@Test
	void samePhysicalSeatCanHaveIndependentStatusForDifferentShows() throws Exception {
		Long firstShowId = sampleShowId();
		var firstShow = entityManager.find(MovieShow.class, firstShowId);
		var seat = entityManager.createQuery("select s from Seat s where s.seatNumber = 'A1'", Seat.class)
				.getSingleResult();
		var secondShow = new MovieShow(firstShow.getMovie(), firstShow.getScreen(),
				firstShow.getStartTime().plusSeconds(10800));
		entityManager.persist(secondShow);
		entityManager.persist(new ShowSeat(secondShow, seat));
		entityManager.flush();
		entityManager.clear();
		jdbcTemplate.update("UPDATE tbooker.show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_id = ?",
				firstShowId, seat.getId());
		mockMvc.perform(get("/api/shows/{id}/seats", firstShowId))
				.andExpect(jsonPath("$[0].status").value("BOOKED"));
		mockMvc.perform(get("/api/shows/{id}/seats", secondShow.getId()))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].seatId").value(seat.getId()))
				.andExpect(jsonPath("$[0].status").value("AVAILABLE"));
	}

	@Test
	void serializesInstantsInUtcEvenWithDifferentDatabaseSessionTimezone() throws Exception {
		assertThat(jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
		jdbcTemplate.execute("SET LOCAL TIME ZONE 'Asia/Kolkata'");
		Long id = jdbcTemplate.queryForObject("""
				INSERT INTO tbooker.movie_shows (movie_id, screen_id, start_time)
				SELECT movie_id, screen_id, TIMESTAMPTZ '2031-03-10 23:00:00+05:30'
				FROM tbooker.movie_shows LIMIT 1 RETURNING id
				""", Long.class);
		mockMvc.perform(get("/api/shows/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.startTime").value("2031-03-10T17:30:00Z"));
	}

	@Test
	void seedIsRepeatableWithoutDuplicatesOrStatusReset() throws Exception {
		var expectedCounts = Map.of("users", 2L, "movies", 1L, "theatres", 1L, "screens", 1L,
				"seats", 8L, "movie_shows", 1L, "show_seats", 8L);
		assertThat(rowCounts()).isEqualTo(expectedCounts);
		jdbcTemplate.update("UPDATE tbooker.show_seats SET status = 'BOOKED' WHERE seat_id = "
				+ "(SELECT id FROM tbooker.seats WHERE seat_number = 'A1')");
		String sql = new ClassPathResource("db/dev/R__sample_catalogue.sql").getContentAsString(StandardCharsets.UTF_8);
		jdbcTemplate.execute(sql);
		jdbcTemplate.execute(sql);
		assertThat(rowCounts()).isEqualTo(expectedCounts);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.show_seats WHERE status = 'BOOKED'",
				Integer.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@MethodSource("invalidDatabaseWrites")
	void databaseRejectsInvalidOrDuplicateRows(String sql) {
		assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
	}

	static Stream<String> invalidDatabaseWrites() {
		return Stream.of(
				"INSERT INTO tbooker.users (name, email) VALUES ('Duplicate', 'ALEX@EXAMPLE.TEST')",
				"INSERT INTO tbooker.movies (title, duration_minutes) VALUES ('Invalid Duration', 0)",
				"INSERT INTO tbooker.screens (theatre_id, name) SELECT theatre_id, name FROM tbooker.screens",
				"INSERT INTO tbooker.seats (screen_id, seat_number) SELECT screen_id, seat_number FROM tbooker.seats",
				"INSERT INTO tbooker.movie_shows (movie_id, screen_id, start_time) "
						+ "SELECT movie_id, screen_id, start_time FROM tbooker.movie_shows",
				"INSERT INTO tbooker.show_seats (show_id, seat_id, screen_id) "
						+ "SELECT show_id, seat_id, screen_id FROM tbooker.show_seats",
				"UPDATE tbooker.show_seats SET status = 'HELD'",
				"INSERT INTO tbooker.screens (theatre_id, name) VALUES (9223372036854775807, 'Orphan')",
				"UPDATE tbooker.movie_shows SET movie_id = 9223372036854775807",
				"UPDATE tbooker.seats SET screen_id = 9223372036854775807",
				"UPDATE tbooker.show_seats SET show_id = 9223372036854775807",
				"UPDATE tbooker.show_seats SET seat_id = 9223372036854775807",
				"DELETE FROM tbooker.movies");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void databaseRejectsSeatFromAnotherScreenRegardlessOfDeclaredScreen(boolean useOtherScreen) {
		Long showId = sampleShowId();
		Long showScreenId = jdbcTemplate.queryForObject("SELECT screen_id FROM tbooker.movie_shows WHERE id = ?",
				Long.class, showId);
		Long otherScreenId = jdbcTemplate.queryForObject("""
				INSERT INTO tbooker.screens (theatre_id, name)
				SELECT id, 'Other Screen' FROM tbooker.theatres RETURNING id
				""", Long.class);
		Long otherSeatId = jdbcTemplate.queryForObject(
				"INSERT INTO tbooker.seats (screen_id, seat_number) VALUES (?, 'A1') RETURNING id",
				Long.class, otherScreenId);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO tbooker.show_seats (show_id, seat_id, screen_id) VALUES (?, ?, ?)",
				showId, otherSeatId, useOtherScreen ? otherScreenId : showScreenId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Long sampleShowId() {
		return jdbcTemplate.queryForObject("SELECT id FROM tbooker.movie_shows", Long.class);
	}

	private Map<String, Long> rowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : new String[] {"users", "movies", "theatres", "screens", "seats", "movie_shows", "show_seats"}) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker." + table, Long.class));
		}
		return counts;
	}

}
