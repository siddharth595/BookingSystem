package com.project.seatbooking.repository;

import com.project.seatbooking.model.ShowUserAllocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowUserAllocationRepository {
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO show_user_allocations(show_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING";
    private static final String LOCK_ALLOCATION = "SELECT show_id, user_id, active_seat_count " +
            "FROM show_user_allocations WHERE show_id = ? AND user_id = ? FOR UPDATE";
    private static final String ADD_SEATS =
            "UPDATE show_user_allocations SET active_seat_count = active_seat_count + ? " +
            "WHERE show_id = ? AND user_id = ?";
    private static final String REMOVE_SEATS =
            "UPDATE show_user_allocations SET active_seat_count = active_seat_count - ? " +
            "WHERE show_id = ? AND user_id = ?";

    private final JdbcTemplate jdbc;

    public ShowUserAllocationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureExists(long showId, long userId) {
        jdbc.update(INSERT_IF_ABSENT, showId, userId);
    }

    public ShowUserAllocation lock(long showId, long userId) {
        return jdbc.query(LOCK_ALLOCATION,
                (rs, n) -> new ShowUserAllocation(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                showId, userId).get(0);
    }

    public void addSeats(long showId, long userId, int count) {
        jdbc.update(ADD_SEATS, count, showId, userId);
    }

    public void removeSeats(long showId, long userId, int count) {
        jdbc.update(REMOVE_SEATS, count, showId, userId);
    }
}
