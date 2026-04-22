package com.rxincredible.config;

import com.rxincredible.entity.User;
import com.rxincredible.entity.MedicalService;
import com.rxincredible.entity.Order;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.repository.MedicalServiceRepository;
import com.rxincredible.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.init.default-users:true}")
    private boolean initializeDefaultUsers;

    @Bean
    CommandLineRunner initDatabase(UserRepository userRepository, MedicalServiceRepository serviceRepository,
            OrderRepository orderRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            // Only create default users if enabled (can be disabled via env var)
            if (!initializeDefaultUsers) {
                log.info("Default user initialization is disabled");
                return;
            }

            log.info("Starting default user initialization");

            // Create default admin user if not exists
            if (!userRepository.findByEmail("admin@rxincredible.com").isPresent()) {
                User admin = new User();
                admin.setEmail("admin@rxincredible.com");
                // Password should be set via environment variable or changed immediately after
                // first login
                admin.setPassword(passwordEncoder.encode(
                        System.getenv("DEFAULT_ADMIN_PASSWORD") != null ? System.getenv("DEFAULT_ADMIN_PASSWORD")
                                : "Admin123!"));
                admin.setFullName("System Administrator");
                admin.setRole("ADMIN");
                admin.setPhone("+1234567890");
                admin.setAddress("123 Admin Street, Medical City");
                admin.setIsActive(true);
                admin.setIsVerified(true);
                userRepository.save(admin);
                log.info("Default Admin User Created");
            }

            // Create default doctor user if not exists
            if (!userRepository.findByEmail("doctor@rxincredible.com").isPresent()) {
                User doctor = new User();
                doctor.setEmail("doctor@rxincredible.com");
                doctor.setPassword(passwordEncoder.encode(
                        System.getenv("DEFAULT_DOCTOR_PASSWORD") != null ? System.getenv("DEFAULT_DOCTOR_PASSWORD")
                                : "Doctor123!"));
                doctor.setFullName("Dr. John Smith");
                doctor.setRole("DOCTOR");
                doctor.setPhone("+1234567891");
                doctor.setAddress("456 Medical Ave");
                doctor.setIsActive(true);
                doctor.setIsVerified(true);
                doctor.setSpecialization("General Medicine");
                doctor.setQualifications("MBBS, MD");
                doctor.setLicenseNumber("MD-12345");
                doctor.setGender("Male");
                doctor.setAge(35);
                doctor.setExperienceYears(10);
                userRepository.save(doctor);
                log.info("Default Doctor User Created");
            } else {
                userRepository.findByEmail("doctor@rxincredible.com").ifPresent(doctor -> {
                    boolean updated = false;

                    if (doctor.getGender() == null || doctor.getGender().isBlank()
                            || "NotSpecified".equalsIgnoreCase(doctor.getGender())) {
                        doctor.setGender("Male");
                        updated = true;
                    }

                    if (doctor.getAge() == null || doctor.getAge() <= 0) {
                        doctor.setAge(35);
                        updated = true;
                    }

                    if (updated) {
                        userRepository.save(doctor);
                        log.info("Default Doctor User Updated With Missing Age/Gender");
                    }
                });
            }

            // Create default accountant user if not exists
            if (!userRepository.findByEmail("accountant@rxincredible.com").isPresent()) {
                User accountant = new User();
                accountant.setEmail("accountant@rxincredible.com");
                accountant.setPassword(passwordEncoder.encode(System.getenv("DEFAULT_ACCOUNTANT_PASSWORD") != null
                        ? System.getenv("DEFAULT_ACCOUNTANT_PASSWORD")
                        : "accountant123!"));
                accountant.setFullName("Jane Accountant");
                accountant.setRole("ACCOUNTANT");
                accountant.setPhone("+1234567892");
                accountant.setAddress("789 Finance Rd");
                accountant.setIsActive(true);
                accountant.setIsVerified(true);
                userRepository.save(accountant);
                log.info("Default Accountant User Created");
            }
            
            // Create default analyst user if not exists
            if (!userRepository.findByEmail("analyst@rxincredible.com").isPresent()) {
                User analyst = new User();
                analyst.setEmail("analyst@rxincredible.com");
                analyst.setPassword(passwordEncoder.encode(System.getenv("DEFAULT_ANALYST_PASSWORD") != null
                        ? System.getenv("DEFAULT_ANALYST_PASSWORD")
                        : "analyst123!"));
                analyst.setFullName("Alex Analyst");
                analyst.setRole("ANALYST");
                analyst.setPhone("+1234567893");
                analyst.setAddress("101 Analysis Ave");
                analyst.setIsActive(true);
                analyst.setIsVerified(true);
                userRepository.save(analyst);
                log.info("Default Analyst User Created");
            }

            log.info("Default Users Initialization Complete");

            // Initialize default medical services - delete old ones and recreate with
            // correct prices
            // First delete all existing services to ensure correct prices
            serviceRepository.deleteAll();
            List<MedicalService> services = List.of(
                    createService("Prescription Analysis",
                            "Upload your prescription and get detailed analysis from expert doctors",
                            new BigDecimal("500.00"), "prescription"),
                    createService("Second Opinion",
                            "Get a second medical opinion from certified healthcare professionals",
                            new BigDecimal("5000.00"), "consultation"),
                    createService("Online Pharmacy", "Order medicines online with instant quotations",
                            new BigDecimal("0.00"), "pharmacy"));
            serviceRepository.saveAll(services);
            System.out.println("=== Default Medical Services Created/Updated ===");

            // ALWAYS remove any unwanted services (health checkup, medicine delivery)
            List<String> unwantedCategories = Arrays.asList("health_checkup", "medicine_delivery", "health checkup",
                    "medicine delivery", "HEALTH_CHECKUP", "MEDICINE_DELIVERY");
            for (String category : unwantedCategories) {
                List<MedicalService> unwanted = serviceRepository.findByCategory(category);
                if (!unwanted.isEmpty()) {
                    serviceRepository.deleteAll(unwanted);
                    System.out.println(
                            "=== Removed " + unwanted.size() + " services with category: " + category + " ===");
                }
            }

            // Also remove services with categories not in allowed list
            List<String> allowedCategories = Arrays.asList("prescription", "pharmacy", "consultation");
            List<MedicalService> allServices = serviceRepository.findAll();
            List<MedicalService> toRemove = allServices.stream()
                    .filter(s -> !allowedCategories.contains(s.getCategory()))
                    .collect(Collectors.toList());
            if (!toRemove.isEmpty()) {
                serviceRepository.deleteAll(toRemove);
                System.out.println("=== Removed " + toRemove.size() + " non-allowed services ===");
            }

            // Fix old orders with incorrect totalAmount (25 or 50)
            // These were set incorrectly due to wrong prices in MedicalService
            List<Order> allOrders = orderRepository.findAll();
            int fixedCount = 0;
            for (Order order : allOrders) {
                boolean orderFixed = false;

                if (order.getTotalAmount() != null) {
                    BigDecimal currentAmount = order.getTotalAmount();

                    // Fix Prescription Analysis orders (was 25, should be 500)
                    if (currentAmount.compareTo(new BigDecimal("25.00")) == 0 ||
                            currentAmount.compareTo(new BigDecimal("25")) == 0) {
                        order.setTotalAmount(new BigDecimal("500.00"));
                        System.out.println("=== Fixed order " + order.getOrderNumber() + ": amount " + currentAmount
                                + " -> 500.00 ===");
                        orderFixed = true;
                    }
                    // Fix Second Opinion orders (was 50/500, should be 5000)
                    else if (currentAmount.compareTo(new BigDecimal("50.00")) == 0 ||
                            currentAmount.compareTo(new BigDecimal("50")) == 0) {
                        order.setTotalAmount(new BigDecimal("5000.00"));
                        System.out.println("=== Fixed order " + order.getOrderNumber() + ": amount " + currentAmount
                                + " -> 5000.00 ===");
                        orderFixed = true;
                    }
                    else if ("SECOND_OPINION".equals(order.getServiceType()) &&
                            (currentAmount.compareTo(new BigDecimal("500.00")) == 0
                                    || currentAmount.compareTo(new BigDecimal("500")) == 0)) {
                        order.setTotalAmount(new BigDecimal("5000.00"));
                        System.out.println("=== Fixed second opinion order " + order.getOrderNumber() + ": amount "
                                + currentAmount + " -> 5000.00 ===");
                        orderFixed = true;
                    }
                }

                // CRITICAL FIX: Only real payment references should upgrade an order to PAID.
                // Placeholder references used during submission must never be treated as payment.
                String paymentReference = order.getPaymentReference();
                boolean hasRealPaymentReference = paymentReference != null
                        && !paymentReference.isEmpty()
                        && !paymentReference.startsWith("SUBMITTED-");

                if (hasRealPaymentReference) {
                    if ("PENDING".equals(order.getPaymentStatus())) {
                        order.setPaymentStatus("PAID");
                        System.out.println("=== Fixed payment status for order " + order.getOrderNumber()
                                + ": PENDING -> PAID (had payment reference) ===");
                        orderFixed = true;
                    }
                }

                // FIX FOR BUG: Previously, orders with amount > 1000 were incorrectly marked as
                // PAID
                // This was wrong - we should NOT auto-mark orders as PAID based on amount
                // Reset orders that have PAID status but NO paymentReference to PENDING
                // (paymentReference is only set when payment is actually made)
                if (!hasRealPaymentReference) {
                    if ("PAID".equals(order.getPaymentStatus())) {
                        order.setPaymentStatus("PENDING");
                        System.out.println("=== Fixed payment status for order " + order.getOrderNumber()
                                + ": PAID -> PENDING (no payment reference, was incorrectly marked) ===");
                        orderFixed = true;
                    }
                }

                if (orderFixed) {
                    orderRepository.save(order);
                    fixedCount++;
                }
            }
            if (fixedCount > 0) {
                System.out.println("=== Fixed " + fixedCount + " orders ===");
            }

            // CRITICAL FIX: Find and fix orders linked to test user
            // (patient@rxincredible.com)
            // These orders need to be identified and either deleted or linked to correct
            // users
            List<String> invalidEmails = List.of("patient@rxincredible.com", "test@rxincredible.com",
                    "demo@rxincredible.com");
            for (String invalidEmail : invalidEmails) {
                Optional<User> testUserOpt = userRepository.findByEmail(invalidEmail);
                if (testUserOpt.isPresent()) {
                    User testUser = testUserOpt.get();
                    System.out.println("=== Found test user: " + invalidEmail + " (ID: " + testUser.getId() + ") ===");

                    // Find orders linked to this test user
                    List<Order> ordersWithTestUser = orderRepository.findAll().stream()
                            .filter(o -> o.getUser() != null && o.getUser().getId().equals(testUser.getId()))
                            .collect(Collectors.toList());

                    System.out.println("=== Found " + ordersWithTestUser.size() + " orders linked to test user "
                            + invalidEmail + " ===");
                    for (Order o : ordersWithTestUser) {
                        System.out.println("  - Order: " + o.getOrderNumber() + " (ID: " + o.getId() + ")");
                    }
                }
            }

            // REMOVED: Auto-assign default doctor to orders without assigned doctor
            // Admin will now manually assign doctors/analysts to orders via the UI
            // This gives admin full control over order assignment
        };
    }

    private MedicalService createService(String name, String description, BigDecimal price, String category) {
        MedicalService service = new MedicalService();
        service.setServiceName(name);
        service.setDescription(description);
        service.setPrice(price);
        service.setCategory(category);
        service.setIsActive(true);
        return service;
    }
}
