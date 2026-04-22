package com.rxincredible.repository;

import com.rxincredible.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    
    List<Order> findByUserId(Long userId);
    
    List<Order> findByStatus(String status);
    
    Optional<Order> findByOrderNumber(String orderNumber);
    
    @Query("SELECT o FROM Order o WHERE o.user.id = :userId ORDER BY o.createdAt DESC")
    List<Order> findUserOrders(Long userId);
    
    @Query("SELECT o FROM Order o WHERE o.status = :status ORDER BY o.createdAt DESC")
    List<Order> findByStatusOrderByCreatedAtDesc(String status);
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
    Long countByStatus(String status);
    
    // Find orders by assigned doctor ID - all orders regardless of payment status
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.prescription WHERE o.assignedDoctor.id = :doctorId ORDER BY o.createdAt DESC")
    List<Order> findByAssignedDoctorId(Long doctorId);
    
    // Find orders by assigned analyst ID - all orders regardless of payment status
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.assignedAnalyst.id = :analystId ORDER BY o.createdAt DESC")
    List<Order> findByAssignedAnalystId(Long analystId);
    
    // Find orders by assigned analyst ID AND service type (for analyst to only see PRESCRIPTION_ANALYSIS orders) - sorted by priority
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.assignedAnalyst.id = :analystId AND o.serviceType = :serviceType ORDER BY CASE WHEN o.priority = 'HIGH' THEN 1 WHEN o.priority = 'MEDIUM' THEN 2 WHEN o.priority = 'LOW' THEN 3 ELSE 4 END, o.createdAt DESC")
    List<Order> findByAssignedAnalystIdAndServiceType(Long analystId, String serviceType);
    
    // Find orders by assigned doctor ID AND service type (for doctor to only see SECOND_OPINION orders) - sorted by priority
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.prescription WHERE o.assignedDoctor.id = :doctorId AND o.serviceType = :serviceType ORDER BY CASE WHEN o.priority = 'HIGH' THEN 1 WHEN o.priority = 'MEDIUM' THEN 2 WHEN o.priority = 'LOW' THEN 3 ELSE 4 END, o.createdAt DESC")
    List<Order> findByAssignedDoctorIdAndServiceType(Long doctorId, String serviceType);
    
    // Get all orders with details (for general listing) - sorted by createdAt (newest first)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription ORDER BY o.createdAt DESC")
    List<Order> findAllWithDetails();
    
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(Long id);
    
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberWithDetails(String orderNumber);
    
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.assignedDoctor.id = :doctorId")
    List<Order> findByAssignedDoctorIdWithDetails(Long doctorId);
    
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.assignedAnalyst.id = :analystId")
    List<Order> findByAssignedAnalystIdWithDetails(Long analystId);
    
    // Find orders by payment status (for admin to see paid orders only)
    // Only returns orders where paymentStatus is explicitly 'PAID', not NULL
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst WHERE o.paymentStatus = :paymentStatus AND o.paymentStatus IS NOT NULL AND o.paymentStatus <> '' ORDER BY o.createdAt DESC")
    List<Order> findByPaymentStatus(String paymentStatus);
    
    // Find orders with null or empty payment status (for debugging)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst WHERE o.paymentStatus IS NULL OR o.paymentStatus = '' OR o.paymentStatus = 'PENDING' ORDER BY o.createdAt DESC")
    List<Order> findByUnpaidStatus();
    
    // Find orders by service type (for accountant to see ONLINE_PHARMACY orders only)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst WHERE o.serviceType = :serviceType ORDER BY o.createdAt DESC")
    List<Order> findByServiceType(String serviceType);
    
    // Find ONLINE_PHARMACY orders with PAID payment status (for accountant view)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.user LEFT JOIN FETCH o.assignedDoctor LEFT JOIN FETCH o.assignedAnalyst LEFT JOIN FETCH o.prescription WHERE o.serviceType = :serviceType AND o.paymentStatus = :paymentStatus ORDER BY o.createdAt DESC")
    List<Order> findByServiceTypeAndPaymentStatus(String serviceType, String paymentStatus);
    
    // Find order by ID with user eagerly loaded (for PDF generation)
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user WHERE o.id = :id")
    Optional<Order> findByIdWithUser(@Param("id") Long id);
    
    // Find expired online pharmacy orders (older than specified hours)
    @Query("SELECT o FROM Order o WHERE o.serviceType = :serviceType AND o.status = :status AND o.paymentStatus = :paymentStatus AND o.createdAt < :cutoffDate")
    List<Order> findExpiredOnlinePharmacyOrders(
        @Param("serviceType") String serviceType,
        @Param("status") String status,
        @Param("paymentStatus") String paymentStatus,
        @Param("cutoffDate") java.time.LocalDateTime cutoffDate
    );
}
