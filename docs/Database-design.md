# Database structure - Seat Booking Service

Schema migrations run through Liquibase. Account bootstrap uses environment variables. DDLs are under `src/main/resources/db/changelog/changes/`.

## 1. Tables
### 1. `users`
* **Attributes:**
    * `id` (BIGINT, PK, Generated Identity)
    * `username` (VARCHAR(255), Unique, Not Null)
    * `email` (VARCHAR(255), Unique, Not Null)
    * `password` (VARCHAR(255), Not Null)  -- password should be stored as hash values
    * `role` (VARCHAR(255), Not Null)
    * `contact_no` (VARCHAR(32))
    * `created_at` (TIMESTAMP WITH TIME ZONE)
* **Indexes:**
    * Primary Key on `id`
    * Unique constraints/indexes on `username` and `email`

---

### 2. `shows`
* **Attributes:**
    * `id` (BIGINT, PK, Generated Identity)
    * `name` (VARCHAR(255), Not Null)
    * `price_paise` (INT, Not Null)
    * `total_seats` (INT, Not Null)
    * `per_user_limit` (INT, Not Null, default 4)
    * `created_at` (TIMESTAMP WITH TIME ZONE)
* **Indexes:**
    * Primary Key on `id`

---

### 3. `seats`
* **Attributes:**
    * `seat_id` (VARCHAR(32), Not Null)
    * `show_id` (BIGINT, Not Null)
    * `status` (VARCHAR(16), Not Null)
    * `held_by_user_id` (BIGINT)
    * `booking_id` (BIGINT, nullable FK to `booking.id`; current confirmed owner)
    * `updated_at` (TIMESTAMP WITH TIME ZONE)
    * `created_at` (TIMESTAMP WITH TIME ZONE)
* **Indexes:**
    * Primary Key (Composite) on `(show_id, seat_id)`
    * `idx_seats_show_status` on `(show_id, status)`

---

### 4. `booking`
* **Attributes:**
    * `id` (BIGINT, PK, Generated Identity)
    * `user_id` (BIGINT, Not Null)
    * `show_id` (BIGINT, Not Null)
    * `amount_paise` (INT, Not Null)
    * `status` (VARCHAR(16), Not Null)
    * `created_at` (TIMESTAMP WITH TIME ZONE)
* **Indexes:**
    * Primary Key on `id`
    * `idx_booking_user` on `(user_id)`
    * `idx_booking_show` on `(show_id)`

---

### 5. `booking_seats`
* **Attributes:**
    * `booking_id` (BIGINT, Not Null)
    * `show_id` (BIGINT, Not Null)
    * `seat_id` (VARCHAR(32), Not Null)
* **Indexes:**
    * Primary Key (Composite) on `(booking_id, seat_id)`
    * `idx_booking_seats_seat_lookup` on `(show_id, seat_id)`

---

### 6. `idempotency_keys`
* **Attributes:**
    * `user_id` (BIGINT, Not Null)
    * `idempotency_key` (VARCHAR(128), Not Null)
    * `request_hash` (VARCHAR(64), Not Null)
    * `response_body` (TEXT, Not Null)
    * `created_at` (TIMESTAMP WITH TIME ZONE)
* **Indexes:**
    * Primary Key (Composite) on `(user_id, idempotency_key)`

Seed an ADMIN and USER account from environment variables at startup. Do not commit reusable credentials; password hashes are stored in the users table.

- The API and logs identify users by unique username. The existing booking and idempotency tables retain their numeric `user_id` foreign key to `users.id`.

## Booking integrity migration

`booking-integrity.sql` adds `shows.per_user_limit`, `seats.booking_id`, and `show_user_allocations`. The API writes to the existing `booking`, `booking_seats`, and `idempotency_keys` tables. The migration marks seats referenced by confirmed bookings as confirmed, links seats with exactly one active booking to that booking, and initializes allocation counts from confirmed booking-seat rows. Ambiguous legacy ownership leaves `seats.booking_id` null, so cancellation cannot release that seat without a verified owner.

Initial admin and demo user accounts are created at startup from `ADMIN_USERNAME`/`ADMIN_PASSWORD` and `USER_USERNAME`/`USER_PASSWORD`; passwords are stored as BCrypt hashes. This avoids committing reusable plaintext credentials in a seed migration or text file.
