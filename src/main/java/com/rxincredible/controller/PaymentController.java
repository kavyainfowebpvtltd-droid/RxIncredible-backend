package com.rxincredible.controller;

import com.razorpay.RazorpayException;
import com.rxincredible.dto.CreateRazorpayOrderRequest;
import com.rxincredible.dto.PaymentVerificationRequest;
import com.rxincredible.dto.RazorpayOrderResponse;
import com.rxincredible.entity.Order;
import com.rxincredible.entity.Payment;
import com.rxincredible.entity.User;
import com.rxincredible.repository.PaymentRepository;
import com.rxincredible.repository.OrderRepository;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.service.PaymentService;
import com.rxincredible.service.OrderService;
import com.rxincredible.service.RazorpayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);
    
    private final PaymentService paymentService;
    private final RazorpayService razorpayService;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    
    public PaymentController(PaymentService paymentService, RazorpayService razorpayService, PaymentRepository paymentRepository, UserRepository userRepository, OrderRepository orderRepository, OrderService orderService) {
        this.paymentService = paymentService;
        this.razorpayService = razorpayService;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }
    
    @PostMapping
    public ResponseEntity<Payment> createPayment(@RequestBody Payment payment) {
        // Extract userId and orderId from the payment object
        if (payment.getUser() != null && payment.getUser().getId() != null) {
            User user = userRepository.findById(payment.getUser().getId())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            payment.setUser(user);
        }
        if (payment.getOrder() != null && payment.getOrder().getId() != null) {
            Order order = orderRepository.findById(payment.getOrder().getId())
                    .orElseThrow(() -> new RuntimeException("Order not found"));
            payment.setOrder(order);
        }
        return ResponseEntity.ok(paymentService.createPayment(payment, payment.getUser().getId(), payment.getOrder().getId()));
    }
    
    @GetMapping
    public ResponseEntity<List<Payment>> getAllPayments() {
        return ResponseEntity.ok(paymentService.findAllPayments());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPaymentById(@PathVariable Long id) {
        return paymentService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/payment-id/{paymentId}")
    public ResponseEntity<Payment> getPaymentByPaymentId(@PathVariable String paymentId) {
        return paymentService.findByPaymentId(paymentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Payment>> getPaymentsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(paymentService.findByUserId(userId));
    }
    
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<Payment>> getPaymentsByOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.findByOrderId(orderId));
    }
    
    @GetMapping("/status/{status}")
    public ResponseEntity<List<Payment>> getPaymentsByStatus(@PathVariable String status) {
        return ResponseEntity.ok(paymentService.findByStatus(status));
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<Payment> updatePaymentStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String transactionReference) {
        Payment payment = paymentService.updatePaymentStatus(id, status, transactionReference);
        
        // When payment is successful (PAID), update the Order payment state.
        // Prescription/second-opinion orders become completed only after report generation.
        if ("PAID".equals(status) && payment.getOrder() != null) {
            orderService.updatePaymentStatus(payment.getOrder().getId(), "PAID", transactionReference);
            
            // Auto-generate payment receipt for Prescription Analysis and Second Opinion services
            try {
                var orderOpt = orderService.findById(payment.getOrder().getId());
                if (orderOpt.isPresent()) {
                    Order order = orderOpt.get();
                    String serviceType = order.getServiceType();
                    if ("PRESCRIPTION_ANALYSIS".equals(serviceType) || "SECOND_OPINION".equals(serviceType)) {
                        System.out.println("Auto-generating payment receipt for " + serviceType + " service");
                        orderService.generatePaymentReceipt(payment.getOrder().getId());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error auto-generating payment receipt: " + e.getMessage());
                // Don't fail the payment update if receipt generation fails
            }
        }
        
        return ResponseEntity.ok(payment);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id) {
        paymentService.deletePayment(id);
        return ResponseEntity.ok().build();
    }
    
    //==========================================================================================================
    // Razorpay Payment Gateway Integration Endpoints
    //==========================================================================================================
    
    /**
     * Get Razorpay API key for frontend integration
     */
    @GetMapping("/razorpay/key")
    public ResponseEntity<Map<String, String>> getRazorpayKey() {
        Map<String, String> response = new HashMap<>();
        response.put("key", razorpayService.getRazorpayKey());
        response.put("testMode", String.valueOf(razorpayService.isTestMode()));
        response.put("mode", razorpayService.isMockMode() ? "MOCK" : (razorpayService.isTestMode() ? "TEST" : "LIVE"));
        response.put("configured", String.valueOf(razorpayService.isConfigured()));
        response.put("mockMode", String.valueOf(razorpayService.isMockMode()));
        return ResponseEntity.ok(response);
    }
    
    /**
     * Create a new Razorpay order for payment
     */
    @PostMapping("/razorpay/create-order")
    public ResponseEntity<?> createRazorpayOrder(@RequestBody Map<String, Object> request) {
        try {
            Long userId = Long.valueOf(request.get("userId").toString());
            Long orderId = Long.valueOf(request.get("orderId").toString());
            Long requestedAmountInPaise = request.get("amount") != null
                    ? Long.valueOf(request.get("amount").toString())
                    : null;
            String requestedCurrency = request.getOrDefault("currency", "INR").toString();
            
            // Get or create payment record
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found"));

            BigDecimal payableAmount = orderService.resolvePayableAmount(order);
            String currency = orderService.resolvePayableCurrencyCode(order);
            Long amountInPaise = orderService.isFixedPriceOrder(order)
                    ? orderService.resolvePayableAmountInPaise(order)
                    : requestedAmountInPaise;

            if (!currency.equalsIgnoreCase(requestedCurrency)) {
                logger.warn("Ignoring frontend currency {} for orderId={}; using server currency {}",
                        requestedCurrency, orderId, currency);
            }

            if (orderService.isFixedPriceOrder(order)) {
                if (requestedAmountInPaise != null && !requestedAmountInPaise.equals(amountInPaise)) {
                    logger.warn(
                            "Ignoring frontend Razorpay amount {} paise for orderId={} serviceType={}; using server amount {} paise",
                            requestedAmountInPaise, orderId, order.getServiceType(), amountInPaise);
                }

                if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(payableAmount) != 0) {
                    order.setTotalAmount(payableAmount);
                    orderRepository.save(order);
                }
            } else if (amountInPaise == null && payableAmount.compareTo(BigDecimal.ZERO) > 0) {
                amountInPaise = orderService.resolvePayableAmountInPaise(order);
            }

            if (amountInPaise == null || amountInPaise <= 0) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Order amount is invalid for payment");
                return ResponseEntity.badRequest().body(error);
            }
            
            // Create payment record with PENDING status
            Payment payment = new Payment();
            payment.setUser(user);
            payment.setOrder(order);
            payment.setAmount(BigDecimal.valueOf(amountInPaise).divide(BigDecimal.valueOf(100))); // Convert paise to rupees for storage
            payment.setCurrencyCode(currency);
            payment.setCurrencySymbol("₹");
            payment.setCurrencySymbol(orderService.resolvePayableCurrencySymbol(order));
            payment.setPaymentMethod("RAZORPAY");
            payment.setStatus("PENDING");
            
            payment = paymentService.createPayment(payment, userId, orderId);
            
            // Create Razorpay order
            CreateRazorpayOrderRequest razorpayRequest = new CreateRazorpayOrderRequest();
            razorpayRequest.setAmount(amountInPaise);
            razorpayRequest.setCurrency(currency);
            razorpayRequest.setReceipt("rcpt_" + payment.getPaymentId());
            razorpayRequest.setPartialPayment(false);
            
            // Add notes for reference
            String notes = "{\"payment_id\":\"" + payment.getPaymentId() + "\",\"user_id\":" + userId + ",\"order_id\":" + orderId + "}";
            razorpayRequest.setNotes(notes);
            
            RazorpayOrderResponse razorpayOrder = razorpayService.createOrder(razorpayRequest);
            
            // Update payment with razorpay order ID
            payment.setTransactionReference(razorpayOrder.getRazorpayOrderId());
            payment.setPaymentGatewayResponse("{\"razorpay_order_id\":\"" + razorpayOrder.getRazorpayOrderId() + "\",\"created_at\":" + razorpayOrder.getCreatedAt() + "}");
            paymentRepository.save(payment);
            
            Map<String, Object> response = new HashMap<>();
            response.put("paymentId", payment.getId());
            response.put("paymentIdDisplay", payment.getPaymentId());
            response.put("razorpayOrderId", razorpayOrder.getRazorpayOrderId());
            response.put("amount", razorpayOrder.getAmount());
            response.put("currency", razorpayOrder.getCurrency());
            response.put("status", razorpayOrder.getStatus());
            response.put("receipt", razorpayOrder.getReceipt());
            
            return ResponseEntity.ok(response);
        } catch (RazorpayException e) {
            logger.error("Failed to create Razorpay order for userId={} orderId={}: {}", request.get("userId"), request.get("orderId"), e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to create Razorpay order: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Error processing Razorpay payment request: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error processing payment request: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
    
    /**
     * Verify payment after successful payment on frontend
     */
    @PostMapping("/razorpay/verify")
    public ResponseEntity<?> verifyPayment(@RequestBody PaymentVerificationRequest request) {
        try {
            // Verify signature
            boolean isValid = razorpayService.verifyPaymentSignature(
                    request.getRazorpayOrderId(),
                    request.getRazorpayPaymentId(),
                    request.getRazorpaySignature()
            );
            
            if (!isValid) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Invalid payment signature");
                return ResponseEntity.badRequest().body(error);
            }
            
            // Find payment by razorpay order ID
            Payment payment = paymentRepository.findByTransactionReference(request.getRazorpayOrderId())
                    .orElseThrow(() -> new RuntimeException("Payment not found"));
            
            // Update payment status to PAID
            payment.setStatus("PAID");
            payment.setTransactionReference(request.getRazorpayPaymentId());
            
            org.json.JSONObject responseJson = new org.json.JSONObject();
            responseJson.put("razorpay_order_id", request.getRazorpayOrderId());
            responseJson.put("razorpay_payment_id", request.getRazorpayPaymentId());
            responseJson.put("verified", true);
            responseJson.put("verified_at", System.currentTimeMillis());
            payment.setPaymentGatewayResponse(responseJson.toString());
            
            payment = paymentRepository.save(payment);
            
            // Update order status
            Order order = payment.getOrder();
            if (order != null) {
                order.setPaymentStatus("PAID");
                order.setPaymentReference(request.getRazorpayPaymentId());
                order.setPaymentMethod("RAZORPAY");
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
                
                // Auto-generate payment receipt for specific services
                String serviceType = order.getServiceType();
                if ("PRESCRIPTION_ANALYSIS".equals(serviceType) || "SECOND_OPINION".equals(serviceType)) {
                    try {
                        orderService.generatePaymentReceipt(order.getId());
                    } catch (Exception e) {
                        // Log but don't fail
                        System.err.println("Error generating payment receipt: " + e.getMessage());
                    }
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("paymentId", payment.getId());
            response.put("paymentIdDisplay", payment.getPaymentId());
            response.put("status", payment.getStatus());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error verifying payment: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}
