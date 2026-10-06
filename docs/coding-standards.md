# Coding Standards — Seat Booking Service

## 1. Principles

1. Correctness before micro-optimization.
2. Prefer explicit concurrency behavior over clever abstractions.
3. Keep transaction boundaries easy to identify.
4. Keep controllers thin.
5. Enforce critical invariants in both application logic and the database where practical.
6. Follow existing project conventions.
7. Do not change unrelated code.

## 2. Money

Use integer paise only:

```java
long amountPaise;
```

Do not use `double` or `float` for money.

The server computes the authoritative booking amount.

## 3. Identity

Protected operations use the authenticated principal from the token.

Never trust a request-body `user_id` to choose the acting user.

Do not let a spoofed user field override authentication context.

## 4. Controllers

Controllers should:

- parse and validate HTTP input
- read path/header parameters
- obtain the authenticated principal
- call a service
- map domain results/errors to HTTP

Controllers must not:

- implement seat locking
- execute read-then-write booking logic
- calculate authoritative booking amount
- maintain idempotency in an in-memory map
- contain complex transaction logic

## 5. Services

Use-case services own business orchestration.

Booking service responsibilities include:

- show/seat validation
- idempotency
- per-user limit
- transactional booking
- amount calculation
- cancellation

The transaction boundary should be clear, e.g.:

```java
@Transactional
public BookingResponse reserve(...) {
    // atomic state transition
}
```

Do not scatter one logical transaction across unrelated service methods.

## 6. Concurrency Rules

Never implement seat allocation as an unprotected read-then-write.

Unsafe:

```text
read seat
if available
write seat confirmed
```

Use the concurrency mechanism documented in `Architecture.md`.

For the recommended strategy:

```text
lock show-user allocation
lock requested seats in sorted order
validate
mutate
commit
```

All booking writers must follow the same discipline.

## 7. Deadlock Avoidance

For multi-seat requests:

1. Reject or normalize duplicates deterministically.
2. Sort seat IDs.
3. Acquire locks in sorted order.

Do not depend on client input order.

## 8. Per-User Limit

Do not perform an unlocked:

```text
COUNT -> compare -> INSERT
```

for the booking-limit decision.

Use the transactional show-user allocation strategy or another documented serialization mechanism.
Keep the default limit for max number of seats to 4. If more than 4 seat bookings come, reject the request with appropriate message.

## 9. Idempotency

Canonicalize the booking intent before hashing.

At minimum, the fingerprint should represent:

```text
show_id + sorted seat IDs
```

Database uniqueness is required for:

```text
(user_id, idempotency_key)
```

Rules:

```text
same key + same request -> replay original result
same key + different request -> 409
```

Do not silently overwrite an existing key.

## 10. Transactions

A successful booking must atomically cover all relevant state transitions:

- idempotency record/result
- seat ownership/state
- booking
- booking-seat rows
- user/show active allocation count

Unexpected database errors must roll back.

Do not swallow persistence exceptions and commit partial state.

## 11. Database

Use:

- primary keys
- foreign keys
- unique constraints
- check constraints where useful
- indexes based on actual query paths

All schema changes go through Liquibase.

Never modify an already-applied migration merely to change its historical meaning. 
Data model and design can be found in Database-Design.md file.

## 12. Liquibase

Formatted SQL should follow:

```sql
--liquibase formatted sql

--changeset author:001
CREATE TABLE ...;
```

Every changeset ID must be unique.

Validate migrations from a clean checkout.

## 13. SQL

Execute all application SQL through repository classes. Services coordinate business decisions and transactions; controllers and metrics call repositories when they need database reads.

Use prepared/parameterized queries.

Never concatenate untrusted user input into SQL.

Concurrency-sensitive repository methods should make their lock behavior explicit.

## 14. API

Use JSON.

Recommended status codes:

```text
201 Created
400 Bad Request
401 Unauthorized
403 Forbidden
404 Not Found
409 Conflict
500 Internal Server Error
503 Service Unavailable
```

Expected contention must not be mapped to 500.

Errors should contain a stable machine-readable/human-readable reason and request ID.

Example:

```json
{
  "error": "seat_taken",
  "message": "Requested seat is already unavailable",
  "request_id": "req-123"
}
```

## 15. Validation

Validate at the API boundary:

- required fields
- non-empty seat list
- duplicate seats
- valid seat identifiers
- positive per-user limit
- non-negative price
- show name
- authentication

Business state checks still belong inside the transaction/service layer.

## 16. Logging

Use structured logs where possible.

Every HTTP request gets a correlation/request ID.

Booking logs should allow investigation of:

- show
- authenticated user
- booking
- outcome
- reason
- latency

Never log credentials, bearer tokens, password material, or secrets.

## 17. Metrics

Required metrics:

```text
reservations_confirmed_total
reservations_declined_total{reason="seat_taken"}
reservations_declined_total{reason="per_user_limit"}
reservations_declined_total{reason="idempotent_replay"}
seats_available
```

Use bounded labels only. Never use user/seat/booking/request IDs as metric labels.

## 18. Tests

Tests are not included in the scope.

## 19. Docker

The container must:

- build from a clean checkout
- receive configuration through environment variables
- expose its configured port
- avoid bundled credentials
- use docker compose which should also spin up the database and its configurations should be handled dynamically.

Local Docker behavior should closely match deployment behavior.

## 20. Dependencies

Before adding a dependency:

1. Search the existing project.
2. Prefer existing framework/JDK functionality where practical.
3. Check compatibility/security/maintenance.
4. Avoid adding infrastructure that is not required by the correctness model.
5. Use json databind and Lombok dependencies to reduce boiler plate code for Beans and DTOs.

Do not introduce Redis/Kafka/distributed locks merely to appear production-like.

## 21. Code Review Checklist

Before considering a change complete, ask:

- What serializes the ownership decision?
- Can two transactions both win?
- Can same-user requests both pass the limit check?
- Can same-key retries create two bookings?
- Can same-key different-body requests change state?
- Can cancellation release a seat after reassignment?
- Can DB failure become a false success?
- Are domain conflicts 409 rather than 500?
- Do API counts and metrics agree?
- Are metric labels bounded?
- Is identity token-derived?

## 22. Definition of Done

- [ ] Requirement implemented.
- [ ] Liquibase migration validated if schema changed.
- [ ] Expected contention does not produce 5xx.
- [ ] Metrics represent actual behavior.
- [ ] Logs have correlation IDs.
- [ ] Liveness/readiness semantics are correct.
- [ ] Docker build works.
- [ ] No secrets committed.
- [ ] No unrelated refactor.

## 23. Request/Response DTOs
- Create proper Request/Response DTOs wherever required. Also create the beans for interacting with the database.
- The code should be extensible and human readable.

## 24. Idempotency
- Idempotency should be captured from the header of the request.
- Each successful request should add the idempotency id and the request / response in the idempotency table.

## 25. Code conduct
- Code should be extensible and keeping the future addition of a redis lock based system.
- Use design patterns wherever required and necessary.
- Commenting should be not extensively and should be low.
- Keep the code compact and use modern standards like java streams etc. Actions should be performed with the goal in mind for each step.
