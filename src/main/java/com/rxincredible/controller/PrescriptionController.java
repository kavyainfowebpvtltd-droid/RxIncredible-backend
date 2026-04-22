package com.rxincredible.controller;

import com.rxincredible.entity.Prescription;
import com.rxincredible.service.PrescriptionService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prescriptions")
public class PrescriptionController {
    
    private final PrescriptionService prescriptionService;
    
    public PrescriptionController(PrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }
    
    @PostMapping
    public ResponseEntity<Prescription> createPrescription(
            @RequestBody Prescription prescription,
            @RequestParam Long userId,
            @RequestParam Long doctorId) {
        return ResponseEntity.ok(prescriptionService.createPrescription(prescription, userId, doctorId));
    }
    
    @GetMapping
    public ResponseEntity<List<Prescription>> getAllPrescriptions() {
        return ResponseEntity.ok(prescriptionService.findAllPrescriptions());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Prescription> getPrescriptionById(@PathVariable Long id) {
        return prescriptionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Prescription>> getPrescriptionsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(prescriptionService.findByUserId(userId));
    }
    
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<List<Prescription>> getPrescriptionsByDoctor(@PathVariable Long doctorId) {
        return ResponseEntity.ok(prescriptionService.findByDoctorId(doctorId));
    }
    
    @GetMapping("/status/{status}")
    public ResponseEntity<List<Prescription>> getPrescriptionsByStatus(@PathVariable String status) {
        return ResponseEntity.ok(prescriptionService.findByStatus(status));
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<Prescription> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(prescriptionService.updateStatus(id, status));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePrescription(@PathVariable Long id) {
        prescriptionService.deletePrescription(id);
        return ResponseEntity.ok().build();
    }
    
    // Additional endpoints for doctor and admin
    
    @GetMapping("/doctor/{doctorId}/pending")
    public ResponseEntity<List<Prescription>> getPendingPrescriptionsForDoctor(@PathVariable Long doctorId) {
        return ResponseEntity.ok(prescriptionService.findPendingPrescriptionsForDoctor(doctorId));
    }
    
    @GetMapping("/pending/unassigned")
    public ResponseEntity<List<Prescription>> getPendingUnassignedPrescriptions() {
        return ResponseEntity.ok(prescriptionService.findPendingUnassignedPrescriptions());
    }
    
    @PutMapping("/{id}/assign-doctor")
    public ResponseEntity<Prescription> assignDoctorToPrescription(
            @PathVariable Long id, 
            @RequestParam Long doctorId) {
        return ResponseEntity.ok(prescriptionService.assignDoctorToPrescription(id, doctorId));
    }
    
    // Generate prescription PDF
    @PostMapping("/{id}/generate-pdf")
    public ResponseEntity<Prescription> generatePrescriptionPdf(@PathVariable Long id) {
        try {
            System.out.println("Received request to generate prescription PDF for: " + id);
            String fileName = prescriptionService.generatePrescriptionPdf(id);
            Prescription prescription = prescriptionService.findById(id).orElse(null);
            if (prescription != null) {
                prescription.setFilePath(fileName);
                prescription = prescriptionService.updatePrescriptionFilePath(id, fileName);
            }
            System.out.println("Prescription PDF generated successfully: " + fileName);
            return ResponseEntity.ok(prescription);
        } catch (Throwable e) {
            System.out.println("ERROR in generatePrescriptionPdf: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Get prescription PDF path
    @GetMapping("/{id}/pdf/path")
    public ResponseEntity<?> getPrescriptionPdfPath(@PathVariable Long id) {
        var prescriptionOpt = prescriptionService.findById(id);
        if (prescriptionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Prescription prescription = prescriptionOpt.get();
        String filePath = prescription.getFilePath();
        return ResponseEntity.ok(Map.of(
            "filePath", filePath != null ? filePath : "",
            "hasPdf", filePath != null && !filePath.isEmpty()
        ));
    }
    
    // Download prescription PDF
    @GetMapping("/{id}/pdf/download")
    public ResponseEntity<?> downloadPrescriptionPdf(@PathVariable Long id) {
        try {
            var prescriptionOpt = prescriptionService.findById(id);
            if (prescriptionOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Prescription prescription = prescriptionOpt.get();
            String filePath = prescription.getFilePath();
            
            System.out.println("Download Prescription PDF - ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No prescription PDF found", "hasPdf", false));
            }
            
            Path path = getPrescriptionFilePath(filePath);
            System.out.println("Full file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"prescription_" + prescription.getId() + ".pdf\"")
                        .body(resource);
            }
            
            System.out.println("File does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Prescription PDF not found on server", "hasPdf", false));
        } catch (Exception e) {
            System.out.println("Error downloading prescription: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error downloading prescription: " + e.getMessage()));
        }
    }
    
    // Helper method to get prescription file path
    private Path getPrescriptionFilePath(String filePath) throws MalformedURLException {
        if (filePath == null) {
            return null;
        }
        
        String uploadDir = prescriptionService.getUploadDirectory();
        Path fullPath = Paths.get(uploadDir, filePath);
        return fullPath;
    }
    
    // Get draft prescriptions for a doctor
    @GetMapping("/doctor/{doctorId}/drafts")
    public ResponseEntity<List<Prescription>> getDraftPrescriptionsForDoctor(@PathVariable Long doctorId) {
        return ResponseEntity.ok(prescriptionService.findDraftPrescriptionsForDoctor(doctorId));
    }
    
    // Get draft prescriptions for an analyst
    @GetMapping("/analyst/{analystId}/drafts")
    public ResponseEntity<List<Prescription>> getDraftPrescriptionsForAnalyst(@PathVariable Long analystId) {
        return ResponseEntity.ok(prescriptionService.findDraftPrescriptionsForAnalyst(analystId));
    }
}
