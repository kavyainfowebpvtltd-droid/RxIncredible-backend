package com.rxincredible.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "prescriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Prescription {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = true)
    private User doctor;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prescriptionDetails;
    
    @Column(columnDefinition = "TEXT")
    private String notes;
    
    @Column(columnDefinition = "TEXT")
    private String diagnosis;
    
    @Column(columnDefinition = "TEXT")
    private String recommendations;
    
    @Column(columnDefinition = "TEXT")
    private String chiefComplaints;
    
    @Column(columnDefinition = "TEXT")
    private String historyPoints;
    
    @Column(columnDefinition = "TEXT")
    private String examFindings;
    
    @Column(columnDefinition = "TEXT")
    private String suggestedInvestigations;
    
    @Column(columnDefinition = "TEXT")
    private String specialInstructions;
    
    private String consultationDate;
    
    private String height;
    
    private String weight;
    
    private String lmp;
    
    private String filePath;
    
    @Column(nullable = false)
    private String status; // PENDING, APPROVED, REJECTED
    
    private String serviceType; // PRESCRIPTION_ANALYSIS, ONLINE_PHARMACY, SECOND_OPINION
    
    @Column(columnDefinition = "TEXT")
    private String analysisNotes; // For analyst notes and analysis
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analyst_id")
    private User analyst; // Assigned analyst
    
    @CreationTimestamp
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
