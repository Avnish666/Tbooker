package com.example.Tbooker.service;

import com.example.Tbooker.dto.BookingResponse;
import com.example.Tbooker.event.BookingConfirmedEvent;
import com.example.Tbooker.event.BookingEventCodec;
import com.example.Tbooker.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Database-only collaborator. Broker availability never participates in this transaction. */
@Service
public class BookingEventRecorder {
    private final OutboxRepository outbox;
    private final BookingEventCodec codec;
    public BookingEventRecorder(OutboxRepository outbox, BookingEventCodec codec) {
        this.outbox = outbox;
        this.codec = codec;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmed(BookingResponse booking) {
        var event = new BookingConfirmedEvent(UUID.randomUUID(), BookingConfirmedEvent.TYPE, 1,
                booking.id(), booking.userId(), booking.showId(), booking.showSeatId(), booking.createdAt());
        outbox.append(event, codec.encode(event));
    }
}
