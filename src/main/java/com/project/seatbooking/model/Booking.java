package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter @AllArgsConstructor
public class Booking {
    private final long id;
    private final long showId;
    private final long userId;
    private final String status;
}
