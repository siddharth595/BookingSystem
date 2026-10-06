package com.project.seatbooking.dto;

import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class ReserveRequest {
    private List<String> seats;
}
