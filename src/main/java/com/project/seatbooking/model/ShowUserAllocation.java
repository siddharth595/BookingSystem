package com.project.seatbooking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter @AllArgsConstructor
public class ShowUserAllocation {
    private final long showId;
    private final long userId;
    private final int activeSeatCount;
}
