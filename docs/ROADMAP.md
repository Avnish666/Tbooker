# Tbooker roadmap

Each milestone is a separate implementation phase with its own scope and tests.
Start with a modular monolith and one PostgreSQL database. Extract services only
after a concrete operational need and an architecture decision record.
Earlier milestones describe their original scope; later phases supersede API
contracts where stated. See `API.md` for the current contract.

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
- Keep proposed future components distinct from the implemented scope at each phase.

## Phase 6 — Authentication and authorization (implemented)

- Register users with BCrypt hashes and a database-enforced unique email.
- Log in and issue short-lived JWTs signed with an environment-provided key.
- Validate tokens through Spring Security and apply USER/ADMIN route rules.
- Derive booking identity from the JWT and restrict booking reads to their owner.
- Keep catalogue reads public and reserve catalogue mutations for ADMIN.
- Preserve legacy users without assigning default passwords; verify V3-to-V4 upgrades.
- Test security with real signed JWTs and retain transaction/concurrency coverage.

## Phase 7 — Temporary reservations and expiration (implemented)

- Hold a show seat for five minutes using PostgreSQL timestamps and transactions.
- View, confirm, or cancel only the authenticated user's reservation.
- Retain historical reservations and permanent booking uniqueness.
- Serialize seat operations, reclaim expired holds synchronously, and run bounded scheduled cleanup.
- Make confirmation retries return the same booking; never release confirmed seats during expiry.
- Verify hold contention, expiry/confirmation races, ownership, rollback, scheduler wiring, and V4 upgrades.

## Phase 8 — Mock payment processing (implemented)

- Inject UPI/Card PaymentStrategy beans through a Spring-managed registry.
- Persist attempts and durable mock receipts in V6; support configured outcomes.
- Enforce owner-scoped initiation idempotency and stable provider request IDs.
- Independently verify results; handle duplicate, stale, and corrected outcomes.
- Require verified success for new bookings and disable unpaid immediate creation.
- Keep gateway execution outside application transactions and financial success
  separate from booking confirmation; retain retry/reconciliation states.
- Expose secure owner-only payment status/history and booking confirmation reads.
- Verify concurrency, rollback, retry boundaries, legacy migration preservation,
  and the existing reservation/security regressions against PostgreSQL.
- Document current guarantees and the absence of real provider/refund automation.

## Phase 9 — Redis catalogue caching and performance (implemented)

- Cache movie/theatre/show DTO lists and details with typed JSON and finite TTLs.
- Invalidate affected JPA catalogue changes after commit; document TTL consistency.
- Fall back to PostgreSQL on cache failure; keep availability and allocation uncached.
- Configure optional Redis locally, cache error counters, and per-cache TTL settings.
- Verify real Redis/PostgreSQL hits, expiry, invalidation, rollback, outages, and contention.
- Provide reproducible k6 baseline/cache comparison with measured query/hit counts.
- Retain all prior authentication, payment, migration, and concurrency regressions.

## Phase 10 — Kafka and transactional outbox (implemented)

- Atomically record one V1 confirmation event with each new paid booking.
- Publish through database-time leases, SKIP LOCKED, fencing, and capped backoff.
- Keep broker waits outside PostgreSQL transactions and preserve events during outages.
- Run independent notification/analytics groups with transactional deduplication.
- Bound consumer retries, preserve raw failed events in an acknowledged DLT, and document replay.
- Provide KRaft Compose configuration, connectivity health, ADMIN metrics, and failure tests.
- Retain Redis, JWT, payment, reservation, and migration regression coverage.
- Do not claim real email, cross-system exactly-once, or production HA readiness.

## Phase 11 — Horizontal scaling and failure verification (implemented)

- Run two Java containers behind NGINX with shared PostgreSQL, Redis, and Kafka.
- Preserve JWT ownership and database allocation/worker coordination across JVMs.
- Disable automatic request replay; document passive health and timeout behavior.
- Test 100 real HTTP contenders, expiration, retries, optional-service outages,
  backend restart, publisher SIGKILL after acknowledgment, and both consumer groups.
- Compare one/two JVMs with equivalent k6 workloads and per-instance metrics.
- See TESTING.md and PERFORMANCE.md for executed results and limitations.

## Phase 12A — GitHub Actions CI (workflow implemented)

- Verify PRs targeting main and pushes to main with Java 21 and the Maven wrapper.
- Cache Maven dependencies and run the existing real-container integration suite.
- Use read-only repository permissions and ephemeral test configuration.
- Preserve failure status and upload available test reports on failure.
- GitHub-hosted execution must be confirmed after the workflow is pushed; local
  verification alone does not establish a passing Actions run.
- Deployment and benchmark automation are outside this phase.

## Phase 12B — Reproducible deployment (implemented)

- Build from source in a pinned Java 21 multi-stage Dockerfile; tests run separately.
- Run two JVMs, NGINX, PostgreSQL, Redis, and Kafka with private service networking.
- Document required secrets, internal addresses, tuning, health, and daily commands.
- Retain PostgreSQL/Kafka volumes, disposable cache behavior, and Flyway startup locks.
- Verify clean initialization, JWT/paid booking, app/database/stack restarts, and
  optional-service outages in disposable projects. See DEPLOYMENT.md and TESTING.md.
- Preserve the host development workflow; no business feature or cloud deployment.

## Phase 12C — Performance benchmarking and capacity analysis (verified)

- Compare one/two JVMs with catalogue, uncached availability, hot-seat contention,
  unique paid-booking traffic and Kafka pipeline workloads in disposable projects.
- Declare latency/error/drop/event targets before increasing offered load; retain
  all 38 runs, including 17 performance-target misses, rather than selecting winners.
- Record HTTP distributions, SQL/cache counters, CPU/RAM/heap/GC, pool/lock pressure,
  outbox/projection delay, consumer lag, and broker-pause recovery evidence.
- Preserve Phase 9/11 evidence; publish raw metrics, charts, limitations and repeatable
  commands. Confirm 251 Maven tests and post-load correctness in every trial.
- Exact steady-state maximum capacity, longer soak tests, isolated profiling and
  production tuning remain future measurements; no business logic was changed.

## Phase 12D — Security, observability and reliability

Phase 12D security/observability/reliability work adds bounded JSON bodies,
correlation and structured logs, PostgreSQL operation budgets, explicit cache
health, conflict/failure metrics and an operational [runbook](RUNBOOK.md).
Existing JWT/ownership/payment/concurrency controls remain unchanged. Dependency
advisory scanning was blocked by the cloud proxy; see [SECURITY.md](SECURITY.md).

## Future — Catalogue administration and scheduling

- Add validated write use cases for catalogues and venue layouts when requested.
- Add show filters and pagination, seat categories, and price snapshots.
- Prevent overlapping shows on a screen; Phase 2 only rejects equal start times.
- Define inventory generation when shows are created and rules for layout edits.
- Add tests for scheduling boundaries and persistence invariants.

## Future — Booking lifecycle and operational controls

- Define confirmed-booking cancellation and refunds as a separate workflow.
- Revisit permanent booking uniqueness only if cancelled historical bookings
  must coexist with a new booking for the same inventory row.
- Add general request idempotency and hold-creation recovery, quotas, and retention policies.
- Measure expiry backlog and lock waits before tuning cleanup and admission limits.

## Future — Identity and payment boundaries

- Extend identity with email verification, password reset, legacy enrollment,
  token revocation/rotation, and abuse controls when requested.
- Replace mock strategies with a real sandbox provider only when requested.
- Authenticate external callbacks; add automated reconciliation, refunds, and
  provider timeout/uncertain-result operations. Mock duplicate/stale-result
  handling and durable manual retry are implemented in Phase 8.
- Introduce patterns only where actual variation or failure handling needs them.

## Future — Reliability and system design

- Extend Phase 12C's local capacity evidence with stable warmup, finer rate searches,
  longer repetitions, separate load-generator hosts and realistic production traffic.
- Expand profiling, existing structured logs, tracing and query/index investigations using
  the observed CPU, connection-queue, hot-row and event-processing pressure.
- Use measured cache behavior to decide whether to add stampede control, a
  circuit breaker, durable invalidation, and TTL jitter.
- Expand Kafka operations with lag/backlog alerts, retention/archiving policies,
  secured multi-node deployment, and controlled replay tooling when requested.

## Future — Delivery and operations

- Configure production deployment procedures; the CI workflow, local application
  Dockerfile, and NGINX Compose deployment are implemented.
- Separate runtime and migration database privileges.
- Configure secrets, TLS, backups, restoration checks, and migration rollout.
- Extend the local health probes and graceful shutdown configuration to production
  rollout procedures; verify shutdown during sustained traffic and extend the runbook.
- Extend the published Phase 12C evidence as deployment targets and traffic
  assumptions change, preserving earlier measurements and limitations.

Advance one phase at a time. Phase 12D adds operational safeguards; real
providers, real email, refunds, and confirmed-booking cancellation remain future work.
