package com.project.seatbooking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SeatResponse {

    @JsonProperty("seat_id")
    private final String seatId;

    private final String status;
}
