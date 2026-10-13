package com.example.Tbooker;

import com.example.Tbooker.dto.PaymentRequest;
import com.example.Tbooker.entity.PaymentMethod;
import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.payment.GatewayResult;
import com.example.Tbooker.payment.PaymentStrategyRegistry;
import com.example.Tbooker.service.PaymentResultProcessor;
import com.example.Tbooker.service.PaymentService;
import com.example.Tbooker.service.PaymentTransactions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=24", "reservations.cleanup.enabled=false",
        "payments.mock.card-outcome=FAILED"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
@Import(PaymentTestFixture.class)
class PaymentIntegrationTests extends SecurityTestSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PaymentTransactions transactions;
    private final PaymentService service;
    private final PaymentResultProcessor processor;
    private final PaymentStrategyRegistry registry;
    private final PaymentTestFixture fixture;
    private final TransactionTemplate transaction;
    private final DataSource dataSource;
    private final int port;
    private Long showId, seatId, userId, otherUserId, reservationId;

    @Autowired
    PaymentIntegrationTests(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, PaymentTransactions transactions,
            PaymentService service, PaymentResultProcessor processor, PaymentStrategyRegistry registry,
            PaymentTestFixture fixture, PlatformTransactionManager manager, DataSource dataSource, @LocalServerPort int port) {
        this.mvc = mvc; this.jdbc = jdbc; this.json = json; this.transactions = transactions;
        this.service = service; this.processor = processor; this.registry = registry; this.fixture = fixture;
        this.transaction = new TransactionTemplate(manager); this.dataSource = dataSource; this.port = port;
    }

    @BeforeEach
    void reset() throws Exception {
        fixture.clear();
        jdbc.update("DELETE FROM tbooker.reservations");
        jdbc.update("DELETE FROM tbooker.bookings");
        jdbc.update("UPDATE tbooker.show_seats SET status = 'AVAILABLE'");
        showId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows", Long.class);
        seatId = jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats", Long.class);
        userId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email = 'alex@example.test'", Long.class);
        otherUserId = jdbc.queryForObject("SELECT id FROM tbooker.users WHERE email = 'priya@example.test'", Long.class);
        reservationId = hold(userId, seatId);
    }

    @Test
    void verifiedSuccessCreatesOneBookingAndCanBeRetrieved() throws Exception {
        var paid = body(pay(userId, reservationId, "success", "UPI").andExpect(status().isCreated())
                .andExpect(header().exists("Location")));
        assertThat(paid.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(paid.get("confirmationStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(paid.get("amountMinor").asLong()).isEqualTo(10000);
        assertThat(paid.get("currency").asText()).isEqualTo("INR");
        assertThat(count("bookings")).isEqualTo(1);
        mvc.perform(get("/api/payments/{id}", paid.get("id").asLong()).header("Authorization", bearer(userId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingId").value(paid.get("bookingId").asLong()));
        mvc.perform(get("/api/payments").header("Authorization", bearer(userId))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(paid.get("id").asLong()));
        mvc.perform(get("/api/reservations/{id}/booking", reservationId).header("Authorization", bearer(userId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(paid.get("bookingId").asLong()));
        assertSeat("BOOKED");
    }

    @Test
    void failureLeavesHoldUnconfirmedAndNewKeyCanUseDifferentMethod() throws Exception {
        var failed = body(pay(userId, reservationId, "declined", "CARD").andExpect(status().isCreated()));
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("confirmationStatus").asText()).isEqualTo("NOT_READY");
        assertThat(count("bookings")).isZero();
        assertSeat("HELD");
        confirm().andExpect(status().isConflict());
        assertThat(body(pay(userId, reservationId, "declined", "CARD").andExpect(status().isOk()))).isEqualTo(failed);
        pay(userId, reservationId, "retry-upi", "UPI").andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"));
        assertThat(count("payments")).isEqualTo(2);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void sameKeyReturnsSameStoredResultIncludingAfterExpiry() throws Exception {
        var original = body(pay(userId, reservationId, "same-key", "UPI").andExpect(status().isCreated()));
        expire();
        for (int i = 0; i < 3; i++) {
            assertThat(body(pay(userId, reservationId, "same-key", "UPI").andExpect(status().isOk()))).isEqualTo(original);
        }
        confirm().andExpect(status().isOk()).andExpect(jsonPath("$.id").value(original.get("bookingId").asLong()));
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void keyReuseWithDifferentMethodOrReservationConflicts() throws Exception {
        pay(userId, reservationId, "params", "UPI").andExpect(status().isCreated());
        pay(userId, reservationId, "params", "CARD").andExpect(status().isConflict());
        Long nextSeat = jdbc.queryForObject("SELECT MAX(id) FROM tbooker.show_seats", Long.class);
        long next = hold(userId, nextSeat);
        pay(userId, next, "params", "UPI").andExpect(status().isConflict());
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test
    void keyNamespaceIsPerOwner() throws Exception {
        pay(userId, reservationId, "shared-key", "UPI").andExpect(status().isCreated());
        Long otherSeat = jdbc.queryForObject("SELECT MAX(id) FROM tbooker.show_seats", Long.class);
        long other = hold(otherUserId, otherSeat);
        pay(otherUserId, other, "shared-key", "UPI").andExpect(status().isCreated());
        assertThat(count("payments")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void foreignOwnerCannotInitiateReadOrRetrieveConfirmation(String role) throws Exception {
        var payment = body(pay(userId, reservationId, "owner", "UPI").andExpect(status().isCreated()));
        String token = bearer(otherUserId, role);
        mvc.perform(post("/api/reservations/{id}/payments", reservationId).header("Authorization", token)
                .header("Idempotency-Key", "foreign").contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"UPI\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/{id}", payment.get("id").asLong()).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments").header("Authorization", token)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/reservations/{id}/booking", reservationId).header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void noClientCanSupplySuccessOrBypassPayment() throws Exception {
        confirm().andExpect(status().isConflict());
        mvc.perform(post("/api/shows/{id}/bookings", showId).header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"showSeatId\":" + seatId + "}"))
                .andExpect(status().isConflict());
        for (String field : List.of("status", "userId", "amountMinor", "providerReference")) {
            mvc.perform(post("/api/reservations/{id}/payments", reservationId).header("Authorization", bearer(userId))
                    .header("Idempotency-Key", "inject").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"method\":\"UPI\",\"" + field + "\":\"SUCCESS\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/payments/callback").header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCESS\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(count("bookings")).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test
    void missingOrInvalidJwtCannotAccessPaymentRoutes() throws Exception {
        for (String token : List.of("", "Bearer malformed")) {
            mvc.perform(post("/api/reservations/{id}/payments", reservationId).header("Authorization", token)
                    .header("Idempotency-Key", "jwt").contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"UPI\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/payments").header("Authorization", token)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/payments/1").header("Authorization", token)).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "spaces not allowed", "*", "key\nvalue"})
    void invalidKeyIsRejected(String key) throws Exception {
        pay(userId, reservationId, key, "UPI").andExpect(status().isBadRequest());
        assertThat(count("payments")).isZero();
    }

    @Test
    void missingKeyOrInvalidMethodIsRejected() throws Exception {
        mvc.perform(post("/api/reservations/{id}/payments", reservationId).header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"UPI\"}")).andExpect(status().isBadRequest());
        pay(userId, reservationId, "method", "BITCOIN").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveReservationCannotStartPayment(boolean cancelled) throws Exception {
        if (cancelled) { cancel(); } else { expire(); }
        pay(userId, reservationId, "inactive", "UPI").andExpect(status().isConflict());
        assertThat(count("payments")).isZero();
        assertThat(count("mock_gateway_receipts")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateSuccessIsRecordedForReconciliationWithoutTakingAnotherUsersSeat(boolean cancelled) throws Exception {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "late").payment();
        registry.get(PaymentMethod.UPI).charge(attempt.command());
        if (cancelled) { cancel(); } else { expire(); }
        long replacement = hold(otherUserId, seatId);
        processor.process(attempt.id());
        var payment = transactions.get(attempt.id(), userId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.confirmationStatus().name()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(payment.bookingId()).isNull();
        assertThat(count("bookings")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM tbooker.reservations WHERE id = ?", String.class, replacement)).isEqualTo("HELD");
        assertSeat("HELD");
    }

    @Test
    void providerSuccessSurvivesApplicationOutcomePersistenceFailure() throws Exception {
        installFailure("payments", "UPDATE");
        try {
            pay(userId, reservationId, "receipt-recovery", "UPI").andExpect(status().isInternalServerError());
            assertThat(count("mock_gateway_receipts")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM tbooker.payments", String.class)).isEqualTo("PENDING");
            assertThat(count("bookings")).isZero();
        } finally { removeFailure("payments"); }
        pay(userId, reservationId, "receipt-recovery", "UPI").andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"));
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void successfulPaymentPersistsWhenBookingInsertRollsBackAndRetryCompletesIt() throws Exception {
        installFailure("bookings", "INSERT");
        try {
            pay(userId, reservationId, "booking-recovery", "UPI").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUCCESS")).andExpect(jsonPath("$.confirmationStatus").value("PENDING"))
                    .andExpect(jsonPath("$.bookingId").isEmpty());
            assertThat(count("bookings")).isZero();
            assertSeat("HELD");
        } finally { removeFailure("bookings"); }
        pay(userId, reservationId, "booking-recovery", "UPI").andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"));
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
    }

    @Test
    void staleEventsCannotUndoSuccessAndNewerSuccessCanCorrectFailure() throws Exception {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.CARD, "events").payment();
        registry.get(PaymentMethod.CARD).charge(attempt.command());
        var failure = registry.get(PaymentMethod.CARD).verify(attempt.command());
        processor.process(attempt.id());
        assertThat(transactions.get(attempt.id(), userId).status()).isEqualTo(PaymentStatus.FAILED);
        jdbc.update("UPDATE tbooker.mock_gateway_receipts SET status = 'SUCCESS', version = 2 WHERE request_id = ?", attempt.command().requestId());
        processor.process(attempt.id());
        transactions.recordVerified(attempt.id(), failure); // Simulate an older already-verified delivery arriving late.
        processor.process(attempt.id());
        assertThat(transactions.get(attempt.id(), userId).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void aSecondFinancialSuccessIsRecordedButCannotCreateASecondBooking() throws Exception {
        var old = body(pay(userId, reservationId, "old-failure", "CARD").andExpect(status().isCreated()));
        var winner = body(pay(userId, reservationId, "winner", "UPI").andExpect(status().isCreated()));
        var attempt = transactions.snapshot(old.get("id").asLong());
        jdbc.update("UPDATE tbooker.mock_gateway_receipts SET status = 'SUCCESS', version = 2 WHERE request_id = ?", attempt.command().requestId());
        processor.process(attempt.id());
        assertThat(transactions.get(attempt.id(), userId).confirmationStatus().name()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(transactions.get(attempt.id(), userId).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT confirmed_payment_id FROM tbooker.reservations WHERE id = ?", Long.class, reservationId))
                .isEqualTo(winner.get("id").asLong());
    }

    @Test
    void receiptParametersMustMatchStoredPaymentAndGatewayCannotRunInsideApplicationTransaction() {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "verify").payment();
        registry.get(PaymentMethod.UPI).charge(attempt.command());
        var receipt = registry.get(PaymentMethod.UPI).verify(attempt.command());
        assertThatThrownBy(() -> transactions.recordVerified(attempt.id(), new GatewayResult(receipt.requestId(),
                receipt.providerReference(), 1, receipt.currency(), PaymentStatus.SUCCESS, 1)))
                .isInstanceOf(com.example.Tbooker.exception.GatewayVerificationException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> registry.get(PaymentMethod.UPI).charge(attempt.command())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> service.initiate(userId, reservationId,
                new PaymentRequest(PaymentMethod.UPI), "verify"))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("bookings")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentInitiationCreatesOneAttemptAndOneBooking(boolean sameKey) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String key = sameKey ? "concurrent" : "concurrent-" + i;
                requests.add(() -> client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/reservations/" + reservationId + "/payments"))
                        .header("Authorization", bearer(userId)).header("Idempotency-Key", key)
                        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"UPI\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var results = contend(requests);
            assertThat(results.stream().filter(code -> code == 201)).hasSize(1);
            assertThat(results.stream().filter(code -> code == (sameKey ? 200 : 409))).hasSize(19);
        }
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateResultDeliveryConfirmsExactlyOnce() throws Exception {
        long id = fixture.verifyOnly(userId, reservationId);
        List<Callable<Integer>> callbacks = new ArrayList<>();
        for (int i = 0; i < 20; i++) { callbacks.add(() -> { processor.process(id); return 200; }); }
        assertThat(contend(callbacks)).containsOnly(200).hasSize(20);
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
        assertSeat("BOOKED");
    }

    @Test
    void databaseProtectsPendingAttemptAndPaymentReservationOwnership() {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "constraints").payment();
        assertThatThrownBy(() -> jdbc.update("UPDATE tbooker.payments SET user_id = ? WHERE id = ?", otherUserId, attempt.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tbooker.payments(user_id,reservation_id,method,amount_minor,currency,idempotency_key,
                    gateway_request_id,created_at,updated_at)
                SELECT user_id,reservation_id,method,amount_minor,currency,'duplicate',?,created_at,updated_at
                FROM tbooker.payments WHERE id = ?
                """, UUID.randomUUID(), attempt.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptInsertFailureDoesNotInvokeGatewayOrChangeTheHold() throws Exception {
        installFailure("payments", "INSERT");
        try {
            pay(userId, reservationId, "prepare-rollback", "UPI").andExpect(status().isInternalServerError());
            assertThat(count("payments")).isZero();
            assertThat(count("mock_gateway_receipts")).isZero();
            assertThat(count("bookings")).isZero();
            assertSeat("HELD");
        } finally { removeFailure("payments"); }
        pay(userId, reservationId, "prepare-rollback", "UPI").andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"));
    }

    @Test
    void failureMarkingPaymentConfirmedRollsBackAllAllocationWrites() {
        long id = fixture.verifyOnly(userId, reservationId);
        installFailure("payments", "UPDATE");
        try {
            processor.confirmKnownSuccess(id);
            assertThat(count("bookings")).isZero();
            assertSeat("HELD");
            assertThat(jdbc.queryForObject("SELECT status FROM tbooker.reservations WHERE id=?", String.class, reservationId))
                    .isEqualTo("HELD");
            assertThat(transactions.get(id, userId).status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(transactions.get(id, userId).confirmationStatus().name()).isEqualTo("PENDING");
        } finally { removeFailure("payments"); }
        processor.confirmKnownSuccess(id);
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(transactions.get(id, userId).confirmationStatus().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void retryRecoversAnAttemptCommittedBeforeTheGatewayWasCalled() throws Exception {
        long id = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "before-charge").payment().id();
        assertThat(count("mock_gateway_receipts")).isZero();
        pay(userId, reservationId, "before-charge", "UPI").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"));
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void pendingProviderResultCanBeSafelyRetriedUntilVerifiedSuccess() throws Exception {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "pending").payment();
        registry.get(PaymentMethod.UPI).charge(attempt.command());
        // Test-only provider ledger control; this is deliberately not a client API.
        jdbc.update("UPDATE tbooker.mock_gateway_receipts SET status='PENDING' WHERE request_id=?", attempt.command().requestId());
        pay(userId, reservationId, "pending", "UPI").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.confirmationStatus").value("NOT_READY"));
        confirm().andExpect(status().isConflict());
        pay(userId, reservationId, "other-pending", "CARD").andExpect(status().isConflict());
        assertThat(count("bookings")).isZero();
        assertSeat("HELD");
        jdbc.update("UPDATE tbooker.mock_gateway_receipts SET status='SUCCESS', version=2 WHERE request_id=?", attempt.command().requestId());
        pay(userId, reservationId, "pending", "UPI").andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"));
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void successfulPaymentAwaitingConfirmationBecomesReconciliationWhenHoldExpires() throws Exception {
        long id = fixture.verifyOnly(userId, reservationId);
        expire();
        processor.confirmKnownSuccess(id);
        assertThat(transactions.get(id, userId).confirmationStatus().name()).isEqualTo("RECONCILIATION_REQUIRED");
        confirm().andExpect(status().isConflict());
        processor.process(id);
        assertThat(transactions.get(id, userId).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("bookings")).isZero();
    }

    @Test
    void resultVersionsRejectContradictionsAndNeverRegressFinancialSuccess() {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "versions").payment();
        registry.get(PaymentMethod.UPI).charge(attempt.command());
        var success = registry.get(PaymentMethod.UPI).verify(attempt.command());
        processor.process(attempt.id());
        var sameVersionFailure = new GatewayResult(success.requestId(), success.providerReference(), success.amountMinor(),
                success.currency(), PaymentStatus.FAILED, success.version());
        assertThatThrownBy(() -> transactions.recordVerified(attempt.id(), sameVersionFailure))
                .isInstanceOf(com.example.Tbooker.exception.GatewayVerificationException.class);
        transactions.recordVerified(attempt.id(), new GatewayResult(success.requestId(), success.providerReference(),
                success.amountMinor(), success.currency(), PaymentStatus.FAILED, success.version() + 1));
        assertThat(transactions.get(attempt.id(), userId).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyOnDifferentSeatsHasOneWinner() throws Exception {
        long otherSeat = jdbc.queryForObject("SELECT MAX(id) FROM tbooker.show_seats", Long.class);
        long otherReservation = hold(userId, otherSeat);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM tbooker.show_seats WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
                statement.setLong(1, seatId); statement.setLong(2, otherSeat); statement.executeQuery().close();
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (long reservation : List.of(reservationId, otherReservation)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                    return pay(userId, reservation, "cross-seat", "UPI").andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND wait_event_type='Lock' AND query LIKE '%tbooker.show_seats%' AND query LIKE '%FOR UPDATE%'", Integer.class)).isEqualTo(2));
            blocker.commit();
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("mock_gateway_receipts")).isEqualTo(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void v6UpgradePreservesHistoricalBookingsAndDoesNotInventPayments() throws Exception {
        jdbc.execute("CREATE DATABASE phase8_upgrade");
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/phase8_upgrade";
        try {
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .defaultSchema("public").target("5").load().migrate();
            String snapshot = "SELECT row_to_json(b)::text FROM tbooker.bookings b UNION ALL "
                    + "SELECT row_to_json(u)::text FROM tbooker.users u UNION ALL "
                    + "SELECT row_to_json(s)::text FROM tbooker.show_seats s UNION ALL "
                    + "SELECT (to_jsonb(r)-'confirmed_payment_id')::text FROM tbooker.reservations r ORDER BY 1";
            List<String> before = new ArrayList<>();
            try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute(new ClassPathResource("db/dev/R__sample_catalogue.sql").getContentAsString(StandardCharsets.UTF_8));
                statement.execute("INSERT INTO tbooker.bookings(user_id,show_seat_id,status,created_at) "
                        + "SELECT (SELECT MIN(id) FROM tbooker.users),MIN(id),'CONFIRMED',clock_timestamp() FROM tbooker.show_seats");
                statement.execute("UPDATE tbooker.show_seats SET status='BOOKED' WHERE id=(SELECT MIN(id) FROM tbooker.show_seats)");
                statement.execute("INSERT INTO tbooker.reservations(user_id,show_seat_id,status,created_at,expires_at,booking_id) "
                        + "SELECT user_id,show_seat_id,'CONFIRMED',created_at,created_at+interval '5 minutes',id FROM tbooker.bookings");
                try (var rows = statement.executeQuery(snapshot)) { while (rows.next()) { before.add(rows.getString(1)); } }
            }
            var upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).defaultSchema("public").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            List<String> after = new ArrayList<>();
            try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery(snapshot)) { while (rows.next()) { after.add(rows.getString(1)); } }
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tbooker.payments")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
                try (var rows = statement.executeQuery("SELECT confirmed_payment_id FROM tbooker.reservations")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
                }
            }
            assertThat(after).isEqualTo(before);
        } finally { jdbc.execute("DROP DATABASE phase8_upgrade WITH (FORCE)"); }
    }

    private List<Integer> contend(List<Callable<Integer>> calls) throws Exception {
        var executor = Executors.newFixedThreadPool(calls.size());
        var ready = new CountDownLatch(calls.size()); var start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var query = blocker.prepareStatement("SELECT id FROM tbooker.show_seats WHERE id = ? FOR UPDATE")) {
                query.setLong(1, seatId); query.executeQuery().close();
            }
            for (var call : calls) { futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                return call.call();
            })); }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("""
                            SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'
                            AND query LIKE '%tbooker.show_seats%' AND query LIKE '%FOR UPDATE%'
                            """, Integer.class)).isEqualTo(calls.size()));
            blocker.commit();
            List<Integer> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown(); futures.forEach(f -> f.cancel(true)); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    private long hold(Long owner, Long seat) throws Exception {
        return body(mvc.perform(post("/api/shows/{id}/reservations", showId).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"showSeatId\":" + seat + "}")).andExpect(status().isCreated())).get("id").asLong();
    }
    private ResultActions pay(Long owner, long reservation, String key, String method) throws Exception {
        return mvc.perform(post("/api/reservations/{id}/payments", reservation).header("Authorization", bearer(owner))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"" + method + "\"}"));
    }
    private ResultActions confirm() throws Exception {
        return mvc.perform(post("/api/reservations/{id}/confirmation", reservationId).header("Authorization", bearer(userId)));
    }
    private void cancel() throws Exception {
        mvc.perform(delete("/api/reservations/{id}", reservationId).header("Authorization", bearer(userId))).andExpect(status().isNoContent());
    }
    private void expire() {
        jdbc.update("UPDATE tbooker.reservations SET created_at=clock_timestamp()-interval '301 seconds', expires_at=clock_timestamp()-interval '1 second' WHERE id=?", reservationId);
    }
    private JsonNode body(ResultActions action) throws Exception { return json.readTree(action.andReturn().getResponse().getContentAsString()); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM tbooker." + table, Integer.class); }
    private void assertSeat(String state) { assertThat(jdbc.queryForObject("SELECT status FROM tbooker.show_seats WHERE id=?", String.class, seatId)).isEqualTo(state); }
    private void installFailure(String table, String event) {
        jdbc.execute("CREATE FUNCTION tbooker.fail_payment_test() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'Forced database failure' USING ERRCODE='23514'; END $$; "
                + "CREATE TRIGGER fail_payment_test AFTER " + event + " ON tbooker." + table + " FOR EACH ROW EXECUTE FUNCTION tbooker.fail_payment_test()");
    }
    private void removeFailure(String table) {
        jdbc.execute("DROP TRIGGER fail_payment_test ON tbooker." + table);
        jdbc.execute("DROP FUNCTION tbooker.fail_payment_test()");
    }
}
