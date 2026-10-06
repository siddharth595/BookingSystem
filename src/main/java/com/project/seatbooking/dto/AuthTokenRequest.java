package com.project.seatbooking.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class AuthTokenRequest {
    private String username;
    private String password;
}
