package com.project.seatbooking.exception;

public class AuthFailed extends RuntimeException {
    public AuthFailed(String message) {
        super(message);
    }
}
