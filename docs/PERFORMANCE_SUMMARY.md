# Performance evidence for a portfolio review

These are **local measurements**, not production capacity guarantees. Phase 12C
is the controlled one-versus-two-instance experiment; the earlier Phase 9 test
is the separate cache enabled/disabled comparison. They use different application
versions, resources and workloads and must not be combined into one speedup claim.
The full [performance report](PERFORMANCE.md) retains every result, including failures.

## Phase 12C environment and workload

Executed 2026-10-14 on a shared Linux x86_64 cloud host: five visible logical CPUs
(AMD EPYC 9V74), 33.29 GiB RAM, Docker 28.4.0. NGINX, applications, infrastructure
and the k6 1.6.0 generator shared the machine. Each JVM had one CPU, 768 MiB
container memory, 128–384 MiB heap and 20 database connections. PostgreSQL had
one CPU/512 MiB; Redis 0.5 CPU/128 MiB; Kafka one CPU/1 GiB; NGINX 0.5 CPU/128 MiB;
k6 one CPU/768 MiB. Two JVMs doubled application resources and connection budget.

Fixture: 10 movies, three theatres/screens, 100 shows, 80 physical seats per
screen, 8,000 show-seat rows and 8,000 users. Every run used real JWT validation;
tokens were pre-issued, so login/BCrypt throughput was **not measured**. Normal
cache TTLs and background workers remained enabled. Each open-arrival run used
10 seconds warmup, 30 seconds measurement and a grace period. Middle-rate repeats
used one/two/two/one JVM order; there were two repetitions per configuration.

| Workload | Offered iterations/s | HTTP requests per iteration | Meaning |
| --- | --- | ---: | --- |
| A: catalogue | 200, 600, 1,200 | 1 | Public metadata reads; cache enabled |
| B: availability | 50, 150, 400 | 1 | Live 80-seat response, never cached |
| C: contention | 100 closed-loop VUs | 1 | One hot seat; one hold succeeds, further requests conflict |
| D: mixed | 5, 15, 40 | 9 | Four metadata reads, two availability reads, hold, mock payment, confirmation retry |
| E: event pipeline | 10, 30, 60 | 3 | Hold, payment, confirmation retry; observe outbox and both projections |

Two extra recovery trials paused Kafka during the event workload. HTTP 409 is an
expected result **only** for contention. Unexpected errors, dropped arrivals,
contract checks and asynchronous latency all affect the predeclared targets.
See the [plan and runner](../performance/phase12c/README.md) for exact thresholds.

## Matched one-versus-two-instance results

Arithmetic means of two per-run measurements at each workload's middle rate.
p95 values are means of per-run percentiles, **not pooled percentiles**. Dropped
iterations are summed. A fixed offered rate caps throughput.

| Workload | JVMs | HTTP requests/s | HTTP p95 ms | Unexpected errors | Dropped iterations | Target passes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Catalogue, 600 iter/s | 1 | 596.98 | 122.98 | 0% | 171 | 0/2 |
| Catalogue, 600 iter/s | 2 | 599.61 | 50.57 | 0% | 21 | 0/2 |
| Availability, 150 iter/s | 1 | 150.01 | 20.30 | 0% | 0 | 2/2 |
| Availability, 150 iter/s | 2 | 150.01 | 11.49 | 0% | 0 | 2/2 |
| Contention, 100 VUs | 1 | 127.90 | 1,667.48 | 0% | 0 | 2/2 |
| Contention, 100 VUs | 2 | 163.16 | 1,172.78 | 0% | 0 | 2/2 |
| Mixed, 15 iter/s | 1 | 111.56 | 1,857.50 | 0% | 86 | 0/2 |
| Mixed, 15 iter/s | 2 | 134.68 | 158.42 | 0% | 0 | 2/2 |
| Events, 30 iter/s | 1 | 60.63 | 6,471.99 | 0.5808% | 406 | 0/2 |
| Events, 30 iter/s | 2 | 89.52 | 549.65 | 0% | 4 | 1/2 |

The mixed workload measured **20.72% higher HTTP throughput and 91.47% lower p95**
with two JVMs, using twice the application CPU/memory/pool budget. Compute
throughput change as `(two / one - 1) × 100` and p95 reduction as
`(1 - two / one) × 100`, from unrounded measurements. Contention requests mostly
reject work: its RPS is **not successful booking throughput**. There was one
successful hold per contention trial, not hundreds of bookings per second.

Across all **38 runs**, **21 met targets and 17 missed**, with no skipped runs.
The retained report contains 234,579 measured HTTP requests, 9,641 committed
measured bookings, 17,646 expected conflicts and 35,993 dropped arrival iterations.
Six overload trials recorded 132 client-side payment timeouts. A client timeout
does not prove the transaction failed; persisted state/idempotent retry resolves
that ambiguity. See [all per-run tables](../performance/results/2026-10-14-phase12c/tables.md).

## Redis comparison: separate Phase 9 experiment

Executed 2026-10-13: one host JVM, 256–512 MiB heap, four visible JVM processors;
10 closed-loop k6 VUs continuously requested `GET /api/shows` with 100 shows.
Runs used 10 seconds warmup, 30 seconds measurement, application restarts between
runs, and disabled/enabled/enabled/disabled cache order. No NGINX/two-JVM comparison
was involved. Values below average the two run-level measurements per mode.

| Metric | Cache disabled | Cache enabled | Observed change |
| --- | ---: | ---: | ---: |
| HTTP requests/s | 1,829.53 | 2,911.52 | +59.14% |
| Mean per-run p95 | 8.979 ms | 5.098 ms | −43.23% |
| HTTP error rate, every run | 0% | 0% | No observed errors |
| Catalogue SELECT calls, both runs combined | 109,790 | 20 | −99.9818% |
| SELECTs per completed request | 1.0 | 0.00011447 | −99.9886% |

[Raw Phase 9 results](../performance/results/2026-10-13-catalogue.json) include the
final uncached run, image/JAR hashes and the uncommitted-source flag. Query counts
cover that catalogue SELECT, not total database work. Ten simultaneous misses at
TTL expiry each queried PostgreSQL; there is no miss coalescing. These historical
cache figures are **not a Phase 12C cache-on/cache-off test**, which was not run.

## Consistency and bottlenecks

Post-load invariants passed after **all 38 Phase 12C trials**: no duplicate holds
or bookings, inventory/booking agreement, verified payment for new bookings, one
outbox fact per booking, one notification, two processed-event markers, and the
expected analytics count after drain. Both Kafka-pause trials recovered. This
proves the checked invariants for these executions, not every possible crash or
long-term durability scenario. [Validation evidence](../performance/results/2026-10-14-phase12c/validation.json)
is separate from JUnit counts and performance target passes.

- **Application CPU and queueing:** at 1,200 offered catalogue requests/s, two
  JVMs were worse than one: 570.69 vs 731.06 HTTP/s, p95 1,467.16 vs 510.51 ms.
  Redis server hit ratios exceeded 99.8%, yet cache-client deadlines expired,
  causing PostgreSQL fallback. Hit ratio alone does not measure client success.
- **Database connection pressure:** mixed 40 iter/s reached sampled active/pending
  pools of 20/176 for one JVM and 38/123 across two. Adding pool slots alone can
  move the queue into PostgreSQL rather than fix the bottleneck.
- **Hot-seat serialization:** contention waits on the one inventory row; more
  JVMs cannot create multiple valid owners for it. Correct conflicts are useful
  work but not additional inventory capacity.
- **Asynchronous backlog:** one JVM at 30 event workflows/s had mean HTTP p95
  6,471.99 ms and substantial publication/projection delays. Finishing HTTP calls
  alone does not prove downstream consumers keep up.

CPU observations are sampled; 100% means one logical core. SQL counts in Phase 12C
include background/observation work and have a wider observation window than the
30-second HTTP interval. Projection timestamps are written **inside** their DB
transactions, before commit; they are not pure listener CPU time. No profiler
was used to identify a specific hot method.

## Reproduce and inspect

The [manifest](../performance/results/2026-10-14-phase12c/manifest.json),
[aggregate report](../performance/results/2026-10-14-phase12c/report.json), raw k6
streams and [analysis](../performance/results/2026-10-14-phase12c/analysis.json)
preserve source and image hashes. Phase 12C measurements predate Phase 12D's
connection/statement/lock timeout and logging changes. Re-running current `main`
is a **new experiment**, not an exact replay of the old application configuration.
For historical source use the Phase 12C merge `7423ae678e0b37ea4b7dc904e279841340e0c8b1`
in a separate clone, preserving your working checkout; compare manifest hashes.

```bash
# Java 21, Docker/Compose, Python 3; matplotlib is needed only for charts.
./mvnw clean verify
# Choose a NEW output directory, never overwrite retained evidence.
python3 performance/phase12c/run.py --output /tmp/tbooker-capacity-new
python3 performance/phase12c/analyse.py --require-complete /tmp/tbooker-capacity-new
# Add --no-charts to analysis if matplotlib is unavailable.
```

The runner owns a fresh UUID Compose project and deletes only its disposable
fixtures/volumes. Never point its reset SQL at a developer or shared database.
[Phase 9 reproduction](PERFORMANCE.md#reproduce-the-comparison) uses its own runner.

Short shared-host trials, two middle-rate repetitions, login exclusion, possible
load-generator scheduling pressure, and resource doubling limit generalization.
There are no confidence intervals, sustained maximum-capacity measurements,
multi-host results or production traffic claims. Future work should measure longer
soak runs, separate load generation, admission control and cache miss coalescing.
