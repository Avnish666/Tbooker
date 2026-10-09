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

**Decision:** Use `UPDATE ... WHERE status = 'AVAILABLE'` and require exactly one
affected row, within `BookingService.book` at explicit `READ COMMITTED` isolation.
The predicate also includes the show and inventory IDs.

**Reason:** Two requests can both read an available entity before either writes.
A conditional update lets PostgreSQL coordinate competitors on the row and
re-evaluate the predicate after a committed change. Only the winner is allowed
to insert a booking. A rollback permits a waiting contender to proceed.

| Alternative | Trade-off for this use case |
| --- | --- |
| `SELECT ... FOR UPDATE`, then check and update | Also valid within one transaction, but requires an explicit lock-read path before the write. The current conditional update already acquires the necessary row lock. |
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
Immediate `CONFIRMED` is deliberately the only booking state. Holds, payment
callbacks, expiry, and cancellation need a new lifecycle, not a long transaction
spanning user interaction or a payment provider.

Atomic commit does not make the HTTP exchange exactly-once. A response can be
lost after commit; request idempotency is still missing. Phase 6 provides own-booking
retrieval, but no mapping from a retried request to its original result.

## 6. Enforce unique bookings in the database

**Decision:** `uq_bookings_show_seat` unconditionally enforces
`UNIQUE (show_seat_id)`, in addition to the conditional update.

**Reason:** The database remains the final protection if later code, a direct
insert, or a regression bypasses the normal claim path. Availability checks alone
cannot guarantee uniqueness across all writers.

**Trade-off:** This rule fits a lifecycle with only confirmed bookings. Preserving
cancelled historical rows and allowing rebooking later would require a deliberate
redesign, such as an active-allocation model or suitable partial uniqueness.
No such alternative is implemented. Uniqueness also does not force the inventory
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
Spring/JPA conventions. A framework-independent domain core or extra Strategy/
Factory abstractions would add complexity without a present variation point.
Actual patterns and SOLID limitations are documented in [LLD.md](LLD.md).

## 9. Defer Redis, Kafka, and distributed workflows

**Decision:** Keep PostgreSQL authoritative and avoid caches, brokers, payments,
or microservices until their use cases are requested and specified.

**Reason:** The current requirements can be met and tested with one transactional
database. Adding distributed state would introduce invalidation, duplicate
delivery, ordering, retry, and recovery decisions unrelated to the current
single-seat confirmation.

**Future direction:** Redis could cache suitable catalogue reads. Kafka could
support notifications or analytics using a transactional outbox and idempotent
consumers. These proposals, described in [HLD.md](HLD.md), are not installed
components or implemented guarantees. No performance measurements justify them
yet. Current verification evidence and failure-testing limits are in
[TESTING.md](TESTING.md).

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
