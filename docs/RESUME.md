# Tbooker — resume and interview preparation

## Project title and three bullets

**Tbooker — Concurrent Movie Booking Backend with Durable Event Processing**

- Built a Java 21/Spring Boot backend with JWT authentication and PostgreSQL seat
  reservations; verified one successful hold and 99 conflicts from 100 concurrent
  requests across two JVMs, with one resulting booking.
- Implemented Strategy-based mock UPI/Card payments with durable idempotency,
  payment-gated booking, and a Kafka transactional outbox with leased publishing
  and idempotent notification/analytics projections.
- Benchmarked Docker/NGINX deployment: at 15 mixed workflows/s, two JVMs delivered
  20.72% higher HTTP throughput and 91.47% lower p95 than one JVM using twice the
  app resource budget; backed correctness with 263 Maven regression tests.

Use these numbers only with their [test scope](TESTING.md) and
[benchmark conditions](PERFORMANCE_SUMMARY.md). The third bullet describes a fixed
local workload, not production capacity or a universal scaling factor. If space is
limited, omit the metric rather than remove its qualification. Payments and
notifications are simulations; the repository is not a production deployment.

## Thirty-second pitch

“I built Tbooker to study what happens when users compete for the same movie seat.
It is a Java and Spring Boot modular monolith with PostgreSQL as the authority for
five-minute reservations and bookings. Payment initiation is idempotent, and the
UPI and Card providers are mocks with durable receipts. Booking commits also
write an outbox event that Kafka workers process safely after retries. I tested
it with two JVMs behind NGINX: 100 competing requests produced one hold and one
booking. I also measured cache behavior and documented where scaling stopped helping.”

## Two-minute technical explanation

“The central invariant is one permanent booking per show seat. A physical seat can
appear in multiple shows, so inventory is a `ShowSeat`, not just a seat number.
For a hold, the service locks that inventory row, reads PostgreSQL time after any
lock wait, expires an old hold if needed, and conditionally claims the seat. The
five-minute deadline is persisted; there is no five-minute database transaction.
The same lock order protects confirmation, cancellation and cleanup. Partial
uniqueness protects active holds, and permanent booking uniqueness is the final
backstop.

“Payment is a separate consistency problem. An owner-scoped idempotency key maps
to a durable attempt and stable provider request UUID. Spring injects UPI/Card
strategies through a registry. A mock provider records a receipt; the result
processor independently verifies it before committing financial success. Booking
confirmation is a later transaction. If it fails, success is still recorded and
can be retried or marked for reconciliation. This avoids pretending a database
rollback can undo an external payment. I have no real provider or automatic refunds.

“Confirmation commits inventory, booking links and an outbox event atomically.
Publishers use database leases, send outside the transaction and can redeliver
after a crash. Each Kafka consumer commits its deduplication marker and projection
together before its offset advances. Redis only caches public catalogue DTOs;
seat availability never depends on it. Two JVMs share PostgreSQL, Redis and Kafka
behind NGINX. Tests cover contention, rollback and actual process/broker outages.
My local benchmarks showed improvements at some loads and regressions at others;
I retained both results, including missed targets. This demonstrates failure-aware
backend design, not production high availability.”

## Ten technical questions with answer points

1. **Why is checking `AVAILABLE` before inserting a reservation unsafe?**
   Two requests can read the same state before either writes. In
   [ReservationService.create](../src/main/java/com/example/Tbooker/service/ReservationService.java),
   `SeatInventoryService.lockAndExpire` locks the shared show-seat row. The
   subsequent conditional update and reservation insert share a transaction.
   Competitors wait, then see committed state at READ_COMMITTED. Partial active-hold
   uniqueness and permanent booking uniqueness protect against alternative write
   paths. A Java `synchronized` block could not protect a second JVM.

2. **Why sample `clock_timestamp()` after obtaining the inventory lock?**
   A request may wait until its hold expires. Transaction-start time would be stale
   and could wrongly authorize confirmation. The database decision time sampled
   after the wait is compared with `expiresAt`. A valid decision under lock may
   commit after the deadline; cleanup cannot simultaneously release that seat.
   See [SeatInventoryService](../src/main/java/com/example/Tbooker/service/SeatInventoryService.java).

3. **How does Spring Security determine the booking owner?**
   The built-in bearer filter extracts the token, `JwtAuthenticationProvider`
   delegates to `NimbusJwtDecoder`, and configured validators check signature,
   issuer, audience, lifetime and required claims. A `JwtAuthenticationToken`
   populates the context; controllers take the subject from `@AuthenticationPrincipal`.
   Owner-scoped repository predicates prevent ID-based cross-user access, even for
   ADMIN. Login itself calls `PasswordEncoder.matches` directly. Token validation
   does not reload the account. See [SECURITY](SECURITY.md).

4. **What exactly does payment idempotency guarantee?**
   An owner/key uniqueness constraint identifies the same attempt. A replay must
   match reservation and method; it reuses the stored provider UUID and amount.
   The mock receipt store deduplicates by that UUID. It does not make arbitrary
   endpoints or different keys equivalent. After an uncertain response, retry the
   original key and inspect persisted financial and confirmation status. See
   [PaymentTransactions](../src/main/java/com/example/Tbooker/service/PaymentTransactions.java).

5. **Why isn't payment and booking one `@Transactional` method?**
   A real provider would not share PostgreSQL's rollback. The orchestrator uses
   `NEVER`; preparation, receipt storage/verification, financial outcome and
   confirmation have explicit separate transactions. SUCCESS survives a booking
   rollback. An expired/cancelled hold produces reconciliation-required state;
   a transient booking DB failure keeps confirmation retryable. The current mock
   provider shares PostgreSQL, so it cannot prove every remote-provider failure mode.
   See [PaymentResultProcessor](../src/main/java/com/example/Tbooker/service/PaymentResultProcessor.java).

6. **Does an idempotent Kafka producer give exactly-once booking events?**
   No. A process may die after Kafka acknowledges but before the DB marks the outbox
   published. Lease recovery resends the same event ID. Unique outbox facts avoid
   creating multiple logical events, while consumer marker-plus-projection
   transactions prevent duplicate effects after redelivery. This is at-least-once
   transport with idempotent database projections, not universal exactly-once delivery.
   See [MESSAGING](MESSAGING.md).

7. **How do background workers coordinate across two JVMs?**
   Cleanup locks bounded batches of expired inventory with `SKIP LOCKED`.
   Publishers claim outbox rows with persisted leases and use fencing tokens for
   completion updates. Kafka group membership distributes partitions within each
   logical subscriber; notifications and analytics have distinct groups. A local
   publisher semaphore bounds in-flight work but is not a distributed correctness
   lock. See [SCALING](SCALING.md).

8. **What happens when Redis fails or catalogue data changes?**
   Cache exceptions fall back to the transactional PostgreSQL reader. Typed DTOs,
   finite TTLs and after-commit invalidation bound catalogue staleness; SQL changes
   outside the JPA listener rely on TTL. Availability is uncached. Concurrent
   misses and timeout fallback can amplify database load, and a server-side cache
   hit can still arrive after the client's deadline. See [HLD caching](HLD.md#redis-catalogue-cache).

9. **How do you know the concurrency test really caused contention?**
   In [BookingConcurrencyIntegrationTests](../src/test/java/com/example/Tbooker/BookingConcurrencyIntegrationTests.java),
   an executor and latches release independent HTTP requests, while a coordinator
   holds the inventory row. The test observes all contenders waiting in PostgreSQL
   before releasing it, then checks each response and stored invariants. The
   [distributed runner](../verification/multi_instance.py) extends this to 100
   requests reaching both real JVMs. These are execution evidence, not a formal
   proof of all interleavings.

10. **Why didn't two instances always double throughput?**
    Workload arrival rate caps useful throughput; the second instance doubles only
    app resources, while PostgreSQL, Redis, Kafka and the host remain shared.
    CPU throttling, pool queues, cache deadlines and hot-row serialization can
    dominate. At 15 mixed workflows/s, HTTP throughput rose 20.72%; at 1,200 offered
    catalogue requests/s, two JVMs performed worse. The 38-run study retained 17
    missed targets. See [PERFORMANCE_SUMMARY](PERFORMANCE_SUMMARY.md).

## Trade-off questions to practice

- **Why a modular monolith instead of microservices?** One deployable and one local
  transaction boundary keep the core invariant understandable. Extract a service
  only for a measured operational/team need; extraction adds network failures and
  cross-service consistency work. Current packages are technical layers, not
  independently enforced Maven modules.
- **Why row locks rather than optimistic retries or Redis locks?** A hot seat needs
  one serial decision. PostgreSQL owns the data and protects it across replicas.
  Optimistic versions can work with retry policy; Redis locks add lease and dual-store
  consistency concerns. The trade-off is bounded contention and connection usage.
- **Why async projections?** Booking should commit during broker downtime. The
  outbox makes intent durable, but projections lag, storage grows and operators
  must handle poison events/DLTs. The analytics singleton can become a hot row.
- **Why HS256 and no session store?** It is simple for the current trusted replicas,
  but all validators hold signing capability and role changes/deletions do not
  invalidate issued tokens. Asymmetric keys and explicit lifecycle/revocation
  policy would be a separate design change.
- **Why no automatic NGINX retry?** Blind replay can duplicate unsafe operations
  after the upstream committed but lost its response. Explicit client retries with
  persisted payment keys/reservation identity make the ambiguity visible.

## Questions about known limitations

- **Can I use this for real payments today?** No: amounts/providers are mocks,
  receipts share the database, and there is no real webhook signature handling,
  settlement, automated reconciliation or refunds.
- **Is the deployment highly available or production hardened?** No: database,
  broker and proxy are single nodes; local transport is plaintext; backup/restore,
  TLS, rate limits and secret rotation operations remain future work. Dependency
  advisory follow-ups are recorded in [SECURITY](SECURITY.md).
- **Can a paid user lose a hold?** Yes, if the hold expires/cancels before a successful
  confirmation decision. Financial success is retained and reconciliation is
  required; the system does not take another user's seat or pretend to refund it.
- **What scale is proved?** Only the recorded short shared-host workloads and
  invariant checks. There is no million-user, multi-region, long-soak or sustainable
  maximum-throughput claim. Historical benchmarks predate Phase 12D timeout changes.
- **What is missing from the product?** Frontend, catalogue mutation APIs, multi-seat
  atomic purchase, confirmed-booking cancellation, real notifications, token
  refresh/revocation and richer search/pagination. See [ROADMAP](ROADMAP.md).

Use [DEMO](DEMO.md) to show the actual APIs, then [DIAGRAMS](DIAGRAMS.md) to explain
transaction boundaries. Be ready to explain one failed benchmark as carefully as
one successful concurrency test.
