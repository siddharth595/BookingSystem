package com.project.seatbooking.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseHealthRepository {
    private static final String CHECK_CONNECTION = "SELECT 1";

    private final JdbcTemplate jdbc;

    public DatabaseHealthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void checkConnection() {
        jdbc.queryForObject(CHECK_CONNECTION, Integer.class);
    }
}
