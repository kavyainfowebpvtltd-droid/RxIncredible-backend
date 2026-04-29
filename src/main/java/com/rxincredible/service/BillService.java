package com.rxincredible.service;

import com.rxincredible.entity.Order;
import com.rxincredible.entity.User;
import com.rxincredible.repository.OrderRepository;
import com.rxincredible.util.CurrencyUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class BillService {

    private final OrderRepository orderRepository;

    @Value("${app.upload.directory}")
    private String uploadDirectory;

    @Autowired
    public BillService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public String generateBillPdf(Long orderId) throws IOException {
        Order order = orderRepository.findByIdWithUser(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));

        System.out.println("=== BILL SERVICE START ===");
        System.out.println("Order ID: " + orderId);
        System.out.println("Order Number: " + order.getOrderNumber());

        // Use upload directory from application.properties
        String uploadDir = uploadDirectory;

        File dir = new File(uploadDir);
        System.out.println("Using directory: " + dir.getAbsolutePath());
        System.out.println("Directory exists: " + dir.exists());

        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            System.out.println("Created: " + created);
        }

        String fileName = "bill_" + order.getOrderNumber() + "_" + System.currentTimeMillis() + ".pdf";
        String filePath = uploadDir + "/" + fileName;

        System.out.println("File path: " + filePath);

        // Now create the PDF
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        PDType1Font fontBold = PDType1Font.HELVETICA_BOLD;
        PDType1Font fontRegular = PDType1Font.HELVETICA;

        PDPageContentStream contentStream = new PDPageContentStream(document, page);

        // Header with Logo
        try {
            File logoFile = new File("backend\\src\\main\\java\\com\\rxincredible\\service\\asset\\logo1.png");
            if (logoFile.exists()) {
                PDImageXObject logo = PDImageXObject.createFromFile(logoFile.getAbsolutePath(), document);
                contentStream.drawImage(logo, 50, 680, 100, 60);
            }
        } catch (Exception e) {
            System.out.println("Logo error: " + e.getMessage());
        }

        // Header Text (next to logo) - Using both logo colors
        contentStream.setNonStrokingColor(0, 51, 102); // RxIncredible Blue
        contentStream.beginText();
        contentStream.setFont(fontBold, 14);
        contentStream.newLineAtOffset(160, 720);
        contentStream.showText("Bhagyawati Drugs & Chemicals Pvt. Ltd.");
        contentStream.endText();

        contentStream.beginText();
        contentStream.setFont(fontRegular, 8);
        contentStream.newLineAtOffset(160, 690);
        contentStream.showText("234 Shree Nagar, Nagpur-15 | Phone: 9822848689");
        contentStream.endText();

        contentStream.beginText();
        contentStream.setFont(fontBold, 8);
        contentStream.newLineAtOffset(160, 675);
        contentStream.showText("GST: 27AALCB2082P2Z4");
        contentStream.endText();

        // Bill Title
        contentStream.beginText();
        contentStream.setFont(fontBold, 18);
        contentStream.newLineAtOffset(220, 700);
        contentStream.showText("PRESCRIPTION BILL");
        contentStream.endText();

        // Bill Details
        contentStream.beginText();
        contentStream.setFont(fontBold, 12);
        contentStream.newLineAtOffset(50, 660);
        contentStream.showText("Bill No: " + safePdfText(order.getOrderNumber()));
        contentStream.endText();

        // Patient Info
        User user = order.getUser();
        String patientName = user != null ? user.getFullName() : "N/A";

        contentStream.beginText();
        contentStream.setFont(fontRegular, 11);
        contentStream.newLineAtOffset(50, 620);
        contentStream.showText("Patient: " + safePdfText(patientName));
        contentStream.endText();

        // Total Amount
        BigDecimal totalAmount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        String amountStr = CurrencyUtil.formatAmount(totalAmount, resolveCountry(order));

        contentStream.beginText();
        contentStream.setFont(fontBold, 14);
        contentStream.newLineAtOffset(300, 580);
        contentStream.showText("Total: " + safePdfText(amountStr));
        contentStream.endText();

        // Footer
        contentStream.beginText();
        contentStream.setFont(fontRegular, 9);
        contentStream.newLineAtOffset(170, 50);
        contentStream.showText("Generated by RxIncredible");
        contentStream.endText();

        contentStream.close();

        // Save
        document.save(filePath);
        document.close();

        System.out.println("PDF saved: " + filePath);
        System.out.println("=== BILL SERVICE END ===");

        return fileName;
    }





    

    // Generate payment receipt PDF
    public String generatePaymentReceiptPdf(Long orderId) throws IOException {
        Order order = orderRepository.findByIdWithUser(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));

        System.out.println("=== PAYMENT RECEIPT SERVICE START ===");
        System.out.println("Order ID: " + orderId);
        System.out.println("Order Number: " + order.getOrderNumber());
        System.out.println("Service Type: " + order.getServiceType());
        System.out.println("Total Amount: " + order.getTotalAmount());
        System.out.println("User: " + (order.getUser() != null ? order.getUser().getEmail() : "NULL"));

        // Validate user exists
        if (order.getUser() == null) {
            throw new RuntimeException("Order " + orderId + " has no associated user");
        }

        // Use upload directory from application.properties
        String uploadDir = uploadDirectory;

        File dir = new File(uploadDir);
        System.out.println("Using directory: " + dir.getAbsolutePath());
        System.out.println("Directory exists: " + dir.exists());

        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            System.out.println("Created: " + created);
        }

        String fileName = "receipt_" + order.getOrderNumber() + "_" + System.currentTimeMillis() + ".pdf";
        String filePath = uploadDir + "/" + fileName;

        System.out.println("File path: " + filePath);

        // Now create the PDF
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        PDType1Font fontBold = PDType1Font.HELVETICA_BOLD;
        PDType1Font fontRegular = PDType1Font.HELVETICA;

        PDPageContentStream contentStream = new PDPageContentStream(document, page);

        // Get order details with null safety
        User user = order.getUser();
        String patientName = (user != null && user.getFullName() != null) ? user.getFullName() : "Customer";
        String patientEmail = (user != null && user.getEmail() != null) ? user.getEmail() : "Not Available";
        String patientPhone = (user != null && user.getPhone() != null) ? user.getPhone() : "Not Available";

        BigDecimal totalAmount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        String amountStr = CurrencyUtil.formatAmount(totalAmount, resolveCountry(order));

        String serviceType = order.getServiceType() != null ? order.getServiceType() : "N/A";
        String serviceTypeDisplay = serviceType.replace("_", " ");

        String paymentMethod = order.getPaymentMethod() != null ? order.getPaymentMethod() : "CARD";
        String paymentReference = order.getPaymentReference() != null ? order.getPaymentReference() : "N/A";

        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

        float yPos = 800;
        float margin = 50;

        // Header with Logo
        try {
            File logoFile = new File("C:\\Paymentlogo\\logo1.png");

            if (logoFile.exists()) {
                PDImageXObject logo = PDImageXObject.createFromFile(logoFile.getAbsolutePath(), document);
                contentStream.drawImage(logo, margin, yPos - 42, 80, 80);
            }
        } catch (Exception e) {
            System.out.println("Logo error: " + e.getMessage());
        }

        // Header Text (next to logo)
        contentStream.setNonStrokingColor(0, 51, 102); // Blue
        contentStream.beginText();
        contentStream.setFont(fontBold, 14);
        contentStream.newLineAtOffset(margin + 100, yPos);
        contentStream.showText("Bhagyawati Drugs & Chemicals Pvt. Ltd.");
        contentStream.endText();

        yPos -= 14;
        contentStream.setNonStrokingColor(80, 80, 80); // Dark gray
        contentStream.beginText();
        contentStream.setFont(fontRegular, 8);
        contentStream.newLineAtOffset(margin + 100, yPos);
        contentStream.showText("234 Shree Nagar, Nagpur-15 | Phone: 9822848689");
        contentStream.endText();

        yPos -= 12;
        contentStream.setNonStrokingColor(0, 51, 102); // Blue
        contentStream.beginText();
        contentStream.setFont(fontBold, 8);
        contentStream.newLineAtOffset(margin + 100, yPos);
        contentStream.showText("GST: 27AALCB2082P2Z4");
        contentStream.endText();

        // Horizontal line
        yPos -= 15;
        contentStream.setStrokingColor(0, 51, 102); // Blue
        contentStream.setLineWidth(2);
        contentStream.moveTo(margin, yPos);
        contentStream.lineTo(550, yPos);
        contentStream.stroke();

        // Payment Receipt Title
        yPos -= 30;
        contentStream.setNonStrokingColor(0, 51, 102); // Blue
        contentStream.beginText();
        contentStream.setFont(fontBold, 22);
        contentStream.newLineAtOffset(180, yPos);
        contentStream.showText("PAYMENT RECEIPT");
        contentStream.endText();

        // Receipt Details Box
        yPos -= 40;
        contentStream.setNonStrokingColor(240, 248, 255); // Light blue background
        contentStream.addRect(margin, yPos - 180, 500, 190);
        contentStream.fill();

        yPos -= 20;
        contentStream.setNonStrokingColor(0, 51, 102); // Blue
        contentStream.beginText();
        contentStream.setFont(fontBold, 14);
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Receipt Details");
        contentStream.endText();

        yPos -= 25;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 11);
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Receipt No: " + safePdfText(fileName.replace(".pdf", "")));
        contentStream.endText();

        yPos -= 18;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Order Number: " + safePdfText(order.getOrderNumber()));
        contentStream.endText();

        yPos -= 18;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Service Type: " + safePdfText(serviceTypeDisplay));
        contentStream.endText();

        yPos -= 18;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Payment Date: " + currentDate);
        contentStream.endText();

        yPos -= 18;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Payment Method: " + safePdfText(paymentMethod));
        contentStream.endText();

        yPos -= 18;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Payment Reference: " + safePdfText(paymentReference));
        contentStream.endText();

        // Patient Info Box
        yPos -= 50;
        contentStream.setNonStrokingColor(240, 248, 255); // Light blue
        contentStream.addRect(margin, yPos - 80, 500, 90);
        contentStream.fill();

        yPos -= 20;
        contentStream.setNonStrokingColor(0, 51, 102); // Blue
        contentStream.beginText();
        contentStream.setFont(fontBold, 12);
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Patient Information");
        contentStream.endText();

        yPos -= 18;
        contentStream.setNonStrokingColor(51, 65, 85);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 10);
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Name: " + safePdfText(patientName));
        contentStream.endText();

        yPos -= 14;
        contentStream.beginText();
        contentStream.newLineAtOffset(margin + 10, yPos);
        contentStream.showText("Email: " + safePdfText(patientEmail) + " | Phone: " + safePdfText(patientPhone));
        contentStream.endText();

        // Amount Paid Box
        yPos -= 40;
        contentStream.setNonStrokingColor(240, 248, 255); // Light blue
        contentStream.addRect(margin, yPos - 50, 500, 60);
        contentStream.fill();

        yPos -= 15;
        contentStream.setNonStrokingColor(0, 51, 102); // Blue text
        contentStream.beginText();
        contentStream.setFont(fontBold, 16);
        contentStream.newLineAtOffset(margin + 20, yPos);
        contentStream.showText("Amount Paid: " + safePdfText(amountStr));
        contentStream.endText();

        // Footer with Logo (Left) and Text (Right) on same line
        float footerY = yPos - 90;

        // Footer Logo - Left corner
        float logoWidth = 180;
        float logoHeight = 50;
        float logoX = 50; // margin

        try {
            File footerLogoFile = new File("C:\\Paymentlogo\\logo2.png");
            if (footerLogoFile.exists()) {
                PDImageXObject footerLogo = PDImageXObject.createFromFile(footerLogoFile.getAbsolutePath(), document);
                contentStream.drawImage(footerLogo, logoX, footerY, logoWidth, logoHeight);
            }
        } catch (Exception e) {
            System.out.println("Footer logo error: " + e.getMessage());
        }

        // Footer Text - Right side (on same Y level as logo)
        float textX = 250; // Start text after logo
        contentStream.setNonStrokingColor(100, 116, 139);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 8);
        contentStream.newLineAtOffset(textX, footerY + 35);
        contentStream.showText("This is a computer-generated receipt and does not require a signature.");
        contentStream.endText();

        contentStream.beginText();
        contentStream.setFont(fontRegular, 8);
        contentStream.newLineAtOffset(textX, footerY + 22);
        contentStream.showText("For any queries, contact us at contact@rxincredible.com | Phone: 9822848689");
        contentStream.endText();

        contentStream.beginText();
        contentStream.setFont(fontRegular, 7);
        contentStream.newLineAtOffset(textX, footerY + 10);
        contentStream.showText("Generated by Bhagyawati Drugs & Chemicals Pvt. Ltd " + safePdfText(currentDate));
        contentStream.endText();

        contentStream.close();

        // Save
        document.save(filePath);
        document.close();

        System.out.println("Payment Receipt PDF saved: " + filePath);
        System.out.println("=== PAYMENT RECEIPT SERVICE END ===");

        return fileName;
    }

    private String resolveCountry(Order order) {
        if (order.getDeliveryCountry() != null && !order.getDeliveryCountry().isBlank()) {
            return order.getDeliveryCountry();
        }
        if (order.getUser() != null && order.getUser().getCountry() != null && !order.getUser().getCountry().isBlank()) {
            return order.getUser().getCountry();
        }
        return "India";
    }

    private String safePdfText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value
                .replace("\u20B9", "Rs.")
                .replace("\u00A0", " ");
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");

        StringBuilder safeText = new StringBuilder(normalized.length());
        for (char ch : normalized.toCharArray()) {
            if ((ch >= 32 && ch <= 126) || (ch >= 160 && ch <= 255)) {
                safeText.append(ch);
            } else {
                safeText.append('?');
            }
        }

        return safeText.toString();
    }
}
