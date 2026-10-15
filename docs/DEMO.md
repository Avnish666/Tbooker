# Tbooker: a 5–10 minute backend demonstration

Use one Bash terminal from the repository root. Prerequisites: Docker/Compose v2,
`curl`, `jq`, and `openssl`. Allow extra time **before the demo** for the first image
pull and Maven/Docker build. The walkthrough takes about 5–10 minutes once images
are cached. It uses real HTTP APIs, a new registered user, mock payment receipts,
Redis counters, and read-only PostgreSQL inspection of Kafka effects.

Do not enable shell tracing or record login responses: they contain credentials.
This is a local development demonstration, not a production deployment procedure.

## 1. Prepare and start (about 1–2 minutes with warm images)

Create `.env` only if absent:

```bash
# demo:prepare
set -euo pipefail
: "${TBOOKER_ENV_FILE:=.env}"
: "${TBOOKER_PROJECT:=tbooker-demo}"
: "${TBOOKER_URL:=http://127.0.0.1:8080}"
if [ ! -f "$TBOOKER_ENV_FILE" ]; then
  cp .env.example "$TBOOKER_ENV_FILE"
fi
```

Edit that file locally: set `DB_PASSWORD`, generate `JWT_SECRET` with
`openssl rand -base64 32`, and uncomment `SPRING_PROFILES_ACTIVE=dev`. Keep the
mock UPI outcome `SUCCESS` and cache/messaging enabled. If port 8080 is occupied,
change `LB_PORT` in the env file and set `TBOOKER_URL` to the matching localhost
URL. Avoid inherited `DB_*`, `JWT_*` or `SPRING_*` overrides from another project.
The explicit project name gives this demo its own persistent volumes; subsequent
runs reuse them. Do not attach it to an existing shared database.

```bash
# demo:start
dc() {
  docker compose --env-file "$TBOOKER_ENV_FILE" -p "$TBOOKER_PROJECT" \
    -f compose.scaled.yaml "$@"
}
dc config --quiet
dc up -d --build --wait --wait-timeout 240
dc ps
curl --fail --silent --show-error "$TBOOKER_URL/actuator/health/readiness" | jq .
```

Expected: all six services healthy and readiness `UP`. Readiness checks PostgreSQL;
optional Redis/Kafka health is checked separately. Flyway initializes the schema,
and the `dev` seed supplies a show and A1–A8. Do not use the password-less seed
users for login. If the database has no shows, check the profile before proceeding.

## 2. Register and log in (about 1 minute)

Create a private temporary directory for request bodies and the bearer token.
Random credentials make registration repeatable without duplicate emails. The
helper checks actual status codes; it does not assume a successful response.

```bash
# demo:authentication
umask 077
DEMO_DIR=$(mktemp -d /tmp/tbooker-demo.XXXXXX)
DEMO_EMAIL="demo-$(openssl rand -hex 8)@example.test"
DEMO_PASSWORD=$(openssl rand -hex 18)
printf '%s\n' "$DEMO_EMAIL" "$DEMO_PASSWORD" |
  jq -Rn '{name:"Portfolio Demo",email:input,password:input}' > "$DEMO_DIR/register-request.json"
jq 'del(.name)' "$DEMO_DIR/register-request.json" > "$DEMO_DIR/credentials.json"
unset DEMO_PASSWORD
request() {
  local expected="$1" name="$2" actual
  shift 2
  actual=$(curl --silent --show-error --max-time 30 \
    --output "$DEMO_DIR/$name.json" --write-out '%{http_code}' "$@")
  if [ "$actual" != "$expected" ]; then
    printf 'Expected HTTP %s, received %s; inspect private file %s\n' \
      "$expected" "$actual" "$DEMO_DIR/$name.json" >&2
    return 1
  fi
  printf '%s: HTTP %s\n' "$name" "$actual"
}
request 201 register -X POST -H 'Content-Type: application/json' \
  --data-binary "@$DEMO_DIR/register-request.json" "$TBOOKER_URL/api/auth/register"
request 200 login -X POST -H 'Content-Type: application/json' \
  --data-binary "@$DEMO_DIR/credentials.json" "$TBOOKER_URL/api/auth/login"
jq -er '.accessToken | select(type == "string" and length > 0)' \
  "$DEMO_DIR/login.json" > "$DEMO_DIR/token"
printf 'header = "Authorization: Bearer %s"\n' "$(cat "$DEMO_DIR/token")" \
  > "$DEMO_DIR/auth.curl"
jq '{id,role}' "$DEMO_DIR/register.json"
```

Registration returns `USER`; BCrypt hashes the password. Login returns an HS256
JWT valid for 15 minutes by default. The token stays in private files, not command
arguments or printed output. Every protected call below supplies it via curl's
configuration file. Re-run login and regenerate `auth.curl` if the token expires.

## 3. Show catalogue and live seats (about 30 seconds)

```bash
# demo:catalogue
request 200 shows "$TBOOKER_URL/api/shows"
jq '.[] | {id,movieTitle,theatreName,startTime}' "$DEMO_DIR/shows.json"
SHOW_ID=$(jq -er '.[0].id' "$DEMO_DIR/shows.json")
request 200 seats "$TBOOKER_URL/api/shows/$SHOW_ID/seats"
jq '.[] | {id,seatNumber,status}' "$DEMO_DIR/seats.json"
SEAT_ID=$(jq -er '[.[] | select(.status == "AVAILABLE")][0].id' "$DEMO_DIR/seats.json")
```

IDs are discovered from the responses. If there is no available seat, use another
show with inventory; do not reset a database or delete bookings to rerun the demo.
Each completed demo permanently consumes one seat. Catalogue metadata may be
cached, but this seat response is read from PostgreSQL.

## 4. Hold, mock payment and confirmation (about 1 minute)

```bash
# demo:booking
request 201 hold --config "$DEMO_DIR/auth.curl" -X POST \
  -H 'Content-Type: application/json' --data "{\"showSeatId\":$SEAT_ID}" \
  "$TBOOKER_URL/api/shows/$SHOW_ID/reservations"
RESERVATION_ID=$(jq -er '.id' "$DEMO_DIR/hold.json")
jq '{id,status,expiresAt}' "$DEMO_DIR/hold.json"
request 200 reservation --config "$DEMO_DIR/auth.curl" \
  "$TBOOKER_URL/api/reservations/$RESERVATION_ID"

# Generate ONCE per intended payment; retain this key for every retry.
PAYMENT_KEY="demo-$(openssl rand -hex 16)"
request 201 payment --config "$DEMO_DIR/auth.curl" -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $PAYMENT_KEY" \
  --data '{"method":"UPI"}' "$TBOOKER_URL/api/reservations/$RESERVATION_ID/payments"
jq '{id,status,confirmationStatus,bookingId,amountMinor,currency}' "$DEMO_DIR/payment.json"
jq -e '.status == "SUCCESS" and .confirmationStatus == "CONFIRMED" and .bookingId != null' \
  "$DEMO_DIR/payment.json" > /dev/null
BOOKING_ID=$(jq -er '.bookingId' "$DEMO_DIR/payment.json")

request 200 confirmation --config "$DEMO_DIR/auth.curl" -X POST \
  "$TBOOKER_URL/api/reservations/$RESERVATION_ID/confirmation"
jq -e --argjson id "$BOOKING_ID" '.id == $id and .status == "CONFIRMED"' \
  "$DEMO_DIR/confirmation.json" > /dev/null
jq '{id,showId,showSeatId,status}' "$DEMO_DIR/confirmation.json"
```

Explain the boundaries: hold transaction commits first; the gateway stores a mock
receipt; financial SUCCESS commits separately; confirmation atomically commits
inventory, booking, reservation/payment links and an outbox event. **The payment
call normally confirms already**; explicit confirmation returns that same booking.
The API never accepts `userId`, amount or a client-supplied payment success flag.
If the five-minute hold expired, inspect its state and start a new hold; never
change keys just to hide an uncertain payment result.

## 5. Duplicate protection (about 30 seconds)

```bash
# demo:duplicates
request 200 payment-replay --config "$DEMO_DIR/auth.curl" -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $PAYMENT_KEY" \
  --data '{"method":"UPI"}' "$TBOOKER_URL/api/reservations/$RESERVATION_ID/payments"
PAYMENT_ID=$(jq -er '.id' "$DEMO_DIR/payment.json")
jq -e --argjson p "$PAYMENT_ID" --argjson b "$BOOKING_ID" \
  '.id == $p and .bookingId == $b' "$DEMO_DIR/payment-replay.json" > /dev/null
request 200 confirmation-replay --config "$DEMO_DIR/auth.curl" -X POST \
  "$TBOOKER_URL/api/reservations/$RESERVATION_ID/confirmation"
jq -e --argjson id "$BOOKING_ID" '.id == $id' \
  "$DEMO_DIR/confirmation-replay.json" > /dev/null
request 409 booked-seat-conflict --config "$DEMO_DIR/auth.curl" -X POST \
  -H 'Content-Type: application/json' --data "{\"showSeatId\":$SEAT_ID}" \
  "$TBOOKER_URL/api/shows/$SHOW_ID/reservations"
jq '{status,detail}' "$DEMO_DIR/booked-seat-conflict.json"
request 200 own-booking --config "$DEMO_DIR/auth.curl" "$TBOOKER_URL/api/bookings/$BOOKING_ID"
```

This is a retry/duplicate demonstration, not a simultaneous race. The retained
[100-contender test](TESTING.md#phase-11-verification) supplies distributed
concurrency evidence. A second JWT owner would also be unable to take this booked
seat; ownership rules additionally block access to another user's reservation.

## 6. Redis hit behavior (about 30 seconds)

```bash
# demo:cache
cache_stats() { dc exec -T redis redis-cli INFO stats < /dev/null | tr -d '\r' | awk '/^keyspace_(hits|misses):/'; }
cache_stats > "$DEMO_DIR/cache-before.txt"
for i in {1..10}; do
  curl --fail --silent --show-error "$TBOOKER_URL/api/shows" > /dev/null
done
cache_stats > "$DEMO_DIR/cache-after.txt"
printf 'Before:\n'; cat "$DEMO_DIR/cache-before.txt"
printf 'After:\n'; cat "$DEMO_DIR/cache-after.txt"
```

Expect hits to rise after the catalogue key is populated. A TTL expiry may add a
miss, and these counters cover the Redis instance, not only your client. Do not
flush a shared cache. This is a functional observation, not a performance benchmark.
The [matched cache comparison](PERFORMANCE_SUMMARY.md#redis-comparison-separate-phase-9-experiment)
contains measured latency and SQL reductions.

## 7. Kafka and database projections (about 1 minute)

There is no public notification/analytics API. Use read-only local operator SQL
to observe the outbox and the two consumer effects for this booking. Container-side
environment expansion supplies database connection names without printing secrets.

```bash
# demo:events
sql() {
  dc exec -T postgres sh -c \
    'exec psql -XqAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}
# Wait up to 30 seconds for both independent projections and publisher state.
EVENT_READY=0
for i in {1..30}; do
  EVENT_READY=$(printf '%s\n' "SELECT COUNT(*) FROM tbooker.outbox_events o
    WHERE o.aggregate_id=$BOOKING_ID AND o.status='PUBLISHED'
      AND (SELECT COUNT(*) FROM tbooker.processed_events p WHERE p.event_id=o.event_id)=2
      AND EXISTS (SELECT 1 FROM tbooker.booking_notifications n WHERE n.event_id=o.event_id);" | sql)
  [ "$EVENT_READY" = 1 ] && break
  sleep 1
done
[ "$EVENT_READY" = 1 ]
printf '%s\n' "SELECT aggregate_id,status,attempts FROM tbooker.outbox_events WHERE aggregate_id=$BOOKING_ID;
  SELECT consumer_name,booking_id FROM tbooker.processed_events WHERE booking_id=$BOOKING_ID;
  SELECT booking_id,status FROM tbooker.booking_notifications WHERE booking_id=$BOOKING_ID;
  SELECT confirmed_count FROM tbooker.booking_analytics WHERE id=1;" | sql
```

Expect one published event, markers named `booking-notifications-v1` and
`booking-analytics-v1`, and a `MOCK_DELIVERED` notification row. Analytics is a
system-wide count, not necessarily `1` on a reused database. The Kafka group IDs
are `tbooker-notifications-v1` and `tbooker-analytics-v1` by default; these differ
from the logical processed-marker names. Effects are eventually consistent and
idempotent under redelivery; no email, SMS or exactly-once transport is claimed.
If the bounded wait fails, inspect [messaging health and recovery](MESSAGING.md)
and [the runbook](RUNBOOK.md); do not equate a committed booking with delivered events.

## 8. Show both backends and stop (about 30 seconds)

```bash
# demo:routing
for i in {1..10}; do
  curl --fail --silent --show-error --dump-header - --output /dev/null \
    "$TBOOKER_URL/api/v1/health"
done | tr -d '\r' | awk 'tolower($1)=="x-tbooker-upstream:" {print $2}' | sort | uniq -c
```

Expect two distinct upstream addresses. This proves routing across both JVMs in
this run, not high availability of PostgreSQL/Kafka or immunity to in-flight
failures. NGINX passive detection can return errors during an instance failure and
does not automatically replay requests. The [failure suite](SCALING.md) covers that.

```bash
# demo:stop
# Deletes only this walkthrough's newly created private credential files.
rm -rf -- "$DEMO_DIR"
unset DEMO_DIR DEMO_EMAIL PAYMENT_KEY
# Retain database and Kafka named volumes, including the booking:
dc down
```

Restart with the same project/env settings to retain the booking. Never append
`--volumes` to a stack holding data you want to keep. For troubleshooting and
safe rebuild commands, use [DEPLOYMENT](DEPLOYMENT.md).
