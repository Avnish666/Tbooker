package com.example.Tbooker;

import com.example.Tbooker.entity.Movie;
import com.example.Tbooker.entity.MovieShow;
import com.example.Tbooker.entity.Screen;
import com.example.Tbooker.entity.Theatre;
import com.example.Tbooker.service.CatalogueMetadataService;
import com.example.Tbooker.service.ShowCatalogueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
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
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"catalogue.cache.enabled=true", "catalogue.cache.show-list-ttl=PT2S",
        "spring.datasource.hikari.maximum-pool-size=24", "reservations.cleanup.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@AutoConfigureMockMvc
@Import(PaymentTestFixture.class)
@ActiveProfiles("dev")
@Testcontainers
class CatalogueCacheIntegrationTests extends SecurityTestSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final Statistics statistics;
    private final TransactionTemplate transaction;
    private final StringRedisTemplate cache;
    private final ShowCatalogueService shows;
    private final CatalogueMetadataService metadata;
    private final PaymentTestFixture payments;
    private final ObjectMapper json;
    private final DataSource dataSource;
    private Long showId, movieId, theatreId, screenId, seatId, userId, otherUserId;
    private Long otherShowId, otherMovieId, otherTheatreId;

    @Autowired
    CatalogueCacheIntegrationTests(MockMvc mvc, JdbcTemplate jdbc, EntityManager entities,
            EntityManagerFactory factory, PlatformTransactionManager transactions, StringRedisTemplate cache,
            ShowCatalogueService shows, CatalogueMetadataService metadata, PaymentTestFixture payments,
            ObjectMapper json, DataSource dataSource) {
        this.mvc = mvc; this.jdbc = jdbc; this.entities = entities;
        this.statistics = factory.unwrap(SessionFactory.class).getStatistics();
        this.transaction = new TransactionTemplate(transactions); this.cache = cache;
        this.shows = shows; this.metadata = metadata; this.payments = payments;
        this.json = json; this.dataSource = dataSource;
    }

    @BeforeEach
    void reset() {
        payments.clear();
        jdbc.update("DELETE FROM tbooker.reservations");
        jdbc.update("DELETE FROM tbooker.bookings");
        jdbc.update("UPDATE tbooker.show_seats SET status='AVAILABLE'");
        showId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows", Long.class);
        movieId = jdbc.queryForObject("SELECT movie_id FROM tbooker.movie_shows WHERE id=?", Long.class, showId);
        screenId = jdbc.queryForObject("SELECT screen_id FROM tbooker.movie_shows WHERE id=?", Long.class, showId);
        theatreId = jdbc.queryForObject("SELECT theatre_id FROM tbooker.screens WHERE id=?", Long.class, screenId);
        seatId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats WHERE show_id=?", Long.class, showId);
        userId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email='alex@example.test'", Long.class);
        otherUserId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email='priya@example.test'", Long.class);
        jdbc.update("UPDATE tbooker.movies SET title='The Sample Adventure' WHERE id=?", movieId);
        jdbc.update("UPDATE tbooker.theatres SET name='Sample Cinema' WHERE id=?", theatreId);
        jdbc.update("UPDATE tbooker.screens SET name='Screen 1' WHERE id=?", screenId);
        jdbc.update("UPDATE tbooker.movie_shows SET start_time='2030-01-01T18:00:00Z' WHERE id=?", showId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.movies WHERE title='Other movie'", Integer.class) == 0) {
            Long movie = jdbc.queryForObject("INSERT INTO tbooker.movies(title,duration_minutes) VALUES('Other movie',90) RETURNING id", Long.class);
            Long theatre = jdbc.queryForObject("INSERT INTO tbooker.theatres(name,city) VALUES('Other theatre','Delhi') RETURNING id", Long.class);
            Long screen = jdbc.queryForObject("INSERT INTO tbooker.screens(theatre_id,name) VALUES(?,'Other screen') RETURNING id", Long.class, theatre);
            jdbc.update("INSERT INTO tbooker.movie_shows(movie_id,screen_id,start_time) VALUES(?,?,'2030-02-01T18:00:00Z')", movie, screen);
        }
        otherMovieId = jdbc.queryForObject("SELECT id FROM tbooker.movies WHERE title='Other movie'", Long.class);
        otherTheatreId = jdbc.queryForObject("SELECT id FROM tbooker.theatres WHERE name='Other theatre'", Long.class);
        otherShowId = jdbc.queryForObject("SELECT id FROM tbooker.movie_shows WHERE movie_id=?", Long.class, otherMovieId);
        // Only this class's disposable container is flushed, never a shared development Redis.
        try (var connection = cache.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        statistics.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"movies", "movie", "theatres", "theatre", "shows", "show"})
    void missQueriesPostgresAndHitReturnsIdenticalTypedJsonWithoutOpeningTransaction(String name) throws Exception {
        String path = switch (name) {
            case "movie" -> "/api/movies/" + movieId;
            case "theatre" -> "/api/theatres/" + theatreId;
            case "show" -> "/api/shows/" + showId;
            default -> "/api/" + name;
        };
        String first = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        long transactions = statistics.getTransactionCount();
        String second = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getTransactionCount()).isEqualTo(transactions);
        Object id = switch (name) { case "movie" -> movieId; case "theatre" -> theatreId; case "show" -> showId; default -> "all"; };
        String serialized = cache.opsForValue().get(key(name, id));
        assertThat(json.readTree(serialized)).isEqualTo(json.readTree(first));
        assertThat(serialized).doesNotContain("@class", "hibernate", "password");
        assertThat(cache.getExpire(key(name, id), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void expiredEntryReloadsAnOutOfBandDatabaseChange() {
        shows.getShows();
        jdbc.update("UPDATE tbooker.movies SET title='Changed via SQL' WHERE id=?", movieId);
        assertThat(shows.getShows().getFirst().movieTitle()).isEqualTo("The Sample Adventure");
        await().atMost(Duration.ofSeconds(6)).until(() -> !cache.hasKey(key("shows", "all")));
        assertThat(shows.getShows().getFirst().movieTitle()).isEqualTo("Changed via SQL");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"movie", "theatre", "screen", "show"})
    void committedJpaChangeEvictsOnlyAffectedDetailsAndLists(String kind) {
        warm();
        transaction.executeWithoutResult(tx -> {
            switch (kind) {
                case "movie" -> ReflectionTestUtils.setField(entities.find(Movie.class, movieId), "title", "Edited movie");
                case "theatre" -> ReflectionTestUtils.setField(entities.find(Theatre.class, theatreId), "name", "Edited theatre");
                case "screen" -> ReflectionTestUtils.setField(entities.find(Screen.class, screenId), "name", "Edited screen");
                case "show" -> ReflectionTestUtils.setField(entities.find(MovieShow.class, showId), "startTime", Instant.parse("2030-01-02T18:00:00Z"));
                default -> throw new IllegalArgumentException(kind);
            }
            entities.flush();
            assertThat(cache.hasKey(key("show", showId))).as("not evicted before commit").isTrue();
        });
        assertThat(cache.hasKey(key("show", showId))).isFalse();
        assertThat(cache.hasKey(key("shows", "all"))).isFalse();
        assertThat(cache.hasKey(key("show", otherShowId))).isTrue();
        assertThat(cache.hasKey(key("movie", otherMovieId))).isTrue();
        assertThat(cache.hasKey(key("theatre", otherTheatreId))).isTrue();
        assertThat(cache.hasKey(key("movie", movieId))).isEqualTo(!kind.equals("movie"));
        assertThat(cache.hasKey(key("movies", "all"))).isEqualTo(!kind.equals("movie"));
        assertThat(cache.hasKey(key("theatre", theatreId))).isEqualTo(!kind.equals("theatre"));
        assertThat(cache.hasKey(key("theatres", "all"))).isEqualTo(!kind.equals("theatre"));
        var refreshed = shows.getShow(showId);
        switch (kind) {
            case "movie" -> assertThat(refreshed.movieTitle()).isEqualTo("Edited movie");
            case "theatre" -> assertThat(refreshed.theatreName()).isEqualTo("Edited theatre");
            case "screen" -> assertThat(refreshed.screenName()).isEqualTo("Edited screen");
            case "show" -> assertThat(refreshed.startTime()).isEqualTo(Instant.parse("2030-01-02T18:00:00Z"));
        }
    }

    @Test
    void createAndDeleteInvalidateAfterCommit() {
        shows.getShows();
        Long created = transaction.execute(tx -> {
            var show = new MovieShow(entities.find(Movie.class, movieId), entities.find(Screen.class, screenId), Instant.parse("2035-01-01T00:00:00Z"));
            entities.persist(show);
            return show.getId();
        });
        assertThat(cache.hasKey(key("shows", "all"))).isFalse();
        assertThat(shows.getShows()).hasSize(3);
        shows.getShow(created);
        transaction.executeWithoutResult(tx -> entities.remove(entities.find(MovieShow.class, created)));
        assertThat(cache.hasKey(key("shows", "all"))).isFalse();
        assertThat(cache.hasKey(key("show", created))).isFalse();
        assertThat(shows.getShows()).hasSize(2);
    }

    @Test
    void rollbackDoesNotEvictOrPublishUncommittedValuesAndTransactionalReadsBypassCache() {
        warm();
        transaction.executeWithoutResult(tx -> {
            ReflectionTestUtils.setField(entities.find(Movie.class, movieId), "title", "Uncommitted");
            entities.flush();
            assertThat(shows.getShow(showId).movieTitle()).isEqualTo("Uncommitted");
            assertThat(metadata.getMovie(movieId).title()).isEqualTo("Uncommitted");
            assertThat(cache.opsForValue().get(key("show", showId))).contains("The Sample Adventure");
            tx.setRollbackOnly();
        });
        assertThat(cache.hasKey(key("show", showId))).isTrue();
        assertThat(shows.getShow(showId).movieTitle()).isEqualTo("The Sample Adventure");
        assertThat(jdbc.queryForObject("SELECT title FROM tbooker.movies WHERE id=?", String.class, movieId)).isEqualTo("The Sample Adventure");
    }

    @Test
    void corruptJsonFallsBackAndReplacesTheBadEntry() {
        cache.opsForValue().set(key("show", showId), "invalid-json", Duration.ofMinutes(1));
        assertThat(shows.getShow(showId).movieTitle()).isEqualTo("The Sample Adventure");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(shows.getShow(showId).movieTitle()).isEqualTo("The Sample Adventure");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void unavailableRedisFallsBackAndDoesNotFailDatabaseReadiness() throws Exception {
        mvc.perform(get("/actuator/health/cache")).andExpect(status().isOk());
        pause();
        try {
            mvc.perform(get("/api/shows/{id}", showId)).andExpect(status().isOk());
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            mvc.perform(get("/actuator/health/cache")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        } finally { resume(); }
        mvc.perform(get("/actuator/health/cache")).andExpect(status().isOk());
    }

    @Test
    void failedEvictionDoesNotRollBackCommitAndTtlEventuallyRefreshes() {
        shows.getShows();
        pause();
        try {
            transaction.executeWithoutResult(tx -> ReflectionTestUtils.setField(entities.find(Movie.class, movieId), "title", "Committed during outage"));
            assertThat(jdbc.queryForObject("SELECT title FROM tbooker.movies WHERE id=?", String.class, movieId)).isEqualTo("Committed during outage");
        } finally { resume(); }
        await().atMost(Duration.ofSeconds(6)).until(() -> !cache.hasKey(key("shows", "all")));
        assertThat(shows.getShows().getFirst().movieTitle()).isEqualTo("Committed during outage");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twentyConcurrentCatalogueReadsReturnConsistentDtos(boolean prewarm) throws Exception {
        if (prewarm) { shows.getShow(showId); }
        statistics.clear();
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> mvc.perform(get("/api/shows/{id}", showId)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
        }
        var responses = concurrently(calls, () -> { });
        assertThat(responses).hasSize(20).allMatch(responses.getFirst()::equals);
        if (prewarm) { assertThat(statistics.getPrepareStatementCount()).isZero(); }
        else { assertThat(statistics.getPrepareStatementCount()).isBetween(1L, 20L); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reservationsAndPaidBookingsStayCorrectWithWarmCacheOrUnavailableRedis(boolean outage) throws Exception {
        warm();
        if (outage) { pause(); }
        try {
            long reservation = hold(userId);
            assertAvailability("HELD");
            mvc.perform(post("/api/reservations/{id}/confirmation", reservation).header("Authorization", bearer(otherUserId)))
                    .andExpect(status().isNotFound());
            payments.verifyOnly(userId, reservation);
            mvc.perform(post("/api/reservations/{id}/confirmation", reservation).header("Authorization", bearer(userId)))
                    .andExpect(status().isOk());
            assertAvailability("BOOKED");
            mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.bookings", Integer.class)).isEqualTo(1);
        } finally { if (outage) { resume(); } }
    }

    @Test
    void expiryAndCancellationRemainImmediatelyVisibleWithoutEvictingCatalogue() throws Exception {
        warm();
        long id = hold(userId);
        jdbc.update("UPDATE tbooker.reservations SET created_at=clock_timestamp()-interval '301 seconds', expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
        assertAvailability("AVAILABLE");
        mvc.perform(post("/api/reservations/{id}/confirmation", id).header("Authorization", bearer(userId)))
                .andExpect(status().isConflict());
        long replacement = hold(otherUserId);
        assertAvailability("HELD");
        mvc.perform(delete("/api/reservations/{id}", replacement).header("Authorization", bearer(otherUserId)))
                .andExpect(status().isNoContent());
        assertAvailability("AVAILABLE");
        assertThat(cache.hasKey(key("show", showId))).isTrue();
    }

    @Test
    void twentyContendingHoldsStillHaveOneWinnerWithCachingEnabled() throws Exception {
        warm();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var query = blocker.prepareStatement("SELECT id FROM tbooker.show_seats WHERE id=? FOR UPDATE")) {
                query.setLong(1, seatId);
                try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> {
                    mvc.perform(get("/api/shows/{id}", showId)).andExpect(status().isOk());
                    return mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(userId))
                            .contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().getResponse().getStatus();
                });
            }
            var statuses = concurrently(calls, () -> {
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE '%tbooker.show_seats%' AND query LIKE '%FOR UPDATE%'
                        """, Integer.class)).isEqualTo(20));
                blocker.commit();
            });
            assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
            assertThat(statuses.stream().filter(s -> s == 409)).hasSize(19);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.reservations WHERE status='HELD'", Integer.class)).isEqualTo(1);
            assertAvailability("HELD");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"movies", "theatres"})
    void newPublicReadsValidateIdsAndRetainAdminMutationPolicy(String resource) throws Exception {
        mvc.perform(get("/api/" + resource + "/0")).andExpect(status().isBadRequest());
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/" + resource + "/99999999")).andExpect(status().isNotFound());
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2); // no negative cache
        mvc.perform(post("/api/" + resource).header("Authorization", bearer(userId))).andExpect(status().isForbidden());
        mvc.perform(post("/api/" + resource).header("Authorization", bearer(userId, "ADMIN")))
                .andExpect(status().isMethodNotAllowed()); // no new mutation handlers
        mvc.perform(post("/api/shows/{id}/reservations", showId).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
    }

    private void warm() {
        shows.getShows(); shows.getShow(showId); shows.getShow(otherShowId);
        metadata.getMovies(); metadata.getMovie(movieId); metadata.getMovie(otherMovieId);
        metadata.getTheatres(); metadata.getTheatre(theatreId); metadata.getTheatre(otherTheatreId);
    }
    private String key(String name, Object id) { return "tbooker:catalogue:v1:" + name + "::" + id; }
    private String body() { return "{\"showSeatId\":" + seatId + "}"; }
    private long hold(Long owner) throws Exception {
        var response = mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }
    private void assertAvailability(String state) throws Exception {
        mvc.perform(get("/api/shows/{id}/seats", showId)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value(state));
    }
    private void pause() { redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec(); }
    private void resume() {
        redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).until(() -> {
            try (var connection = cache.getConnectionFactory().getConnection()) { return "PONG".equals(connection.ping()); }
        });
    }
    private <T> List<T> concurrently(List<Callable<T>> calls, CheckedAction afterStart) throws Exception {
        var executor = Executors.newFixedThreadPool(calls.size());
        var ready = new CountDownLatch(calls.size());
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (var call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Start latch timeout"); }
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            afterStart.run();
            List<T> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown(); futures.forEach(f -> f.cancel(true)); executor.shutdownNow();
            assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
