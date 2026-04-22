package com.rxincredible.controller;

import com.rxincredible.entity.MedicalService;
import com.rxincredible.entity.User;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.service.MedicalServiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/services")
public class MedicalServiceController {
    
    private final MedicalServiceService medicalServiceService;
    private final UserRepository userRepository;
    
    public MedicalServiceController(MedicalServiceService medicalServiceService, UserRepository userRepository) {
        this.medicalServiceService = medicalServiceService;
        this.userRepository = userRepository;
    }
    
    @PostMapping
    public ResponseEntity<MedicalService> createService(@RequestBody MedicalService service) {
        return ResponseEntity.ok(medicalServiceService.createService(service));
    }
    
    @GetMapping
    public ResponseEntity<List<MedicalService>> getAllServices(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String country) {
        return ResponseEntity.ok(medicalServiceService.findAllServices(resolveCountry(userId, country)));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<MedicalService> getServiceById(@PathVariable Long id) {
        return medicalServiceService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/active")
    public ResponseEntity<List<MedicalService>> getActiveServices(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String country) {
        return ResponseEntity.ok(medicalServiceService.findActiveServices(resolveCountry(userId, country)));
    }
    
    @GetMapping("/category/{category}")
    public ResponseEntity<List<MedicalService>> getServicesByCategory(
            @PathVariable String category,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String country) {
        return ResponseEntity.ok(medicalServiceService.findByCategory(category, resolveCountry(userId, country)));
    }
    
    @GetMapping("/role/{role}")
    public ResponseEntity<List<MedicalService>> getServicesByRole(
            @PathVariable String role,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String country) {
        List<MedicalService> services = medicalServiceService.findActiveServices(resolveCountry(userId, country));
        
        // Filter services based on role
        switch (role.toUpperCase()) {
            case "ACCOUNTANT":
                // Accountants only see Online Pharmacy service
                services = services.stream()
                    .filter(s -> "pharmacy".equalsIgnoreCase(s.getCategory()))
                    .collect(java.util.stream.Collectors.toList());
                break;
            case "USER":
            case "PATIENT":
                // Patients see all services (Prescription, Pharmacy, Consultation)
                // Already filtered for active services only
                break;
            case "PHARMACY":
                // Pharmacy role sees Pharmacy and Prescription services
                services = services.stream()
                    .filter(s -> "pharmacy".equalsIgnoreCase(s.getCategory()) || 
                                 "prescription".equalsIgnoreCase(s.getCategory()))
                    .collect(java.util.stream.Collectors.toList());
                break;
            default:
                // Other roles see all active services
                break;
        }
        
        return ResponseEntity.ok(services);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<MedicalService> updateService(
            @PathVariable Long id,
            @RequestBody MedicalService service) {
        return ResponseEntity.ok(medicalServiceService.updateService(id, service));
    }
    
    @DeleteMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateService(@PathVariable Long id) {
        medicalServiceService.deactivateService(id);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{id}/activate")
    public ResponseEntity<Void> activateService(@PathVariable Long id) {
        medicalServiceService.activateService(id);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteService(@PathVariable Long id) {
        medicalServiceService.deleteService(id);
        return ResponseEntity.ok().build();
    }

    private String resolveCountry(Long userId, String country) {
        if (country != null && !country.isBlank()) {
            return country;
        }

        if (userId == null) {
            return "India";
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getCountry() == null || user.getCountry().isBlank()) {
            return "India";
        }

        return user.getCountry();
    }
}
