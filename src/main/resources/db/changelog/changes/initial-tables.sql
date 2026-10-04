--liquibase formatted sql

--changeset siddharth:10-2026-ddl-1
CREATE TABLE users (
   id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
   user_name VARCHAR(255) NOT NULL,
   email VARCHAR(255) UNIQUE NOT NULL,
   password VARCHAR(255) NOT NULL,
   contact_no VARCHAR(32),
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

--changeset siddharth:10-2026-ddl-2
CREATE TABLE shows (
   id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
   name VARCHAR(255) NOT NULL,
   price_paise INT NOT NULL,
   total_seats INT NOT NULL,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

--changeset siddharth:10-2026-ddl-3
CREATE TABLE seats (
   seat_id VARCHAR(32) NOT NULL, -- e.g., 'A1', 'A2'
   show_id BIGINT NOT NULL,
   status VARCHAR(16) NOT NULL DEFAULT 'available', -- 'available', 'held', 'confirmed'
   held_by_user_id BIGINT,
   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

   PRIMARY KEY (show_id, seat_id),
   CONSTRAINT fk_seats_shows FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE,
   CONSTRAINT fk_seats_users FOREIGN KEY (held_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX idx_seats_show_status ON seats(show_id, status);

--changeset siddharth:10-2026-ddl-4
CREATE TABLE booking (
     id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     user_id BIGINT NOT NULL,
     show_id BIGINT NOT NULL,
     amount_paise INT NOT NULL,
     status VARCHAR(16) NOT NULL, -- 'confirmed', 'cancelled'
     created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

     CONSTRAINT fk_booking_users FOREIGN KEY (user_id) REFERENCES users(id),
     CONSTRAINT fk_booking_shows FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE
);
CREATE INDEX idx_booking_user ON booking(user_id);
CREATE INDEX idx_booking_show ON booking(show_id);

--changeset siddharth:10-2026-ddl-5
CREATE TABLE booking_seats (
   booking_id BIGINT NOT NULL,
   show_id BIGINT NOT NULL,
   seat_id VARCHAR(32) NOT NULL,

   PRIMARY KEY (booking_id, seat_id),
   CONSTRAINT fk_booking_seats_booking FOREIGN KEY (booking_id) REFERENCES booking(id) ON DELETE CASCADE,
   CONSTRAINT fk_booking_seats_seat FOREIGN KEY (show_id, seat_id) REFERENCES seats(show_id, seat_id) ON DELETE CASCADE
);

CREATE INDEX idx_booking_seats_seat_lookup ON booking_seats(show_id, seat_id);

--changeset siddharth:10-2026-ddl-6
CREATE TABLE idempotency_keys (
      user_id BIGINT NOT NULL,
      idempotency_key VARCHAR(128) NOT NULL,
      request_hash VARCHAR(64) NOT NULL,
      response_body TEXT NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

      PRIMARY KEY (user_id, idempotency_key),
      CONSTRAINT fk_idempotency_users FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

