package com.project.seatbooking.service;

import tools.jackson.databind.json.JsonMapper;
import com.project.seatbooking.repository.UserRepository;
import com.project.seatbooking.model.UserAccount;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    public static class AuthFailed extends RuntimeException {}
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JsonMapper json;
    private final byte[] secret;

    public TokenService(UserRepository users, PasswordEncoder encoder, JsonMapper json,
                        @Value("${booking.jwt-secret:}") String secret) {
        this.users = users; this.encoder = encoder; this.json = json;
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String login(String username, String password) {
        var user = users.find(username).orElseThrow(AuthFailed::new);
        if (!encoder.matches(password, user.getPasswordHash()))
            throw new AuthFailed();
        try {
            String header = encode(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String body = encode(json.writeValueAsBytes(Map.of("sub", username, "role", user.getRole(),
                    "iat", Instant.now().getEpochSecond(), "exp", Instant.now().plusSeconds(3600).getEpochSecond())));
            String value = header + "." + body;
            return value + "." + encode(sign(value));
        } catch (Exception e) { throw new IllegalStateException("Token creation failed", e); }
    }

    public UserAccount authenticate(String token) {
        String username;
        String role;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException();
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(signature, sign(parts[0] + "." + parts[1])))
                throw new IllegalArgumentException();
            var header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText()) ||
                    claims.path("exp").asLong() <= Instant.now().getEpochSecond()) throw new IllegalArgumentException();
            username = claims.path("sub").asText();
            role = claims.path("role").asText();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid token"); }
        var user = users.find(username).orElseThrow(() -> new IllegalArgumentException("Invalid token"));
        if (!user.getRole().equals(role)) throw new IllegalArgumentException("Invalid token");
        return user;
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    }
    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
