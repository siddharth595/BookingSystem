package com.project.seatbooking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class CreateShowRequest {

    private String name;

    private List<String> seats;

    @JsonProperty("price_paise")
    private Long pricePaise;

    @JsonProperty("per_user_limit")
    private Integer perUserLimit;
}
