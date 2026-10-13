package com.example.Tbooker.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class BookingEventCodec {
    private final ObjectMapper json;
    private final Validator validator;

    public BookingEventCodec(ObjectMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    public BookingConfirmedEvent decode(String key, byte[] bytes) {
        if (bytes == null || bytes.length > 65536) {
            throw new InvalidBookingEventException("Missing or oversized booking event");
        }
        try {
            var event = json.readValue(bytes, BookingConfirmedEvent.class);
            if (event == null || !validator.validate(event).isEmpty()
                    || !event.bookingId().toString().equals(key)) {
                throw new InvalidBookingEventException("Invalid booking event schema or partition key");
            }
            return event;
        } catch (IOException failure) {
            // Do not place arbitrary payload contents in logs or exception/DLT messages.
            throw new InvalidBookingEventException("Malformed booking event JSON");
        }
    }

    public String encode(BookingConfirmedEvent event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode booking event", failure); }
    }

    public String fingerprint(BookingConfirmedEvent event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(event).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
