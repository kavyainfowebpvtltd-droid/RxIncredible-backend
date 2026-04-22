package com.rxincredible.controller;

import com.rxincredible.entity.Quotation;
import com.rxincredible.entity.User;
import com.rxincredible.service.QuotationService;
import com.rxincredible.util.CurrencyUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/quotations")
public class QuotationController {
    
    private final QuotationService quotationService;
    
    public QuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }
    
    @PostMapping
    public ResponseEntity<Quotation> createQuotation(
            @RequestBody Quotation quotation,
            @RequestParam Long userId,
            @RequestParam Long createdById,
            @RequestParam(required = false) Long orderId) {
        return ResponseEntity.ok(quotationService.createQuotation(quotation, userId, createdById, orderId));
    }
    
    @GetMapping
    public ResponseEntity<List<Quotation>> getAllQuotations() {
        return ResponseEntity.ok(quotationService.findAllQuotations());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Quotation> getQuotationById(@PathVariable Long id) {
        // Try to fetch with order details first
        return quotationService.findByIdWithOrder(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> quotationService.findById(id)
                        .map(ResponseEntity::ok)
                        .orElse(ResponseEntity.notFound().build()));
    }
    
    @GetMapping("/number/{quotationNumber}")
    public ResponseEntity<Quotation> getQuotationByNumber(@PathVariable String quotationNumber) {
        return quotationService.findByQuotationNumber(quotationNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Quotation>> getQuotationsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(quotationService.findByUserId(userId));
    }
    
    @GetMapping("/order/{orderId}")
    public ResponseEntity<Map<String, Object>> getQuotationByOrderId(@PathVariable Long orderId) {
        return quotationService.findByOrderId(orderId)
                .map(quotation -> {
                    BigDecimal subtotal = quotation.getSubtotal() != null ? quotation.getSubtotal() : BigDecimal.ZERO;
                    BigDecimal totalAmount = quotation.getTotalAmount() != null ? quotation.getTotalAmount() : BigDecimal.ZERO;
                    BigDecimal deliveryCharge = totalAmount.subtract(subtotal);
                    String country = resolveCountry(quotation);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("id", quotation.getId());
                    response.put("quotationNumber", quotation.getQuotationNumber() != null ? quotation.getQuotationNumber() : "");
                    response.put("items", quotation.getItems() != null ? quotation.getItems() : "[]");
                    response.put("subtotal", subtotal);
                    response.put("displaySubtotal", CurrencyUtil.formatAmount(subtotal, country));
                    response.put("tax", quotation.getTax() != null ? quotation.getTax() : BigDecimal.ZERO);
                    response.put("discount", quotation.getDiscount() != null ? quotation.getDiscount() : BigDecimal.ZERO);
                    response.put("deliveryCharge", deliveryCharge);
                    response.put("displayDeliveryCharge", CurrencyUtil.formatAmount(deliveryCharge, country));
                    response.put("totalAmount", totalAmount);
                    response.put("displayTotalAmount", CurrencyUtil.formatAmount(totalAmount, country));
                    response.put("currencyCode", CurrencyUtil.resolveCurrencyCode(country));
                    response.put("currencySymbol", CurrencyUtil.resolveCurrencySymbol(country));
                    response.put("status", quotation.getStatus() != null ? quotation.getStatus() : "");
                    response.put("emailSent", Boolean.TRUE.equals(quotation.getEmailSent()));
                    response.put("orderId", orderId);
                    response.put("createdAt", quotation.getCreatedAt());
                    response.put("updatedAt", quotation.getUpdatedAt());

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> downloadQuotationPdf(@PathVariable Long id) {
        try {
            byte[] pdfBytes = quotationService.downloadQuotationPdf(id);
            Quotation quotation = quotationService.findByIdWithOrder(id)
                    .orElseGet(() -> quotationService.findById(id)
                            .orElseThrow(() -> new RuntimeException("Quotation not found")));
            String orderNumber = quotation.getOrder() != null && quotation.getOrder().getOrderNumber() != null
                    ? quotation.getOrder().getOrderNumber()
                    : String.valueOf(id);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Quotation_" + orderNumber + ".pdf\"")
                    .body(pdfBytes);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : "Quotation not found";
            if (message.toLowerCase().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
    }

    @GetMapping("/order/{orderId}/download")
    public ResponseEntity<?> downloadQuotationPdfByOrderId(@PathVariable Long orderId) {
        try {
            byte[] pdfBytes = quotationService.downloadQuotationPdfByOrderId(orderId);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Quotation_" + orderId + ".pdf\"")
                    .body(pdfBytes);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : "Quotation not found";
            if (message.toLowerCase().contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
    }
    
    @GetMapping("/status/{status}")
    public ResponseEntity<List<Quotation>> getQuotationsByStatus(@PathVariable String status) {
        return ResponseEntity.ok(quotationService.findByStatus(status));
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<Quotation> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(quotationService.updateStatus(id, status));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuotation(@PathVariable Long id) {
        quotationService.deleteQuotation(id);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{id}/send-email")
    public ResponseEntity<Map<String, String>> sendQuotationEmail(@PathVariable Long id) {
        try {
            quotationService.sendQuotationEmail(id);
            return ResponseEntity.ok(Map.of("message", "Quotation email sent successfully"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to send email: " + e.getMessage()));
        }
    }

    private String resolveCountry(Quotation quotation) {
        if (quotation.getOrder() != null && quotation.getOrder().getDeliveryCountry() != null
                && !quotation.getOrder().getDeliveryCountry().isBlank()) {
            return quotation.getOrder().getDeliveryCountry();
        }

        User user = quotation.getUser();
        if (user != null && user.getCountry() != null && !user.getCountry().isBlank()) {
            return user.getCountry();
        }

        return "India";
    }
}
