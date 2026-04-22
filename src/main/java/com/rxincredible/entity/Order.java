package com.rxincredible.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rxincredible.util.CurrencyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Order {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password", "assignedDoctor"})
    private User user;
    
    // Store user's email for orders created by accountant
    private String userEmail;
    
    @Column(unique = true, nullable = false)
    private String orderNumber;
    
    @Column(columnDefinition = "TEXT")
    private String orderDetails;
    
    @Column(nullable = false)
    private BigDecimal totalAmount;

    private String currencyCode;

    private String currencySymbol;
    
    @Column(nullable = false)
    private String status; // SUBMITTED, IN_REVIEW, COMPLETED, CANCELLED
    
    private String paymentStatus; // PENDING, PAID, FAILED
    
    private String paymentMethod;
    
    private String paymentReference;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Prescription prescription;
    
    private String serviceType; // PRESCRIPTION_ANALYSIS, ONLINE_PHARMACY, SECOND_OPINION
    
    // Medical report status
    private String medicalReportStatus; // NOT_STARTED, IN_PROGRESS, COMPLETED
    
    // For linking orders to doctors
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_doctor_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password", "assignedDoctor"})
    private User assignedDoctor;
    
    // For linking orders to analysts (for Prescription Analysis)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_analyst_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password", "assignedDoctor"})
    private User assignedAnalyst;
    
    // Medical report file path
    @Column(columnDefinition = "TEXT")
    private String medicalReportFilePath;
    
    // Prescription PDF file path (from doctor generate)
    @Column(columnDefinition = "TEXT")
    private String prescriptionPath;
    
    // Bill/PDF file path
    @Column(columnDefinition = "TEXT")
    private String billFilePath;
    
    // Payment Receipt file path
    @Column(columnDefinition = "TEXT")
    private String paymentReceiptPath;
    
    // Delivery address for online pharmacy orders
    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;
    
    // Delivery address details
    private String deliveryCity;
    
    private String deliveryState;
    
    private String deliveryPincode;
    
    private String deliveryCountry;
    
    // Delivery phone number for online pharmacy orders
    private String deliveryPhone;
    
    // Delivery status: PROCESSING, SHIPPED, DELIVERED
    private String deliveryStatus;
    
    // Priority: HIGH, MEDIUM, LOW (default MEDIUM)
    private String priority;
    
    @CreationTimestamp
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private String resolveCountryForCurrency() {
        if (deliveryCountry != null && !deliveryCountry.isBlank()) {
            return deliveryCountry;
        }
        if (user != null && user.getCountry() != null && !user.getCountry().isBlank()) {
            return user.getCountry();
        }
        return "India";
    }

    @PrePersist
    @PreUpdate
    private void syncCurrencyMetadata() {
        String country = resolveCountryForCurrency();
        this.currencyCode = CurrencyUtil.resolveCurrencyCode(country);
        this.currencySymbol = CurrencyUtil.resolveCurrencySymbol(country);
    }

    @Transient
    @JsonProperty("displayAmount")
    public String getDisplayAmount() {
        return CurrencyUtil.formatAmount(totalAmount, resolveCountryForCurrency());
    }
}
