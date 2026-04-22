package com.rxincredible.controller;

import com.rxincredible.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/invoice")
@CrossOrigin(origins = "*")
public class InvoiceController {
    
    private static final Logger logger = LoggerFactory.getLogger(InvoiceController.class);
    
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    
    @Autowired
    private EmailService emailService;
    
    @PostMapping("/send-email")
    public ResponseEntity<?> sendInvoiceEmail(@RequestBody InvoiceEmailRequest request) {
        logger.info("Received request to send invoice email to: {}", request.getEmail());
        
        if (request.getEmail() == null || request.getEmail().isEmpty()) {
            return ResponseEntity.badRequest().body("Email address is required");
        }
        
        if (!isValidEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body("Invalid email format");
        }
        
        String email = request.getEmail();
        if (email.endsWith("@rxincredible.com") || email.endsWith("@test.com") || 
            email.endsWith("@example.com") || email.endsWith("@googlemail.com") || 
            email.contains("mailer-daemon")) {
            return ResponseEntity.badRequest().body("Cannot send email to test/internal email address");
        }
        
        try {
            byte[] pdfBytes = null;
            if (request.getPdfData() != null && !request.getPdfData().isEmpty()) {
                String pdfData = request.getPdfData();
                if (pdfData.contains(",")) {
                    pdfData = pdfData.split(",")[1];
                }
                pdfBytes = Base64.getDecoder().decode(pdfData);
            }
            
            emailService.sendInvoiceEmail(email, request.getInvoiceNo(), pdfBytes);
            
            logger.info("Invoice email sent successfully to: {}", email);
            return ResponseEntity.ok("Invoice sent successfully");
            
        } catch (Exception e) {
            logger.error("Failed to send invoice email: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to send email: " + e.getMessage());
        }
    }
    
    private boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }
    
    public static class InvoiceEmailRequest {
        private String email;
        private String invoiceNo;
        private String pdfData;
        
        public String getEmail() {
            return email;
        }
        
        public void setEmail(String email) {
            this.email = email;
        }
        
        public String getInvoiceNo() {
            return invoiceNo;
        }
        
        public void setInvoiceNo(String invoiceNo) {
            this.invoiceNo = invoiceNo;
        }
        
        public String getPdfData() {
            return pdfData;
        }
        
        public void setPdfData(String pdfData) {
            this.pdfData = pdfData;
        }
    }
}
