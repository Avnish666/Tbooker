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
- Read `docs/BOOKING.md` before changing booking. Claim availability with the
  conditional PostgreSQL update and insert the booking in one service transaction.
  Never replace this with check-then-save, a Java lock, or an unconditional update.
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
- Book through `POST /api/shows/{showId}/bookings`; use validated request DTOs,
  response DTOs, and `201`/`400`/`404`/`409` outcomes as documented.
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
