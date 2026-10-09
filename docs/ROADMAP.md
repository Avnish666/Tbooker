# Tbooker roadmap

Each milestone is a separate implementation phase with its own scope and tests.
Start with a modular monolith and one PostgreSQL database. Extract services only
after a concrete operational need and an architecture decision record.

## Phase 1 — Foundation (implemented)

- Java 21, Spring Boot, Maven, and package conventions
- PostgreSQL configuration through environment variables and local Docker Compose
- Flyway schema initialization and Hibernate schema validation
- HTTP health endpoint, database readiness, and Problem Details
- JUnit 5 HTTP tests and PostgreSQL integration tests with Testcontainers

## Phase 2 — Database and show catalogue (implemented)

- Model users, movies, theatres, screens, physical seats, shows, and show seats.
- Add Flyway V2, foreign keys, scoped uniqueness, checks, and query indexes.
- Enforce that a show seat's physical seat belongs to the show's screen.
- Store show instants consistently in UTC.
- Expose the three read-only `/api/shows` APIs using DTOs and thin controllers.
- Provide repeatable, opt-in development data and database/API integration tests.
- Document relationships and API examples in `DATABASE.md` and the README.

## Phase 3 — Booking and concurrency (implemented)

- Add confirmed bookings, Flyway V3, and unique show-seat protection.
- Expose `POST /api/shows/{showId}/bookings` with validated DTOs and clear errors.
- Claim availability with a conditional PostgreSQL update at READ COMMITTED.
- Commit or roll back the seat update and booking insert as one transaction.
- Test actual concurrent row contention, duplicate requests, and insert failures.
- Document the race condition and database guarantees in `BOOKING.md`.
- Confirm immediately; no temporary holds or external payment calls.

## Phase 4 — Integration and concurrency testing (implemented)

- Review the booking transaction and its database safeguards.
- Coordinate 20 independent HTTP requests with an executor and countdown latches.
- Require one `201`, nineteen `409` responses, one booking, and a `BOOKED` seat.
- Repeat the race with isolated fixtures and verify no duplicate bookings.
- Test rollback both before and after booking insertion using real PostgreSQL.
- Verify repeated Flyway migrations preserve history, catalogue data, and bookings.
- Run the full Maven suite and document remaining limitations in `TESTING.md`.

## Phase 5 — System design documentation (implemented)

- Document the current architecture, components, request flows, and scaling limits.
- Describe actual classes, interfaces, relationships, and booking sequence diagrams.
- Record schema details, API contracts, design decisions, and alternatives.
- Link setup, verification, and future milestones from the README.
- Keep Redis, Kafka, holds, payments, and other future work explicitly unimplemented.

## Phase 6 — Authentication and authorization (implemented)

- Register users with BCrypt hashes and a database-enforced unique email.
- Log in and issue short-lived JWTs signed with an environment-provided key.
- Validate tokens through Spring Security and apply USER/ADMIN route rules.
- Derive booking identity from the JWT and restrict booking reads to their owner.
- Keep catalogue reads public and reserve catalogue mutations for ADMIN.
- Preserve legacy users without assigning default passwords; verify V3-to-V4 upgrades.
- Test security with real signed JWTs and retain transaction/concurrency coverage.

## Future — Catalogue administration and scheduling

- Add validated write use cases for catalogues and venue layouts when requested.
- Add show filters and pagination, seat categories, and price snapshots.
- Prevent overlapping shows on a screen; Phase 2 only rejects equal start times.
- Define inventory generation when shows are created and rules for layout edits.
- Add tests for scheduling boundaries and persistence invariants.

## Future — Holds and booking lifecycle

- Define temporary seat-hold state transitions, expiry rules, and cancellation.
- Design ownership and release semantics without weakening double-booking safety.
- Introduce request idempotency; a duplicate request currently returns `409`.
- Revisit the unique booking constraint deliberately if cancelled historical
  bookings must coexist with a new booking for the same inventory row.
- Test simultaneous requests, rollback, retries, and hold expiration.

## Future — Identity and payment boundaries

- Extend identity with email verification, password reset, legacy enrollment,
  token revocation/rotation, and abuse controls when requested.
- Define a payment interface, use a sandbox provider, and verify callbacks.
- Handle duplicate callbacks, cancellation, refunds, and reconciliation.
- Introduce patterns only where actual variation or failure handling needs them.

## Future — Reliability and system design

- Define traffic assumptions and capacity targets; measure the bottlenecks
  identified in the current HLD before publishing capacity estimates.
- Add metrics, structured logs, tracing, load tests, and query/index tuning.
- Consider Redis for measured cache needs, and messaging such as Kafka for
  specific asynchronous workflows; neither is a prerequisite for the monolith.
- If asynchronous processing is introduced, define outbox delivery, consumer
  idempotency, retry behavior, and failure recovery first.

## Future — Delivery and operations

- Add an application Dockerfile, CI build/test pipeline, and deployment setup.
- Separate runtime and migration database privileges.
- Configure secrets, TLS, backups, restoration checks, and migration rollout.
- Wire the existing health probes into deployment configuration and verify
  shutdown behavior; add operational runbooks.
- Publish reproducible performance evidence for the resume project, with
  limitations documented.

Advance one phase at a time. Do not add holds, Redis, Kafka, payments, or
cancellation as part of Phase 6.
