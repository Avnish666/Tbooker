# Integration and security verification

Phase 4 changed tests and documentation only. Phase 6 adds authentication,
authorization, owner-scoped booking reads, and V4 while preserving the booking
algorithm. Historical Phase 4/5 results appear below, followed by the current run.

## Implementation review

Phase 6 first authenticates a real signed JWT and derives the user ID from its
subject. Request bodies cannot select the owner; existing transaction checks
then apply unchanged.

The supported booking path has these safeguards:

1. Resource lookups validate the user, show, and show-seat association. They do
   not authorize booking based on a previously read availability value.
2. The conditional PostgreSQL update includes the inventory ID, show ID, and
   `status = 'AVAILABLE'`. Only one affected row authorizes the insert.
3. `BookingService.book` is invoked through Spring's transaction proxy at
   `READ COMMITTED`. The row lock is held through the insert and commit; runtime
   failures roll back the seat update and booking together.
4. The native update clears the persistence context, avoiding stale managed
   availability. `saveAndFlush` checks insert constraints before returning.
5. `uq_bookings_show_seat` independently forbids a second booking for that
   inventory row. Only that named unique violation maps to `409`; unrelated
   integrity failures remain server errors.

No double-booking defect was found in this path. The tests exercise these
guarantees against PostgreSQL rather than inferring them from annotations or mocks.

## Twenty-request test

`BookingConcurrencyIntegrationTests` uses JUnit 5, Spring Boot Test with a random
HTTP port, and a dedicated Testcontainers PostgreSQL 17 instance. It performs
three repetitions, each with a fresh available seat and 20 distinct users.

For each repetition:

- A fixed `ExecutorService` has 20 workers. A ready `CountDownLatch` ensures all
  workers have started, and a start latch releases their HTTP requests together.
- A coordinating JDBC transaction temporarily locks the target row. The test
  polls `pg_stat_activity` until all 20 conditional updates are blocked, proving
  that every request reached PostgreSQL before a winner is allowed to commit.
- The lock is released, all futures are collected, and every response is checked.
- Exactly one request must return `201`. All other 19 responses must be `409`
  Problem Details for the unavailable seat, rather than unrelated server errors
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
return the expected generic `500`, leave zero bookings and an `AVAILABLE` seat,
and allow a successful retry after removing the trigger. These expected HTTP
errors are assertions in passing tests, not ignored test failures.

Other existing checks cover rollback after a unique-constraint failure and a
waiting request proceeding when a competing transaction rolls back.

Migration repeatability is checked through `Flyway.migrate()`, not by manually
rerunning versioned SQL. Both the default and development-profile contexts call
it three additional times. Every call must execute zero migrations, validate
successfully, have no pending migrations, and preserve the complete schema
history. The development check also snapshots all eight application tables
after confirming a booking and requires every row to remain unchanged. Existing
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
- POST retries are not idempotent. If the commit succeeds but the response is
  lost, retrying returns `409` rather than recovering the original result.
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

The remaining limitations are explicitly deferred beyond Phase 6.
