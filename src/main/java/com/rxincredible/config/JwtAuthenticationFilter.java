package com.rxincredible.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        String requestPath = request.getRequestURI();
        System.out.println("=== JWT FILTER START ===");
        System.out.println("Request: " + request.getMethod() + " " + requestPath);
        
        // First try to get token from cookie
        String jwt = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("token".equals(cookie.getName())) {
                    jwt = cookie.getValue();
                    System.out.println("Token found in cookie");
                    break;
                }
            }
        }

        // If no cookie, try Authorization header (fallback for API clients)
        if (jwt == null) {
            final String authHeader = request.getHeader("Authorization");
            System.out.println("Auth header: " + (authHeader != null ? authHeader.substring(0, Math.min(20, authHeader.length())) + "..." : "null"));
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                jwt = authHeader.substring(7);
                System.out.println("Token found in Authorization header");
            }
        }

        // If no token found, continue without authentication
        if (jwt == null) {
            System.out.println("No token found, continuing without authentication");
            System.out.println("=== JWT FILTER END (no token) ===");
            filterChain.doFilter(request, response);
            return;
        }

        final String userEmail;

        try {
            // Extract username from token
            userEmail = jwtUtils.extractUsername(jwt);
            System.out.println("Token username: " + userEmail);

            // If username is found and no authentication is set in context
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);
                System.out.println("User loaded: " + userDetails.getUsername() + ", authorities: " + userDetails.getAuthorities());

                // Validate token and set authentication
                if (jwtUtils.validateToken(jwt, userDetails)) {
                    System.out.println("Token validated successfully");
                    System.out.println("User authorities: " + userDetails.getAuthorities());
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    System.out.println("Authentication set in security context");
                } else {
                    System.out.println("Token validation failed");
                }
                // If token is invalid, don't set authentication - just continue without it
                // This allows permitAll endpoints to work even with invalid tokens
            }
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            logger.warn("Expired JWT token: " + e.getMessage());
            System.out.println("JWT token expired");
        } catch (io.jsonwebtoken.JwtException e) {
            logger.warn("Invalid JWT token: " + e.getMessage());
            System.out.println("JWT token invalid: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error in JWT authentication", e);
            System.out.println("JWT unexpected error: " + e.getMessage());
        }

        // Always continue the filter chain - this allows permitAll endpoints to work
        // even when the token is invalid or expired
        System.out.println("=== JWT FILTER END ===");
        filterChain.doFilter(request, response);
    }
}
