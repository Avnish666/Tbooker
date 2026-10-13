package com.example.Tbooker;

import com.example.Tbooker.dto.ReservationRequest;
import com.example.Tbooker.service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"messaging.enabled=true", "messaging.scheduling-enabled=true", "messaging.poll-ms=200",
        "messaging.topic-check-ms=1000", "messaging.metrics-ms=100", "messaging.retry-initial=PT0.3S",
        "messaging.retry-max=PT1S", "reservations.cleanup.enabled=false",
        "spring.kafka.producer.properties.delivery.timeout.ms=1500",
        "spring.kafka.producer.properties.request.timeout.ms=500", "spring.kafka.producer.properties.max.block.ms=300",
        "logging.level.org.apache.kafka=ERROR", "logging.level.org.springframework.kafka=ERROR"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(PaymentTestFixture.class)
@Testcontainers
class MessagingRecoveryIntegrationTests extends SecurityTestSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Container
    static final KafkaContainer kafka=new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE","false");
    private static final AtomicBoolean paused=new AtomicBoolean();
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> {
            // Start Spring with an unreachable real broker, before topics can be created.
            if(paused.compareAndSet(false,true)) kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
            return kafka.getBootstrapServers();
        });
    }
    @AfterAll
    static void restoreBroker() {
        if(kafka.isRunning() && Boolean.TRUE.equals(kafka.getDockerClient().inspectContainerCmd(kafka.getContainerId()).exec().getState().getPaused()))
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    private final ReservationService reservations;
    private final PaymentTestFixture payments;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final MockMvc mvc;
    @Autowired
    MessagingRecoveryIntegrationTests(ReservationService reservations, PaymentTestFixture payments,
            JdbcTemplate jdbc, MeterRegistry meters, MockMvc mvc) {
        this.reservations=reservations;this.payments=payments;this.jdbc=jdbc;this.meters=meters;this.mvc=mvc;
    }

    @Test
    void startupAndBookingWorkWithoutKafkaThenScheduledPublicationAndConsumersRecover() throws Exception {
        try {
            Long user=jdbc.queryForObject("SELECT MIN(id) FROM tbooker.users",Long.class);
            Long show=jdbc.queryForObject("SELECT MIN(id) FROM tbooker.movie_shows",Long.class);
            Long seat=jdbc.queryForObject("SELECT MIN(id) FROM tbooker.show_seats",Long.class);
            var reservation=reservations.create(show,user,new ReservationRequest(seat));
            payments.verifyOnly(user,reservation.id());
            var booking=reservations.confirm(reservation.id(),user);
            assertThat(booking.id()).isPositive();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.outbox_events",Integer.class)).isEqualTo(1);
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
            await().atMost(Duration.ofSeconds(10)).until(() -> meters.find("booking.outbox.publish.failures").counter()!=null);
            await().atMost(Duration.ofSeconds(5)).until(() -> meters.get("booking.outbox.pending").gauge().value()==1);
        } finally { restoreBroker(); }
        // Neither a manual publisher call nor manual topic creation is used here.
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.outbox_events WHERE status='PUBLISHED'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.booking_notifications",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM tbooker.booking_analytics",Integer.class)).isEqualTo(1);
            assertThat(meters.get("booking.outbox.pending").gauge().value()).isZero();
        });
        mvc.perform(get("/actuator/health/messaging")).andExpect(status().isOk());
        assertThat(meters.get("booking.outbox.publish.delay").timer().count()).isEqualTo(1);
        assertThat(meters.getMeters().stream().map(m -> m.getId().getName()))
                .anyMatch(name -> name.startsWith("kafka.consumer."));
        assertThat(meters.getMeters().stream().map(m -> m.getId().getName()))
                .anyMatch(name -> name.startsWith("kafka.consumer.") && name.contains("records.lag"));
    }
}
