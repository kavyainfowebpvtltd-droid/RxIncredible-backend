package com.rxincredible.repository;

import com.rxincredible.entity.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {
    
    List<Prescription> findByUserId(Long userId);
    
    @Query("SELECT p FROM Prescription p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.doctor WHERE p.doctor.id = :doctorId")
    List<Prescription> findByDoctorId(Long doctorId);
    
    List<Prescription> findByStatus(String status);
    
    @Query("SELECT p FROM Prescription p WHERE p.user.id = :userId ORDER BY p.createdAt DESC")
    List<Prescription> findUserPrescriptions(Long userId);
    
    @Query("SELECT p FROM Prescription p WHERE p.status = :status ORDER BY p.createdAt DESC")
    List<Prescription> findByStatusOrderByCreatedAtDesc(String status);
    
    // Additional queries for doctor assignment
    @Query("SELECT p FROM Prescription p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.doctor WHERE p.doctor.id = :doctorId AND p.status = :status")
    List<Prescription> findByDoctorIdAndStatus(Long doctorId, String status);
    
    @Query("SELECT p FROM Prescription p WHERE p.doctor IS NULL AND p.status = :status ORDER BY p.createdAt DESC")
    List<Prescription> findByDoctorIdIsNullAndStatus(String status);
    
    // Query for analyst drafts
    @Query("SELECT p FROM Prescription p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.doctor WHERE p.analyst.id = :analystId AND p.status = :status")
    List<Prescription> findByAnalystIdAndStatus(@Param("analystId") Long analystId, @Param("status") String status);
    
    // Query for analyst prescriptions filtered by service type
    @Query("SELECT p FROM Prescription p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.doctor WHERE p.analyst.id = :analystId AND p.serviceType = :serviceType")
    List<Prescription> findByAnalystIdAndServiceType(@Param("analystId") Long analystId, @Param("serviceType") String serviceType);
    
    // Query for analyst prescriptions filtered by status and service type
    @Query("SELECT p FROM Prescription p LEFT JOIN FETCH p.user LEFT JOIN FETCH p.doctor WHERE p.analyst.id = :analystId AND p.status = :status AND p.serviceType = :serviceType")
    List<Prescription> findByAnalystIdAndStatusAndServiceType(@Param("analystId") Long analystId, @Param("status") String status, @Param("serviceType") String serviceType);
}
