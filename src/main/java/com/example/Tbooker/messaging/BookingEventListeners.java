package com.example.Tbooker.messaging;

import com.example.Tbooker.event.BookingEventCodec;
import com.example.Tbooker.service.BookingEventProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="messaging.enabled", havingValue="true", matchIfMissing=true)
public class BookingEventListeners {
    private final BookingEventCodec codec;
    private final BookingEventProcessor processor;
    public BookingEventListeners(BookingEventCodec codec, BookingEventProcessor processor) {
        this.codec = codec;
        this.processor = processor;
    }

    @KafkaListener(id="bookingNotifications", topics="${messaging.topic:tbooker.booking-confirmed.v1}",
            groupId="${messaging.notification-group:tbooker-notifications-v1}", containerFactory="bookingKafkaListenerContainerFactory")
    public void notifyBooking(ConsumerRecord<String,byte[]> record) {
        processor.notifyBooking(codec.decode(record.key(), record.value()));
    }

    @KafkaListener(id="bookingAnalytics", topics="${messaging.topic:tbooker.booking-confirmed.v1}",
            groupId="${messaging.analytics-group:tbooker-analytics-v1}", containerFactory="bookingKafkaListenerContainerFactory")
    public void countBooking(ConsumerRecord<String,byte[]> record) {
        processor.countBooking(codec.decode(record.key(), record.value()));
    }
}
