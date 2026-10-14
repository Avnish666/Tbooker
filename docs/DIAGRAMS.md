# Architecture walkthrough

These five diagrams summarize the implemented system at Phase 12E. Follow the
linked source and detailed [HLD](HLD.md), [LLD](LLD.md), [schema](DATABASE.md) and
[decisions](DECISIONS.md) for the full contracts. Services are concrete Spring
beans unless explicitly marked as interfaces. Arrows omit incidental dependencies.

## 1. High-level architecture

```mermaid
flowchart TB
    Client[HTTP client] --> N[NGINX - passive detection, no request replay]
    N --> A[Spring Boot A - JWT, controllers, services]
    N --> B[Spring Boot B - same application]
    A & B -->|transactions and uncached availability| PG[(PostgreSQL)]
    A & B -->|public metadata DTOs only| R[(Redis)]
    subgraph Workers[Background components present in both JVMs]
        Cleanup[ReservationCleanup]
        Publisher[OutboxPublisher]
        Consumers[BookingEventListeners - two logical consumer groups]
    end
    Cleanup -->|inventory locks and database time| PG
    Publisher <-->|claim, lease and fenced completion| PG
    Publisher -->|booking key, stable event ID| K[Kafka - three partitions, one broker]
    K --> Consumers
    Consumers -->|processed marker plus projection transaction| PG
```

[Scaled Compose](../compose.scaled.yaml) deploys six containers. Workers run inside
the application containers, not in additional services. PostgreSQL and Kafka have
named volumes; Redis is disposable. The shared database owns all correctness
state. The private network exposes only NGINX on host loopback by default.
[NGINX configuration](../deploy/nginx/nginx.conf) disables automatic replay;
failed requests can reach the client before passive detection excludes a backend.

## 2. Low-level class design

```mermaid
classDiagram
    class PaymentStrategy {
        <<interface>>
        +method() PaymentMethod
        +charge(PaymentCommand) void
        +verify(PaymentCommand) GatewayResult
    }
    class PasswordEncoder { <<interface>> }
    class ReservationRepository { <<interface>> }
    class ShowSeatRepository { <<interface>> }
    class PaymentRepository { <<interface>> }
    class BookingRepository { <<interface>> }
    class UserRepository { <<interface>> }
    ReservationController --> ReservationService
    ReservationService --> SeatInventoryService
    SeatInventoryService --> ShowSeatRepository
    SeatInventoryService --> ReservationRepository
    ReservationService --> ReservationRepository
    ReservationService --> BookingRepository
    ReservationService --> PaymentRepository
    ReservationService --> BookingEventRecorder
    BookingEventRecorder --> OutboxRepository
    AuthController --> AuthService
    AuthService --> UserRepository
    AuthService --> PasswordEncoder
    AuthService --> TokenService
    PaymentController --> PaymentService
    PaymentService --> PaymentTransactions
    PaymentService --> PaymentResultProcessor
    PaymentService --> PaymentStrategyRegistry
    PaymentResultProcessor --> PaymentTransactions
    PaymentResultProcessor --> ReservationService
    PaymentResultProcessor --> PaymentStrategyRegistry
    PaymentTransactions --> PaymentRepository
    PaymentTransactions --> SeatInventoryService
    PaymentStrategyRegistry o-- PaymentStrategy : injected implementations
    PaymentStrategy <|.. MockUpiPaymentStrategy
    PaymentStrategy <|.. MockCardPaymentStrategy
    MockUpiPaymentStrategy --> MockGatewayStore
    MockCardPaymentStrategy --> MockGatewayStore
    User "1" <-- "*" Reservation : owner
    User "1" <-- "*" Payment : owner
    User "1" <-- "*" Booking : owner
    ShowSeat "1" <-- "*" Reservation : history
    ShowSeat "1" <-- "0..1" Booking : permanent allocation
    Reservation "1" <-- "*" Payment : attempts
    Reservation --> Booking : optional confirmation
    MovieShow "1" <-- "*" ShowSeat
    Seat "1" <-- "*" ShowSeat
    Movie "1" <-- "*" MovieShow
    Screen "1" <-- "*" MovieShow
    Screen "1" <-- "*" Seat
    Theatre "1" <-- "*" Screen
```

Repository interfaces shown here extend Spring Data `JpaRepository`; Spring creates
their implementations. `OutboxRepository` and `MockGatewayStore` are concrete JDBC
collaborators. The complete [LLD](LLD.md) includes catalogue/cache/event classes
and schema relationships. No generic `ReservationService` interface exists.

Patterns actually used:

- **Strategy and registry:** [PaymentStrategy](../src/main/java/com/example/Tbooker/payment/PaymentStrategy.java)
  is implemented by UPI/Card Spring beans. The registry validates its injected
  list and selects by method; it is not a separate Abstract Factory hierarchy.
- **Repository, DTO and service layer:** HTTP mapping, use-case policy and
  persistence are separated; entities never become API response bodies.
- **Dependency injection and proxy interception:** constructor dependencies enable
  substitution; Spring proxies apply transactions and caching at bean boundaries.
- **Cache-aside, transactional outbox and idempotent consumer:** distributed
  reliability patterns, detailed in diagrams below and [MESSAGING](MESSAGING.md).

SRP separates payment orchestration from transaction stages and gateway behavior.
DIP/OCP are concrete at the `PaymentStrategy`, `PasswordEncoder` and repository
interfaces; adding a provider also requires deliberate enum/config/schema work.
State enums describe transitions; the project does not implement the GoF State
pattern or a general Saga framework.

## 3. Authentication, hold, payment and booking sequence

```mermaid
sequenceDiagram
    actor C as Client
    participant S as Spring Security
    participant A as AuthController and AuthService
    participant T as TokenService
    participant R as ReservationService proxy
    participant I as SeatInventoryService
    participant P as PaymentService and PaymentResultProcessor
    participant X as PaymentTransactions proxy
    participant G as PaymentStrategy and MockGatewayStore
    participant DB as PostgreSQL
    C->>A: POST /api/auth/login with email and password
    A->>DB: Load account
    A->>A: PasswordEncoder.matches - BCrypt
    A->>T: issue(user)
    T-->>C: Signed HS256 JWT
    C->>S: POST /api/shows/id/reservations with Bearer JWT
    S->>S: Decode, verify signature and claims, set SecurityContext, authorize
    S->>R: ReservationController.create with JWT subject
    R->>DB: BEGIN hold transaction - READ_COMMITTED
    R->>I: lockAndExpire(showSeatId)
    I->>DB: FOR UPDATE inventory, then clock_timestamp, expire old hold
    R->>DB: Conditional AVAILABLE to HELD, insert owned reservation with deadline +300s
    R->>DB: COMMIT
    R-->>C: 201 reservation - no transaction during user think time
    C->>S: POST /api/reservations/id/payments with same identity and idempotency key
    S->>P: PaymentController.initiate - orchestration NEVER joins a transaction
    P->>X: prepare(owner, reservation, method, key)
    X->>DB: TX1 lock inventory, validate active hold, persist PENDING attempt and stable UUID
    X-->>P: Committed command
    P->>G: charge then independently verify command
    G->>DB: Separate mock receipt write/read transactions
    G-->>P: Verified financial result
    P->>X: recordVerified(paymentId, result)
    X->>DB: TX2 validate receipt identity and version, commit SUCCESS
    P->>R: confirmForPayment(reservation, owner, payment)
    R->>I: TX3 lock inventory, obtain database decision time
    R->>DB: Recheck owner, active deadline and persisted SUCCESS payment
    alt Hold still active
        R->>DB: HELD to BOOKED, unique booking, reservation/payment confirmation, outbox insertion
        R->>DB: COMMIT all allocation writes
        P-->>C: 201 new payment or 200 replay, bookingId and confirmationStatus
        C->>S: POST /api/reservations/id/confirmation - optional retry
        S->>R: Return same booking via owner-scoped confirmation
        R-->>C: 200 original BookingResponse
    else Expired or cancelled
        R->>DB: ROLLBACK TX3
        P->>X: Separate transaction marks RECONCILIATION_REQUIRED
        P-->>C: Payment SUCCESS retained, no booking
    else Booking database failure
        R->>DB: ROLLBACK TX3
        Note over P,DB: SUCCESS remains durable, confirmation remains pending for safe retry
    end
```

The [controllers](../src/main/java/com/example/Tbooker/controller) map requests
through Spring MVC after the filter chain. Login does not use an
`AuthenticationManager` or `UserDetailsService`; protected routes use the built-in
`BearerTokenAuthenticationFilter`, `JwtAuthenticationProvider` and `NimbusJwtDecoder`.
[AuthService](../src/main/java/com/example/Tbooker/service/AuthService.java),
[ReservationService](../src/main/java/com/example/Tbooker/service/ReservationService.java)
and [PaymentResultProcessor](../src/main/java/com/example/Tbooker/service/PaymentResultProcessor.java)
are the relevant policy boundaries. Gateway code is a durable local simulation,
not a remote provider. There is no transaction spanning gateway work or five-minute holds.

## 4. Transactional outbox and recovery

```mermaid
flowchart TD
    TX[Booking transaction] --> Atomic[Commit booking plus unique outbox event atomically]
    Atomic --> Pending[Outbox PENDING]
    Pending --> Claim[Publisher claims with SKIP LOCKED - short transaction]
    Claim --> Flight[IN_FLIGHT with expiring lease and fencing token]
    Flight --> Send[Send outside DB transaction]
    Send --> K[Kafka acknowledgment]
    K --> Mark[Conditional PUBLISHED update matching lease token]
    Send -->|send error| Backoff[Conditional retry scheduling with backoff]
    Backoff --> Pending
    K -->|JVM dies before status commit| Lease[Lease expires, another publisher reclaims]
    Lease --> Flight
    K --> NG[Notification consumer group]
    K --> AG[Analytics consumer group]
    NG --> NT[One DB transaction: unique processed marker plus mock notification]
    AG --> AT[One DB transaction: unique processed marker plus count increment]
    NT & AT --> Offset[Commit Kafka offset after database transaction]
    NG & AG -->|processing failure| Retry[Bounded listener retries]
    Retry -->|exhausted or non-retryable| DLT[Dead-letter topic - acknowledgment required]
    DLT -->|broker unavailable| Retain[Retain source offset for later delivery]
    NT & AT -->|duplicate delivery| Existing[Existing matching marker skips effect]
```

[OutboxPublisher](../src/main/java/com/example/Tbooker/messaging/OutboxPublisher.java)
can publish an event more than once after uncertain acknowledgment. Fencing
protects state updates from stale workers; it cannot undo a Kafka send. Producer
idempotence does not make a database plus broker transaction exactly once.
[BookingEventProcessor](../src/main/java/com/example/Tbooker/service/BookingEventProcessor.java)
commits a deduplication marker and its projection together; a crash before offset
commit can then safely redeliver. Notification and analytics use separate groups
and separate logical marker identities. DLT messages require operator review;
there is no automatic DLT replay or real notification delivery.

## 5. Reservation state machine

```mermaid
stateDiagram-v2
    [*] --> HELD: Claim AVAILABLE inventory and insert new reservation
    HELD --> CONFIRMED: Owner plus verified payment, decision time before expiresAt
    HELD --> CANCELLED: Owner cancels while active
    HELD --> EXPIRED: Database deadline reached
    CONFIRMED --> CONFIRMED: Confirmation retry returns original booking
    CANCELLED --> CANCELLED: Cancellation retry returns 204
    CONFIRMED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
```

These are **reservation** states. The associated `ShowSeat` has `AVAILABLE → HELD →
BOOKED`; active cancellation or expiry releases `HELD → AVAILABLE`. A new hold
creates a new reservation ID; terminal history is not reactivated. Nothing here
cancels a confirmed booking or releases a booked seat.

All writers lock the same inventory row first. The decision clock is read **after
waiting for that lock**, so confirmation and expiration cannot both win. If
confirmation passes its deadline check under lock, its commit may occur after the
wall-clock deadline and still be valid. Reads report logical expiration even
before the scheduler persists it. A rejected write can roll back its attempted
cleanup; the next successful hold or cleanup batch normalizes storage. See
[SeatInventoryService](../src/main/java/com/example/Tbooker/service/SeatInventoryService.java)
and [ReservationCleanup](../src/main/java/com/example/Tbooker/config/ReservationCleanup.java).
