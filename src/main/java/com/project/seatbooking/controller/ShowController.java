package com.project.seatbooking.controller;

import com.project.seatbooking.dto.CreateShowRequest;
import com.project.seatbooking.dto.ShowResponse;
import com.project.seatbooking.service.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {
    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ShowResponse create(@RequestBody CreateShowRequest request) {
        return shows.create(request.getName(), request.getSeats(), request.getPricePaise(), request.getPerUserLimit());
    }

    @GetMapping("/shows/{id}")
    public ShowResponse get(@PathVariable long id) {
        return shows.get(id);
    }
}
