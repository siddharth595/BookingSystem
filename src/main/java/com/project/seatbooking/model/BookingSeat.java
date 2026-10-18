package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BookingSeat {
    private final long bookingId;
    private final long showId;
    private final String seatId;
}
