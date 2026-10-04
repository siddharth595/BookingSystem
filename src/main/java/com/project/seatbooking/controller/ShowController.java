package com.project.seatbooking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {
    @GetMapping("/shows")
    public String getShows() {
        return "Up and Running";
    }

}
