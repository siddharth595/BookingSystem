package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter @AllArgsConstructor
public class Show {
    private final long id;
    private final String name;
    private final long pricePaise;
    private final int totalSeats;
    private final int perUserLimit;
}
