package com.rxincredible.service;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.rxincredible.config.RazorpayConfig;
import com.rxincredible.dto.CreateRazorpayOrderRequest;
import com.rxincredible.dto.RazorpayOrderResponse;
import com.rxincredible.entity.Order;
import com.rxincredible.entity.Payment;
import com.rxincredible.repository.PaymentRepository;
import com.rxincredible.repository.OrderRepository;
import com.rxincredible.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

@Service
public class RazorpayService {
    
    private static final Logger logger = LoggerFactory.getLogger(RazorpayService.class);
    
    private final RazorpayClient razorpayClient;
    private final RazorpayConfig razorpayConfig;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    
    public RazorpayService(
            RazorpayClient razorpayClient,
            RazorpayConfig razorpayConfig,
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            UserRepository userRepository) {
        this.razorpayClient = razorpayClient;
        this.razorpayConfig = razorpayConfig;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }
    
    /**
     * Create a Razorpay order for payment
     */
    @Transactional
    public RazorpayOrderResponse createOrder(CreateRazorpayOrderRequest request) throws RazorpayException {
        if (razorpayConfig.isMockMode()) {
            return createMockOrder(request);
        }

        razorpayConfig.validateForUsage();
        logger.info("Creating Razorpay order with amount: {} {}", request.getCurrency(), request.getAmount());
        
        // Create order with Razorpay
        org.json.JSONObject orderRequest = new org.json.JSONObject();
        orderRequest.put("amount", request.getAmount());
        orderRequest.put("currency", request.getCurrency() != null ? request.getCurrency() : "INR");
        orderRequest.put("receipt", request.getReceipt());
        orderRequest.put("partial_payment", request.isPartialPayment());
        
        // Add notes if provided
        if (request.getNotes() != null && !request.getNotes().isEmpty()) {
            org.json.JSONObject notes = new org.json.JSONObject(request.getNotes());
            orderRequest.put("notes", notes);
        }
        
        // Add test mode flag in test environment
        if (razorpayConfig.isTestMode()) {
            logger.info("Operating in TEST mode - using Razorpay test credentials");
        }
        
        com.razorpay.Order razorpayOrder = razorpayClient.orders.create(orderRequest);
        
        // Convert to response DTO
        RazorpayOrderResponse response = new RazorpayOrderResponse();
        response.setRazorpayOrderId(asString(razorpayOrder.get("id")));
        response.setEntity(asString(razorpayOrder.get("entity")));
        response.setAmount(asLong(razorpayOrder.get("amount")));
        response.setAmountPaid(asString(razorpayOrder.get("amount_paid")));
        response.setAmountDue(asString(razorpayOrder.get("amount_due")));
        response.setCurrency(asString(razorpayOrder.get("currency")));
        response.setReceipt(asString(razorpayOrder.get("receipt")));
        response.setStatus(asString(razorpayOrder.get("status")));
        response.setCreatedAt(asLong(razorpayOrder.get("created_at")));
        response.setOfferId(razorpayOrder.has("offer_id") ? asString(razorpayOrder.get("offer_id")) : null);
        
        logger.info("Razorpay order created: {}", response.getRazorpayOrderId());
        return response;
    }
    
    /**
     * Verify payment signature from frontend
     */
    public boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        try {
            if (razorpayConfig.isMockMode()) {
                boolean isValid = razorpayOrderId != null && !razorpayOrderId.isBlank()
                        && razorpayPaymentId != null && !razorpayPaymentId.isBlank();
                logger.info("Mock payment verification for order {}: {}", razorpayOrderId, isValid ? "VALID" : "INVALID");
                return isValid;
            }

            razorpayConfig.validateForUsage();
            org.json.JSONObject attributes = new org.json.JSONObject();
            attributes.put("razorpay_order_id", razorpayOrderId);
            attributes.put("razorpay_payment_id", razorpayPaymentId);
            attributes.put("razorpay_signature", razorpaySignature);
            
            boolean isValid = Utils.verifyPaymentSignature(attributes, razorpayConfig.getRazorpaySecret());
            logger.info("Payment signature verification for order {}: {}", razorpayOrderId, isValid ? "VALID" : "INVALID");
            return isValid;
        } catch (RazorpayException e) {
            logger.error("Error verifying payment signature: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Fetch payment details from Razorpay
     */
    public com.razorpay.Payment getPaymentDetails(String paymentId) throws RazorpayException {
        if (razorpayConfig.isMockMode()) {
            throw new IllegalStateException("Razorpay payment details are not available in mock mode.");
        }
        razorpayConfig.validateForUsage();
        return razorpayClient.payments.fetch(paymentId);
    }
    
    /**
     * Update payment status in local database after successful verification
     */
    @Transactional
    public Payment updatePaymentFromRazorpay(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        Payment payment = paymentRepository.findByTransactionReference(razorpayOrderId)
                .orElseThrow(() -> new RuntimeException("Payment not found for razorpay order: " + razorpayOrderId));
        
        payment.setStatus("PAID");
        payment.setTransactionReference(razorpayPaymentId);
        
        // Store the full response as JSON
        org.json.JSONObject responseJson = new org.json.JSONObject();
        responseJson.put("razorpay_order_id", razorpayOrderId);
        responseJson.put("razorpay_payment_id", razorpayPaymentId);
        responseJson.put("verified", true);
        responseJson.put("verified_at", System.currentTimeMillis());
        payment.setPaymentGatewayResponse(responseJson.toString());
        
        payment = paymentRepository.save(payment);
        
        // Update associated order status
        Order order = payment.getOrder();
        if (order != null) {
            order.setPaymentStatus("PAID");
            if ("PRESCRIPTION_ANALYSIS".equals(order.getServiceType()) || "SECOND_OPINION".equals(order.getServiceType())) {
                if (!"COMPLETED".equals(order.getMedicalReportStatus())) {
                    if (order.getMedicalReportStatus() == null || order.getMedicalReportStatus().isBlank()) {
                        order.setMedicalReportStatus("NOT_STARTED");
                    }
                    String currentStatus = order.getStatus();
                    if (currentStatus == null
                            || currentStatus.isBlank()
                            || "PENDING".equals(currentStatus)
                            || "COMPLETED".equals(currentStatus)) {
                        order.setStatus("SUBMITTED");
                    }
                }
            } else {
                order.setStatus("COMPLETED");
            }
            orderRepository.save(order);
        }
        
        logger.info("Payment updated from Razorpay: {} -> PAID", payment.getPaymentId());
        return payment;
    }
    
    /**
     * Refund a payment
     */
    @Transactional
    public com.razorpay.Refund refundPayment(String paymentId, int amount) throws RazorpayException {
        if (razorpayConfig.isMockMode()) {
            throw new IllegalStateException("Refunds are not available in mock mode.");
        }
        razorpayConfig.validateForUsage();
        org.json.JSONObject refundRequest = new org.json.JSONObject();
        refundRequest.put("amount", amount);
        
        com.razorpay.Refund refund = razorpayClient.payments.refund(paymentId, refundRequest);
        logger.info("Refund initiated for payment {}: {}", paymentId, refund.get("id"));
        return refund;
    }
    
    /**
     * Get Razorpay API key (for frontend to use)
     */
    public String getRazorpayKey() {
        return razorpayConfig.getRazorpayKey();
    }
    
    /**
     * Check if operating in test mode
     */
    public boolean isTestMode() {
        return razorpayConfig.isTestMode();
    }

    public boolean isConfigured() {
        return razorpayConfig.isConfigured();
    }

    public boolean isMockMode() {
        return razorpayConfig.isMockMode();
    }

    private RazorpayOrderResponse createMockOrder(CreateRazorpayOrderRequest request) {
        logger.info("Creating MOCK Razorpay order with amount: {} {}", request.getCurrency(), request.getAmount());

        RazorpayOrderResponse response = new RazorpayOrderResponse();
        response.setRazorpayOrderId("mock_order_" + UUID.randomUUID().toString().replace("-", ""));
        response.setEntity("order");
        response.setAmount(request.getAmount());
        response.setAmountPaid("0");
        response.setAmountDue(String.valueOf(request.getAmount()));
        response.setCurrency(request.getCurrency() != null ? request.getCurrency() : "INR");
        response.setReceipt(request.getReceipt());
        response.setStatus("created");
        response.setCreatedAt(System.currentTimeMillis() / 1000);
        response.setOfferId(null);
        return response;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date.getTime() / 1000;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
