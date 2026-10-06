package com.project.seatbooking.repository;

import com.project.seatbooking.model.IdempotencyKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyKeyRepository {
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO idempotency_keys(user_id, idempotency_key, request_hash, response_body) " +
            "VALUES (?, ?, ?, '') ON CONFLICT DO NOTHING";
    private static final String LOCK_KEY = "SELECT user_id, idempotency_key, request_hash, response_body " +
            "FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ? FOR UPDATE";
    private static final String SAVE_RESPONSE = "UPDATE idempotency_keys SET response_body = ? " +
            "WHERE user_id = ? AND idempotency_key = ?";

    private final JdbcTemplate jdbc;

    public IdempotencyKeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean insertIfAbsent(long userId, String key, String requestHash) {
        return jdbc.update(INSERT_IF_ABSENT, userId, key, requestHash) == 1;
    }

    public IdempotencyKey lock(long userId, String key) {
        return jdbc.query(LOCK_KEY,
                (rs, n) -> new IdempotencyKey(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                userId, key).get(0);
    }

    public void saveResponse(long userId, String key, String responseBody) {
        jdbc.update(SAVE_RESPONSE, responseBody, userId, key);
    }
}
