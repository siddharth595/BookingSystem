package com.project.seatbooking.service;

import com.project.seatbooking.repository.UserRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SeedUsers implements CommandLineRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;


    private static final Logger logger = LogManager.getLogger(SeedUsers.class);

    public SeedUsers(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        logger.info("Seeding users started...");
        String adminName = "admin";
        String adminPassword = "Admin123@";
        String userNamePrefix = "user";
        String userPassword = "User123@";

//        if (!adminName.isBlank() && adminName.equals(userName))
//            throw new IllegalStateException("Admin and user names must differ");
//
//        if (adminName.isBlank() != adminPassword.isBlank() || userName.isBlank() != userPassword.isBlank())
//            throw new IllegalStateException("Seed accounts require both username and password");
//
//        if (!adminName.isBlank() && !adminName.matches("[A-Za-z0-9_]{1,255}") ||
//                !userName.isBlank() && !userName.matches("[A-Za-z0-9_]{1,255}"))
//            throw new IllegalStateException("Seed usernames must be alphanumeric");
//
//        if (!adminName.isBlank() && !adminPassword.isBlank()) {
//            logger.info("[SeedUsers][run] Seeding Admin user in database with role ADMIN");
//            users.createIfMissing(adminName, encoder.encode(adminPassword), "ROLE_ADMIN");
//        }
//
//
//        if (!userName.isBlank() && !userPassword.isBlank()) {
//            logger.info("[SeedUsers][run] Seeding user1 in database with role ADMIN");
//            users.createIfMissing(userName, encoder.encode(userPassword), "ROLE_USER");
//        }
//
//        if (!userName2.isBlank() && !userPassword2.isBlank()) {
//            logger.info("[SeedUsers][run] Seeding user2 in database with role USER");
//            users.createIfMissing(userName2, encoder.encode(userPassword2), "ROLE_USER");
//        }
//
//        if (!userName3.isBlank() && !userPassword3.isBlank()) {
//            logger.info("[SeedUsers][run] Seeding user3 in database with role USER");
//            users.createIfMissing(userName3, encoder.encode(userPassword3), "ROLE_USER");
//        }

        long numberOfAdminUsers = users.countAdminUsers();
        if(numberOfAdminUsers == 0){
            users.createIfMissing(adminName, encoder.encode(adminPassword), "ADMIN");
        }

        long numberOfUsers = users.countTotalUsers();
        while(numberOfUsers < 100){
            users.createIfMissing(userNamePrefix.concat("_").concat(String.valueOf(numberOfUsers+1)), encoder.encode(userPassword), "USER");
            numberOfUsers++;
        }
        logger.info("Seeding users ended...");

    }
}