package com.rxincredible.repository;

import com.rxincredible.entity.PendingUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PendingUserRepository extends JpaRepository<PendingUser, Long> {
    
    @Query("SELECT p FROM PendingUser p WHERE LOWER(TRIM(p.email)) = LOWER(TRIM(:email))")
    Optional<PendingUser> findByEmail(@Param("email") String email);
    
    Optional<PendingUser> findByVerificationToken(String token);
    
    @Query("SELECT COUNT(p) > 0 FROM PendingUser p WHERE LOWER(TRIM(p.email)) = LOWER(TRIM(:email))")
    boolean existsByEmail(@Param("email") String email);
    
    @Modifying
    @Query("DELETE FROM PendingUser p WHERE LOWER(TRIM(p.email)) = LOWER(TRIM(:email))")
    void deleteByEmail(@Param("email") String email);
}
