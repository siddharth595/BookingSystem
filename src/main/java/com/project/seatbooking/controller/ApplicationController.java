package com.project.seatbooking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationController {

    @GetMapping("/status")
    public String status() {
        return "Up and Running";
    }
}
