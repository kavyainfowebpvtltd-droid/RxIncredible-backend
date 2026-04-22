package com.rxincredible.controller;

import com.rxincredible.entity.Prescription;
import com.rxincredible.entity.User;
import com.rxincredible.repository.PrescriptionRepository;
import com.rxincredible.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analyst")
public class AnalystController {

    private final PrescriptionRepository prescriptionRepository;
    private final UserRepository userRepository;

    public AnalystController(PrescriptionRepository prescriptionRepository, UserRepository userRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.userRepository = userRepository;
    }

    // Get all prescriptions assigned to analyst (only PRESCRIPTION_ANALYSIS service type)
    @GetMapping("/prescriptions")
    public ResponseEntity<List<Prescription>> getAssignedPrescriptions() {
        // Get current analyst's ID from security context
        Long analystId = getCurrentAnalystId();
        if (analystId == null) {
            return ResponseEntity.status(401).build();
        }
        // Filter prescriptions by analyst ID and service type = PRESCRIPTION_ANALYSIS
        List<Prescription> prescriptions = prescriptionRepository.findByAnalystIdAndServiceType(analystId, "PRESCRIPTION_ANALYSIS");
        return ResponseEntity.ok(prescriptions);
    }
    
    // Helper method to get current analyst ID from security context
    private Long getCurrentAnalystId() {
        try {
            org.springframework.security.core.Authentication authentication = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                User user = userRepository.findByEmail(username).orElse(null);
                if (user != null && "ANALYST".equals(user.getRole())) {
                    return user.getId();
                }
            }
        } catch (Exception e) {
            // Log error but don't expose to client
        }
        return null;
    }

    // Get prescriptions by status (only PRESCRIPTION_ANALYSIS service type)
    @GetMapping("/prescriptions/status/{status}")
    public ResponseEntity<List<Prescription>> getPrescriptionsByStatus(@PathVariable String status) {
        Long analystId = getCurrentAnalystId();
        if (analystId == null) {
            return ResponseEntity.status(401).build();
        }
        // Filter by analyst ID, status, and service type = PRESCRIPTION_ANALYSIS
        List<Prescription> prescriptions = prescriptionRepository.findByAnalystIdAndStatusAndServiceType(analystId, status, "PRESCRIPTION_ANALYSIS");
        return ResponseEntity.ok(prescriptions);
    }

    // Get all pending prescriptions (only PRESCRIPTION_ANALYSIS service type)
    @GetMapping("/prescriptions/pending")
    public ResponseEntity<List<Prescription>> getPendingPrescriptions() {
        Long analystId = getCurrentAnalystId();
        if (analystId == null) {
            return ResponseEntity.status(401).build();
        }
        // Filter by analyst ID, status = PENDING, and service type = PRESCRIPTION_ANALYSIS
        List<Prescription> prescriptions = prescriptionRepository.findByAnalystIdAndStatusAndServiceType(analystId, "PENDING", "PRESCRIPTION_ANALYSIS");
        return ResponseEntity.ok(prescriptions);
    }

    // Get all approved/completed prescriptions (only PRESCRIPTION_ANALYSIS service type)
    @GetMapping("/prescriptions/completed")
    public ResponseEntity<List<Prescription>> getCompletedPrescriptions() {
        Long analystId = getCurrentAnalystId();
        if (analystId == null) {
            return ResponseEntity.status(401).build();
        }
        // Filter by analyst ID, status = APPROVED, and service type = PRESCRIPTION_ANALYSIS
        List<Prescription> prescriptions = prescriptionRepository.findByAnalystIdAndStatusAndServiceType(analystId, "APPROVED", "PRESCRIPTION_ANALYSIS");
        return ResponseEntity.ok(prescriptions);
    }

    // Get prescription by ID
    @GetMapping("/prescriptions/{id}")
    public ResponseEntity<Prescription> getPrescriptionById(@PathVariable Long id) {
        return prescriptionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Update prescription analysis notes
    @PutMapping("/prescriptions/{id}/analyze")
    public ResponseEntity<Prescription> analyzePrescription(
            @PathVariable Long id,
            @RequestBody Map<String, String> analysisData) {
        return prescriptionRepository.findById(id)
                .map(prescription -> {
                    if (analysisData.containsKey("analysisNotes")) {
                        prescription.setAnalysisNotes(analysisData.get("analysisNotes"));
                    }
                    if (analysisData.containsKey("status")) {
                        prescription.setStatus(analysisData.get("status"));
                    }
                    return ResponseEntity.ok(prescriptionRepository.save(prescription));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Add analysis recommendation
    @PutMapping("/prescriptions/{id}/recommendation")
    public ResponseEntity<Prescription> addRecommendation(
            @PathVariable Long id,
            @RequestBody Map<String, String> recommendationData) {
        return prescriptionRepository.findById(id)
                .map(prescription -> {
                    if (recommendationData.containsKey("analysisNotes")) {
                        String existingNotes = prescription.getAnalysisNotes() != null ? 
                            prescription.getAnalysisNotes() + "\n" : "";
                        prescription.setAnalysisNotes(existingNotes + recommendationData.get("analysisNotes"));
                    }
                    return ResponseEntity.ok(prescriptionRepository.save(prescription));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Get all analysts
    @GetMapping("/analysts")
    public ResponseEntity<List<User>> getAllAnalysts() {
        List<User> analysts = userRepository.findByRole("ANALYST");
        return ResponseEntity.ok(analysts);
    }

    // Get analyst by ID
    @GetMapping("/analysts/{id}")
    public ResponseEntity<User> getAnalystById(@PathVariable Long id) {
        return userRepository.findById(id)
                .filter(user -> "ANALYST".equals(user.getRole()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Get analyst profile
    @GetMapping("/profile")
    public ResponseEntity<User> getAnalystProfile(@RequestParam Long analystId) {
        return userRepository.findById(analystId)
                .filter(user -> "ANALYST".equals(user.getRole()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Get analyst statistics
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Long>> getAnalystStatistics(@RequestParam Long analystId) {
        long totalPrescriptions = prescriptionRepository.count();
        long pendingPrescriptions = prescriptionRepository.findByStatusOrderByCreatedAtDesc("PENDING").size();
        long analyzedPrescriptions = prescriptionRepository.findByStatusOrderByCreatedAtDesc("ANALYZED").size();
        long approvedPrescriptions = prescriptionRepository.findByStatusOrderByCreatedAtDesc("APPROVED").size();

        return ResponseEntity.ok(Map.of(
            "total", totalPrescriptions,
            "pending", pendingPrescriptions,
            "analyzed", analyzedPrescriptions,
            "approved", approvedPrescriptions
        ));
    }

    // Mark prescription as analyzed
    @PutMapping("/prescriptions/{id}/mark-analyzed")
    public ResponseEntity<Prescription> markAsAnalyzed(@PathVariable Long id) {
        return prescriptionRepository.findById(id)
                .map(prescription -> {
                    prescription.setStatus("ANALYZED");
                    return ResponseEntity.ok(prescriptionRepository.save(prescription));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Mark prescription as approved
    @PutMapping("/prescriptions/{id}/approve")
    public ResponseEntity<Prescription> approvePrescription(@PathVariable Long id) {
        return prescriptionRepository.findById(id)
                .map(prescription -> {
                    prescription.setStatus("APPROVED");
                    return ResponseEntity.ok(prescriptionRepository.save(prescription));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Mark prescription as rejected
    @PutMapping("/prescriptions/{id}/reject")
    public ResponseEntity<Prescription> rejectPrescription(
            @PathVariable Long id,
            @RequestBody Map<String, String> rejectionData) {
        return prescriptionRepository.findById(id)
                .map(prescription -> {
                    prescription.setStatus("REJECTED");
                    if (rejectionData.containsKey("analysisNotes")) {
                        String existingNotes = prescription.getAnalysisNotes() != null ? 
                            prescription.getAnalysisNotes() + "\n" : "";
                        prescription.setAnalysisNotes(existingNotes + "Rejection Reason: " + rejectionData.get("analysisNotes"));
                    }
                    return ResponseEntity.ok(prescriptionRepository.save(prescription));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}