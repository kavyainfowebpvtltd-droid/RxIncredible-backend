package com.rxincredible.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RazorpayOrderResponse {
    private String razorpayOrderId;
    private String entity;
    private Long amount;
    private String amountPaid;
    private String amountDue;
    private String currency;
    private String receipt;
    private String status;
    private Long createdAt;
    private String offerId;
}