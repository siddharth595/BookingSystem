package com.project.seatbooking.controller;

import com.project.seatbooking.service.CleanUpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationController {

    @Autowired
    CleanUpService cleanUpService;

    @GetMapping("/status")
    public String status() {
        return "Up and Running";
    }

    @DeleteMapping("/truncate/users")
    public String truncateUsers(){
        if(cleanUpService.truncateUsers()){
            return "Successfully truncated tables";
        }
        else {
            return "Failed to truncate tables";
        }
    }

    @DeleteMapping("/truncate/tables")
    public String truncateTables(){
        if(cleanUpService.truncateTables()){
            return "Successfully truncated tables";
        }
        else {
            return "Failed to truncate tables";
        }
    }

}
