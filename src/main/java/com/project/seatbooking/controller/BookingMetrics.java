package com.project.seatbooking.controller;

import com.project.seatbooking.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BookingMetrics {
    private final MeterRegistry registry;
    private final PrometheusMeterRegistry prometheus;

    public BookingMetrics(MeterRegistry registry, PrometheusMeterRegistry prometheus, SeatRepository seats) {
        this.registry = registry;
        this.prometheus = prometheus;
        Gauge.builder("seats_available", seats, repository -> (double) repository.countAvailable()).register(registry);

        for (String reason : new String[]{"seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict"})
            Counter.builder("reservations_declined").tag("reason", reason).register(registry);
        Counter.builder("reservations_confirmed").register(registry);
        Counter.builder("reservations_cancelled").register(registry);
    }

    public void confirmed() {
        registry.counter("reservations_confirmed").increment();
    }

    public void declined(String reason) {
        registry.counter("reservations_declined", "reason", reason).increment();
    }

    public void cancelled() {
        registry.counter("reservations_cancelled").increment();
    }

    public String scrape() {
        return prometheus.scrape();
    }
}
