# PRD — Seat Booking Service

## 1. Product Overview

Build a small production-style JSON HTTP service that sells assigned seats for a show such as a concert or movie hall.

The primary objective is **correctness under extreme contention**. At on-sale time, thousands of buyers may concurrently attempt to reserve the same small set of seats. The service is the system of record and must make one atomic decision about ownership of every seat.

The service must also be operable: it must expose health/readiness endpoints, Prometheus-style metrics, structured logs, and a reproducible concurrency-burst client.

This project is evaluated primarily through live behavior under concurrency rather than documentation.

## 2. Goals

The system must guarantee:

1. A seat can never be confirmed for two users.
2. A hot-seat race has exactly one successful winner.
3. Domain declines are returned as HTTP 409, not 5xx.
4. A user's seat limit cannot be exceeded under concurrency.
5. Retried requests with the same idempotency key do not create another booking or charge.
6. The same idempotency key with a different request body is rejected with 409.
7. Seat state remains reconcilable: `available + held + confirmed == total_seats`.
8. Identity comes from authentication, not from client-supplied user IDs.
9. Release/cancellation cannot accidentally resurrect a seat already owned by someone else.
10. The deployed service is observable in real time.

## 3. Scope

### In Scope

- Create a show and its assigned seats.
- Reserve one or more seats.
- Per-user booking limit.
- Idempotent booking requests.
- Explicit booking cancellation.
- Show state and reconciliation counts.
- Authentication/identity derived from a token.
- Liveness endpoint.
- Readiness endpoint with database dependency check.
- Prometheus-style metrics.
- Structured logs with correlation/request ID.
- Dockerized local execution.
- One-command concurrency burst script.
- Live deployment instructions.

### Out of Scope

- UI.
- Payment-provider integration and real money movement.
- Refund processing.
- Email/SMS.
- Seat recommendation/search.
- Distributed cache or event streaming.
- Multi-region active-active deployment.

Money must still be represented in integer minor units (`paise`); never use floating point for money.

## 4. Product Decisions

### 4.1 Partial Multi-Seat Requests

Booking requests are **all-or-nothing**.

If a user requests `A12` and `A13`, and either seat is unavailable, the entire request returns `409` and neither seat is reserved.

### 4.2 Hold/Release Model

Use **explicit cancellation** for the initial version:

```http
POST /bookings/{booking_id}/cancel
```

A successful reserve immediately creates a `confirmed` booking. Cancellation marks it `cancelled` and returns its seats to `available`.

The implementation must make cancellation ownership-safe and atomic.

### 4.3 Per-User Limit

Default:

```text
4 seats per user per show
```

Make the limit configurable per show.

The limit must be evaluated against current active ownership and remain correct under parallel requests from the same user.

## 5. Authentication and Identity

Protected booking/cancellation requests derive identity from the authentication token.

A request must not be able to choose its acting user by putting `user_id` in the request body.

For a challenge deployment, a simple deterministic bearer-token mapping is acceptable, for example:

```http
Authorization: Bearer user-123
```

provided the server treats the authenticated principal as authoritative.

A spoofed body field such as:

```json
{"user_id":"another-user"}
```

must never override the token identity.

## 6. Functional Requirements

### FR-001 — Create Show

`POST /shows` (admin)

Request:

```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3", "A12"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

Return the created show with an id, metadata, and every seat initially `available`.

Reject duplicate seat IDs in the same show.

### FR-002 — Reserve Seats

`POST /shows/{id}/reserve` (authenticated user)

Request:

```json
{
  "seats": ["A12"]
}
```

Send `Idempotency-Key: req-abc-123` as a request header.

Success is HTTP `201`:

```json
{
  "booking_id": 123,
  "show_id": 1,
  "user_id": "user-42",
  "seats": ["A12"],
  "amount_paise": 25000,
  "status": "confirmed"
}
```

Required behavior:

- A seat confirmed for one user can never be confirmed for another.
- A hot-seat race produces one `201`; losers receive clean `409 seat_taken` responses.
- Exceeding the per-user limit is a clean `409 per_user_limit` response.
- Same user + same idempotency key + same request returns the original booking.
- Same user + same idempotency key + different requested seats returns `409` with no state change.
- Multi-seat bookings are atomic and all-or-nothing.

### FR-003 — Cancel Booking

`POST /bookings/{booking_id}/cancel`

Only the authenticated owner may cancel.

Cancellation must atomically release only seats belonging to that booking.

A safe implementation must not be able to release a seat after ownership has changed to another booking.

### FR-004 — Show State

`GET /shows/{id}`

Return:

- show metadata
- total seats
- available/held/confirmed counts
- every seat and its status

The reconciliation invariant is:

```text
available + held + confirmed == total_seats
```

If `held` is not used by the chosen model, it should consistently be `0`.

### FR-005 — Liveness

`GET /health/live`

Indicates process liveness only and should not require the database.

### FR-006 — Readiness

`GET /health/ready`

Must check the required database dependency.

- DB reachable -> HTTP 200.
- DB unavailable -> non-2xx.

Readiness must fail closed.

### FR-007 — Metrics

`GET /metrics`

Prometheus-style output.

Required metrics:

```text
reservations_confirmed_total
reservations_declined_total{reason="seat_taken"}
reservations_declined_total{reason="per_user_limit"}
reservations_declined_total{reason="idempotent_replay"}
seats_available
```

Useful additional metrics:

```text
reservations_declined_total{reason="idempotency_conflict"}
reservations_cancelled_total
reservation_requests_total
reservation_request_duration_seconds
reservation_5xx_total
```

Metrics must represent real events/state and must reconcile with API state.
The required `reservations_*` metric names are retained as the existing monitoring contract even though the domain object is called a booking.

### FR-008 — Structured Logging

Booking requests should log structured fields including:

- timestamp
- level
- request/correlation id
- operation
- show id
- user id when appropriate
- booking id when available
- outcome
- decline reason
- latency when useful

Never log passwords, bearer tokens, database credentials, or secret keys.

## 7. Error Contract

| Scenario | Status |
|---|---:|
| Invalid request | 400 |
| Missing/invalid authentication | 401 |
| Authenticated but not owner/admin | 403 |
| Show/booking not found | 404 |
| Seat already taken | 409 |
| Per-user limit exceeded | 409 |
| Same idempotency key with different body | 409 |
| Unexpected infrastructure/application failure | 500 |

Example:

```json
{
  "error": "seat_taken",
  "message": "Requested seat is already unavailable",
  "request_id": "req-123"
}
```

Expected contention is a domain result, not a server error.

## 8. Correctness Bar

### Hot seat

With one free A12 and 500 different users racing it:

```text
1 x 201
499 x 409 seat_taken
0 x 5xx
```

### 20,000-request burst

The service must maintain:

- no double ownership
- zero 5xx caused by expected contention
- user-limit correctness
- idempotency correctness
- reconciliation

### User limit

A user firing 10 parallel requests against a limit-4 show must finish with at most 4 active owned seats.

### Identity

A token for user A and a spoofed body field for user B can only act as user A.

## 9. Non-Functional Requirements

### Correctness

Seat ownership is strongly consistent.

### Performance

The service should remain responsive during heavy contention. Correctness is more important than maximum throughput.

### Deployment

A clean checkout must build and run. Dockerized local execution must match deployment as closely as practical.

### Security

- Never store plaintext passwords if password authentication is implemented.
- Never expose credentials/tokens through logs or APIs.
- Use parameterized database access.
- Do not hard-code deployment secrets.

## 10. Acceptance Criteria

- [ ] Create-show endpoint creates unique seats in `available` state.
- [ ] Reserve endpoint returns 201 only after successful atomic ownership.
- [ ] Hot-seat contention produces exactly one winner.
- [ ] Expected contention produces 409, not 500.
- [ ] Multi-seat requests are all-or-nothing.
- [ ] Per-user limit holds under concurrent requests.
- [ ] Idempotent retries create no duplicate booking.
- [ ] Same key with different body returns 409 and changes nothing.
- [ ] Cancellation is owner-only and concurrency-safe.
- [ ] Reconciliation invariant holds.
- [ ] Liveness works without DB.
- [ ] Readiness fails when DB is unavailable.
- [ ] Prometheus metrics are exposed and truthful.
- [ ] Structured logs contain correlation IDs.
- [ ] Docker build works from a clean checkout.
- [ ] Burst script reproduces the critical scenarios.

## 11. Deliverables

1. Public Git repository with incremental commit history.
2. Live public URL.
3. Dockerfile/compose as appropriate.
4. One-command burst script.
5. README with local, Docker, deployment, API, metrics, and burst instructions.
6. `WRITEUP.md` covering atomic decision, idempotency, holds/cancellation, consistency vs availability, observability, AI usage, and next steps.
