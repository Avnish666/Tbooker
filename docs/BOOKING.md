# Reservations, verified payments, and permanent bookings

New bookings follow hold → mock payment → verified confirmation. The legacy
`POST /api/shows/{showId}/bookings` now returns `409` for otherwise valid creation;
leaving it enabled would bypass payment. Existing own-booking GETs and historical
confirmed bookings remain supported. See [API](API.md) for DTOs and examples.

## Identity and ownership

Spring Security validates JWTs before MVC. Controllers derive owner ID from the
subject and never accept arbitrary userId, payment status, or amount. Reservation,
payment, and booking detail queries include the owner; foreign IDs return `404`
including for ADMIN. Payment initiation accepts only method and Idempotency-Key.

## The race and inventory authority

A check-then-save algorithm is unsafe: two transactions can both read AVAILABLE
before either saves. Java synchronization would only coordinate one JVM.
`SeatInventoryService.lockAndExpire` instead locks `show_seats.id` FOR UPDATE,
reads PostgreSQL `clock_timestamp()` after waiting, then conditionally expires an
old hold. `ReservationService.create` changes AVAILABLE to HELD and inserts history
in the same transaction. Exactly one current hold can win; a losing caller gets
`409`. The partial unique HELD index independently protects history.

An expired hold can be replaced even if cleanup is delayed. Five-minute holds
are persisted timestamps, not five-minute database transactions. Cleanup uses
bounded SKIP LOCKED batches; every supported mutating workflow uses inventory
first so competing instances coordinate at PostgreSQL rather than Java memory.

## Payment transaction boundaries

1. `PaymentTransactions.prepare`: READ_COMMITTED, lock inventory, validate active
   owned hold, check key and unresolved attempts, insert/replay PENDING, commit.
2. `PaymentService` selects an injected strategy and calls it outside any
   application transaction. The mock receipt has a stable provider UUID and
   commits through `MockGatewayStore` in a separate short transaction.
3. `PaymentResultProcessor.process`: independently verify the canonical receipt;
   `PaymentTransactions.recordVerified` locks inventory, checks command identity,
   reference/version, and commits financial SUCCESS/FAILED/PENDING.
4. On SUCCESS, `ReservationService.confirmForPayment` starts the separate
   allocation transaction described below. Failure cannot roll back the already
   committed financial outcome.

NEVER propagation on the orchestrator, processor, and strategies rejects calls
from an open application transaction. The mock ledger is still on the same
PostgreSQL server; separate commits are not separate availability domains.

## Atomic confirmation

`ReservationService.confirm` (public API) and `confirmForPayment` (internal)
serialize through the same seat lock. They reload the owned reservation after
locking and use database decision time. `confirmLocked` requires persisted SUCCESS
for the same reservation/owner and an active unexpired hold, then performs:

```sql
UPDATE tbooker.show_seats SET status = 'BOOKED'
WHERE id = :seatId AND status = 'HELD';
```

One affected row authorizes booking insertion. `BookingRepository.saveAndFlush`
inserts the CONFIRMED booking; `ReservationRepository.confirm` conditionally links
booking/payment IDs and changes HELD to CONFIRMED; `PaymentRepository.markConfirmed`
changes the payment's confirmation state. All four writes commit together.
A failure at any point restores HELD/no booking while preserving the prior
financial SUCCESS. Flushing checks constraints but is not a commit.

The unconditional `uq_bookings_show_seat` remains the final duplicate-booking
backstop. Composite FKs enforce reservation/booking owner and seat identity, and
reservation/payment owner and reservation identity. Native writes flush and clear
JPA state to avoid decisions from entities read before waiting on a lock.

A reservation confirmed earlier returns the same booking on retry. Historical
Phase 7 confirmations can have no payment; they remain historical reads, not a
new unpaid write path. Concurrent duplicate confirmations cannot create another
booking. If another payment already confirmed this reservation, a late additional
SUCCESS is flagged for reconciliation rather than linked to that booking.

## Expiration and cancellation races

Eligibility is `expires_at > decisionTime` where time is sampled after the seat
lock. A confirmer waiting past expiry fails even if the HTTP request arrived
early. A valid decision may commit after the deadline; cleanup skips its locked
row and cannot undo the committed booking. Confirmation and expiration cannot
both win for the same hold. Cancellation can win before confirmation, but can
never release BOOKED inventory or a replacement user's hold.

Cancellation/expiry do not undo external money. If payment finishes late,
confirmation fails and SUCCESS remains durable with RECONCILIATION_REQUIRED.
No automatic refund, hold renewal, or confirmed-booking cancellation exists.

## Retries, versions, and remaining limits

- Owner/key uniqueness binds payment parameters; concurrent identical requests
  reuse one attempt. A stable gateway UUID deduplicates mock receipts, even when
  the call executes again. This is not exactly-once external execution.
- One PENDING attempt per reservation is database-enforced; service checks reject
  new keys while PENDING/SUCCESS exists. FAILED can retry with a new key while active.
- Result processing verifies independent provider proof. Old versions are ignored,
  equal-version contradictions rejected, and SUCCESS never regresses. A newer
  correction from FAILED to SUCCESS is retained even after another attempt won.
- SUCCESS/PENDING is recoverable by original-key retry or active reservation
  confirmation. Expired/cancelled/other-payment conflicts become reconciliation.
  No automatic reconciliation worker or operator HTTP/CLI is installed.
- PostgreSQL locks/constraints work across instances sharing one writer, but
  deployment/failover/real network gateway tests have not been performed.
- No explicit lock/statement timeouts, hold quotas, past-show cutoff, or history
  pagination are configured. Privileged direct SQL can still violate cross-table
  business facts; constraints do not authenticate a provider.

[TESTING.md](TESTING.md) describes actual contention, migration and failure tests;
[HLD](HLD.md) and [LLD](LLD.md) show states, components, and execution order.
