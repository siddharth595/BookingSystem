package com.project.seatbooking.repository;

import com.project.seatbooking.model.UserAccount;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final String FIND_USER = "SELECT id, username, password, role FROM users WHERE username = ?";
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO users(username, email, password, role) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (username) DO NOTHING";

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public Optional<UserAccount> find(String username) {
        return jdbc.query(FIND_USER,
                (rs, n) -> new UserAccount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), username)
                .stream().findFirst();
    }
    public void createIfMissing(String username, String hash, String role) {
        jdbc.update(INSERT_IF_ABSENT, username, username + "@example.invalid", hash, role);
    }
}
