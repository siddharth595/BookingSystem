package com.project.seatbooking.service;

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
    public BookingService(BookingRepository bookings, BookingSeatRepository bookingSeats,
            IdempotencyKeyRepository idempotencyKeys, SeatRepository seatRepository, ShowRepository shows,
            ShowUserAllocationRepository allocations, JsonMapper json, UserRepository users) {
        this.bookings = bookings; this.bookingSeats = bookingSeats; this.idempotencyKeys = idempotencyKeys;
        this.seatRepository = seatRepository; this.shows = shows; this.allocations = allocations;
        this.json = json; this.users = users;
    }
    public static class Conflict extends RuntimeException {
        private final String reason;
        public Conflict(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }
    public static class Forbidden extends RuntimeException {}
    public record Result(BookingResponse body, boolean replay) {}
    public record Cancellation(CancelBookingResponse body, boolean changed) {}

    @Transactional
    public Result reserve(long showId, String user, List<String> requested, String key) {
        if (requested == null || requested.isEmpty() || requested.size() != requested.stream().distinct().count() ||
                requested.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9_-]{1,32}")) ||
                key == null || key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException("Invalid seats or idempotency key");

        List<String> seats = requested.stream().sorted().toList();

        long userId = users.find(user).orElseThrow().getId();
        String fingerprint = hash(showId + ":" + String.join(",", seats));
        boolean inserted = idempotencyKeys.insertIfAbsent(userId, key, fingerprint);
        IdempotencyKey prior = idempotencyKeys.lock(userId, key);

        if (!inserted) {
            if (!fingerprint.equals(prior.getRequestHash()))
                throw new Conflict("idempotency_conflict");
            try {
                return new Result(json.readValue(prior.getResponseBody(), BookingResponse.class), true);
            }
            catch (JacksonException e) {
                throw new IllegalStateException("Stored response invalid", e);
            }
        }

        Show show = shows.find(showId).orElseThrow(() -> new ShowService.NotFound("Show not found"));
        allocations.ensureExists(showId, userId);
        ShowUserAllocation allocation = allocations.lock(showId, userId);

        for (String seat : seats) {
            var locked = seatRepository.lock(showId, seat)
                    .orElseThrow(() -> new IllegalArgumentException("Seat does not exist in show"));
            if (!"available".equals(locked.getStatus())) throw new Conflict("seat_taken");
        }
        if ((long) allocation.getActiveSeatCount() + seats.size() > show.getPerUserLimit())
            throw new Conflict("per_user_limit");
        long amount = Math.multiplyExact(show.getPricePaise(), seats.size());
        if (amount > Integer.MAX_VALUE) throw new IllegalArgumentException("Booking amount exceeds supported range");
        long id = bookings.create(showId, userId, amount);
        for (String seat : seats) {
            int changed = seatRepository.confirm(showId, seat, id);
            if (changed != 1) throw new IllegalStateException("Locked seat changed unexpectedly");
            bookingSeats.create(id, showId, seat);
        }
        allocations.addSeats(showId, userId, seats.size());
        BookingResponse body = new BookingResponse(id, showId, user, seats, amount, "confirmed");
        try {
            idempotencyKeys.saveResponse(userId, key, json.writeValueAsString(body));
        } catch (JacksonException e) { throw new IllegalStateException("Response serialization failed", e); }
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
                try { return new Cancellation(json.readValue(prior.getResponseBody(), CancelBookingResponse.class), false); }
                catch (JacksonException e) { throw new IllegalStateException("Stored response invalid", e); }
            }
        }
        Booking booking = bookings.lock(id).orElseThrow(() -> new ShowService.NotFound("Booking not found"));
        if (userId != booking.getUserId()) throw new Forbidden();
        if ("cancelled".equals(booking.getStatus())) return finishCancellation(id, userId, key, false);
        allocations.ensureExists(booking.getShowId(), userId);
        allocations.lock(booking.getShowId(), userId);
        List<BookingSeat> seats = bookingSeats.findByBooking(id);
        for (BookingSeat seat : seats) {
            Long owner = seatRepository.lock(booking.getShowId(), seat.getSeatId())
                    .orElseThrow(() -> new IllegalStateException("Seat ownership mismatch")).getBookingId();
            if (owner == null || owner != id) throw new IllegalStateException("Seat ownership mismatch");
        }
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
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
