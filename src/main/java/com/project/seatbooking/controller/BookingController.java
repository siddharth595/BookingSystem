package com.project.seatbooking.controller;

import com.project.seatbooking.service.BookingService;
import com.project.seatbooking.dto.BookingResponse;
import com.project.seatbooking.dto.CancelBookingResponse;
import com.project.seatbooking.dto.ReserveRequest;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {
    private static final Logger log = LoggerFactory.getLogger(BookingController.class);
    private final BookingService bookings;
    private final BookingMetrics metrics;

    public BookingController(BookingService bookings, BookingMetrics metrics) {
        this.bookings = bookings;
        this.metrics = metrics;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<BookingResponse> reserve(
            @PathVariable long id,
            @RequestBody ReserveRequest request,
            @RequestHeader("Idempotency-Key") String key,
            Principal principal,
            HttpServletRequest http) {
        var result = bookings.reserve(id, principal.getName(), request.getSeats(), key);
        if (result.replay()) metrics.declined("idempotent_replay");
        else metrics.confirmed();
        log.info("booking request_id={} show_id={} user_id={} booking_id={} outcome={}",
                http.getAttribute("request_id"), id, principal.getName(), result.body().getBookingId(),
                result.replay() ? "idempotent_replay" : "confirmed");
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PostMapping("/bookings/{id}/cancel")
    public CancelBookingResponse cancel(
            @PathVariable long id,
            Principal principal,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {

        var result = bookings.cancel(id, principal.getName(), key);
        if (result.changed()) metrics.cancelled();
        return result.body();
    }
}
