package com.project.seatbooking.repository;

import com.project.seatbooking.model.Show;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {
    private static final String INSERT_SHOW = "INSERT INTO shows(name, price_paise, total_seats, per_user_limit) " +
            "VALUES (?, ?, ?, ?) RETURNING id";
    private static final String FIND_SHOW =
            "SELECT id, name, price_paise, total_seats, per_user_limit FROM shows WHERE id = ?";
    private static final String FIND_SHOW_BY_NAME =
            "SELECT id, name, price_paise, total_seats, per_user_limit FROM shows WHERE name = ?";

    private static final String TUNCATE_SHOW_TABLE = "TRUNCATE TABLE shows RESTART IDENTITY CASCADE";

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String name, long pricePaise, int totalSeats, int perUserLimit) {
        return jdbc.queryForObject(INSERT_SHOW, Long.class, name, pricePaise, totalSeats, perUserLimit);
    }

    public Optional<Show> find(long id) {
        return jdbc.query(FIND_SHOW,
                        (rs, n) -> new Show(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)), id)
                .stream().findFirst();
    }

    public Optional<Show> findByName(String name) {
        return jdbc.query(FIND_SHOW_BY_NAME,
                        (rs, n) -> new Show(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)), name)
                .stream().findFirst();
    }

    public void truncateShows(){
        jdbc.execute(TUNCATE_SHOW_TABLE);
    }
}
