package com.project.seatbooking.repository;

import com.project.seatbooking.model.Booking;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {
    private static final String INSERT_BOOKING = "INSERT INTO booking(show_id, user_id, amount_paise, status) " +
            "VALUES (?, ?, ?, 'confirmed') RETURNING id";
    private static final String LOCK_BOOKING = "SELECT id, show_id, user_id, status FROM booking WHERE id = ? FOR UPDATE";
    private static final String CANCEL_BOOKING = "UPDATE booking SET status = 'cancelled' WHERE id = ?";

    private final JdbcTemplate jdbc;

    public BookingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long create(long showId, long userId, long amountPaise) {
        return jdbc.queryForObject(INSERT_BOOKING, Long.class, showId, userId, amountPaise);
    }

    public Optional<Booking> lock(long id) {
        return jdbc.query(LOCK_BOOKING,
                (rs, n) -> new Booking(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)), id)
                .stream().findFirst();
    }

    public void cancel(long id) {
        jdbc.update(CANCEL_BOOKING, id);
    }
}
