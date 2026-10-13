# HTTP API

Local base URL: `http://localhost:8080` (`SERVER_PORT` can override the port).
Successful domain responses are JSON DTOs. Catalogue reads are public. Payment, booking, and reservation
routes require `Authorization: Bearer <accessToken>`; the JWT subject determines
the user. Authentication is stateless and does not use cookies or HTTP sessions.

## Routes

| Method | Path | Successful response |
| --- | --- | --- |
| POST | `/api/auth/register` | `201`, `UserResponse` |
| POST | `/api/auth/login` | `200`, `AuthResponse` |
| GET | `/api/v1/health` | `200`, `HealthResponse` |
| GET | `/api/movies` | `200`, array of `MovieResponse` |
| GET | `/api/movies/{id}` | `200`, `MovieResponse` |
| GET | `/api/theatres` | `200`, array of `TheatreResponse` |
| GET | `/api/theatres/{id}` | `200`, `TheatreResponse` |
| GET | `/api/shows` | `200`, array of `ShowResponse` |
| GET | `/api/shows/{showId}` | `200`, `ShowResponse` |
| GET | `/api/shows/{showId}/seats` | `200`, array of `ShowSeatResponse` |
| POST | `/api/shows/{showId}/bookings` | Retired creation path: `409` after valid resource checks |
| GET | `/api/bookings` | `200`, array of own `BookingResponse` |
| GET | `/api/bookings/{bookingId}` | `200`, own `BookingResponse` |
| POST | `/api/shows/{showId}/reservations` | `201`, `ReservationResponse` and `Location` |
| GET | `/api/reservations/{id}` | `200`, own `ReservationResponse` |
| POST | `/api/reservations/{id}/confirmation` | `200`, `BookingResponse`, including retries |
| DELETE | `/api/reservations/{id}` | `204`, cancellation of an active hold |
| POST | `/api/reservations/{id}/payments` | `201` new / `200` replay, `PaymentResponse` and `Location` |
| GET | `/api/payments/{id}` | `200`, own `PaymentResponse` |
| GET | `/api/payments` | `200`, array of own `PaymentResponse` |
| GET | `/api/reservations/{id}/booking` | `200`, own `BookingResponse`; `409` if not confirmed |

Health retains its `/api/v1` prefix; catalogue and booking routes use `/api`.
There are no user-list, catalogue-write, confirmed-booking cancellation, refund, or payment-callback endpoints.
Catalogue mutation paths are reserved for ADMIN; there are no mutation handlers. No pagination or filtering parameters are implemented.

## Registration and login

These two POST endpoints are public. Accepted request fields are explicit;
unknown fields such as `role` are rejected. Names are required and at most 120
characters. Email is required, validated, and at most 254 characters. Registration
normalizes email case and enforces case-insensitive uniqueness.

```bash
# Replace the illustrative password with your own private local password.
curl -i -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  --data '{"name":"Demo User","email":"demo@example.test","password":"replace-with-private-password"}'

curl --fail -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  --data '{"email":"demo@example.test","password":"replace-with-private-password"}'
# Copy accessToken from the login response into TOKEN locally.
TOKEN=paste-access-token-here
```

Registration requires a password of at least 12 Java string characters and at
most 72 UTF-8 bytes, and returns `201`:

```json
{"id":3,"name":"Demo User","email":"demo@example.test","role":"USER"}
```

`UserResponse` contains `id` (integer), `name`/`email` (strings), and `role`
(`USER` for every public registration). Passwords and hashes are never returned.
A duplicate email returns `409`; invalid fields or a submitted role return `400`.
A duplicate registration cannot change the existing password or role.

Successful login returns `200` with `AuthResponse`:

```json
{"accessToken":"<signed JWT>","tokenType":"Bearer","expiresIn":900}
```

The token is a string, `tokenType` is `Bearer`, and `expiresIn` is the configured
lifetime in seconds (900 by default). Wrong passwords, unknown accounts, and
legacy/sample accounts without credentials return `401` with detail
`Invalid email or password`. Malformed input returns `400`.

There is no refresh, logout/revocation, password-reset, or public role-management
API. A trusted operator can promote a registered account, but a new login is
needed for a token with the new role. See [SECURITY.md](SECURITY.md).

## Health

```bash
curl --fail http://localhost:8080/api/v1/health
```

```json
{"status":"UP"}
```

This endpoint confirms HTTP handling and does not query the database. Actuator
also exposes `GET /actuator/health`, `/actuator/health/liveness`, and
`/actuator/health/readiness`. Readiness includes the database health indicator;
liveness does not. Optional Redis is excluded from health/readiness. Healthy responses return `200`; unhealthy health groups can
return `503`. Component details are hidden. Only health is exposed from Actuator.

## Movie and theatre catalogue

```bash
curl --fail http://localhost:8080/api/movies
curl --fail http://localhost:8080/api/movies/1
curl --fail http://localhost:8080/api/theatres
curl --fail http://localhost:8080/api/theatres/1
```

IDs are illustrative. List responses are arrays ordered by ID, or `[]`. Details
use these DTOs; no JPA entities or private user data are exposed:

```json
{"id":1,"title":"The Sample Adventure","durationMinutes":120}
```

```json
{"id":1,"name":"Sample Cinema","city":"Bengaluru"}
```

GET/HEAD reads are public. Invalid/non-positive IDs return `400`, absent details
return `404`. ADMIN mutation authorization remains configured, but there are no
write handlers. The lists have no filtering or pagination.

Movie/theatre details use ten-minute server-side Redis TTLs and lists five-minute
TTLs. Show detail/list TTLs are one minute/thirty seconds. JPA changes trigger
after-commit targeted eviction, but metadata can be stale after native SQL,
failed invalidation, or a racing reader until TTL expiry. Redis failure falls
back to PostgreSQL without changing the success DTO/status contract. Errors are
not cached. These caches do not change HTTP no-store headers or add ETags.
Seat availability is always read from PostgreSQL; do not interpret any catalogue
response as a seat guarantee. See [HLD](HLD.md#redis-catalogue-cache).

## List shows and get a show

```bash
curl --fail http://localhost:8080/api/shows
# Use an id returned by the list; 1 is illustrative.
SHOW_ID=1
curl --fail "http://localhost:8080/api/shows/$SHOW_ID"
```

The list returns an array ordered by `startTime`, then `id`, including past shows.
An empty catalogue returns `[]`. The detail endpoint returns one object:

```json
{
  "id": 1,
  "movieId": 1,
  "movieTitle": "The Sample Adventure",
  "durationMinutes": 120,
  "screenId": 1,
  "screenName": "Screen 1",
  "theatreId": 1,
  "theatreName": "Sample Cinema",
  "city": "Bengaluru",
  "startTime": "2030-01-01T18:00:00Z"
}
```

| `ShowResponse` field | JSON type | Meaning |
| --- | --- | --- |
| `id` | integer | Movie-show ID. |
| `movieId` | integer | Movie ID. |
| `movieTitle` | string | Movie title. |
| `durationMinutes` | integer | Positive movie duration. |
| `screenId` | integer | Screen ID. |
| `screenName` | string | Screen name within its theatre. |
| `theatreId` | integer | Theatre ID. |
| `theatreName` | string | Theatre name. |
| `city` | string | Theatre city. |
| `startTime` | string | ISO-8601 UTC instant. |

All fields are populated by the current service. An absent show returns `404`.
Path IDs must be positive values representable by a Java `Long`; invalid values
return `400`.

## Get show seats

```bash
curl --fail "http://localhost:8080/api/shows/$SHOW_ID/seats"
```

Example array containing one inventory row:

```json
[
  {
    "id": 5,
    "showId": 1,
    "seatId": 5,
    "seatNumber": "A5",
    "status": "AVAILABLE"
  }
]
```

| `ShowSeatResponse` field | JSON type | Meaning |
| --- | --- | --- |
| `id` | integer | Show-specific inventory ID; use this as `showSeatId` when booking. |
| `showId` | integer | Owning show. |
| `seatId` | integer | Physical seat; independent of the inventory ID. |
| `seatNumber` | string | Label such as `A5`. |
| `status` | string | `AVAILABLE`, `HELD`, or `BOOKED`; expired holds are shown as `AVAILABLE` even before cleanup. |

Rows are ordered lexically by `seatNumber`, then inventory `id`; this is not a
numeric seat-label ordering algorithm. An existing show with no inventory
returns `[]`; an absent show returns `404`. Availability is a read-time snapshot
and can change before the client submits a booking.

## Legacy immediate booking and confirmation DTO

`POST /api/shows/{showId}/bookings` can no longer create a booking. After
validating the JWT, `BookingRequest {"showSeatId":5}`, and resource associations,
it returns `409` with `Immediate booking is no longer supported; create a
reservation and pay first`. Invalid inputs still return `400`, missing resources
`404`, and missing/invalid authentication `401`. This prevents the old route from
bypassing the Phase 8 payment requirement.

Create a reservation, initiate a payment, then retrieve the booking using
`GET /api/reservations/{id}/booking` or the existing own-booking APIs.
There is currently no past-show booking cutoff.

| `BookingResponse` field | JSON type | Meaning |
| --- | --- | --- |
| `id` | integer | Generated permanent booking ID. |
| `userId` | integer | Owner derived from the authenticated JWT subject. |
| `showId` | integer | Show being booked. |
| `showSeatId` | integer | Show inventory ID, not physical `seatId`. |
| `status` | string | Always `CONFIRMED` in this lifecycle. |
| `createdAt` | string | UTC instant, up to microsecond precision. |

```json
{"id":1,"userId":3,"showId":1,"showSeatId":5,"status":"CONFIRMED","createdAt":"2026-10-13T05:00:00Z"}
```

## Read your bookings

```bash
curl --fail http://localhost:8080/api/bookings -H "Authorization: Bearer $TOKEN"
BOOKING_ID=1
curl --fail "http://localhost:8080/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $TOKEN"
```

The list returns an unpaged array of `BookingResponse`, ordered by `createdAt`
descending, then `id` descending; a user with no bookings receives `[]`. Detail
returns the same DTO as creation. No owner selector is accepted by these
controllers; the principal supplies identity and repository queries filter by it.
An absent booking and another user's booking both return `404`. ADMIN users can
also read only their own bookings. Nonpositive or malformed booking IDs return
`400` after authentication.

## Errors

The security handlers, domain exception advice, and Spring MVC error handling use RFC 9457
Problem Details with `Content-Type: application/problem+json`. For example,
attempting to reserve held or already-booked inventory produces:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "This show seat is held or already booked",
  "instance": "/api/shows/1/reservations"
}
```

| HTTP status | Condition |
| --- | --- |
| `400` | Invalid path/body input, malformed JSON, or show-seat association mismatch. |
| `401` | Missing/invalid/expired token on protected routes, or invalid login credentials. |
| `403` | Authenticated user lacks the role required by the route. |
| `404` | Missing show/user/show seat, or missing/foreign booking, payment, or reservation. |
| `409` | Duplicate email, unavailable seat, inactive reservation, missing payment proof, retired immediate booking, conflicting key parameters, pending/successful attempt, or named booking/hold uniqueness conflict. |
| `502` | Provider receipt could not be verified. Retry the original payment key; do not create another charge. |
| `500` | An unexpected data-integrity violation; detail is `The request could not be completed`. |

Missing-resource details have the form `Show 1 was not found`,
`User 1 was not found`, or `Show seat 5 was not found`. A mismatched association
uses `Show seat 5 does not belong to show 1`. The unique-constraint fallback has
detail `This show seat already has a confirmed booking`.

Validation details are supplied by Spring and can differ from domain messages;
clients should branch on status and context rather than parse the prose. The
application does not define a uniform custom contract for every unhandled server
failure. Database/internal stack traces are not included in the explicit error
responses above.

Examples after [local setup](../README.md#local-setup):

```bash
# 400: nonpositive path ID
curl -i http://localhost:8080/api/shows/0

# 404 on a sample database where this show does not exist
curl -i http://localhost:8080/api/shows/9223372036854775807

# 400: invalid booking body
curl -i -X POST "http://localhost:8080/api/shows/$SHOW_ID/bookings" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data '{"showSeatId":0}'
```

IDs, booking timestamps, and response cardinalities in these examples are
illustrative. The development seed creates eight seats, uses generated IDs, and
preserves any bookings already made; it does not reset the database on restart.

## Temporary seat reservations

All reservation routes require a valid bearer JWT and USER or ADMIN role.
ADMIN has no ownership override. Reservation creation accepts only `showSeatId`;
unknown fields (`userId`, `status`, `expiresAt`, etc.) return `400`. IDs must be
positive `Long` values. The show, user, and inventory must exist (`404`); an
inventory row belonging to another show returns `400`.

```bash
# Use an available show seat from the public catalogue and the accessToken from login.
curl -i -X POST "http://localhost:8080/api/shows/$SHOW_ID/reservations" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data "{\"showSeatId\":$SHOW_SEAT_ID}"
# Copy id from the response (illustrative value below).
RESERVATION_ID=1
curl --fail "http://localhost:8080/api/reservations/$RESERVATION_ID" \
  -H "Authorization: Bearer $TOKEN"
# Pay before confirmation. Keep this key for retries of this exact request.
PAYMENT_KEY=$(uuidgen)
curl -i -X POST "http://localhost:8080/api/reservations/$RESERVATION_ID/payments" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $PAYMENT_KEY" \
  -H 'Content-Type: application/json' --data '{"method":"UPI"}'
# Normally payment already confirmed it; this also safely retries confirmation.
curl -i -X POST "http://localhost:8080/api/reservations/$RESERVATION_ID/confirmation" \
  -H "Authorization: Bearer $TOKEN"
# Alternatively, cancel an unpaid active hold before confirmation:
curl -i -X DELETE "http://localhost:8080/api/reservations/$RESERVATION_ID" \
  -H "Authorization: Bearer $TOKEN"
```

Creation returns `201 Created`, `Location: /api/reservations/{id}`, and:

```json
{
  "id": 1,
  "showId": 1,
  "showSeatId": 5,
  "status": "HELD",
  "createdAt": "2026-10-12T12:00:00Z",
  "expiresAt": "2026-10-12T12:05:00Z",
  "bookingId": null
}
```

`ReservationResponse` contains positive integer IDs, a lifecycle status, UTC
creation/deadline instants, and the nullable confirmed-booking ID. No entity or
owner-selection input is exposed. The server grants five minutes from its
**database-time allocation decision**, not five minutes after the client receives
the response. Creation does not renew another hold, including the same user's;
active-held or booked seats return `409`. An expired hold is atomically reclaimed.
Hold creation has no client idempotency key or reservation-list recovery API.

Viewing returns the same DTO and `200` for any owned historical state. A past
deadline reports `EXPIRED` for an unconfirmed hold without needing cleanup to
have run. An absent or another user's reservation returns `404` for viewing,
confirmation, and cancellation alike.

Confirmation takes no body. For an active owned reservation it requires a stored
verified SUCCESS payment for that owner/reservation, then commits a permanent
booking and returns `200` with `BookingResponse`. Missing proof returns `409`.
Historical confirmed reservations without payment links remain readable/retryable;
this exception preserves history and cannot create a new unpaid booking.
Retried confirmations return **the same booking ID and body**, even after the
former hold deadline; concurrent retries cannot create duplicates. Expired or
cancelled reservations return `409`. The deadline is checked after acquiring the
inventory lock; a request arriving early but acquiring it after expiry fails.
A decision made before expiry can commit after it without being undone by cleanup.

Cancellation takes no body. It returns `204` and atomically releases an active
owned hold; repeating cancellation of the same cancelled ID also returns `204`
without touching a replacement hold. Cancelling an expired or confirmed
reservation returns `409`. Cancellation does not delete history and cannot cancel
a confirmed booking. Cancelling while payment is pending does not cancel an
already-started provider operation; late success requires reconciliation.
Missing/invalid JWTs return `401`; route-role failures return
`403`. Errors use the existing Problem Details format.

## Mock payments

All payment APIs require a validated JWT and USER/ADMIN role. ADMIN has no owner
override. Initiation accepts only `PaymentRequest {"method":"UPI"}` or
`{"method":"CARD"}`; case-sensitive enum values, a missing/null method, and unknown
fields are validated. Clients cannot set amount, currency, owner, status,
provider reference, or gateway request ID. Unknown fields return `400`.

`Idempotency-Key` is required and contains 1–100 ASCII letters, digits, or
`._:-`. Scope is `(authenticated user, key)`, with case-sensitive matching.
The key binds reservation ID and method; reuse with different parameters returns
`409`. A replay uses the stored server price snapshot even if configuration has
changed. Keys currently have no expiration or purge workflow.

```bash
# RESERVATION_ID came from a successful hold. Generate ONCE, then reuse on retry.
PAYMENT_KEY=$(uuidgen)
curl -i -X POST "http://localhost:8080/api/reservations/$RESERVATION_ID/payments" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $PAYMENT_KEY" \
  -H 'Content-Type: application/json' --data '{"method":"UPI"}'
# Same URL, method and key safely resume/retrieve that attempt.
PAYMENT_ID=1 # replace with returned id
curl --fail "http://localhost:8080/api/payments/$PAYMENT_ID" -H "Authorization: Bearer $TOKEN"
curl --fail http://localhost:8080/api/payments -H "Authorization: Bearer $TOKEN"
curl -i "http://localhost:8080/api/reservations/$RESERVATION_ID/booking" -H "Authorization: Bearer $TOKEN"
```

A new attempt returns `201 Created`; a matching replay returns `200 OK`. Both
include `Location: /api/payments/{id}`. A declined mock payment is still a created
resource (`201` with FAILED), not a transport error. Example successful DTO:

```json
{
  "id": 1,
  "reservationId": 1,
  "method": "UPI",
  "amountMinor": 10000,
  "currency": "INR",
  "status": "SUCCESS",
  "confirmationStatus": "CONFIRMED",
  "providerReference": "mock-upi-00000000-0000-0000-0000-000000000001",
  "bookingId": 1,
  "createdAt": "2026-10-13T05:00:00Z",
  "updatedAt": "2026-10-13T05:00:00.010Z"
}
```

IDs, UUIDs and times are illustrative. `providerReference` is null before a
verified receipt. `bookingId` is non-null only when this payment confirmed the
booking. No JPA entities, provider operation UUID field, owner selector, or
idempotency key are returned. Amount uses integer minor units: default 10000
paise = INR 100, a mock price rather than implemented seat pricing.

| Payment status / confirmation status | Client interpretation |
| --- | --- |
| PENDING / NOT_READY | Provider result is pending or not yet stored. Retry the original key. |
| FAILED / NOT_READY | Verified failure, no booking; a new key can retry while the hold remains active. |
| SUCCESS / PENDING | Financial success stored but booking has not committed. Retry the original key or confirmation while active. |
| SUCCESS / CONFIRMED | Retrieve the permanent booking. |
| SUCCESS / RECONCILIATION_REQUIRED | Payment succeeded but cannot allocate this reservation; no automatic refund exists. |

Replays return the **current stored state**, which can advance after recovery;
they do not replay frozen HTTP bytes. Pending retries may invoke the mock again
with the same provider UUID. FAILED replays return stored failure; trusted
internal verification can later record a newer provider correction. A new key
is rejected when an unresolved/successful attempt exists. New attempts require
an active owned hold; expired/cancelled/confirmed holds return `409`. A matching
existing-key replay remains available after the hold becomes inactive.

Payment history is unpaged, sorted by `createdAt DESC, id DESC`, and returns `[]`
for no payments. Foreign/absent payment and reservation IDs return `404`, also
for ADMIN. GET operations do not process results. Booking retrieval is `409` until
the reservation is confirmed. Domain conflicts are Problem Details; uncertain
provider/database failures must be recovered with the original key.

No HTTP callback or payment-status mutation endpoint exists. An arbitrary
`POST /api/payments/callback` is denied by the default security rule. Internal
`PaymentResultProcessor.process(id)` retrieves provider proof independently.
Mock outcome controls are trusted server configuration, never request parameters:
`PAYMENTS_MOCK_UPI_OUTCOME` and `PAYMENTS_MOCK_CARD_OUTCOME` accept SUCCESS
(default), FAILED, or PENDING. `PAYMENTS_MOCK_AMOUNT_MINOR` is positive (default
10000); currency is fixed INR. Configuration changes affect only new receipts.
There is no public way to advance a deliberately PENDING mock receipt.
