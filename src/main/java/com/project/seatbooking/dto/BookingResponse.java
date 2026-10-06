package com.project.seatbooking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class BookingResponse {

    @JsonProperty("booking_id")
    private long bookingId;

    @JsonProperty("show_id")
    private long showId;

    @JsonProperty("user_id")
    private String userId;

    private List<String> seats;

    @JsonProperty("amount_paise")
    private long amountPaise;

    private String status;
}
