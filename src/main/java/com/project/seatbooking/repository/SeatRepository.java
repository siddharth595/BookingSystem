package com.project.seatbooking.repository;

import com.project.seatbooking.model.Seat;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeatRepository {
    private static final String INSERT_SEAT = "INSERT INTO seats(show_id, seat_id) VALUES (?, ?)";

    private static final String FIND_BY_SHOW =
            "SELECT seat_id, status, booking_id FROM seats WHERE show_id = ? ORDER BY seat_id";

    private static final String LOCK_SEAT = "SELECT seat_id, status, booking_id FROM seats " +
            "WHERE show_id = ? AND seat_id = ? FOR UPDATE";

    private static final String CONFIRM_SEAT = "UPDATE seats SET status = 'confirmed', booking_id = ?, " +
            "updated_at = CURRENT_TIMESTAMP WHERE show_id = ? AND seat_id = ? AND status = 'available' " +
            "AND booking_id IS NULL";

    private static final String RELEASE_SEAT =
            "UPDATE seats SET status = 'available', booking_id = NULL, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE show_id = ? AND seat_id = ? AND booking_id = ?";

    private static final String COUNT_AVAILABLE = "SELECT count(*) FROM seats WHERE status = 'available'";

    private static final String TUNCATE_SEAT_TABLE = "TRUNCATE SEATS CASCADE";
    private final JdbcTemplate jdbc;

    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(long showId, String seatId) {
        jdbc.update(INSERT_SEAT, showId, seatId);
    }

    public List<Seat> findByShow(long showId) {
        return jdbc.query(FIND_BY_SHOW,
                (rs, n) -> mapSeat(rs), showId);
    }

    public Optional<Seat> lock(long showId, String seatId) {
        return jdbc.query(LOCK_SEAT,
                (rs, n) -> mapSeat(rs), showId, seatId).stream().findFirst();
    }

    public int confirm(long showId, String seatId, long bookingId) {
        return jdbc.update(CONFIRM_SEAT, bookingId, showId, seatId);
    }

    public int release(long showId, String seatId, long bookingId) {
        return jdbc.update(RELEASE_SEAT, showId, seatId, bookingId);
    }

    public long countAvailable() {
        return jdbc.queryForObject(COUNT_AVAILABLE, Long.class);
    }

    private Seat mapSeat(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Seat(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class));
    }

    public void tuncateSeatTable() {
        jdbc.update(TUNCATE_SEAT_TABLE);
    }
}
