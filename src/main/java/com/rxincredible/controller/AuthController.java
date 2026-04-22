package com.rxincredible.controller;

import com.rxincredible.config.JwtUtils;
import com.rxincredible.entity.User;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.service.UserService;
import com.rxincredible.service.OtpService;
import com.rxincredible.service.EmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final OtpService otpService;
    private final EmailService emailService;

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository,
            UserService userService, JwtUtils jwtUtils, OtpService otpService, EmailService emailService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.userService = userService;
        this.jwtUtils = jwtUtils;
        this.otpService = otpService;
        this.emailService = emailService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials, HttpServletResponse response) {
        String email = normalizeEmail(credentials.get("email"));
        String password = credentials.get("password");

        log.info("Login attempt for email: {}", maskEmail(email));

        try {
            // First check if user is in pending_users (not verified yet)
            boolean isPending = userService.isEmailPending(email);

            if (isPending) {
                // Generate and send OTP for verification
                String otp = otpService.generateOtp();

                // Get pending user details
                var pendingUserOpt = userService.getPendingUserByEmail(email);
                if (pendingUserOpt.isPresent()) {
                    var pendingUser = pendingUserOpt.get();

                    // Update pending user with new OTP
                    userService.updatePendingUserWithOtp(pendingUser.getId(), otp);

                    // Send OTP email - DO NOT log OTP
                    emailService.sendOtpEmail(email, pendingUser.getFullName(), otp);
                    log.info("OTP sent for email verification: {}", maskEmail(email));
                }

                Map<String, String> responseMap = new HashMap<>();
                responseMap.put("error", "Email not verified. An OTP has been sent to your email.");
                responseMap.put("pending", "true");
                responseMap.put("email", email);
                responseMap.put("otpSent", "true");
                return ResponseEntity.status(403).body(responseMap);
            }

            // Then check if user exists in main database
            boolean userExists = userRepository.findByEmail(email).isPresent();

            if (!userExists) {
                log.warn("Login failed - user not found: {}", maskEmail(email));
                Map<String, String> error = new HashMap<>();
                error.put("error", "Invalid email or password");
                return ResponseEntity.status(401).body(error);
            }

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            if ("Disabled".equalsIgnoreCase(user.getStatus())) {
                log.warn("Login blocked for disabled user: {}", maskEmail(email));
                Map<String, String> error = new HashMap<>();
                error.put("error", "Your account is disabled. Please contact the administrator.");
                return ResponseEntity.status(403).body(error);
            }

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password));

            log.info("Login successful for email: {}", maskEmail(email));

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();

            if ("ADMIN".equalsIgnoreCase(user.getRole())) {
                String otp = otpService.generateOtp();
                user.setOtp(otp);
                user.setOtpExpiry(LocalDateTime.now().plusMinutes(5));
                userRepository.save(user);

                emailService.sendOtpEmail(email, user.getFullName(), otp);
                log.info("Admin OTP sent for email: {}", maskEmail(email));

                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("message", "OTP sent to your email");
                responseMap.put("otpRequired", true);
                responseMap.put("email", email);
                responseMap.put("role", user.getRole());
                return ResponseEntity.accepted().body(responseMap);
            }

            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Generate proper JWT token
            String token = jwtUtils.generateToken(userDetails);

            // Set token in httpOnly cookie
            Cookie cookie = new Cookie("token", token);
            cookie.setHttpOnly(true);
            cookie.setSecure(false); // Set to false for development (HTTP)
            cookie.setPath("/");
            cookie.setMaxAge(86400); // 24 hours
            // Set SameSite to Lax to allow cross-site cookies
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("message", "Login successful");
            responseMap.put("user", user);
            // Also return token in body for API clients that can't access cookies
            responseMap.put("token", token);

            return ResponseEntity.ok(responseMap);
        } catch (DisabledException e) {
            log.warn("Login blocked by disabled status: {}", maskEmail(email));
            Map<String, String> error = new HashMap<>();
            User user = userRepository.findByEmail(email).orElse(null);
            if (user != null && "Disabled".equalsIgnoreCase(user.getStatus())) {
                error.put("error", "Your account is disabled. Please contact the administrator.");
            } else {
                error.put("error", "Your account cannot be used right now. Please contact the administrator.");
            }
            return ResponseEntity.status(403).body(error);
        } catch (AuthenticationException e) {
            log.warn("Login failed for email: {} - {}", maskEmail(email), e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Invalid email or password");
            return ResponseEntity.status(401).body(error);
        }
    }

    @PostMapping("/admin/verify-otp")
    public ResponseEntity<?> verifyAdminOtp(@RequestBody Map<String, String> payload, HttpServletResponse response) {
        String email = normalizeEmail(payload.get("email"));
        String otp = payload.get("otp");

        log.info("Admin OTP verification attempt for email: {}", maskEmail(email));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "OTP login is only available for admin users."));
        }

        if (user.getOtp() == null || user.getOtpExpiry() == null) {
            return ResponseEntity.status(400).body(Map.of("error", "No OTP request found. Please login again."));
        }

        if (user.getOtpExpiry().isBefore(LocalDateTime.now())) {
            user.setOtp(null);
            user.setOtpExpiry(null);
            userRepository.save(user);
            return ResponseEntity.status(400).body(Map.of("error", "OTP has expired. Please login again."));
        }

        if (!user.getOtp().equals(otp)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid OTP. Please try again."));
        }

        UserDetails userDetails = user;
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String token = jwtUtils.generateToken(userDetails);

        Cookie cookie = new Cookie("token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(86400);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        user.setOtp(null);
        user.setOtpExpiry(null);
        userRepository.save(user);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("message", "Admin login successful");
        responseMap.put("user", user);
        responseMap.put("token", token);
        return ResponseEntity.ok(responseMap);
    }

    @PostMapping("/admin/resend-otp")
    public ResponseEntity<?> resendAdminOtp(@RequestBody Map<String, String> payload) {
        String email = normalizeEmail(payload.get("email"));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "OTP login is only available for admin users."));
        }

        String otp = otpService.generateOtp();
        user.setOtp(otp);
        user.setOtpExpiry(LocalDateTime.now().plusMinutes(5));
        userRepository.save(user);

        emailService.sendOtpEmail(email, user.getFullName(), otp);
        log.info("Admin OTP resent for email: {}", maskEmail(email));

        return ResponseEntity.ok(Map.of("message", "OTP resent successfully"));
    }

    // Helper method to mask email for logging
    private String maskEmail(String email) {
        if (email == null || !email.contains("@"))
            return "***";
        int atIndex = email.indexOf("@");
        if (atIndex <= 2)
            return "***" + email.substring(atIndex);
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        SecurityContextHolder.clearContext();

        // Clear the token cookie
        Cookie cookie = new Cookie("token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        log.info("User logged out successfully");

        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("message", "Logout successful");
        return ResponseEntity.ok(responseMap);
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        System.out.println("=== /auth/me called ===");
        System.out.println("Authentication object: " + authentication);
        System.out.println("Is authenticated: " + (authentication != null ? authentication.isAuthenticated() : "null"));
        
        if (authentication != null && authentication.isAuthenticated()) {
            String email = authentication.getName();
            System.out.println("User email from auth: " + email);
            
            User user = userRepository.findByEmail(email)
                    .orElse(null);
            
            if (user != null) {
                System.out.println("User found: " + user.getFullName() + " (ID: " + user.getId() + ", Role: " + user.getRole() + ")");
                return ResponseEntity.ok(user);
            } else {
                System.out.println("User not found for email: " + email);
            }
        } else {
            System.out.println("Not authenticated or authentication is null");
        }

        return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
    }
}
