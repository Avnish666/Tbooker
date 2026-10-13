# Integration and security verification

The current suite verifies Phases 1–9, including payment-gated bookings and optional
Redis catalogue caching. Historical phase results are retained below; the final
section records Phase 9 verification. Database/concurrency tests use PostgreSQL
Testcontainers; cache tests additionally use an isolated Redis container.

## Implementation review

The supported Phase 8 allocation path has these safeguards:

1. JWT authenticates identity; controllers/DTOs cannot select arbitrary owner or
   payment outcome. Resource and owner-scoped queries validate associations.
2. All lifecycle mutations lock the show-seat row first at READ_COMMITTED and
   sample database time after waiting. Conditional updates authorize transitions.
3. Payment preparation persists owner/key uniqueness, stable provider identity,
   and server amount. The mock ledger deduplicates the provider operation.
4. Gateway calls occur outside application transactions. Independent verification
   persists financial outcome in its own transaction before booking confirmation.
5. `ReservationService.confirmLocked` checks active owned hold and SUCCESS proof,
   then changes inventory, inserts booking, and links reservation/payment in one
   transaction. Failures roll back allocation without erasing financial success.
6. Permanent booking uniqueness, one-HELD and one-PENDING partial uniqueness, and
   composite ownership FKs are database backstops. Stale/equal-version contradictory
   provider results cannot overwrite committed progress.

Concurrency tests use actual independent HTTP requests/database transactions,
not in-memory locks or a mocked repository. No double-booking regression was
observed in the executed suite; this does not prove all possible deployment failures.

## Twenty-request test

`BookingConcurrencyIntegrationTests` uses JUnit 5, Spring Boot Test with a random
HTTP port, and a dedicated Testcontainers PostgreSQL 17 instance. It performs
three repetitions, each with a fresh available seat and 20 distinct users.

For each repetition:

- A fixed `ExecutorService` has 20 workers. A ready `CountDownLatch` ensures all
  workers have started, and a start latch releases their HTTP requests together.
- A coordinating JDBC transaction temporarily locks the target row. The test
  polls `pg_stat_activity` until all 20 inventory lock queries are blocked, proving
  that every request reached PostgreSQL before a winner is allowed to commit.
- The lock is released, all futures are collected, and every response is checked.
- Each worker first creates a hold. Exactly one gets `201`, pays through the real
  mock-payment HTTP API (`201`), and retrieves its booking (`200`). The other 19
  hold requests must return `409` Problem Details, not unrelated server errors
  or the unique-constraint fallback.
- Independent database queries require one confirmed booking belonging to the
  winning user, matching the response ID, a `BOOKED` seat, and zero duplicate
  `show_seat_id` groups.

The connection pool is set to 24 **only on this test context**: 20 request
transactions, one coordinating lock owner, one observer, and spare capacity.
The production pool configuration is unchanged. A pool smaller than the number
of waiting requests would prevent this test from proving simultaneous database
contention.

There is no outer test transaction, so the HTTP transactions commit and roll
back independently. PostgreSQL containers and HTTP ports are isolated from the
developer database. Fixture cleanup deletes only rows created by the repetition.
Waits and HTTP requests have deadlines, futures are cancelled on cleanup, and
the executor is shut down. Repetitions execute sequentially; concurrency occurs
inside each repetition. The existing test classes use their own containers and
the default sequential JUnit method execution.

## Rollback and migration tests

`BookingIntegrationTests` injects failures with test-only PostgreSQL triggers:
one `BEFORE INSERT` and one `AFTER INSERT`. Both execute after the seat claim;
the latter executes after a booking row has been inserted. Each request must
return the expected generic `500`, leave zero bookings and a `HELD` seat with
verified SUCCESS payment retained, and allow confirmation retry after removing
the trigger. Hold creation and payment verification committed before confirmation. These expected HTTP
errors are assertions in passing tests, not ignored test failures.

Other existing checks cover rollback after a unique-constraint failure and a
waiting request proceeding when a competing transaction rolls back.

Migration repeatability is checked through `Flyway.migrate()`, not by manually
rerunning versioned SQL. Both the default and development-profile contexts call
it three additional times. Every call must execute zero migrations, validate
successfully, have no pending migrations, and preserve the complete schema
history. The development booking check snapshots all ten domain tables plus
mock receipts after paid confirmation and requires every row to remain unchanged. Existing
tests separately replay development seed SQL and verify no duplicate data or
reset booking status.

## Execution and results

Requirements: Java 21 JDK, a working Docker daemon, and access to Maven Central
and container images (or populated caches).

```bash
bash mvnw clean verify

# Focused booking/concurrency verification:
bash mvnw -Dtest=BookingIntegrationTests,BookingConcurrencyIntegrationTests test
```

The full Maven `clean verify` run during Phase 4 used the cloud environment's
Maven proxy settings and dependency cache. It completed successfully:

| Test class | Passed | Failed / errors | Skipped |
| --- | ---: | ---: | ---: |
| `BookingConcurrencyIntegrationTests` | 3 repetitions | 0 | 0 |
| `BookingIntegrationTests` | 35 | 0 | 0 |
| `ShowCatalogueIntegrationTests` | 31 | 0 | 0 |
| `TbookerApplicationTests` | 4 | 0 | 0 |
| `HealthControllerTests` | 2 | 0 | 0 |
| **Total** | **75** | **0** | **0** |

The three concurrency repetitions submitted 60 HTTP booking requests in total.
Each repetition produced one success, nineteen conflicts, and one stored booking.
Surefire XML reports in `target/surefire-reports` contain the actual outcomes.

Docker was available for this run, and no database tests were skipped. If Docker
is unavailable in another environment, the integration suite must fail visibly;
do not add automatic skipping or claim database coverage from the health slice
alone. `bash mvnw -Dtest=HealthControllerTests test` is only a reduced HTTP check.

## Phase 5 re-verification

The full suite was executed again during the documentation review on
2026-10-09 (Asia/Kolkata), using Java 21 and a working Docker daemon. It completed
with **BUILD SUCCESS: 75 tests, 0 failures, 0 errors, 0 skipped**, with the same
per-class counts shown above. The three 20-request races again passed all response
and database assertions. The executable Spring Boot JAR was built successfully.
No application, test, configuration, or migration source changed in this phase.

The cloud invocation used its existing setup helpers and writable Maven cache:

```bash
source /workspace/.setup/env.sh
bash mvnw -s /workspace/.cache/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.cache/maven/repository clean verify
```

An initial attempt stopped before compilation because Maven's default
`/home/agent/.m2/repository` was not writable. Specifying the workspace cache
resolved this environment issue; it was not a test failure. The helper paths are
cloud-specific and are not needed for the standard local `bash mvnw clean verify`
command. Surefire XML results are in `target/surefire-reports`.

## Phase 6 verification

The full Maven `clean verify` run on 2026-10-09 (Asia/Kolkata) used Java 21,
PostgreSQL Testcontainers, Spring Security, and Nimbus JOSE + JWT 10.10. The cloud
command is the same as in the Phase 5 section. **BUILD SUCCESS** produced the
executable JAR and these results:

| Test class | Passed | Failures / errors | Skipped |
| --- | ---: | ---: | ---: |
| `SecurityIntegrationTests` | 42 | 0 | 0 |
| `JwtConfigurationTests` | 8 | 0 | 0 |
| `BookingIntegrationTests` | 35 | 0 | 0 |
| `BookingConcurrencyIntegrationTests` | 3 repetitions | 0 | 0 |
| `ShowCatalogueIntegrationTests` | 31 | 0 | 0 |
| `TbookerApplicationTests` | 4 | 0 | 0 |
| `HealthControllerTests` | 2 | 0 | 0 |
| **Total** | **125** | **0** | **0** |

Tests use a process-generated random signing key and actual token signatures;
there is no mocked JWT decoder or `@WithMockUser` bypass. Registration/login
tests exercise BCrypt and use real login tokens for booking/ownership checks.
Concurrency fixtures use independently signed subject tokens for each contender.
All three 20-request races still require one `201`, nineteen `409`, one booking,
and a `BOOKED` seat after actual database row contention.

Security checks include salted hash storage, case-insensitive and simultaneous
duplicate registration, password byte limits, role/owner injection, generic login
errors, missing/malformed/unsigned/wrong-key/expired JWTs, invalid issuer/audience/
subject/role/time claims, header-only authentication, and role-based catalogue
access. Own-booking reads are checked with distinct login identities and with an
ADMIN who is not the owner. `POST /api/shows` remains unimplemented: an ADMIN
passes security and reaches `405`, while USER receives `403`.

A separate database is first migrated to V3, seeded, and given a confirmed
booking; V4 then preserves that relationship and leaves the legacy password null.
Repeated migration remains a no-op. Configuration tests reject missing/weak keys
and invalid token lifetimes. Existing rollback/constraint tests continue to use
real PostgreSQL, with V1–V3 and the conditional update unchanged.

Latest XML results replace earlier run artifacts in `target/surefire-reports`.
No tests were skipped. These are correctness checks, not a security audit,
penetration test, throughput benchmark, or failover certification.

## Remaining weaknesses and limits

- The repository does not configure a database lock timeout or statement timeout
  for booking. A long-running competing transaction can make requests wait; the
  connection-acquisition timeout does not bound an already-running SQL update.
- Hold-creation retries have no recovery key. Payment initiation is owner/key
  idempotent and reservation confirmation recovers the original booking.
- Foreign keys and uniqueness prevent duplicate/dangling bookings, but direct SQL
  can still make availability disagree with booking rows. The supported service
  transaction preserves consistency; it does not repair existing inconsistencies.
- JWT identity and ownership are now verified. Token revocation/refresh, rate
  limiting, email verification, password reset, and legacy enrollment are absent;
  existing tokens retain their roles until expiry. See [SECURITY.md](SECURITY.md).
- These tests use one application instance and one PostgreSQL instance. They do
  not simulate application crashes, database failover, network partitions, or
  production traffic. The 24-connection test setting is not a production pool
  sizing recommendation or a throughput/latency benchmark.

The remaining operational limitations are deferred beyond Phase 8.

## Phase 7 verification

This records the Phase 7 baseline. In Phase 8 the same lifecycle/concurrency
assertions run with real verified payment fixtures before confirmation; the old
direct-booking success case now checks rejection and paid replacement instead.

`ReservationIntegrationTests` disables automatic scheduling and uses PostgreSQL
through Testcontainers, real signed JWTs, and independent transactions. It covers:

- Five-minute creation and effective public/owner read states.
- Missing resources, show-seat mismatch, invalid inputs, and owner/expiry injection.
- Missing, malformed, and expired tokens; foreign USER/ADMIN ownership rejection.
- Active-hold exclusion of other holds and immediate bookings.
- Confirmation, repeat/concurrent confirmation, cancellation, and terminal history.
- Expiration without cleanup, replacement holds, and direct-booking reclamation.
- 20 HTTP hold contenders coordinated by `ExecutorService`/`CountDownLatch`,
  all observed waiting on PostgreSQL locks; repeated three times.
- Hold-versus-direct-booking and cancel-versus-confirm contention.
- Expiry after a confirmation transaction starts but before it obtains the lock.
- A confirmation decision before expiry that commits after the deadline;
  concurrent cleanup skips its row and cannot undo the booking.
- Cleanup of expired rows, locked-row skipping, and repeat application.
- Forced AFTER-insert/update database failures rolling back the entire allocation.
- Hold uniqueness, booking-link owner integrity, V4-to-V5 migration preservation,
  and repeated Flyway migration calls.

`ReservationCleanupIntegrationTests` enables the actual scheduler with a short
interval and awaits a committed EXPIRED/AVAILABLE outcome without calling an API
or the cleanup service itself. Shared test support closes contexts, including
schedulers and pools, before class-scoped PostgreSQL containers stop.

The reservation concurrency context has 24 database connections for 20 workers,
a coordinating blocker, and an observer. Fixtures reset only their test container.
Time-boundary tests use persisted database timestamps; no five-minute sleeps,
authentication mocks, or substitute database engines are used. The Phase 7 suite retained security, immediate-booking, and catalogue coverage;
Phase 8 adapts creation cases to the paid workflow and preserves those regressions.

On **2026-10-12**, full Maven `clean verify` completed with **BUILD SUCCESS**:

| Test class | Executed | Failed / errors / skipped |
| --- | ---: | --- |
| `ReservationIntegrationTests` | 33 | 0 / 0 / 0 |
| `ReservationCleanupIntegrationTests` | 1 | 0 / 0 / 0 |
| `SecurityIntegrationTests` | 42 | 0 / 0 / 0 |
| `BookingIntegrationTests` | 35 | 0 / 0 / 0 |
| `BookingConcurrencyIntegrationTests` | 3 | 0 / 0 / 0 |
| `ShowCatalogueIntegrationTests` | 31 | 0 / 0 / 0 |
| `TbookerApplicationTests` | 4 | 0 / 0 / 0 |
| `HealthControllerTests` | 2 | 0 / 0 / 0 |
| `JwtConfigurationTests` | 8 | 0 / 0 / 0 |
| **Total** | **159** | **0 / 0 / 0** |

The executable Spring Boot JAR was built. The cloud run used the same setup
helper, settings file, and writable Maven cache documented above. An initial
full run found one obsolete assertion treating `HELD` as an invalid state;
it now tests `UNSUPPORTED`, retaining invalid-state coverage. Scheduler teardown
was corrected by closing test contexts before stopping containers. The final
run passed without scheduler callbacks accessing stopped containers.

These are correctness checks on a single application/database setup, not load,
latency, availability, multi-node failover, or sustained-backlog measurements.

## Phase 8 verification

On **2026-10-13**, the full wrapper `clean verify` completed with **BUILD SUCCESS**
and built `target/Tbooker-0.0.1-SNAPSHOT.jar`. The cloud environment used Java 21,
Docker 28.4.0, and real PostgreSQL 17.11 Testcontainers. Exact command:

```bash
source /workspace/.setup/env.sh
bash mvnw -s /workspace/.cache/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.cache/maven/repository clean verify
```

The environment settings/cache override Maven's read-only default location; it
is not a change to project dependencies. Normal local setup uses
`bash mvnw clean verify`. No production credentials are required by the tests.

| Test class | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `PaymentIntegrationTests` | 35 | 0 | 0 | 0 |
| `ReservationIntegrationTests` | 33 | 0 | 0 | 0 |
| `ReservationCleanupIntegrationTests` | 1 | 0 | 0 | 0 |
| `SecurityIntegrationTests` | 42 | 0 | 0 | 0 |
| `BookingIntegrationTests` | 35 | 0 | 0 | 0 |
| `BookingConcurrencyIntegrationTests` | 3 | 0 | 0 | 0 |
| `ShowCatalogueIntegrationTests` | 31 | 0 | 0 | 0 |
| `TbookerApplicationTests` | 4 | 0 | 0 | 0 |
| `HealthControllerTests` | 2 | 0 | 0 | 0 |
| `JwtConfigurationTests` | 8 | 0 | 0 | 0 |
| **Total** | **194** | **0** | **0** | **0** |

All existing Phase 1–7 test classes executed. No tests were unavailable, disabled,
or skipped. The complete run's Surefire XML is in `target/surefire-reports`;
cloud build output was captured at `/tmp/tbooker-phase8-clean-verify.log` (local
artifact, not committed). Expected PostgreSQL trigger errors appear in passing
rollback tests; they are deliberately asserted failures, not ignored test errors.

Payment tests verify:

- Verified success, configured Card failure, and owner-only status/history/booking reads.
- No unpaid confirmation or legacy creation bypass, no client SUCCESS/owner/amount
  injection, JWT rejection, and default-denied callback routes.
- Matching-key replay, parameter conflicts, per-owner key scope, invalid/missing keys,
  and a fresh-key retry after verified failure while a hold remains active.
- Twenty independent HTTP initiations with identical keys (one `201`, nineteen `200`)
  and different keys (one `201`, nineteen `409`), observed waiting on PostgreSQL locks.
- Twenty concurrent trusted result deliveries; exactly one booking, no duplicate receipt.
- Two different seats racing for the same owner/key; one attempt and one conflict.
- Expired/cancelled holds before initiation and late SUCCESS after another user
  has replaced the hold; reconciliation cannot take that user's inventory.
- Recovery from an attempt committed before the gateway call, provider SUCCESS
  before outcome persistence fails, and financial SUCCESS before booking failure.
- Rollback on attempt insertion and on the final payment-confirmation write,
  proving inventory, reservation link, and booking all roll back together.
- Pending provider receipt retries, stale/contradictory versions, newer corrections
  to FAILED, and additional financial SUCCESS without another booking.
- Receipt amount mismatch rejection and NEVER propagation preventing gateway
  calls from an application transaction.
- Pending-attempt/owner database constraints and V5-to-V6 preservation without
  fake payments. Existing paid-booking migration tests validate repeated no-op
  migration and unchanged history/data for all tables including receipts.

`PaymentTestFixture` calls the actual strategy and verification persistence but
stops before confirmation. Reservation/booking tests use it to exercise their
original confirmation failure/race assertions with genuine mock proof; it does
not forge SUCCESS with SQL or bypass JWT on the HTTP endpoints. Security and the
20-client booking workflow traverse hold, payment, and booking-read HTTP APIs.
Test-only direct provider ledger edits model delayed/corrected outcomes; the
application exposes no such status-mutation API.

Interrupted workflows are simulated at durable transaction boundaries and with
real PostgreSQL failure triggers. These tests do not kill an OS process, partition
a network, run multiple application instances, exercise a real payment network,
or benchmark production performance. They demonstrate recovery from equivalent
persisted intermediate states, not deployed failover or payment certification.
Automatic reconciliation/refunds, lock timeouts, and operational controls remain
limitations documented in HLD and DECISIONS.


## Phase 9 verification

On **2026-10-13**, the full wrapper `clean verify` passed with **218 tests,
0 failures, 0 errors, 0 skipped**, using the same cloud Maven command above.
The JAR was built successfully with Java 21 and Docker-backed PostgreSQL 17.11
and Redis 7.4. Surefire XML is in `target/surefire-reports`; the local log is
`/tmp/tbooker-phase9-clean-verify.log`. All existing 194 regression cases ran.

| Test class | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `BookingConcurrencyIntegrationTests` | 3 | 0 | 0 | 0 |
| `BookingIntegrationTests` | 35 | 0 | 0 | 0 |
| `CatalogueCacheIntegrationTests` | 24 | 0 | 0 | 0 |
| `PaymentIntegrationTests` | 35 | 0 | 0 | 0 |
| `ReservationCleanupIntegrationTests` | 1 | 0 | 0 | 0 |
| `ReservationIntegrationTests` | 33 | 0 | 0 | 0 |
| `SecurityIntegrationTests` | 42 | 0 | 0 | 0 |
| `ShowCatalogueIntegrationTests` | 31 | 0 | 0 | 0 |
| `TbookerApplicationTests` | 4 | 0 | 0 | 0 |
| `JwtConfigurationTests` | 8 | 0 | 0 | 0 |
| `HealthControllerTests` | 2 | 0 | 0 | 0 |
| **Total** | **218** | **0** | **0** | **0** |

`CatalogueCacheIntegrationTests` adds 24 cases with real Redis and PostgreSQL:

- All six caches: miss executes one Hibernate SQL statement, hit executes none
  and opens no database transaction; API and stored JSON match with UTC timestamps.
- Real Redis TTL expiry refreshes an out-of-band SQL change.
- JPA movie/theatre/screen/show edits invalidate after commit, preserving unrelated
  details; show creation/removal invalidate lists and the removed detail.
- Rollback leaves cache intact; reads within a transaction bypass cached data and
  never publish its uncommitted values.
- Malformed cached JSON falls back to SQL and is replaced by a valid DTO.
- Pausing the Redis container causes real command timeouts: catalogue reads still
  work, readiness stays UP, and failed eviction cannot roll back a database commit.
- Twenty coordinated cold/warm catalogue requests return identical DTOs; warm
  reads execute no SQL. Cold reads may execute multiple queries (no single-flight).
- With warm cache and with Redis paused, real signed JWT ownership, paid
  confirmation, permanent booking uniqueness, and live inventory still work.
- Expiry/cancellation are immediately visible through the uncached seat route.
- Twenty MockMvc requests contend in independent PostgreSQL transactions while
  the cache is enabled. A coordinator holds the row until `pg_stat_activity`
  observes all 20 lock waiters; exactly one hold succeeds and 19 conflict.
- New public movie/theatre APIs validate IDs, do not cache missing resources, and
  retain USER/ADMIN mutation authorization without adding write handlers.

The first targeted run had two incorrect new assertions expecting `201` from the
existing confirmation API; its contract is `200`. Those assertions were corrected
before the successful full run. No domain behavior was changed to satisfy them.
The cache suite uses MockMvc through the real filter chain; existing paid-booking
concurrency tests still traverse a random-port HTTP server. Fixtures flush only
the dedicated disposable Redis container. Legacy regression contexts explicitly
disable optional caching; no database mocks or automatically skipped cases are used.

The suite does not simulate every network failure or verify multiple deployed
backend instances. In-process invalidation events are not durable, race-driven
metadata staleness remains TTL-bounded, and Redis outages can amplify SQL load.
Performance evidence is separately recorded in [PERFORMANCE.md](PERFORMANCE.md);
passing correctness tests is not evidence of production capacity.
