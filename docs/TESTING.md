# Integration and security verification

The current suite verifies Phases 1–10, including payment-gated bookings, Redis
catalogue caching, and Kafka booking events. Historical phase results are retained
below; later sections record Phase 10 and Phase 11 verification. Database/concurrency tests
use PostgreSQL Testcontainers; cache and messaging tests additionally use isolated
Redis and Kafka containers respectively.

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
   then changes inventory, inserts booking, links reservation/payment, and records
   the outbox fact in one transaction. Failures roll back allocation and the event
   without erasing financial success.
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
- The Maven tests use one application context at a time and disposable databases.
  Phase 10 recreates publisher objects at durable crash boundaries, runs competing
  publisher objects, and pauses real brokers; it does not kill JVMs or verify a
  multi-host deployment, database failover, or production traffic. The 24-connection
  test setting is not a production pool sizing recommendation or a benchmark.

Phase 11 adds deployed two-JVM and real process-failure evidence below; multi-host
HA and production controls remain future work.

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

The Phase 9 cache suite does not simulate every network failure or verify
multiple deployed backend instances; Phase 11 adds separate deployment tests. In-process invalidation events are not durable, race-driven
metadata staleness remains TTL-bounded, and Redis outages can amplify SQL load.
Performance evidence is separately recorded in [PERFORMANCE.md](PERFORMANCE.md);
passing correctness tests is not evidence of production capacity.


## Phase 10 verification

On **2026-10-13 at 07:16:44 UTC**, the full Maven wrapper `clean verify` completed
with **BUILD SUCCESS: 251 passed, 0 failures, 0 errors, 0 skipped**. It built
`target/Tbooker-0.0.1-SNAPSHOT.jar`. All 218 Phase 1–9 cases and 33 new messaging
cases executed. Java 21, Docker, PostgreSQL 17.11, Redis 7.4, and Kafka 3.9.1 were
available; no tests were disabled or unavailable. The exact cloud invocation was:

```bash
source /workspace/.setup/env.sh
bash mvnw -s /workspace/.cache/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.cache/maven/repository clean verify
```

| Test class | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `BookingConcurrencyIntegrationTests` | 3 | 0 | 0 | 0 |
| `BookingEventsIntegrationTests` | 32 | 0 | 0 | 0 |
| `BookingIntegrationTests` | 35 | 0 | 0 | 0 |
| `CatalogueCacheIntegrationTests` | 24 | 0 | 0 | 0 |
| `MessagingRecoveryIntegrationTests` | 1 | 0 | 0 | 0 |
| `PaymentIntegrationTests` | 35 | 0 | 0 | 0 |
| `ReservationCleanupIntegrationTests` | 1 | 0 | 0 | 0 |
| `ReservationIntegrationTests` | 33 | 0 | 0 | 0 |
| `SecurityIntegrationTests` | 42 | 0 | 0 | 0 |
| `ShowCatalogueIntegrationTests` | 31 | 0 | 0 | 0 |
| `TbookerApplicationTests` | 4 | 0 | 0 | 0 |
| `JwtConfigurationTests` | 8 | 0 | 0 | 0 |
| `HealthControllerTests` | 2 | 0 | 0 | 0 |
| **Total** | **251** | **0** | **0** | **0** |

Surefire XML in `target/surefire-reports` supplied these counts. Full local output
is `/tmp/tbooker-phase10-clean-verify-complete.log`; neither build artifacts nor
logs are committed. The cloud-specific Maven settings/cache are optional locally.
A focused messaging run is:

```bash
bash mvnw -Dtest=BookingEventsIntegrationTests,MessagingRecoveryIntegrationTests test
```

`BookingEventsIntegrationTests` uses real PostgreSQL/Kafka, actual paid confirmation
through MockMvc and JWT validation, and disabled automatic polling for deterministic
failure injection. Its 32 cases cover:

- Exactly one logical event, matching payload/booking time, repeat confirmation,
  and measured publication delay.
- Rollback on outbox insertion and deferred commit failures, and no event after
  payment failure. Verified financial SUCCESS survives allocation rollback.
- Runtime broker pause: booking still commits, readiness stays UP, messaging health
  becomes DOWN, send failure schedules retry, and recovery delivers the event.
  A separate JDBC NOWAIT lock proves no outbox row lock is held during the send.
- Twenty duplicate Kafka deliveries and three independent 20-thread PostgreSQL
  consumer races; one notification, one count increment, and one marker per group.
- Transient failures in both projections roll back markers/effects and then retry
  successfully. Exhausted retries reach the DLT; replay updates only the failed
  projection and leaves the successful projection unchanged.
- Seven poison variants, including malformed/missing/unsupported fields, wrong
  key, tombstone, invalid bytes, and a near-limit payload. Raw failed bytes and
  origin/group headers survive DLT delivery; subsequent valid records proceed.
- DLT unavailability prevents committing past the failed source offset; recreating
  the DLT resumes safe recovery and processing.
- Publisher recreation after lease claim and after broker acceptance; both recover
  from persisted state, preserving event IDs and deduplicated effects.
- Competing publishers skip locked work, recover the remaining row, and preserve
  all eight events. Stale lease tokens cannot overwrite a later publisher's state.
- Broker acceptance followed by a database state-update failure leaves a reclaimable
  lease and emits the failure metric; retry does not repeat projection effects.
- Outbox logical uniqueness and JSON-null identity checks; reused event/booking
  identities are dead-lettered without changing committed projections.
- NEVER propagation rejects publication from an ambient database transaction.
- Metrics routes return 401 without a JWT, 403 for USER, and 200 for ADMIN.
- V6-to-V7 upgrade preserves historical bookings, seeds analytics without fake
  historical events, validates checksums, and repeated migration executes nothing.

`MessagingRecoveryIntegrationTests` pauses Kafka before Spring initialization.
The application starts and confirms a paid booking, records a backlog gauge and
publish failures, then recovers through its actual schedulers after Kafka resumes.
Topic provisioning, publication, both projections, backlog drain, publication delay,
and native consumer lag metrics are observed without manually invoking a worker.

The new suite initially exposed an intermittent duplicate-consumer error: the
marker insert targeted only `(consumer_name,event_id)`, while an identical racing
insert could violate the separate booking unique index. It now arbitrates both
unique constraints and validates the winning marker/hash in a fresh READ_COMMITTED
snapshot. Three repeated races and rejection of a different event ID for the same
booking cover this fix. The final full run above contains the corrected code.

Each messaging class owns disposable Kafka/PostgreSQL containers. Tests stop
listeners before resetting fixtures, delete only their broker's old test records,
wait for actual committed offsets, bound asynchronous waits, and close application
contexts before containers. Existing tests disable Kafka workers but still record
outbox facts; fixtures remove outbox references before bookings. Existing migration
upgrade tests remain targeted at their original schema versions.

The single-node Compose KRaft service was also started in an isolated temporary
Compose project: its health check and broker topic-list command succeeded, and
only that project's resources were removed afterward. Documentation links, event
schema JSON, and all 18 Mermaid diagrams were checked separately.

Limitations: publisher objects are recreated at durable crash boundaries; JVM/host
processes are not killed. Competing publisher objects share one application process.
Broker pauses and SQL failures do not certify multi-host failover or every network
partition. No Kafka throughput/latency benchmark, production HA, real email delivery,
retention cleanup, automatic DLT replay, or external exactly-once guarantee is claimed.
Historical Phase 9 k6 results were not rerun; the catalogue benchmark runner disables
messaging workers to keep its workload focused.

## Phase 11 verification

Final `bash mvnw clean verify` completed with **251 passed, 0 failures, 0 errors,
0 skipped** on Java 21, using environment-specific Maven settings/cache arguments.
This rerun includes all existing reservation/payment/security/cache/messaging,
Flyway upgrade/repeatability, rollback, and concurrency regressions. The retained
[Maven class-count report](../verification/results/2026-10-13-maven.json) is derived
from the final Surefire XML files. No dependency prevented a requested check.


The standalone Docker checks in `verification/multi_instance.py` completed on
2026-10-13: **13 passed, 0 failed, 0 skipped**, executed as the original complete
12-case suite plus the subsequently added in-flight payment case. These are separate end-to-end cases,
not additional JUnit methods. The runner started and health-checked NGINX, two
real app JVMs, PostgreSQL, Redis, and Kafka, then removed only its own resources.
The [raw report](../verification/results/2026-10-13-multi-instance.json) retains
HTTP counts, actual PostgreSQL contention, Kafka CLI membership, and failure logs.

| Case | Verified result |
| --- | --- |
| Routing, JWT and metrics | Both upstreams; one login JWT accepted by both; metrics 401/403 and instance tags; no internal host ports |
| 100 contenders | 100 simultaneous database row-lock waiters across both JVMs; one 201 and 99 conflicts; 20 confirmations return one booking |
| Expiration | Effective expiry, reclaim by another owner, scheduled normalization |
| Cancellation | Retry does not release another owner's hold; foreign confirmation 404 |
| Confirmation waits past expiry | Paid confirmation waiting on the lock cannot use a stale deadline |
| Confirmation commits after deadline | Eligibility before expiry remains valid through commit; cleanup cannot release BOOKED |
| Concurrent payment results | 20 deliveries converge on one payment and booking |
| Redis outage | Database fallback, readiness, paid booking succeed |
| Kafka outage | Paid booking commits while messaging health fails; backlog and both projections recover |
| Backend crash and restart | Persisted active hold survives; healthy peer remains reachable; no automatic upstream replay |
| Publisher crash after acknowledgment | Real SIGKILL before PUBLISHED state write; survivor reclaims lease; one notification and two group markers |
| Consumer groups | Two members in each of two distinct groups; hosts match app containers; all three partitions assigned |
| Interrupted payment POST | JVM killed while its first payment insert waited; 502 with one upstream, zero persisted payments, explicit retry 201 then repeat 200 |

The backend test observed **19 HTTP 200 and one 504** while a peer was stopped.
NGINX recorded a timeout *while connecting to upstream*, not a Spring exception.
During restart it observed **142 HTTP 200 and four 502**. Six same-key payment
writes returned one 201 and five 200s, with one durable payment/booking and one
upstream per attempt. These are observed counts, not a promise that every future
failure has this distribution. Passive failure detection necessarily allows
failed probes; disabling replay preserves explicit client recovery semantics.

Two earlier failures were verification assumptions: the gateway test excluded
504, and Kafka 3.9's single-topic `--members --verbose` output omits the topic name.
The corrected checks require NGINX failure evidence and healthy-peer recovery,
and cross-check `--members` with topic/partition/consumer IDs from `--offsets`.
A newly added SQL assertion also initially used the wrong link direction; the
final runner joins reservations to bookings through `reservation.booking_id`.
No assertion bypass or booking implementation change was used.

A final focused [in-flight payment test](../verification/results/2026-10-13-payment-no-replay.json)
blocked the first payment insert with a test-only advisory lock, killed its actual
serving JVM, and asserted 502 with one upstream and no durable payment/booking.
Releasing the lock allowed the abandoned transaction to roll back. Only an
explicit owner/key retry created the confirmed payment (201); another retry
returned the same payment (200). This proves the failed POST was not silently
replayed by NGINX; it does not claim every gateway error precedes a commit.
The current default runner includes all 13 cases.

The correctness runner expands each pool to 60 solely to establish real 100-way
lock contention (default and benchmark pools are 20 per JVM). SQL failure and
blocking triggers exist only in the disposable test database. Normal Compose
uses automatic restart; crash tests override it to keep a killed process stopped
until the test explicitly restarts it. The final report contains no skipped cases.

Remaining limits: one Docker host, one PostgreSQL writer and one Kafka broker;
no host/disk/database failover or network-partition certification. Performance
runs are separate from correctness and documented in [PERFORMANCE.md](PERFORMANCE.md).

The twelve k6 measured runs also met all thresholds: zero unexpected errors,
matching payment/booking counts, and one hold plus expected conflicts in every
hot-seat run. These are load runs, not additional JUnit test methods. All 20
Mermaid diagrams parsed successfully, and local documentation links were checked.
Docker Compose health/startup/port smoke checks are included in the end-to-end
runner; there is no invented separate smoke-test count.

## Phase 12B deployment verification — 2026-10-13

Executed on the Phase 12B working tree based on `336cc15` (Phase 12A merged),
using Java 21, Docker 28.4.0, and Compose 2.40.3 on Linux. No application source
or applied migration changed. The source-built image ran two real JVMs, pinned
PostgreSQL/Redis/Kafka images, and NGINX on the new internal service network.

| Final verification | Passed | Failed/errors | Skipped | Evidence |
| --- | ---: | ---: | ---: | --- |
| Maven `clean verify` | 251 | 0 | 0 | [Surefire count summary](../verification/results/2026-10-13-phase12b-maven.json) |
| Existing distributed suite | 13 | 0 | 0 | [Raw report](../verification/results/2026-10-13-phase12b-multi-instance.json) |
| New deployment/recovery runner | 8 | 0 | 0 | [Raw report](../verification/results/2026-10-13-phase12b-deployment.json) |
| Host Java/Compose smoke checks | 2 | 0 | 0 | [Raw report](../verification/results/2026-10-13-phase12b-host-smoke.json) |

Maven finished at `2026-10-13T17:58:08Z` with BUILD SUCCESS; all 13 Surefire XML
class reports were aggregated independently. The cloud command supplied existing
Maven settings/cache paths and disabled MockMvc request dumps. The Docker build
packages source without tests; its success is separate from the Maven result.

Reproduce the automated suites from the repository root:

```bash
./mvnw clean verify --batch-mode --no-transfer-progress
python3 verification/deployment.py --output /tmp/phase12b-deployment.json
python3 verification/multi_instance.py --output /tmp/phase12b-distributed.json
```

The deployment runner verified:

- Six healthy services, NGINX as the only published port, and internal networking.
- Empty-volume V1–V7 order and exactly one opt-in sample migration across two JVMs.
- Real registration/login; the same issued JWT worked on both JVMs. Missing or
  invalid tokens returned 401, USER metrics access 403, and foreign booking 404.
- Paid reservation confirmation, both-app and one-app restarts, and unchanged
  permanent booking/inventory/payment rows and Flyway history.
- PostgreSQL stop/start: readiness returned 503 during the outage, then both pools
  reconnected and existing bookings remained readable.
- Redis and Kafka pauses while explicitly restarting a backend. Readiness and
  new paid bookings still worked; Redis reads fell back to PostgreSQL and Kafka
  backlog/projections recovered after unpause.
- `down` without volume removal, recreation of all six containers on the retained
  volumes, unchanged Flyway history/bookings, and payment-key retry returning 200
  for the same booking. The private password, signing key, and JWT were absent
  from the recreated stack's logs.

The existing distributed suite again forced **100 database lock waiters** (50 per
JVM): one 201, ninety-nine 409s, one booking, and twenty successful confirmation
retries. Kafka membership had two JVMs per distinct notification/analytics group,
with all three partitions assigned; publisher death after acknowledgment recovered.
Backend fault checks observed one 504 among 20 reads, one 504 during explicit
payment attempts, and three 502s while restarting. NGINX made no automatic replay.
Killing a JVM before payment insertion returned 502 and left zero attempts;
explicit same-key retries returned 201 then 200. These are expected failure-boundary
observations, not a zero-error failover claim.

The additional host smoke used a fresh, separate `compose.yaml` project and the
Maven-built JAR on the host with private generated credentials and random ports.
PostgreSQL/Redis/Kafka and both readiness/messaging health passed. Without a dev
profile, V1–V7 initialized, `/api/shows` returned an empty list, users remained
empty, and no sample migration was applied. Its temporary script/logs were under
`/tmp`; the supported manual host commands remain in the README.

Compose syntax/config checks passed for both files, missing DB_PASSWORD/JWT_SECRET
produced helpful failures, `.env` remains ignored, and documentation links/Python
syntax/`git diff --check` passed. The runtime was inspected as UID 10001 with no
builder settings, custom CA, `.env`, or credential/proxy environment baked in.

Earlier attempts are **not** counted as passed: one deployment attempt stopped
before tests because an optional Maven settings mount left an empty cache file
(0 passed, 1 setup failure, 8 unrun). The mount was moved outside the cache. The
next attempt passed five cases before `compose up` tried to restart a paused
Redis dependency (1 failure, 2 unrun). The runner now uses the documented backend
`restart` command; the final fresh-project run passed all eight. This is a Compose
paused-container control limitation, documented with the `--no-deps` alternative.

A cold Docker build in this cloud environment needed its existing trusted proxy,
CA, and Maven settings via build-only inputs. The source image built successfully
with TLS verification intact, and subsequent standard Compose builds used the
warm dependency cache. This does not establish cold direct Internet access in
this restricted environment. No GitHub-hosted deployment, new benchmark,
multi-host failover, volume-loss recovery, or production hardening was tested.
All created test stacks/volumes were disposable and removed by their owners;
no developer data was deleted. See [DEPLOYMENT.md](DEPLOYMENT.md).

## Phase 12C regression verification — 2026-10-14

Before starting capacity measurements, Java 21 and the full Maven wrapper
`clean verify` ran on `d5d57ca` plus the Phase 12C benchmark-only working changes.
The build completed at `2026-10-14T03:04:18Z`: **251 passed, zero failures, zero
errors, zero skipped**, across 13 Surefire suites. PostgreSQL, Redis and Kafka
Testcontainers were available. The exact command, suite totals and XML report
hashes are retained in [regression.json](../performance/results/2026-10-14-phase12c/regression.json).
Cloud Maven settings and dependency-cache overrides were needed; no production
credentials were used. Regression testing and measured load did not overlap.

The separate capacity experiment is documented in [PERFORMANCE.md](PERFORMANCE.md#phase-12c--controlled-capacity-measurements).
Its load runs are not additional JUnit test methods. A missed latency, dropped-work
or event-delay target is a measured capacity failure; it is distinct from a
booking uniqueness or projection consistency failure. No application, schema,
transaction, authentication or deployment setting was changed for Phase 12C.

All **38 planned load runs completed**: 21 met their declared performance targets,
17 missed targets under ramp/overload, and zero were skipped. Post-load correctness
passed in all 38. Four 100-VU contention trials each retained one active hold;
17,646 other requests returned the expected 409. Both Kafka-pause recovery trials
committed 100 bookings during the pause and eventually projected every event.
Six overloaded write trials had 132 client payment timeouts; the resulting
committed bookings remained consistent. These are recorded performance failures,
not silently excluded observations or failing Maven test methods.

Two harness pilots preceded the retained matrix. The first exposed an incorrect
verification SQL join (`bookings.payment_id` did not exist); the invariant query
was corrected to follow `reservations.confirmed_payment_id`. The second pilot
passed. Neither pilot is included in the 38-run comparison. Final script hashes
are retained with the report, and the final analysis validates fault execution,
raw dropped-iteration counts and the complete matrix. The application was unchanged.
