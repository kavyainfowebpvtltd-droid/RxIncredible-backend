package com.rxincredible.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter implements Filter {

    // Simple in-memory rate limiting - use Redis for distributed systems
    private final Map<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

    private static final int MAX_REQUESTS_PER_MINUTE = 600;
    private static final int MAX_REQUESTS_PER_MINUTE_LOGIN = 100;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String clientIP = getClientIP(httpRequest);
        String endpoint = httpRequest.getRequestURI();

        // Apply stricter rate limiting for auth endpoints
        int maxRequests = endpoint.contains("/auth/") ? MAX_REQUESTS_PER_MINUTE_LOGIN : MAX_REQUESTS_PER_MINUTE;

        RateLimitBucket bucket = buckets.computeIfAbsent(clientIP, k -> new RateLimitBucket(maxRequests));

        if (!bucket.tryConsume()) {
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String getClientIP(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class RateLimitBucket {
        private final int maxRequests;
        private final AtomicInteger count;
        private volatile long windowStart;

        RateLimitBucket(int maxRequests) {
            this.maxRequests = maxRequests;
            this.count = new AtomicInteger(0);
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            // Reset window every minute
            if (now - windowStart > 60000) {
                count.set(0);
                windowStart = now;
            }
            return count.incrementAndGet() <= maxRequests;
        }
    }
}
