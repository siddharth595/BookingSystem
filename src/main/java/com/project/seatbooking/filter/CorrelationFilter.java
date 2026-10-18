package com.project.seatbooking.filter;

import com.project.seatbooking.service.BookingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    private final JsonMapper json;
    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(CorrelationFilter.class);
    public CorrelationFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        logger.info("[CorrelationFilter][doFilterInternal] Request received: {}", request.getRequestURI());
        String supplied = request.getHeader("X-Request-ID");
        String id = supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}") ? supplied : UUID.randomUUID().toString();
        request.setAttribute("request_id", id);
        response.setHeader("X-Request-ID", id);
        logger.info("[CorrelationFilter][doFilterInternal] X-Request-ID for the request: {}", id);
        long start = System.nanoTime();
        MDC.put("request_id", id);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("{}", json.writeValueAsString(Map.of("request_id", id, "method", request.getMethod(),
                    "path", request.getRequestURI(), "status", response.getStatus(),
                    "latency_ms", (System.nanoTime() - start) / 1_000_000)));
            MDC.remove("request_id");
        }
    }
}
