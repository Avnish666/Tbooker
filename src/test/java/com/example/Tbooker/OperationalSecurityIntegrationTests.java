package com.example.Tbooker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "reservations.cleanup.enabled=false", "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=2", "spring.datasource.hikari.connection-timeout=400",
        "spring.datasource.hikari.validation-timeout=250",
        "spring.datasource.hikari.data-source-properties.options=-c lock_timeout=300ms -c statement_timeout=1s"})
@ActiveProfiles("dev")
@Testcontainers
class OperationalSecurityIntegrationTests extends SecurityTestSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @LocalServerPort private int port;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private Long user, seat, show;

    @Autowired
    OperationalSecurityIntegrationTests(JdbcTemplate jdbc, DataSource dataSource,
            PlatformTransactionManager transactions, MeterRegistry metrics, ObjectMapper json) {
        this.jdbc = jdbc; this.dataSource = dataSource; this.transactions = new TransactionTemplate(transactions);
        this.metrics = metrics; this.json = json;
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM tbooker.reservations");
        jdbc.update("UPDATE tbooker.show_seats SET status='AVAILABLE'");
        user = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.users", Long.class);
        seat = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats", Long.class);
        show = jdbc.queryForObject("SELECT show_id FROM tbooker.show_seats WHERE id=?", Long.class, seat);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOversizedBodiesIncludingChunkedRequestsWithoutEchoingContent(boolean chunked) throws Exception {
        byte[] body = ("{\"password\":\"secret-sentinel-" + "x".repeat(16384) + "\"}").getBytes(StandardCharsets.UTF_8);
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        var response = send(request("/api/auth/login").header("Content-Type", "application/json").POST(publisher).build());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).doesNotContain("secret-sentinel", "xxx");
        assertCorrelation(response);
    }

    @Test
    void invalidJsonDoesNotEchoCredentialsAndHasCorrelation() throws Exception {
        var response = send(request("/api/auth/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"secret-sentinel\", invalid}" )).build());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("secret-sentinel", "Unrecognized token", "Exception");
        assertCorrelation(response);
    }

    @Test
    void correlationCoversSecurityFailuresAndRejectsArbitraryHeaderContent() throws Exception {
        var response = send(request("/api/bookings").header("X-Request-ID", "attacker-secret-sentinel").GET().build());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("attacker");
        assertCorrelation(response);
        String id = "a".repeat(32);
        var forbidden = send(request("/actuator/metrics").header("Authorization", bearer(user))
                .header("X-Request-ID", id).GET().build());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("X-Request-ID")).contains(id);
        assertCorrelation(forbidden);
    }

    @Test
    void conflictsAndHttpLatencyAreMeasuredWithoutOwnerOrRequestIdTags() throws Exception {
        double before = metrics.counter("reservations.conflicts").count();
        assertThat(reserve().statusCode()).isEqualTo(201);
        assertThat(reserve().statusCode()).isEqualTo(409);
        assertThat(metrics.counter("reservations.conflicts").count()).isEqualTo(before + 1);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var timer = metrics.get("http.server.requests").tag("status", "409").timer();
            assertThat(timer.count()).isGreaterThanOrEqualTo(1);
            assertThat(timer.takeSnapshot().percentileValues()).anySatisfy(value -> {
                assertThat(value.percentile()).isEqualTo(0.95);
                assertThat(value.value()).isPositive();
            });
            assertThat(timer.getId().getTags()).noneMatch(tag ->
                    tag.getKey().equals("correlationId") || tag.getKey().equals("userId"));
        });
        assertThat(send(request("/actuator/metrics/reservations.conflicts")
                .header("Authorization", bearer(user, "ADMIN")).GET().build()).statusCode()).isEqualTo(200);
    }

    @Test
    void lockTimeoutReturns503RollsBackHoldAndAllowsExplicitRetry() throws Exception {
        try (var blocker = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            blocker.setAutoCommit(false);
            try (var query = blocker.prepareStatement("SELECT id FROM tbooker.show_seats WHERE id=? FOR UPDATE")) {
                query.setLong(1, seat); query.executeQuery().close();
            }
            var response = reserve();
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("SQL", "lock timeout", "tbooker.show_seats");
            assertCorrelation(response);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.reservations", Integer.class)).isZero();
            blocker.rollback();
        }
        assertThat(reserve().statusCode()).isEqualTo(201);
    }

    @Test
    void statementTimeoutRollsBackEarlierWritesInTheSameTransaction() {
        String name = jdbc.queryForObject("SELECT name FROM tbooker.users WHERE id=?", String.class, user);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            jdbc.update("UPDATE tbooker.users SET name='must-roll-back' WHERE id=?", user);
            jdbc.execute("SELECT pg_sleep(2)");
        })).isInstanceOf(QueryTimeoutException.class);
        assertThat(jdbc.queryForObject("SELECT name FROM tbooker.users WHERE id=?", String.class, user)).isEqualTo(name);
    }

    @Test
    void exhaustedPoolReturns503AndRecoversAfterConnectionsAreReleased() throws Exception {
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            var response = send(request("/api/shows").GET().build());
            assertThat(response.statusCode()).isEqualTo(503);
            assertCorrelation(response);
        }
        assertThat(send(request("/api/shows").GET().build()).statusCode()).isEqualTo(200);
    }

    @Test
    void databaseFailureDoesNotExposeFailingRowDetailsInResponseOrLogs() throws Exception {
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); root.addAppender(logs);
        jdbc.execute("""
                CREATE FUNCTION tbooker.reject_hold_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private-row-sentinel' USING ERRCODE='23514'; END $$;
                CREATE TRIGGER reject_hold_test BEFORE INSERT ON tbooker.reservations
                FOR EACH ROW EXECUTE FUNCTION tbooker.reject_hold_test();
                """);
        try {
            var response = reserve();
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("private-row-sentinel", "SQLException", "23514");
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-row-sentinel");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.reservations", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM tbooker.show_seats WHERE id=?", String.class, seat))
                    .isEqualTo("AVAILABLE");
        } finally {
            root.detachAppender(logs); logs.stop();
            jdbc.execute("DROP TRIGGER reject_hold_test ON tbooker.reservations; DROP FUNCTION tbooker.reject_hold_test()");
        }
        assertThat(reserve().statusCode()).isEqualTo(201);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(8));
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> reserve() throws Exception {
        return send(request("/api/shows/" + show + "/reservations").header("Authorization", bearer(user))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"showSeatId\":" + seat + "}")).build());
    }
    private void assertCorrelation(HttpResponse<String> response) throws Exception {
        String id = response.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(id).matches("[a-f0-9]{32}");
        assertThat(json.readTree(response.body()).path("correlationId").asText()).isEqualTo(id);
    }
}
