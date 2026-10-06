package com.project.seatbooking.repository;

import com.project.seatbooking.model.BookingSeat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingSeatRepository {
    private static final String INSERT_BOOKING_SEAT =
            "INSERT INTO booking_seats(booking_id, show_id, seat_id) VALUES (?, ?, ?)";
    private static final String FIND_BY_BOOKING = "SELECT booking_id, show_id, seat_id FROM booking_seats " +
            "WHERE booking_id = ? ORDER BY seat_id";

    private final JdbcTemplate jdbc;

    public BookingSeatRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(long bookingId, long showId, String seatId) {
        jdbc.update(INSERT_BOOKING_SEAT, bookingId, showId, seatId);
    }

    public List<BookingSeat> findByBooking(long bookingId) {
        return jdbc.query(FIND_BY_BOOKING,
                (rs, n) -> new BookingSeat(rs.getLong(1), rs.getLong(2), rs.getString(3)), bookingId);
    }
}
