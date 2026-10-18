# Seat booking service

Spring Boot 4 / PostgreSQL JSON API for assigned seats. The database is the source of truth for bookings and user limits.

## Run locally

Prerequisites: Java 17, PostgreSQL, `curl`, and `jq`. Maven is downloaded by `./mvnw` on first use. Create a `seatbooking` database and a database user with access to it, then set:

```sh
export DATABASE_URL=jdbc:postgresql://localhost:5432/seatbooking
export DATABASE_USER=seatbooking
export DATABASE_PASSWORD='your database password'
export JWT_SECRET="$(openssl rand -hex 32)"
export ADMIN_USERNAME=admin ADMIN_PASSWORD='your admin password'
export USER_USERNAME=demo_user USER_PASSWORD='your user password'
./mvnw spring-boot:run
```

Liquibase runs `initial-tables.sql` and then the additive `booking-integrity.sql` migration at startup. Passwords are BCrypt hashes; if a seeded username already exists, startup does not reset its password. No credential is committed to the repository.

## Docker

Set `DATABASE_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD`, and `USER_PASSWORD` in the environment, then run `docker compose up --build`. Compose starts PostgreSQL and the app on port 8080 (`PORT` overrides the host port). Data persists in the `booking_db` volume.

## API

Obtain a token:

```sh
curl -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"username":"demo_user","password":"<your password>"}'
```

Use `Authorization: Bearer <access_token>` for protected requests. Only the configured admin can create shows. The JWT subject is the acting username; request body `user_id` is ignored.

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/token` | Username/password; returns a one-hour bearer token |
| POST | `/shows` | Admin; `name`, `seats`, `price_paise`, optional `per_user_limit` (default 4) |
| GET | `/shows/{id}` | Show, seats, and reconciliation counts |
| POST | `/shows/{id}/reserve` | User; body `{"seats":["A1"]}` and `Idempotency-Key` header; 201 or 200 replay |
| POST | `/bookings/{id}/cancel` | Owner; releases seats; repeat cancellation is safe; optional `Idempotency-Key` persists the result |
| GET | `/health/live` | Process liveness, independent of PostgreSQL |
| GET | `/health/ready` | PostgreSQL check; 503 when unavailable |
| GET | `/metrics` | Prometheus format |

The reserve response returns `booking_id`. The required Prometheus metric names retain their `reservations_*` prefix for monitoring compatibility.

Expected booking declines are 409 with `seat_taken`, `per_user_limit`, or `idempotency_conflict`. Multi-seat requests are all-or-nothing. Booking/cancellation failures roll back their transactions.

## Burst check

With the service running and the same admin/user environment variables set:

```sh
./burst.sh http://localhost:8080
```

The script creates a fresh show, races one seat, concurrently retries a key, tests the four-seat user limit and all-or-nothing behavior, prints status counts, and exits nonzero on an invariant failure. `HOT_REQUESTS` (default 100) and `PARALLEL` (default 32) control the load.

## Deployment

Build the Dockerfile, provide PostgreSQL and the environment variables above through deployment secrets, expose port 8080, and use `/health/live` and `/health/ready` as liveness and readiness probes. Apply the Liquibase migrations during startup. A public deployment URL has not been configured in this repository.
