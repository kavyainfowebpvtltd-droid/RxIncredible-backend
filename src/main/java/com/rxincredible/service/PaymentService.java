package com.rxincredible.service;

import com.rxincredible.entity.Payment;
import com.rxincredible.entity.User;
import com.rxincredible.entity.Order;
import com.rxincredible.repository.PaymentRepository;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.repository.OrderRepository;

import org.apache.coyote.BadRequestException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {
    
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    
    public PaymentService(PaymentRepository paymentRepository, UserRepository userRepository, OrderRepository orderRepository) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }
    
    @Transactional
    public Payment createPayment(Payment payment, Long userId, Long orderId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        payment.setUser(user);
        payment.setOrder(order);

        if (payment.getPaymentId() == null || payment.getPaymentId().isBlank()) {
            payment.setPaymentId("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }

        if (payment.getStatus() == null || payment.getStatus().isBlank()) {
            payment.setStatus("PENDING");
        }

        if (payment.getPaymentMethod() == null || payment.getPaymentMethod().isBlank()) {
            payment.setPaymentMethod("RAZORPAY");
        }

        return paymentRepository.save(payment);
    }


    
    public List<Payment> findAllPayments() {
        return paymentRepository.findAll();
    }
    
    public Optional<Payment> findById(Long id) {
        return paymentRepository.findById(id);
    }
    
    public Optional<Payment> findByPaymentId(String paymentId) {
        return paymentRepository.findByPaymentId(paymentId);
    }
    
    public List<Payment> findByUserId(Long userId) {
        return paymentRepository.findUserPayments(userId);
    }
    
    public List<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
    
    public List<Payment> findByStatus(String status) {
        return paymentRepository.findByStatus(status);
    }
    
    @Transactional
    public Payment updatePaymentStatus(Long id, String status, String transactionReference) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        payment.setStatus(status);
        payment.setTransactionReference(transactionReference);
        return paymentRepository.save(payment);
    }
    
    @Transactional
    public void deletePayment(Long id) {
        paymentRepository.deleteById(id);
    }
}
