# AGENTS.md — Coding Agent Instructions

## 1. Mission

You are implementing an event seat reservation service that will be tested with high-concurrency bursts against a live deployment.

The most important properties are:

1. No seat double-sell.
2. No per-user-limit oversubscription.
3. No duplicate reservation caused by retries.
4. No unsafe cancellation/release.
5. No 5xx for expected contention.
6. Reconciliation always holds.
7. Health/readiness/metrics/logs are truthful.

Treat correctness under concurrency as a first-class requirement.

## 2. Required Reading

Before significant changes, read:

1. `PRD.md`
2. `Architecture.md`
3. `coding-standards.md`
4. `Database-design.md`

The running behavior must agree with these documents.

## 3. Requirement Priority

Resolve conflicts using:

```text
Explicit current task requirement
    >
PRD.md
    >
Architecture.md
    >
coding-standards.md
    >
existing conventions
```

If a deliberate architecture change is made, update the architecture documentation.

## 4. Repository Exploration

Before editing:

- inspect the repository tree
- inspect build configuration
- inspect DB configuration
- inspect Liquibase migrations
- locate controllers/services/repositories/beans
- inspect relevant tests
- inspect Docker/deployment files
- inspect README and burst script
- search for existing implementations before creating new ones

Do not guess what already exists.

## 5. Mandatory Rule — No Read-Then-Write Booking

Never implement:

```text
SELECT seat
if available
UPDATE seat to confirmed
```

without transactional concurrency protection.

The ownership decision must have a documented serialization point.

Use the mechanism in `Architecture.md`.

## 6. Mandatory Rule — Lock Ordering

For multi-seat reservations:

```text
sort seat IDs
lock in sorted order
```

Recommended global order:

```text
show-user allocation
then seats sorted by seat ID
```

Do not allow different code paths to acquire the same set of locks in different orders.

## 7. Mandatory Rule — All-or-Nothing

If a request asks for:

```text
[A12, A13]
```

and A13 is unavailable, do not reserve A12.

The whole operation must return 409 and leave no partial reservation state.

## 8. Mandatory Rule — User Limit

Never use an unlocked count as the authority for the per-user limit.

Unsafe:

```text
count seats
if count < limit
insert
```

Use a transaction with a serialized show-user allocation row or another rigorously correct strategy.

Parallel requests from one user must never finish with more active seats than the configured limit.

## 9. Mandatory Rule — Idempotency

Idempotency identity:

```text
authenticated user + idempotency key
```

Store a deterministic request fingerprint.

Same key + same fingerprint:

```text
return original reservation/result
```

Same key + different fingerprint:

```text
409 idempotency_conflict
```

No state change for the conflict.

Use a database unique constraint to protect against concurrent creation of the same key.

## 10. Mandatory Rule — Identity

Authenticated identity is authoritative. Identity should be fetched from the token. The token need not be JWT token but a simple base64 encoded token will also do for this demo.

Never allow a body field such as:

```json
{"user_id":"someone-else"}
```

to override the token's identity.

Do not add an impersonation fallback for convenience.

## 11. Mandatory Rule — Cancellation Safety

Cancellation must verify ownership before releasing seats.

Never blindly set a referenced seat to `available` without verifying it is still owned by the reservation being cancelled.

Use transaction/locking appropriate to the architecture.

## 12. Domain Errors

Expected competition is normal:

```text
seat_taken       -> 409
per_user_limit   -> 409
idempotency_conflict -> 409
```

Do not map normal contention to 500.

Do not catch every exception and convert every failure into 409.

Real infrastructure/application failures may be 5xx.

## 13. Database Is the Source of Truth

Do not keep authoritative seat ownership in process-local memory.

Avoid correctness dependencies on:

- static maps
- local caches
- single-process locks

The design must remain correct after restart and with multiple application instances.

## 14. Transaction Rules

A successful reserve must atomically update all required state:

```text
idempotency
seat ownership
reservation
reservation seats
user/show active allocation
```

Do not commit partial state.

Do not swallow transaction exceptions.

## 15. Observability Rules

Required metrics must exist and be real:

```text
reservations_confirmed_total
reservations_declined_total{reason="seat_taken"}
reservations_declined_total{reason="per_user_limit"}
reservations_declined_total{reason="idempotent_replay"}
seats_available
```

Do not use high-cardinality identifiers as metric labels.

Every request should receive/propagate a correlation ID and write structured logs.

## 16. Health Rules

### Liveness

`GET /health/live` should reflect process liveness only and should not require PostgreSQL.

### Readiness

`GET /health/ready` must verify required DB connectivity.

When DB is unavailable:

```text
readiness != healthy
```

Do not fake readiness.

## 17. Testing Rules

Tests are not in the project scope.

## 18 Reconciliation while developing the system

Expected:

```text
available + held + confirmed == total_seats
```

## 19. Burst Script

Maintain a one-command test such as:

```bash
./burst.sh <BASE_URL>
```


The script should:

1. create a fresh show
2. storm a hot seat
3. test idempotent retries
4. test same-user limits
5. query final state
6. print 201/409/5xx outcomes
7. print reconciliation
8. exit non-zero on invariant failure

When reservation semantics change, update the burst test.

## 20. README Rules

Keep README accurate and document:

- prerequisites
- local run
- environment variables
- Docker run
- migrations
- authentication
- APIs
- `/health/live`
- `/health/ready`
- `/metrics`
- burst command
- deployment
- live URL when available

Do not document untested commands as working.

## 21. Docker/Deployment

A clean clone must build without personal machine state.

Configuration must come from environment variables or deployment secrets.

Do not commit credentials, private keys, API tokens, or DB passwords.

## 22. Dependency Rules

Before adding a dependency:

1. Search the repository.
2. Check JDK/framework capability.
3. Check compatibility/security.
4. Assess operational cost.
5. Add only if it materially helps.

Do not add Redis/Kafka/distributed locking just because the project is a concurrency challenge.

## 23. Change Scope

Keep changes focused.

Do not:

- rewrite unrelated modules
- change frameworks during feature work
- remove tests to make the build green
- weaken locking because it appears faster
- bypass database constraints
- change public API semantics accidentally

## 24. Documentation Updates

When implementation changes:

- product behavior -> update `PRD.md`
- architecture/concurrency -> update `Architecture.md`
- coding rule -> update `coding-standards.md`
- run/deploy/API -> update `README.md`
- database structure -> update `Database-design.md`
- challenge explanation -> update `WRITEUP.md`

Keep the documents consistent with the running service.

## 25. Final Verification

Before reporting completion:

```text
[ ] Clean build succeeds
[ ] Hot-seat exactly-one-winner property verified
[ ] Per-user limit verified under concurrency
[ ] Idempotency verified under concurrency
[ ] Same-key/different-body verified
[ ] Cancellation ownership verified
[ ] Reconciliation verified
[ ] Metrics match observed state
[ ] Correlation IDs appear in logs
[ ] Liveness works without DB
[ ] Readiness fails when DB fails
[ ] Docker build works
[ ] Burst script works
[ ] No secrets committed
[ ] Final diff contains no unrelated changes
```

## 26. Final Response Discipline

When completing a task, report:

### Changes

What changed.

### Correctness

Which concurrency/idempotency invariants were verified.

### Observability

Relevant health/metrics/log changes.

### Assumptions

Only meaningful assumptions.

Never claim a test, deployment, or live URL was verified unless it actually was.

## 26. Agent Mindset

For every shared-state operation, ask:

```text
Where is the atomic decision?
What serializes it?
What database constraint backs it?
What happens to losers?
What happens on retry?
What happens if the process crashes?
What happens if the database is unavailable?
```

The evaluator will test the running behavior, so correctness must exist in the implementation rather than only in comments or documentation.
