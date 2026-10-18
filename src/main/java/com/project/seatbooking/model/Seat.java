package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Seat {
    private final String seatId;
    private final String status;
    private final Long bookingId;
}
