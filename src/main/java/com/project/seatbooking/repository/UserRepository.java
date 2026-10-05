package com.project.seatbooking.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 1. Fetching a single value
    public int getUserCount() {
        String sql = "SELECT COUNT(*) FROM users";
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    // 2. Fetching multiple rows
    public List<Map<String, Object>> getAllUsers() {
        String sql = "SELECT id, username, email FROM users";
        return jdbcTemplate.queryForList(sql);
    }

    // 3. Executing a safe, parameterized update/insert
    public void createNewUser(String username, String email) {
        String sql = "INSERT INTO users (username, email) VALUES (?, ?)";
        jdbcTemplate.update(sql, username, email);
    }
}

