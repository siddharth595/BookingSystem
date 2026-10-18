--liquibase formatted sql

--changeset booking:booking-integrity-1
ALTER TABLE shows
    ADD COLUMN per_user_limit INT NOT NULL DEFAULT 4 CHECK (per_user_limit > 0);
ALTER TABLE seats
    ADD COLUMN booking_id BIGINT REFERENCES booking (id);
CREATE TABLE show_user_allocations
(
    show_id           BIGINT NOT NULL REFERENCES shows (id),
    user_id           BIGINT NOT NULL REFERENCES users (id),
    active_seat_count INT    NOT NULL DEFAULT 0 CHECK (active_seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);

--changeset booking:booking-integrity-2
UPDATE seats s
SET status = 'confirmed' FROM booking_seats bs JOIN booking b
ON b.id = bs.booking_id
WHERE b.status = 'confirmed' AND s.show_id = bs.show_id AND s.seat_id = bs.seat_id;

--changeset booking:booking-integrity-3
WITH owners AS (SELECT bs.show_id, bs.seat_id, MIN(bs.booking_id) AS booking_id, COUNT(*) AS owner_count
                FROM booking_seats bs
                         JOIN booking b ON b.id = bs.booking_id
                WHERE b.status = 'confirmed'
                GROUP BY bs.show_id, bs.seat_id)
UPDATE seats s
SET booking_id = owners.booking_id FROM owners
WHERE s.show_id = owners.show_id AND s.seat_id = owners.seat_id AND owners.owner_count = 1;

--changeset booking:booking-integrity-4
INSERT INTO show_user_allocations(show_id, user_id, active_seat_count)
SELECT b.show_id, b.user_id, COUNT(*) ::INT
FROM booking b
         JOIN booking_seats bs ON bs.booking_id = b.id
WHERE b.status = 'confirmed'
GROUP BY b.show_id, b.user_id;
