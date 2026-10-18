package com.project.seatbooking.controller;

import com.project.seatbooking.exception.AuthFailed;
import com.project.seatbooking.exception.Conflict;
import com.project.seatbooking.exception.Forbidden;
import com.project.seatbooking.exception.NotFound;
import com.project.seatbooking.service.BookingService;
import com.project.seatbooking.service.ShowService;
import com.project.seatbooking.service.TokenService;
import com.project.seatbooking.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    private final BookingMetrics metrics;

    public ApiErrors(BookingMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<ErrorResponse> conflict(Conflict ex, HttpServletRequest request) {
        metrics.declined(ex.reason());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("booking request_id={} operation={} user_id={} outcome=declined reason={}",
                request.getAttribute("request_id"), request.getRequestURI(), auth == null ? "" : auth.getName(),
                ex.reason());
        return error(HttpStatus.CONFLICT, ex.reason(), ex.getMessage(), request);
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ErrorResponse> missing(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "not_found", ex.getMessage(), request);
    }

    @ExceptionHandler(Forbidden.class)
    ResponseEntity<ErrorResponse> forbidden(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "forbidden", "Not booking owner", request);
    }

    @ExceptionHandler(AuthFailed.class)
    ResponseEntity<ErrorResponse> unauthorized(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid credentials", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> badInput(Exception ex, HttpServletRequest request) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Invalid request";
        return error(HttpStatus.BAD_REQUEST, "invalid_request", message, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        log.error("request_id={} operation={} outcome=error", request.getAttribute("request_id"),
                request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server failure", request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String reason, String message,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(reason, message,
                String.valueOf(request.getAttribute("request_id"))));
    }
}
