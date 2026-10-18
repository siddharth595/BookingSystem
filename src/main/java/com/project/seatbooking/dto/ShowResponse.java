package com.project.seatbooking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ShowResponse {
    private final long id;
    private final String name;

    @JsonProperty("price_paise")
    private final long pricePaise;

    @JsonProperty("total_seats")
    private final int totalSeats;

    @JsonProperty("per_user_limit")
    private final int perUserLimit;

    private final long available;
    private final long held;
    private final long confirmed;
    private final List<SeatResponse> seats;
}
