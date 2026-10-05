# Database structure - Seat Reservation Service

`Seeded Scripts should be run only via liquibase. The DDLs are listed at path src/main/resources/db/changelog/changes/initial-tables/sql`

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

Add some dummy users in the users table using another liquibase migration script. The username and password details should be stored in a text file for future reference. Atleast one user should have ADMIN role.

- Wherever user_id is considered, use the username as its unique across the system. Username can be alphanumeric value.