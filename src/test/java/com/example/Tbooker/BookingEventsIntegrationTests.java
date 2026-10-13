package com.example.Tbooker;

import com.example.Tbooker.config.MessagingProperties;
import com.example.Tbooker.event.BookingConfirmedEvent;
import com.example.Tbooker.event.BookingEventCodec;
import com.example.Tbooker.messaging.OutboxMetrics;
import com.example.Tbooker.messaging.OutboxPublisher;
import com.example.Tbooker.repository.OutboxRepository;
import com.example.Tbooker.service.BookingEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"messaging.enabled=true", "messaging.scheduling-enabled=false",
        "messaging.retry-initial=PT2S", "reservations.cleanup.enabled=false",
        "payments.mock.card-outcome=FAILED", "spring.datasource.hikari.maximum-pool-size=24",
        "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(PaymentTestFixture.class)
@Testcontainers
class BookingEventsIntegrationTests extends SecurityTestSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PaymentTestFixture payments;
    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;
    private final BookingEventCodec codec;
    private final BookingEventProcessor processor;
    private final KafkaTemplate<Object,Object> template;
    private final KafkaListenerEndpointRegistry listeners;
    private final MessagingProperties properties;
    private final OutboxMetrics metrics;
    private final MeterRegistry meters;
    private final Executor completions;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private Long userId, showId, seatId;
    private Map<TopicPartition,Long> dltStart;

    @Autowired
    BookingEventsIntegrationTests(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, PaymentTestFixture payments,
            OutboxRepository outbox, OutboxPublisher publisher, BookingEventCodec codec, BookingEventProcessor processor,
            KafkaTemplate<Object,Object> template, KafkaListenerEndpointRegistry listeners, MessagingProperties properties,
            OutboxMetrics metrics, MeterRegistry meters, @Qualifier("outboxCompletionExecutor") Executor completions,
            DataSource dataSource, PlatformTransactionManager transactions) {
        this.mvc=mvc; this.jdbc=jdbc; this.json=json; this.payments=payments; this.outbox=outbox;
        this.publisher=publisher; this.codec=codec; this.processor=processor; this.template=template;
        this.listeners=listeners; this.properties=properties; this.metrics=metrics; this.meters=meters;
        this.completions=completions; this.dataSource=dataSource; this.transaction=new TransactionTemplate(transactions);
    }

    @BeforeEach
    void reset() throws Exception {
        await().atMost(Duration.ofSeconds(15)).until(() -> publisher.inFlightCount()==0);
        listeners.stop();
        try (var admin=admin()) {
            for (String topic : List.of(properties.topic(), properties.deadLetterTopic())) {
                var offsets=endOffsets(topic);
                admin.deleteRecords(offsets.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        e -> RecordsToDelete.beforeOffset(e.getValue())))).all().get(15,TimeUnit.SECONDS);
            }
        }
        payments.clear();
        jdbc.update("DELETE FROM tbooker.processed_events");
        jdbc.update("DELETE FROM tbooker.booking_notifications");
        jdbc.update("UPDATE tbooker.booking_analytics SET confirmed_count=0 WHERE id=1");
        jdbc.update("DELETE FROM tbooker.reservations");
        jdbc.update("DELETE FROM tbooker.bookings");
        jdbc.update("UPDATE tbooker.show_seats SET status='AVAILABLE'");
        userId=jdbc.queryForObject("SELECT MIN(id) FROM tbooker.users",Long.class);
        showId=jdbc.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows",Long.class);
        seatId=jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats",Long.class);
        dltStart=endOffsets(properties.deadLetterTopic());
        listeners.start();
        await().atMost(Duration.ofSeconds(20)).until(() -> listeners.getListenerContainers().stream()
                .allMatch(c -> c.getAssignedPartitions()!=null && c.getAssignedPartitions().size()==properties.partitions()));
    }

    @Test
    void confirmationAndRetriesCreateOneLogicalEventWithMatchingPayload() throws Exception {
        long reservation=paidHold(seatId);
        long booking=confirm(reservation);
        confirm(reservation);
        assertThat(count("outbox_events")).isEqualTo(1);
        var event=event();
        assertThat(event.bookingId()).isEqualTo(booking);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.showId()).isEqualTo(showId);
        assertThat(event.showSeatId()).isEqualTo(seatId);
        assertThat(event.eventType()).isEqualTo("BookingConfirmed");
        assertThat(event.version()).isEqualTo(1);
        assertThat(state()).isEqualTo("PENDING");
        assertThat(event.occurredAt()).isEqualTo(jdbc.queryForObject("SELECT created_at FROM tbooker.bookings",java.sql.Timestamp.class).toInstant());
        assertThat(publisher.publishBatch()).isEqualTo(1);
        awaitProjection(1);
        awaitConsumed();
        assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(meters.find("booking.outbox.publish.delay").timer().count()).isPositive();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bookingAndEventRollBackTogetherOnOutboxFailure(boolean deferred) throws Exception {
        long reservation=paidHold(seatId);
        jdbc.execute("CREATE FUNCTION tbooker.reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test outbox failure' USING ERRCODE='23514'; END $$");
        jdbc.execute(deferred
                ? "CREATE CONSTRAINT TRIGGER reject_outbox AFTER INSERT ON tbooker.outbox_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tbooker.reject_outbox()"
                : "CREATE TRIGGER reject_outbox BEFORE INSERT ON tbooker.outbox_events FOR EACH ROW EXECUTE FUNCTION tbooker.reject_outbox()");
        try {
            mvc.perform(post("/api/reservations/{id}/confirmation",reservation).header("Authorization",bearer(userId)))
                    .andExpect(status().isInternalServerError());
            assertThat(count("bookings")).isZero();
            assertThat(count("outbox_events")).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM tbooker.show_seats WHERE id=?",String.class,seatId)).isEqualTo("HELD");
            assertThat(jdbc.queryForObject("SELECT status FROM tbooker.payments",String.class)).isEqualTo("SUCCESS");
        } finally {
            jdbc.execute("DROP TRIGGER reject_outbox ON tbooker.outbox_events");
            jdbc.execute("DROP FUNCTION tbooker.reject_outbox()");
        }
        confirm(reservation);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void failedPaymentCannotCreateBookingEvent() throws Exception {
        long reservation=hold(seatId);
        mvc.perform(post("/api/reservations/{id}/payments",reservation).header("Authorization",bearer(userId))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CARD\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(count("bookings")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void brokerOutageDoesNotRollBackBookingAndPublisherRetriesWithoutHoldingDatabaseLocks() throws Exception {
        double failures=counter("booking.outbox.publish.failures");
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            confirm(paidHold(seatId));
            assertThat(count("bookings")).isEqualTo(1);
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
            mvc.perform(get("/actuator/health/messaging")).andExpect(status().isServiceUnavailable());
            assertThat(publisher.publishBatch()).isEqualTo(1);
            try (var connection=dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var query=connection.createStatement()) {
                    query.execute("SELECT * FROM tbooker.outbox_events FOR UPDATE NOWAIT");
                    query.execute("SELECT * FROM tbooker.show_seats FOR UPDATE NOWAIT");
                }
                connection.rollback();
            }
            await().atMost(Duration.ofSeconds(15)).until(() -> state().equals("PENDING"));
            assertThat(counter("booking.outbox.publish.failures")).isGreaterThan(failures);
            assertThat(publisher.publishBatch()).isZero(); // bounded backoff has not elapsed
            assertThat(jdbc.queryForObject("SELECT last_error IS NOT NULL AND next_attempt_at>clock_timestamp() FROM tbooker.outbox_events",Boolean.class)).isTrue();
        } finally { kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(30)).until(() -> { publisher.publishBatch(); return state().equals("PUBLISHED"); });
        awaitProjection(1);
        awaitConsumed();
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void twentyDuplicateKafkaDeliveriesDoNotRepeatEitherProjection() throws Exception {
        confirm(paidHold(seatId));
        var event=event();
        List<CompletableFuture<?>> sends=new ArrayList<>();
        for(int i=0;i<20;i++) sends.add(template.send(properties.topic(),event.bookingId().toString(),bytes(event)));
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(20,TimeUnit.SECONDS);
        awaitConsumed();
        assertThat(count("booking_notifications")).isEqualTo(1);
        assertThat(analytics()).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(dltCount()).isZero();
    }

    @RepeatedTest(3)
    void concurrentDatabaseConsumerTransactionsDeduplicateDurably() throws Exception {
        var event=synthetic(10001L);
        var executor=Executors.newFixedThreadPool(20);
        var ready=new CountDownLatch(20); var start=new CountDownLatch(1);
        List<Future<?>> futures=new ArrayList<>();
        try {
            for(int i=0;i<20;i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
                processor.notifyBooking(event); processor.countBooking(event); return null;
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            for(var f:futures) f.get(20,TimeUnit.SECONDS);
            assertThat(count("booking_notifications")).isEqualTo(1);
            assertThat(analytics()).isEqualTo(1);
            assertThat(count("processed_events")).isEqualTo(2);
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(strings={"booking_notifications","booking_analytics"})
    void transientConsumerFailureRetriesAndRollsBackDeduplication(String table) throws Exception {
        double failures=counter("booking.consumer.failures");
        installProjectionFailure(table,false);
        try {
            var event=synthetic(10002L);
            template.send(properties.topic(),event.bookingId().toString(),bytes(event)).get(10,TimeUnit.SECONDS);
            awaitProjection(1); awaitConsumed();
            assertThat(jdbc.queryForObject("SELECT last_value FROM tbooker.consumer_failures",Integer.class)).isEqualTo(3);
            assertThat(counter("booking.consumer.failures")-failures).isEqualTo(2);
            assertThat(count("processed_events")).isEqualTo(2);
            assertThat(dltCount()).isZero();
        } finally { removeProjectionFailure(table); }
    }

    @Test
    void exhaustedRetriesGoToDltWithOriginalPayloadAndConsumerInformation() throws Exception {
        installProjectionFailure("booking_notifications",true);
        var event=synthetic(10003L);
        try {
            template.send(properties.topic(),0,event.bookingId().toString(),bytes(event)).get(10,TimeUnit.SECONDS);
            awaitConsumed();
            var failed=readDlt(1).getFirst();
            assertThat(failed.value()).isEqualTo(bytes(event));
            assertThat(failed.key()).isEqualTo(event.bookingId().toString());
            assertThat(header(failed,KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo(properties.notificationGroup());
            assertThat(header(failed,KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(properties.topic());
            assertThat(failed.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT last_value FROM tbooker.consumer_failures",Integer.class)).isEqualTo(4);
            assertThat(count("booking_notifications")).isZero();
            assertThat(analytics()).isEqualTo(1);
            assertThat(count("processed_events")).isEqualTo(1);
        } finally { removeProjectionFailure("booking_notifications"); }
        // Authorized operator-style replay preserves the event ID; the successful group deduplicates it.
        template.send(properties.topic(),event.bookingId().toString(),bytes(event)).get(10,TimeUnit.SECONDS);
        awaitProjection(1); awaitConsumed();
        assertThat(count("processed_events")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"malformed","missing","version","key","tombstone","binary","large"})
    void poisonRecordReachesBothGroupsDltAndDoesNotBlockFollowingRecord(String kind) throws Exception {
        var invalid=synthetic(10004L);
        byte[] payload=switch(kind) {
            case "malformed" -> "{invalid".getBytes(StandardCharsets.UTF_8);
            case "missing" -> "{}".getBytes(StandardCharsets.UTF_8);
            case "version" -> codec.encode(invalid).replace("\"version\":1","\"version\":2").getBytes(StandardCharsets.UTF_8);
            case "tombstone" -> null;
            case "binary" -> new byte[]{(byte)0xff,(byte)0xfe};
            case "large" -> ("{\"padding\":\""+"x".repeat(62000)+"\"}").getBytes(StandardCharsets.UTF_8);
            default -> bytes(invalid);
        };
        String key=kind.equals("key") ? "9999" : invalid.bookingId().toString();
        template.send(new ProducerRecord<>(properties.topic(),0,key,payload)).get(10,TimeUnit.SECONDS);
        var valid=synthetic(10005L);
        template.send(properties.topic(),0,valid.bookingId().toString(),bytes(valid)).get(10,TimeUnit.SECONDS);
        awaitConsumed();
        var failures=readDlt(2);
        assertThat(failures).allSatisfy(record -> assertThat(record.value()).isEqualTo(payload));
        assertThat(failures.stream().map(f -> header(f,KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))).containsExactlyInAnyOrder(properties.notificationGroup(),properties.analyticsGroup());
        assertThat(count("booking_notifications")).isEqualTo(1);
        assertThat(analytics()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void expiredLeaseSurvivesPublisherRestartIncludingAckBeforeStateUpdate(boolean acceptedBeforeCrash) throws Exception {
        confirm(paidHold(seatId));
        var claim=outbox.claim(1,properties.lease()).getFirst();
        if(acceptedBeforeCrash) {
            template.send(properties.topic(),claim.bookingId().toString(),claim.payload().getBytes(StandardCharsets.UTF_8)).get(10,TimeUnit.SECONDS);
            awaitProjection(1);
        }
        jdbc.update("UPDATE tbooker.outbox_events SET lease_until=clock_timestamp()-interval '1 second'");
        var restarted=newPublisher();
        assertThat(restarted.publishBatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).until(() -> state().equals("PUBLISHED") && restarted.inFlightCount()==0);
        awaitConsumed();
        assertThat(jdbc.queryForObject("SELECT attempts FROM tbooker.outbox_events",Integer.class)).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("booking_notifications")).isEqualTo(1);
        assertThat(analytics()).isEqualTo(1);
    }

    @Test
    void stalePublisherCannotOverwriteANewerLease() throws Exception {
        confirm(paidHold(seatId));
        var old=outbox.claim(1,properties.lease()).getFirst();
        jdbc.update("UPDATE tbooker.outbox_events SET lease_until=clock_timestamp()-interval '1 second'");
        var current=outbox.claim(1,properties.lease()).getFirst();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(outbox.published(old)).isFalse();
        assertThat(outbox.failed(old,Duration.ofSeconds(1),"OldFailure")).isFalse();
        assertThat(state()).isEqualTo("IN_FLIGHT");
        template.send(properties.topic(),current.bookingId().toString(),current.payload().getBytes(StandardCharsets.UTF_8)).get(10,TimeUnit.SECONDS);
        assertThat(outbox.published(current)).isTrue();
        awaitProjection(1);
    }

    @Test
    void multiplePublishersSkipLockedRowsAndLoseNoEvents() throws Exception {
        for(Long seat:jdbc.queryForList("SELECT id FROM tbooker.show_seats ORDER BY id",Long.class)) confirm(paidHold(seat));
        var first=newPublisher(); var second=newPublisher();
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var query=blocker.createStatement()) { query.execute("SELECT event_id FROM tbooker.outbox_events ORDER BY created_at LIMIT 1 FOR UPDATE"); }
            var one=executor.submit(() -> { start.await(); return first.publishBatch(); });
            var two=executor.submit(() -> { start.await(); return second.publishBatch(); });
            start.countDown();
            assertThat(one.get(10,TimeUnit.SECONDS)+two.get(10,TimeUnit.SECONDS)).isEqualTo(7);
            blocker.commit();
            await().atMost(Duration.ofSeconds(15)).until(() -> first.inFlightCount()==0 && second.inFlightCount()==0);
            assertThat(first.publishBatch()).isEqualTo(1);
            awaitProjection(8); awaitConsumed();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.outbox_events WHERE status='PUBLISHED'",Integer.class)).isEqualTo(8);
            assertThat(count("processed_events")).isEqualTo(16);
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void databaseRejectsDuplicateLogicalEvents() throws Exception {
        confirm(paidHold(seatId));
        var original=event();
        var duplicate=new BookingConfirmedEvent(UUID.randomUUID(),original.eventType(),1,original.bookingId(),userId,showId,seatId,original.occurredAt());
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> outbox.append(duplicate,codec.encode(duplicate))))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_outbox_booking_event");
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void databaseRejectsNullPayloadIdentity() throws Exception {
        confirm(paidHold(seatId));
        assertThatThrownBy(() -> jdbc.update("UPDATE tbooker.outbox_events SET payload=jsonb_set(payload,'{eventId}','null'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_outbox_payload");
        assertThat(event().eventId()).isNotNull();
    }

    @Test
    void databaseFailureAfterBrokerAckLeavesRecoverableLeaseAndNoDuplicateEffects() throws Exception {
        confirm(paidHold(seatId));
        double before=counter("booking.outbox.state.failures");
        jdbc.execute("CREATE FUNCTION tbooker.reject_published() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='PUBLISHED' THEN RAISE EXCEPTION 'test ack persistence failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_published BEFORE UPDATE ON tbooker.outbox_events FOR EACH ROW EXECUTE FUNCTION tbooker.reject_published()");
        try {
            publisher.publishBatch();
            await().atMost(Duration.ofSeconds(15)).until(() -> counter("booking.outbox.state.failures")>before && publisher.inFlightCount()==0);
            assertThat(state()).isEqualTo("IN_FLIGHT");
            awaitProjection(1);
        } finally {
            jdbc.execute("DROP TRIGGER reject_published ON tbooker.outbox_events");
            jdbc.execute("DROP FUNCTION tbooker.reject_published()");
        }
        jdbc.update("UPDATE tbooker.outbox_events SET lease_until=clock_timestamp()-interval '1 second'");
        publisher.publishBatch();
        await().atMost(Duration.ofSeconds(15)).until(() -> state().equals("PUBLISHED"));
        awaitConsumed();
        assertThat(count("booking_notifications")).isEqualTo(1);
        assertThat(analytics()).isEqualTo(1);
    }

    @Test
    void failedDltPublicationCannotAdvanceOffsetsAndRecoversWhenTopicReturns() throws Exception {
        double failures=counter("booking.consumer.failures");
        double deadLetters=counter("booking.consumer.dead.letters");
        try(var admin=admin()) {
            admin.deleteTopics(List.of(properties.deadLetterTopic())).all().get(15,TimeUnit.SECONDS);
            try {
                var result=template.send(properties.topic(),0,"10020","{invalid".getBytes(StandardCharsets.UTF_8)).get(10,TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(20)).until(() -> counter("booking.consumer.failures")>=failures+4);
                for(String group:List.of(properties.notificationGroup(),properties.analyticsGroup())) {
                    var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
                    var committed=offsets.get(new TopicPartition(properties.topic(),0));
                    if(committed!=null) assertThat(committed.offset()).isLessThanOrEqualTo(result.getRecordMetadata().offset());
                }
                assertThat(counter("booking.consumer.dead.letters")).isEqualTo(deadLetters);
            } finally {
                dltStart=new HashMap<>();
                for(int i=0;i<properties.partitions();i++) dltStart.put(new TopicPartition(properties.deadLetterTopic(),i),0L);
                admin.createTopics(List.of(new NewTopic(properties.deadLetterTopic(),properties.partitions(),(short)1)))
                        .all().get(15,TimeUnit.SECONDS);
            }
        }
        awaitConsumed();
        readDlt(2);
        var valid=synthetic(10021L);
        template.send(properties.topic(),0,valid.bookingId().toString(),bytes(valid)).get(10,TimeUnit.SECONDS);
        awaitProjection(1); awaitConsumed();
    }

    @Test
    void publisherRejectsAnAmbientDatabaseTransaction() throws Exception {
        confirm(paidHold(seatId));
        assertThatThrownBy(() -> transaction.execute(tx -> publisher.publishBatch())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(state()).isEqualTo("PENDING");
    }

    @Test
    void operationalMetricsRequireAnAdminJwt() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization",bearer(userId))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics/booking.outbox.pending").header("Authorization",bearer(userId,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("booking.outbox.pending"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void reusedEventOrBookingIdentityIsDeadLetteredWithoutChangingProjection(boolean newEventId) throws Exception {
        var original=synthetic(10006L);
        template.send(properties.topic(),original.bookingId().toString(),bytes(original)).get(10,TimeUnit.SECONDS);
        awaitProjection(1); awaitConsumed();
        var changed=new BookingConfirmedEvent(newEventId ? UUID.randomUUID() : original.eventId(),original.eventType(),1,
                original.bookingId(),newEventId ? userId : userId+100,showId,seatId,original.occurredAt());
        template.send(properties.topic(),changed.bookingId().toString(),bytes(changed)).get(10,TimeUnit.SECONDS);
        awaitConsumed(); readDlt(2);
        assertThat(analytics()).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT user_id FROM tbooker.booking_notifications",Long.class)).isEqualTo(userId);
    }

    @Test
    void v7UpgradePreservesBookingsAndSeedsAnalyticsWithoutInventingEvents() throws Exception {
        jdbc.execute("CREATE DATABASE phase10_upgrade");
        String url="jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/phase10_upgrade";
        try {
            Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).target("6").load().migrate();
            try(var connection=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var statement=connection.createStatement()) {
                statement.execute(new ClassPathResource("db/dev/R__sample_catalogue.sql").getContentAsString(StandardCharsets.UTF_8));
                statement.execute("INSERT INTO tbooker.bookings(user_id,show_seat_id,status,created_at) SELECT (SELECT MIN(id) FROM tbooker.users),MIN(id),'CONFIRMED',clock_timestamp() FROM tbooker.show_seats");
                statement.execute("UPDATE tbooker.show_seats SET status='BOOKED' WHERE id=(SELECT MIN(id) FROM tbooker.show_seats)");
            }
            var flyway=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            try(var connection=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var statement=connection.createStatement();
                    var rows=statement.executeQuery("SELECT (SELECT COUNT(*) FROM tbooker.bookings),(SELECT confirmed_count FROM tbooker.booking_analytics),(SELECT COUNT(*) FROM tbooker.outbox_events)")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(1); assertThat(rows.getInt(3)).isZero();
            }
        } finally { jdbc.execute("DROP DATABASE phase10_upgrade WITH (FORCE)"); }
    }

    private long hold(Long seat) throws Exception {
        var result=mvc.perform(post("/api/shows/{id}/reservations",showId).header("Authorization",bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"showSeatId\":"+seat+"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(result.getContentAsString()).get("id").asLong();
    }
    private long paidHold(Long seat) throws Exception { long id=hold(seat);payments.verifyOnly(userId,id);return id; }
    private long confirm(long id) throws Exception {
        var result=mvc.perform(post("/api/reservations/{id}/confirmation",id).header("Authorization",bearer(userId)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(result.getContentAsString()).get("id").asLong();
    }
    private BookingConfirmedEvent event() { return codec.decode(jdbc.queryForObject("SELECT aggregate_id::text FROM tbooker.outbox_events",String.class),jdbc.queryForObject("SELECT payload::text FROM tbooker.outbox_events",String.class).getBytes(StandardCharsets.UTF_8)); }
    private BookingConfirmedEvent synthetic(long booking) { return new BookingConfirmedEvent(UUID.randomUUID(),"BookingConfirmed",1,booking,userId,showId,seatId,Instant.now()); }
    private byte[] bytes(BookingConfirmedEvent event) { return codec.encode(event).getBytes(StandardCharsets.UTF_8); }
    private String state() { return jdbc.queryForObject("SELECT status FROM tbooker.outbox_events",String.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM tbooker."+table,Integer.class); }
    private long analytics() { return jdbc.queryForObject("SELECT confirmed_count FROM tbooker.booking_analytics",Long.class); }
    private double counter(String name) { var counter=meters.find(name).counter();return counter==null?0:counter.count(); }
    private OutboxPublisher newPublisher() { return new OutboxPublisher(outbox,template,properties,metrics,completions); }
    private void awaitProjection(int size) { await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {assertThat(count("booking_notifications")).isEqualTo(size);assertThat(analytics()).isEqualTo(size);}); }
    private AdminClient admin() { return AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers())); }
    private Map<TopicPartition,Long> endOffsets(String topic) throws Exception {
        try(var admin=admin()) {
            Map<TopicPartition,OffsetSpec> request=new HashMap<>();
            for(int i=0;i<properties.partitions();i++)request.put(new TopicPartition(topic,i),OffsetSpec.latest());
            return admin.listOffsets(request).all().get(10,TimeUnit.SECONDS).entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,e -> e.getValue().offset()));
        }
    }
    private void awaitConsumed() throws Exception {
        var ends=endOffsets(properties.topic());
        try(var admin=admin()) {
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                for(String group:List.of(properties.notificationGroup(),properties.analyticsGroup())) {
                    var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
                    for(var end:ends.entrySet())if(end.getValue()>0) {
                        assertThat(offsets).containsKey(end.getKey());
                        assertThat(offsets.get(end.getKey()).offset()).isGreaterThanOrEqualTo(end.getValue());
                    }
                }
            });
        }
    }
    private long dltCount() throws Exception { var ends=endOffsets(properties.deadLetterTopic());return ends.entrySet().stream().mapToLong(e -> e.getValue()-dltStart.get(e.getKey())).sum(); }
    private List<ConsumerRecord<String,byte[]>> readDlt(int count) throws Exception {
        var config=Map.<String,Object>of("bootstrap.servers",kafka.getBootstrapServers(),"group.id","test-dlt-"+UUID.randomUUID(),"enable.auto.commit",false);
        try(var consumer=new KafkaConsumer<String,byte[]>(config,new StringDeserializer(),new ByteArrayDeserializer())) {
            consumer.assign(dltStart.keySet());dltStart.forEach(consumer::seek);
            List<ConsumerRecord<String,byte[]>> records=new ArrayList<>();
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(records.size()<count && System.nanoTime()<deadline)consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            assertThat(records).hasSize(count);return records;
        }
    }
    private String header(ConsumerRecord<?,?> record,String name) { return new String(Objects.requireNonNull(record.headers().lastHeader(name)).value(),StandardCharsets.UTF_8); }
    private void installProjectionFailure(String table,boolean permanent) {
        jdbc.execute("CREATE SEQUENCE tbooker.consumer_failures");
        jdbc.execute("CREATE FUNCTION tbooker.fail_consumer() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF nextval('tbooker.consumer_failures') "+(permanent?"> 0":"<= 2")+" THEN RAISE EXCEPTION 'test transient failure' USING ERRCODE='40001'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_consumer AFTER "+(table.equals("booking_analytics")?"UPDATE":"INSERT")+" ON tbooker."+table+" FOR EACH ROW EXECUTE FUNCTION tbooker.fail_consumer()");
    }
    private void removeProjectionFailure(String table) { jdbc.execute("DROP TRIGGER fail_consumer ON tbooker."+table);jdbc.execute("DROP FUNCTION tbooker.fail_consumer()");jdbc.execute("DROP SEQUENCE tbooker.consumer_failures"); }
}
