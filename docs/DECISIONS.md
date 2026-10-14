# Architecture decisions

These records explain decisions visible in the current implementation. The
alternatives below are trade-offs, not claims that every approach was implemented
or benchmarked. Proposed changes remain future work in [ROADMAP.md](ROADMAP.md).

## 1. Start with a modular monolith

**Decision:** Use one Spring Boot deployment and one Maven module, organized into
Controller → Service → Repository layers. Separate catalogue and booking use
cases into focused services. Current packages are technical layers; formal
domain modules and dependency-boundary checks are not implemented.

**Reason:** The booking invariant spans inventory and bookings. Keeping both in
one application/database permits an ordinary local transaction and a small
development and testing setup. Microservices would add network failures,
deployment coordination, and cross-service consistency work before independent
scaling or ownership has been demonstrated.

**Trade-off:** Deployment and scaling remain coupled, and package discipline
alone cannot prevent all future coupling. Establish stronger internal domain
boundaries as the code grows; extract a service only for a concrete need.

## 2. Use PostgreSQL and test against PostgreSQL

**Decision:** Use PostgreSQL for runtime storage and Testcontainers integration
tests. Model relationships with foreign keys, scoped uniqueness, and checks.

**Reason:** The data is relational, and booking needs transactional writes plus
well-defined conditional-update locking. PostgreSQL can enforce integrity even
when a writer bypasses the Java service. Its `timestamptz`, expression indexes,
and composite foreign keys support the current model directly.

**Alternatives and cost:** A document database would require a different model
and deliberate multi-record transaction/invariant handling. H2 is convenient for
some tests but cannot establish this PostgreSQL concurrency behavior. Docker is
therefore a requirement for the full suite. PostgreSQL is a shared operational
dependency whose backups, availability, and capacity still need later work.

## 3. Separate physical seats from show inventory

**Decision:** `Seat` belongs to a screen; `ShowSeat` belongs to a show and a seat.
Booking references `ShowSeat`. Add a supporting `screen_id` to inventory and two
composite foreign keys so the show and seat must share a screen.

**Reason:** Availability is specific to a show. A global booked flag on the
physical seat would incorrectly block the same seat for every other show.
Application-only screen validation would leave the invariant vulnerable to
direct SQL or later write paths.

**Trade-off:** Inventory repeats for every show, and the supporting screen column
adds deliberate redundancy and parent composite unique indexes. That cost buys
database enforcement without a custom trigger. Layout changes and inventory
generation will need explicit future use cases.

## 4. Claim availability with an atomic conditional update

**Decision:** Preserve conditional PostgreSQL updates and require one affected
row at READ_COMMITTED. Phase 3 used AVAILABLE-to-BOOKED in `BookingService.book`.
Phases 7–8 coordinate lifecycle changes through the inventory row lock, using
AVAILABLE-to-HELD for creation and HELD-to-BOOKED for payment-gated confirmation.
The legacy immediate write path is disabled in Phase 8.

**Reason:** Two requests can both read an available entity before either writes.
A conditional update lets PostgreSQL coordinate competitors on the row and
re-evaluate the predicate after a committed change. Only the winner is allowed
to insert a booking. A rollback permits a waiting contender to proceed.

| Alternative | Trade-off for this use case |
| --- | --- |
| `SELECT ... FOR UPDATE`, then check and update | Now used before the conditional update in Phase 7 to coordinate inventory and reservation history with a single lock order. |
| Optimistic `@Version` locking | Can detect stale writes, but needs a version column and explicit handling of optimistic failures/retries. The current business predicate directly expresses availability. |
| Serializable isolation | Useful for broader multi-row invariants; adds possible serialization failures and retry policy. Not required for this single-row claim. |
| Java `synchronized` or an in-process lock | Cannot coordinate separate application instances or non-Java writers. |
| Redis/distributed lock | Adds another authority and failure/expiry cases while the data still requires database integrity. It is not needed for the current claim. |

**Trade-off:** The SQL is PostgreSQL-tested and sits alongside JPA. Its modifying
query clears the persistence context to avoid stale managed availability, and
the service obtains fresh references afterward. Hot-seat requests wait on a row;
this is a correctness choice, not evidence of a particular throughput.

## 5. Keep claim and confirmation in one transaction

**Decision:** Spring `@Transactional` wraps resource checks, the seat update, and
`saveAndFlush` of the confirmed booking. The interceptor commits before the
controller can return success. Runtime exceptions trigger rollback.

**Reason:** Committing the seat update separately could leave a booked seat with
no booking if insertion fails. Inserting first without coordinating inventory
would also split the invariant. A single database transaction makes the changes
atomic, and the row lock remains held through completion.

**Trade-off:** Transactions consume connections and can wait under contention.
Keep them short; no external payment call is made while a seat is locked.
`CONFIRMED` remains the only permanent booking state. Phase 7 stores
temporary holds, expiration, and reservation cancellation separately. Neither
workflow keeps a transaction open while a user decides or calls a payment provider.

Atomic commit does not make the HTTP exchange exactly-once. A response can be
lost after commit. Hold creation still lacks a recovery key. Phase 8 payments
use owner-scoped keys, and reservation confirmation returns the stored booking
on a retry using the reservation ID. The financial outcome commits separately
from this allocation transaction, as described in decision 16.

## 6. Enforce unique bookings in the database

**Decision:** `uq_bookings_show_seat` unconditionally enforces
`UNIQUE (show_seat_id)`, in addition to the conditional update.

**Reason:** The database remains the final protection if later code, a direct
insert, or a regression bypasses the normal claim path. Availability checks alone
cannot guarantee uniqueness across all writers.

**Trade-off:** This rule fits a lifecycle with only confirmed bookings. Preserving
cancelled historical rows and allowing rebooking later would require a deliberate
redesign, such as an active-allocation model or suitable partial uniqueness.
No confirmed-booking cancellation alternative is implemented; reservation
history instead lives in a separate table and does not consume booking uniqueness. Uniqueness also does not force the inventory
status to agree with booking existence; the service transaction maintains that
cross-table consistency.

Only SQLSTATE `23505` for this named constraint maps to `409`. Other integrity
failures return a generic `500`, preserving the distinction between an occupied
seat and an unexpected defect.

## 7. Use Long identities, UTC instants, and Flyway-owned DDL

**Decision:** Use PostgreSQL identity `BIGINT` keys mapped to `Long`, Java
`Instant`/PostgreSQL `timestamptz`, and explicit UTC JDBC/session settings. Flyway
owns migrations; Hibernate validates the resulting schema.

**Reason:** Sequential database-generated IDs are sufficient for one database
and straightforward joins. UTC instants remove ambiguity from absolute show and
booking times. Reviewed SQL preserves constraints that entity annotations alone
do not express, including the composite foreign keys and email expression index.

**Alternatives and cost:** UUIDs could support independently generated IDs later,
but are not currently needed. Local date-times would require extra zone context.
Identity values can have gaps and are not secrets. Application-assigned booking
time depends on the host clock, and `timestamptz` does not retain an original zone
name. Automatic Hibernate schema updates would obscure the reviewed migration
history, so they are disabled.

Development seed data is a separate repeatable migration loaded only by `dev`.
This keeps normal startup empty but means seeded development databases should
not switch migration locations to a non-dev profile.

## 8. Keep HTTP contracts separate and abstractions focused

**Decision:** Expose record DTOs, use thin controllers and constructor injection,
and perform entity-to-DTO mapping in services. Keep open-in-view disabled. Use
Spring Data interfaces without adding a service interface for every class.

**Reason:** DTOs avoid leaking lazy persistence graphs and allow the response to
combine movie, venue, and show data. Service boundaries make transactions
explicit. Constructor dependencies reveal each class's collaborators.

**Trade-off:** DTOs require mapping code, and the current services still depend on
Spring/JPA conventions. A framework-independent domain core would add complexity.
Phase 8 now has a concrete gateway variation point justifying PaymentStrategy and
an injected registry (decision 15).
Actual patterns and SOLID limitations are documented in [LLD.md](LLD.md).

## 9. Keep allocation in PostgreSQL; add downstream infrastructure by phase

**Decision:** Keep PostgreSQL authoritative and defer real providers and
microservices. Phase 8 adds the mock payment boundary and recovery states.
Phase 9 supersedes the earlier cache deferral for catalogue metadata only.
Phase 10 supersedes broker deferral for downstream booking facts, using an outbox
and idempotent consumers (decisions 17–19). Neither Redis nor Kafka decides seat
allocation or participates in booking database commits.

**Reason:** Seat allocation still fits one transactional database. Distributed
components introduce invalidation, duplicate delivery, ordering, retry, and
recovery decisions; each phase adds a specific tested boundary while keeping the
booking invariant in PostgreSQL.

**Current evidence:** Kafka notification/analytics behavior and recovery are in
[MESSAGING.md](MESSAGING.md). Redis catalogue evidence is in
[PERFORMANCE.md](PERFORMANCE.md); correctness and failure-testing limits are in
[TESTING.md](TESTING.md). Service extraction and real providers remain future work.

## 10. Use Spring Security with short-lived JWTs and owner-scoped queries

**Decision:** Authenticate local accounts with cost-12 BCrypt, issue HS256 JWTs
through Spring's Nimbus-backed encoder, and validate them through the resource-server
filter chain. Nimbus 10.10 is explicitly pinned rather than inheriting the older
transitive release. Require an environment signing key, issuer/audience/time and
claim validation, and use USER/ADMIN route rules. Booking identity comes from the
principal, and booking read queries include its owner ID, with no ADMIN bypass.

**Reason:** Maintained framework/library code handles JWT parsing and signature
validation. Route-level roles alone would not prevent access to another user's
booking ID, so authorization also belongs in the database query. Registration
always creates USER and rejects role injection. Null passwords on legacy users
prevent invented credentials or takeover while preserving their history.

**Alternatives and trade-offs:** Stateful sessions simplify immediate revocation
but need a shared store for replicas. JWT validation avoids that store, while
role changes and credential compromise remain effective until expiry or signing
key replacement. HS256 keeps one application's setup small, but all validators
hold signing authority; independent services may later need asymmetric keys.
There are no refresh tokens, denylist, MFA, reset/enrollment flow, or rate limits.
An ADMIN is provisioned through a trusted operator action, not registration input.

CSRF is disabled only because bearer credentials are explicitly sent in the
Authorization header and cookie/session authentication is disabled. TLS and
secure token/key handling remain deployment requirements. See [SECURITY.md](SECURITY.md)
for the filter chain, patterns used, lifecycle limits, and operational trade-offs.

## 11. Keep temporary reservation history separate from permanent bookings

**Decision:** Add `reservations` with `HELD`, `CONFIRMED`, `CANCELLED`, and
`EXPIRED`; add `HELD` to inventory. Preserve `uq_bookings_show_seat` and existing
bookings. A partial unique index permits one stored `HELD` reservation per seat.
A confirmed reservation links uniquely to its booking; a composite foreign key
also enforces matching owner and show seat.

**Reason:** Expiring a hold must permit another hold without losing historical
identity or weakening the permanent-booking invariant. An old reservation ID
must never confirm or cancel a later owner's hold. A separate table makes the
lifetimes and retry semantics explicit without introducing pending bookings.

**Alternative:** Store holds as pending booking rows and replace unconditional
booking uniqueness with partial active-state uniqueness. That would change the
existing booking lifecycle and read contracts. We retain the established
confirmed-booking model instead. The trade-off is another table and supporting
indexes; archival/retention policy is future work.

## 12. Serialize short seat operations and evaluate expiry after locking

**Decision:** Every supported seat mutation locks inventory first. Reservation
operations and payment-gated booking use conditional updates within one
`READ_COMMITTED` transaction. Database `clock_timestamp()` sampled after locking
is the decision time; `expires_at <= decisionTime` is expired. The hold duration
is five minutes. No Java monitor, long-lived transaction, Redis lock, or external
payment call participates.

**Reason:** A transaction may wait beyond expiry. PostgreSQL `CURRENT_TIMESTAMP`
is fixed at transaction start and would give stale time to a waiting confirmer.
The row lock makes confirmation, expiration, replacement, and cancellation
mutually ordered. Constraints independently enforce one held row and one booking.

**Trade-off:** An extra locking query and row contention are accepted for a clear
multi-table transaction. This is still per seat, not a show-wide lock. A valid
confirmation decision can commit after the deadline; the deadline is not a
network-response deadline. Explicit lock/statement timeouts, fairness, and
multi-node failover tests remain future work.

## 13. Use scheduled normalization plus synchronous expiry checks

**Decision:** Run a 30-second scheduled cleanup callback, one batch of up to 100
expired seats per transaction, using `SKIP LOCKED`. Claim paths reclaim expired
holds themselves. Read DTOs compute effective expiration without changing rows.

**Reason:** Scheduler delay, restart, or overlapping replicas must not extend a
hold or deny new claims. Persisted timestamps establish eligibility immediately;
cleanup updates stored statuses and leaves history intact. A failed domain
request rolls back attempted cleanup with its other writes; this does not change
the deadline's authority.

**Trade-off:** Stored `HELD` may temporarily mean expired, and a large backlog
requires multiple ticks. No time-varying `now()` predicate is used in a unique
index. PostgreSQL is the only authority; adopting Redis expiration would require
new consistency and recovery decisions. Metrics and archival are not implemented.

## 14. Make reservation confirmation retry-safe without general idempotency keys

**Decision:** `POST /api/reservations/{id}/confirmation` returns `200` with the
same booking for both first confirmation and retries. Ownership remains enforced
on every call. Reservation cancellation is also repeatable (`204` for CANCELLED),
but cannot release a replacement hold or a confirmed booking.

**Reason:** A confirmation response can be lost after commit. The stable
reservation-to-booking link recovers that result and gives concurrency retries
one permanent outcome. Unique booking and reservation-link constraints remain
the database backstops.

**Limit:** Hold creation remains non-idempotent; retrying an active allocation
returns `409`. Phase 8 adds keys specifically for payment initiation and disables
immediate booking. There is no general-purpose idempotency store, confirmed-booking
cancellation, or refund workflow.

## 15. Select injected payment strategies with a bounded registry

**Decision:** `PaymentStrategy` exposes `method`, `charge`, and `verify`.
`MockUpiPaymentStrategy` and `MockCardPaymentStrategy` are Spring beans.
`PaymentStrategyRegistry` receives their List, rejects duplicate/missing methods,
and keeps an immutable map. `PaymentService` selects by PaymentMethod.

**Reason:** Provider behavior is now a real variation point. SRP keeps it outside
booking logic; DIP makes orchestration depend on the interface; OCP allows a
new implementation without editing the orchestration branches. Spring owns
construction, so a separate object-building factory is unnecessary.

**Trade-off:** Adding another method still requires the enum/schema/API policy to
change. This is not an unrestricted plugin system. Both mock providers share a
small durable ledger service instead of duplicating transaction mechanics.

## 16. Commit financial outcome separately from booking confirmation

**Decision:** First persist an attempt, invoke/verify the gateway outside any
application transaction, commit the verified outcome, then attempt confirmation
in another short transaction. Mark inventory, booking, reservation linkage, and
payment confirmation together. NEVER propagation on orchestration and strategies
rejects accidental calls from an application transaction; the mock ledger owns
separate REQUIRES_NEW transactions.

**Reason:** A real provider cannot be rolled back by a local database rollback.
Persisting SUCCESS only inside the booking transaction would erase evidence of a
charge when insertion fails. `confirmationStatus` distinguishes recoverable
SUCCESS/PENDING from CONFIRMED and RECONCILIATION_REQUIRED.

**Alternative:** Holding a seat transaction across network I/O would consume a
connection/lock, still fail to make external money atomic, and create uncertain
outcomes on timeouts. A distributed transaction protocol is not available here.

**Trade-off:** More transaction boundaries and intermediate states require retry
and reconciliation. Original-key retry and the internal processor are implemented;
a background reconciliation worker, operational UI, and refunds are not. The mock
ledger shares PostgreSQL, so it demonstrates separate commits rather than an
independently available external provider. No exactly-once external execution or
production payment certification is claimed.

## 17. Persist owner-scoped idempotency and stable provider operation identity

**Decision:** UNIQUE(user_id,idempotency_key) binds a case-sensitive key to the
reservation and method. Stable UUID and server amount/currency snapshots travel
to the provider. Same parameters return the current stored attempt; mismatches
return `409`. A partial unique index allows one PENDING attempt per reservation.
Under the seat lock, the service also rejects a new key while SUCCESS exists.

**Reason:** A lost HTTP response or concurrent retry must not create another
local payment attempt. Reusing the same provider UUID lets the mock ledger
resolve uncertain execution without creating another receipt. In-memory maps
would lose the key after restart and differ across application instances.

**Trade-off:** Keys and history have no retention limit yet. Hold creation has no
idempotency contract. Gateway calls can be repeated: provider-side deduplication
is a required contract, not an exactly-once invocation guarantee. FAILED permits
another key only while the hold remains active; identical FAILED replays retain
the recorded result unless trusted internal verification corrects it.

## 18. Independently verify results and retain late financial facts

**Decision:** No public callback accepts arbitrary status. The internal processor
retrieves the canonical receipt by the stored command, validates its ID, price,
currency, reference, and version, and serializes changes under the inventory lock.
Stale deliveries cannot reverse progress; conflicting equal-version outcomes
fail verification; SUCCESS never becomes FAILED/PENDING. A newer provider
correction may change FAILED to SUCCESS.

**Reason:** The client cannot be trusted to prove payment. Notifications may be
duplicated or arrive out of order. Financial truth must survive expiry or a
booking failure. An expired/cancelled hold cannot be reclaimed by late success,
and an extra success cannot create a second booking.

**Trade-off:** There is no unique-success-per-reservation index: if a provider
corrects a previous failure after a retry paid successfully, both financial
successes must remain visible. Permanent booking uniqueness, the selected
payment FK, conditional updates, and row locks prevent duplicate allocation;
the extra payment requires reconciliation. Schema constraints cannot authenticate
provider data against a compromised application or privileged database writer.
A real provider would additionally require authenticated/signed notifications,
independent API verification, secret management, timeout policies, and refunds.

## 19. Retire the unpaid write path while preserving booking history

**Decision:** The legacy `POST /api/shows/{id}/bookings` validates requests/resources
but returns `409` for otherwise valid creation. Reservation confirmation requires
an owned stored SUCCESS attempt; confirmation retries return the same booking.
V6 leaves old confirmed reservations without a payment link intact.

**Reason:** Leaving immediate booking active would bypass verified payment.
Inventing payments for old bookings would falsify financial history. Existing
booking reads, JWT rules, reservation expiry/cancellation, and uniqueness remain.

**Trade-off:** Clients using the old creation endpoint must adopt hold-and-pay.
The schema allows a null historical payment link, so the supported service is
responsible for enforcing the new payment requirement on new bookings.


## 16. Cache public metadata with Redis, keep allocation uncached

**Decision:** Spring Cache with shared Redis and typed JSON DTO values caches
movie/theatre list/detail and show list/detail. A separate read service opens a
transaction only on misses. Use explicit versioned keys and TTLs: movie/theatre
10-minute details, 5-minute lists; show 1-minute details, 30-second lists. Select
a no-op cache with an environment flag for baseline measurement or operations.

**Reason:** These descriptive reads can tolerate bounded staleness and avoid
repeated joins/mapping. The existing show entity graph already avoids N+1 queries;
there was no evidence requiring new PostgreSQL indexes. Shared Redis provides
cross-instance reuse that local process caches cannot. DTO-specific serializers
avoid Hibernate proxies, Java serialization, and permissive polymorphic typing.

**Invalidation:** JPA lifecycle events evict changed metadata and related show
entries after commit; unrelated detail entries remain. Cache access is bypassed
inside caller transactions. Native/bulk writers must explicitly publish an event
or accept TTL staleness. There are no catalogue mutation endpoints in this phase.
The event is not durable; races/crashes/failed invalidation can leave old data
until a later fill's TTL expires. A durable outbox or versioned read protocol
would strengthen this guarantee at additional complexity and is not implemented.

**Failure trade-offs:** Cache failures degrade to PostgreSQL and observable
warnings/counters. Redis is excluded from readiness and allocation. Command
and connect timeouts bound individual waits, not whole-system outage load.
No circuit breaker, stampede prevention, negative caching, or TTL jitter is added.
An outage can produce extra latency, query load, and logs. Monitor before tuning.

**Availability decision:** Leave `/api/shows/{id}/seats` uncached. Expiry can change
availability without a write and without the scheduler running, so metadata-style
invalidation is insufficient. PostgreSQL evaluates expiry and serializes all
allocation decisions under row locks. A stale movie title never authorizes a hold.

**Alternatives:** Local Caffeine avoids a network hop but would need per-instance
invalidation. HTTP/CDN caching would need a separate public cache-control policy;
Spring Security's existing no-store headers are retained. Redis locks or inventory
ownership would add a second source of truth. None is introduced. The reproducible
ABBA k6 comparison measures one workload; it is not a capacity or SLA claim.


## 17. Use a transactional outbox for booking-confirmed facts

**Decision:** Add one unique outbox record in the exact PostgreSQL transaction
that confirms a paid booking. Publish only after a short leased claim commits.
PostgreSQL remains the authoritative booking ledger; Kafka is downstream.

**Reason:** Publishing first can emit a booking that later rolls back; publishing
after commit without a durable outbox can lose the event on a crash. An in-process
AFTER_COMMIT callback alone cannot close that failure window. Database/Kafka XA
or a distributed transaction is unnecessary for the requested asynchronous effects.
The existing catalogue's best-effort invalidation has a different, TTL-bounded
consistency requirement and remains unchanged.

**Trade-offs:** Polling adds publication delay, SQL load, and a growing outbox.
SKIP LOCKED plus database-time leases supports concurrent publishers without
holding row locks during broker waits. A fencing token prevents old completions
from overwriting newer claims. No permanent retry limit discards unpublished facts;
backoff is capped. CDC/Debezium could replace polling later, with added operations.

## 18. Accept duplicate transport; make database consumers idempotent

**Decision:** Use booking ID as the partition key and separate notification and
analytics groups. Each consumer commits its event marker and effect in one
READ_COMMITTED transaction, then Spring Kafka commits the record offset. Producer
idempotence and `acks=all` are enabled, but no cross-system exactly-once claim is made.

**Reason:** A crash after Kafka acceptance/before outbox update causes resend; a
crash after consumer commit/before offset commit causes redelivery. Durable UUID
markers and canonical payload hashes protect both effects. A unique booking guard
also catches accidental new identities for the same confirmation. Insertion
handles conflicts across both unique indexes before checking the committed marker
and hash; targeting only the event-ID key can reject an identical concurrent
delivery at the other unique index. A real email
provider would need its own idempotency/recovery boundary; this phase persists a
mock delivery only and does not justify another Strategy abstraction.

**Trade-offs:** Deduplication history consumes storage and cannot be pruned without
a replay-retention policy. The analytics singleton is a write serialization point.
Projection reads are eventually consistent; booking success does not imply that
both consumers have caught up. Broker partitions and retention need operational
planning, and multiple publishers do not create a global event order.

## 19. Bound consumer retries and preserve poison records in a DLT

**Decision:** Malformed/schema/integrity errors go directly to the DLT; transient
failures get three retries with capped exponential backoff. Commit past a failed
record only after DLT publication is acknowledged. Preserve raw bytes and diagnostic
headers, including the originating group. Source size limits leave room for DLT
headers. Expose metrics to ADMIN only; keep booking readiness independent of Kafka.

**Trade-offs:** DLT unavailability must pause progress instead of silently dropping
data. DLT publication itself can duplicate after uncertain acknowledgments.
Authorized manual replay keeps the original event ID; automatic replay, alerts,
retention cleanup, broker ACL/TLS provisioning, and real email remain future work.
Local single-node KRaft and same-process concurrency tests do not certify HA or
production readiness. See [MESSAGING.md](MESSAGING.md) for failure boundaries,
rollout constraints, and operational recovery.

## Phase 11 — Replicate the monolith with one authoritative writer

**Decision:** Run the same verified JAR in two non-root Java containers, behind
NGINX round robin, sharing PostgreSQL, Redis, Kafka, and JWT configuration. Keep
the original host-development Compose file separate. No schema or booking
algorithm change is required: inventory-first row locks and database constraints
already arbitrate across connections from different JVMs.

**Trade-off:** This proves local horizontal application scaling, not HA of the
single database, broker, cache, or edge. Each new JVM adds a pool and consumers;
connection budgets and topic partition counts bound scaling. Stateless JWTs avoid
sticky sessions, but shared symmetric keys require coordinated rotation.

**Decision:** Disable `proxy_next_upstream` for every request. Passive exclusion
uses one failed transport attempt and a five-second timeout. Docker readiness is
not an active NGINX health-check feed. A caller may see 502/503/504 during failure
or restart. Connection waits use a one-second timeout; upstream read/send idle
timeouts are twenty seconds. Neither timeout proves that a write rolled back.

**Trade-off:** Healthy peers serve subsequent traffic, but the failed request is
not transparently retried. Automatic write replay could duplicate a hold or hide
an ambiguous payment result. Payment retries must reuse the owner's original key;
confirmation retries use the reservation ID. Hold creation still lacks a recovery
key. Public deployment needs TLS, stronger availability controls, and safe client
retry policies.

**Decision:** Reuse PostgreSQL SKIP LOCKED expiry batches, fenced outbox leases,
and durable projection markers for background workers. Redis/Kafka only need to
be started in Compose dependencies; PostgreSQL must be healthy. Optional service
failure must not prevent explicit restart of a database-ready backend.

**Decision:** Use a separate disposable Compose runner for real JVM termination
and load tests. Synthetic test JWTs use ephemeral signing keys and the real
resource-server filter chain. ABBA benchmark repetitions share infrastructure,
fixtures, workload, and per-replica quotas; report added resources and expected
409s explicitly. Instance-tagged metrics and Docker samples locate pressure;
short local measurements are not production capacity estimates.


## Phase 12B — Build from source and retain local state explicitly

**Decision:** A pinned JDK builder uses the repository Maven wrapper; a separate
non-root JRE stage contains only the JAR. CI/local Maven tests remain explicit,
because an ordinary Docker build has no Testcontainers daemon. The build context
excludes credentials and local artifacts. Image digests are intentionally updated
with validation, not silently refreshed via moving tags.

**Decision:** Keep the scaled and host Compose workflows separate. Only NGINX
joins a host-facing bridge; the scaled service network is internal. PostgreSQL
and Kafka retain named volumes. Redis is disposable metadata and has no volume.
Both replicas get the same JWT and policy environment; internal addresses remain
fixed service names. Required credentials have no default.

**Migration strategy:** Same-image/profile startup uses Flyway's PostgreSQL lock.
For schema changes, quiesce both writers and take a restorable backup first.
Do not infer that a serialized migration makes mixed-version rolling upgrades safe.
No rollback DDL or new schema change is introduced in this phase.

**Trade-offs:** Immutable pins need maintenance. Source packaging duplicates CI
compilation and is not itself a test result. Local volumes are not backups;
Compose restart policies do not heal an unhealthy-but-running process. The setup
remains single-host plaintext with one database/broker/proxy, not production HA.
See [DEPLOYMENT.md](DEPLOYMENT.md) for the operational contract and verification.

## Phase 12D — Bound resource use and make failures diagnosable

**Decision:** Keep Spring's JWT resource-server chain and owner predicates. Add
request correlation before security and a 16 KiB body filter after authorization,
before MVC/Jackson. NGINX overwrites the same request ID. Unknown-length bodies
receive the same limit as declared-length bodies. Filters handle transport
concerns without a custom authentication mechanism or new business endpoint.

**Trade-off:** Bounded buffering serves the current blocking JSON API, not a future
streaming-upload API. It is not a rate limiter or total-memory admission policy.
Correlation is not trusted identity, idempotency or a distributed trace.

**Decision:** Use Boot ECS JSON, sanitized summaries and fixed-cardinality counters.
Exclude throwable message/stack fields and disable Hibernate raw SQL-error logs
because PostgreSQL may include failed-row data. Limit application warnings per
JVM/category for 30 seconds while counting every failure. Conflicts are metrics.

**Trade-off:** Normal console logs have less debugging detail. Operators use types,
correlation, metrics and persisted state, and must still protect infrastructure
logs and DLT diagnostics. This is not general-purpose secret detection/redaction.

**Decision:** Bound Hikari acquisition (3 s), PostgreSQL locks (5 s), statements
(15 s), JDBC connect/socket/cancel waits (3/20/2 s). Return generic 503 for DB
resource/transient failures, preserve 409 for known business/unique conflicts and
500 for other integrity errors. No automatic write retries or NGINX replay.

**Trade-off:** Limits apply per operation, not per HTTP transaction. Several
statements can exceed the NGINX 20-second idle timeout; a network failure can
follow a commit. Shorter waits may reject work under overload. Flyway uses the
same pool, so large migrations require reviewed startup limits with quiesced
writers and a verified backup. Phase 12C throughput was not remeasured under these
new defaults and must not be presented as a new capacity result.

**Decision:** Reuse Actuator/Micrometer for local p95, status counts, JVM/GC/pool,
conflicts, payment outcomes, cache errors, outbox and consumer metrics. Redis health
is a separate group; readiness stays DB-only. Native fetch lag is supplemented by
broker group-offset commands, without a new monitoring stack.

**Trade-off:** Percentiles cannot be averaged across instances. Counters reset,
sampled backlog can be stale and there is no durable history/alert service. Root
health includes optional services and can be DOWN while booking readiness is UP.
Query individual replicas and persisted state before inferring a payment outcome.
