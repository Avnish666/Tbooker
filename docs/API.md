# HTTP API

Local base URL: `http://localhost:8080` (`SERVER_PORT` can override the port).
Successful domain responses are JSON DTOs. Catalogue reads are public. Booking
routes require `Authorization: Bearer <accessToken>`; the JWT subject determines
the user. Authentication is stateless and does not use cookies or HTTP sessions.

## Routes

| Method | Path | Successful response |
| --- | --- | --- |
| POST | `/api/auth/register` | `201`, `UserResponse` |
| POST | `/api/auth/login` | `200`, `AuthResponse` |
| GET | `/api/v1/health` | `200`, `HealthResponse` |
| GET | `/api/shows` | `200`, array of `ShowResponse` |
| GET | `/api/shows/{showId}` | `200`, `ShowResponse` |
| GET | `/api/shows/{showId}/seats` | `200`, array of `ShowSeatResponse` |
| POST | `/api/shows/{showId}/bookings` | `201`, `BookingResponse` |
| GET | `/api/bookings` | `200`, array of own `BookingResponse` |
| GET | `/api/bookings/{bookingId}` | `200`, own `BookingResponse` |

Health retains its `/api/v1` prefix; catalogue and booking routes use `/api`.
There are no user-list, catalogue-write, cancellation, hold, or payment endpoints.
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
liveness does not. Healthy responses return `200`; unhealthy health groups can
return `503`. Component details are hidden. Only health is exposed from Actuator.

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
| `status` | string | `AVAILABLE` or `BOOKED`. |

Rows are ordered lexically by `seatNumber`, then inventory `id`; this is not a
numeric seat-label ordering algorithm. An existing show with no inventory
returns `[]`; an absent show returns `404`. Availability is a read-time snapshot
and can change before the client submits a booking.

## Book one show seat

Use a show-seat response's `id`, not its physical `seatId`. Log in first using
an account registered through the authentication API. Sample users have no
password; there is no shared development login.

```bash
# TOKEN is the accessToken from login. SHOW_ID came from the public catalogue.
SHOW_SEAT_ID=5
curl -i -X POST "http://localhost:8080/api/shows/$SHOW_ID/bookings" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data "{\"showSeatId\":$SHOW_SEAT_ID}"
```

`BookingRequest` contains one required, non-null, positive `Long` inventory ID:

```json
{"showSeatId":5}
```

Missing, null, zero, negative, fractional, or out-of-range IDs fail with `400`
after authentication. `userId` is no longer a request field: sending it returns
`400`. Other unknown request fields are also rejected.
Use JSON integer values; the API does not promise rejection of every Jackson
scalar coercion such as numeric strings. The show, authenticated user, and show seat must exist,
and the inventory row must belong to the show in the path. Association mismatch
returns `400`, not a booking for the other show.

Successful response: `201 Created`, with this illustrative body:

```json
{
  "id": 1,
  "userId": 1,
  "showId": 1,
  "showSeatId": 5,
  "status": "CONFIRMED",
  "createdAt": "2026-10-08T09:45:00Z"
}
```

| `BookingResponse` field | JSON type | Meaning |
| --- | --- | --- |
| `id` | integer | Generated booking ID. |
| `userId` | integer | User ID derived from the authenticated JWT subject. |
| `showId` | integer | Requested show. |
| `showSeatId` | integer | Claimed inventory row. |
| `status` | string | Always `CONFIRMED` in the current lifecycle. |
| `createdAt` | string | Server-assigned UTC instant, with up to microsecond precision. |

The controller returns a body without a `Location` header; use
`GET /api/bookings/{bookingId}` to retrieve your booking. Confirmation is immediate and involves no payment or
temporary hold. There is currently no cutoff check for booking a past show.

The seat update and booking insertion commit together before `201` is returned.
Competing requests for the same available inventory row receive one success and
conflicts for the others, assuming the winning transaction commits. An insert
failure rolls back the availability change. See [booking design](BOOKING.md).

POST is not idempotent: repeating a successful request, even for the same user,
returns `409`. If the connection is lost after commit, the client cannot recover
the original result by an idempotency key. The client can list its own bookings
but there is no automatic retry-to-original-response mapping.

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
attempting to book an already-booked inventory row produces:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Show seat 5 is already booked",
  "instance": "/api/shows/1/bookings"
}
```

| HTTP status | Condition |
| --- | --- |
| `400` | Invalid path/body input, malformed JSON, or show-seat association mismatch. |
| `401` | Missing/invalid/expired token on protected routes, or invalid login credentials. |
| `403` | Authenticated user lacks the role required by the route. |
| `404` | Missing show/user/show seat, or missing/foreign booking. |
| `409` | Duplicate registration email, unavailable seat, or the named booking unique constraint rejects an insert. |
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
