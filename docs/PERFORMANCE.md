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
