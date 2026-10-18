package com.project.seatbooking.service;

import com.project.seatbooking.exception.Conflict;
import com.project.seatbooking.exception.Forbidden;
import com.project.seatbooking.exception.NotFound;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.project.seatbooking.dto.BookingResponse;
import com.project.seatbooking.dto.CancelBookingResponse;
import com.project.seatbooking.model.Booking;
import com.project.seatbooking.model.BookingSeat;
import com.project.seatbooking.model.IdempotencyKey;
import com.project.seatbooking.model.Show;
import com.project.seatbooking.model.ShowUserAllocation;
import com.project.seatbooking.repository.BookingRepository;
import com.project.seatbooking.repository.BookingSeatRepository;
import com.project.seatbooking.repository.IdempotencyKeyRepository;
import com.project.seatbooking.repository.SeatRepository;
import com.project.seatbooking.repository.ShowRepository;
import com.project.seatbooking.repository.ShowUserAllocationRepository;
import com.project.seatbooking.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final SeatRepository seatRepository;
    private final ShowRepository shows;
    private final ShowUserAllocationRepository allocations;
    private final JsonMapper json;
    private final UserRepository users;
    private static final Logger logger = LogManager.getLogger(BookingService.class);

    public BookingService(
            BookingRepository bookings,
            BookingSeatRepository bookingSeats,
            IdempotencyKeyRepository idempotencyKeys,
            SeatRepository seatRepository,
            ShowRepository shows,
            ShowUserAllocationRepository allocations,
            JsonMapper json,
            UserRepository users) {

        this.bookings = bookings;
        this.bookingSeats = bookingSeats;
        this.idempotencyKeys = idempotencyKeys;
        this.seatRepository = seatRepository;
        this.shows = shows;
        this.allocations = allocations;
        this.json = json;
        this.users = users;
    }


    public record Result(BookingResponse body, boolean replay) {
    }

    public record Cancellation(CancelBookingResponse body, boolean changed) {
    }

    @Transactional
    public Result reserve(long showId, String user, List<String> requested, String key) {
        if (requested == null || requested.isEmpty() || requested.size() != requested.stream().distinct().count() ||
                requested.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9_-]{1,32}")) ||
                key == null || key.isBlank() || key.length() > 128) {
            logger.info("[BookingService][reserve] Invalid request received with idempotency key as {} and requested seats {}", key, requested);
            throw new IllegalArgumentException("Invalid seats or idempotency key");
        }


        List<String> seats = requested.stream().sorted().toList();

        long userId = users.find(user).orElseThrow().getId();
        String fingerprint = hash(showId + ":" + String.join(",", seats));
        boolean inserted = idempotencyKeys.insertIfAbsent(userId, key, fingerprint);
        IdempotencyKey prior = idempotencyKeys.lock(userId, key);

        if (!inserted) {
            if (!fingerprint.equals(prior.getRequestHash())) {
                logger.info("[BookingService][reserve] Different request with same idempotency key as {} and requested seats {}", key, requested);
                throw new Conflict("idempotency_conflict");
            }

            try {
                logger.info("[BookingService][reserve] Returning cached response as idempotency key and request are same");
                return new Result(json.readValue(prior.getResponseBody(), BookingResponse.class), true);
            } catch (JacksonException e) {
                throw new IllegalStateException("Stored response invalid", e);
            }
        }

        Show show = shows.find(showId).orElseThrow(() -> new NotFound("Show not found"));
        allocations.ensureExists(showId, userId);
        ShowUserAllocation allocation = allocations.lock(showId, userId);

        for (String seat : seats) {
            var locked = seatRepository.lock(showId, seat)
                    .orElseThrow(() -> new IllegalArgumentException("Seat does not exist in show"));
            if (!"available".equals(locked.getStatus())) {
                logger.info("[BookingService][reserve] Seat already locked for show {} as {}", showId, seat);
                throw new Conflict("seat_taken");
            }
        }
        if ((long) allocation.getActiveSeatCount() + seats.size() > show.getPerUserLimit()) {
            logger.info("[BookingService][reserve] Per user limit of {} already reached hence rejecting the request", show.getPerUserLimit());
            throw new Conflict("per_user_limit");
        }
        long amount = Math.multiplyExact(show.getPricePaise(), seats.size());
        if (amount > Integer.MAX_VALUE) throw new IllegalArgumentException("Booking amount exceeds supported range");
        long id = bookings.create(showId, userId, amount);
        logger.info("[BookingService][reserve] Booking id {} has been created", id);
        logger.info("[BookingService][reserve] Starting seat booking for show {} as {}", showId, seats);
        for (String seat : seats) {
            int changed = seatRepository.confirm(showId, seat, id);
            if (changed != 1) {
                logger.info("[BookingService][reserve] Seat confirmation for show {} and seat {} received {} as seat already booked", showId, seat, changed);
                throw new IllegalStateException("Locked seat changed unexpectedly");
            }
            bookingSeats.create(id, showId, seat);
        }
        allocations.addSeats(showId, userId, seats.size());
        BookingResponse body = new BookingResponse(id, showId, user, seats, amount, "confirmed");
        try {
            logger.info("[BookingService][reserve] Adding the successful response in idempotency table");
            idempotencyKeys.saveResponse(userId, key, json.writeValueAsString(body));
        } catch (JacksonException e) {
            throw new IllegalStateException("Response serialization failed", e);
        }
        return new Result(body, false);
    }

    @Transactional
    public Cancellation cancel(long id, String user, String key) {
        long userId = users.find(user).orElseThrow().getId();
        if (key != null) {
            if (key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Invalid idempotency key");
            String fingerprint = hash("cancel:" + id);
            boolean inserted = idempotencyKeys.insertIfAbsent(userId, key, fingerprint);
            IdempotencyKey prior = idempotencyKeys.lock(userId, key);
            if (!inserted) {
                if (!fingerprint.equals(prior.getRequestHash())) throw new Conflict("idempotency_conflict");
                try {
                    return new Cancellation(json.readValue(prior.getResponseBody(), CancelBookingResponse.class), false);
                } catch (JacksonException e) {
                    throw new IllegalStateException("Stored response invalid", e);
                }
            }
        }
        Booking booking = bookings.lock(id).orElseThrow(() -> new NotFound("Booking not found"));
        if (userId != booking.getUserId()) {
            logger.info("[BookingService][cancel] Booking user does not match with the cancellation request user {}", userId);
            throw new Forbidden("User not allowed to cancel booking");
        }
        if ("cancelled".equals(booking.getStatus())) return finishCancellation(id, userId, key, false);
        allocations.ensureExists(booking.getShowId(), userId);
        allocations.lock(booking.getShowId(), userId);
        List<BookingSeat> seats = bookingSeats.findByBooking(id);
        logger.info("[BookingService][cancel] Starting lock operation on the seats for cancellation");
        for (BookingSeat seat : seats) {
            Long owner = seatRepository.lock(booking.getShowId(), seat.getSeatId())
                    .orElseThrow(() -> new IllegalStateException("Seat ownership mismatch")).getBookingId();
            if (owner == null || owner != id) throw new IllegalStateException("Seat ownership mismatch");
        }
        logger.info("[BookingService][cancel] Starting cancel operation on the seats");
        for (BookingSeat seat : seats) {
            seatRepository.release(booking.getShowId(), seat.getSeatId(), id);
        }
        bookings.cancel(id);
        allocations.removeSeats(booking.getShowId(), userId, seats.size());
        return finishCancellation(id, userId, key, true);
    }

    private Cancellation finishCancellation(long id, long userId, String key, boolean changed) {
        CancelBookingResponse body = new CancelBookingResponse(id, "cancelled");
        if (key != null) idempotencyKeys.saveResponse(userId, key, json.writeValueAsString(body));
        return new Cancellation(body, changed);
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
