# Operational runbook — Phase 12D

These commands operate the local, single-host Compose deployment. Run from the
repository root. Docker and database administrators are trusted. This is not a
production HA, backup, incident-response, or payment-refund system. Use the same
Compose project name and private `.env` on restart. Never run fault-injection
tests against a developer or production database.

## Start, health, and shutdown

Follow [DEPLOYMENT.md](DEPLOYMENT.md) for Docker resources, environment setup and
migration safety. Copy `.env.example` only if `.env` does not already exist, set
private `DB_PASSWORD` and `JWT_SECRET`, and use `dev` only for a sample database.

```bash
docker compose -f compose.scaled.yaml config --quiet
docker compose -f compose.scaled.yaml up -d --build --wait --wait-timeout 240
docker compose -f compose.scaled.yaml ps
curl --fail http://localhost:8080/nginx-health
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
curl --fail http://localhost:8080/actuator/health/cache
curl --fail http://localhost:8080/actuator/health/messaging
```

Use your `LB_PORT` if changed. `/nginx-health` checks the proxy only; liveness
checks the process. Readiness requires PostgreSQL, not Redis/Kafka. The cache and
messaging groups report optional-service availability separately. Root health
can be DOWN during an optional-service outage while readiness remains UP. Health
responses intentionally hide credentials, addresses and exception details.
Load-balanced health reaches one replica; also inspect each container's health:

```bash
docker compose -f compose.scaled.yaml exec -T app-a wget -Y off -qO- http://localhost:8080/actuator/health/readiness
docker compose -f compose.scaled.yaml exec -T app-b wget -Y off -qO- http://localhost:8080/actuator/health/readiness
docker compose -f compose.scaled.yaml stop
# Alternatively remove containers/networks, retaining PostgreSQL/Kafka data:
docker compose -f compose.scaled.yaml down --timeout 35
```

Do not add `--volumes`, run Flyway clean, or change project names as a repair.
Graceful shutdown allows active work to finish, but forced termination can still
produce an ambiguous HTTP outcome. Named volumes are not backups.

## Logs and request correlation

```bash
docker compose -f compose.scaled.yaml logs --since 10m --tail 200 --no-color app-a app-b nginx
# Application ECS JSON, omitting Compose prefixes; jq is an optional host tool:
docker compose -f compose.scaled.yaml logs --since 10m --no-color --no-log-prefix app-a app-b |
  jq -R 'fromjson? | select(.log.level == "WARN" or .log.level == "ERROR")'
```

Keep the response `X-Request-ID` when reporting an error. NGINX generates and
overwrites this ID; it appears as `correlationId` in its JSON access log, request
logs and application Problem Details. Use `jq ... select(.correlationId == "ID")`
to locate it. NGINX-generated errors have the header but may have HTML bodies.
App IDs are diagnostic, not trusted identity, authorization or payment keys.
MDC is cleared when the request exits; asynchronous workers use durable event
IDs and database state instead of claiming HTTP-to-Kafka distributed tracing.

Expected reservation conflicts are counted, not logged with stack traces.
Cache warnings are limited to one per 30 seconds per JVM. Database request,
payment-confirmation and scheduled-task warnings have separate 30-second limits;
their counters still include every observed failure. Suppressed warnings are not
evidence of recovery. Application summaries log exception classes, not raw SQL,
messages, failed rows, tokens or request bodies. Hibernate's raw SQL-error logger
is disabled. Third-party infrastructure logs still require restricted access and
review before sharing. Do not enable SQL/HTTP wire debug, paste `.env`, resolved
Compose configuration, container environment dumps, DLT payloads or bearer tokens.
Never put credentials in URL paths; the edge records paths but omits query strings.

## Metrics and interpreting them

Only an ADMIN JWT can read `/actuator/metrics`. Provision an operator through the
controlled procedure in [SECURITY.md](SECURITY.md), then log in. Do not paste a
token into shell history or enable shell tracing. For an interactive Bash shell:

```bash
read -rsp 'ADMIN JWT: ' TBOOKER_ADMIN_JWT; echo
printf 'header = "Authorization: Bearer %s"\n' "$TBOOKER_ADMIN_JWT" |
  curl --fail --silent --show-error --config - http://localhost:8080/actuator/metrics
unset TBOOKER_ADMIN_JWT
```

Append a metric name to that URL to inspect measurements/tags. Each response
describes one JVM. Collect each replica through a trusted internal collector or
inspect the `instance` tag; never subtract two random load-balanced samples as
if they came from the same counter. No exporter/history store/alert manager is
installed. Counters reset on restart; some meters exist only after activity.

| Signal | Actuator meter / command | Interpretation |
| --- | --- | --- |
| HTTP throughput/error rate | `http.server.requests`, COUNT by `status`, `uri`, `method`, `instance` | Same-instance count delta / seconds; divide 5xx count delta by all requests. Report expected 409s separately. |
| HTTP p95 | `http.server.requests.percentile`, `phi=0.95` | Local time-window percentile per method/route/status/instance; not a cluster percentile. Do not average p95 values. |
| JVM heap | `jvm.memory.used`, `jvm.memory.max`, `area=heap` | Sum matching heap pools within an instance. Container RSS also includes native/non-heap memory. |
| GC | `jvm.gc.pause` | Pause count, total/max seconds; interpret with heap pressure. |
| Pool | `hikaricp.connections.active`, `.idle`, `.pending`, `.max`, `hikaricp.connections.timeout`, `.acquire` | Pending plus acquisition timeouts indicate contention/exhaustion. |
| Hold conflicts | `reservations.conflicts` | HTTP domain conflicts, including expiry/availability and unique active-hold violations; not just double-book attempts. |
| Payment failures | `payments.conflicts`, `payments.verification.failures`, `payments.confirmation.reconciliation` | Attempts observed; reconciliation counter can include repeat observations, not distinct payments. |
| Operational failures | `tbooker.operation.failures`, `operation` tag | DATABASE_REQUEST, PAYMENT_CONFIRMATION or SCHEDULED_TASK. Check persisted outcome before retry. |
| Redis | `/actuator/health/cache`, `catalogue.cache.errors` by cache/operation | Cache errors trigger database fallback; health is a current probe, not historical availability. |
| Outbox | `booking.outbox.pending`, `booking.outbox.publish.failures`, `.state.failures`, `.publish.delay` | Pending is a sampled shared-DB gauge; do not sum replicas. It can be stale when DB/scheduling fails. |
| Consumers | `booking.consumer.failures`, `booking.consumer.dead.letters`; native `kafka.consumer.*` meters | Retry/DLT activity; native fetch lag is not completed-projection lag. Use group offsets below for broker backlog. |

`docker stats --no-stream` gives current container CPU/RSS. See [PERFORMANCE.md](PERFORMANCE.md)
for the Phase 12C evidence; new timeout defaults are not a new capacity benchmark.

## Database, pool exhaustion and slow locks

The scaled default is 20 connections per JVM (40 total), plus administrator and
startup connections, against PostgreSQL's 160-connection limit. Adding instances
requires a new budget. Increasing pool sizes without identifying pressure can
make lock contention and database overload worse.

```bash
docker compose -f compose.scaled.yaml exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose -f compose.scaled.yaml exec -T postgres sh -c 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
SET default_transaction_read_only = on;
SET statement_timeout = '5s';
SELECT application_name, state, wait_event_type, wait_event, count(*)
FROM pg_stat_activity WHERE datname=current_database()
GROUP BY application_name, state, wait_event_type, wait_event ORDER BY 1,2;
SELECT pid, application_name, clock_timestamp()-xact_start AS transaction_age,
       wait_event_type, wait_event, pg_blocking_pids(pid) AS blockers
FROM pg_stat_activity WHERE datname=current_database() AND xact_start IS NOT NULL
ORDER BY xact_start;
SELECT installed_rank, version, description, success FROM public.flyway_schema_history ORDER BY installed_rank;
SQL
```

These queries omit SQL text/row values. Investigate long/idle transactions and
connection leaks before terminating anything; killing a session can interrupt
legitimate work. No automatic session termination is provided.

Pool acquisition defaults to 3000 ms (`DB_POOL_TIMEOUT_MS`), PostgreSQL lock waits
to 5 seconds (`DB_LOCK_TIMEOUT`) and statements to 15 seconds
(`DB_STATEMENT_TIMEOUT`). JDBC connection/socket/cancel waits are 3/20/2 seconds.
They bound individual operations, not the entire HTTP transaction. Lock/statement
failure aborts and rolls back that transaction. A network failure during commit
may have an unknown outcome. API database resource/timeout failures return generic
503; other database failures return generic 500. Neither is proof that a preceding
payment/booking transaction did not commit. No automatic write replay was added.

PostgreSQL limits also apply to Flyway connections. Before a large migration,
quiesce both writers, verify a backup and choose justified startup limits for both
instances. Restore runtime limits afterwards. Do not edit old migrations or raise
limits to hide an unexplained blocker. There is no general rollback migration.

## Redis outage

```bash
docker compose -f compose.scaled.yaml exec -T redis redis-cli ping
docker compose -f compose.scaled.yaml logs --since 10m --tail 100 redis
curl --fail http://localhost:8080/actuator/health/cache
curl --fail http://localhost:8080/actuator/health/readiness
```

Redis is disposable catalogue metadata. A cache failure should produce failed
cache health, increment `catalogue.cache.errors`, and fall back to PostgreSQL.
Reservations, payments and bookings never use cached availability. Monitor DB
load: the fallback is correct but can overload the single writer. Cache stampedes
and global request admission control are not solved. Do not clear a database or
change booking state to repair a cache. `CATALOGUE_CACHE_ENABLED=false` can disable
caching on an explicitly recreated backend while diagnosing an ongoing outage;
the health probe still reports Redis independently.

## Kafka, consumer groups and outbox

Default names below match the repository; substitute intentional topic/group
overrides. Both JVMs should participate in **each** of two separate groups.

```bash
docker compose -f compose.scaled.yaml exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:19092
docker compose -f compose.scaled.yaml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group tbooker-notifications-v1
docker compose -f compose.scaled.yaml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group tbooker-analytics-v1
docker compose -f compose.scaled.yaml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group tbooker-notifications-v1 --members --verbose
docker compose -f compose.scaled.yaml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group tbooker-analytics-v1 --members --verbose
docker compose -f compose.scaled.yaml exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:19092 --topic tbooker.booking-confirmed.v1.DLT --time -1
```

`LAG` is broker end offset minus committed group offset, per partition. It is not
the number of distinct unfinished bookings. DLT end-offset growth plus the DLT
counter shows activity; retained offsets are not exact retained record counts.
Three partitions bound consumers that can actively process each group. Check
group membership, broker availability and DB failures before resetting offsets.
The application intentionally has no automatic DLT consumer or replay endpoint.

```bash
docker compose -f compose.scaled.yaml exec -T postgres sh -c 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
SET default_transaction_read_only = on;
SET statement_timeout = '5s';
SELECT status, count(*), min(created_at) AS oldest FROM tbooker.outbox_events GROUP BY status;
SELECT event_id, aggregate_id, status, attempts, next_attempt_at, lease_until, last_error
FROM tbooker.outbox_events WHERE status<>'PUBLISHED' ORDER BY created_at LIMIT 20;
SELECT consumer_name, count(*) FROM tbooker.processed_events GROUP BY consumer_name;
SQL
```

Pending/backoff events should progress after Kafka returns. An IN_FLIGHT event
with an expired lease is reclaimable by either JVM; a fenced update prevents an
old worker from overwriting a newer lease. Publisher death after broker ack may
deliver again. Consumer markers and projection changes commit together before
the offset, so duplicates do not duplicate a notification/analytics update.
DLT publication failure must leave the source offset uncommitted.

Read [MESSAGING.md](MESSAGING.md) before controlled replay. Fix the cause, retain
the **original event ID**, inspect restricted diagnostics, and follow the existing
requeue procedure. Never delete consumer markers, invent replacement event IDs,
blanket reset offsets or delete Kafka volumes to make lag disappear. Source/DLT
retention is seven days; prolonged outages require reconciliation against durable
outbox/booking facts. No real email side effect or exactly-once transport is claimed.

## Failed booking or payment

1. Keep the HTTP status, correlation ID, reservation ID and original payment key
   privately. Do not include tokens, passwords or real financial details in logs.
2. Read `/api/reservations/{id}`, `/api/payments/{id}` and
   `/api/reservations/{id}/booking` with the owner's token. ADMIN has no ownership
   bypass. A 409 can be expected contention, expiry or missing verified payment.
3. A SUCCESS payment with PENDING confirmation may safely retry the **same** payment
   key or the same reservation confirmation. Financial success commits separately;
   an allocation rollback must not erase it. Do not start a new payment key to
   recover an ambiguous payment response.
4. RECONCILIATION_REQUIRED means payment succeeded but allocation could not be
   confirmed (for example, an expired/replaced hold). Do not steal a later hold or
   manually mark a seat BOOKED. Refunds and operator reconciliation tooling are
   not implemented. Use the persisted state for investigation, not a fabricated
   successful callback. The public API has no payment-result callback endpoint.

For a trusted database operator, these read-only summaries avoid payment payloads:

```bash
docker compose -f compose.scaled.yaml exec -T postgres sh -c 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
SET default_transaction_read_only = on;
SET statement_timeout = '5s';
SELECT status, confirmation_status, count(*) FROM tbooker.payments GROUP BY status, confirmation_status;
SELECT id, reservation_id, status, confirmation_status, created_at FROM tbooker.payments
WHERE status='SUCCESS' AND confirmation_status<>'CONFIRMED' ORDER BY created_at LIMIT 20;
SQL
```

## Backend/startup recovery

```bash
docker compose -f compose.scaled.yaml restart app-b
docker compose -f compose.scaled.yaml ps
# After an intentional image/config change, recreate only the backend:
docker compose -f compose.scaled.yaml up -d --build --no-deps --wait --wait-timeout 240 app-b
```

Use compatible images/profiles across replicas; do not roll a schema-breaking
change this way. When Redis/Kafka is paused, `up` without `--no-deps` may attempt
to reconcile the paused dependency and fail; explicit backend `restart` remains
supported. Do not restart both healthy backends merely to treat a Kafka outage.

For startup failure, check required environment validation, DB readiness,
Flyway history/timeout/checksum and container resources. Changing DB_PASSWORD in
`.env` does not rotate a role in an existing PostgreSQL volume. Restore correct
credentials or perform an authorized database rotation; never delete the volume.
Missing readiness can be a schema/DB problem even if `/api/v1/health` says UP.
NGINX detects transport failures passively: it may return 502/504 before excluding
a failed peer and does not actively remove a reachable-but-unready peer. It never
automatically replays requests. Expired holds remain ineligible after restart
because persisted expiry is checked under a database row lock; cleanup timing is
not the source of correctness.

## Verification commands

```bash
./mvnw clean verify
python3 verification/deployment.py --output /tmp/tbooker-deployment-new.json
python3 verification/multi_instance.py --output /tmp/tbooker-distributed-new.json
```

Java 21, Docker/Testcontainers, Compose and Python 3 are required. Choose unused
output paths. The runners create their own credentials/projects/volumes, inject
faults only there, and remove only their owned disposable volumes. They do not
attach to the operator's stack. See [TESTING.md](TESTING.md) for executed results
and [SECURITY.md](SECURITY.md) for remaining production limitations.
