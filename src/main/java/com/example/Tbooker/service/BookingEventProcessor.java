package com.example.Tbooker.service;

import com.example.Tbooker.event.BookingConfirmedEvent;
import com.example.Tbooker.event.BookingEventCodec;
import com.example.Tbooker.repository.BookingProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class BookingEventProcessor {
    public static final String NOTIFICATIONS = "booking-notifications-v1";
    public static final String ANALYTICS = "booking-analytics-v1";
    private final BookingProjectionRepository projections;
    private final BookingEventCodec codec;
    public BookingEventProcessor(BookingProjectionRepository projections, BookingEventCodec codec) {
        this.projections = projections;
        this.codec = codec;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void notifyBooking(BookingConfirmedEvent event) {
        if (projections.firstDelivery(NOTIFICATIONS, event, codec.fingerprint(event))) {
            projections.mockNotification(event);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void countBooking(BookingConfirmedEvent event) {
        if (projections.firstDelivery(ANALYTICS, event, codec.fingerprint(event))) {
            projections.countBooking();
        }
    }
}
