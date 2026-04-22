package com.rxincredible.repository;

import com.rxincredible.entity.MedicalService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicalServiceRepository extends JpaRepository<MedicalService, Long> {
    
    List<MedicalService> findByIsActiveTrue();
    
    List<MedicalService> findByCategory(String category);
    
    List<MedicalService> findByIsActiveTrueAndCategory(String category);
    
    void deleteByCategory(String category);
}
