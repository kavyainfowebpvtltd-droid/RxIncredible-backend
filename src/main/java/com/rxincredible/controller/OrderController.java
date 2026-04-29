package com.rxincredible.controller;

import com.rxincredible.entity.Order;
import com.rxincredible.entity.Prescription;
import com.rxincredible.entity.User;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.service.OrderService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    private final OrderService orderService;
    private final UserRepository userRepository;
    
    public OrderController(OrderService orderService, UserRepository userRepository) {
        this.orderService = orderService;
        this.userRepository = userRepository;
    }
    
    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        // Extract userId from the order object
        if (order.getUser() != null && order.getUser().getId() != null) {
            User user = userRepository.findById(order.getUser().getId())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            order.setUser(user);
        }
        return ResponseEntity.ok(orderService.createOrder(order, order.getUser().getId()));
    }
    
    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders(
            @RequestParam(required = false) Boolean admin,
            @RequestParam(required = false) String role) {
        // If admin=true parameter is passed, return all orders (not just PAID)
        // This allows admin to see all orders and assign doctors to unpaid orders
        if (admin != null && admin) {
            return ResponseEntity.ok(orderService.findAllOrdersWithoutFilter()); // All orders
        }
        // For accountant, only return ONLINE_PHARMACY orders
        if ("ACCOUNTANT".equals(role)) {
            return ResponseEntity.ok(orderService.findOnlinePharmacyOrders());
        }
        // For user orders page, return all orders (no filter)
        return ResponseEntity.ok(orderService.findAllOrdersWithoutFilter());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        return orderService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/number/{orderNumber}")
    public ResponseEntity<Order> getOrderByNumber(@PathVariable String orderNumber) {
        return orderService.findByOrderNumber(orderNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getOrdersByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.findByUserId(userId));
    }
    
    // Get user's inquiries (orders with PENDING payment status)
    // These orders show in user's inquiries page before payment
    // Orders older than 72 hours are automatically filtered out
    @GetMapping("/user/{userId}/inquiries")
    public ResponseEntity<List<Order>> getUserInquiries(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.findUserInquiries(userId));
    }
    
    // Remove expired unpaid orders (older than 72 hours)
    // This endpoint can be called periodically or on-demand
    @PostMapping("/remove-expired")
    public ResponseEntity<Map<String, Object>> removeExpiredOrders() {
        try {
            int removedCount = orderService.removeExpiredUnpaidOrders();
            return ResponseEntity.ok(Map.of(
                "message", "Expired unpaid orders removed successfully",
                "removedCount", removedCount
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to remove expired orders: " + e.getMessage()));
        }
    }
    
    // Get user's orders (orders with PAID payment status)
    // These orders show in user's orders page after payment
    @GetMapping("/user/{userId}/orders")
    public ResponseEntity<List<Order>> getUserOrders(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.findUserOrders(userId));
    }
    
    @GetMapping("/status/{status}")
    public ResponseEntity<List<Order>> getOrdersByStatus(@PathVariable String status) {
        return ResponseEntity.ok(orderService.findByStatus(status));
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<Order> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(orderService.updateStatus(id, status));
    }
    
    @PostMapping("/{id}/send-email")
    public ResponseEntity<Map<String, String>> sendEmail(
            @PathVariable Long id,
            @RequestBody(required = false) SendBillEmailRequest request) {
        try {
            orderService.sendOrderEmail(
                    id,
                    request != null ? request.getPdfBase64() : null,
                    request != null ? request.getAttachmentName() : null,
                    request != null ? request.getEmailType() : null);
            return ResponseEntity.ok(Map.of("message", "Email sent successfully"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to send email: " + e.getMessage()));
        }
    }
    
    @PutMapping("/{id}/payment")
    public ResponseEntity<Order> updatePaymentStatus(
            @PathVariable Long id,
            @RequestParam String paymentStatus,
            @RequestParam(required = false) String paymentReference) {
        return ResponseEntity.ok(orderService.updatePaymentStatus(id, paymentStatus, paymentReference));
    }
    
    // Mark payment as successful - this is the main endpoint for successful payments
    @PostMapping("/{id}/payment/success")
    public ResponseEntity<Order> paymentSuccess(
            @PathVariable Long id,
            @RequestParam(required = false) String paymentReference) {
        // Update payment status to PAID
        Order order = orderService.updatePaymentStatus(id, "PAID", paymentReference);
        
        // Auto-generate payment receipt for Prescription Analysis and Second Opinion services
        try {
            var updatedOrderOpt = orderService.findById(id);
            if (updatedOrderOpt.isPresent()) {
                Order updatedOrder = updatedOrderOpt.get();
                String serviceType = updatedOrder.getServiceType();
                if ("PRESCRIPTION_ANALYSIS".equals(serviceType) || "SECOND_OPINION".equals(serviceType)) {
                    System.out.println("Auto-generating payment receipt for " + serviceType + " service");
                    orderService.generatePaymentReceipt(id);
                }
            }
        } catch (Exception e) {
            System.err.println("Error auto-generating payment receipt: " + e.getMessage());
            // Don't fail the payment success if receipt generation fails
        }
        
        return ResponseEntity.ok(order);
    }
    
    // Update total amount for an order (for accountant to modify bill amount)
    @PutMapping("/{id}/total-amount")
    public ResponseEntity<Order> updateTotalAmount(
            @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> request) {
        BigDecimal totalAmount = request.get("totalAmount");
        return ResponseEntity.ok(orderService.updateTotalAmount(id, totalAmount));
    }
    
    // Update userEmail for an order (for linking orders created by accountant to user's email)
    @PutMapping("/{id}/user-email")
    public ResponseEntity<Order> updateUserEmail(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String userEmail = request.get("userEmail");
        return ResponseEntity.ok(orderService.updateUserEmail(id, userEmail));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/count/status/{status}")
    public ResponseEntity<Long> countByStatus(@PathVariable String status) {
        return ResponseEntity.ok(orderService.countByStatus(status));
    }
    
    @PutMapping("/{id}/assign-doctor")
    public ResponseEntity<?> assignDoctor(
            @PathVariable Long id, 
            @RequestParam Long doctorId,
            @RequestParam(required = false) String priority) {
        try {
            System.out.println("=== ASSIGN DOCTOR API CALL ===");
            System.out.println("Order ID: " + id);
            System.out.println("Doctor ID: " + doctorId);
            System.out.println("Priority: " + priority);
            
            // First, let's verify the order exists and log its current state
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Order not found with ID: " + id));
            }
            
            Order currentOrder = orderOpt.get();
            System.out.println("Current Order Status before assignment: " + currentOrder.getStatus());
            System.out.println("Current Assigned Doctor: " + (currentOrder.getAssignedDoctor() != null ? currentOrder.getAssignedDoctor().getId() : "None"));
            
            // Proceed with assignment
            Order order = orderService.assignDoctor(id, doctorId, priority);
            System.out.println("=== ASSIGN DOCTOR SUCCESS ===");
            System.out.println("Order Number: " + order.getOrderNumber());
            System.out.println("New Status: " + order.getStatus());
            System.out.println("Assigned Doctor ID: " + (order.getAssignedDoctor() != null ? order.getAssignedDoctor().getId() : "None"));
            System.out.println("Assigned Doctor Name: " + (order.getAssignedDoctor() != null ? order.getAssignedDoctor().getFullName() : "None"));
            System.out.println("Priority: " + order.getPriority());
            
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            System.out.println("=== ASSIGN DOCTOR ERROR ===");
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    
    // Assign analyst to order (for Prescription Analysis service)
    @PutMapping("/{id}/assign-analyst")
    public ResponseEntity<?> assignAnalyst(
            @PathVariable Long id, 
            @RequestParam Long analystId,
            @RequestParam(required = false) String priority) {
        try {
            System.out.println("=== ASSIGN ANALYST API CALL ===");
            System.out.println("Order ID: " + id);
            System.out.println("Analyst ID: " + analystId);
            System.out.println("Priority: " + priority);
            
            // First, let's verify the order exists and log its current state
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Order not found with ID: " + id));
            }
            
            Order currentOrder = orderOpt.get();
            System.out.println("Current Order Status before assignment: " + currentOrder.getStatus());
            System.out.println("Current Assigned Analyst: " + (currentOrder.getAssignedAnalyst() != null ? currentOrder.getAssignedAnalyst().getId() : "None"));
            
            // Proceed with assignment
            Order order = orderService.assignAnalyst(id, analystId, priority);
            System.out.println("=== ASSIGN ANALYST SUCCESS ===");
            System.out.println("Order Number: " + order.getOrderNumber());
            System.out.println("New Status: " + order.getStatus());
            System.out.println("Assigned Analyst ID: " + (order.getAssignedAnalyst() != null ? order.getAssignedAnalyst().getId() : "None"));
            System.out.println("Assigned Analyst Name: " + (order.getAssignedAnalyst() != null ? order.getAssignedAnalyst().getFullName() : "None"));
            System.out.println("Priority: " + order.getPriority());
            
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            System.out.println("=== ASSIGN ANALYST ERROR ===");
            System.out.println("Error: " + e.getMessage());
            System.out.println("Error Type: " + e.getClass().getName());;
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage(), "type", e.getClass().getName()));
        }
    }
    
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<List<Order>> getOrdersByDoctor(@PathVariable Long doctorId) {
        System.out.println("=== GET ORDERS BY DOCTOR API ===");
        System.out.println("Requested Doctor ID: " + doctorId);
        
        // First, let's verify the doctor exists
        var doctorOpt = userRepository.findById(doctorId);
        if (doctorOpt.isEmpty()) {
            System.out.println("ERROR: Doctor not found with ID: " + doctorId);
            return ResponseEntity.badRequest().body(List.of());
        }
        
        User doctor = doctorOpt.get();
        System.out.println("Doctor found: " + doctor.getFullName() + " (" + doctor.getEmail() + ")");
        System.out.println("Doctor role: " + doctor.getRole());
        
        // Use filtered method - doctors should only see SECOND_OPINION orders
        List<Order> orders = orderService.findByAssignedDoctorId(doctorId);
        System.out.println("=== QUERY RESULT ===");
        System.out.println("Found " + orders.size() + " SECOND_OPINION orders for doctor " + doctorId);
        
        for (Order o : orders) {
            System.out.println("Order: " + o.getOrderNumber() + 
                ", Status: " + o.getStatus() + 
                ", PaymentStatus: " + o.getPaymentStatus() + 
                ", ServiceType: " + o.getServiceType() +
                ", MedicalReportStatus: " + o.getMedicalReportStatus() +
                ", AssignedDoctor: " + (o.getAssignedDoctor() != null ? o.getAssignedDoctor().getId() + "-" + o.getAssignedDoctor().getFullName() : "None"));
        }
        System.out.println("=== END GET ORDERS BY DOCTOR ===");
        return ResponseEntity.ok(orders);
    }
    
    @GetMapping("/with-details")
    public ResponseEntity<List<Order>> getAllOrdersWithDetails(
            @RequestParam(required = false) String paymentStatus) {
        // If paymentStatus is specified, filter by that status
        if (paymentStatus != null && !paymentStatus.isEmpty()) {
            return ResponseEntity.ok(orderService.findByPaymentStatus(paymentStatus));
        }
        // When no paymentStatus is specified, return all orders but filter out unpaid online pharmacy orders
        // Online pharmacy orders should only show after payment is complete
        List<Order> allOrders = orderService.findAllOrdersWithoutFilter();
        List<Order> filteredOrders = allOrders.stream()
            .filter(order -> {
                // For online pharmacy orders, only show if payment status is PAID
                if ("ONLINE_PHARMACY".equals(order.getServiceType())) {
                    return "PAID".equals(order.getPaymentStatus());
                }
                // For other service types, show all orders
                return true;
            })
            .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(filteredOrders);
    }
    
    @GetMapping("/{id}/details")
    public ResponseEntity<Order> getOrderWithDetails(@PathVariable Long id) {
        return orderService.findByIdWithDetails(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/number/{orderNumber}/details")
    public ResponseEntity<Order> getOrderByNumberWithDetails(@PathVariable String orderNumber) {
        return orderService.findByOrderNumberWithDetails(orderNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Order> updateOrder(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        return orderService.updateOrder(id, updates)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/doctor/{doctorId}/details")
    public ResponseEntity<List<Order>> getOrdersByDoctorWithDetails(@PathVariable Long doctorId) {
        // Use filtered method - doctors should only see SECOND_OPINION orders
        return ResponseEntity.ok(orderService.findByAssignedDoctorIdWithDetails(doctorId));
    }
    
    // Save medical report as draft (doesn't change order status to COMPLETED)
    @PostMapping("/{id}/medical-report/draft")
    public ResponseEntity<Order> saveMedicalReportDraft(
            @PathVariable Long id,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long analystId,
            @RequestBody MedicalReportRequest request) {
        // Use analystId if provided and valid (>0), otherwise use doctorId
        Long userId = (analystId != null && analystId > 0) ? analystId : doctorId;
        System.out.println("=== SAVE MEDICAL REPORT DRAFT ===");
        System.out.println("Order ID: " + id);
        System.out.println("Analyst ID: " + analystId);
        System.out.println("Doctor ID: " + doctorId);
        System.out.println("Using User ID: " + userId);
        return ResponseEntity.ok(orderService.saveMedicalReportDraft(
                id, 
                userId, 
                request.getDiagnosis(), 
                request.getRecommendations(), 
                request.getPrescriptionDetails(),
                request.getNotes(),
                request.getChiefComplaints(),
                request.getHistoryPoints(),
                request.getExamFindings(),
                request.getConsultationDate(),
                request.getHeight(),
                request.getWeight(),
                request.getLmp(),
                request.getAnalysisNotes()));
    }
    
    @PostMapping("/{id}/medical-report")
    public ResponseEntity<Order> submitMedicalReport(
            @PathVariable Long id,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long analystId,
            @RequestBody MedicalReportRequest request) {
        // Use analystId if provided and valid (>0), otherwise use doctorId
        Long userId = (analystId != null && analystId > 0) ? analystId : doctorId;
        System.out.println("=== SUBMIT MEDICAL REPORT ===");
        System.out.println("Order ID: " + id);
        System.out.println("Analyst ID: " + analystId);
        System.out.println("Doctor ID: " + doctorId);
        System.out.println("Using User ID: " + userId);
        return ResponseEntity.ok(orderService.submitMedicalReport(
                id, 
                userId, 
                request.getDiagnosis(), 
                request.getRecommendations(), 
                request.getPrescriptionDetails(),
                request.getNotes(),
                request.getChiefComplaints(),
                request.getHistoryPoints(),
                request.getExamFindings(),
                request.getConsultationDate(),
                request.getHeight(),
                request.getWeight(),
                request.getLmp()));
    }
    
    // Download medical report file
    @GetMapping("/{id}/medical-report/download")
    public ResponseEntity<?> downloadMedicalReport(@PathVariable Long id) {
        try {
            // First get the order to check if medical report exists
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getMedicalReportFilePath();
            
            System.out.println("Download Medical Report - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No medical report file found for this order", "hasReport", false));
            }
            
            Path path = getReportFilePath(filePath);
            System.out.println("Full file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"medical_report.pdf\"")
                        .body(resource);
            }
            
            System.out.println("File does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Medical report file not found on server", "hasReport", false));
        } catch (Exception e) {
            System.out.println("Error downloading medical report: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error downloading report: " + e.getMessage()));
        }
    }
    
    // View medical report inline
    @GetMapping("/{id}/medical-report/view")
    public ResponseEntity<?> viewMedicalReport(@PathVariable Long id) {
        try {
            // First get the order to check if medical report exists
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getMedicalReportFilePath();
            
            System.out.println("View Medical Report - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No medical report file found for this order", "hasReport", false));
            }
            
            Path path = getReportFilePath(filePath);
            System.out.println("Full file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "inline; filename=\"medical_report.pdf\"")
                        .body(resource);
            }
            
            System.out.println("File does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Medical report file not found on server", "hasReport", false));
        } catch (Exception e) {
            System.out.println("Error viewing medical report: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error viewing report: " + e.getMessage()));
        }
    }
    
    // Get medical report data (for loading draft/submitted report)
    @GetMapping("/{id}/medical-report")
    public ResponseEntity<?> getMedicalReport(@PathVariable Long id) {
        try {
            // Use findByIdWithDetails to fetch prescription along with order
            var orderOpt = orderService.findByIdWithDetails(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            
            // Get prescription data if exists
            if (order.getPrescription() != null) {
                Prescription prescription = order.getPrescription();
                Map<String, String> response = new HashMap<>();
                response.put("diagnosis", prescription.getDiagnosis() != null ? prescription.getDiagnosis() : "");
                response.put("recommendations", prescription.getRecommendations() != null ? prescription.getRecommendations() : "");
                response.put("prescriptionDetails", prescription.getPrescriptionDetails() != null ? prescription.getPrescriptionDetails() : "");
                response.put("notes", prescription.getNotes() != null ? prescription.getNotes() : "");
                response.put("chiefComplaints", prescription.getChiefComplaints() != null ? prescription.getChiefComplaints() : "");
                response.put("historyPoints", prescription.getHistoryPoints() != null ? prescription.getHistoryPoints() : "");
                response.put("examFindings", prescription.getExamFindings() != null ? prescription.getExamFindings() : "");
                response.put("consultationDate", prescription.getConsultationDate() != null ? prescription.getConsultationDate() : "");
                response.put("height", prescription.getHeight() != null ? prescription.getHeight() : "");
                response.put("weight", prescription.getWeight() != null ? prescription.getWeight() : "");
                response.put("lmp", prescription.getLmp() != null ? prescription.getLmp() : "");
                response.put("status", order.getMedicalReportStatus() != null ? order.getMedicalReportStatus() : "NOT_STARTED");
                return ResponseEntity.ok(response);
            }
            
            // No prescription exists yet
            Map<String, String> response = new HashMap<>();
            response.put("diagnosis", "");
            response.put("recommendations", "");
            response.put("prescriptionDetails", "");
            response.put("notes", "");
            response.put("chiefComplaints", "");
            response.put("historyPoints", "");
            response.put("examFindings", "");
            response.put("consultationDate", "");
            response.put("height", "");
            response.put("weight", "");
            response.put("lmp", "");
            response.put("status", "NOT_STARTED");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            System.out.println("Error getting medical report: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error getting medical report: " + e.getMessage()));
        }
    }
    
    // Get medical report file path
    @GetMapping("/{id}/medical-report/path")
    public ResponseEntity<?> getMedicalReportPath(@PathVariable Long id) {
        String filePath = orderService.getMedicalReportFilePath(id);
        return ResponseEntity.ok(Map.of("filePath", filePath != null ? filePath : ""));
    }
    
    // Generate bill PDF for an order (Accountant function)
    @PostMapping("/{id}/generate-bill")
    public ResponseEntity<Order> generateBill(@PathVariable Long id) {
        try {
            System.out.println("Received request to generate bill for order: " + id);
            Order order = orderService.generateBill(id);
            System.out.println("Bill generated successfully, path: " + order.getBillFilePath());
            return ResponseEntity.ok(order);
        } catch (Throwable e) {
            System.out.println("ERROR in generateBill: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Get bill file path
    @GetMapping("/{id}/bill/path")
    public ResponseEntity<?> getBillPath(@PathVariable Long id) {
        String storedPath = orderService.getBillFilePath(id);
        boolean hasBill = storedPath != null && !storedPath.isEmpty();
        String viewUrl = hasBill ? "/api/orders/" + id + "/bill/view" : "";
        String downloadUrl = hasBill ? "/api/orders/" + id + "/bill/download" : "";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filePath", viewUrl);
        response.put("viewUrl", viewUrl);
        response.put("downloadUrl", downloadUrl);
        response.put("storedPath", storedPath != null ? storedPath : "");
        response.put("hasBill", hasBill);
        return ResponseEntity.ok(response);
    }
    
    // Download bill PDF
    @GetMapping("/{id}/bill/download")
    public ResponseEntity<?> downloadBill(@PathVariable Long id) {
        try {
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getBillFilePath();
            
            System.out.println("Download Bill - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No bill found for this order", "hasBill", false));
            }
            
            Path path = getBillFilePath(filePath);
            System.out.println("Full bill file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"bill_" + order.getOrderNumber() + ".pdf\"")
                        .body(resource);
            }
            
            System.out.println("Bill file does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Bill file not found on server", "hasBill", false));
        } catch (Exception e) {
            System.out.println("Error downloading bill: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error downloading bill: " + e.getMessage()));
        }
    }
    
    // View bill PDF inline
    @GetMapping("/{id}/bill/view")
    public ResponseEntity<?> viewBill(@PathVariable Long id) {
        try {
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getBillFilePath();
            
            System.out.println("View Bill - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No bill found for this order", "hasBill", false));
            }
            
            Path path = getBillFilePath(filePath);
            System.out.println("Full bill file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "inline; filename=\"bill_" + order.getOrderNumber() + ".pdf\"")
                        .body(resource);
            }
            
            System.out.println("Bill file does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Bill file not found on server", "hasBill", false));
        } catch (Exception e) {
            System.out.println("Error viewing bill: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error viewing bill: " + e.getMessage()));
        }
    }
    
    // Helper method to get bill file path
    private Path getBillFilePath(String filePath) throws MalformedURLException {
        if (filePath == null) {
            return null;
        }
        
        // The filePath is just the filename (e.g., "bill_ORD123_1234567890.pdf")
        // We need to combine it with the upload directory to get the full path
        String uploadDir = orderService.getUploadDirectory();
        Path fullPath = Paths.get(uploadDir, filePath);
        return fullPath;
    }
    
    // Generate payment receipt for an order
    @PostMapping("/{id}/generate-receipt")
    public ResponseEntity<?> generatePaymentReceipt(@PathVariable Long id) {
        try {
            System.out.println("Received request to generate payment receipt for order: " + id);
            Order order = orderService.generatePaymentReceipt(id);
            System.out.println("Payment receipt generated successfully, path: " + order.getPaymentReceiptPath());
            return ResponseEntity.ok(order);
        } catch (Throwable e) {
            System.out.println("ERROR in generatePaymentReceipt: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            // Return detailed error message
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage(), "type", e.getClass().getName()));
        }
    }
    
    // Get payment receipt file path
    @GetMapping("/{id}/receipt/path")
    public ResponseEntity<?> getPaymentReceiptPath(@PathVariable Long id) {
        String storedPath = orderService.getPaymentReceiptPath(id);
        boolean hasReceipt = storedPath != null && !storedPath.isEmpty();
        String viewUrl = hasReceipt ? "/api/orders/" + id + "/receipt/view" : "";
        String downloadUrl = hasReceipt ? "/api/orders/" + id + "/receipt/download" : "";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filePath", viewUrl);
        response.put("viewUrl", viewUrl);
        response.put("downloadUrl", downloadUrl);
        response.put("storedPath", storedPath != null ? storedPath : "");
        response.put("hasReceipt", hasReceipt);
        return ResponseEntity.ok(response);
    }
    
    // Download payment receipt PDF
    @GetMapping("/{id}/receipt/download")
    public ResponseEntity<?> downloadPaymentReceipt(@PathVariable Long id) {
        try {
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getPaymentReceiptPath();
            
            // If paymentReceiptPath is null, try billFilePath for backward compatibility
            if (filePath == null || filePath.isEmpty()) {
                filePath = order.getBillFilePath();
            }
            
            System.out.println("Download Payment Receipt - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "No payment receipt found for this order", "hasReceipt", false));
            }
            
            Path path = getReceiptFilePath(filePath);
            System.out.println("Full receipt file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                String downloadFileName = path.getFileName() != null
                        ? path.getFileName().toString()
                        : "payment_receipt_" + order.getOrderNumber() + ".pdf";
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .cacheControl(org.springframework.http.CacheControl.noStore().mustRevalidate())
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .header(HttpHeaders.EXPIRES, "0")
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"" + downloadFileName + "\"")
                        .body(resource);
            }
            
            System.out.println("Receipt file does not exist at path: " + path);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Payment receipt file not found on server", "hasReceipt", false));
        } catch (Exception e) {
            System.out.println("Error downloading payment receipt: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error downloading receipt: " + e.getMessage()));
        }
    }
    
    // View payment receipt PDF inline
    @GetMapping("/{id}/receipt/view")
    public ResponseEntity<?> viewPaymentReceipt(@PathVariable Long id) {
        try {
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getPaymentReceiptPath();
            
            // If paymentReceiptPath is null, try billFilePath for backward compatibility
            if (filePath == null || filePath.isEmpty()) {
                filePath = order.getBillFilePath();
            }
            
            System.out.println("View Payment Receipt - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "No payment receipt found for this order", "hasReceipt", false));
            }
            
            Path path = getReceiptFilePath(filePath);
            System.out.println("Full receipt file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                String inlineFileName = path.getFileName() != null
                        ? path.getFileName().toString()
                        : "payment_receipt_" + order.getOrderNumber() + ".pdf";
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .cacheControl(org.springframework.http.CacheControl.maxAge(0, TimeUnit.SECONDS).mustRevalidate().noTransform())
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .header(HttpHeaders.EXPIRES, "0")
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "inline; filename=\"" + inlineFileName + "\"")
                        .body(resource);
            }
            
            System.out.println("Receipt file does not exist at path: " + path);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Payment receipt file not found on server", "hasReceipt", false));
        } catch (Exception e) {
            System.out.println("Error viewing payment receipt: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error viewing receipt: " + e.getMessage()));
        }
    }
    
    // Helper method to get receipt file path
    private Path getReceiptFilePath(String filePath) throws MalformedURLException {
        if (filePath == null) {
            return null;
        }

        Path storedPath = Paths.get(filePath);
        if (storedPath.isAbsolute()) {
            return storedPath;
        }

        String relativePath = filePath
                .replace("/uploads/documents/", "")
                .replace("uploads/documents/", "")
                .replace("/", File.separator)
                .replace("\\", File.separator);

        return Paths.get(orderService.getUploadDirectory(), relativePath);
    }
    
    // Helper method to get report file path
    private Path getReportFilePath(String filePath) throws MalformedURLException {
        if (filePath == null) {
            return null;
        }
        
        // Remove /uploads/documents/ prefix if present
        String relativePath = filePath.replace("/uploads/documents/", "");
        // Replace forward slashes with backslashes for Windows
        relativePath = relativePath.replace("/", File.separator);
        return Paths.get(orderService.getUploadDirectory(), relativePath);
    }
    
    // DTO for medical report submission
    public static class MedicalReportRequest {
        private String diagnosis;
        private String recommendations;
        private String prescriptionDetails;
        private String notes;
        private String chiefComplaints;
        private String historyPoints;
        private String examFindings;
        private String consultationDate;
        private String height;
        private String weight;
        private String lmp;
        private String analysisNotes;
        
        public String getDiagnosis() {
            return diagnosis;
        }
        
        public void setDiagnosis(String diagnosis) {
            this.diagnosis = diagnosis;
        }
        
        public String getRecommendations() {
            return recommendations;
        }
        
        public void setRecommendations(String recommendations) {
            this.recommendations = recommendations;
        }
        
        public String getPrescriptionDetails() {
            return prescriptionDetails;
        }
        
        public void setPrescriptionDetails(String prescriptionDetails) {
            this.prescriptionDetails = prescriptionDetails;
        }
        
        public String getNotes() {
            return notes;
        }
        
        public void setNotes(String notes) {
            this.notes = notes;
        }
        
        public String getChiefComplaints() {
            return chiefComplaints;
        }
        
        public void setChiefComplaints(String chiefComplaints) {
            this.chiefComplaints = chiefComplaints;
        }
        
        public String getHistoryPoints() {
            return historyPoints;
        }
        
        public void setHistoryPoints(String historyPoints) {
            this.historyPoints = historyPoints;
        }
        
        public String getExamFindings() {
            return examFindings;
        }
        
        public void setExamFindings(String examFindings) {
            this.examFindings = examFindings;
        }
        
        public String getConsultationDate() {
            return consultationDate;
        }
        
        public void setConsultationDate(String consultationDate) {
            this.consultationDate = consultationDate;
        }
        
        public String getHeight() {
            return height;
        }
        
        public void setHeight(String height) {
            this.height = height;
        }
        
        public String getWeight() {
            return weight;
        }
        
        public void setWeight(String weight) {
            this.weight = weight;
        }
        
        public String getLmp() {
            return lmp;
        }
        
        public void setLmp(String lmp) {
            this.lmp = lmp;
        }
        
        public String getAnalysisNotes() {
            return analysisNotes;
        }
        
        public void setAnalysisNotes(String analysisNotes) {
            this.analysisNotes = analysisNotes;
        }
    }
    
    // DTO for sending prescription email with full prescription data
    public static class PrescriptionEmailRequest {
        private String patientName;
        private String age;
        private String gender;
        private String address;
        private String consultationDate;
        private String height;
        private String weight;
        private String chiefComplaints;
        private String diagnosis;
        private String historyPoints;
        private String examFindings;
        private String medicines; // JSON string of medicines array
        private String investigations;
        private String suggestedInvestigations;
        private String specialInstructions;
        private Long doctorId;
        private String doctorName;
        private String doctorQualification;
        private String doctorRegistrationNumber;
        private String doctorAddress;
        private String doctorContact;
        private String doctorEmail;
        private String pdfBase64; // PDF from frontend
        
        // Getters and setters
        public String getPatientName() { return patientName; }
        public void setPatientName(String patientName) { this.patientName = patientName; }
        public String getAge() { return age; }
        public void setAge(String age) { this.age = age; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getConsultationDate() { return consultationDate; }
        public void setConsultationDate(String consultationDate) { this.consultationDate = consultationDate; }
        public String getHeight() { return height; }
        public void setHeight(String height) { this.height = height; }
        public String getWeight() { return weight; }
        public void setWeight(String weight) { this.weight = weight; }
        public String getChiefComplaints() { return chiefComplaints; }
        public void setChiefComplaints(String chiefComplaints) { this.chiefComplaints = chiefComplaints; }
        public String getDiagnosis() { return diagnosis; }
        public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
        public String getHistoryPoints() { return historyPoints; }
        public void setHistoryPoints(String historyPoints) { this.historyPoints = historyPoints; }
        public String getExamFindings() { return examFindings; }
        public void setExamFindings(String examFindings) { this.examFindings = examFindings; }
        public String getMedicines() { return medicines; }
        public void setMedicines(String medicines) { this.medicines = medicines; }
        public String getInvestigations() { return investigations; }
        public void setInvestigations(String investigations) { this.investigations = investigations; }
        public String getSuggestedInvestigations() { return suggestedInvestigations; }
        public void setSuggestedInvestigations(String suggestedInvestigations) { this.suggestedInvestigations = suggestedInvestigations; }
        public String getSpecialInstructions() { return specialInstructions; }
        public void setSpecialInstructions(String specialInstructions) { this.specialInstructions = specialInstructions; }
        public Long getDoctorId() { return doctorId; }
        public void setDoctorId(Long doctorId) { this.doctorId = doctorId; }
        public String getDoctorName() { return doctorName; }
        public void setDoctorName(String doctorName) { this.doctorName = doctorName; }
        public String getDoctorQualification() { return doctorQualification; }
        public void setDoctorQualification(String doctorQualification) { this.doctorQualification = doctorQualification; }
        public String getDoctorRegistrationNumber() { return doctorRegistrationNumber; }
        public void setDoctorRegistrationNumber(String doctorRegistrationNumber) { this.doctorRegistrationNumber = doctorRegistrationNumber; }
        public String getDoctorAddress() { return doctorAddress; }
        public void setDoctorAddress(String doctorAddress) { this.doctorAddress = doctorAddress; }
        public String getDoctorContact() { return doctorContact; }
        public void setDoctorContact(String doctorContact) { this.doctorContact = doctorContact; }
        public String getDoctorEmail() { return doctorEmail; }
        public void setDoctorEmail(String doctorEmail) { this.doctorEmail = doctorEmail; }
        public String getPdfBase64() { return pdfBase64; }
        public void setPdfBase64(String pdfBase64) { this.pdfBase64 = pdfBase64; }
    }

    public static class SendBillEmailRequest {
        private String pdfBase64;
        private String attachmentName;
        private String emailType;

        public String getPdfBase64() { return pdfBase64; }
        public void setPdfBase64(String pdfBase64) { this.pdfBase64 = pdfBase64; }
        public String getAttachmentName() { return attachmentName; }
        public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }
        public String getEmailType() { return emailType; }
        public void setEmailType(String emailType) { this.emailType = emailType; }
    }
    
    // Send prescription email with PDF - generates PDF from form data
    @PostMapping("/{id}/send-prescription-email")
    public ResponseEntity<String> sendPrescriptionEmail(
            @PathVariable Long id,
            @RequestBody PrescriptionEmailRequest request) {
        try {
            System.out.println("Received request to send prescription email for order: " + id);
            orderService.sendPrescriptionEmailWithPDF(id, request);
            return ResponseEntity.ok("Prescription email sent successfully");
        } catch (Exception e) {
            System.out.println("ERROR sending prescription email: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Failed to send prescription email: " + e.getMessage());
        }
    }
    
    // Get prescription file path
    @GetMapping("/{id}/prescription/path")
    public ResponseEntity<?> getPrescriptionPath(@PathVariable Long id) {
        String filePath = orderService.getPrescriptionPath(id);
        return ResponseEntity.ok(Map.of("filePath", filePath != null ? filePath : "", "hasPrescription", filePath != null && !filePath.isEmpty()));
    }
    
    // Download prescription PDF
    @GetMapping("/{id}/prescription/download")
    public ResponseEntity<?> downloadPrescription(@PathVariable Long id) {
        try {
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getPrescriptionPath();
            
            System.out.println("Download Prescription - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No prescription found for this order", "hasPrescription", false));
            }
            
            Path path = getPrescriptionFilePath(filePath);
            System.out.println("Full prescription file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"prescription_" + order.getOrderNumber() + ".pdf\"")
                        .body(resource);
            }
            
            System.out.println("Prescription file does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Prescription file not found on server", "hasPrescription", false));
        } catch (Exception e) {
            System.out.println("Error downloading prescription: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error downloading prescription: " + e.getMessage()));
        }
    }
    
    // View prescription PDF inline
    @GetMapping("/{id}/prescription/view")
    public ResponseEntity<?> viewPrescription(@PathVariable Long id) {
        try {
            var orderOpt = orderService.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Order order = orderOpt.get();
            String filePath = order.getPrescriptionPath();
            
            System.out.println("View Prescription - Order ID: " + id + ", FilePath: " + filePath);
            
            if (filePath == null || filePath.isEmpty()) {
                return ResponseEntity.ok()
                    .body(Map.of("message", "No prescription found for this order", "hasPrescription", false));
            }
            
            Path path = getPrescriptionFilePath(filePath);
            System.out.println("Full prescription file path: " + path);
            
            Resource resource = new UrlResource(path.toUri());
            
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "inline; filename=\"prescription_" + order.getOrderNumber() + ".pdf\"")
                        .body(resource);
            }
            
            System.out.println("Prescription file does not exist at path: " + path);
            return ResponseEntity.ok()
                .body(Map.of("message", "Prescription file not found on server", "hasPrescription", false));
        } catch (Exception e) {
            System.out.println("Error viewing prescription: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error viewing prescription: " + e.getMessage()));
        }
    }
    
    // Helper method to get prescription file path
    private Path getPrescriptionFilePath(String filePath) throws MalformedURLException {
        if (filePath == null) {
            return null;
        }
        
        // Remove /uploads/documents/ prefix if present
        String relativePath = filePath.replace("/uploads/documents/", "");
        // Replace forward slashes with backslashes for Windows
        relativePath = relativePath.replace("/", File.separator);
        return Paths.get(orderService.getUploadDirectory(), relativePath);
    }
    
    // Reset payment status to PENDING for a specific order
    @PostMapping("/{id}/reset-payment-status")
    public ResponseEntity<Map<String, Object>> resetOrderPaymentStatus(@PathVariable Long id) {
        try {
            Order order = orderService.resetOrderPaymentStatus(id);
            return ResponseEntity.ok(Map.of(
                "message", "Payment status reset successfully",
                "orderId", id,
                "paymentStatus", order.getPaymentStatus()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to reset payment status: " + e.getMessage()));
        }
    }
    
    // Reset payment status to PENDING for ALL orders incorrectly marked as PAID (global reset)
    // This is a fix for the bug where orders with amount > 1000 were incorrectly marked as PAID
    @PostMapping("/reset-payment-status")
    public ResponseEntity<Map<String, Object>> resetIncorrectPaymentStatus() {
        try {
            int count = orderService.resetIncorrectPaymentStatus();
            return ResponseEntity.ok(Map.of(
                "message", "Payment status reset completed",
                "fixedCount", count
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to reset payment status: " + e.getMessage()));
        }
    }
    
    // Fix orders with invalid user email (patient@rxincredible.com)
    // This endpoint helps fix orders that are linked to the test user
    @PostMapping("/fix-user-email")
    public ResponseEntity<Map<String, Object>> fixOrdersWithInvalidEmail(
            @RequestParam String invalidEmail,
            @RequestParam Long validUserId) {
        try {
            List<Order> orders = orderService.fixOrdersWithInvalidUserEmail(invalidEmail, validUserId);
            return ResponseEntity.ok(Map.of(
                "message", "Orders fixed successfully",
                "totalOrders", orders.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to fix orders: " + e.getMessage()));
        }
    }
    
    // Get all orders with user emails (for debugging)
    @GetMapping("/debug-users")
    public ResponseEntity<List<Order>> getAllOrdersWithUserEmails() {
        return ResponseEntity.ok(orderService.getAllOrdersWithUserEmails());
    }
    
    // Debug: Get all orders with assigned doctors (for checking assignment)
    @GetMapping("/debug/assigned")
    public ResponseEntity<List<Map<String, Object>>> getAllOrdersWithAssignedDoctors() {
        List<Order> orders = orderService.getAllOrdersWithUserEmails();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Order o : orders) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("orderId", o.getId());
            map.put("orderNumber", o.getOrderNumber());
            map.put("status", o.getStatus());
            map.put("serviceType", o.getServiceType());
            map.put("paymentStatus", o.getPaymentStatus());
            map.put("assignedDoctorId", o.getAssignedDoctor() != null ? o.getAssignedDoctor().getId() : null);
            map.put("assignedDoctorName", o.getAssignedDoctor() != null ? o.getAssignedDoctor().getFullName() : null);
            map.put("assignedDoctorEmail", o.getAssignedDoctor() != null ? o.getAssignedDoctor().getEmail() : null);
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }
    
    // Debug: Get orders for a specific doctor with full details
    @GetMapping("/debug/doctor/{doctorId}/orders")
    public ResponseEntity<Map<String, Object>> debugDoctorOrders(@PathVariable Long doctorId) {
        Map<String, Object> response = new HashMap<>();
        
        // Get doctor details
        User doctor = userRepository.findById(doctorId).orElse(null);
        if (doctor == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Doctor not found"));
        }
        
        response.put("doctorId", doctorId);
        response.put("doctorName", doctor.getFullName());
        response.put("doctorEmail", doctor.getEmail());
        
        // Get all orders using orderService
        List<Order> allOrders = orderService.getAllOrdersWithUserEmails();
        List<Map<String, Object>> assignedOrders = new java.util.ArrayList<>();
        
        for (Order o : allOrders) {
            if (o.getAssignedDoctor() != null && o.getAssignedDoctor().getId().equals(doctorId)) {
                Map<String, Object> orderMap = new HashMap<>();
                orderMap.put("orderId", o.getId());
                orderMap.put("orderNumber", o.getOrderNumber());
                orderMap.put("status", o.getStatus());
                orderMap.put("serviceType", o.getServiceType());
                orderMap.put("paymentStatus", o.getPaymentStatus());
                orderMap.put("medicalReportStatus", o.getMedicalReportStatus());
                assignedOrders.add(orderMap);
            }
        }
        
        response.put("totalAssignedOrders", assignedOrders.size());
        response.put("orders", assignedOrders);
        
        return ResponseEntity.ok(response);
    }
    
    // Get orders by assigned analyst - FILTERED to only return PRESCRIPTION_ANALYSIS orders
    // Analysts should only see prescription analysis orders, not second opinion or online pharmacy orders
    @GetMapping("/analyst/{analystId}")
    public ResponseEntity<List<Order>> getOrdersByAnalyst(@PathVariable Long analystId) {
        System.out.println("=== GET ORDERS BY ANALYST API ===");
        System.out.println("Requested Analyst ID: " + analystId);
        
        // First, let's verify the analyst exists
        var analystOpt = userRepository.findById(analystId);
        if (analystOpt.isEmpty()) {
            System.out.println("ERROR: Analyst not found with ID: " + analystId);
            return ResponseEntity.badRequest().body(List.of());
        }
        
        User analyst = analystOpt.get();
        
        // Verify the user is actually an analyst
        if (!"ANALYST".equals(analyst.getRole())) {
            System.out.println("ERROR: User with ID " + analystId + " is not an analyst. Role: " + analyst.getRole());
            return ResponseEntity.badRequest().body(List.of());
        }
        
        System.out.println("Analyst found: " + analyst.getFullName() + " (" + analyst.getEmail() + ")");
        System.out.println("Analyst role: " + analyst.getRole());
        
        // Use filtered method - analysts should only see PRESCRIPTION_ANALYSIS orders
        List<Order> orders = orderService.findByAssignedAnalystId(analystId);
        System.out.println("=== QUERY RESULT ===");
        System.out.println("Found " + orders.size() + " PRESCRIPTION_ANALYSIS orders for analyst " + analystId);
        
        for (Order o : orders) {
            System.out.println("Order: " + o.getOrderNumber() + 
                ", Status: " + o.getStatus() + 
                ", PaymentStatus: " + o.getPaymentStatus() + 
                ", ServiceType: " + o.getServiceType() +
                ", MedicalReportStatus: " + o.getMedicalReportStatus() +
                ", AssignedAnalyst: " + (o.getAssignedAnalyst() != null ? o.getAssignedAnalyst().getId() + "-" + o.getAssignedAnalyst().getFullName() : "None"));
        }
        System.out.println("=== END GET ORDERS BY ANALYST ===");
        return ResponseEntity.ok(orders);
    }
    
    // Update priority for an order
    @PutMapping("/{id}/priority")
    public ResponseEntity<?> updatePriority(
            @PathVariable Long id,
            @RequestParam String priority) {
        try {
            System.out.println("=== UPDATE PRIORITY API CALL ===");
            System.out.println("Order ID: " + id);
            System.out.println("Priority: " + priority);
            
            Order order = orderService.updatePriority(id, priority);
            System.out.println("=== UPDATE PRIORITY SUCCESS ===");
            System.out.println("Order Number: " + order.getOrderNumber());
            System.out.println("New Priority: " + order.getPriority());
            
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            System.out.println("=== UPDATE PRIORITY ERROR ===");
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
