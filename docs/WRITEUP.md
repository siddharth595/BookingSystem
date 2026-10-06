# WRITEUP — Seat Booking Service

## 1. Atomic Decision

The authoritative ownership decision is made inside a PostgreSQL transaction.

The implementation uses the existing `booking`, `booking_seats`, and `idempotency_keys` tables. It locks the `(show_id, user_id)` allocation row first, then requested seat rows in deterministic `seat_id` order. It validates the current state while those locks are held and only then creates the booking, booking-seat rows, and updates seat state before commit.

This prevents a read-then-write race. For a hot seat, one transaction becomes the serialization winner; subsequent transactions observe the committed unavailable state and return `409 seat_taken`.

## 2. Multi-Seat / Deadlock Strategy

Multi-seat requests are all-or-nothing. Requested seat IDs are normalized and sorted before locks are acquired.

A global lock order of:

```text
show-user allocation -> sorted seat rows
```

means overlapping requests do not acquire the same locks in opposite orders.

## 3. Per-User Limit

The limit cannot be implemented safely as an unlocked `COUNT` followed by an insert.

A dedicated show-user allocation row is used as a serialization point for the user's active seat count. The count check, booking creation, seat updates, and count increment occur in one transaction.

Therefore parallel requests from one user cannot race past the configured limit.

## 4. Idempotency

Idempotency is stored in PostgreSQL using:

```text
(user_id, idempotency_key)
```

with a unique database constraint.

A deterministic fingerprint represents the booking intent, including the show and normalized seat list.

Same key + same body returns the original booking.

Same key + different body returns `409 idempotency_conflict` without changing booking state.

The database uniqueness constraint is also required for two concurrent identical requests that both arrive before either has created an idempotency record.

## 5. Holds / Release

The initial design uses explicit cancellation rather than automatic expiry.

A successful booking is immediately `confirmed`.

Cancellation is owner-only and runs transactionally. The implementation verifies that the seats still belong to the booking before releasing them. It never blindly changes a seat to `available`.

## 6. Consistency vs Availability

The booking write path prioritizes consistency over availability.

If PostgreSQL is unavailable, the service must not make an in-memory/speculative ownership decision. Returning an infrastructure failure is preferable to double-selling a seat.

Readiness therefore fails when the required database dependency is down.

## 7. Observability

The service exposes:

- `/health/live`
- `/health/ready`
- `/metrics`

The main operational metrics are:

```text
reservations_confirmed_total
reservations_declined_total
seats_available
```

Declines are classified with bounded reasons such as `seat_taken`, `per_user_limit`, and `idempotent_replay`.

Structured logs include correlation/request IDs so a burst can be traced from request to outcome.

## 8. What I Would Page for at 2am

- Sustained non-zero 5xx rate.
- Database connectivity/readiness failures.
- Booking latency degradation.
- Deadlock/serialization failures above baseline.
- Any mismatch between seat metrics and API reconciliation.
- Unexpected idempotency conflicts or state anomalies.
- Evidence that confirmed-seat state is not reconciling to the authoritative seat table.

Expected contention should appear as 409s, not as application errors.

## 9. AI Usage

AI tools may be used for scaffolding, documentation, test-case generation, code review prompts, and implementation suggestions.

The concurrency design, transaction boundaries, idempotency semantics, and correctness properties must be understood and validated by the developer rather than accepted blindly from generated code.

The final submission should disclose the specific AI usage honestly.

For this implementation, Codex generated the service, migration, API, Docker, and burst script changes. An offline Maven compile was run; package/Docker builds and live concurrency properties still require verification in an environment with the required dependencies and PostgreSQL.

## 10. Next Steps

Beyond the challenge, I would consider:

- stronger authentication/authorization
- temporary seat holds if product requirements need them
- payment integration with payment-level idempotency
- distributed tracing
- load testing in CI
- property-based/concurrency testing
- database read scaling only after measurements justify it
- formal SLOs and alert thresholds
