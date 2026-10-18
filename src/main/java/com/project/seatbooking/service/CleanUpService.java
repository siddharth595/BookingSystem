package com.project.seatbooking.service;

import com.project.seatbooking.repository.*;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CleanUpService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final ShowUserAllocationRepository  showUserAllocationRepository;
    private final UserRepository userRepository;

    public CleanUpService(BookingRepository bookingRepository, BookingSeatRepository bookingSeatRepository, IdempotencyKeyRepository idempotencyKeyRepository, SeatRepository seatRepository, ShowRepository showRepository, ShowUserAllocationRepository showUserAllocationRepository, UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.showUserAllocationRepository = showUserAllocationRepository;
        this.userRepository = userRepository;
    }


    //For testing
    @Transactional
    public boolean truncateTables(){
        try {
            bookingRepository.truncateBookingTable();
            bookingSeatRepository.truncateBookingSeatTable();
            idempotencyKeyRepository.truncateIdempotencyKeyTable();
            showRepository.truncateShows();
            seatRepository.tuncateSeatTable();
            showUserAllocationRepository.truncateShowUsersAllocations();
            userRepository.truncateUsers();
            return true;
        }
        catch (Exception e){
            e.printStackTrace();
            return false;
        }

    }
}
