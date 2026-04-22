package com.rxincredible.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rxincredible.util.CurrencyUtil;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    @Size(min = 6, message = "Password must be at least 6 characters")
    // Pattern disabled to allow simpler passwords - frontend handles validation
    // @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[^\\s]+$", message = "Incorrect
    // Password")
    private String password;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String role; // ADMIN, DOCTOR, ACCOUNTANT, ANALYST, USER

    // Phone is optional for registration, but if provided must be 10 digits
    // @Pattern(regexp = "^[1-9][0-9]{9}$", message = "Invalid Phone Number")
    private String phone;

    private String address;

    private String city;

    private String state;

    private String pincode;

    private String country;
    
    // Delivery phone number for user orders
    private String deliveryPhone;

    // Age is optional for registration, but if provided must be between 1-100
    // @Min(value = 1, message = "Invalid Age")
    // @Max(value = 100, message = "Invalid Age")
    private Integer age;

    private String gender;
    
    // Height and weight for patient medical records
    private String height;
    
    private String weight;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private String status = "Active";

    private Boolean isVerified = false;

    private String otp;

    // Doctor specialization (e.g., General Medicine, Cardiology, etc.)
    private String specialization;

    // Doctor qualifications (e.g., MBBS, MD, etc.)
    private String qualifications;

    // Doctor license number
    private String licenseNumber;

    // Doctor documents may temporarily be base64 before being persisted to files.
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
    @JsonAlias("avatar")
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

    private LocalDateTime otpExpiry;

    // For linking patients to doctors
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_doctor_id")
    @JsonIgnoreProperties({ "assignedDoctor", "hibernateLazyInitializer", "handler" })
    private User assignedDoctor;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // UserDetails implementation
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !"Disabled".equalsIgnoreCase(getStatus());
    }

    public String getStatus() {
        if (status == null || status.isBlank()) {
            return Boolean.TRUE.equals(isActive) ? "Active" : "Inactive";
        }
        return normalizeStatus(status);
    }

    public void setStatus(String status) {
        String normalizedStatus = normalizeStatus(status);
        this.status = normalizedStatus;

        if ("Active".equalsIgnoreCase(normalizedStatus)) {
            this.isActive = true;
        } else if ("Inactive".equalsIgnoreCase(normalizedStatus) || "Disabled".equalsIgnoreCase(normalizedStatus)) {
            this.isActive = false;
        }
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
        this.status = Boolean.TRUE.equals(isActive) ? "Active" : "Inactive";
    }

    @PrePersist
    @PreUpdate
    private void syncStatusBeforeSave() {
        if (status == null || status.isBlank()) {
            status = Boolean.TRUE.equals(isActive) ? "Active" : "Inactive";
            return;
        }

        status = normalizeStatus(status);
        if ("Active".equalsIgnoreCase(status)) {
            isActive = true;
        } else {
            isActive = false;
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return Boolean.TRUE.equals(isActive) ? "Active" : "Inactive";
        }

        String trimmedStatus = status.trim();
        if ("active".equalsIgnoreCase(trimmedStatus)) {
            return "Active";
        }
        if ("inactive".equalsIgnoreCase(trimmedStatus)) {
            return "Inactive";
        }
        if ("disabled".equalsIgnoreCase(trimmedStatus)) {
            return "Disabled";
        }

        return Boolean.TRUE.equals(isActive) ? "Active" : "Inactive";
    }

    @Transient
    @JsonProperty("currencyCode")
    public String getCurrencyCode() {
        return CurrencyUtil.resolveCurrencyCode(country);
    }

    @Transient
    @JsonProperty("currencySymbol")
    public String getCurrencySymbol() {
        return CurrencyUtil.resolveCurrencySymbol(country);
    }

    @Transient
    @JsonProperty("avatar")
    public String getAvatar() {
        return profilePicture;
    }
}
