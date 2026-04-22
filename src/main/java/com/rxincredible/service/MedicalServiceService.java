package com.rxincredible.service;

import com.rxincredible.entity.MedicalService;
import com.rxincredible.repository.MedicalServiceRepository;
import com.rxincredible.util.CurrencyUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Collectors;

@Service
public class MedicalServiceService {
    
    private final MedicalServiceRepository medicalServiceRepository;
    
    public MedicalServiceService(MedicalServiceRepository medicalServiceRepository) {
        this.medicalServiceRepository = medicalServiceRepository;
    }
    
    @Transactional
    public MedicalService createService(MedicalService service) {
        service.setIsActive(true);
        return medicalServiceRepository.save(service);
    }
    
    public List<MedicalService> findAllServices(String country) {
        // Only return these 3 categories: prescription, pharmacy, consultation
        List<String> allowedCategories = Arrays.asList("prescription", "pharmacy", "consultation");
        List<MedicalService> services = medicalServiceRepository.findAll().stream()
                .filter(service -> allowedCategories.contains(service.getCategory()))
                .collect(Collectors.toList());
        return applyCurrencyDisplay(services, country);
    }
    
    public Optional<MedicalService> findById(Long id) {
        return medicalServiceRepository.findById(id);
    }
    
    public List<MedicalService> findActiveServices(String country) {
        // Only return these 3 categories: prescription, pharmacy, consultation
        List<String> allowedCategories = Arrays.asList("prescription", "pharmacy", "consultation");
        List<MedicalService> services = medicalServiceRepository.findByIsActiveTrue().stream()
                .filter(service -> allowedCategories.contains(service.getCategory()))
                .collect(Collectors.toList());
        return applyCurrencyDisplay(services, country);
    }
    
    public List<MedicalService> findByCategory(String category, String country) {
        return applyCurrencyDisplay(medicalServiceRepository.findByCategory(category), country);
    }
    
    @Transactional
    public MedicalService updateService(Long id, MedicalService serviceDetails) {
        MedicalService service = medicalServiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Service not found"));
        
        service.setServiceName(serviceDetails.getServiceName());
        service.setDescription(serviceDetails.getDescription());
        service.setPrice(serviceDetails.getPrice());
        service.setCategory(serviceDetails.getCategory());
        
        return medicalServiceRepository.save(service);
    }
    
    @Transactional
    public void deactivateService(Long id) {
        MedicalService service = medicalServiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Service not found"));
        service.setIsActive(false);
        medicalServiceRepository.save(service);
    }
    
    @Transactional
    public void activateService(Long id) {
        MedicalService service = medicalServiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Service not found"));
        service.setIsActive(true);
        medicalServiceRepository.save(service);
    }
    
    @Transactional
    public void deleteService(Long id) {
        medicalServiceRepository.deleteById(id);
    }

    private List<MedicalService> applyCurrencyDisplay(List<MedicalService> services, String country) {
        String currencyCode = CurrencyUtil.resolveCurrencyCode(country);
        String currencySymbol = CurrencyUtil.resolveCurrencySymbol(country);

        services.forEach(service -> {
            service.setCurrencyCode(currencyCode);
            service.setCurrencySymbol(currencySymbol);
            service.setDisplayPrice(CurrencyUtil.formatAmount(service.getPrice(), country));
        });

        return services;
    }
}
