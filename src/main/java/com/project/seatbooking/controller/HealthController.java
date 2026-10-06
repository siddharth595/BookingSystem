package com.project.seatbooking.controller;

import com.project.seatbooking.dto.HealthResponse;
import com.project.seatbooking.repository.DatabaseHealthRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DatabaseHealthRepository databaseHealth;
    private final BookingMetrics metrics;
    public HealthController(DatabaseHealthRepository databaseHealth, BookingMetrics metrics) {
        this.databaseHealth = databaseHealth; this.metrics = metrics;
    }
    @GetMapping("/health/live") public HealthResponse live() {
        return new HealthResponse("UP");
    }

    @GetMapping("/health/ready") public ResponseEntity<HealthResponse> ready() {
        try {
            databaseHealth.checkConnection();
            return ResponseEntity.ok(new HealthResponse("UP"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN"));
        }
    }
    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public String metrics() { return metrics.scrape(); }
}
