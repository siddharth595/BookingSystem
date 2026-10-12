package com.project.seatbooking.service;

import com.project.seatbooking.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SeedUsers implements CommandLineRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    @Value("${booking.admin-username:}") private String adminName;
    @Value("${booking.admin-password:}") private String adminPassword;
    @Value("${booking.user-username:}") private String userName;
    @Value("${booking.user-password:}") private String userPassword;
    public SeedUsers(UserRepository users, PasswordEncoder encoder) { this.users = users; this.encoder = encoder; }
    @Override public void run(String... args) {
        if (!adminName.isBlank() && adminName.equals(userName))
            throw new IllegalStateException("Admin and user names must differ");
        if (adminName.isBlank() != adminPassword.isBlank() || userName.isBlank() != userPassword.isBlank())
            throw new IllegalStateException("Seed accounts require both username and password");
        if (!adminName.isBlank() && !adminName.matches("[A-Za-z0-9_]{1,255}") ||
                !userName.isBlank() && !userName.matches("[A-Za-z0-9_]{1,255}"))
            throw new IllegalStateException("Seed usernames must be alphanumeric");
        if (!adminName.isBlank() && !adminPassword.isBlank())
            users.createIfMissing(adminName, encoder.encode(adminPassword), "ROLE_ADMIN");
        if (!userName.isBlank() && !userPassword.isBlank())
            users.createIfMissing(userName, encoder.encode(userPassword), "ROLE_USER");
    }
}