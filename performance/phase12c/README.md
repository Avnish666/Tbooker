# Phase 12C controlled capacity experiment

Run from the repository root after Maven verification, with no other load test
or regression suite running. Docker/Compose, Python 3, and sufficient disk space
for compressed raw k6 output are required. Chart generation additionally uses
matplotlib. The source image and service versions come from Phase 12B Compose;
k6 1.6.0 is pinned by digest in `run.py`. No application setting/source is changed.

```bash
./mvnw clean verify
python3 performance/phase12c/run.py --output performance/results/my-phase12c
# Resume only completed jobs under the same plan and script hashes:
python3 performance/phase12c/run.py --output performance/results/my-phase12c --resume
python3 performance/phase12c/analyse.py performance/results/my-phase12c
```

Use `analyse.py --require-complete DIRECTORY` for final publication; it rejects an
unfinished matrix. `--no-charts` writes just the tables/JSON for an interim review.
`--plan PATH` on the runner supports a separate rate matrix; preserve the original
plan/results and use a new output directory. HTTP thresholds and the contention
VU count are also fixed in `workloads.js`; changing those requires a reviewed
script change, not only editing the JSON plan.

Each session creates a new UUID-named Compose project with private runtime JWT
keys/passwords and named volumes, using `verification/stack.py`. It never reads
the developer `.env`. Only runner-owned resources are stopped, reset, paused,
and removed. `--only JOB_ID` selects a pilot or missing job. Existing evidence is
never overwritten; partial-job directories require a new output directory.
Completed jobs can be reused across sessions only with identical plan/script hashes.

For an overloaded catalogue trial, capture optional read-only cache diagnostics
**before** the runner removes its containers. Find its UUID project with
`docker compose ls`, then pass that project explicitly:

```bash
python3 performance/phase12c/cache-diagnostics.py performance/results/my-phase12c \
  --project tb11-REPLACE_WITH_RUNNER_HEX_ID
```

This saves only aggregate `CatalogueCacheErrors` counts in completed measurement
windows, never log lines or tokens. Redis server hits can include commands whose
client timed out, so server hit ratio alone does not prove successful cache reads.
The diagnostic report lists which runs were observed; missing runs are unknown.
It is not required to run the workload. Keep local console logs ignored as required
by project conventions; compressed metric output and JSON evidence are shareable.

## Predeclared plan and acceptance targets

`plan.json` is defined before measurements and retained with every report. The
matrix has 38 jobs: increasing rates for catalogue, availability, mixed traffic,
and event generation; ABBA (1,2,2,1 replicas) at each middle rate; four 100-VU
contention runs; and one Kafka-pause recovery run per replica configuration.
Each run has a 10-second warmup and 30-second measured window, plus up to ten
seconds for requests to finish. Targets apply to the measured window only.

| Workload | Offered iterations/s | p95 HTTP target | p99 HTTP target |
| --- | --- | --- | --- |
| A catalogue | 200, 600, 1200 | 100 ms | 250 ms |
| B seat availability | 50, 150, 400 | 250 ms | 500 ms |
| C one-seat contention | 100 closed-loop VUs | 2500 ms | 5000 ms |
| D mixed | 5, 15, 40 | 1000 ms | 2000 ms |
| E booking events | 10, 30, 60 | 1000 ms | 2000 ms |

All normal acceptance checks also require <0.1% unexpected HTTP errors, zero
fixture exhaustion, every response-contract check passing, and zero dropped
open-arrival iterations. Event-generating runs additionally require publication
p95 <=2 seconds, each projection's end-to-end timestamp p95 <=2.5 seconds, and
at most 40 unpublished events per replica at load completion. The Kafka outage
runs deliberately exceed normal event delay targets; they require eventual full
drain and consistency within 120 seconds of the post-load drain check.

These are local experiment targets, not business SLAs. A 30-second passing run
establishes only a tested operating point. Report the highest passing offered
rate and the first failing tested rate as a bracket; do not infer an exact maximum
or long-term sustainable capacity. If the highest rate passes, capacity remains
unbracketed. Middle-rate repeats provide a small variability check, not confidence
intervals. No application tuning is performed between one/two-instance runs.

## Data and workload definitions

Every run stops both apps after events drain, clears only its private transactional
and show data, then provisions the same 10 movies, 3 theatres/screens, 100 shows,
80 physical seats per screen (8,000 show seats), and 8,000 distinct load-test users.
It clears Redis, restarts the selected replica(s), reconfigures the same NGINX,
and warms the actual request paths. App restarts reset JIT/pool/GC state for each
trial. Broker offsets survive graceful restarts; old events drain before fixture
reset. Warmup bookings use indices 0 onward; measurements start at index 1000.
A fixture-exhaustion error invalidates a run rather than producing fake conflicts.

- **A:** One request/iteration, cycling movie list/detail, theatre list/detail,
  and show list/detail equally. IDs vary deterministically across the fixture.
- **B:** One expiry-aware, uncached show-seat GET/iteration, rotating shows.
  Response checks require exactly 80 seats belonging to the requested show.
- **C:** One hold request/iteration to the same available show seat. One 201 and
  all remaining 409s are required. Users differ by VU. No payment is sent.
  Rejection RPS is not successful-booking throughput.
- **D:** Nine requests per successful iteration: four catalogue reads (44.44%),
  two seat reads (22.22%), one hold, one UPI payment, and one confirmation (11.11%
  each). The second seat read must expose that newly created hold. Every iteration
  uses a distinct user and seat, and a unique owner-scoped payment key. Because
  verified payment already confirms a booking, the explicit confirmation verifies
  retry-safe recovery of the same booking, not a second booking/event.
- **E:** Three requests per iteration: hold, UPI payment, explicit confirmation.
  This measures the full booking-to-Kafka pipeline, not broker-only throughput.
  Each recovery run pauses only its broker about 5 seconds after load startup,
  unpauses it about 10 seconds later, and records actual fault timestamps.

Authentication signatures and ownership checks remain enabled; fixture JWTs are
issued before load with the same trusted ephemeral key. Login/BCrypt is excluded.
Raw output excludes bearer tokens/credentials: fixture files live only in the
private temporary directory, and k6 exports bounded route names without URL/header
or body tags. Output includes compressed k6 metric points, summaries, console
output, raw resource samples, SQL statistics, and per-event timestamp deltas.

## Fairness and measurements

Both configurations use the same NGINX and shared PostgreSQL, Redis, Kafka, image,
data, quotas, pool settings, and script. One configuration stops app-b and routes
only to app-a. Two apps double app CPU/memory/pool budget; this is not equal-total-
resource scaling. Each app has 1 CPU, 768 MiB container memory, 128–384 MiB heap,
and 20 Hikari connections. Shared limits: PostgreSQL 1 CPU/512 MiB; Redis 0.5 CPU/
128 MiB; Kafka 1 CPU/1 GiB; NGINX 0.5 CPU/128 MiB. Generator: 1 CPU/768 MiB.
All containers compete on the same host. Machine facts and image hashes are saved.

Normal Compose worker timings are restored after initial harness startup:
30-second cleanup, 1-second outbox polling, 30-second lease, batch size 20 per
replica. Kafka has three partitions and one broker. Unlike Phase 11, the fast
correctness-runner timings are not used for measured runs.

Open arrival-rate executors continue offering work independently of response
speed, with 40 initial and 200 maximum VUs. Drops indicate the scheduled work could
not start within that finite client budget; report them and fail the target.
C uses closed-loop contention because its purpose is serial inventory contention.
RPS counts actual HTTP requests, not offered iterations; mixed/events multiply
successful iterations by nine/three. Percentiles describe the observed requests,
not dropped work. They can understate overload when requests never start.

Redis starts cold before each warmup. It is **partially warm**, not guaranteed
fully warm, afterward: warmup uses up to 50 read iterations/s or five write
iterations/s; TTLs remain the defaults and can expire during measurement. Save
hit/miss counters before warmup, before load, and after load. A workload with zero
Redis observations has N/A hit ratio, not 0%. B must not acquire a cache dependency.

Docker samples record CPU/memory for all services and k6; 100% means one logical
core. CPU cgroup counters before/after identify quota throttling when available.
Micrometer snapshots include heap, GC pause counters/time, Hikari usage/pending,
and native Kafka fetch lag. Samples are sequential/coarse and can miss peaks;
unavailable meters are recorded, never fabricated as zero. PostgreSQL snapshots
include connection and lock waiters; pg_stat_statements totals/top statements
include background work and monitoring. Its window includes k6 startup/collection
and final snapshots; `sql_window_seconds` states that duration. SQL calls are not
just catalogue SELECTs and are not directly comparable to Phase 9's query metric.

Outbox backlog is sampled directly once from shared PostgreSQL, not summed from
replica gauges. Store timestamp differences per measured event (excluding warmup):
`published_at - created_at` and each consumer marker's `processed_at - created_at`.
These use one database clock. Publication time is the DB mark after broker ack;
marker time is recorded within the projection transaction, before its commit.
Thus the latter is an end-to-end processing timestamp proxy, **not pure listener
execution time or broker-to-commit latency**. A consumer may record a marker before
the publisher writes its ack, so subtracting those two times would be misleading.
Native fetch lag and post-load committed-offset lag are recorded separately.

After every run the runner waits for drain and checks booking/hold uniqueness,
inventory consistency, verified payments, one outbox/notification per booking,
two consumer markers per booking, and analytics equality. A target miss is a
capacity observation; a violated correctness invariant aborts the experiment.
