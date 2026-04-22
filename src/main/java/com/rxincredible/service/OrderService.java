package com.rxincredible.service;

import com.rxincredible.entity.Order;
import com.rxincredible.entity.Prescription;
import com.rxincredible.entity.User;
import com.rxincredible.entity.MedicalService;
import com.rxincredible.entity.Document;
import com.rxincredible.repository.OrderRepository;
import com.rxincredible.repository.PrescriptionRepository;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.repository.MedicalServiceRepository;
import com.rxincredible.repository.DocumentRepository;
import com.rxincredible.controller.OrderController.PrescriptionEmailRequest;
import com.rxincredible.util.CurrencyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.json.JSONObject;


import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDFont;

@Service
public class OrderService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");
    
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final EmailService emailService;
    private final BillService billService;
    private final MedicalServiceRepository medicalServiceRepository;
    private final DocumentRepository documentRepository;
    
    @Value("${app.upload.directory}")
    private String uploadDirectory;
    
    public OrderService(OrderRepository orderRepository, UserRepository userRepository, PrescriptionRepository prescriptionRepository, EmailService emailService, BillService billService, MedicalServiceRepository medicalServiceRepository, DocumentRepository documentRepository) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.emailService = emailService;
        this.billService = billService;
        this.medicalServiceRepository = medicalServiceRepository;
        this.documentRepository = documentRepository;
    }
    
    @Transactional
    public Order createOrder(Order order, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        order.setUser(user);
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setStatus("PENDING");
        
        String requestedPaymentStatus = order.getPaymentStatus();

        // Only keep PAID when the request explicitly represents a completed payment.
        if (requestedPaymentStatus == null || requestedPaymentStatus.isEmpty()) {
            order.setPaymentStatus("PENDING");
        } else if (!"PAID".equalsIgnoreCase(requestedPaymentStatus)) {
            order.setPaymentStatus("PENDING");
        } else {
            System.out.println("Payment status already set from frontend: " + order.getPaymentStatus());
        }

        // Never keep a payment reference for unpaid orders.
        if (!"PAID".equals(order.getPaymentStatus())) {
            order.setPaymentReference(null);
        }
        
        // Preserve delivery address from frontend if provided
        if (order.getDeliveryAddress() != null && !order.getDeliveryAddress().isEmpty()) {
            System.out.println("Delivery address from frontend: " + order.getDeliveryAddress());
        }
        if (order.getDeliveryCity() != null && !order.getDeliveryCity().isEmpty()) {
            System.out.println("Delivery city from frontend: " + order.getDeliveryCity());
        }
        if (order.getDeliveryState() != null && !order.getDeliveryState().isEmpty()) {
            System.out.println("Delivery state from frontend: " + order.getDeliveryState());
        }
        if (order.getDeliveryPincode() != null && !order.getDeliveryPincode().isEmpty()) {
            System.out.println("Delivery pincode from frontend: " + order.getDeliveryPincode());
        }
        
        // Preserve paymentMethod from frontend if provided (e.g., UPI, CREDIT_CARD)
        if ("PAID".equals(order.getPaymentStatus()) && order.getPaymentMethod() != null && !order.getPaymentMethod().isEmpty()) {
            System.out.println("Payment method from frontend: " + order.getPaymentMethod());
        } else if ("PAID".equals(order.getPaymentStatus())) {
            // If payment is PAID but no method specified, set default
            order.setPaymentMethod("CARD");
        } else {
            order.setPaymentMethod("NONE");
        }
        
        BigDecimal fixedServiceAmount = resolveFixedServiceAmount(order);
        if (fixedServiceAmount != null) {
            if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(fixedServiceAmount) != 0) {
                logger.info("Overriding frontend amount {} with {} for fixed-price service {}",
                        order.getTotalAmount(), fixedServiceAmount, order.getServiceType());
            }
            order.setTotalAmount(fixedServiceAmount);
        } else if (order.getTotalAmount() == null) {
            order.setTotalAmount(BigDecimal.ZERO);
        } else {
            System.out.println("totalAmount already set from frontend: " + order.getTotalAmount());
        }
        
        return orderRepository.save(order);
    }
    
    // Map service type to category for looking up in MedicalService
    private String getServiceCategory(String serviceType) {
        if (serviceType == null) return null;
        
        switch (serviceType) {
            case "PRESCRIPTION_ANALYSIS":
                return "prescription";
            case "SECOND_OPINION":
                return "consultation";
            case "ONLINE_PHARMACY":
                return "pharmacy";
            default:
                return null;
        }
    }

    public boolean isFixedPriceService(String serviceType) {
        return resolveFixedServiceAmount(serviceType) != null;
    }

    public boolean isFixedPriceOrder(Order order) {
        return resolveFixedServiceAmount(order) != null;
    }

    public BigDecimal resolvePayableAmount(Order order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal fixedServiceAmount = resolveFixedServiceAmount(order);
        if (fixedServiceAmount != null) {
            return fixedServiceAmount;
        }

        return order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
    }

    public Long resolvePayableAmountInPaise(Order order) {
        BigDecimal amount = resolvePayableAmount(order).setScale(2, RoundingMode.HALF_UP);
        return amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    public String resolvePayableCurrencyCode(Order order) {
        return CurrencyUtil.resolveCurrencyCode(resolveCountryForPricing(order));
    }

    public String resolvePayableCurrencySymbol(Order order) {
        return CurrencyUtil.resolveCurrencySymbol(resolveCountryForPricing(order));
    }

    private BigDecimal resolveFixedServiceAmount(Order order) {
        if (order == null || isCombinedServiceSelection(order)) {
            return null;
        }

        return resolveFixedServiceAmount(order.getServiceType(), resolveCountryForPricing(order));
    }

    private BigDecimal resolveFixedServiceAmount(String serviceType) {
        return resolveFixedServiceAmount(serviceType, "India");
    }

    private BigDecimal resolveFixedServiceAmount(String serviceType, String country) {
        if (serviceType == null) {
            return null;
        }

        boolean isIndia = CurrencyUtil.isIndia(country);
        return switch (serviceType) {
            case "PRESCRIPTION_ANALYSIS" -> isIndia
                    ? applyIndiaGst(findActiveServicePrice("prescription", new BigDecimal("500.00")))
                    : new BigDecimal("10.00");
            case "SECOND_OPINION" -> isIndia
                    ? applyIndiaGst(findActiveServicePrice("consultation", new BigDecimal("5000.00")))
                    : new BigDecimal("100.00");
            default -> null;
        };
    }

    private BigDecimal findActiveServicePrice(String category, BigDecimal fallbackAmount) {
        return medicalServiceRepository.findByIsActiveTrueAndCategory(category).stream()
                .map(MedicalService::getPrice)
                .findFirst()
                .orElse(fallbackAmount);
    }

    private BigDecimal applyIndiaGst(BigDecimal baseAmount) {
        BigDecimal safeBaseAmount = baseAmount != null ? baseAmount : BigDecimal.ZERO;
        return safeBaseAmount.multiply(BigDecimal.ONE.add(GST_RATE)).setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveCountryForPricing(Order order) {
        if (order.getDeliveryCountry() != null && !order.getDeliveryCountry().isBlank()) {
            return order.getDeliveryCountry();
        }
        if (order.getUser() != null && order.getUser().getCountry() != null && !order.getUser().getCountry().isBlank()) {
            return order.getUser().getCountry();
        }
        return "India";
    }

    private boolean isCombinedServiceSelection(Order order) {
        if (order == null || order.getOrderDetails() == null || order.getOrderDetails().isBlank()) {
            return false;
        }

        try {
            JSONObject orderDetails = new JSONObject(order.getOrderDetails());
            JSONObject services = orderDetails.optJSONObject("services");
            return services != null
                    && services.optBoolean("prescriptionAnalysis", false)
                    && services.optBoolean("secondOpinion", false);
        } catch (Exception e) {
            logger.debug("Unable to inspect order details for combined service selection on order {}", order.getId(), e);
            return false;
        }
    }
    
    // For admin: Get all orders with payment status filter
    // Only returns paid orders by default to ensure orders only show after payment
    public List<Order> findAllOrders() {
        // Default to PAID orders only - orders only appear after user makes payment
        return orderRepository.findByPaymentStatus("PAID");
    }
    
    // For user orders page: Get all orders without payment filter
    // Online Pharmacy orders should show in user inquiries even before payment
    // After payment, they will shift to backend (admin sees only PAID orders)
    public List<Order> findAllOrdersWithoutFilter() {
        // Return all orders including unpaid Online Pharmacy orders
        // Users should see their orders in inquiries page even before payment
        return orderRepository.findAllWithDetails();
    }
    
    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }
    
    public Optional<Order> updateOrder(Long id, Map<String, Object> updates) {
        Optional<Order> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            return Optional.empty();
        }
        
        Order order = orderOpt.get();
        
        // Update fields if provided
        if (updates.containsKey("deliveryAddress")) {
            order.setDeliveryAddress((String) updates.get("deliveryAddress"));
        }
        if (updates.containsKey("deliveryCity")) {
            order.setDeliveryCity((String) updates.get("deliveryCity"));
        }
        if (updates.containsKey("deliveryState")) {
            order.setDeliveryState((String) updates.get("deliveryState"));
        }
        if (updates.containsKey("deliveryPincode")) {
            order.setDeliveryPincode((String) updates.get("deliveryPincode"));
        }
        if (updates.containsKey("deliveryCountry")) {
            order.setDeliveryCountry((String) updates.get("deliveryCountry"));
        }
        if (updates.containsKey("deliveryPhone")) {
            order.setDeliveryPhone((String) updates.get("deliveryPhone"));
        }
        if (updates.containsKey("deliveryStatus")) {
            order.setDeliveryStatus((String) updates.get("deliveryStatus"));
        }
        if (updates.containsKey("status")) {
            order.setStatus((String) updates.get("status"));
        }
        if (updates.containsKey("serviceType")) {
            order.setServiceType((String) updates.get("serviceType"));
        }
        if (updates.containsKey("medicalReportStatus")) {
            order.setMedicalReportStatus((String) updates.get("medicalReportStatus"));
        }
        
        return Optional.of(orderRepository.save(order));
    }
    
    public Optional<Order> findByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber);
    }
    
    public List<Order> findByUserId(Long userId) {
        return orderRepository.findUserOrders(userId);
    }
    
    // Get user's inquiries (orders with PENDING payment status)
    // These orders show in user's inquiries page before payment
    // Orders older than 72 hours are automatically removed
    public List<Order> findUserInquiries(Long userId) {
        List<Order> allOrders = orderRepository.findUserOrders(userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoffTime = now.minusHours(72);
        
        // Return only unpaid ONLINE_PHARMACY orders in inquiries
        // and created within last 72 hours
        return allOrders.stream()
            .filter(order -> "ONLINE_PHARMACY".equals(order.getServiceType()))
            .filter(order -> "PENDING".equals(order.getPaymentStatus()))
            .filter(order -> order.getCreatedAt().isAfter(cutoffTime))
            .collect(Collectors.toList());
    }
    
    // Remove expired unpaid orders (older than 72 hours)
    // This should be called periodically or on-demand
    @Transactional
    public int removeExpiredUnpaidOrders() {
        List<Order> allOrders = orderRepository.findAll();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoffTime = now.minusHours(72);
        int removedCount = 0;
        
        for (Order order : allOrders) {
            // Check if order is unpaid and older than 72 hours
            if ("PENDING".equals(order.getPaymentStatus()) && 
                order.getCreatedAt().isBefore(cutoffTime)) {
                System.out.println("Removing expired unpaid order: " + order.getOrderNumber() + 
                    " (Created: " + order.getCreatedAt() + ", Age: " + 
                    java.time.Duration.between(order.getCreatedAt(), now).toHours() + " hours)");
                orderRepository.delete(order);
                removedCount++;
            }
        }
        
        System.out.println("Total expired unpaid orders removed: " + removedCount);
        return removedCount;
    }
    
    // Get user's orders (orders with PAID payment status)
    // These orders show in user's orders page after payment
    public List<Order> findUserOrders(Long userId) {
        List<Order> allOrders = orderRepository.findUserOrders(userId);
        // Return paid orders only.
        // This ensures ONLINE_PHARMACY moves from inquiries to orders only after payment.
        return allOrders.stream()
            .filter(order -> "PAID".equals(order.getPaymentStatus()))
            .collect(Collectors.toList());
    }
    
    public List<Order> findByStatus(String status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }
    
    @Transactional
    public Order updateStatus(Long id, String status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);
        
        // Email notifications are handled elsewhere - don't send duplicate emails here
        
        return savedOrder;
    }
    
    public void sendOrderEmail(Long orderId) {
        sendOrderEmail(orderId, null, null, null);
    }

    public void sendOrderEmail(Long orderId, String pdfBase64) {
        sendOrderEmail(orderId, pdfBase64, null, null);
    }

    public void sendOrderEmail(Long orderId, String pdfBase64, String attachmentName, String emailType) {
        logger.info("Starting quotation email workflow for order ID: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        String recipientEmail = order.getUser() != null ? order.getUser().getEmail() : order.getUserEmail();
        logger.info("Preparing bill email for order {} and recipient {}", order.getOrderNumber(), recipientEmail);
        logger.info("Current billFilePath for order {}: {}", order.getOrderNumber(), order.getBillFilePath());

        if ("quotation".equalsIgnoreCase(emailType)) {
            if (pdfBase64 == null || pdfBase64.isEmpty()) {
                throw new RuntimeException("Quotation PDF is required to send quotation email");
            }

            try {
                byte[] pdfBytes = decodePdfBase64(pdfBase64);
                emailService.sendQuotationEmail(order, pdfBytes, attachmentName);
                logger.info("Quotation email sent successfully for order {} to {}", order.getOrderNumber(), recipientEmail);
                logger.info("Finished quotation email workflow for order {}", order.getOrderNumber());
                return;
            } catch (Exception e) {
                logger.error("Failed to send quotation email for order {}: {}", order.getOrderNumber(), e.getMessage(), e);
                throw new RuntimeException("Failed to send quotation email: " + e.getMessage(), e);
            }
        }
        
        // Prefer the exact PDF generated on the frontend when provided
        String billFilePath = order.getBillFilePath();

        if (pdfBase64 != null && !pdfBase64.isEmpty()) {
            try {
                byte[] pdfBytes = decodePdfBase64(pdfBase64);
                billFilePath = saveBillPdfFromFrontend(order, pdfBytes);
                logger.info("Saved bill PDF from frontend for order {} at {}", order.getOrderNumber(), billFilePath);
            } catch (Exception e) {
                logger.error("Failed to save bill PDF from frontend for order {}: {}", order.getOrderNumber(), e.getMessage(), e);
            }
        }
        
        // First, try to find the bill from document service only when a frontend PDF was not supplied
        if (pdfBase64 == null || pdfBase64.isEmpty()) {
            try {
                List<Document> documents = documentRepository.findByOrderId(orderId);
                System.out.println("Found " + documents.size() + " documents for order " + orderId);
                
                // Look for a BILL document
                for (Document doc : documents) {
                    if ("BILL".equals(doc.getCategory())) {
                        logger.info("Found BILL document for order {} at {}", order.getOrderNumber(), doc.getFilePath());
                        billFilePath = doc.getFilePath();
                        break;
                    }
                }
            } catch (Exception e) {
                logger.error("Error finding bill documents for order {}: {}", order.getOrderNumber(), e.getMessage(), e);
            }
        }
        
        // If no bill file path from documents, try to generate the bill PDF
        if (billFilePath == null || billFilePath.isEmpty()) {
            logger.info("No bill file path found for order {}. Generating bill PDF.", order.getOrderNumber());
            try {
                billFilePath = billService.generateBillPdf(orderId);
                logger.info("Generated bill PDF for order {} at {}", order.getOrderNumber(), billFilePath);
                order.setBillFilePath(billFilePath);
                orderRepository.save(order);
                logger.info("Saved generated bill path for order {}", order.getOrderNumber());
            } catch (Exception e) {
                logger.error("Failed to generate bill PDF for order {}: {}", order.getOrderNumber(), e.getMessage(), e);
            }
        } else {
            logger.info("Using existing bill file path for order {}: {}", order.getOrderNumber(), billFilePath);
        }
        
        // Send bill email with or without PDF
        if (billFilePath != null && !billFilePath.isEmpty()) {
            try {
                String pdfPath = getFullFilePath(billFilePath);
                logger.info("Resolved PDF path for order {}: {}", order.getOrderNumber(), pdfPath);
                
                // Check if file exists
                File pdfFile = new File(pdfPath);
                logger.info("PDF file exists at resolved path for order {}: {}", order.getOrderNumber(), pdfFile.exists());
                
                // If file doesn't exist at converted path, try original path
                if (!pdfFile.exists()) {
                    File originalFile = new File(billFilePath);
                    logger.info("PDF file exists at original path for order {}: {}", order.getOrderNumber(), originalFile.exists());
                    if (originalFile.exists()) {
                        pdfPath = billFilePath;
                    }
                }
                
                emailService.sendBillEmail(order, pdfPath);
                logger.info("Bill email sent successfully for order {} to {}", order.getOrderNumber(), recipientEmail);
            } catch (Exception e) {
                logger.error("Failed to send bill email with PDF for order {}: {}", order.getOrderNumber(), e.getMessage(), e);
                // Try sending without PDF
                emailService.sendBillEmail(order, null);
                logger.info("Bill email sent without PDF for order {} to {}", order.getOrderNumber(), recipientEmail);
            }
        } else {
            // Send email without PDF if no bill file found
            logger.warn("No bill PDF available for order {}. Sending email without attachment.", order.getOrderNumber());
            emailService.sendBillEmail(order, null);
            logger.info("Bill email sent without PDF for order {} to {}", order.getOrderNumber(), recipientEmail);
        }
        logger.info("Finished quotation email workflow for order {}", order.getOrderNumber());
    }
    
    @Transactional
    public Order updatePaymentStatus(Long id, String paymentStatus, String paymentReference) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setPaymentStatus(paymentStatus);
        order.setPaymentReference(paymentReference);
        if ("PAID".equalsIgnoreCase(paymentStatus)) {
            applyPostPaymentOrderStatus(order);
        }
        return orderRepository.save(order);
    }

    private void applyPostPaymentOrderStatus(Order order) {
        if (isDoctorReportService(order)) {
            if ("COMPLETED".equals(order.getMedicalReportStatus())) {
                order.setStatus("COMPLETED");
                return;
            }

            if (order.getMedicalReportStatus() == null || order.getMedicalReportStatus().isBlank()) {
                order.setMedicalReportStatus("NOT_STARTED");
            }

            String currentStatus = order.getStatus();
            if (currentStatus == null
                    || currentStatus.isBlank()
                    || "PENDING".equals(currentStatus)
                    || "COMPLETED".equals(currentStatus)) {
                order.setStatus("SUBMITTED");
            }
            return;
        }

        String currentStatus = order.getStatus();
        if (currentStatus == null || currentStatus.isBlank() || "PENDING".equals(currentStatus)) {
            order.setStatus("COMPLETED");
        }
    }

    private boolean isDoctorReportService(Order order) {
        return order != null
                && ("PRESCRIPTION_ANALYSIS".equals(order.getServiceType())
                        || "SECOND_OPINION".equals(order.getServiceType()));
    }
    
    // Update total amount for an order (for accountant to modify bill amount)
    @Transactional
    public Order updateTotalAmount(Long id, BigDecimal totalAmount) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        System.out.println("BEFORE UPDATE - Order: " + order.getOrderNumber() + ", TotalAmount: " + order.getTotalAmount());
        order.setTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);
        System.out.println("AFTER UPDATE - Order: " + savedOrder.getOrderNumber() + ", TotalAmount: " + savedOrder.getTotalAmount());
        return savedOrder;
    }
    
    // Update userEmail for an order (for linking orders created by accountant to user's email)
    @Transactional
    public Order updateUserEmail(Long id, String userEmail) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        System.out.println("BEFORE UPDATE - Order: " + order.getOrderNumber() + ", UserEmail: " + order.getUserEmail());
        order.setUserEmail(userEmail);
        Order savedOrder = orderRepository.save(order);
        System.out.println("AFTER UPDATE - Order: " + savedOrder.getOrderNumber() + ", UserEmail: " + savedOrder.getUserEmail());
        return savedOrder;
    }
    
    @Transactional
    public void deleteOrder(Long id) {
        orderRepository.deleteById(id);
    }
    
    public Long countByStatus(String status) {
        return orderRepository.countByStatus(status);
    }
    
    @Transactional
    public Order assignDoctor(Long orderId, Long doctorId, String priority) {
        System.out.println("=== ASSIGN DOCTOR START ===");
        System.out.println("Order ID: " + orderId);
        System.out.println("Doctor ID: " + doctorId);
        System.out.println("Priority: " + priority);
        
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found with ID: " + orderId));
            System.out.println("Order found: " + order.getOrderNumber());
            System.out.println("Current status: " + order.getStatus());
            System.out.println("Current paymentStatus: " + order.getPaymentStatus());
            System.out.println("Current assigned doctor ID: " + (order.getAssignedDoctor() != null ? order.getAssignedDoctor().getId() : "None"));
            
            User doctor = userRepository.findById(doctorId)
                    .orElseThrow(() -> new RuntimeException("Doctor not found with ID: " + doctorId));
            System.out.println("Doctor found: " + doctor.getFullName() + " (ID: " + doctor.getId() + ")");
            
            // Set the assigned doctor
            order.setAssignedDoctor(doctor);
            // Set status to IN_REVIEW
            order.setStatus("IN_REVIEW");
            // Set priority (default to MEDIUM if not provided)
            if (priority != null && !priority.isEmpty()) {
                order.setPriority(priority.toUpperCase());
            } else {
                order.setPriority("MEDIUM");
            }
            
            // Save the order
            Order savedOrder = orderRepository.save(order);
            System.out.println("Order saved - ID: " + savedOrder.getId());
            System.out.println("Order saved with new status: " + savedOrder.getStatus());
            System.out.println("Order saved with assigned doctor ID: " + (savedOrder.getAssignedDoctor() != null ? savedOrder.getAssignedDoctor().getId() : "None"));
            System.out.println("Order saved with assigned doctor name: " + (savedOrder.getAssignedDoctor() != null ? savedOrder.getAssignedDoctor().getFullName() : "None"));
            System.out.println("Order saved with priority: " + savedOrder.getPriority());
            
            // Verify the saved order
            Order verifiedOrder = orderRepository.findById(orderId).orElse(null);
            if (verifiedOrder != null) {
                System.out.println("VERIFIED - Order status: " + verifiedOrder.getStatus());
                System.out.println("VERIFIED - Assigned doctor ID: " + (verifiedOrder.getAssignedDoctor() != null ? verifiedOrder.getAssignedDoctor().getId() : "None"));
                System.out.println("VERIFIED - Priority: " + verifiedOrder.getPriority());
            }
            
            // Send email notification to the doctor when assigned
            try {
                emailService.sendOrderAssignedToProfessionalEmail(savedOrder, doctor, "DOCTOR");
            } catch (Exception emailError) {
                System.out.println("Error sending email to doctor (non-critical): " + emailError.getMessage());
            }
            
            System.out.println("=== ASSIGN DOCTOR END ===");
            return savedOrder;
        } catch (Exception e) {
            System.out.println("=== ASSIGN DOCTOR ERROR ===");
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    @Transactional
    public Order assignAnalyst(Long orderId, Long analystId, String priority) {
        System.out.println("=== ASSIGN ANALYST START ===");
        System.out.println("Order ID: " + orderId);
        System.out.println("Analyst ID: " + analystId);
        System.out.println("Priority: " + priority);
        
        try {
            // First fetch the order without relations to avoid lazy loading issues
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found with ID: " + orderId));
            System.out.println("Order found: " + order.getOrderNumber());
            System.out.println("Current status: " + order.getStatus());
            System.out.println("Current paymentStatus: " + order.getPaymentStatus());
            System.out.println("Current assigned analyst ID: " + (order.getAssignedAnalyst() != null ? order.getAssignedAnalyst().getId() : "None"));
            
            // Verify order has a valid user before proceeding
            if (order.getUser() == null) {
                throw new RuntimeException("Order does not have a user assigned. Cannot assign analyst to an order without a valid user.");
            }
            
            // Fetch analyst from database
            User analyst = userRepository.findById(analystId)
                    .orElseThrow(() -> new RuntimeException("Analyst not found with ID: " + analystId));
            
            // Verify the user is actually an analyst
            if (!"ANALYST".equals(analyst.getRole())) {
                throw new RuntimeException("User with ID " + analystId + " is not an analyst. User role: " + analyst.getRole());
            }
            
            System.out.println("Analyst found: " + analyst.getFullName() + " (ID: " + analyst.getId() + ", Role: " + analyst.getRole() + ")");
            
            // Set the assigned analyst
            order.setAssignedAnalyst(analyst);
            // Set status to IN_REVIEW
            order.setStatus("IN_REVIEW");
            // Set priority (default to MEDIUM if not provided)
            if (priority != null && !priority.isEmpty()) {
                order.setPriority(priority.toUpperCase());
            } else {
                order.setPriority("MEDIUM");
            }
            
            // Save the order
            Order savedOrder = orderRepository.save(order);
            System.out.println("Order saved - ID: " + savedOrder.getId());
            System.out.println("Order saved with new status: " + savedOrder.getStatus());
            System.out.println("Order saved with assigned analyst ID: " + (savedOrder.getAssignedAnalyst() != null ? savedOrder.getAssignedAnalyst().getId() : "None"));
            System.out.println("Order saved with assigned analyst name: " + (savedOrder.getAssignedAnalyst() != null ? savedOrder.getAssignedAnalyst().getFullName() : "None"));
            System.out.println("Order saved with priority: " + savedOrder.getPriority());
            
            // Send email notification to the analyst when assigned (non-critical, don't fail if it errors)
            try {
                emailService.sendOrderAssignedToProfessionalEmail(savedOrder, analyst, "ANALYST");
            } catch (Exception emailError) {
                System.out.println("Error sending email to analyst (non-critical): " + emailError.getMessage());
            }
            
            System.out.println("=== ASSIGN ANALYST END ===");
            return savedOrder;
        } catch (Exception e) {
            System.out.println("=== ASSIGN ANALYST ERROR ===");
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            // Re-throw the exception so the controller can return proper error
            throw new RuntimeException("Failed to assign analyst: " + e.getMessage(), e);
        }
    }
    
    public List<Order> findByAssignedDoctorId(Long doctorId) {
        System.out.println("=== FIND ORDERS BY DOCTOR START ===");
        System.out.println("Doctor ID: " + doctorId);
        // Filter by SECOND_OPINION service type - doctors should only see second opinion orders
        List<Order> orders = orderRepository.findByAssignedDoctorIdAndServiceType(doctorId, "SECOND_OPINION");
        System.out.println("Found " + orders.size() + " SECOND_OPINION orders for doctor " + doctorId);
        
        for (Order o : orders) {
            System.out.println("Order: " + o.getOrderNumber() + 
                ", Status: " + o.getStatus() + 
                ", PaymentStatus: " + o.getPaymentStatus() + 
                ", ServiceType: " + o.getServiceType() + 
                ", MedicalReportStatus: " + o.getMedicalReportStatus() +
                ", AssignedDoctor: " + (o.getAssignedDoctor() != null ? o.getAssignedDoctor().getId() + "-" + o.getAssignedDoctor().getFullName() : "None") +
                ", Prescription: " + (o.getPrescription() != null ? o.getPrescription().getId() + "-" + o.getPrescription().getStatus() : "None"));
        }
        System.out.println("=== FIND ORDERS BY DOCTOR END ===");
        return orders;
    }
    
    // Find orders by assigned analyst - FILTERED to only return PRESCRIPTION_ANALYSIS orders
    // Analysts should only see prescription analysis orders, not second opinion or online pharmacy orders
    public List<Order> findByAssignedAnalystId(Long analystId) {
        System.out.println("=== FIND ORDERS BY ANALYST START ===");
        System.out.println("Analyst ID: " + analystId);
        // Filter by PRESCRIPTION_ANALYSIS service type - analysts should only see prescription analysis orders
        List<Order> orders = orderRepository.findByAssignedAnalystIdAndServiceType(analystId, "PRESCRIPTION_ANALYSIS");
        System.out.println("Found " + orders.size() + " PRESCRIPTION_ANALYSIS orders for analyst " + analystId);
        for (Order o : orders) {
            System.out.println("Order: " + o.getOrderNumber() + 
                ", Status: " + o.getStatus() + 
                ", PaymentStatus: " + o.getPaymentStatus() + 
                ", ServiceType: " + o.getServiceType() + 
                ", MedicalReportStatus: " + o.getMedicalReportStatus() +
                ", AssignedAnalyst: " + (o.getAssignedAnalyst() != null ? o.getAssignedAnalyst().getId() + "-" + o.getAssignedAnalyst().getFullName() : "None") +
                ", Prescription: " + (o.getPrescription() != null ? o.getPrescription().getId() + "-" + o.getPrescription().getStatus() : "None"));
        }
        System.out.println("=== FIND ORDERS BY ANALYST END ===");
        return orders;
    }
    
    public List<Order> findAllOrdersWithDetails() {
        // Return all orders including unpaid Online Pharmacy orders
        // Users should see their orders in inquiries page even before payment
        return orderRepository.findAllWithDetails();
    }
    
    public Optional<Order> findByIdWithDetails(Long id) {
        return orderRepository.findByIdWithDetails(id);
    }
    
    public Optional<Order> findByOrderNumberWithDetails(String orderNumber) {
        return orderRepository.findByOrderNumberWithDetails(orderNumber);
    }
    
    public List<Order> findByAssignedDoctorIdWithDetails(Long doctorId) {
        // Filter by SECOND_OPINION service type - doctors should only see second opinion orders
        return orderRepository.findByAssignedDoctorIdAndServiceType(doctorId, "SECOND_OPINION");
    }
    
    // Find orders by payment status (for admin to see paid orders only)
    public List<Order> findByPaymentStatus(String paymentStatus) {
        return orderRepository.findByPaymentStatus(paymentStatus);
    }
    
    // Find only ONLINE_PHARMACY orders with PAID payment status (for accountant view)
    // Accountant can only see orders that have been paid
    public List<Order> findOnlinePharmacyOrders() {
        return orderRepository.findByServiceTypeAndPaymentStatus("ONLINE_PHARMACY", "PAID");
    }
    
    @Transactional
    public Order submitMedicalReport(Long orderId, Long doctorId, String diagnosis, String recommendations, String prescriptionDetails, String notes,
            String chiefComplaints, String historyPoints, String examFindings, String consultationDate, String height, String weight, String lmp) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        
        // Create or update prescription
        Prescription prescription = order.getPrescription();
        if (prescription == null) {
            prescription = new Prescription();
            prescription.setUser(order.getUser());
            prescription.setDoctor(doctor);
            prescription.setStatus("COMPLETED");
        } else {
            prescription.setDoctor(doctor);
            prescription.setStatus("COMPLETED");
        }
        
        prescription.setDiagnosis(diagnosis);
        prescription.setRecommendations(recommendations);
        prescription.setPrescriptionDetails(prescriptionDetails);
        prescription.setNotes(notes);
        
        // Save new fields
        prescription.setChiefComplaints(chiefComplaints != null ? chiefComplaints : "");
        prescription.setHistoryPoints(historyPoints != null ? historyPoints : "");
        prescription.setExamFindings(examFindings != null ? examFindings : "");
        prescription.setConsultationDate(consultationDate != null ? consultationDate : "");
        prescription.setHeight(height != null ? height : "");
        prescription.setWeight(weight != null ? weight : "");
        prescription.setLmp(lmp != null ? lmp : "");
        prescription.setSuggestedInvestigations("");
        prescription.setSpecialInstructions("");
        
        // Copy serviceType from order to prescription
        // For doctors, always set to SECOND_OPINION regardless of order service type
        if (doctor != null && "DOCTOR".equals(doctor.getRole())) {
            prescription.setServiceType("SECOND_OPINION");
        } else if (order.getServiceType() != null) {
            prescription.setServiceType(order.getServiceType());
        }
        
        prescription = prescriptionRepository.save(prescription);
        
        // Generate and save the medical report
        String reportFilePath = null;
        try {
            reportFilePath = saveMedicalReportAsFile(order, prescription, doctor);
            order.setMedicalReportFilePath(reportFilePath);
            System.out.println("Medical report saved to: " + reportFilePath);
        } catch (Exception e) {
            System.err.println("Failed to save medical report file: " + e.getMessage());
            e.printStackTrace();
            // Continue without failing - the prescription data is still saved
        }
        
        // Update order with prescription and status
        order.setPrescription(prescription);
        order.setMedicalReportStatus("COMPLETED");
        order.setStatus("COMPLETED");
        
        Order savedOrder = orderRepository.save(order);
        System.out.println("Order saved with medical report file path: " + savedOrder.getMedicalReportFilePath());
        
        // Note: Email is NOT sent here - it's sent separately via sendPrescriptionEmailWithPDF
        // This avoids duplicate emails with different PDF formats
        
        return savedOrder;
    }
    
    // Save medical report as draft (doesn't change order status to COMPLETED)
    @Transactional
    public Order saveMedicalReportDraft(Long orderId, Long doctorId, String diagnosis, String recommendations, String prescriptionDetails, String notes, 
            String chiefComplaints, String historyPoints, String examFindings, String consultationDate, String height, String weight, String lmp, String analysisNotes) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        
        // Create or update prescription as DRAFT
        Prescription prescription = order.getPrescription();
        if (prescription == null) {
            prescription = new Prescription();
            prescription.setUser(order.getUser());
            prescription.setDoctor(doctor);
            prescription.setAnalyst(doctor); // Set analyst for draft
            prescription.setStatus("DRAFT");
        } else {
            prescription.setDoctor(doctor);
            prescription.setAnalyst(doctor); // Set analyst for draft
            prescription.setStatus("DRAFT");
        }
        
        // Handle null values - use empty string instead of null
        prescription.setDiagnosis(diagnosis != null ? diagnosis : "");
        prescription.setRecommendations(recommendations != null ? recommendations : "");
        prescription.setPrescriptionDetails(prescriptionDetails != null ? prescriptionDetails : "");
        prescription.setNotes(notes != null ? notes : "");
        
        // Save new fields
        prescription.setChiefComplaints(chiefComplaints != null ? chiefComplaints : "");
        prescription.setHistoryPoints(historyPoints != null ? historyPoints : "");
        prescription.setExamFindings(examFindings != null ? examFindings : "");
        prescription.setConsultationDate(consultationDate != null ? consultationDate : "");
        prescription.setHeight(height != null ? height : "");
        prescription.setWeight(weight != null ? weight : "");
        prescription.setLmp(lmp != null ? lmp : "");
        prescription.setAnalysisNotes(analysisNotes != null ? analysisNotes : "");
        prescription.setSuggestedInvestigations("");
        prescription.setSpecialInstructions("");
        
        // Copy serviceType from order to prescription
        // For doctors, always set to SECOND_OPINION regardless of order service type
        if (doctor != null && "DOCTOR".equals(doctor.getRole())) {
            prescription.setServiceType("SECOND_OPINION");
        } else {
            // For analysts and other users, use the order's service type
            prescription.setServiceType(order.getServiceType() != null ? order.getServiceType() : "PRESCRIPTION_ANALYSIS");
        }
        
        prescription = prescriptionRepository.save(prescription);
        
        // Update order with prescription and DRAFT status
        order.setPrescription(prescription);
        order.setMedicalReportStatus("DRAFT");
        // Keep order status as IN_REVIEW so it stays in pending list
        // Only medicalReportStatus should be DRAFT, not the order status
        
        Order savedOrder = orderRepository.save(order);
        System.out.println("Draft medical report saved for order: " + orderId);
        
        return savedOrder;
    }
    
    // Helper method to get full file path from relative path
    private String getFullFilePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        
        // Get the upload directory
        String baseDir = uploadDirectory;
        
        // Remove /uploads/documents/ prefix if present
        String fileName = relativePath.replace("/uploads/documents/", "");
        
        // Also handle case where prefix might have different slash direction
        fileName = fileName.replace("uploads/documents/", "");
        
        // Use File.separator to combine paths for cross-platform compatibility
        // But first, ensure we use consistent separators in the filename
        fileName = fileName.replace("/", File.separator);
        
        // Construct the full path
        String fullPath = baseDir + File.separator + fileName;
        
        logger.info("Converting relative path '{}' to full path '{}'", relativePath, fullPath);
        
        // Check if the file exists with this path
        File testFile = new File(fullPath);
        if (testFile.exists()) {
            return fullPath;
        }
        
        // Try alternative: use forward slashes consistently
        String altPath = baseDir + "/" + fileName.replace(File.separator, "/");
        File altFile = new File(altPath);
        if (altFile.exists()) {
            logger.info("File found with alternative path: {}", altPath);
            return altPath;
        }
        
        // Try another alternative: just use the filename
        String justFileName = fileName.contains(File.separator) ? 
            fileName.substring(fileName.lastIndexOf(File.separator) + 1) : fileName;
        String simplePath = baseDir + File.separator + justFileName;
        File simpleFile = new File(simplePath);
        if (simpleFile.exists()) {
            logger.info("File found with simple path: {}", simplePath);
            return simplePath;
        }
        
        logger.warn("File not found at any expected path. Returning original full path: {}", fullPath);
        return fullPath;
    }
    
    // Generate and save medical report as PDF file
    private String saveMedicalReportAsFile(Order order, Prescription prescription, User doctor) throws IOException {
        // Create reports directory for this order - use File.separator for cross-platform compatibility
        String orderFolder = "order_" + order.getId();
        String reportsSubfolder = "reports";
        
        // Use the upload directory path directly
        String fullPath = uploadDirectory + File.separator + orderFolder + File.separator + reportsSubfolder;
        Path reportsPath = Paths.get(fullPath);
        
        System.out.println("Creating report directory: " + reportsPath.toAbsolutePath());
        
        if (!Files.exists(reportsPath)) {
            Files.createDirectories(reportsPath);
            System.out.println("Created directory: " + reportsPath);
        }
        
        // Generate PDF for the report
        String fileName = "medical_report_" + order.getOrderNumber() + ".pdf";
        Path filePath = reportsPath.resolve(fileName);
        
        // Create PDF document
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        
        // Get data
        String companyName = "RXIncredible";
        String companyTagline = "Your Trusted Online Pharmacy & Healthcare Partner";
        String companyAddress = "234 Shree Nagar, Nagpur-15";
        String companyPhone = "9822848689";
        
        String patientName = order.getUser() != null ? order.getUser().getFullName() : "N/A";
        String patientAge = order.getUser() != null ? String.valueOf(order.getUser().getAge()) : "N/A";
        String patientGender = order.getUser() != null ? order.getUser().getGender() : "N/A";
        String patientPhone = order.getUser() != null ? order.getUser().getPhone() : "N/A";
        String patientAddress = order.getUser() != null ? order.getUser().getAddress() : "N/A";
        
        String doctorName = doctor.getFullName() != null ? doctor.getFullName() : "N/A";
        String doctorSpecialization = doctor.getSpecialization() != null ? doctor.getSpecialization() : "General Physician";
        String doctorLicense = doctor.getLicenseNumber() != null ? doctor.getLicenseNumber() : "N/A";
        
        String diagnosis = prescription.getDiagnosis() != null ? prescription.getDiagnosis() : "No diagnosis provided";
        String recommendations = prescription.getRecommendations() != null ? prescription.getRecommendations() : "No recommendations provided";
        String prescriptionDetails = prescription.getPrescriptionDetails() != null ? prescription.getPrescriptionDetails() : "No prescription provided";
        String notes = prescription.getNotes() != null ? prescription.getNotes() : "";
        
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
        
        // Fonts
        PDType1Font boldFont = PDType1Font.HELVETICA_BOLD;
        PDType1Font regularFont = PDType1Font.HELVETICA;
        
        float yPosition = 800;
        float margin = 50;
        float pageWidth = page.getMediaBox().getWidth();
        float contentWidth = pageWidth - (2 * margin);
        
        // Write content to PDF
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        
        // Header - Company Name (Blue)
        contentStream.setNonStrokingColor(30, 58, 138); // #1e3a8a
        contentStream.beginText();
        contentStream.setFont(boldFont, 24);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText(companyName);
        contentStream.endText();
        
        yPosition -= 25;
        contentStream.setNonStrokingColor(100, 116, 139); // gray
        contentStream.beginText();
        contentStream.setFont(regularFont, 12);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText(companyTagline);
        contentStream.endText();
        
        yPosition -= 20;
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText(companyAddress + " | Phone: " + companyPhone);
        contentStream.endText();
        
        // Horizontal line
        yPosition -= 15;
        contentStream.setStrokingColor(30, 58, 138);
        contentStream.setLineWidth(2);
        contentStream.moveTo(margin, yPosition);
        contentStream.lineTo(pageWidth - margin, yPosition);
        contentStream.stroke();
        
        // Order Info
        yPosition -= 25;
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(boldFont, 14);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText("Medical Report");
        contentStream.endText();
        
        yPosition -= 20;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText("Order #: " + order.getOrderNumber() + " | Service: " + order.getServiceType() + " | Date: " + currentDate);
        contentStream.endText();
        
        // Patient Info Box
        yPosition -= 25;
        contentStream.setNonStrokingColor(241, 245, 249); // light gray background
        contentStream.addRect(margin, yPosition - 60, contentWidth, 70);
        contentStream.fill();
        
        yPosition -= 15;
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(boldFont, 12);
        contentStream.newLineAtOffset(margin + 10, yPosition);
        contentStream.showText("Patient Information");
        contentStream.endText();
        
        yPosition -= 18;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin + 10, yPosition);
        contentStream.showText("Name: " + patientName + " | Age: " + patientAge + " | Gender: " + patientGender);
        contentStream.endText();
        
        yPosition -= 14;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPosition);
        contentStream.showText("Phone: " + patientPhone + " | Address: " + (patientAddress != null ? patientAddress : "N/A"));
        contentStream.endText();
        
        // Diagnosis Section
        yPosition -= 40;
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(boldFont, 12);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText("Diagnosis");
        contentStream.endText();
        
        yPosition -= 15;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin, yPosition);
        
        // Wrap text for diagnosis
        String wrappedDiagnosis = wrapText(diagnosis, 80);
        contentStream.showText(wrappedDiagnosis);
        contentStream.endText();
        
        // Calculate y position after diagnosis
        String[] diagLines = wrappedDiagnosis.split("\n");
        yPosition -= (diagLines.length * 12);
        
        // Recommendations Section
        yPosition -= 20;
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(boldFont, 12);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText("Recommendations");
        contentStream.endText();
        
        yPosition -= 15;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin, yPosition);
        
        String wrappedRecs = wrapText(recommendations, 80);
        contentStream.showText(wrappedRecs);
        contentStream.endText();
        
        String[] recLines = wrappedRecs.split("\n");
        yPosition -= (recLines.length * 12);
        
        // Prescription Section
        yPosition -= 20;
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(boldFont, 12);
        contentStream.newLineAtOffset(margin, yPosition);
        contentStream.showText("Prescription");
        contentStream.endText();
        
        yPosition -= 15;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin, yPosition);
        
        String wrappedPresc = wrapText(prescriptionDetails, 80);
        contentStream.showText(wrappedPresc);
        contentStream.endText();
        
        String[] prescLines = wrappedPresc.split("\n");
        yPosition -= (prescLines.length * 12);
        
        // Additional Notes
        if (notes != null && !notes.isEmpty()) {
            yPosition -= 20;
            contentStream.setNonStrokingColor(30, 58, 138);
            contentStream.beginText();
            contentStream.setFont(boldFont, 12);
            contentStream.newLineAtOffset(margin, yPosition);
            contentStream.showText("Additional Notes");
            contentStream.endText();
            
            yPosition -= 15;
            contentStream.setNonStrokingColor(51, 65, 85);
            contentStream.beginText();
            contentStream.setFont(regularFont, 10);
            contentStream.newLineAtOffset(margin, yPosition);
            
            String wrappedNotes = wrapText(notes, 80);
            contentStream.showText(wrappedNotes);
            contentStream.endText();
            
            String[] noteLines = wrappedNotes.split("\n");
            yPosition -= (noteLines.length * 12);
        }
        
        // Doctor Info
        yPosition -= 30;
        contentStream.setNonStrokingColor(241, 245, 249);
        contentStream.addRect(margin, yPosition - 50, contentWidth, 60);
        contentStream.fill();
        
        yPosition -= 15;
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(boldFont, 12);
        contentStream.newLineAtOffset(margin + 10, yPosition);
        contentStream.showText("Dr. " + doctorName);
        contentStream.endText();
        
        yPosition -= 14;
        contentStream.setNonStrokingColor(100, 116, 139);
        contentStream.beginText();
        contentStream.setFont(regularFont, 10);
        contentStream.newLineAtOffset(margin + 10, yPosition);
        contentStream.showText(doctorSpecialization + " | License: " + doctorLicense);
        contentStream.endText();
        
        // Signature line
        yPosition -= 40;
        contentStream.setStrokingColor(100, 116, 139);
        contentStream.setLineWidth(1);
        contentStream.moveTo(pageWidth - margin - 150, yPosition);
        contentStream.lineTo(pageWidth - margin, yPosition);
        contentStream.stroke();
        
        contentStream.setNonStrokingColor(100, 116, 139);
        contentStream.beginText();
        contentStream.setFont(regularFont, 8);
        contentStream.newLineAtOffset(pageWidth - margin - 150, yPosition - 12);
        contentStream.showText("Doctor's Signature");
        contentStream.endText();
        
        // Footer
        contentStream.setNonStrokingColor(30, 58, 138);
        contentStream.beginText();
        contentStream.setFont(regularFont, 8);
        contentStream.newLineAtOffset(margin, 30);
        contentStream.showText(companyName + " - " + companyAddress + " | Phone: " + companyPhone);
        contentStream.endText();
        
        contentStream.beginText();
        contentStream.setFont(regularFont, 8);
        contentStream.newLineAtOffset(margin, 18);
        contentStream.showText("Report ID: " + order.getOrderNumber() + " | Generated: " + currentDate);
        contentStream.endText();
        
        // Close the content stream
        contentStream.close();
        
        // Save the PDF
        document.save(filePath.toFile());
        document.close();
        
        System.out.println("Saved PDF report to: " + filePath.toAbsolutePath());
        
        // Return the relative path for storage in database
        return "/uploads/documents/" + orderFolder + "/" + reportsSubfolder + "/" + fileName;
    }
    
    // Helper method to wrap text
    private String wrapText(String text, int maxChars) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        
        for (String word : words) {
            if (line.length() + word.length() + 1 > maxChars) {
                result.append(line.toString().trim()).append("\n");
                line = new StringBuilder();
            }
            line.append(word).append(" ");
        }
        result.append(line.toString().trim());
        return result.toString();
    }
    
    // Generate HTML content for medical report
    private String generateMedicalReportHtml(Order order, Prescription prescription, User doctor) {
        String companyName = "RXIncredible";
        String companyTagline = "Your Trusted Online Pharmacy & Healthcare Partner";
        String companyAddress = "234 Shree Nagar, Nagpur-15";
        String companyPhone = "9822848689";
        String companyEmail = "contact@rxincredible.com";
        String companyWebsite = "www.rxincredible.com";
        
        String patientName = order.getUser() != null ? order.getUser().getFullName() : "N/A";
        String patientAge = order.getUser() != null ? String.valueOf(order.getUser().getAge()) : "N/A";
        String patientGender = order.getUser() != null ? order.getUser().getGender() : "N/A";
        String patientEmail = order.getUser() != null ? order.getUser().getEmail() : "N/A";
        String patientPhone = order.getUser() != null ? order.getUser().getPhone() : "N/A";
        String patientAddress = order.getUser() != null ? order.getUser().getAddress() : "N/A";
        
        String doctorName = doctor.getFullName() != null ? doctor.getFullName() : "N/A";
        String doctorEmail = doctor.getEmail() != null ? doctor.getEmail() : "N/A";
        String doctorPhone = doctor.getPhone() != null ? doctor.getPhone() : "N/A";
        String doctorSpecialization = doctor.getSpecialization() != null ? doctor.getSpecialization() : "General Physician";
        String doctorLicense = doctor.getLicenseNumber() != null ? doctor.getLicenseNumber() : "N/A";
        
        String diagnosis = prescription.getDiagnosis() != null ? prescription.getDiagnosis() : "No diagnosis provided";
        String recommendations = prescription.getRecommendations() != null ? prescription.getRecommendations() : "No recommendations provided";
        String prescriptionDetails = prescription.getPrescriptionDetails() != null ? prescription.getPrescriptionDetails() : "No prescription provided";
        String notes = prescription.getNotes() != null ? prescription.getNotes() : "";
        
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
        
        return "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "  <title>Medical Report - " + companyName + "</title>\n" +
            "  <style>\n" +
            "    * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
            "    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f0f4f8; padding: 20px; }\n" +
            "    .container { max-width: 800px; margin: 0 auto; background: white; border-radius: 10px; box-shadow: 0 4px 15px rgba(0,0,0,0.1); overflow: hidden; }\n" +
            "    .header { background: linear-gradient(135deg, #1e3a8a 0%, #3b82f6 100%); color: white; padding: 25px 30px; }\n" +
            "    .company-name { font-size: 32px; font-weight: bold; margin-bottom: 5px; }\n" +
            "    .tagline { font-size: 14px; opacity: 0.9; margin-bottom: 10px; }\n" +
            "    .contact-info { font-size: 13px; opacity: 0.85; }\n" +
            "    .contact-info span { margin-right: 20px; }\n" +
            "    .content { padding: 25px 30px; }\n" +
            "    .section { margin-bottom: 20px; }\n" +
            "    .section-title { font-size: 16px; font-weight: 600; color: #1e3a8a; border-bottom: 2px solid #3b82f6; padding-bottom: 8px; margin-bottom: 12px; }\n" +
            "    .patient-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 15px 20px; margin-bottom: 20px; }\n" +
            "    .patient-row { display: flex; margin-bottom: 8px; flex-wrap: wrap; }\n" +
            "    .patient-row:last-child { margin-bottom: 0; }\n" +
            "    .label { font-weight: 600; color: #475569; min-width: 120px; }\n" +
            "    .value { color: #1e293b; }\n" +
            "    .diagnosis-box { background: #fffbeb; border-left: 4px solid #f59e0b; padding: 15px; border-radius: 0 8px 8px 0; margin-bottom: 15px; }\n" +
            "    .recommendations-box { background: #f0fdf4; border-left: 4px solid #22c55e; padding: 15px; border-radius: 0 8px 8px 0; margin-bottom: 15px; }\n" +
            "    .prescription-box { background: #fef2f2; border-left: 4px solid #ef4444; padding: 15px; border-radius: 0 8px 8px 0; }\n" +
            "    .rx-symbol { font-size: 28px; color: #dc2626; font-weight: bold; margin-bottom: 10px; }\n" +
            "    .content-text { line-height: 1.7; white-space: pre-wrap; color: #334155; }\n" +
            "    .doctor-info { background: linear-gradient(135deg, #f8fafc 0%, #e2e8f0 100%); border-radius: 8px; padding: 15px 20px; margin-top: 20px; }\n" +
            "    .doctor-name { font-size: 16px; font-weight: 600; color: #1e3a8a; }\n" +
            "    .doctor-details { font-size: 13px; color: #64748b; margin-top: 5px; }\n" +
            "    .signature { margin-top: 15px; text-align: right; }\n" +
            "    .signature-line { border-top: 1px solid #94a3b8; width: 200px; display: inline-block; padding-top: 5px; font-size: 12px; color: #64748b; }\n" +
            "    .footer { background: #1e3a8a; color: white; text-align: center; padding: 15px; font-size: 12px; }\n" +
            "    .status-badge { display: inline-block; background: #22c55e; color: white; padding: 5px 15px; border-radius: 20px; font-size: 12px; margin-top: 10px; }\n" +
            "    .order-info { background: #eff6ff; padding: 10px 15px; border-radius: 6px; margin-bottom: 15px; font-size: 13px; color: #1e3a8a; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"container\">\n" +
            "    <div class=\"header\">\n" +
            "      <div class=\"company-name\">" + companyName + "</div>\n" +
            "      <div class=\"tagline\">" + companyTagline + "</div>\n" +
            "      <div class=\"contact-info\">\n" +
            "        <span>📍 " + companyAddress + "</span>\n" +
            "        <span>📞 " + companyPhone + "</span>\n" +
            "      </div>\n" +
            "      <div class=\"status-badge\">✓ Report Submitted Successfully</div>\n" +
            "    </div>\n" +
            "\n" +
            "    <div class=\"content\">\n" +
            "      <div class=\"order-info\">\n" +
            "        <strong>Order #:</strong> " + order.getOrderNumber() + " &nbsp;|&nbsp; " +
            "        <strong>Service:</strong> " + order.getServiceType() + " &nbsp;|&nbsp; " +
            "        <strong>Date:</strong> " + currentDate + "\n" +
            "      </div>\n" +
            "\n" +
            "      <div class=\"patient-box\">\n" +
            "        <div class=\"patient-row\"><span class=\"label\">Patient Name:</span><span class=\"value\">" + patientName + "</span></div>\n" +
            "        <div class=\"patient-row\"><span class=\"label\">Age:</span><span class=\"value\">" + patientAge + " years</span></div>\n" +
            "        <div class=\"patient-row\"><span class=\"label\">Gender:</span><span class=\"value\">" + patientGender + "</span></div>\n" +
            "        <div class=\"patient-row\"><span class=\"label\">Phone:</span><span class=\"value\">" + patientPhone + "</span></div>\n" +
            "        <div class=\"patient-row\"><span class=\"label\">Email:</span><span class=\"value\">" + patientEmail + "</span></div>\n" +
            "        <div class=\"patient-row\"><span class=\"label\">Address:</span><span class=\"value\">" + patientAddress + "</span></div>\n" +
            "      </div>\n" +
            "\n" +
            "      <div class=\"section\">\n" +
            "        <div class=\"section-title\">📋 Diagnosis</div>\n" +
            "        <div class=\"diagnosis-box content-text\">" + escapeHtml(diagnosis) + "</div>\n" +
            "      </div>\n" +
            "\n" +
            "      <div class=\"section\">\n" +
            "        <div class=\"section-title\">💊 Recommendations</div>\n" +
            "        <div class=\"recommendations-box content-text\">" + escapeHtml(recommendations) + "</div>\n" +
            "      </div>\n" +
            "\n" +
            "      <div class=\"section\">\n" +
            "        <div class=\"section-title\">💉 Prescription</div>\n" +
            "        <div class=\"prescription-box\">\n" +
            "          <div class=\"rx-symbol\">℞</div>\n" +
            "          <div class=\"content-text\">" + escapeHtml(prescriptionDetails) + "</div>\n" +
            "        </div>\n" +
            "      </div>\n" +
            "\n" +
            "      <div class=\"section\">\n" +
            "        <div class=\"section-title\">📝 Additional Notes</div>\n" +
            "        <div class=\"content-text\">" + (notes.isEmpty() ? "No additional notes" : escapeHtml(notes)) + "</div>\n" +
            "      </div>\n" +
            "\n" +
            "      <div class=\"doctor-info\">\n" +
            "        <div class=\"doctor-name\">Dr. " + doctorName + "</div>\n" +
            "        <div class=\"doctor-details\">\n" +
            "          " + doctorSpecialization + " | License: " + doctorLicense + "<br>\n" +
            "          📧 " + doctorEmail + " | 📞 " + doctorPhone + "\n" +
            "        </div>\n" +
            "        <div class=\"signature\">\n" +
            "          <div class=\"signature-line\">Doctor's Signature</div>\n" +
            "        </div>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "\n" +
            "    <div class=\"footer\">\n" +
            "      <p>" + companyName + " - " + companyTagline + "</p>\n" +
            "      <p>📍 " + companyAddress + " | 📞 " + companyPhone + " | 🌐 " + companyWebsite + "</p>\n" +
            "      <p>Report ID: " + order.getOrderNumber() + " | Generated: " + currentDate + "</p>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "</body>\n" +
            "</html>";
    }
    
    // Helper method to escape HTML special characters
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
    
    // Get the medical report file path for an order
    public String getMedicalReportFilePath(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        return order.getMedicalReportFilePath();
    }
    
    // Get the upload directory path
    public String getUploadDirectory() {
        return uploadDirectory;
    }
    
    // Generate and save bill PDF for an order
    @Transactional
    public Order generateBill(Long orderId) throws IOException {
        Order order = orderRepository.findByIdWithUser(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        // Generate payment receipt for Prescription Analysis and Second Opinion services
        // These services are paid upfront and need a payment receipt
        if ("PRESCRIPTION_ANALYSIS".equals(order.getServiceType()) || "SECOND_OPINION".equals(order.getServiceType())) {
            System.out.println("Generating payment receipt for " + order.getServiceType() + " service");
            String receiptFilePath = billService.generatePaymentReceiptPdf(orderId);
            System.out.println("Payment receipt generated: " + receiptFilePath);
            order.setBillFilePath(receiptFilePath);
            // Also save to paymentReceiptPath for the new receipt system
            order.setPaymentReceiptPath(receiptFilePath);
            Order savedOrder = orderRepository.save(order);
            System.out.println("Order saved with payment receipt path: " + savedOrder.getBillFilePath());
            return savedOrder;
        }
        
        // If totalAmount is 0 or null, automatically calculate based on service type
        if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(BigDecimal.ZERO) == 0) {
            if (order.getServiceType() != null) {
                String category = getServiceCategory(order.getServiceType());
                if (category != null) {
                    List<MedicalService> services = medicalServiceRepository.findByCategory(category);
                    if (!services.isEmpty()) {
                        MedicalService service = services.stream()
                            .filter(MedicalService::getIsActive)
                            .findFirst()
                            .orElse(null);
                        if (service != null) {
                            order.setTotalAmount(service.getPrice());
                            System.out.println("Auto-set totalAmount to: " + service.getPrice() + " for service type: " + order.getServiceType());
                        }
                    }
                }
            }
            // If still 0 or null, set default to 0 for Online Pharmacy (others are blocked above)
            if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(BigDecimal.ZERO) == 0) {
                if ("ONLINE_PHARMACY".equals(order.getServiceType())) {
                    order.setTotalAmount(BigDecimal.ZERO);
                }
            }
            orderRepository.save(order);
        }
        
        // Use BillService to generate the PDF
        String billFilePath = billService.generateBillPdf(orderId);
        
        // Update order with bill file path
        order.setBillFilePath(billFilePath);
        
        Order savedOrder = orderRepository.save(order);
        
        // Send bill email to patient with PDF and payment/rejection links
        try {
            if (savedOrder.getBillFilePath() != null && !savedOrder.getBillFilePath().isEmpty()) {
                // Get the full file path for the PDF
                String pdfPath = getFullFilePath(savedOrder.getBillFilePath());
                System.out.println("Bill PDF full path: " + pdfPath);
                
                // Verify file exists
                File pdfFile = new File(pdfPath);
                if (!pdfFile.exists()) {
                    // Try original path
                    File originalFile = new File(savedOrder.getBillFilePath());
                    if (originalFile.exists()) {
                        pdfPath = savedOrder.getBillFilePath();
                        System.out.println("Using original bill path: " + pdfPath);
                    } else {
                        System.out.println("Warning: Bill PDF file does not exist!");
                    }
                }
                
                emailService.sendBillEmail(savedOrder, pdfPath);
                System.out.println("Bill email sent to patient: " + savedOrder.getUser().getEmail());
            } else {
                // Send email without attachment if PDF not available
                emailService.sendBillEmail(savedOrder, null);
                System.out.println("Bill email sent (without PDF) to patient: " + savedOrder.getUser().getEmail());
            }
        } catch (Exception e) {
            System.err.println("Failed to send bill email: " + e.getMessage());
            e.printStackTrace();
            // Don't fail the whole operation if email fails
        }
        
        return savedOrder;
    }
    
    // Get the bill file path for an order
    public String getBillFilePath(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        return order.getBillFilePath();
    }
    
    // Get the payment receipt file path for an order
    public String getPaymentReceiptPath(Long orderId) {
        Order order = orderRepository.findByIdWithUser(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        String storedReceiptPath = order.getPaymentReceiptPath();
        if (isStoredFileAvailable(storedReceiptPath)) {
            return storedReceiptPath;
        }

        String fallbackBillPath = order.getBillFilePath();
        if (isStoredFileAvailable(fallbackBillPath)) {
            if (storedReceiptPath == null || storedReceiptPath.isBlank()) {
                order.setPaymentReceiptPath(fallbackBillPath);
                orderRepository.save(order);
            }
            return fallbackBillPath;
        }

        if (isReceiptEligible(order)) {
            try {
                Order updatedOrder = generatePaymentReceipt(orderId);
                return updatedOrder.getPaymentReceiptPath();
            } catch (IOException e) {
                logger.error("Failed to auto-generate payment receipt for order {}", order.getOrderNumber(), e);
            }
        }

        return storedReceiptPath;
    }
    
    // Generate and save payment receipt for an order
    @Transactional
    public Order generatePaymentReceipt(Long orderId) throws IOException {
        Order order = orderRepository.findByIdWithUser(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        // Generate payment receipt PDF
        String receiptFilePath = billService.generatePaymentReceiptPdf(orderId);
        
        // Save to paymentReceiptPath field
        order.setPaymentReceiptPath(receiptFilePath);
        
        // Also save to billFilePath for backward compatibility
        order.setBillFilePath(receiptFilePath);
        
        Order savedOrder = orderRepository.save(order);
        System.out.println("Payment receipt generated and saved: " + savedOrder.getPaymentReceiptPath());
        
        return savedOrder;
    }

    private boolean isReceiptEligible(Order order) {
        if (order == null) {
            return false;
        }

        boolean eligibleService = "PRESCRIPTION_ANALYSIS".equals(order.getServiceType())
                || "SECOND_OPINION".equals(order.getServiceType());
        return eligibleService && "PAID".equalsIgnoreCase(order.getPaymentStatus());
    }

    private boolean isStoredFileAvailable(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }

        File directFile = new File(filePath);
        if (directFile.isAbsolute()) {
            return directFile.exists();
        }

        Path resolvedPath = Paths.get(uploadDirectory, filePath
                .replace("/uploads/documents/", "")
                .replace("uploads/documents/", "")
                .replace("/", File.separator)
                .replace("\\", File.separator));
        return Files.exists(resolvedPath);
    }
    
    // Send prescription email with PDF - uses PDF from frontend
    public void sendPrescriptionEmailWithPDF(Long orderId, PrescriptionEmailRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        User user = order.getUser();
        if (user == null || user.getEmail() == null) {
            throw new RuntimeException("User or email not found for order " + orderId);
        }
        
        System.out.println("Sending prescription email to: " + user.getEmail());
        System.out.println("Patient: " + request.getPatientName());
        System.out.println("Doctor: " + request.getDoctorName());
        
        // Use PDF from frontend if available, otherwise generate
        String pdfFilePath = null;
        byte[] pdfBytes = null;
        if (request.getPdfBase64() != null && !request.getPdfBase64().isEmpty()) {
            try {
                pdfBytes = decodePdfBase64(request.getPdfBase64());
                pdfFilePath = savePdfFromFrontend(order, pdfBytes);
                System.out.println("Saved PDF from frontend at: " + pdfFilePath);
            } catch (Exception e) {
                System.err.println("Failed to save PDF from frontend: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // Fallback to generating PDF
            try {
                pdfFilePath = generatePrescriptionPDF(order, request);
                System.out.println("Generated PDF at: " + pdfFilePath);
            } catch (Exception e) {
                System.err.println("Failed to generate prescription PDF: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Send email with or without PDF
        try {
            // Create a temporary prescription object for the email
            Prescription prescription = new Prescription();
            prescription.setDiagnosis(request.getDiagnosis());
            prescription.setRecommendations(request.getInvestigations());
            prescription.setPrescriptionDetails(request.getMedicines());
            prescription.setNotes(request.getSpecialInstructions());
            prescription.setChiefComplaints(request.getChiefComplaints());
            prescription.setHistoryPoints(request.getHistoryPoints());
            prescription.setExamFindings(request.getExamFindings());
            prescription.setSuggestedInvestigations(request.getSuggestedInvestigations());
            
            // Create a temporary order with prescription for email
            Order orderWithPrescription = new Order();
            orderWithPrescription.setId(order.getId());
            orderWithPrescription.setOrderNumber(order.getOrderNumber());
            orderWithPrescription.setServiceType(order.getServiceType());
            orderWithPrescription.setStatus(order.getStatus());
            orderWithPrescription.setUser(user);
            orderWithPrescription.setPrescription(prescription);
            
            // Create doctor user object
            User doctor = new User();
            doctor.setFullName(request.getDoctorName());
            doctor.setQualifications(request.getDoctorQualification());
            doctor.setLicenseNumber(request.getDoctorRegistrationNumber());
            doctor.setAddress(request.getDoctorAddress());
            doctor.setPhone(request.getDoctorContact());
            doctor.setEmail(request.getDoctorEmail());
            orderWithPrescription.setAssignedDoctor(doctor);
            
            if (pdfBytes != null && pdfBytes.length > 0) {
                emailService.sendPrescriptionEmail(
                        orderWithPrescription,
                        pdfBytes,
                        "Prescription_" + order.getOrderNumber() + ".pdf");
            } else if (pdfFilePath != null && !pdfFilePath.isEmpty()) {
                emailService.sendPrescriptionEmail(orderWithPrescription, pdfFilePath);
            } else {
                emailService.sendPrescriptionEmail(orderWithPrescription, null);
            }
            System.out.println("Prescription email sent successfully to: " + user.getEmail());
        } catch (Exception e) {
            System.err.println("Failed to send prescription email: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to send prescription email: " + e.getMessage());
        }
    }
    
    // Save PDF from frontend to server
    private String saveBillPdfFromFrontend(Order order, byte[] pdfBytes) throws IOException {
        String orderFolder = "order_" + order.getId();
        String billsSubfolder = "bills";

        String fullPath = uploadDirectory + File.separator + orderFolder + File.separator + billsSubfolder;
        Path billsPath = Paths.get(fullPath);

        if (!Files.exists(billsPath)) {
            Files.createDirectories(billsPath);
        }

        String fileName = "bill_" + order.getOrderNumber() + ".pdf";
        Path filePath = billsPath.resolve(fileName);
        Files.write(filePath, pdfBytes);

        String billPath = "/uploads/documents/" + orderFolder + "/" + billsSubfolder + "/" + fileName;
        order.setBillFilePath(billPath);
        orderRepository.save(order);

        System.out.println("Saved bill path to order: " + billPath);
        return filePath.toAbsolutePath().toString();
    }

    // Save PDF from frontend to server
    private String savePdfFromFrontend(Order order, byte[] pdfBytes) throws IOException {
        // Create prescriptions directory
        String orderFolder = "order_" + order.getId();
        String prescriptionsSubfolder = "prescriptions";
        
        String fullPath = uploadDirectory + File.separator + orderFolder + File.separator + prescriptionsSubfolder;
        Path prescriptionsPath = Paths.get(fullPath);
        
        if (!Files.exists(prescriptionsPath)) {
            Files.createDirectories(prescriptionsPath);
        }
        
        String fileName = "prescription_" + order.getOrderNumber() + ".pdf";
        Path filePath = prescriptionsPath.resolve(fileName);
        
        // Write PDF bytes to file
        Files.write(filePath, pdfBytes);
        
        System.out.println("Saved PDF from frontend to: " + filePath.toAbsolutePath());
        
        // Save the prescription path to order
        String prescriptionPath = "/uploads/documents/" + orderFolder + "/" + prescriptionsSubfolder + "/" + fileName;
        order.setPrescriptionPath(prescriptionPath);
        orderRepository.save(order);
        System.out.println("Saved prescription path to order: " + prescriptionPath);
        
        return filePath.toAbsolutePath().toString();
    }

    private byte[] decodePdfBase64(String base64Data) {
        String normalizedBase64 = base64Data;
        if (normalizedBase64.contains(",")) {
            normalizedBase64 = normalizedBase64.substring(normalizedBase64.indexOf(',') + 1);
        }
        return Base64.getDecoder().decode(normalizedBase64.trim());
    }
    
    // Get prescription PDF path for an order
    public String getPrescriptionPath(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null) {
            return order.getPrescriptionPath();
        }
        return null;
    }
    
    // Reset payment status for orders incorrectly marked as PAID
    // Orders with PAID status but NO paymentReference should be reset to PENDING
    @Transactional
    public int resetIncorrectPaymentStatus() {
        List<Order> allOrders = orderRepository.findAll();
        int count = 0;
        
        for (Order order : allOrders) {
            // If paymentStatus is PAID but there's no real paymentReference, it's incorrect
            if ("PAID".equals(order.getPaymentStatus())) {
                String paymentReference = order.getPaymentReference();
                boolean hasRealPaymentReference = paymentReference != null
                        && !paymentReference.isEmpty()
                        && !paymentReference.startsWith("SUBMITTED-");

                if (!hasRealPaymentReference) {
                    order.setPaymentStatus("PENDING");
                    order.setPaymentReference(null);
                    orderRepository.save(order);
                    count++;
                    System.out.println("=== Reset payment status for order " + order.getOrderNumber() + ": PAID -> PENDING ===");
                }
            }
        }
        
        System.out.println("=== Total orders reset: " + count + " ===");
        return count;
    }
    
    // Reset payment status for a specific order
    @Transactional
    public Order resetOrderPaymentStatus(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        // If paymentStatus is PAID but there's no real paymentReference, it's incorrect
        if ("PAID".equals(order.getPaymentStatus())) {
            String paymentReference = order.getPaymentReference();
            boolean hasRealPaymentReference = paymentReference != null
                    && !paymentReference.isEmpty()
                    && !paymentReference.startsWith("SUBMITTED-");

            if (!hasRealPaymentReference) {
                order.setPaymentStatus("PENDING");
                order.setPaymentReference(null);
                order = orderRepository.save(order);
                System.out.println("=== Reset payment status for order " + order.getOrderNumber() + ": PAID -> PENDING ===");
            }
        }
        
        return order;
    }
    
    // Fix orders with invalid user email (patient@rxincredible.com)
    // This is a utility method to fix test orders
    @Transactional
    public List<Order> fixOrdersWithInvalidUserEmail(String invalidEmail, Long validUserId) {
        List<Order> allOrders = orderRepository.findAll();
        User validUser = userRepository.findById(validUserId).orElse(null);
        int fixedCount = 0;
        
        if (validUser == null) {
            throw new RuntimeException("Valid user not found with ID: " + validUserId);
        }
        
        for (Order order : allOrders) {
            if (order.getUser() != null && invalidEmail.equals(order.getUser().getEmail())) {
                System.out.println("=== Fixing order " + order.getOrderNumber() + ": changing user from " 
                    + invalidEmail + " to user ID " + validUserId + " ===");
                order.setUser(validUser);
                orderRepository.save(order);
                fixedCount++;
            }
        }
        
        System.out.println("=== Total orders fixed: " + fixedCount + " ===");
        return orderRepository.findAll();
    }
    
    // Get all orders with their user emails (for debugging which orders have invalid emails)
    public List<Order> getAllOrdersWithUserEmails() {
        return orderRepository.findAll();
    }
    
    // Update priority for an order
    @Transactional
    public Order updatePriority(Long orderId, String priority) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with ID: " + orderId));
        
        // Validate priority value
        if (priority != null && !priority.isEmpty()) {
            String upperPriority = priority.toUpperCase();
            if (!"HIGH".equals(upperPriority) && !"MEDIUM".equals(upperPriority) && !"LOW".equals(upperPriority)) {
                throw new RuntimeException("Invalid priority value. Must be HIGH, MEDIUM, or LOW");
            }
            order.setPriority(upperPriority);
        } else {
            order.setPriority("MEDIUM");
        }
        
        Order savedOrder = orderRepository.save(order);
        System.out.println("Priority updated for order " + savedOrder.getOrderNumber() + ": " + savedOrder.getPriority());
        return savedOrder;
    }
    
    // Generate prescription PDF from form data
    private String generatePrescriptionPDF(Order order, PrescriptionEmailRequest request) throws IOException {
        // Create prescriptions directory
        String orderFolder = "order_" + order.getId();
        String prescriptionsSubfolder = "prescriptions";
        
        String fullPath = uploadDirectory + File.separator + orderFolder + File.separator + prescriptionsSubfolder;
        Path prescriptionsPath = Paths.get(fullPath);
        
        if (!Files.exists(prescriptionsPath)) {
            Files.createDirectories(prescriptionsPath);
        }
        
        String fileName = "prescription_" + order.getOrderNumber() + ".pdf";
        Path filePath = prescriptionsPath.resolve(fileName);
        
        // Create PDF document using PDFBox
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        float margin = 50;
        float yPos = pageHeight - margin;
        
        // Colors
        int[] primaryColor = {15, 108, 123}; // Teal
        int[] blackColor = {0, 0, 0};
        
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        
        // Header - Company Name
        contentStream.setNonStrokingColor(primaryColor[0], primaryColor[1], primaryColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 24);
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText("rxincredible");
        contentStream.endText();
        
        yPos -= 20;
        
        // Doctor Info
        contentStream.setNonStrokingColor(blackColor[0], blackColor[1], blackColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 14);
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText(request.getDoctorName() != null ? request.getDoctorName().toUpperCase() : "");
        contentStream.endText();
        
        yPos -= 12;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText(request.getDoctorQualification() != null ? request.getDoctorQualification() : "");
        contentStream.endText();
        
        yPos -= 8;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText("REG. NO: " + (request.getDoctorRegistrationNumber() != null ? request.getDoctorRegistrationNumber() : ""));
        contentStream.endText();
        
        yPos -= 7;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText(request.getDoctorAddress() != null ? request.getDoctorAddress() : "");
        contentStream.endText();
        
        yPos -= 7;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText(request.getDoctorContact() != null ? request.getDoctorContact() : "");
        contentStream.endText();
        
        yPos -= 7;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText(request.getDoctorEmail() != null ? request.getDoctorEmail() : "");
        contentStream.endText();
        
        // Divider line
        yPos -= 15;
        contentStream.setStrokingColor(primaryColor[0], primaryColor[1], primaryColor[2]);
        contentStream.setLineWidth(0.5f);
        contentStream.moveTo(margin, yPos);
        contentStream.lineTo(pageWidth - margin, yPos);
        contentStream.stroke();
        
        yPos -= 15;
        
        // Patient Info - Left Column
        float leftCol = margin;
        float rightCol = margin + 95;
        
        // Name
        contentStream.setNonStrokingColor(blackColor[0], blackColor[1], blackColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("Name of Patient:");
        contentStream.endText();
        
        yPos -= 12;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText(request.getPatientName() != null ? request.getPatientName() : "");
        contentStream.endText();
        
        // Date
        yPos -= 15;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("Date of Consultation:");
        contentStream.endText();
        
        yPos -= 12;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText(request.getConsultationDate() != null ? request.getConsultationDate() : "");
        contentStream.endText();
        
        // Address
        yPos -= 15;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("Address:");
        contentStream.endText();
        
        yPos -= 12;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(leftCol, yPos);
        String address = request.getAddress() != null ? request.getAddress() : "";
        contentStream.showText(address.length() > 40 ? address.substring(0, 40) : address);
        contentStream.endText();
        
        // Right Column - Age, Gender
        float rightY = yPos + 24;
        
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
        contentStream.newLineAtOffset(rightCol, rightY);
        contentStream.showText("Age:");
        contentStream.endText();
        
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
        contentStream.newLineAtOffset(rightCol + 25, rightY);
        contentStream.showText(request.getAge() != null ? request.getAge() : "");
        contentStream.endText();
        
        rightY -= 12;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
        contentStream.newLineAtOffset(rightCol, rightY);
        contentStream.showText("Gender:");
        contentStream.endText();
        
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
        contentStream.newLineAtOffset(rightCol + 35, rightY);
        contentStream.showText(request.getGender() != null ? request.getGender() : "");
        contentStream.endText();
        
        // Divider line
        yPos -= 20;
        contentStream.setStrokingColor(primaryColor[0], primaryColor[1], primaryColor[2]);
        contentStream.setLineWidth(0.3f);
        contentStream.moveTo(margin, yPos);
        contentStream.lineTo(pageWidth - margin, yPos);
        contentStream.stroke();
        
        yPos -= 15;
        
        // Two Column Layout
        float colWidth = 90;
        
        // LEFT COLUMN
        // Chief Complaints
        contentStream.setNonStrokingColor(blackColor[0], blackColor[1], blackColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("CHIEF COMPLAINTS");
        contentStream.endText();
        
        yPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(leftCol, yPos);
        String complaints = request.getChiefComplaints() != null ? request.getChiefComplaints() : "-";
        contentStream.showText(complaints.length() > 200 ? complaints.substring(0, 200) : complaints);
        contentStream.endText();
        
        yPos -= 25;
        
        // History Points
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("RELEVANT POINTS FROM HISTORY");
        contentStream.endText();
        
        yPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(leftCol, yPos);
        String history = request.getHistoryPoints() != null ? request.getHistoryPoints() : "-";
        contentStream.showText(history.length() > 200 ? history.substring(0, 200) : history);
        contentStream.endText();
        
        yPos -= 25;
        
        // Exam Findings
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("EXAMINATION / LAB FINDINGS");
        contentStream.endText();
        
        yPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(leftCol, yPos);
        String exam = request.getExamFindings() != null ? request.getExamFindings() : "-";
        contentStream.showText(exam.length() > 200 ? exam.substring(0, 200) : exam);
        contentStream.endText();
        
        yPos -= 25;
        
        // Investigations
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(leftCol, yPos);
        contentStream.showText("SUGGESTED INVESTIGATIONS");
        contentStream.endText();
        
        yPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(leftCol, yPos);
        String investigations = request.getInvestigations() != null ? request.getInvestigations() : "-";
        contentStream.showText(investigations.length() > 200 ? investigations.substring(0, 200) : investigations);
        contentStream.endText();
        
        // RIGHT COLUMN
        // Reset yPos for right column
        float rightYPos = yPos + 110;
        
        // Diagnosis
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(rightCol, rightYPos);
        contentStream.showText("DIAGNOSIS");
        contentStream.endText();
        
        rightYPos -= 12;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(rightCol, rightYPos);
        String diagnosis = request.getDiagnosis() != null ? request.getDiagnosis() : "-";
        contentStream.showText(diagnosis.length() > 200 ? diagnosis.substring(0, 200) : diagnosis);
        contentStream.endText();
        
        rightYPos -= 25;
        
        // Rx Symbol
        contentStream.setNonStrokingColor(primaryColor[0], primaryColor[1], primaryColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 28);
        contentStream.newLineAtOffset(rightCol, rightYPos);
        contentStream.showText("Rx");
        contentStream.endText();
        
        rightYPos -= 15;
        
        // Medicines
        contentStream.setNonStrokingColor(blackColor[0], blackColor[1], blackColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(rightCol, rightYPos);
        contentStream.showText("PRESCRIPTION");
        contentStream.endText();
        
        rightYPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(rightCol, rightYPos);
        String medicines = request.getMedicines() != null ? request.getMedicines() : "-";
        contentStream.showText(medicines.length() > 300 ? medicines.substring(0, 300) : medicines);
        contentStream.endText();
        
        // Divider line
        yPos -= 20;
        contentStream.setStrokingColor(primaryColor[0], primaryColor[1], primaryColor[2]);
        contentStream.setLineWidth(0.3f);
        contentStream.moveTo(margin, yPos);
        contentStream.lineTo(pageWidth - margin, yPos);
        contentStream.stroke();
        
        yPos -= 15;
        
        // Special Instructions
        contentStream.setNonStrokingColor(blackColor[0], blackColor[1], blackColor[2]);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
        contentStream.newLineAtOffset(margin, yPos);
        contentStream.showText("SPECIAL INSTRUCTIONS");
        contentStream.endText();
        
        yPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
        contentStream.newLineAtOffset(margin, yPos);
        String instructions = request.getSpecialInstructions() != null ? request.getSpecialInstructions() : "-";
        contentStream.showText(instructions.length() > 300 ? instructions.substring(0, 300) : instructions);
        contentStream.endText();
        
        // Doctor Signature
        yPos -= 40;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ITALIC, 10);
        contentStream.newLineAtOffset(pageWidth - margin - 100, yPos);
        contentStream.showText("RMP's Signature & Stamp");
        contentStream.endText();
        
        yPos -= 10;
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_BOLD, 9);
        contentStream.newLineAtOffset(pageWidth - margin - 50, yPos);
        contentStream.showText(request.getDoctorName() != null ? request.getDoctorName().toUpperCase() : "");
        contentStream.endText();
        
        // Footer
        contentStream.setNonStrokingColor(100, 100, 100);
        contentStream.beginText();
        contentStream.setFont(PDType1Font.TIMES_ITALIC, 8);
        contentStream.newLineAtOffset(margin, 30);
        contentStream.showText("Note: This prescription is generated on RxIncredible.");
        contentStream.endText();
        
        // Close content stream
        contentStream.close();
        
        // Save PDF
        document.save(filePath.toFile());
        document.close();
        
        System.out.println("Prescription PDF saved to: " + filePath.toAbsolutePath());
        
        return filePath.toAbsolutePath().toString();
    }
}
