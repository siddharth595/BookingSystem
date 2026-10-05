# Architecture — Seat Reservation Service

## 1. Design Objective

Use a simple stateless HTTP application backed by one transactional relational database.

The database is the system of record for:

- shows
- seats
- users
- booking
- booking_seats
- idempotency_keys

The key architectural requirement is that **seat ownership and user-limit decisions have a clear serialization point**.
The DDLs for these tables can be found at path `@src/main/resources/db/changelog/changes/initial-tables.sql`
The table structure for these tables can be found in `Database-design.md` file.
`user_id` wherever referenced should be referred to username column and the logs should also refer to it. Dont use the id field in users table for user_id as foreign key. username will be unique accross the system.

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

- parse reservation request
- obtain authenticated principal
- obtain idempotency key
- delegate to BookingService
- map domain outcomes to HTTP

### BookingService

Owns the reservation use case:

- idempotency handling
- user validation from token using spring security
- show and seat validation
- per-user limit
- atomic seat acquisition
- reservation creation
- cancellation

### Repositories/DAOs

- Own persistence access and concurrency primitives.
- Use JdbcTemplate rather than JPA for more control. SQL queries should be fine tuned for the concurrency requirements. It should handle cases where multiple users try to book same seat at one time.

### Observability

Provides:

- metrics
- structured logging
- correlation IDs
- health/readiness

## 4. Recommended Package Structure

For Java/Spring Boot:

```text
com.example.seatbooking
|
+-- controller
|   +-- ShowController
|   +-- BookingController
|   +-- HealthController
|
+-- service
|   +-- ShowService
|   +-- BookingService
|
+-- repository
|   +-- ShowRepository
|   +-- SeatRepository
|   +-- ReservationRepository
|   +-- ReservationSeatRepository
|   +-- IdempotencyKeyRepository
|   +-- ShowUserAllocationRepository
|
+-- Beans
|   +-- Show
|   +-- Seat
|   +-- Booking
|   +-- BookingSeat
|   +-- IdempotencyKey
|   +-- ShowUserAllocation
|
+-- dto
|   +-- CreateShowRequest
|   +-- ReserveRequest
|   +-- ShowResponse
|   +-- ReservationResponse
|   +-- ErrorResponse
|   +-- 
|
+-- exception
|   +-- SeatTakenException
|   +-- UserLimitExceededException
|   +-- IdempotencyConflictException
|   +-- ResourceNotFoundException
|
+-- security
|   +-- AuthenticationFilter
|
+-- observability
|   +-- MetricsService
|   +-- CorrelationIdFilter
|
+-- config
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
updated_at
created_at
```

Primary key:

```text
(show_id, seat_id)
```

This makes a seat unique within a show.

### reservations

```text
reservations
------------
id                 PK
show_id            FK
user_id
amount_paise
status             confirmed | cancelled
created_at
updated_at
```

### reservation_seats

```text
reservation_seats
-----------------
reservation_id     FK
show_id
seat_id

PK:
(reservation_id, seat_id)

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
reservation_id
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
user_id
active_seat_count
per_user_limit

PK:
(show_id, user_id)
```

This row provides a clear serialization point for same-user parallel booking requests.

## 6. Core Invariants

### Invariant 1 — One Seat, One Active Owner

For each `(show_id, seat_id)`, at most one active reservation can own the seat.

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

### Invariant 5 — Reservation/Seat Consistency

A confirmed reservation's seats must be in the corresponding non-available state and must reference the reservation correctly.

## 7. Atomic Reservation Flow

```text
POST /shows/{id}/reserve
          |
          v
ReservationController
          |
          v
ReservationService
          |
          +--> authenticate user
          |
          +--> validate request
          |
          +--> idempotency lookup/insert
          |
          +--> BEGIN TRANSACTION
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
4. Create reservation state.
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
INSERT reservation
```

without serialization.

Recommended mechanism:

1. Lock `(show_id, user_id)` in `show_user_allocations`.
2. Read `active_seat_count`.
3. Verify `active_seat_count + requested_count <= per_user_limit`.
4. Lock requested seats in sorted order.
5. Create reservation and update seat states.
6. Increment the allocation count in the same transaction.
7. Commit.

Parallel requests from the same user therefore see a serialized allocation count.

## 12. Idempotency Design

The logical idempotency identity is:

```text
(authenticated_user_id, idempotency_key)
```
Here user_id should be username as username is unique.
The service stores a deterministic request fingerprint, for example a SHA-256 hash of a canonical representation of:

```text
show_id + sorted seat IDs
```

### Same Key + Same Body

Return the existing reservation/result.

No new seat mutation occurs.

### Same Key + Different Body

Return:

```text
409 idempotency_conflict
```

No reservation mutation occurs.

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
Here user_id is username from users table
as the database serialization point.

One request creates the record; a competing request handles the uniqueness conflict and reads the authoritative stored result.

## 13. Cancellation

Recommended lifecycle:

```text
confirmed --> cancelled
```

Within one transaction:

1. Lock reservation.
2. Verify authenticated owner.
3. Lock its reservation-seat rows/seats as necessary.
4. Verify current ownership still belongs to this reservation.
5. Mark reservation cancelled.
6. Release only those seats.
7. Decrement `active_seat_count`.
8. Commit.

Never blindly set all seats referenced by a cancelled reservation to available without verifying the ownership relationship.

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

Use bounded labels. Do not attach `user_id`, `reservation_id`, `request_id`, or `seat_id` as high-cardinality labels.

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

Readiness must fail when PostgreSQL is unreachable because the service cannot safely make authoritative reservation decisions.

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

The reservation write path prefers consistency over availability.

If PostgreSQL is unavailable, do not accept a speculative booking.

A temporary `503` is preferable to selling the same seat twice.

Readiness should also become unhealthy.

## 20. Architectural Decisions

### ADR-001 — Single PostgreSQL Source of Truth

Use one transactional relational database for authoritative reservation state.

### ADR-002 — Row-Level Locking for Seats

The seat row is the serialization point for ownership decisions.

### ADR-003 — Deterministic Lock Ordering

Lock show-user allocation first and seats sorted by ID to reduce deadlock risk.

### ADR-004 — All-or-Nothing Multi-Seat Reservations

No partial reservation state is committed when any requested seat is unavailable.

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
      "password": "SecurePassword123!"
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
     - { "seats": ["A12"], "idempotency_key": "…" }
   - Response body sample:
     - Success 201:
       { "reservation_id": "…", "show_id": "…", "user_id": "…", "seats": ["A12"], "amount_paise": 25000, "status": "confirmed" }
       Behaviour that must hold:
     - No double-sell: a seat confirmed (or actively held) for one user can never be confirmed for another. A race for the same seat produces exactly one winner; losers get a clean decline (409), never a 500.
   - Identity comes from the auth token, not from the request body. The sub key in the claims of the JWT will carry the username of the user.
   - Auth token will be JWT token generated using API 1 string with below json structure
   - The request names the seat(s) wanted and carries an idempotency key (header)
   - If a user asks for ["A12","A13"] and only one is free, define and document your behaviour (all-or-nothing vs best-effort) — and make it hold under concurrency.

4. Release  a hold
   - This should make the seat available again and should be functional in case the seat booking is not successful at later stages like payment failure etc.
   - This is a basic API which should also function under concurrent request which will make the seat available. This should also go into idempotency table.
5. Show state — GET /shows/{id}
    - Returns per-seat status (available / held / confirmed) and counts. available + held + confirmed == total_seats must hold at all times — this is your reconciliation invariant.

6. Health & metrics

There is no API for user management and users should be seeded with atleast one user having ADMIN role. User management APIs can be future scope.