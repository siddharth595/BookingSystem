package com.project.seatbooking.controller;

import com.project.seatbooking.dto.AuthTokenRequest;
import com.project.seatbooking.dto.AuthTokenResponse;
import com.project.seatbooking.service.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final TokenService tokens;
    public AuthController(TokenService tokens) { this.tokens = tokens; }
    @PostMapping("/auth/token")
    @ResponseStatus(HttpStatus.OK)
    public AuthTokenResponse login(@RequestBody AuthTokenRequest request) {
        if (request.getUsername() == null || request.getPassword() == null)
            throw new IllegalArgumentException("Username and password are required");
        return new AuthTokenResponse(tokens.login(request.getUsername(), request.getPassword()), "Bearer", 3600);
    }
}
