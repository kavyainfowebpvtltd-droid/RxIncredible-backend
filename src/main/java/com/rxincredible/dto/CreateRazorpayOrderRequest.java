package com.rxincredible.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRazorpayOrderRequest {
    private Long amount; // Amount in paise (INR)
    private String currency; // Default "INR"
    private String receipt; // Unique receipt ID
    private String notes; // JSON string for additional notes
    private boolean partialPayment; // Allow partial payments
}