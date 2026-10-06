package com.project.seatbooking.service;

import com.project.seatbooking.dto.SeatResponse;
import com.project.seatbooking.dto.ShowResponse;
import com.project.seatbooking.model.Seat;
import com.project.seatbooking.model.Show;
import com.project.seatbooking.repository.SeatRepository;
import com.project.seatbooking.repository.ShowRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {
    private final ShowRepository shows;
    private final SeatRepository seatRepository;
    public ShowService(ShowRepository shows, SeatRepository seatRepository) {
        this.shows = shows; this.seatRepository = seatRepository;
    }

    @Transactional
    public ShowResponse create(String name, List<String> seats, Long price, Integer limit) {
        if (name == null || name.isBlank() || name.length() > 255 || seats == null || seats.isEmpty() ||
                seats.size() != seats.stream().distinct().count() || price == null || price < 0 || price > Integer.MAX_VALUE ||
                limit != null && limit <= 0)
            throw new IllegalArgumentException("Invalid show details");
        if (seats.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9_-]{1,32}")))
            throw new IllegalArgumentException("Invalid seat ID");
        int perUserLimit = limit == null ? 4 : limit;
        long id = shows.create(name, price, seats.size(), perUserLimit);
        for (String seat : seats) seatRepository.create(id, seat);
        return get(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShowResponse get(long id) {
        Show show = shows.find(id).orElseThrow(() -> new NotFound("Show not found"));
        List<Seat> rows = seatRepository.findByShow(id);
        long available = rows.stream().filter(r -> "available".equals(r.getStatus())).count();
        long held = rows.stream().filter(r -> "held".equals(r.getStatus())).count();
        long confirmed = rows.stream().filter(r -> "confirmed".equals(r.getStatus())).count();
        if (available + held + confirmed != rows.size() || rows.size() != show.getTotalSeats())
            throw new IllegalStateException("Seat reconciliation failed");
        return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(), show.getTotalSeats(),
                show.getPerUserLimit(), available, held, confirmed,
                rows.stream().map(r -> new SeatResponse(r.getSeatId(), r.getStatus())).toList());
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }
}
