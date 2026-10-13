# Project conventions

## Scope and architecture

- This is a Java 21 Movie Ticket Booking System built as a modular monolith.
- Read `README.md`, `docs/ROADMAP.md`, the POM, and existing code before changing it.
- Implement only the requested phase. Preserve working behavior and user changes.
- Phases 1–3 provide the foundation, show catalogue, and immediate confirmed
  bookings. Do not add holds, payments, Redis, Kafka, authentication, or
  microservices without a later request.
- Phase 4 verifies integration, 20-request contention, rollback, and repeatable
  migrations. Keep verification changes separate from new product features.
- Phase 6 adds Spring Security, BCrypt, JWT, USER/ADMIN roles, and own-booking
  reads. Read `docs/SECURITY.md` before changing identity or access rules. Do not
  add Redis, Kafka, payments, holds, or cancellation in this phase.
- Cloud tasks already have isolated checkouts; use the existing checkout rather
  than creating a worktree unless the user explicitly requests one.

- Phase 7 adds PostgreSQL-backed five-minute reservations and scheduled expiry.
  Read the reservation sections of `HLD.md`, `LLD.md`, `DATABASE.md`, and `API.md`
  under `docs/` before changing seat allocation. Do not add Redis, Kafka,
  payments, or confirmed-booking cancellation without a later request.

## Code conventions

- Follow Controller → Service → Repository layering for domain use cases.
- Use constructor dependency injection, thin controllers, and DTO API bodies.
- Keep JPA entities out of request and response payloads.
- Apply SOLID through focused responsibilities and real boundaries; avoid
  unnecessary interfaces, generic base classes, factories, or design patterns.
- Use records for simple immutable DTOs and Jakarta Validation for API input.
- Reject unknown request fields. Never accept a booking owner or registration
  role from the client. Obtain booking identity from the validated JWT subject;
  query booking reads by both ID and owner, with no ADMIN ownership bypass.
- Use Spring's resource-server chain and Nimbus JWT validation, not a custom JWT
  filter or cryptography. Keep JWT_SECRET mandatory and environment-provided.
  Preserve issuer/audience/time/subject/role validation and short token lifetimes.
- Store only BCrypt hashes. Enforce the 72-byte UTF-8 limit, use generic login
  failures, and keep sample/legacy accounts without passwords unable to log in.
- Public show reads stay public. Catalogue mutations require ADMIN; there are
  currently no mutation handlers or public role-management endpoints.
- Put business transactions in services. Design database constraints alongside
  use cases; document concurrency and isolation decisions when booking is added.
- Read `docs/BOOKING.md` before changing booking. After verified payment, claim
  HELD inventory and insert/link the booking in one service transaction.
  Never replace this with check-then-save, a Java lock, or an unconditional update.
- Every allocation path locks inventory first through `SeatInventoryService`,
  then evaluates database `clock_timestamp()` after waiting for that lock.
  Never use transaction-start time or a stale JPA entity for hold eligibility.
  Keep reservation identity and ownership immutable, preserve historical rows,
  and enforce HELD-to-terminal transitions with conditional updates.
- Expiry correctness must not depend on the scheduler. Claims reclaim old holds,
  reads expose effective expiry, and cleanup only normalizes persisted state.
  Preserve retry-safe confirmation and cancellation without releasing a later hold.
- Keep `uq_reservations_held_seat` as the partial unique hold guard and the
  reservation/booking composite FK as owner/seat link protection.
- Keep `uq_bookings_show_seat` as the database backstop and map only that specific
  unique violation to an already-booked conflict. Do not hide other database
  failures as `409`. Never hold a booking transaction open for external payment.
- Preserve the current `com.example.Tbooker` base package unless a package
  migration is explicitly part of the task. New subpackages use lowercase names.
- Follow existing Java formatting and imports. Do not introduce Lombok or broad
  mechanical reformatting for a small feature.
- The health controller returns a DTO directly because it has no business logic;
  do not add a service or repository merely to pass a constant through layers.

## Persistence and configuration

- PostgreSQL is the database in development and integration tests; avoid H2 as a
  substitute for PostgreSQL-specific behavior.
- Flyway owns DDL. Add versioned migrations; never edit an applied migration.
- Keep Hibernate `ddl-auto: validate`, open-in-view disabled, and Flyway clean
  disabled. Domain tables belong in the `tbooker` schema.
- Use `Long` identity keys and lazy to-one relationships. Read `docs/DATABASE.md`
  before changing catalogue relationships or constraints. `ShowSeat` belongs to
  one show and one physical seat; both must reference the same screen.
- Use `Instant` and PostgreSQL `timestamptz` for show times, with UTC JDBC/session
  settings and UTC API output. Do not use local timestamps for absolute instants.
- Sample data is opt-in via the `dev` profile and `db/dev` Flyway location. Keep
  it out of normal migrations and preserve its repeatability and existing status.
- Read database settings from environment variables. Keep `.env` ignored and
  update `.env.example` when configuration requirements change.
- Never commit credentials, production secrets, local database data, or logs.
- Preserve TLS and package verification during dependency installation.

## API and errors

- Preserve the requested catalogue routes `/api/shows`, `/api/shows/{showId}`,
  and `/api/shows/{showId}/seats`; health remains at `/api/v1/health`. Do not rename
  routes without an explicit API change. Use consistent HTTP status codes.
- Phase 8 booking creation requires a reservation and verified payment. The
  legacy `POST /api/shows/{showId}/bookings` must keep rejecting new creation with
  `409`; never restore this unpaid bypass. Use DTOs and documented status codes.
- Use Spring Problem Details for HTTP errors. Add centralized domain exception
  mappings when domain features require them; do not expose stack traces or
  internal database messages to clients.
- Keep `/api/v1/health` lightweight. Use Actuator readiness for database health.

## Verification and reporting

- Use the Maven wrapper with a Java 21 JDK. The existing Spring Boot parent
  manages compatible dependency versions; avoid arbitrary version overrides.
  Nimbus is explicitly pinned to 10.10 for the current JWT implementation;
  validate signature handling and the full suite when updating it.
- Security tests must traverse the real filter chain with signed tokens.
  Generate test-only signing keys at runtime; never use production secrets.
- Close cached test contexts before class-scoped containers stop so scheduler
  callbacks cannot access a stopped PostgreSQL instance. Reservation lifecycle
  tests disable automatic scheduling and invoke cleanup explicitly; a dedicated
  integration test verifies the actual scheduled callback.
- Run `bash mvnw clean verify` when practical. Full tests require Docker for
  Testcontainers and must not silently skip database checks when Docker is absent.
- `bash mvnw -Dtest=HealthControllerTests test` runs only the HTTP slice tests.
  Clearly report this reduced coverage if Docker is unavailable.
- Use JUnit 5 and Mockito for unit tests when mocking adds value; use
  Testcontainers PostgreSQL for database and transaction behavior.
- Add focused tests for meaningful new behavior and regressions. Test failure
  cases and concurrency when those features are introduced.
- Booking concurrency tests must use independent PostgreSQL transactions, force
  actual row contention, and assert one committed booking. Do not wrap these
  tests in a test-level transaction or substitute mocks for locking behavior.
- Keep the 20-request HTTP test coordinated with `ExecutorService` and
  `CountDownLatch`. Its test-only pool needs room for all contenders plus the
  lock owner and observer; do not silently turn it into a pool-queue test.
- Use fresh fixtures for repetitions, bounded waits, and executor/resource
  cleanup. Verify repeated `Flyway.migrate()` calls preserve both schema history
  and existing bookings; do not replay versioned SQL scripts manually.
- Test rollback after a real insert failure; a failed request must not leave a
  seat marked `BOOKED` without its booking.
- Report implemented behavior, changed files, actual test results, limitations,
  and brief design decisions. Stop at the requested phase boundary.

## Phase 8 payment conventions

- Mock payments are authorized in Phase 8; Phase 9 additionally permits Redis
  catalogue caching, and Phase 10 adds Kafka booking events. Real gateways, refunds,
  and confirmed-booking cancellation are not implemented.
- Read the payment sections of HLD, LLD, DATABASE, API, and DECISIONS before edits.
- Use injected PaymentStrategy implementations and the Spring-managed registry.
  Gateway orchestration must remain outside application transactions (NEVER).
- Keep financial outcome persistence separate from booking confirmation. A booking
  failure must not erase SUCCESS; preserve PENDING/reconciliation recovery state.
- Only internal independent provider verification can change payment outcome.
  Never add a client-supplied success/price/owner or an unauthenticated callback.
- Idempotency is scoped to owner/key and bound to reservation/method. Retain stable
  provider UUID and price across retries. Do not claim exactly-once external calls.
- Preserve inventory-first locking, provider version checks, pending-attempt and
  permanent-booking uniqueness, and composite owner/reservation/payment links.
- Do not reject real financial facts just to enforce one SUCCESS row; late extra
  successes require reconciliation and must never create another booking.
- Historical confirmed bookings may have no payment; do not fabricate one.
- Tests must retain the Phase 1–7 regression cases through the paid workflow and
  exercise real PostgreSQL contention, rollback, duplicate/stale results, and upgrades.


## Phase 9 cache conventions

- Redis caches only public movie/theatre/show DTO metadata. Never cache inventory
  for allocation or put reservation/payment/booking/authentication state in Redis.
- Read `docs/HLD.md` and `docs/PERFORMANCE.md` before changing the cache policy.
- Preserve the cache facade / transactional reader separation: hits avoid database
  transactions, and callers already in a transaction bypass the cache entirely.
- Use explicit cache names, typed JSON, versioned keys, and finite validated TTLs.
- Invalidate affected entries only after commit. JPA lifecycle events cover entity
  writes; bulk/native SQL writers need an explicit event or accept TTL staleness.
- Cache failure must fall back to PostgreSQL and remain observable. Do not swallow
  domain/database exceptions or make readiness/allocation depend on Redis.
- Keep Redis tests isolated in disposable containers. Run the full suite and use
  the k6 comparison for performance claims; never equate local RPS with capacity.


## Phase 10 messaging conventions

- Read `docs/MESSAGING.md` before changing booking event capture or Kafka processing.
- Every new confirmed booking and its unique outbox fact must commit together.
  Never publish to Kafka inside the booking transaction or skip capture when Kafka
  workers are disabled. Preserve the legacy confirmed-booking retry behavior.
- Use short SKIP LOCKED lease claims, database time, and fenced state updates.
  Never hold a database transaction/row lock while waiting for a broker acknowledgment.
- Retain stable event IDs across retries; transport is at-least-once. Commit each
  consumer marker and projection change together before committing its Kafka offset.
- Never discard a poison record or advance its offset when DLT publication fails.
  Preserve raw failed bytes and group/offset diagnostics without logging secrets.
- Health is public; Actuator metrics require ADMIN. Kafka failure must not fail
  PostgreSQL-based booking readiness. No real emails, microservices, or new gateways.
- Use real Kafka/PostgreSQL tests for retries, lease recovery, deduplication, and
  crash boundaries. Document simulated publisher restart separately from JVM/host failure.
- Existing regressions disable Kafka workers but still exercise outbox capture.
  Keep test cleanup aware of the outbox FK; never add production cascade deletion
  just to simplify fixtures. Do not rewrite applied versioned migrations.
