# Architecture — Seat Booking Service

## 1. Design Objective

Use a simple stateless HTTP application backed by one transactional relational database.

The database is the system of record for:

- shows
- seats
- users
- booking and booking_seats
- idempotency_keys
- show_user_allocations

The key architectural requirement is that **seat ownership and user-limit decisions have a clear serialization point**.
The DDLs for these tables can be found at path `@src/main/resources/db/changelog/changes/initial-tables.sql`
The table structure for these tables can be found in `Database-design.md` file.
The active booking API uses the existing `booking`, `booking_seats`, and `idempotency_keys` tables. The additive `booking-integrity.sql` migration adds seat ownership and show-user allocation state. Seed users are supplied through environment variables at startup so no reusable credentials are stored in the repository.
The migration initializes allocation counts from confirmed bookings and only assigns `seats.booking_id` when exactly one confirmed booking claims a seat. Ambiguous legacy ownership stays unavailable and cannot be released by cancellation.
The token subject, API `user_id`, and logs use the unique username. Existing database `user_id` columns remain numeric foreign keys to `users.id`; the service resolves the authenticated username to that ID.

## 2. High-Level Architecture

```text
                         Internet
                            |
                            v
                   +------------------+
                   | HTTP API Service  |
                   |                  |
                   | Controllers      |
                   | Services         |
                   | Metrics          |
                   | Logging          |
                   +--------+---------+
                            |
                            v
                   +------------------+
                   |   PostgreSQL     |
                   |                  |
                   | shows            |
                   | seats            |
                   | booking          |
                   | booking_seats    |
                   | idempotency_keys |
                   | users            |
                   +------------------+

              /health/live
              /health/ready
              /metrics
```

A single database is intentional. The challenge is transactional contention over shared seat state; adding Redis/Kafka/distributed locks creates more failure modes without helping the core correctness requirement.

## 3. Components

### ShowController

- create show

### BookingController

- parse booking request
- obtain authenticated principal
- obtain idempotency key
- delegate to BookingService
- map domain outcomes to HTTP

### BookingService

Owns the booking use case:

- idempotency handling
- user validation from token using spring security
- show and seat validation
- per-user limit
- atomic seat acquisition
- booking creation
- cancellation

### Repositories/DAOs

- `UserRepository`, `ShowRepository`, `SeatRepository`, `BookingRepository`, `BookingSeatRepository`, `IdempotencyKeyRepository`, and `ShowUserAllocationRepository` own queries for their respective tables.
- `DatabaseHealthRepository` performs the readiness connectivity query; `SeatRepository` supplies the available-seat metric.
- Services own the transaction boundaries and call explicit repository lock methods in the documented order. Repository calls share the service transaction.

### Observability

Provides:

- metrics
- structured logging
- correlation IDs
- health/readiness

## 4. Package Structure

```text
com.project.seatbooking
|
+-- controller
|   +-- AuthController, ShowController, BookingController, HealthController
|   +-- ApiErrors, BookingMetrics
|
+-- filter
|   +-- CorrelationFilter
|
+-- config
|   +-- SecurityConfig
|
+-- service
|   +-- ShowService, BookingService, TokenService, SeedUsers
|
+-- repository
|   +-- UserRepository, ShowRepository, SeatRepository
|   +-- BookingRepository, BookingSeatRepository, IdempotencyKeyRepository
|   +-- ShowUserAllocationRepository, DatabaseHealthRepository
|
+-- model
|   +-- UserAccount, Show, Seat, Booking, BookingSeat
|   +-- IdempotencyKey, ShowUserAllocation
|
+-- dto
    +-- AuthTokenRequest, AuthTokenResponse, CreateShowRequest
    +-- ShowResponse, SeatResponse, ReserveRequest
    +-- BookingResponse, CancelBookingResponse, ErrorResponse, HealthResponse
```

## 5. Data Model

### shows

```text
shows
-----
id                 PK
name
price_paise
per_user_limit
total_seats
created_at
```

### seats

```text
seats
-----
show_id            PK/FK
seat_id            PK
status             available | held | confirmed
booking_id         FK, current confirmed owner
updated_at
created_at
```

Primary key:

```text
(show_id, seat_id)
```

This makes a seat unique within a show.

### booking

```text
booking
-------
id                 PK
show_id            FK
user_id            FK to users.id
amount_paise
status             confirmed | cancelled
created_at
```

### booking_seats

```text
booking_seats
-------------
booking_id         FK
show_id
seat_id

PK:
(booking_id, seat_id)

FK:
(show_id, seat_id) -> seats(show_id, seat_id)
```

### idempotency_keys

```text
idempotency_keys
----------------
user_id
idempotency_key
request_hash
response_body
created_at

UNIQUE:
(user_id, idempotency_key)
```

### show_user_allocations

Recommended for robust per-user concurrency:

```text
show_user_allocations
---------------------
show_id
user_id            FK to users.id
active_seat_count

PK:
(show_id, user_id)
```

This row provides a clear serialization point for same-user parallel booking requests.

## 6. Core Invariants

### Invariant 1 — One Seat, One Active Owner

For each `(show_id, seat_id)`, at most one active booking can own the seat.

### Invariant 2 — User Limit

For `(show_id, user_id)`:

```text
active_seat_count <= per_user_limit
```

### Invariant 3 — Idempotency

For `(user_id, idempotency_key)` there is at most one idempotency record.

### Invariant 4 — Reconciliation

```text
available + held + confirmed == total_seats
```

### Invariant 5 — Booking/Seat Consistency

A confirmed booking's seats must be in the corresponding non-available state and reference that booking through `seats.booking_id`.

## 7. Atomic Booking Flow

```text
POST /shows/{id}/reserve
          |
          v
BookingController
          |
          v
BookingService
          |
          +--> authenticate user
          |
          +--> validate request
          |
          +--> BEGIN TRANSACTION
          |
          +--> idempotency lookup/insert
          |
          +--> lock show-user allocation row
          |
          +--> lock requested seats, sorted by seat_id
          |
          +--> validate availability
          |
          +--> validate user limit
          |
          +--> create booking
          |
          +--> create booking_seats
          |
          +--> update seat states
          |
          +--> persist idempotency result
          |
          +--> COMMIT
          |
          v
        201/409
```

The exact order of idempotency database operations may be adapted to the selected ORM/SQL implementation, but duplicate concurrent keys must be serialized by a database uniqueness constraint and all resulting state must be atomic.

## 8. Why Read-Then-Write Is Wrong

Incorrect:

```text
SELECT seat WHERE status = available

if available:
    UPDATE seat SET status = confirmed
```

Two concurrent transactions can both observe the same state before either commits.

Correctness requires that the database serialize the ownership decision.

## 9. Seat Concurrency Strategy

Use PostgreSQL row-level locking, conceptually:

```sql
BEGIN;

SELECT *
FROM seats
WHERE show_id = ?
  AND seat_id IN (?, ?)
ORDER BY seat_id
FOR UPDATE;
```

Then:

1. Verify all requested seats exist.
2. Verify every requested seat is available.
3. Verify the user's limit.
4. Create booking state.
5. Update seats.
6. Commit.

If a seat is unavailable, roll back and return 409.

For a hot-seat race, one transaction gets the lock first. The winner commits the state transition. Waiting transactions subsequently see the seat as unavailable and return `409 seat_taken`.

## 10. Multi-Seat Lock Ordering

Multi-seat requests are all-or-nothing.

Always normalize and sort requested seat IDs before locking.

Example:

```text
request: [A13, A12]
lock:    [A12, A13]
```

All booking transactions use the same order.

Recommended global lock order:

```text
1. show-user allocation row
2. seat rows sorted by seat_id
```

This prevents common lock-order inversions and materially reduces deadlock risk.

## 11. Per-User Limit Under Concurrency
a user cannot hold more than per_user_limit seats for a show (default 4). Over-limit is a clean decline, not an error.
Avoid:

```text
COUNT active seats
if count < limit
INSERT booking
```

without serialization.

Recommended mechanism:

1. Lock `(show_id, user_id)` in `show_user_allocations`.
2. Read `active_seat_count`.
3. Verify `active_seat_count + requested_count <= per_user_limit`.
4. Lock requested seats in sorted order.
5. Create booking and update seat states.
6. Increment the allocation count in the same transaction.
7. Commit.

Parallel requests from the same user therefore see a serialized allocation count.

## 12. Idempotency Design

The logical idempotency identity is:

```text
(authenticated_user_id, idempotency_key)
```
The token username resolves to `users.id` before accessing the existing numeric-key table.
The service stores a deterministic request fingerprint, for example a SHA-256 hash of a canonical representation of:

```text
show_id + sorted seat IDs
```

### Same Key + Same Body

Return the existing booking/result.

No new seat mutation occurs.

### Same Key + Different Body

Return:

```text
409 idempotency_conflict
```

No booking mutation occurs.

### Concurrent Same-Key Requests

Do not rely solely on:

```text
SELECT key
```

followed by an unchecked insert.

Use:

```text
UNIQUE(user_id, idempotency_key)
```
Here `user_id` is the numeric ID of the authenticated username. The composite primary key is the database serialization point.

One request creates the record; a competing request handles the uniqueness conflict and reads the authoritative stored result.

## 13. Cancellation

Recommended lifecycle:

```text
confirmed --> cancelled
```

Within one transaction:

1. Lock booking.
2. Verify authenticated owner.
3. Lock its booking-seat rows/seats as necessary.
4. Verify current ownership still belongs to this booking.
5. Mark booking cancelled.
6. Release only those seats.
7. Decrement `active_seat_count`.
8. Commit.

Never blindly set all seats referenced by a cancelled booking to available without verifying the ownership relationship.

## 14. Show State / Reconciliation

`GET /shows/{id}` should use the authoritative seat table.

A correctness-first query is conceptually:

```sql
SELECT status, COUNT(*)
FROM seats
WHERE show_id = ?
GROUP BY status;
```

Then validate:

```text
sum(status counts) == total_seats
```

The response must not silently invent counts if the stored state is inconsistent.

## 15. Observability

### Metrics

Required:

```text
reservations_confirmed_total
reservations_declined_total{reason="seat_taken"}
reservations_declined_total{reason="per_user_limit"}
reservations_declined_total{reason="idempotent_replay"}
seats_available
```

The `reservations_*` metric names remain for monitoring compatibility; API and database terminology uses booking.

Use bounded labels. Do not attach `user_id`, `booking_id`, `request_id`, or `seat_id` as high-cardinality labels.

### Logging

Use JSON/structured logs where practical.

Each request receives or propagates a request/correlation ID.

### Health

```text
GET /health/live
```

Process health only.

```text
GET /health/ready
```

Required dependencies healthy, especially database connectivity.

Readiness fails closed.

## 16. Deployment

Recommended deployment topology:

```text
Public URL
    |
    v
Containerized App
    |
    v
Managed PostgreSQL
```

The application should remain stateless so it can scale horizontally:

```text
             Load Balancer
                  |
          +-------+-------+
          |       |       |
         App     App     App
          |       |       |
          +-------+-------+
                  |
              PostgreSQL
```

Never store authoritative seat ownership only in process memory.

## 17. Health/Readiness Semantics

Liveness must remain useful when PostgreSQL is unavailable.

Readiness must fail when PostgreSQL is unreachable because the service cannot safely make authoritative booking decisions.

## 18. Burst Test Design

A `burst.sh` client should:

1. Create a fresh show.
2. Generate or authenticate many users.
3. Run a hot-seat storm.
4. Run same-key concurrent retries.
5. Run same-user limit contention.
6. Exercise multi-seat all-or-nothing behavior.
7. Query final state.
8. Print 201 count, 409 counts by reason, 5xx count, and reconciliation.
9. Exit non-zero when an invariant fails.

## 19. Consistency vs Availability

The booking write path prefers consistency over availability.

If PostgreSQL is unavailable, do not accept a speculative booking.

A temporary `503` is preferable to selling the same seat twice.

Readiness should also become unhealthy.

## 20. Architectural Decisions

### ADR-001 — Single PostgreSQL Source of Truth

Use one transactional relational database for authoritative booking state.

### ADR-002 — Row-Level Locking for Seats

The seat row is the serialization point for ownership decisions.

### ADR-003 — Deterministic Lock Ordering

Lock show-user allocation first and seats sorted by ID to reduce deadlock risk.

### ADR-004 — All-or-Nothing Multi-Seat Bookings

No partial booking state is committed when any requested seat is unavailable.

### ADR-005 — Database-Backed Idempotency

Persist keys and fingerprints in the database with a unique constraint.

### ADR-006 — Stateless Application

No authoritative ownership state lives only in memory.

### ADR-007 — Integer Paise

All money values use integer minor units.

## 21. API Design
1.  POST /auth/token
    - Authenticates a user using their credentials and returns a cryptographically signed JSON Web Token (JWT) used for subsequent stateless authorization across protected endpoints.
    - Request body sample json
    ```json
      {
      "username": "john_doe",
      "password": "<your password>"
      }
     ```
    - Returned when credentials are valid. Credentials are verified from users table. The response provides a signed bearer token along with expiration details.
      - The claims of the JWT should have this
        ```json
          {
          "sub": "john_doe",
          "role": "ROLE_USER",
          "iat": 1775380000,
          "exp": 1775383600
          } ```
2. POST /shows 
    - Request body sample
      - { "name": "friday-night", "seats": ["A1","A2","A3","..."], "price_paise": 25000 }
    - Response 
      - Returns the created show with an id and every seat in "available" state.
    - Only user with ADMIN role can perform this operation. The role handling and auth should be done using Spring security.

3. Reserve a seat — POST /shows/{id}/reserve (authenticated user)
   - Request body sample:
     - { "seats": ["A12"] }
   - Response body sample:
     - Success 201:
       { "booking_id": "…", "show_id": "…", "user_id": "…", "seats": ["A12"], "amount_paise": 25000, "status": "confirmed" }
       Behaviour that must hold:
     - No double-sell: a seat confirmed (or actively held) for one user can never be confirmed for another. A race for the same seat produces exactly one winner; losers get a clean decline (409), never a 500.
   - Identity comes from the auth token, not from the request body. The sub key in the claims of the JWT will carry the username of the user.
   - Auth token will be JWT token generated using API 1 string with below json structure
   - The request names the seat(s) wanted and carries an `Idempotency-Key` header.
   - If a user asks for ["A12","A13"] and only one is free, the request is declined without booking either seat.

4. Cancel a booking — `POST /bookings/{booking_id}/cancel`
   - Only the authenticated owner can cancel. Cancellation releases seats only when they are still owned by that booking. A repeated cancellation returns the cancelled state without releasing seats again. When an `Idempotency-Key` header is supplied, the cancellation result is stored in `idempotency_keys`.
5. Show state — GET /shows/{id}
    - Returns per-seat status (available / held / confirmed) and counts. available + held + confirmed == total_seats must hold at all times — this is your reconciliation invariant.

6. Health & metrics

There is no API for user management and users should be seeded with atleast one user having ADMIN role. User management APIs can be future scope.
