CREATE TABLE tbooker.outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id BIGINT NOT NULL REFERENCES tbooker.bookings(id),
    event_type VARCHAR(64) NOT NULL CHECK (event_type = 'BookingConfirmed'),
    event_version INTEGER NOT NULL CHECK (event_version = 1),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(160),
    CONSTRAINT uq_outbox_booking_event UNIQUE (aggregate_id, event_type),
    CONSTRAINT ck_outbox_payload CHECK ((jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY['eventId','eventType','version','bookingId','userId','showId','showSeatId','occurredAt']
        AND payload->>'eventId' = event_id::text
        AND payload->>'bookingId' = aggregate_id::text
        AND payload->>'eventType' = event_type
        AND payload->>'version' = event_version::text) IS TRUE),
    CONSTRAINT ck_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND published_at IS NULL)
        OR (status = 'PENDING' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NOT NULL))
);
CREATE INDEX idx_outbox_pending ON tbooker.outbox_events(next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_expired_lease ON tbooker.outbox_events(lease_until) WHERE status = 'IN_FLIGHT';

-- Consumer identities are stable logical projection names, independent of Kafka group configuration.
CREATE TABLE tbooker.processed_events (
    consumer_name VARCHAR(40) NOT NULL,
    event_id UUID NOT NULL,
    booking_id BIGINT NOT NULL CHECK (booking_id > 0),
    payload_hash CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT uq_processed_booking UNIQUE (consumer_name, booking_id)
);
CREATE TABLE tbooker.booking_notifications (
    event_id UUID PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE CHECK (booking_id > 0),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    status VARCHAR(20) NOT NULL CHECK (status = 'MOCK_DELIVERED'),
    message TEXT NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE tbooker.booking_analytics (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    confirmed_count BIGINT NOT NULL CHECK (confirmed_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- Preserve a baseline for existing bookings without inventing historical notifications/events.
-- Quiesce booking writes during rollout; older binaries do not create outbox events.
INSERT INTO tbooker.booking_analytics(id, confirmed_count) SELECT 1, COUNT(*) FROM tbooker.bookings;
