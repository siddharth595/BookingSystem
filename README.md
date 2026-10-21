# Seat booking service

Spring Boot 4 / PostgreSQL JSON API for assigned seats. The database is the source of truth for bookings and user limits.

## Run locally

Prerequisites: Java 21, PostgreSQL, `curl`, and `jq`. Maven is downloaded by `./mvnw` on first use. Create a `seatbooking` database and a database user with access to it, then set:

```sh
export DATABASE_URL=jdbc:postgresql://localhost:5432/seatbooking
export DATABASE_USER=seatbooking
export DATABASE_PASSWORD='your database password'
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

## Burst check script execution:

> [!NOTE]
> If testing the service hosted on render with hostname https://seatbooking-dxaq.onrender.com , kindly check the status command first which will bring up the Render instance by accessing the url https://seatbooking-dxaq.onrender.com/status in browser or using below curl command from terminal
   ```bash
        curl --location 'https://seatbooking-dxaq.onrender.com/status'
```

### Concurrent Seat Booking Load Test

The `burst.sh` script tests the seat-booking API under concurrent requests by simulating multiple users attempting to reserve the **same seat** on a newly created show. It helps validate reservation atomicity, inventory consistency, and API stability under contention.

#### Prerequisites

Ensure the application is running and the following tools are installed:

- `curl` — API requests
- `jq` — JSON processing
- `xargs` — concurrent request execution
- `bash` — script execution

Seed the application with the configured admin account and user accounts (`user_1`, `user_2`, etc.) before running the test.

### Configuration
Export below variables and then run the script. Note that the application already has 100 dummy users for execution of this script and their usernames and passwords are set in the script itself.

| Variable | Default | Description |
|---|---|---|
| `USER_COUNT` | `100` | Number of user accounts to authenticate |
| `HOT_REQUESTS` | `100` | Total reservation requests |
| `PARALLEL` | `100` | Maximum concurrent requests |
| `HOT_SEAT` | `A1` | Seat targeted by every request |

**Important:** Override the default credentials when they differ from your environment. Avoid committing real credentials to source control.

### How to Run

Make the script executable:

```bash
chmod +x burst.sh
```

Run against a local application:

```bash

HOT_REQUESTS=100 \
PARALLEL=10 \
HOT_SEAT=A1 \
./burst.sh http://localhost:8080
```

Run against the deployed application:

```bash
HOT_REQUESTS=100 \
PARALLEL=10 \
HOT_SEAT=A4 \
./burst.sh https://seatbooking-dxaq.onrender.com
```

### What the Script Does

1. Authenticates the admin and the configured number of seeded users.
2. Creates a new show containing 13 seats.
3. Verifies that the selected seat is initially available.
4. Sends the configured number of concurrent reservation requests for that same seat, distributing requests across user accounts and assigning a unique idempotency key to each request.
5. Collects HTTP responses and summarizes successful bookings, conflicts, rate limits, server errors, and transport errors.
6. Retrieves the final inventory and checks that exactly one reservation was created, the inventory reconciles, the targeted seat is confirmed, and no server or transport errors occurred.

### Expected Results

For 100 requests competing for one available seat, the expected result is:

- Exactly **one HTTP 201** reservation-creation response.
- The remaining **99 requests return HTTP 409** with a seat-conflict reason.
- No HTTP 5xx responses or transport errors.
- Exactly one confirmed seat, with the remaining seats available.
- Final result: `RESULT: PASSED`.

The script exits with a non-zero status if its assertions fail.

### Load Testing Guidance

- Start with a low concurrency level, such as `PARALLEL=10`, and increase it gradually while monitoring application logs, CPU, memory, database connections, and restarts.
- We have seen that the deployed application due to low compute fails when the concurrency is kept as 1000 but does not face issue of double booking. Whereas if the application is deployed in local with the shared Docker compose file, this issue is not seen and can with stand the load.

**Note:** This is a hot-seat contention test, not a general throughput benchmark. Each request uses a distinct idempotency key, so it tests competing reservations rather than replaying the same request. A passing test validates the checked conditions for that run; it does not guarantee production capacity or correctness under every workload.



## Deployment

Build the Dockerfile, provide PostgreSQL and the environment variables above through deployment secrets, expose port 8080, and use `/health/live` and `/health/ready` as liveness and readiness probes. Apply the Liquibase migrations during startup. A public deployment URL has not been configured in this repository.
Also for local execution, use the Docker compose file to deploy both the database and the service together using docker.

This Springboot service is also deployed on Render with the url https://seatbooking-dxaq.onrender.com. The Postgres database is setup on Neon.


## User Seeding

The application seeds 1 ADMIN user and 100 dummy users at startup for enacting the booking scenario. The usernames of the dummy users is of pattern `user_<digit from 1-100>`. Passwords of these 100 seeded users are also same for testing purpose.