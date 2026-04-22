package com.rxincredible.repository;

import com.rxincredible.entity.Quotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    
    List<Quotation> findByUserId(Long userId);
    
    Optional<Quotation> findByQuotationNumber(String quotationNumber);
    
    List<Quotation> findByStatus(String status);
    
    Optional<Quotation> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);

    @Query("SELECT q FROM Quotation q LEFT JOIN FETCH q.user LEFT JOIN FETCH q.createdBy LEFT JOIN FETCH q.order LEFT JOIN FETCH q.order.user WHERE q.order.id = :orderId ORDER BY q.createdAt DESC")
    List<Quotation> findAllByOrderIdWithDetails(@Param("orderId") Long orderId);
    
    @Query("SELECT q FROM Quotation q WHERE q.user.id = :userId ORDER BY q.createdAt DESC")
    List<Quotation> findUserQuotations(Long userId);
    
    @Query("SELECT q FROM Quotation q WHERE q.status = :status ORDER BY q.createdAt DESC")
    List<Quotation> findByStatusOrderByCreatedAtDesc(String status);
    
    // Fetch all quotations with user details (fixes lazy loading issue)
    @Query("SELECT DISTINCT q FROM Quotation q LEFT JOIN FETCH q.user LEFT JOIN FETCH q.createdBy LEFT JOIN FETCH q.order WHERE q.order IS NOT NULL ORDER BY q.createdAt DESC")
    List<Quotation> findAllWithUserDetails();
    
    // Fetch all quotations including those without orders
    @Query("SELECT DISTINCT q FROM Quotation q LEFT JOIN FETCH q.user LEFT JOIN FETCH q.createdBy ORDER BY q.createdAt DESC")
    List<Quotation> findAllWithUserAndCreatorDetails();
    
    // Fetch quotation by ID with order details (including order's user)
    @Query("SELECT q FROM Quotation q LEFT JOIN FETCH q.user LEFT JOIN FETCH q.createdBy LEFT JOIN FETCH q.order LEFT JOIN FETCH q.order.user WHERE q.id = :id")
    Optional<Quotation> findByIdWithOrder(@Param("id") Long id);
}
