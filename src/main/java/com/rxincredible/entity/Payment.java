package com.rxincredible.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.rxincredible.util.CurrencyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Payment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    
    @Column(unique = true, nullable = false)
    private String paymentId;
    
    @Column(nullable = false)
    private BigDecimal amount;

    private String currencyCode;

    private String currencySymbol;
    
    @Column(nullable = false)
    private String paymentMethod; // CREDIT_CARD, DEBIT_CARD, UPI, BANK_TRANSFER
    
    @Column(nullable = false)
    private String status; // PENDING, COMPLETED, FAILED, REFUNDED
    
    private String transactionReference;
    
    private String paymentGatewayResponse;
    
    @CreationTimestamp
    private LocalDateTime paymentDate;
    
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private String resolveCountryForCurrency() {
        if (order != null && order.getDeliveryCountry() != null && !order.getDeliveryCountry().isBlank()) {
            return order.getDeliveryCountry();
        }
        if (user != null && user.getCountry() != null && !user.getCountry().isBlank()) {
            return user.getCountry();
        }
        if (order != null && order.getUser() != null && order.getUser().getCountry() != null
                && !order.getUser().getCountry().isBlank()) {
            return order.getUser().getCountry();
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
}
