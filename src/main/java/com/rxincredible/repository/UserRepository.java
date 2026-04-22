package com.rxincredible.repository;

import com.rxincredible.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.email)) = LOWER(TRIM(:email))")
    Optional<User> findByEmail(@Param("email") String email);
    
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(TRIM(u.email)) = LOWER(TRIM(:email))")
    boolean existsByEmail(@Param("email") String email);
    
    List<User> findByRole(String role);
    
    List<User> findByIsActiveTrue();
    
    @Query("SELECT u FROM User u WHERE u.role = :role AND u.isActive = true")
    List<User> findActiveUsersByRole(String role);
    
    @Query("SELECT u FROM User u WHERE u.otp = :otp AND u.otpExpiry > CURRENT_TIMESTAMP")
    Optional<User> findByOtpAndNotExpired(String otp);
    
    // Patient Assignment Queries
    List<User> findByAssignedDoctor(User doctor);
    
    @Query("SELECT u FROM User u WHERE u.role = 'USER' AND u.assignedDoctor IS NULL")
    List<User> findUnassignedPatients();
    
    @Query("SELECT u FROM User u WHERE u.role = 'USER' AND u.assignedDoctor.id = :doctorId")
    List<User> findByAssignedDoctorId(Long doctorId);
}
