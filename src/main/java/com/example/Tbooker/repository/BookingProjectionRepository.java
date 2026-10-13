package com.example.Tbooker.repository;

import com.example.Tbooker.event.BookingConfirmedEvent;
import com.example.Tbooker.event.InvalidBookingEventException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class BookingProjectionRepository {
    private final JdbcTemplate jdbc;
    public BookingProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean firstDelivery(String consumer, BookingConfirmedEvent event, String hash) {
        int inserted = jdbc.update("""
                INSERT INTO tbooker.processed_events(consumer_name,event_id,booking_id,payload_hash)
                VALUES(?,?,?,?) ON CONFLICT DO NOTHING
                """, consumer, event.eventId(), event.bookingId(), hash);
        if (inserted == 1) { return true; }
        // Both unique keys must arbitrate simultaneous inserts of the same fact.
        // READ_COMMITTED gives this statement a fresh snapshot after the insert waits.
        var existing = jdbc.queryForList("SELECT payload_hash FROM tbooker.processed_events WHERE consumer_name=? AND event_id=?",
                String.class, consumer, event.eventId());
        if (existing.size() != 1 || !hash.equals(existing.getFirst())) {
            throw new InvalidBookingEventException("Event or booking identity was reused with different content");
        }
        return false;
    }

    public void mockNotification(BookingConfirmedEvent event) {
        jdbc.update("""
                INSERT INTO tbooker.booking_notifications(event_id,booking_id,user_id,status,message)
                VALUES(?,?,?,'MOCK_DELIVERED',?)
                """, event.eventId(), event.bookingId(), event.userId(), "Booking " + event.bookingId() + " is confirmed (mock notification)");
    }

    public void countBooking() {
        if (jdbc.update("UPDATE tbooker.booking_analytics SET confirmed_count=confirmed_count+1,updated_at=clock_timestamp() WHERE id=1") != 1) {
            throw new IllegalStateException("Booking analytics baseline is missing");
        }
    }
}
