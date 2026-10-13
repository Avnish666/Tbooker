# Catalogue performance evidence — Phase 9

## Reproduce the comparison

Requirements: Java 21, the Maven wrapper, Python 3, and Docker Engine. The runner
uses Linux Docker host networking for k6 and loopback ephemeral ports for private
PostgreSQL/Redis containers. It never reads `.env` or uses the developer database.
Run from the repository root, with no other load test/test suite running:

```bash
bash mvnw clean verify
python3 performance/run-comparison.py --output performance/results/my-comparison.json
```

In the cloud environment, first source `/workspace/.setup/env.sh` and use the
Maven settings/cache overrides documented in [TESTING.md](TESTING.md). The runner
requires the built JAR, not a second Maven installation. It refuses to overwrite
an existing result. Optional flags are `--jar`, `--vus`, `--duration`, `--warmup`.
The images are `postgres:17.11-bookworm`, `redis:7.4-alpine`, and `grafana/k6:1.6.0`;
exact measured digests are in the JSON artifact. Docker pulls missing images.

`performance/catalogue.js` can also run with standalone k6 on other operating
systems against a separately prepared 100-show development dataset:

```bash
BASE_URL=http://localhost:8080 VUS=10 DURATION=30s SHOW_COUNT=100 \
  k6 run performance/catalogue.js
```

This standalone invocation writes `summary.json`; keep generated raw output out
of commits unless intentionally publishing measured evidence. Only the runner
provisions isolated data and measures PostgreSQL/Redis counters.

## Workload and measurement method

The runner starts dedicated PostgreSQL and Redis containers, applies unchanged
Flyway migrations through the application with the dev profile, and populates
exactly 100 shows sharing one movie/screen. No booking writes run during the test.
It restarts the same JAR for each run with `-Xms256m -Xmx512m
-XX:ActiveProcessorCount=4`. The JVM setting controls runtime processor assumptions;
it is not an exclusive four-core CPU allocation. All processes share this cloud
machine; PostgreSQL/Redis/k6 have no CPU quota configured by the runner.

Runs use disabled–enabled–enabled–disabled (ABBA) order to make order effects
visible. Each starts with an empty Redis, warms for 10 seconds, then measures
30 seconds of `GET /api/shows` using 10 virtual users with no think time. The
baseline uses `CATALOGUE_CACHE_ENABLED=false` on the same Phase 9 JAR; all other
application settings/workload remain identical. This isolates the cache switch,
not every code change between older phases. The list TTL stays at its default
30 seconds, so the measured cached workload includes an expiry and cold refill.

k6 checks both HTTP 200 and the 100-element DTO payload; thresholds require zero
HTTP errors and all checks passing. Throughput and latency come from k6's
`http_reqs` / `http_req_duration`; latency excludes DNS/TCP setup and does not
include client-side JSON checks, though those affect closed-workload throughput.
The runner resets `pg_stat_statements` after warmup, then counts matching
`SELECT ... movie_shows ...` calls. This is catalogue SELECT volume, not all SQL
statements, transactions, or a query-plan analysis. It takes deltas of Redis
`keyspace_hits` / `keyspace_misses` on the otherwise unused dedicated Redis.
A disabled-cache baseline has no cache observations, so its ratio is N/A.
The warmup, startup migrations, readiness probes, and setup SQL are excluded.

Runtime-generated database/JWT secrets and raw logs stay in process environment
or a temporary directory; they are not written to the aggregate result. Containers
and temporary files are removed when the runner exits. Do not target production
with the standalone workload or reuse a shared database for benchmark setup.

## Executed results

On **2026-10-13**, all four runs completed successfully. Source evidence:
[`2026-10-13-catalogue.json`](../performance/results/2026-10-13-catalogue.json).
That artifact records the base commit plus uncommitted-work flag, JAR SHA-256,
Java/OS details, visible CPU count, container digests, workload, and measurements.
The cache implementation was uncommitted as requested. The Maven correctness
suite had completed before benchmarking; it did not run concurrently.

| Run | Cache | Requests | Requests/s | Average ms | p95 ms | HTTP errors | Cache hits / misses | Catalogue SELECTs |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Disabled | 56,665 | 1,888.57 | 4.072 | 8.588 | 0% | 0 / 0 | 56,665 |
| 2 | Enabled | 86,354 | 2,878.24 | 2.560 | 5.113 | 0% | 86,344 / 10 | 10 |
| 3 | Enabled | 88,363 | 2,944.81 | 2.509 | 5.083 | 0% | 88,353 / 10 | 10 |
| 4 | Disabled | 53,125 | 1,770.50 | 4.411 | 9.371 | 0% | 0 / 0 | 53,125 |

All response checks passed. Cached hit ratios were **99.9884%** and **99.9887%**.
Each cached run made ten database reads when its key expired; simultaneous misses
are not coalesced. The baseline executed one catalogue SELECT per request.

Across these observations, uncached throughput was **1,770–1,889 requests/s**
and cached throughput **2,878–2,945 requests/s**. Cached p95 was **5.08–5.11 ms**
versus **8.59–9.37 ms** uncached. These are run ranges, not pooled percentiles,
confidence intervals, or guaranteed production improvements.

### Calculated improvements

Using the arithmetic mean of the two run-level measurements in each configuration:

| Metric | Uncached run mean | Cached run mean | Measured change |
| --- | ---: | ---: | ---: |
| Throughput | 1,829.53 requests/s | 2,911.52 requests/s | **59.14% higher** |
| Average response time | 4.241 ms | 2.535 ms | **40.24% lower** |
| Per-run p95 latency | 8.979 ms | 5.098 ms | **43.23% lower** |

Throughput improvement is `(cached / uncached - 1) × 100`; latency reduction is
`(1 - cached / uncached) × 100`, using unrounded measurements. The p95 row is the
mean of two run-level p95 values, **not** a combined percentile of all requests.
Likewise, average latency here averages the two run means rather than pooling
individual observations. Raw per-run values remain available in the artifact.

Across two 30-second measured runs per configuration, catalogue SELECT calls fell
from **109,790 to 20**, a **99.9818% reduction**, while request counts increased
from **109,790 to 174,717**. Normalizing for completed requests, database reads fell
from **1.0 to 0.00011447 per request**: **99.9886% fewer SELECTs per request**.
The combined measured cache hit ratio was **99.9886%**. These counts cover the
catalogue SELECT only, not all PostgreSQL activity.

## Interpretation and limits

Redis reduced repeat SQL work for this hot, read-only catalogue list; the existing
entity graph already fetched the list in one query. Cache serialization still
costs CPU/memory, every hit has a network round trip, and the response remains
unpaged. There is no basis here for adding more indexes or caching inventory.

The experiment is short, single-machine, and single-application-instance. The
client, cache, database, and application compete for host resources. It measures
a warm hot key with 100 shows, not a realistic key distribution, cold startup,
large catalogues, mixed writes, remote-network latency, Redis outages under load,
or a saturation curve. A closed workload can hide queue growth/coordinated
omission under overload; these runs are not an open-loop capacity test. Two runs
per configuration and brief warmups do not establish statistical confidence,
long-term GC behavior, scalability, or an SLA. Further load scenarios belong to
later work. Functional outage and contention behavior is verified separately in
[TESTING.md](TESTING.md#phase-9-verification).


Phase 10 note: the comparison runner now explicitly disables Kafka workers in its
isolated catalogue benchmark. The results above remain the original Phase 9
measurements; they are not Kafka or Phase 10 throughput measurements.

## Phase 11: one versus two JVMs behind NGINX

Executed on 2026-10-13 with `performance/run-scaling.py` and `performance/scaling.js`.
**All 12 measured runs completed, with zero unexpected failures and no duplicate
bookings.** Raw k6 summaries, database counts, image/JAR hashes, JVM/pool snapshots,
and Docker resource samples are retained in
[2026-10-13-scaling.json](../performance/results/2026-10-13-scaling.json).
These are new Phase 11 measurements, not a rerun of the Phase 9 cache comparison.

```bash
bash mvnw clean verify
python3 performance/run-scaling.py --output /tmp/tbooker-scaling.json
```

### Equivalent workload and environment

For each workload the runner used ABBA order: **1, 2, 2, 1 JVMs**. Both setups used
the same NGINX, PostgreSQL, Redis, and Kafka containers on one Docker host (Docker
reported 5 CPUs and 35,743,784,960 bytes memory). Every run restarted the same app
image, reset the same disposable database/Redis fixtures, warmed catalogue reads
for 10 seconds, then measured for 30 seconds, with up to 30 seconds for in-flight
requests to finish. Fixture reset happened with apps stopped after projections
drained; normal graceful listener stop retained broker offsets. No development
volumes were used. Broker/cache/publisher/consumer workers remained enabled.

Each app had a 1-CPU quota, 768 MiB RAM limit, 128–384 MiB heap, and a 20-connection
pool. Two apps therefore have twice the application CPU/memory/pool budget;
shared service limits did not change. PostgreSQL had 1 CPU/512 MiB, Redis
0.5 CPU/128 MiB, Kafka 1 CPU/1 GiB, and NGINX 0.5 CPU/128 MiB. Verification settings
used 1-second expiry cleanup, 500-ms publication polling, and an 8-second lease
in both configurations (normal Compose defaults are 30s, 1s, and 30s).

- Catalogue: 20 closed-loop virtual users read the same 100-show DTO list.
- Mixed: 20 virtual users each issue eight catalogue reads, one unique-seat hold,
  and one UPI payment that must confirm a booking. A 12,000-seat fixture avoids
  intended conflicts. Signed JWTs are prepared beforehand; login/BCrypt is excluded.
- Contention: 100 virtual users repeatedly request one seat. Exactly one hold
  succeeds per run; remaining calls must conflict. No payments are sent here.
  This measures rejection throughput after the winner, not booking throughput.

### Measured comparison

RPS, p50, and p95 below are **arithmetic means of the two per-run measurements**
for each configuration. The latency figures are not pooled percentiles. Request,
conflict, and booking counts are sums over those two independent runs.

| Workload | JVMs | Requests/s | p50 ms | p95 ms | Unexpected error rate | Expected 409s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Catalogue | 1 | 531.83 | 15.78 | 89.98 | 0% | 0 |
| Catalogue | 2 | 894.57 | 13.23 | 61.72 | 0% | 0 |
| Mixed paid booking | 1 | 102.84 | 94.23 | 956.46 | 0% | 0 |
| Mixed paid booking | 2 | 202.41 | 45.86 | 442.61 | 0% | 0 |
| Hot-seat contention | 1 | 115.48 | 660.58 | 2038.04 | 0% | 7,003 |
| Hot-seat contention | 2 | 178.10 | 490.04 | 1231.25 | 0% | 10,770 |

| Two JVMs versus one | Throughput change | p50 reduction | p95 reduction |
| --- | ---: | ---: | ---: |
| Catalogue | +68.21% | 16.19% | 31.41% |
| Mixed paid booking | +96.83% | 51.34% | 53.72% |
| Hot-seat contention | +54.23% | 25.82% | 39.59% |

Throughput change is `(two / one - 1) × 100`; latency reduction is
`(1 - two / one) × 100`. These are observed differences, not statistical
confidence intervals or guaranteed scaling factors.

### Resources and database observations

CPU is the mean of Docker samples, with 100% representing one logical core.
App memory is the largest sampled sum of container memory across active replicas;
it is not heap-only and not a continuous peak. Pool numbers are the maximum
sampled sums of per-JVM gauges (total / active / pending), collected sequentially.
The hard pool budget is 20 versus 40; snapshots can miss brief bursts.

| Workload | JVMs | App CPU % | App memory MiB | PostgreSQL CPU % | Pool total / active / pending | DB lock waiters |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| Catalogue | 1 | 96.2 | 367.4 | 1.5 | 7 / 1 / 0 | 0 |
| Catalogue | 2 | 202.4 | 754.5 | 1.2 | 16 / 4 / 0 | 0 |
| Mixed paid booking | 1 | 94.5 | 400.3 | 14.8 | 20 / 16 / 0 | 0 |
| Mixed paid booking | 2 | 178.9 | 788.6 | 24.6 | 29 / 12 / 0 | 1 |
| Hot-seat contention | 1 | 95.3 | 428.2 | 12.2 | 20 / 20 / 84 | 19 |
| Hot-seat contention | 2 | 182.4 | 814.8 | 17.0 | 40 / 40 / 63 | 39 |

All requested JVM heap/GC, pool, outbox, and native Kafka lag snapshots were
available; no sampling errors were recorded. Raw evidence also includes CPU/RAM
for Redis, Kafka, and NGINX. Shared outbox gauges describe the same database rows
and must not be summed across replicas. Native consumer lag is not end-to-end
projection delay.

| Workload | JVMs | HTTP requests | Confirmed bookings | SQL calls | SQL calls / HTTP request |
| --- | ---: | ---: | ---: | ---: | ---: |
| Catalogue | 1 | 31,920 | 0 | 579 | 0.0181 |
| Catalogue | 2 | 53,743 | 0 | 1,079 | 0.0201 |
| Mixed paid booking | 1 | 6,360 | 636 | 45,150 | 7.0991 |
| Mixed paid booking | 2 | 12,330 | 1,233 | 87,462 | 7.0934 |
| Hot-seat contention | 1 | 7,005 | 0 | 63,519 | 9.0677 |
| Hot-seat contention | 2 | 10,772 | 0 | 97,988 | 9.0965 |

SQL calls come from `pg_stat_statements` after resetting statistics before each
measured run. They include transactions, worker polling, readiness and monitoring
SQL; they are **not catalogue SELECT counts** and cannot be compared directly
with the Phase 9 table. More replicas also poll shared state more often. Adding
replicas is not a database-query reduction mechanism.

### Interpretation and limits

Application CPU samples approach the configured per-replica quotas. Adding a
second quota improved the measured workloads, while increasing memory, connection,
and shared-service usage. Hot-seat requests still serialize on one inventory row;
extra processes cannot create additional valid owners. Expected 409s are excluded
from unexpected failures and should not be presented as successful bookings.

These short local closed-loop runs do not establish production capacity, an SLA,
linear scaling, or multi-host availability. The load generator shares the host;
monitoring and health checks add overhead. Only catalogue reads warm up; payment
classes/JIT and broker/cache state are not fully steady-state. ABBA reduces simple
ordering bias but two repetitions cannot establish statistical significance.
Percentiles combine request types within each workload, not per-route service
levels. Closed-loop load can hide queue growth under overload. Samples are coarse,
GC observations cover snapshot intervals, and the test does not isolate Kafka
capacity or remote-network effects. No outage was injected during these timed
runs; failure correctness was tested separately in [TESTING.md](TESTING.md).
