package com.example.Tbooker.repository;

import com.example.Tbooker.event.BookingConfirmedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(BookingConfirmedEvent event, String payload) {
        jdbc.update("""
                INSERT INTO tbooker.outbox_events(event_id,aggregate_id,event_type,event_version,payload)
                VALUES (?,?,?,?,?::jsonb)
                """, event.eventId(), event.bookingId(), event.eventType(), event.version(), payload);
    }

    @Transactional
    public List<Claim> claim(int limit, Duration lease) {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidates AS (
                    SELECT event_id FROM tbooker.outbox_events
                    WHERE (status='PENDING' AND next_attempt_at<=clock_timestamp())
                       OR (status='IN_FLIGHT' AND lease_until<=clock_timestamp())
                    ORDER BY created_at,event_id FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE tbooker.outbox_events e SET status='IN_FLIGHT',lease_token=?,
                    lease_until=clock_timestamp()+(? * interval '1 millisecond'),attempts=attempts+1
                FROM candidates c WHERE e.event_id=c.event_id
                RETURNING e.event_id,e.aggregate_id,e.payload::text,e.created_at,e.attempts,e.lease_token
                """, (rs, row) -> new Claim(rs.getObject("event_id", UUID.class), rs.getLong("aggregate_id"),
                rs.getString("payload"), rs.getTimestamp("created_at").toInstant(), rs.getInt("attempts"),
                rs.getObject("lease_token", UUID.class)), limit, token, lease.toMillis());
    }

    @Transactional
    public boolean published(Claim claim) {
        return jdbc.update("""
                UPDATE tbooker.outbox_events SET status='PUBLISHED',published_at=clock_timestamp(),
                    lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND status='IN_FLIGHT' AND lease_token=?
                """, claim.eventId(), claim.leaseToken()) == 1;
    }

    @Transactional
    public boolean failed(Claim claim, Duration delay, String errorClass) {
        return jdbc.update("""
                UPDATE tbooker.outbox_events SET status='PENDING',lease_token=NULL,lease_until=NULL,
                    next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),last_error=?
                WHERE event_id=? AND status='IN_FLIGHT' AND lease_token=?
                """, delay.toMillis(), errorClass, claim.eventId(), claim.leaseToken()) == 1;
    }

    public long pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tbooker.outbox_events WHERE status<>'PUBLISHED'", Long.class);
    }

    public record Claim(UUID eventId, Long bookingId, String payload, Instant createdAt, int attempts, UUID leaseToken) { }
}
