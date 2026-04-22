package com.rxincredible.service;

import com.rxincredible.entity.PendingUser;
import com.rxincredible.entity.User;
import com.rxincredible.repository.PendingUserRepository;
import com.rxincredible.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {
    
    private final UserRepository userRepository;
    private final PendingUserRepository pendingUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final EmailService emailService;
    
    @Value("${app.upload.directory:uploads}")
    private String uploadDirectory;
    
    public UserService(UserRepository userRepository, PendingUserRepository pendingUserRepository, PasswordEncoder passwordEncoder, OtpService otpService, EmailService emailService) {
        this.userRepository = userRepository;
        this.pendingUserRepository = pendingUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
        this.emailService = emailService;
    }
    
    @Transactional
    public User registerUser(User user) {
        user.setEmail(normalizeEmail(user.getEmail()));

        if (userRepository.existsByEmail(user.getEmail())) {
            throw new RuntimeException("Email already exists");
        }
        
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        
        // For Google Authenticator, we don't store OTP - it's generated on-the-fly by the user's app
        // The user will need to enter the OTP from their Google Authenticator after registration
        user.setOtp(null);
        user.setOtpExpiry(null);
        
        // Set default values for age and gender if not provided
        if (user.getAge() == null) {
            user.setAge(0);
        }
        if (user.getGender() == null || user.getGender().isEmpty()) {
            user.setGender("NotSpecified");
        }
        
        // For doctors, DON'T auto-activate - they need to verify email first
        // unless explicitly set to false
        if (user.getRole() != null && user.getRole().equals("DOCTOR")) {
            if (user.getSpecialization() == null) {
                user.setSpecialization("");
            }
            if (user.getLicenseNumber() == null) {
                user.setLicenseNumber("");
            }
            if (user.getExperienceYears() == null) {
                user.setExperienceYears(0);
            }
            if (user.getQualifications() == null) {
                user.setQualifications("");
            }
            // DO NOT auto-activate doctors - they must verify email first
            // This is handled by the pending user flow
            if (user.getIsActive() == null) {
                user.setIsActive(false); // Will be activated after email verification
            }
            // DO NOT mark doctors as verified - they must verify email first
            user.setIsVerified(false);
        } else {
            // Auto-activate all other users (ACCOUNTANT, ADMIN, USER) registered by admin
            if (user.getIsActive() == null) {
                user.setIsActive(true);
            }
            if (user.getIsVerified() == null) {
                user.setIsVerified(true);
            }
        }
        
        User savedUser = userRepository.save(user);
        return persistDoctorAssets(savedUser);
    }
    
    /**
     * Save user as pending (temporary storage) - email verification required
     */
    @Transactional
    public PendingUser savePendingUser(User user, String verificationToken) {
        user.setEmail(normalizeEmail(user.getEmail()));
        System.out.println("--- savePendingUser called ---");
        System.out.println("Email: " + user.getEmail());
        System.out.println("Role: " + user.getRole());
        
        if (userRepository.existsByEmail(user.getEmail())) {
            System.out.println("ERROR: Email already exists in users table");
            throw new RuntimeException("Email already exists");
        }
        
        if (pendingUserRepository.existsByEmail(user.getEmail())) {
            System.out.println("ERROR: Email already pending verification");
            throw new RuntimeException("Verification email already sent. Please verify or try again.");
        }
        
        // Create pending user entity
        PendingUser pendingUser = new PendingUser();
        pendingUser.setEmail(user.getEmail());
        pendingUser.setPassword(passwordEncoder.encode(user.getPassword()));
        pendingUser.setFullName(user.getFullName());
        pendingUser.setRole(user.getRole() != null ? user.getRole() : "USER");
        pendingUser.setPhone(user.getPhone());
        pendingUser.setAddress(user.getAddress());
        pendingUser.setCountry(user.getCountry());
        pendingUser.setAge(user.getAge());
        pendingUser.setGender(user.getGender());
        pendingUser.setSpecialization(user.getSpecialization());
        pendingUser.setQualifications(user.getQualifications());
        pendingUser.setLicenseNumber(user.getLicenseNumber());
        pendingUser.setAadharCard(user.getAadharCard());
        pendingUser.setPanCard(user.getPanCard());
        pendingUser.setMedicalCouncilRegistration(user.getMedicalCouncilRegistration());
        pendingUser.setUgCertificate(user.getUgCertificate());
        pendingUser.setPgCertificate(user.getPgCertificate());
        pendingUser.setProfilePicture(user.getProfilePicture());
        pendingUser.setAadharCardFileName(user.getAadharCardFileName());
        pendingUser.setPanCardFileName(user.getPanCardFileName());
        pendingUser.setMedicalCouncilRegistrationFileName(user.getMedicalCouncilRegistrationFileName());
        pendingUser.setUgCertificateFileName(user.getUgCertificateFileName());
        pendingUser.setPgCertificateFileName(user.getPgCertificateFileName());
        pendingUser.setProfilePictureFileName(user.getProfilePictureFileName());
        pendingUser.setExperienceYears(user.getExperienceYears());
        pendingUser.setVerificationToken(verificationToken);
        pendingUser.setTokenExpiry(java.time.LocalDateTime.now().plusHours(24)); // Token valid for 24 hours
        
        PendingUser saved = pendingUserRepository.save(pendingUser);
        System.out.println("PendingUser saved with ID: " + saved.getId());
        System.out.println("Verification token: " + verificationToken);
        
        return saved;
    }
    
    /**
     * Save user as pending with OTP - for email verification
     */
    @Transactional
    public PendingUser savePendingUserWithOtp(User user, String otp) {
        user.setEmail(normalizeEmail(user.getEmail()));
        System.out.println("--- savePendingUserWithOtp called ---");
        System.out.println("Email: " + user.getEmail());
        System.out.println("Role: " + user.getRole());
        
        if (userRepository.existsByEmail(user.getEmail())) {
            System.out.println("ERROR: Email already exists in users table");
            throw new RuntimeException("Email already exists");
        }
        
        if (pendingUserRepository.existsByEmail(user.getEmail())) {
            System.out.println("ERROR: Email already pending verification");
            throw new RuntimeException("Verification email already sent. Please verify or try again.");
        }
        
        // Create pending user entity with OTP
        PendingUser pendingUser = new PendingUser();
        pendingUser.setEmail(user.getEmail());
        pendingUser.setPassword(passwordEncoder.encode(user.getPassword()));
        pendingUser.setFullName(user.getFullName());
        pendingUser.setRole(user.getRole() != null ? user.getRole() : "USER");
        pendingUser.setPhone(user.getPhone());
        pendingUser.setAddress(user.getAddress());
        pendingUser.setCountry(user.getCountry());
        pendingUser.setAge(user.getAge());
        pendingUser.setGender(user.getGender());
        pendingUser.setSpecialization(user.getSpecialization());
        pendingUser.setQualifications(user.getQualifications());
        pendingUser.setLicenseNumber(user.getLicenseNumber());
        pendingUser.setAadharCard(user.getAadharCard());
        pendingUser.setPanCard(user.getPanCard());
        pendingUser.setMedicalCouncilRegistration(user.getMedicalCouncilRegistration());
        pendingUser.setUgCertificate(user.getUgCertificate());
        pendingUser.setPgCertificate(user.getPgCertificate());
        pendingUser.setProfilePicture(user.getProfilePicture());
        pendingUser.setAadharCardFileName(user.getAadharCardFileName());
        pendingUser.setPanCardFileName(user.getPanCardFileName());
        pendingUser.setMedicalCouncilRegistrationFileName(user.getMedicalCouncilRegistrationFileName());
        pendingUser.setUgCertificateFileName(user.getUgCertificateFileName());
        pendingUser.setPgCertificateFileName(user.getPgCertificateFileName());
        pendingUser.setProfilePictureFileName(user.getProfilePictureFileName());
        pendingUser.setExperienceYears(user.getExperienceYears());
        // Use OTP as verification token
        pendingUser.setVerificationToken(otp);
        // OTP valid for 5 minutes
        pendingUser.setTokenExpiry(java.time.LocalDateTime.now().plusMinutes(5));
        
        PendingUser saved = pendingUserRepository.save(pendingUser);
        System.out.println("PendingUser saved with ID: " + saved.getId());
        System.out.println("OTP: " + otp);
        
        return saved;
    }
    
    /**
     * Verify OTP and convert pending user to actual user
     */
    @Transactional
    public User verifyOtpAndCreateUser(String email, String otp) {
        email = normalizeEmail(email);
        System.out.println("--- verifyOtpAndCreateUser called ---");
        System.out.println("Email: " + email);
        System.out.println("OTP received: '" + otp + "'");
        
        PendingUser pendingUser = pendingUserRepository.findByEmail(email)
                .orElseThrow(() -> {
                    System.out.println("ERROR: No pending user found for this email");
                    return new RuntimeException("No pending registration found for this email");
                });
        
        System.out.println("Found pending user: " + pendingUser.getEmail());
        System.out.println("Stored OTP: '" + pendingUser.getVerificationToken() + "'");
        System.out.println("Token expiry: " + pendingUser.getTokenExpiry());
        System.out.println("Current time: " + java.time.LocalDateTime.now());
        System.out.println("OTP match: " + pendingUser.getVerificationToken().equals(otp));
        
        // Check if OTP matches
        if (!pendingUser.getVerificationToken().equals(otp)) {
            System.out.println("ERROR: Invalid OTP");
            throw new RuntimeException("Invalid OTP. Please try again.");
        }
        
        // Check if token/OTP is expired
        if (pendingUser.getTokenExpiry().isBefore(java.time.LocalDateTime.now())) {
            System.out.println("ERROR: OTP expired");
            pendingUserRepository.delete(pendingUser);
            throw new RuntimeException("OTP has expired. Please register again.");
        }
        
        // Check if email already exists in main users table
        if (userRepository.existsByEmail(pendingUser.getEmail())) {
            System.out.println("ERROR: Email already verified in users table");
            pendingUserRepository.delete(pendingUser);
            throw new RuntimeException("Email already verified. Please login.");
        }
        
        System.out.println("Creating user from pending user...");
        
        // Create user from pending user
        User user = new User();
        user.setEmail(pendingUser.getEmail());
        user.setPassword(pendingUser.getPassword());
        user.setFullName(pendingUser.getFullName());
        user.setRole(pendingUser.getRole());
        user.setPhone(pendingUser.getPhone());
        user.setAddress(pendingUser.getAddress());
        user.setCountry(pendingUser.getCountry());
        user.setAge(pendingUser.getAge());
        user.setGender(pendingUser.getGender());
        user.setSpecialization(pendingUser.getSpecialization());
        user.setQualifications(pendingUser.getQualifications());
        user.setLicenseNumber(pendingUser.getLicenseNumber());
        user.setAadharCard(pendingUser.getAadharCard());
        user.setPanCard(pendingUser.getPanCard());
        user.setMedicalCouncilRegistration(pendingUser.getMedicalCouncilRegistration());
        user.setUgCertificate(pendingUser.getUgCertificate());
        user.setPgCertificate(pendingUser.getPgCertificate());
        user.setProfilePicture(pendingUser.getProfilePicture());
        user.setAadharCardFileName(pendingUser.getAadharCardFileName());
        user.setPanCardFileName(pendingUser.getPanCardFileName());
        user.setMedicalCouncilRegistrationFileName(pendingUser.getMedicalCouncilRegistrationFileName());
        user.setUgCertificateFileName(pendingUser.getUgCertificateFileName());
        user.setPgCertificateFileName(pendingUser.getPgCertificateFileName());
        user.setProfilePictureFileName(pendingUser.getProfilePictureFileName());
        user.setExperienceYears(pendingUser.getExperienceYears());
        user.setIsVerified(true); // Mark as verified
        user.setIsActive(true);
        
        // Set default values
        if (user.getAge() == null) {
            user.setAge(0);
        }
        if (user.getGender() == null || user.getGender().isEmpty()) {
            user.setGender("NotSpecified");
        }
        
        // Set default values for doctor-specific fields
        if (user.getRole() != null && user.getRole().equals("DOCTOR")) {
            if (user.getSpecialization() == null) {
                user.setSpecialization("");
            }
            if (user.getLicenseNumber() == null) {
                user.setLicenseNumber("");
            }
            if (user.getExperienceYears() == null) {
                user.setExperienceYears(0);
            }
            if (user.getQualifications() == null) {
                user.setQualifications("");
            }
        }
        
        // Save user
        User savedUser = userRepository.save(user);
        savedUser = persistDoctorAssets(savedUser);
        System.out.println("User saved to users table with ID: " + savedUser.getId());
        
        // Delete pending user after successful verification
        pendingUserRepository.delete(pendingUser);
        System.out.println("Pending user deleted");
        
        return savedUser;
    }
    
    /**
     * Verify email and convert pending user to actual user
     */
    @Transactional
    public User verifyAndCreateUser(String token) {
        System.out.println("--- verifyAndCreateUser called ---");
        System.out.println("Token: " + token);
        
        PendingUser pendingUser = pendingUserRepository.findByVerificationToken(token)
                .orElseThrow(() -> {
                    System.out.println("ERROR: Invalid verification token");
                    return new RuntimeException("Invalid or expired verification token");
                });
        
        System.out.println("Found pending user: " + pendingUser.getEmail());
        
        // Check if token is expired
        if (pendingUser.getTokenExpiry().isBefore(java.time.LocalDateTime.now())) {
            System.out.println("ERROR: Token expired");
            pendingUserRepository.delete(pendingUser);
            throw new RuntimeException("Verification token has expired. Please register again.");
        }
        
        // Check if email already exists in main users table
        if (userRepository.existsByEmail(pendingUser.getEmail())) {
            System.out.println("ERROR: Email already verified in users table");
            pendingUserRepository.delete(pendingUser);
            throw new RuntimeException("Email already verified. Please login.");
        }
        
        System.out.println("Creating user from pending user...");
        
        // Create user from pending user
        User user = new User();
        user.setEmail(pendingUser.getEmail());
        user.setPassword(pendingUser.getPassword());
        user.setFullName(pendingUser.getFullName());
        user.setRole(pendingUser.getRole());
        user.setPhone(pendingUser.getPhone());
        user.setAddress(pendingUser.getAddress());
        user.setCountry(pendingUser.getCountry());
        user.setAge(pendingUser.getAge());
        user.setGender(pendingUser.getGender());
        user.setSpecialization(pendingUser.getSpecialization());
        user.setQualifications(pendingUser.getQualifications());
        user.setLicenseNumber(pendingUser.getLicenseNumber());
        user.setAadharCard(pendingUser.getAadharCard());
        user.setPanCard(pendingUser.getPanCard());
        user.setMedicalCouncilRegistration(pendingUser.getMedicalCouncilRegistration());
        user.setUgCertificate(pendingUser.getUgCertificate());
        user.setPgCertificate(pendingUser.getPgCertificate());
        user.setProfilePicture(pendingUser.getProfilePicture());
        user.setAadharCardFileName(pendingUser.getAadharCardFileName());
        user.setPanCardFileName(pendingUser.getPanCardFileName());
        user.setMedicalCouncilRegistrationFileName(pendingUser.getMedicalCouncilRegistrationFileName());
        user.setUgCertificateFileName(pendingUser.getUgCertificateFileName());
        user.setPgCertificateFileName(pendingUser.getPgCertificateFileName());
        user.setProfilePictureFileName(pendingUser.getProfilePictureFileName());
        user.setExperienceYears(pendingUser.getExperienceYears());
        user.setIsVerified(true); // Mark as verified
        user.setIsActive(true);
        
        // Set default values
        if (user.getAge() == null) {
            user.setAge(0);
        }
        if (user.getGender() == null || user.getGender().isEmpty()) {
            user.setGender("NotSpecified");
        }
        
        // Set default values for doctor-specific fields
        if (user.getRole() != null && user.getRole().equals("DOCTOR")) {
            if (user.getSpecialization() == null) {
                user.setSpecialization("");
            }
            if (user.getLicenseNumber() == null) {
                user.setLicenseNumber("");
            }
            if (user.getExperienceYears() == null) {
                user.setExperienceYears(0);
            }
            if (user.getQualifications() == null) {
                user.setQualifications("");
            }
        }
        
        // Save user
        User savedUser = userRepository.save(user);
        savedUser = persistDoctorAssets(savedUser);
        System.out.println("User saved to users table with ID: " + savedUser.getId());
        
        // Delete pending user after successful verification
        pendingUserRepository.delete(pendingUser);
        System.out.println("Pending user deleted");
        
        return savedUser;
    }
    
    /**
     * Check if email is pending verification
     */
    public boolean isEmailPending(String email) {
        return pendingUserRepository.existsByEmail(normalizeEmail(email));
    }
    
    /**
     * Get pending user by email
     */
    public Optional<PendingUser> getPendingUserByEmail(String email) {
        return pendingUserRepository.findByEmail(normalizeEmail(email));
    }

    /**
     *  store otp in db with time
     */
    @Transactional
    public String storeOtpForResetPassword(String email, String currentOtp)
    {
        User pu = userRepository.findByEmail(normalizeEmail(email)).orElseThrow(() -> new RuntimeException("User not found"));
        pu.setOtp(currentOtp);
        pu.setOtpExpiry(LocalDateTime.now().plusMinutes(5));
        userRepository.save(pu);
        return "Otp Stored Successfully";
    }


    /**
     *  verify Forget otp in db
     */
    public boolean verifyResetPasswordOtp(String email, String currentOtp)
    {
        User pu = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check OTP exists
        if (pu.getOtp() == null || pu.getOtpExpiry() == null) {
            return false;
        }

        // Check OTP match
        if (!pu.getOtp().equals(currentOtp)) {
            return false;
        }

        // Check expiry
        if (pu.getOtpExpiry().isBefore(LocalDateTime.now())) {
            return false;
        }

        // ✅ Valid OTP → clear and save
        return true;
    }

    /**
     *  reset Password
     */
@Transactional
    public boolean resetPassword(String email, String currentOtp, String newPassword)
    {
        String normalizedEmail = normalizeEmail(email);
        System.out.println("=== resetPassword called ===");
        System.out.println("Email: " + normalizedEmail);
        System.out.println("New password length: " + (newPassword != null ? newPassword.length() : 0));
        
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new RuntimeException("User not found: " + normalizedEmail));
        
        System.out.println("User found with ID: " + user.getId());

        if (!verifyResetPasswordOtp(email, currentOtp)) {
            System.out.println("OTP verification FAILED");
            return false;
        }
        
        System.out.println("OTP verified, now encoding password...");
        
        if (newPassword == null || newPassword.length() < 6) {
            System.out.println("ERROR: Password too short or null!");
            return false;
        }
        
        String encodedPassword = passwordEncoder.encode(newPassword);
        System.out.println("Encoded password length: " + (encodedPassword != null ? encodedPassword.length() : 0));
        
        user.setPassword(encodedPassword);
        user.setOtp(null);
        user.setOtpExpiry(null);
        userRepository.saveAndFlush(user);
        
        System.out.println("Password reset complete for user: " + normalizedEmail);

        return true;
    }


    /**
     * Delete pending user (for cleanup or resend)
     */
    @Transactional
    public void deletePendingUser(String email) {
        pendingUserRepository.deleteByEmail(normalizeEmail(email));
    }
    
    /**
     * Update pending user with new verification token
     */
    @Transactional
    public void updatePendingUser(Long id, String newToken) {
        PendingUser pendingUser = pendingUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Pending user not found"));
        pendingUser.setVerificationToken(newToken);
        pendingUser.setTokenExpiry(java.time.LocalDateTime.now().plusHours(24));
        pendingUserRepository.save(pendingUser);
    }
    
    /**
     * Update pending user with new OTP
     */
    @Transactional
    public void updatePendingUserWithOtp(Long id, String newOtp) {
        PendingUser pendingUser = pendingUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Pending user not found"));
        pendingUser.setVerificationToken(newOtp);
        pendingUser.setTokenExpiry(java.time.LocalDateTime.now().plusMinutes(5)); // OTP valid for 5 minutes
        pendingUserRepository.save(pendingUser);
    }
    
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email));
    }
    
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
    
    public List<User> findAllUsers() {
        return userRepository.findAll();
    }
    
    public List<User> findUsersByRole(String role) {
        return userRepository.findByRole(role);
    }
    
    public List<User> findActiveUsers() {
        return userRepository.findByIsActiveTrue();
    }
    
    @Transactional
    public User updateUser(Long id, User userDetails) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (userDetails.getFullName() != null) {
            user.setFullName(userDetails.getFullName());
        }
        if (userDetails.getPhone() != null) {
            user.setPhone(userDetails.getPhone());
        }
        if (userDetails.getAddress() != null) {
            user.setAddress(userDetails.getAddress());
        }

        if (userDetails.getRole() != null && !userDetails.getRole().isBlank()) {
            user.setRole(userDetails.getRole());
        }
        if (userDetails.getStatus() != null && !userDetails.getStatus().isBlank()) {
            user.setStatus(userDetails.getStatus());
        } else if (userDetails.getIsActive() != null) {
            user.setIsActive(userDetails.getIsActive());
        }
        
        // Update city, state, pincode, country and delivery phone
        if (userDetails.getCity() != null) {
            user.setCity(userDetails.getCity());
        }
        if (userDetails.getState() != null) {
            user.setState(userDetails.getState());
        }
        if (userDetails.getPincode() != null) {
            user.setPincode(userDetails.getPincode());
        }
        if (userDetails.getCountry() != null) {
            user.setCountry(userDetails.getCountry());
        }
        if (userDetails.getDeliveryPhone() != null) {
            user.setDeliveryPhone(userDetails.getDeliveryPhone());
        }
        
        // Update age and gender if provided
        if (userDetails.getAge() != null) {
            user.setAge(userDetails.getAge());
        }
        if (userDetails.getGender() != null && !userDetails.getGender().isEmpty()) {
            user.setGender(userDetails.getGender());
        }
        
        // Update height and weight if provided
        if (userDetails.getHeight() != null) {
            user.setHeight(userDetails.getHeight());
        }
        if (userDetails.getWeight() != null) {
            user.setWeight(userDetails.getWeight());
        }
        
        // Update doctor-specific fields if provided
        if (userDetails.getSpecialization() != null) {
            user.setSpecialization(userDetails.getSpecialization());
        }
        if (userDetails.getLicenseNumber() != null) {
            user.setLicenseNumber(userDetails.getLicenseNumber());
        }
        if (userDetails.getExperienceYears() != null) {
            user.setExperienceYears(userDetails.getExperienceYears());
        }
        if (userDetails.getQualifications() != null) {
            user.setQualifications(userDetails.getQualifications());
        }
        
        // Update document fields - save as files in uploads/documents/doctor_{id}/
        if (userDetails.getAadharCard() != null && !userDetails.getAadharCard().isEmpty()) {
            String filePath = saveDocument(userDetails.getAadharCard(), userDetails.getAadharCardFileName(), id, "aadhar");
            user.setAadharCard(filePath);
            user.setAadharCardFileName(userDetails.getAadharCardFileName());
        }
        if (userDetails.getAadharCardFileName() != null && user.getAadharCard() == null) {
            user.setAadharCardFileName(userDetails.getAadharCardFileName());
        }
        
        if (userDetails.getPanCard() != null && !userDetails.getPanCard().isEmpty()) {
            String filePath = saveDocument(userDetails.getPanCard(), userDetails.getPanCardFileName(), id, "pan");
            user.setPanCard(filePath);
            user.setPanCardFileName(userDetails.getPanCardFileName());
        }
        if (userDetails.getPanCardFileName() != null && user.getPanCard() == null) {
            user.setPanCardFileName(userDetails.getPanCardFileName());
        }
        
        if (userDetails.getMedicalCouncilRegistration() != null && !userDetails.getMedicalCouncilRegistration().isEmpty()) {
            String filePath = saveDocument(userDetails.getMedicalCouncilRegistration(),
                    userDetails.getMedicalCouncilRegistrationFileName(), id, "medical-council");
            user.setMedicalCouncilRegistration(filePath);
            user.setMedicalCouncilRegistrationFileName(userDetails.getMedicalCouncilRegistrationFileName());
        }
        if (userDetails.getMedicalCouncilRegistrationFileName() != null && user.getMedicalCouncilRegistration() == null) {
            user.setMedicalCouncilRegistrationFileName(userDetails.getMedicalCouncilRegistrationFileName());
        }

        if (userDetails.getUgCertificate() != null && !userDetails.getUgCertificate().isEmpty()) {
            String filePath = saveDocument(userDetails.getUgCertificate(), userDetails.getUgCertificateFileName(), id,
                    "ug-certificate");
            user.setUgCertificate(filePath);
            user.setUgCertificateFileName(userDetails.getUgCertificateFileName());
        }
        if (userDetails.getUgCertificateFileName() != null && user.getUgCertificate() == null) {
            user.setUgCertificateFileName(userDetails.getUgCertificateFileName());
        }

        if (userDetails.getPgCertificate() != null && !userDetails.getPgCertificate().isEmpty()) {
            String filePath = saveDocument(userDetails.getPgCertificate(), userDetails.getPgCertificateFileName(), id,
                    "pg-certificate");
            user.setPgCertificate(filePath);
            user.setPgCertificateFileName(userDetails.getPgCertificateFileName());
        }
        if (userDetails.getPgCertificateFileName() != null && user.getPgCertificate() == null) {
            user.setPgCertificateFileName(userDetails.getPgCertificateFileName());
        }

        if (userDetails.getProfilePicture() != null && !userDetails.getProfilePicture().isEmpty()) {
            String filePath = saveDocument(userDetails.getProfilePicture(), userDetails.getProfilePictureFileName(), id,
                    "profile");
            user.setProfilePicture(filePath);
            user.setProfilePictureFileName(userDetails.getProfilePictureFileName());
        }
        if (userDetails.getProfilePictureFileName() != null && user.getProfilePicture() == null) {
            user.setProfilePictureFileName(userDetails.getProfilePictureFileName());
        }
        
        if (userDetails.getPassword() != null && !userDetails.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(userDetails.getPassword()));
        }
        
        User savedUser = userRepository.save(user);
        return persistDoctorAssets(savedUser);
    }
    
    // Helper method to save document as file
    private String saveDocument(String base64Data, String fileName, Long userId, String docType) {
        try {
            Path baseUploadDir = resolveDocumentUploadDirectory();
            Path doctorDir = baseUploadDir.resolve("doctor_" + userId);
            if (!Files.exists(doctorDir)) {
                Files.createDirectories(doctorDir);
            }
            
            // Generate unique filename
            String extension = "";
            if (fileName != null && fileName.contains(".")) {
                extension = fileName.substring(fileName.lastIndexOf("."));
            }
            String uniqueFileName = docType + "_" + UUID.randomUUID().toString() + extension;
            
            // Decode base64 and save file
            String base64Clean = base64Data;
            if (base64Data.contains(",")) {
                base64Clean = base64Data.substring(base64Data.indexOf(",") + 1);
            }
            byte[] decodedBytes = Base64.getDecoder().decode(base64Clean);
            Path filePath = doctorDir.resolve(uniqueFileName);
            Files.write(filePath, decodedBytes);
            
            // Return relative path from uploads folder
            return "/uploads/documents/doctor_" + userId + "/" + uniqueFileName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save document: " + e.getMessage(), e);
        }
    }

    private Path resolveDocumentUploadDirectory() {
        Path configuredPath = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        String normalizedPath = configuredPath.toString().replace("\\", "/");

        if (normalizedPath.endsWith("/uploads/documents")) {
            return configuredPath;
        }

        if (normalizedPath.endsWith("/uploads")) {
            return configuredPath.resolve("documents");
        }

        if (normalizedPath.endsWith("/documents")) {
            return configuredPath;
        }

        return configuredPath.resolve("uploads").resolve("documents");
    }

    private User persistDoctorAssets(User user) {
        boolean updated = false;

        if (hasInlineUpload(user.getAadharCard())) {
            user.setAadharCard(saveDocument(user.getAadharCard(), user.getAadharCardFileName(), user.getId(), "aadhar"));
            updated = true;
        }

        if (hasInlineUpload(user.getPanCard())) {
            user.setPanCard(saveDocument(user.getPanCard(), user.getPanCardFileName(), user.getId(), "pan"));
            updated = true;
        }

        if (hasInlineUpload(user.getMedicalCouncilRegistration())) {
            user.setMedicalCouncilRegistration(saveDocument(user.getMedicalCouncilRegistration(),
                    user.getMedicalCouncilRegistrationFileName(), user.getId(), "medical-council"));
            updated = true;
        }

        if (hasInlineUpload(user.getUgCertificate())) {
            user.setUgCertificate(saveDocument(user.getUgCertificate(),
                    user.getUgCertificateFileName(), user.getId(), "ug-certificate"));
            updated = true;
        }

        if (hasInlineUpload(user.getPgCertificate())) {
            user.setPgCertificate(saveDocument(user.getPgCertificate(),
                    user.getPgCertificateFileName(), user.getId(), "pg-certificate"));
            updated = true;
        }

        if (hasInlineUpload(user.getProfilePicture())) {
            user.setProfilePicture(saveDocument(user.getProfilePicture(), user.getProfilePictureFileName(), user.getId(),
                    "profile"));
            updated = true;
        }

        if (!updated) {
            return user;
        }

        User savedUser = userRepository.save(user);
        return persistDoctorAssets(savedUser);
    }

    private boolean hasInlineUpload(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String trimmedValue = value.trim();
        return !trimmedValue.startsWith("/uploads/")
                && !trimmedValue.startsWith("http://")
                && !trimmedValue.startsWith("https://");
    }
    
    @Transactional
    public void deactivateUser(Long id) {
        updateUserStatus(id, "Inactive");
    }
    
    @Transactional
    public void activateUser(Long id) {
        updateUserStatus(id, "Active");
    }

    @Transactional
    public User updateUserStatus(Long id, String status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus(status);
        User savedUser = userRepository.save(user);
        return persistDoctorAssets(savedUser);
    }
    
    @Transactional
    public String generateOtp() {
        // Use real TOTP generation from OtpService (Google Authenticator compatible)
        return otpService.generateOtp();
    }
    
    @Transactional
    public boolean verifyOtp(String email, String otp) {
        email = normalizeEmail(email);
        // Verify using the real TOTP from OtpService (Google Authenticator)
        // The user enters the OTP from their Google Authenticator app
        boolean isValid = otpService.verifyOtp(otp);
        
        if (isValid) {
            // Mark user as verified
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                user.setIsVerified(true);
                user.setOtp(null);
                user.setOtpExpiry(null);
                userRepository.save(user);
            }
        }
        
        return isValid;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
    
    @Transactional
    public void updateOtp(String email) {
        // Update OTP - this will generate a new TOTP
        // Note: The user should use their Google Authenticator app to get the code
        // This method is kept for API compatibility but doesn't actually change anything
        // since the OTP is time-based and changes automatically every 30 seconds
    }
    
    // Patient Assignment Methods
    
    @Transactional
    public User assignPatientToDoctor(Long patientId, Long doctorId) {
        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found"));
        
        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        
        if (!"DOCTOR".equals(doctor.getRole())) {
            throw new RuntimeException("User is not a doctor");
        }
        
        if (!"USER".equals(patient.getRole())) {
            throw new RuntimeException("Can only assign patients (users with role USER)");
        }
        
        patient.setAssignedDoctor(doctor);
        User savedPatient = userRepository.save(patient);
        
        // Send email notification to doctor
        try {
            emailService.sendPatientAssignedToDoctorEmail(doctor, patient);
        } catch (Exception e) {
            // Log error but don't fail the assignment
            System.err.println("Failed to send email to doctor: " + e.getMessage());
        }
        
        return savedPatient;
    }
    
    @Transactional
    public User removePatientAssignment(Long patientId) {
        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found"));
        patient.setAssignedDoctor(null);
        return userRepository.save(patient);
    }
    
    public List<User> getAssignedPatients(Long doctorId) {
        return userRepository.findByAssignedDoctorId(doctorId);
    }
    
    public List<User> getAllPatients() {
        return userRepository.findByRole("USER");
    }
    
    public List<User> getUnassignedPatients() {
        return userRepository.findUnassignedPatients();
    }
    
    /**
     * Get all pending users (users who registered but haven't verified email)
     */
    public List<PendingUser> getAllPendingUsers() {
        return pendingUserRepository.findAll();
    }
}
