package com.rxincredible.repository;

import com.rxincredible.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    
    @Query("SELECT d FROM Document d JOIN FETCH d.user")
    List<Document> findAllWithUser();
    
    List<Document> findByUserId(Long userId);
    
    List<Document> findByOrderId(Long orderId);
    
    List<Document> findByUserIdAndStatus(Long userId, String status);
    
    List<Document> findByCategory(String category);
    
    List<Document> findByUserIdAndCategory(Long userId, String category);
    
    void deleteById(Long id);
}
