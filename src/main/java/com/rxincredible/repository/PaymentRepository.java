package com.rxincredible.repository;

import com.rxincredible.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    
    List<Payment> findByUserId(Long userId);
    
    List<Payment> findByOrderId(Long orderId);
    
    Optional<Payment> findByPaymentId(String paymentId);
    
    Optional<Payment> findByTransactionReference(String transactionReference);
    
    List<Payment> findByStatus(String status);
    
    @Query("SELECT p FROM Payment p WHERE p.user.id = :userId ORDER BY p.paymentDate DESC")
    List<Payment> findUserPayments(Long userId);
}
