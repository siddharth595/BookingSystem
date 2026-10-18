package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserAccount {
    private final long id;
    private final String username;
    private final String passwordHash;
    private final String role;

    @Override
    public String toString() {
        return "UserAccount{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", passwordHash='" + passwordHash + '\'' +
                ", role='" + role + '\'' +
                '}';
    }
}
