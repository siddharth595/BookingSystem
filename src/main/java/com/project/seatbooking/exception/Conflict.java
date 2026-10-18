package com.project.seatbooking.exception;

public class Conflict extends RuntimeException {
    private final String reason;

    public Conflict(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
