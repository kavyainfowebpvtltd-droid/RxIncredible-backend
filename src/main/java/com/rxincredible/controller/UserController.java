package com.rxincredible.controller;


import com.rxincredible.dto.ResetPasswordDto;
import com.rxincredible.dto.ResetVerifyPasswordRequestDto;
import com.rxincredible.entity.PendingUser;
import com.rxincredible.entity.User;
import com.rxincredible.service.UserService;
import com.rxincredible.service.OtpService;
import com.rxincredible.service.EmailService;
import com.rxincredible.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final OtpService otpService;
    private final EmailService emailService;
    private final OrderService orderService;

    @Value("${app.upload.directory:uploads/documents}")
    private String uploadDirectory;

    public UserController(UserService userService, OtpService otpService, EmailService emailService, OrderService orderService) {
        this.userService = userService;
        this.otpService = otpService;
        this.emailService = emailService;
        this.orderService = orderService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody User user) {
        try {
            log.info("Registration request for email: {}", maskEmail(user.getEmail()));

            // Validate required fields
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
            }
            if (user.getPassword() == null || user.getPassword().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
            }
            if (user.getFullName() == null || user.getFullName().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Full name is required"));
            }

            // Set default role if not provided
            if (user.getRole() == null || user.getRole().isEmpty()) {
                user.setRole("USER");
            }

            // Check if user already exists in main database
            if (userService.findByEmail(user.getEmail()).isPresent()) {
                log.warn("Registration failed - email already exists: {}", maskEmail(user.getEmail()));
                return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
            }

            // Check if verification email already sent (pending)
            if (userService.isEmailPending(user.getEmail())) {
                log.warn("Registration failed - verification pending: {}", maskEmail(user.getEmail()));
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Verification email already sent. Please check your email or try again."));
            }

            // Generate OTP for email verification - DO NOT LOG OTP
            String otp = otpService.generateOtp();

            // Save user as pending
            userService.savePendingUserWithOtp(user, otp);
            log.info("User saved to pending users table: {}", maskEmail(user.getEmail()));

            // Send OTP via email - DO NOT LOG OTP
            try {
                emailService.sendOtpEmail(user.getEmail(), user.getFullName(), otp);
                log.info("Verification email sent: {}", maskEmail(user.getEmail()));
            } catch (Exception emailEx) {
                // Don't fail registration if email fails - just log it
                log.error("Failed to send verification email: {}", emailEx.getMessage());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Registration successful. Please check your email to verify your account.");
            response.put("email", user.getEmail());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Registration error for email: {} - Error: {}", maskEmail(user.getEmail()), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Registration failed: " + e.getMessage()));
        }
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

    /**
     * Direct registration for admin.
     * ADMIN users are created immediately.
     * All other roles stay pending until email verification is completed.
     */
    @PostMapping("/register-direct")
    public ResponseEntity<?> registerDirect(@RequestBody User user) {
        log.info("Direct registration request for email: {}, role: {}", maskEmail(user.getEmail()), user.getRole());

        // Validate required fields
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }
        if (user.getFullName() == null || user.getFullName().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Full name is required"));
        }
        if (user.getRole() == null || user.getRole().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role is required"));
        }

        // Check if user already exists in main database
        if (userService.findByEmail(user.getEmail()).isPresent()) {
            log.warn("Direct registration failed - email exists: {}", maskEmail(user.getEmail()));
            return ResponseEntity.badRequest().body(Map.of("error", "Email already exists in the system"));
        }

        // Clean up any pending user with same email
        try {
            if (userService.isEmailPending(user.getEmail())) {
                userService.deletePendingUser(user.getEmail());
            }
        } catch (Exception e) {
            log.warn("Could not cleanup pending user: {}", e.getMessage());
        }

        try {
            // Only ADMIN should be created immediately.
            // USER, DOCTOR, ANALYST, and ACCOUNTANT must verify email first.
            if (user.getRole() != null && !user.getRole().equals("ADMIN")) {
                // Generate OTP for email verification (stored but not sent yet)
                String otp = otpService.generateOtp();

                // Save user as pending (requires email verification)
                userService.savePendingUserWithOtp(user, otp);
                log.info("{} saved to pending: {}", user.getRole(), maskEmail(user.getEmail()));

                Map<String, Object> response = new HashMap<>();
                response.put("message",
                        user.getRole() + " added successfully! User will need to verify email on first login.");
                response.put("email", user.getEmail());
                response.put("requiresVerification", true);

                return ResponseEntity.ok(response);
            }

            // ADMIN is created immediately
            User savedUser = userService.registerUser(user);
            log.info("User registered directly with ID: {}", savedUser.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "User registered successfully!");
            response.put("user", savedUser);
            response.put("requiresVerification", false);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Direct registration failed for: {} - {}", maskEmail(user.getEmail()), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Registration failed: " + e.getMessage()));
        }
    }

    /**
     * Verify email and complete registration
     */
    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        log.info("Email verification request received");

        try {
            User verifiedUser = userService.verifyAndCreateUser(token);

            log.info("Email verified successfully for user ID: {}", verifiedUser.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Email verified successfully! Your account has been created.");
            response.put("user", verifiedUser);

            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("Email verification failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Resend verification OTP
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestParam String email) {
        // Check if user already verified
        if (userService.findByEmail(email).isPresent()) {
            log.warn("Resend verification failed - already verified: {}", maskEmail(email));
            return ResponseEntity.badRequest().body(Map.of("error", "Email already verified. Please login."));
        }

        // Check if pending user exists
        var pendingUserOpt = userService.getPendingUserByEmail(email);
        if (pendingUserOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No pending registration found. Please register again."));
        }

        PendingUser pendingUser = pendingUserOpt.get();

        // Generate new OTP - DO NOT LOG OTP
        String otp = otpService.generateOtp();

        // Update pending user with new OTP
        userService.updatePendingUserWithOtp(pendingUser.getId(), otp);

        // Send new OTP via email
        emailService.sendOtpEmail(email, pendingUser.getFullName(), otp);
        log.info("Resent verification OTP to: {}", maskEmail(email));

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Verification OTP has been sent again. Please check your email.");

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.findAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return userService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<User> getUserByEmail(@PathVariable String email) {
        return userService.findByEmail(email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/role/{role}")
    public ResponseEntity<List<User>> getUsersByRole(@PathVariable String role) {
        return ResponseEntity.ok(userService.findUsersByRole(role));
    }

    @GetMapping("/active")
    public ResponseEntity<List<User>> getActiveUsers() {
        return ResponseEntity.ok(userService.findActiveUsers());
    }

    /**
     * Get all pending users (users who registered but haven't verified email)
     */
    @GetMapping("/pending")
    public ResponseEntity<List<PendingUser>> getPendingUsers() {
        return ResponseEntity.ok(userService.getAllPendingUsers());
    }

    /**
     * Debug endpoint - get pending user details by email
     */
    @GetMapping("/pending/debug")
    public ResponseEntity<?> getPendingUserDebug(@RequestParam String email) {
        var pendingUserOpt = userService.getPendingUserByEmail(email);
        if (pendingUserOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PendingUser pu = pendingUserOpt.get();
        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("email", pu.getEmail());
        debugInfo.put("fullName", pu.getFullName());
        debugInfo.put("role", pu.getRole());
        debugInfo.put("otp", pu.getVerificationToken());
        debugInfo.put("tokenExpiry", pu.getTokenExpiry());
        debugInfo.put("createdAt", pu.getCreatedAt());
        
        // Also get current OTP from OtpService for comparison
        String currentOtp = otpService.generateOtp();
        debugInfo.put("currentOtpFromService", currentOtp);
        
        return ResponseEntity.ok(debugInfo);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User user) {
        return ResponseEntity.ok(userService.updateUser(id, user));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<User> updateUserStatus(@PathVariable Long id, @RequestParam String status) {
        if (!"active".equalsIgnoreCase(status)
                && !"inactive".equalsIgnoreCase(status)
                && !"disabled".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("Status must be Active, Inactive, or Disabled");
        }

        return ResponseEntity.ok(userService.updateUserStatus(id, status));
    }

    @DeleteMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateUser(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/activate")
    public ResponseEntity<Void> activateUser(@PathVariable Long id) {
        userService.activateUser(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestParam String email, @RequestParam String otp) {
        log.info("OTP verification request for email: {}", maskEmail(email));
        log.info("OTP received: {}", otp);

        // First check if user is pending registration (this takes priority)
        // Pending users have OTP stored in the database that was sent via email
        var pendingUserOpt = userService.getPendingUserByEmail(email);
        log.info("Pending user found: {}", pendingUserOpt.isPresent());
        
        if (pendingUserOpt.isPresent()) {
            // New user registration - verify OTP and create user
            log.info("Found pending user, stored OTP: {}", pendingUserOpt.get().getVerificationToken());
            try {
                User verifiedUser = userService.verifyOtpAndCreateUser(email, otp);
                log.info("Registration verified successfully for: {}", maskEmail(email));

                Map<String, Object> response = new HashMap<>();
                response.put("message", "Email verified successfully! Your account has been created.");
                response.put("user", verifiedUser);

                log.info("Returning success response with user: {}", verifiedUser.getEmail());
                return ResponseEntity.ok(response);
            } catch (RuntimeException e) {
                log.warn("Verification failed for: {} - {}", maskEmail(email), e.getMessage());
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }

        // Check if user exists in main users table (for forgot password, resend OTP scenarios)
        log.info("Checking main users table for: {}", maskEmail(email));
        var existingUserOpt = userService.findByEmail(email);
        log.info("Existing user found: {}", existingUserOpt.isPresent());
        
        if (existingUserOpt.isPresent()) {
            // User exists - verify OTP using TOTP (Google Authenticator style)
            // This is used for forgot password or resend OTP flows
            boolean isValid = userService.verifyOtp(email, otp);

            if (isValid) {
                log.info("OTP verified successfully for existing user: {}", maskEmail(email));
                return ResponseEntity.ok(true);
            } else {
                log.warn("Invalid OTP for existing user: {}", maskEmail(email));
                return ResponseEntity.ok(false);
            }
        }

        log.warn("Verification failed - no user or pending user found: {}", maskEmail(email));
        return ResponseEntity.badRequest()
                .body(Map.of("error", "No user found with this email. Please register first."));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(@RequestParam String email) {
        // Get user details for sending email
        var userOpt = userService.findByEmail(email);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found with this email"));
        }

        // Generate and send the current OTP via email
        String currentOtp = otpService.generateOtp();

        User user = userOpt.get();
        emailService.sendOtpEmail(user.getEmail(), user.getFullName(), currentOtp);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "OTP has been sent to your email.");

        return ResponseEntity.ok(response);
    }

    // Forgot Password - sends OTP for password reset
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestParam String email) {
        // Get user details
        var userOpt = userService.findByEmail(email);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found with this email"));
        }

        // Generate and send the current OTP via email
        String currentOtp = otpService.generateOtp();




        User user = userOpt.get();
        emailService.sendOtpEmail(user.getEmail(), user.getFullName(), currentOtp);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "OTP has been sent to your email for password reset.");

        userService.storeOtpForResetPassword(email,currentOtp);

        return ResponseEntity.ok(response);
    }

    // Forgot Password - sends OTP for password reset
    @PostMapping("/verify/forgot-password")
    public ResponseEntity<?> verifyForgotPassword(@RequestBody ResetVerifyPasswordRequestDto dto)
    {
       boolean result = userService.verifyResetPasswordOtp(dto.getEmail(), dto.getCurrentOtp());

       if (result) {
           return ResponseEntity.ok(Map.of(
                   "success", true,
                   "message", "OTP verified successfully"));
       }

       return ResponseEntity.badRequest().body(Map.of(
               "success", false,
               "error", "Invalid or expired OTP"));


    }

    // Forgot Password - sends OTP for password reset
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordDto dto)
    {
       boolean result = userService.resetPassword(dto.getEmail(), dto.getCurrentOtp(), dto.getNewPassword());
       if (result) {

           return ResponseEntity.ok(Map.of(
                   "success", true,
                   "message", "Password reset successfully"));
       }

       return ResponseEntity.badRequest().body(Map.of(
               "success", false,
               "error", "Password reset failed"));
    }


    // Patient Assignment Endpoints for Doctors

    @GetMapping("/patients")
    public ResponseEntity<List<User>> getAllPatients() {
        return ResponseEntity.ok(userService.getAllPatients());
    }

    @GetMapping("/patients/unassigned")
    public ResponseEntity<List<User>> getUnassignedPatients() {
        return ResponseEntity.ok(userService.getUnassignedPatients());
    }

    @GetMapping("/doctor/{doctorId}/patients")
    public ResponseEntity<List<User>> getAssignedPatients(@PathVariable Long doctorId) {
        return ResponseEntity.ok(userService.getAssignedPatients(doctorId));
    }

    @PostMapping("/patients/{patientId}/assign")
    public ResponseEntity<User> assignPatient(
            @PathVariable Long patientId,
            @RequestParam Long doctorId) {
        return ResponseEntity.ok(userService.assignPatientToDoctor(patientId, doctorId));
    }

    @DeleteMapping("/patients/{patientId}/assign")
    public ResponseEntity<User> removePatientAssignment(@PathVariable Long patientId) {
        return ResponseEntity.ok(userService.removePatientAssignment(patientId));
    }

    // Get user document for viewing
    @GetMapping("/{id}/document/{docType}")
    public ResponseEntity<?> getUserDocument(@PathVariable Long id, @PathVariable String docType) {
        try {
            var userOpt = userService.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            User user = userOpt.get();
            String filePath = null;
            String fileName = null;

            switch (docType) {
                case "aadhar":
                    filePath = user.getAadharCard();
                    fileName = user.getAadharCardFileName();
                    break;
                case "pan":
                    filePath = user.getPanCard();
                    fileName = user.getPanCardFileName();
                    break;
                case "medical-council":
                    filePath = user.getMedicalCouncilRegistration();
                    fileName = user.getMedicalCouncilRegistrationFileName();
                    break;
                case "ug-certificate":
                    filePath = user.getUgCertificate();
                    fileName = user.getUgCertificateFileName();
                    break;
                case "pg-certificate":
                    filePath = user.getPgCertificate();
                    fileName = user.getPgCertificateFileName();
                    break;
                case "profile":
                case "avatar":
                case "profile-picture":
                    filePath = user.getProfilePicture();
                    fileName = user.getProfilePictureFileName();
                    break;
                default:
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid document type"));
            }

            log.debug("Document request - ID: {}, docType: {}", id, docType);

            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            try {
                Path fullPath = resolveUserDocumentPath(filePath, id);

                log.debug("Trying path: {}", fullPath.toAbsolutePath());

                Resource resource = new UrlResource(fullPath.toUri());

                if (resource.exists()) {
                    log.debug("File found: {}", fileName);
                    String contentType = "application/octet-stream";
                    if (fileName != null) {
                        if (fileName.toLowerCase().endsWith(".pdf")) {
                            contentType = "application/pdf";
                        } else if (fileName.toLowerCase().endsWith(".jpg")
                                || fileName.toLowerCase().endsWith(".jpeg")) {
                            contentType = "image/jpeg";
                        } else if (fileName.toLowerCase().endsWith(".png")) {
                            contentType = "image/png";
                        }
                    }

                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(contentType))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename=\"" + (fileName != null ? fileName : "document") + "\"")
                            .body(resource);
                } else {
                    log.warn("File does not exist at: {}", fullPath.toAbsolutePath());
                }
            } catch (Exception e) {
                log.error("Error loading file for user {}: {}", id, e.getMessage());
            }

            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private Path resolveUserDocumentPath(String filePath, Long userId) throws IOException {
        String normalizedFilePath = filePath.replace("\\", "/");
        String fileName = Paths.get(normalizedFilePath).getFileName().toString();
        Path backendRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path configuredUploadDir = resolveConfiguredUploadDirectory();
        List<Path> candidates = new ArrayList<>();

        if (normalizedFilePath.startsWith("/uploads/documents/")) {
            String relativePath = normalizedFilePath.replace("/uploads/documents/", "");
            candidates.add(configuredUploadDir.resolve(relativePath));
            candidates.add(backendRoot.resolve("uploads").resolve("documents").resolve(relativePath));
        }

        if (normalizedFilePath.startsWith("/uploads/")) {
            String relativePath = normalizedFilePath.replace("/uploads/", "");
            candidates.add(backendRoot.resolve("uploads").resolve(relativePath));
        }

        candidates.add(configuredUploadDir.resolve("doctor_" + userId).resolve(fileName));
        candidates.add(backendRoot.resolve("uploads").resolve("documents").resolve("doctor_" + userId).resolve(fileName));

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
        }

        try (Stream<Path> stream = Files.walk(backendRoot, 4)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .filter(path -> path.toString().contains("doctor_" + userId))
                    .findFirst()
                    .orElse(candidates.get(0));
        }
    }

    private Path resolveConfiguredUploadDirectory() {
        Path configuredPath = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        String normalizedPath = configuredPath.toString().replace("\\", "/");

        if (normalizedPath.endsWith("/uploads/documents") || normalizedPath.endsWith("/documents")) {
            return configuredPath;
        }

        if (normalizedPath.endsWith("/uploads")) {
            return configuredPath.resolve("documents");
        }

        return configuredPath.resolve("uploads").resolve("documents");
    }

    @GetMapping("/{id}/profile-picture")
    public ResponseEntity<?> getUserProfilePicture(@PathVariable Long id) {
        return getUserDocument(id, "profile");
    }
    
    // Upload prescription document for online pharmacy order
    // This endpoint is called when user submits documents for online pharmacy
    @PostMapping("/upload/pharmacy")
    public ResponseEntity<?> uploadPharmacyDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") Long userId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "description", required = false) String description) {
        try {
            log.info("Pharmacy document upload request - userId: {}, orderId: {}", userId, orderId);
            
            // Find the user
            var userOpt = userService.findById(userId);
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }
            
            // Create upload directory path
            String uploadDir = System.getProperty("user.dir") + "/uploads/documents";
            Path uploadsPath = Paths.get(uploadDir);
            
            // Create order-specific folder if orderId is provided
            Path targetDir;
            String relativePath;
            
            if (orderId != null) {
                targetDir = uploadsPath.resolve("order_" + orderId).resolve("prescriptions");
                relativePath = "/uploads/documents/order_" + orderId + "/prescriptions/";
            } else {
                // Use user folder if no order ID
                targetDir = uploadsPath.resolve("user_" + userId).resolve("pharmacy");
                relativePath = "/uploads/documents/user_" + userId + "/pharmacy/";
            }
            
            // Create directory if not exists
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }
            
            // Generate unique filename
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueFileName = "prescription_" + System.currentTimeMillis() + extension;
            
            // Save the file
            Path filePath = targetDir.resolve(uniqueFileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            
            log.info("Pharmacy document saved to: {}", filePath);
            
            // Return success response with file path
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Document uploaded successfully");
            response.put("fileName", uniqueFileName);
            response.put("filePath", relativePath + uniqueFileName);
            
            // If orderId is provided, update the order's prescription path and status to SUBMITTED
            if (orderId != null) {
                try {
                    var orderOpt = orderService.findById(orderId);
                    if (orderOpt.isPresent()) {
                        com.rxincredible.entity.Order order = orderOpt.get();
                        order.setPrescriptionPath(relativePath + uniqueFileName);
                        order.setStatus("SUBMITTED");
                        orderService.updateOrder(orderId, Map.of(
                            "prescriptionPath", relativePath + uniqueFileName,
                            "status", "SUBMITTED"
                        ));
                        response.put("orderId", orderId);
                        response.put("prescriptionPath", relativePath + uniqueFileName);
                        response.put("orderStatus", "SUBMITTED");
                        log.info("Order {} status updated to SUBMITTED with prescription path", orderId);
                    }
                } catch (Exception e) {
                    log.warn("Could not update order prescription path: {}", e.getMessage());
                }
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error uploading pharmacy document: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to upload document: " + e.getMessage()));
        }
    }
    
    // Submit online pharmacy order with delivery address
    // This endpoint is called when user fills delivery address and clicks submit
    @PostMapping("/submit-pharmacy-order")
    public ResponseEntity<?> submitPharmacyOrder(@RequestBody Map<String, Object> request) {
        try {
            Long userId = Long.valueOf(request.get("userId").toString());
            String deliveryAddress = (String) request.get("deliveryAddress");
            String deliveryCity = (String) request.get("deliveryCity");
            String deliveryState = (String) request.get("deliveryState");
            String deliveryPincode = (String) request.get("deliveryPincode");
            String deliveryPhone = (String) request.get("deliveryPhone");
            
            log.info("Submit pharmacy order request - userId: {}", userId);
            
            // Find the user
            var userOpt = userService.findById(userId);
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }
            
            // Create a new order for online pharmacy
            com.rxincredible.entity.Order order = new com.rxincredible.entity.Order();
            order.setUser(userOpt.get());
            order.setServiceType("ONLINE_PHARMACY");
            order.setStatus("SUBMITTED");
            order.setPaymentStatus("PENDING");
            order.setPaymentMethod("NONE");
            
            // Set delivery address
            order.setDeliveryAddress(deliveryAddress);
            order.setDeliveryCity(deliveryCity);
            order.setDeliveryState(deliveryState);
            order.setDeliveryPincode(deliveryPincode);
            order.setDeliveryPhone(deliveryPhone);
            
            // Set total amount to 0 for online pharmacy (will be calculated later)
            order.setTotalAmount(java.math.BigDecimal.ZERO);
            
            // Create the order
            com.rxincredible.entity.Order savedOrder = orderService.createOrder(order, userId);
            
            log.info("Pharmacy order created with ID: {}, Order Number: {}", savedOrder.getId(), savedOrder.getOrderNumber());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Order submitted successfully");
            response.put("orderId", savedOrder.getId());
            response.put("orderNumber", savedOrder.getOrderNumber());
            response.put("status", savedOrder.getStatus());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error submitting pharmacy order: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to submit order: " + e.getMessage()));
        }
    }
}
