package com.rxincredible.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pending_users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingUser {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String email;
    
    @Column(nullable = false)
    private String password;
    
    @Column(nullable = false)
    private String fullName;
    
    @Column(nullable = false)
    private String role; // ADMIN, DOCTOR, ACCOUNTANT, ANALYST, USER
    
    private String phone;
    
    private String address;

    private String country;
    
    private Integer age;
    
    private String gender;
    
    // Doctor specialization
    private String specialization;
    
    // Doctor qualifications
    private String qualifications;
    
    // Doctor license number
    private String licenseNumber;
    
    // Doctor documents may arrive as base64 before they are persisted to files.
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String aadharCard;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String panCard;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String medicalCouncilRegistration;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String ugCertificate;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String pgCertificate;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String profilePicture;
    
    // Document file names
    private String aadharCardFileName;
    private String panCardFileName;
    private String medicalCouncilRegistrationFileName;
    private String ugCertificateFileName;
    private String pgCertificateFileName;
    private String profilePictureFileName;
    
    // Doctor experience in years
    private Integer experienceYears;
    
    // Verification token (one-time use)
    @Column(nullable = false)
    private String verificationToken;
    
    // Token expiry time (e.g., 24 hours)
    private LocalDateTime tokenExpiry;
    
    @CreationTimestamp
    private LocalDateTime createdAt;
}
