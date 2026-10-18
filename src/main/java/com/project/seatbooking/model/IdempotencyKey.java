package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class IdempotencyKey {
    private final long userId;
    private final String key;
    private final String requestHash;
    private final String responseBody;
}
