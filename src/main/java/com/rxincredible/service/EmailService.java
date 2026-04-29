package com.rxincredible.service;

import com.rxincredible.entity.Order;
import com.rxincredible.entity.Prescription;
import com.rxincredible.entity.User;
import com.rxincredible.util.CurrencyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.util.regex.Pattern;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    // Email validation pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Send OTP email for email verification
     */
    public void sendOtpEmail(String toEmail, String fullName, String otp) {
        if (toEmail == null || toEmail.isEmpty()) {
            logger.warn("Cannot send OTP email: Email not provided");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("RxIncredible <info@rxincredible.com>");
            message.setTo(toEmail);
            message.setSubject("Email Verification - RxIncredible");
            message.setText(buildOtpEmailBody(fullName, otp));

            mailSender.send(message);

            logger.info("OTP email sent successfully to: {}", toEmail);
            logger.info("OTP for testing: {}", otp);
        } catch (Exception e) {
            logger.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
            logger.error("Email sending stack trace: ", e);
            // Still log the OTP for testing purposes
            logger.info("OTP for {} (email failed): {}", toEmail, otp);
        }
    }

    /**
     * Send verification email with verification link
     */
    public void sendVerificationEmail(String toEmail, String fullName, String verifyUrl) {
        if (toEmail == null || toEmail.isEmpty()) {
            logger.warn("Cannot send verification email: Email not provided");
            return;
        }

        try {
            // Use HTML email for better UI
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("RxIncredible <info@rxincredible.com>");
            helper.setTo(toEmail);
            helper.setSubject("Verify Your Email - RxIncredible Registration");
            helper.setText(buildVerificationEmailBody(fullName, verifyUrl), true);

            mailSender.send(message);

            logger.info("Verification email sent successfully to: {}", toEmail);
            logger.info("Verification URL: {}", verifyUrl);
        } catch (MessagingException e) {
            logger.error("Failed to send verification email to {}: {}", toEmail, e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to send verification email to {}: {}", toEmail, e.getMessage());
            // Log the verification URL for testing purposes
            logger.info("Verification URL for {}: {}", toEmail, verifyUrl);
        }
    }

    private String buildVerificationEmailBody(String fullName, String verifyUrl) {
        StringBuilder body = new StringBuilder();
        body.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>\n");
        body.append("<div style='max-width: 600px; margin: 0 auto; padding: 20px;'>\n");

        // Header
        body.append(
                "<div style='background: linear-gradient(135deg, #1e3a8a, #2563eb); padding: 20px; border-radius: 10px 10px 0 0;'>\n");
        body.append("<h1 style='color: white; margin: 0;'>RxIncredible</h1>\n");
        body.append("<p style='color: #e0e7ff; margin: 5px 0 0 0;'>Medical & Pharmacy Services</p>\n");
        body.append("</div>\n");

        // Content
        body.append("<div style='background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px;'>\n");
        body.append("<h2 style='color: #1e3a8a;'>Verify Your Email Address</h2>\n");
        body.append("<p>Dear <strong>").append(fullName != null ? fullName : "User").append("</strong>,</p>\n");
        body.append("<p>Thank you for registering with RxIncredible!</p>\n");
        body.append(
                "<p>Please click the button below to verify your email address and complete your registration:</p>\n");

        // Verification Button
        body.append("<div style='margin: 30px 0; text-align: center;'>\n");
        body.append("<a href='").append(verifyUrl).append("'");
        body.append(
                " style='display: inline-block; background: #16a34a; color: white; padding: 15px 30px; text-decoration: none; border-radius: 8px; font-weight: bold;'>\n");
        body.append("Verify Email</a>\n");
        body.append("</div>\n");

        body.append("<p style='color: #6b7280; font-size: 14px;'>\n");
        body.append("This verification link will expire in 24 hours.</p>\n");

        body.append("<p style='color: #6b7280; font-size: 14px;'>\n");
        body.append(
                "If the button above doesn't work, you can copy and paste the following link into your browser:</p>\n");
        body.append("<p style='color: #2563eb; word-break: break-all;'>").append(verifyUrl).append("</p>\n");

        body.append("<p>If you did not register with RxIncredible, please ignore this email.</p>\n");

        // Footer
        body.append(
                "<div style='margin-top: 30px; padding-top: 20px; border-top: 1px solid #e5e7eb; color: #6b7280; font-size: 12px;'>\n");
        body.append("<p><strong>RxIncredible</strong> - Medical & Pharmacy Services</p>\n");
        body.append("<p>Phone: 9822848689 | Email: contact@rxincredible.com | Website: www.rxincredible.com</p>\n");
        body.append("</div>\n");

        body.append("</div>\n");
        body.append("</div>\n");
        body.append("</body>\n");
        body.append("</html>\n");

        return body.toString();
    }

    private String buildOtpEmailBody(String fullName, String otp) {
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(fullName != null ? fullName : "User").append(",\n\n");
        body.append("Thank you for registering with RxIncredible!\n\n");
        body.append("Your One-Time Password (OTP) for email verification is:\n\n");
        body.append("        ").append(otp).append("\n\n");
        body.append("This OTP will expire in 30 seconds.\n");
        body.append("Please enter this OTP to verify your email address.\n\n");
        body.append("If you did not register with RxIncredible, please ignore this email.\n\n");
        body.append("Thank you,\n");
        body.append("RxIncredible Team\n");
        body.append("Medical & Pharmacy Services");

        return body.toString();
    }

    /**
     * Send email when order status is updated to COMPLETED
     */
    public void sendOrderCompletedEmail(Order order) {
        User user = order.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            logger.warn("Cannot send email: User or email not found for order {}. Email will not be sent.",
                    order.getId());
            return;
        }

        String toEmail = user.getEmail();

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}. Email will not be sent.", toEmail);
            return;
        }

        // Check if email is a test/internal email that can't receive mail
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}. Email will not be sent.", toEmail);
            return;
        }

        String subject = "Your Prescription Bill is Ready - Order " + order.getOrderNumber();

        String body = buildOrderCompletedEmailBody(order, user);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("RxIncredible <info@rxincredible.com>");
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            logger.info("Order completion email sent to: {}", toEmail);
        } catch (Exception e) {
            logger.error("Failed to send order completion email: {}", e.getMessage());
        }
    }

    /**
     * Send email when doctor is assigned to an order
     */
    public void sendDoctorAssignedEmail(Order order) {
        User user = order.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            logger.warn("Cannot send email: User or email not found for order {}. Email will not be sent.",
                    order.getId());
            return;
        }

        String toEmail = user.getEmail();

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}. Email will not be sent.", toEmail);
            return;
        }

        // Check if email is a test/internal email that can't receive mail
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}. Email will not be sent.", toEmail);
            return;
        }

        String subject = "Doctor Assigned - Order " + order.getOrderNumber();

        String body = buildDoctorAssignedEmailBody(order, user);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("RxIncredible <info@rxincredible.com>");
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            logger.info("Doctor assigned email sent to: {}", toEmail);
        } catch (Exception e) {
            logger.error("Failed to send doctor assigned email: {}", e.getMessage());
        }
    }

    private String buildOrderCompletedEmailBody(Order order, User user) {
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(user.getFullName()).append(",\n\n");
        body.append("Your prescription bill has been generated and your order is now COMPLETED.\n\n");
        body.append("Order Details:\n");
        body.append("-------------\n");
        body.append("Order Number: ").append(order.getOrderNumber()).append("\n");
        body.append("Service Type: ").append(order.getServiceType()).append("\n");
        body.append("Status: ").append(order.getStatus()).append("\n");

        if (order.getTotalAmount() != null) {
            body.append("Total Amount: ₹").append(order.getTotalAmount()).append("\n");
        }

        body.append("\n");
        body.append("You can now proceed with the payment and avail your medicines.\n\n");
        body.append("Thank you for choosing RxIncredible!\n\n");
        body.append("Best regards,\n");
        body.append("RxIncredible Team\n");
        body.append("Medical & Pharmacy Services");

        return body.toString();
    }

    private String buildDoctorAssignedEmailBody(Order order, User user) {
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(user.getFullName()).append(",\n\n");
        body.append("A doctor has been assigned to your order.\n\n");
        body.append("Order Details:\n");
        body.append("-------------\n");
        body.append("Order Number: ").append(order.getOrderNumber()).append("\n");
        body.append("Service Type: ").append(order.getServiceType()).append("\n");
        body.append("Status: ").append(order.getStatus()).append("\n");

        if (order.getAssignedDoctor() != null) {
            body.append("Assigned Doctor: Dr. ").append(order.getAssignedDoctor().getFullName()).append("\n");
        }

        body.append("\n");
        body.append("Your order is now being reviewed by the doctor.\n\n");
        body.append("Thank you for choosing RxIncredible!\n\n");
        body.append("Best regards,\n");
        body.append("RxIncredible Team\n");
        body.append("Medical & Pharmacy Services");

        return body.toString();
    }

    /**
     * Send email with prescription PDF attachment when medical report is generated
     */
    public void sendPrescriptionEmail(Order order, String pdfFilePath) {
        User user = order.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            logger.warn("Cannot send email: User or email not found for order {}. Email will not be sent.",
                    order.getId());
            return;
        }

        String toEmail = user.getEmail();

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}. Email will not be sent.", toEmail);
            return;
        }

        // Check if email is a test/internal email that can't receive mail
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}. Email will not be sent.", toEmail);
            return;
        }

        String subject = "Your Medical Report & Prescription is Ready - Order " + order.getOrderNumber();

        String body = buildPrescriptionEmailBody(order, user);

        // Always try to send as HTML email with attachment
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("RxIncredible <info@rxincredible.com>");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body);

            // Try to attach PDF if file path is provided
            if (pdfFilePath != null && !pdfFilePath.isEmpty()) {
                File pdfFile = new File(pdfFilePath);
                logger.info("Checking for prescription PDF at path: {}", pdfFile.getAbsolutePath());

                if (pdfFile.exists()) {
                    helper.addAttachment("Prescription_" + order.getOrderNumber() + ".pdf", pdfFile);
                    logger.info("Prescription PDF attached successfully: {}", pdfFile.getAbsolutePath());
                } else {
                    // Try alternative path formats
                    logger.warn("PDF file not found at: {}. Trying alternative paths...", pdfFile.getAbsolutePath());

                    // Try with forward slashes
                    File altPdfFile1 = new File(pdfFilePath.replace("\\", "/"));
                    if (altPdfFile1.exists()) {
                        helper.addAttachment("Prescription_" + order.getOrderNumber() + ".pdf", altPdfFile1);
                        logger.info("Prescription PDF attached from alternative path: {}",
                                altPdfFile1.getAbsolutePath());
                    } else {
                        // Try with backward slashes
                        File altPdfFile2 = new File(pdfFilePath.replace("/", "\\"));
                        if (altPdfFile2.exists()) {
                            helper.addAttachment("Prescription_" + order.getOrderNumber() + ".pdf", altPdfFile2);
                            logger.info("Prescription PDF attached from alternative path: {}",
                                    altPdfFile2.getAbsolutePath());
                        } else {
                            logger.warn(
                                    "Prescription PDF file not found in any path format. Email will be sent without attachment.");
                        }
                    }
                }
            } else {
                logger.warn("No PDF file path provided for order {}. Email will be sent without attachment.",
                        order.getId());
            }

            mailSender.send(message);
            logger.info("Prescription email sent to: {}", toEmail);

        } catch (MessagingException e) {
            logger.error("Failed to send prescription email with attachment: {}", e.getMessage());
            // Try sending without attachment
            try {
                SimpleMailMessage simpleMessage = new SimpleMailMessage();
                simpleMessage.setFrom("RxIncredible <info@rxincredible.com>");
                simpleMessage.setTo(toEmail);
                simpleMessage.setSubject(subject);
                simpleMessage.setText(body);

                mailSender.send(simpleMessage);
                logger.info("Prescription email sent to (fallback): {}", toEmail);
            } catch (Exception ex) {
                logger.error("Failed to send prescription email: {}", ex.getMessage());
            }
        } catch (Exception e) {
            logger.error("Failed to send prescription email: {}", e.getMessage());
        }
    }

    public void sendPrescriptionEmail(Order order, byte[] pdfBytes, String attachmentName) {
        User user = order.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            logger.warn("Cannot send email: User or email not found for order {}. Email will not be sent.",
                    order.getId());
            return;
        }

        String toEmail = user.getEmail();

        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}. Email will not be sent.", toEmail);
            return;
        }

        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}. Email will not be sent.", toEmail);
            return;
        }

        String subject = "Your Medical Report & Prescription is Ready - Order " + order.getOrderNumber();
        String body = buildPrescriptionEmailBody(order, user);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("RxIncredible <info@rxincredible.com>");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body);

            if (pdfBytes != null && pdfBytes.length > 0) {
                DataSource dataSource = new ByteArrayDataSource(pdfBytes, "application/pdf");
                helper.addAttachment(
                        attachmentName != null && !attachmentName.isBlank()
                                ? attachmentName
                                : "Prescription_" + order.getOrderNumber() + ".pdf",
                        dataSource);
                logger.info("Prescription PDF bytes attached successfully for order {}", order.getOrderNumber());
            } else {
                logger.warn("No PDF bytes provided for order {}. Email will be sent without attachment.",
                        order.getId());
            }

            mailSender.send(message);
            logger.info("Prescription email with byte attachment sent to: {}", toEmail);
        } catch (MessagingException e) {
            logger.error("Failed to send prescription email with byte attachment: {}", e.getMessage());
            throw new RuntimeException("Failed to send prescription email with attachment", e);
        } catch (Exception e) {
            logger.error("Failed to send prescription email with byte attachment: {}", e.getMessage());
            throw new RuntimeException("Failed to send prescription email with attachment", e);
        }
    }

    public void sendQuotationEmail(Order order, byte[] pdfBytes, String attachmentName) {
        User user = order.getUser();
        String toEmail = user != null ? user.getEmail() : null;
        if (toEmail == null || toEmail.isEmpty()) {
            toEmail = order.getUserEmail();
        }

        if (toEmail == null || toEmail.isEmpty()) {
            logger.warn("Cannot send quotation email: User email not found for order {}.", order.getId());
            throw new RuntimeException("Patient email not found for this order");
        }

        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send quotation email: Invalid email format {}.", toEmail);
            throw new RuntimeException("Invalid patient email: " + toEmail);
        }

        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send quotation email to blocked address: {}.", toEmail);
            throw new RuntimeException("Email delivery is blocked for this recipient: " + toEmail);
        }

        String subject = "Your Quotation is Ready - Order " + order.getOrderNumber();
        String body = buildQuotationEmailBody(order, user);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("RxIncredible <info@rxincredible.com>");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, true);

            if (pdfBytes != null && pdfBytes.length > 0) {
                DataSource dataSource = new ByteArrayDataSource(pdfBytes, "application/pdf");
                helper.addAttachment(
                        attachmentName != null && !attachmentName.isBlank()
                                ? attachmentName
                                : "Quotation_" + order.getOrderNumber() + ".pdf",
                        dataSource);
                logger.info("Quotation PDF attached successfully for order {}", order.getOrderNumber());
            } else {
                throw new RuntimeException("Quotation PDF bytes are missing");
            }

            mailSender.send(message);
            logger.info("Quotation email sent to: {}", toEmail);
        } catch (MessagingException e) {
            logger.error("Failed to send quotation email: {}", e.getMessage());
            throw new RuntimeException("Failed to send quotation email", e);
        } catch (Exception e) {
            logger.error("Failed to send quotation email: {}", e.getMessage());
            String message = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : "Unknown mail sending error";
            throw new RuntimeException(message, e);
        }
    }

    private String buildPrescriptionEmailBody(Order order, User user) {
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(user.getFullName()).append(",\n\n");
        body.append("Good news! Your medical report and prescription have been generated.\n\n");
        body.append("Order Details:\n");
        body.append("-------------\n");
        body.append("Order Number: ").append(order.getOrderNumber()).append("\n");
        body.append("Service Type: ").append(order.getServiceType()).append("\n");
        body.append("Status: ").append(order.getStatus()).append("\n");

        if (order.getPrescription() != null) {
            Prescription prescription = order.getPrescription();
            if (prescription.getDiagnosis() != null && !prescription.getDiagnosis().isEmpty()) {
                body.append("\nDiagnosis: ").append(prescription.getDiagnosis()).append("\n");
            }
            // Removed recommendations and prescription details from email body
            // They are included in the PDF attachment
        }

        if (order.getAssignedDoctor() != null) {
            body.append("\nDoctor: Dr. ").append(order.getAssignedDoctor().getFullName()).append("\n");
        }

        body.append("\n");
        body.append("Please find your prescription attached to this email.\n");
        body.append("You can also download it from your account on our portal.\n\n");
        body.append("Thank you for choosing RxIncredible!\n\n");
        body.append("Best regards,\n");
        body.append("RxIncredible Team\n");
        body.append("Medical & Pharmacy Services\n");
        body.append("Phone: 9822848689\n");
        body.append("Email: contact@rxincredible.com\n");
        body.append("Website: www.rxincredible.com");

        return body.toString();
    }

    private String buildQuotationEmailBody(Order order, User user) {
        String loginUrl = frontendUrl + "/login?redirect=/user/pay/" + order.getId();
        String rejectUrl = frontendUrl + "/user/reject/" + order.getId();
        String displayName = user != null && user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName()
                : "Customer";
        String country = order.getDeliveryCountry() != null && !order.getDeliveryCountry().isBlank()
                ? order.getDeliveryCountry()
                : user != null && user.getCountry() != null && !user.getCountry().isBlank() ? user.getCountry()
                        : "India";
        String displayAmount = CurrencyUtil.formatAmount(order.getTotalAmount(), country);

        StringBuilder body = new StringBuilder();
        body.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>\n");
        body.append("<div style='max-width: 600px; margin: 0 auto; padding: 20px;'>\n");
        body.append(
                "<div style='background: linear-gradient(135deg, #1e3a8a, #2563eb); padding: 20px; border-radius: 10px 10px 0 0;'>\n");
        body.append("<h1 style='color: white; margin: 0;'>RxIncredible</h1>\n");
        body.append("<p style='color: #e0e7ff; margin: 5px 0 0 0;'>Medical & Pharmacy Services</p>\n");
        body.append("</div>\n");
        body.append("<div style='background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px;'>\n");
        body.append("<h2 style='color: #1e3a8a;'>Your Quotation is Ready!</h2>\n");
        body.append("<p>Dear <strong>").append(displayName).append("</strong>,</p>\n");
        body.append("<p>Please find your quotation PDF attached with this email.</p>\n");
        body.append("<table style='width: 100%; border-collapse: collapse; margin: 20px 0;'>\n");
        body.append(
                "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Order Number:</strong></td>");
        body.append("<td style='padding: 10px; border-bottom: 1px solid #ddd;'>").append(order.getOrderNumber())
                .append("</td></tr>\n");
        body.append(
                "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Service Type:</strong></td>");
        body.append("<td style='padding: 10px; border-bottom: 1px solid #ddd;'>").append(order.getServiceType())
                .append("</td></tr>\n");

        String currencySymbol = "";
        if (true) {
            currencySymbol = "₹";
        }

        if (order.getTotalAmount() != null) {

            body.append(
                    "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Total Amount:</strong></td>");
            body.append(
                    "<td style='padding: 10px; border-bottom: 1px solid #ddd; font-size: 18px; color: #16a34a; font-weight: bold;'>");
            body.append(displayAmount).append("</td></tr>\n");
        }
        body.append("</table>\n");
        body.append("<div style='margin: 30px 0; text-align: center;'>\n");
        body.append("<a href='").append(loginUrl).append(
                "' style='display: inline-block; background: #16a34a; color: white; padding: 15px 30px; text-decoration: none; border-radius: 8px; font-weight: bold; margin-right: 10px;'>Pay Now</a>\n");
        body.append("<a href='").append(rejectUrl).append(
                "' style='display: inline-block; background: #dc2626; color: white; padding: 15px 30px; text-decoration: none; border-radius: 8px; font-weight: bold;'>Reject Quote</a>\n");
        body.append("</div>\n");
        body.append("<p>Thank you for choosing RxIncredible!</p>\n");
        body.append(
                "<div style='margin-top: 30px; padding-top: 20px; border-top: 1px solid #e5e7eb; color: #6b7280; font-size: 12px;'>\n");
        body.append("<p><strong>RxIncredible</strong> - Medical & Pharmacy Services</p>\n");
        body.append("<p>Phone: 9822848689 | Email: contact@rxincredible.com | Website: www.rxincredible.com</p>\n");
        body.append("</div>\n");
        body.append("</div>\n");
        body.append("</div>\n");
        body.append("</body></html>\n");
        return body.toString();
    }

    /**
     * Send email with bill PDF attachment and payment/rejection links
     */
    public void sendBillEmail(Order order, String pdfFilePath) {
        User user = order.getUser();
        String toEmail = user != null ? user.getEmail() : null;
        if (toEmail == null || toEmail.isEmpty()) {
            toEmail = order.getUserEmail();
        }

        if (toEmail == null || toEmail.isEmpty()) {
            logger.warn("Cannot send email: User email not found for order {}.", order.getId());
            throw new RuntimeException("Patient email not found for this order");
        }

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}.", toEmail);
            throw new RuntimeException("Invalid patient email: " + toEmail);
        }

        // Check if email is a test/internal email that can't receive mail
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}.", toEmail);
            throw new RuntimeException("Email delivery is blocked for this recipient: " + toEmail);
        }

        String subject = "Your Prescription Bill is Ready - Order " + order.getOrderNumber();

        String body = buildBillEmailBody(order, user);

        // Always use HTML email format
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("RxIncredible <info@rxincredible.com>");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, true); // Enable HTML

            // Try to attach PDF if file path is provided
            if (pdfFilePath != null && !pdfFilePath.isEmpty()) {
                File pdfFile = new File(pdfFilePath);
                logger.info("Checking for bill PDF at path: {}", pdfFile.getAbsolutePath());

                if (pdfFile.exists()) {
                    helper.addAttachment("Bill_" + order.getOrderNumber() + ".pdf", pdfFile);
                    logger.info("Bill PDF attached successfully: {}", pdfFile.getAbsolutePath());
                } else {
                    // Try alternative path formats
                    logger.warn("PDF file not found at: {}. Trying alternative paths...", pdfFile.getAbsolutePath());

                    // Try with forward slashes
                    File altPdfFile1 = new File(pdfFilePath.replace("\\", "/"));
                    if (altPdfFile1.exists()) {
                        helper.addAttachment("Bill_" + order.getOrderNumber() + ".pdf", altPdfFile1);
                        logger.info("Bill PDF attached from alternative path: {}", altPdfFile1.getAbsolutePath());
                    } else {
                        // Try with backward slashes
                        File altPdfFile2 = new File(pdfFilePath.replace("/", "\\"));
                        if (altPdfFile2.exists()) {
                            helper.addAttachment("Bill_" + order.getOrderNumber() + ".pdf", altPdfFile2);
                            logger.info("Bill PDF attached from alternative path: {}", altPdfFile2.getAbsolutePath());
                        } else {
                            logger.warn(
                                    "Bill PDF file not found in any path format. Email will be sent without attachment.");
                        }
                    }
                }
            } else {
                logger.warn("No PDF file path provided for order {}. Email will be sent without attachment.",
                        order.getId());
            }

            mailSender.send(message);
            logger.info("Bill email sent to: {}", toEmail);

        } catch (MessagingException e) {
            logger.error("Failed to send bill email: {}", e.getMessage());
            // Try sending HTML without attachment
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true);
                helper.setFrom("RxIncredible <info@rxincredible.com>");
                helper.setTo(toEmail);
                helper.setSubject(subject);
                helper.setText(body, true);

                mailSender.send(message);
                logger.info("Bill email (HTML fallback) sent to: {}", toEmail);
            } catch (Exception ex) {
                logger.error("Failed to send bill email: {}", ex.getMessage());
                throw new RuntimeException("Failed to send bill email: " + ex.getMessage(), ex);
            }
        } catch (Exception e) {
            logger.error("Failed to send bill email: {}", e.getMessage());
            throw new RuntimeException("Failed to send bill email: " + e.getMessage(), e);
        }
    }

    private String buildBillEmailBody(Order order, User user) {
        // Send to login page first, then redirect to shipping after login
        String loginUrl = frontendUrl + "/login?redirect=/user/shipping/" + order.getId();
        String rejectUrl = frontendUrl + "/user/reject/" + order.getId();

        StringBuilder body = new StringBuilder();
        body.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>\n");
        body.append("<div style='max-width: 600px; margin: 0 auto; padding: 20px;'>\n");

        // Header
        body.append(
                "<div style='background: linear-gradient(135deg, #1e3a8a, #2563eb); padding: 20px; border-radius: 10px 10px 0 0;'>\n");
        body.append("<h1 style='color: white; margin: 0;'>RxIncredible</h1>\n");
        body.append("<p style='color: #e0e7ff; margin: 5px 0 0 0;'>Medical & Pharmacy Services</p>\n");
        body.append("</div>\n");

        // Content
        body.append("<div style='background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px;'>\n");
        String displayName = "Customer";
        if (user != null && user.getFullName() != null && !user.getFullName().isBlank()) {
            displayName = user.getFullName();
        }

        body.append("<h2 style='color: #1e3a8a;'>Your Prescription Bill is Ready!</h2>\n");
        body.append("<p>Dear <strong>").append(displayName).append("</strong>,</p>\n");
        body.append("<p>Your prescription bill has been generated. Please find the details below:</p>\n");

        // Order Details Table
        body.append("<table style='width: 100%; border-collapse: collapse; margin: 20px 0;'>\n");
        body.append(
                "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Order Number:</strong></td>\n");
        body.append("<td style='padding: 10px; border-bottom: 1px solid #ddd;'>").append(order.getOrderNumber())
                .append("</td></tr>\n");
        body.append(
                "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Service Type:</strong></td>\n");
        body.append("<td style='padding: 10px; border-bottom: 1px solid #ddd;'>").append(order.getServiceType())
                .append("</td></tr>\n");

        if (order.getTotalAmount() != null) {
            body.append(
                    "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Total Amount:</strong></td>\n");
            body.append(
                    "<td style='padding: 10px; border-bottom: 1px solid #ddd; font-size: 18px; color: #16a34a; font-weight: bold;'>");
            body.append("₹").append(order.getTotalAmount()).append("</td></tr>\n");
        }
        body.append("</table>\n");

        // Action Buttons
        body.append("<div style='margin: 30px 0; text-align: center;'>\n");
        body.append("<a href='").append(loginUrl).append("' ");
        body.append(
                "style='display: inline-block; background: #16a34a; color: white; padding: 15px 30px; text-decoration: none; border-radius: 8px; font-weight: bold; margin-right: 10px;'>\n");
        body.append("Pay Now</a>\n");

        body.append("<a href='").append(rejectUrl).append("' ");
        body.append(
                "style='display: inline-block; background: #dc2626; color: white; padding: 15px 30px; text-decoration: none; border-radius: 8px; font-weight: bold;'>\n");
        body.append("Reject Bill</a>\n");
        body.append("</div>\n");

        body.append("<p style='color: #6b7280; font-size: 14px;'>\n");
        body.append(
                "Please note: If you do not respond within 7 days, the order will be automatically cancelled.</p>\n");

        body.append("<p>Thank you for choosing RxIncredible!</p>\n");

        // Footer
        body.append(
                "<div style='margin-top: 30px; padding-top: 20px; border-top: 1px solid #e5e7eb; color: #6b7280; font-size: 12px;'>\n");
        body.append("<p><strong>RxIncredible</strong> - Medical & Pharmacy Services</p>\n");
        body.append("<p>Phone: 9822848689 | Email: contact@rxincredible.com | Website: www.rxincredible.com</p>\n");
        body.append("</div>\n");

        body.append("</div>\n");
        body.append("</div>\n");
        body.append("</body>\n");
        body.append("</html>\n");

        return body.toString();
    }

    // Helper method to validate email format
    private boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Send invoice email with PDF attachment
     * Subject: "Your Invoice - Rxincredible"
     */
    public void sendInvoiceEmail(String toEmail, String invoiceNo, byte[] pdfBytes) {
        if (toEmail == null || toEmail.isEmpty()) {
            logger.warn("Cannot send invoice email: Email not provided");
            return;
        }

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send invoice: Invalid email format {}", toEmail);
            return;
        }

        // Check for test/internal emails
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") ||
                toEmail.endsWith("@example.com") || toEmail.endsWith("@googlemail.com") ||
                toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send invoice to test/internal email address: {}", toEmail);
            return;
        }

        String subject = "Your Invoice - Rxincredible";
        String body = buildInvoiceEmailBody(invoiceNo);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("RxIncredible <info@rxincredible.com>");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, true);

            // Attach PDF if provided
            if (pdfBytes != null && pdfBytes.length > 0) {
                DataSource dataSource = new ByteArrayDataSource(pdfBytes, "application/pdf");
                helper.addAttachment("Invoice_" + invoiceNo + ".pdf", dataSource);
                logger.info("Invoice PDF attached successfully");
            }

            mailSender.send(message);
            logger.info("Invoice email sent successfully to: {}", toEmail);

        } catch (MessagingException e) {
            logger.error("Failed to send invoice email: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to send invoice email: {}", e.getMessage());
        }
    }

    private String buildInvoiceEmailBody(String invoiceNo) {
        StringBuilder body = new StringBuilder();
        body.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>\n");
        body.append("<div style='max-width: 600px; margin: 0 auto; padding: 20px;'>\n");

        // Header
        body.append(
                "<div style='background: linear-gradient(135deg, #2F5D9F, #3d73c7); padding: 20px; border-radius: 10px 10px 0 0;'>\n");
        body.append("<h1 style='color: white; margin: 0;'>RxIncredible</h1>\n");
        body.append("<p style='color: #e0e7ff; margin: 5px 0 0 0;'>Bhagyavati Drugs and Chemical Pvt. India</p>\n");
        body.append("</div>\n");

        // Content
        body.append("<div style='background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px;'>\n");
        body.append("<h2 style='color: #2F5D9F;'>Your Invoice is Ready!</h2>\n");
        body.append("<p>Dear Customer,</p>\n");
        body.append("<p>Thank you for your business! Please find your invoice details below:</p>\n");

        // Invoice Details
        body.append("<table style='width: 100%; border-collapse: collapse; margin: 20px 0;'>\n");
        body.append(
                "<tr><td style='padding: 10px; border-bottom: 1px solid #ddd;'><strong>Invoice Number:</strong></td>\n");
        body.append("<td style='padding: 10px; border-bottom: 1px solid #ddd;'>").append(invoiceNo)
                .append("</td></tr>\n");
        body.append("</table>\n");

        body.append("<p>Please find the invoice PDF attached to this email.</p>\n");
        body.append("<p>Thank you for choosing RxIncredible!</p>\n");

        // Footer
        body.append(
                "<div style='margin-top: 30px; padding-top: 20px; border-top: 1px solid #e5e7eb; color: #6b7280; font-size: 12px;'>\n");
        body.append("<p><strong>RxIncredible</strong> - Medical & Pharmacy Services</p>\n");
        body.append("<p>Bhagyavati Drugs and Chemical Pvt. India</p>\n");
        body.append("<p>Phone: 9822848689 | Email: contact@rxincredible.com | Website: www.rxincredible.com</p>\n");
        body.append("</div>\n");

        body.append("</div>\n");
        body.append("</div>\n");
        body.append("</body>\n");
        body.append("</html>\n");

        return body.toString();
    }

    /**
     * Send email to doctor when a patient is assigned to them
     */
    public void sendPatientAssignedToDoctorEmail(User doctor, User patient) {
        if (doctor == null || doctor.getEmail() == null || doctor.getEmail().isEmpty()) {
            logger.warn("Cannot send email: Doctor or email not found.");
            return;
        }

        String toEmail = doctor.getEmail();

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}. Email will not be sent.", toEmail);
            return;
        }

        // Check if email is a test/internal email that can't receive mail
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}. Email will not be sent.", toEmail);
            return;
        }

        String subject = "New Patient Assigned - RxIncredible";
        String body = buildPatientAssignedToDoctorEmailBody(doctor, patient);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("RxIncredible <info@rxincredible.com>");
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            logger.info("Patient assigned email sent to doctor: {}", toEmail);
        } catch (Exception e) {
            logger.error("Failed to send patient assigned email to doctor: {}", e.getMessage());
        }
    }

    private String buildPatientAssignedToDoctorEmailBody(User doctor, User patient) {
        StringBuilder body = new StringBuilder();
        body.append("Dear Dr. ").append(doctor.getFullName()).append(",\n\n");
        body.append("A new patient has been assigned to you on RxIncredible.\n\n");
        body.append("Patient Details:\n");
        body.append("-------------\n");
        body.append("Name: ").append(patient.getFullName()).append("\n");
        body.append("Email: ").append(patient.getEmail()).append("\n");
        if (patient.getPhone() != null && !patient.getPhone().isEmpty()) {
            body.append("Phone: ").append(patient.getPhone()).append("\n");
        }
        if (patient.getAddress() != null && !patient.getAddress().isEmpty()) {
            body.append("Address: ").append(patient.getAddress()).append("\n");
        }
        if (patient.getAge() != null && patient.getAge() > 0) {
            body.append("Age: ").append(patient.getAge()).append("\n");
        }
        if (patient.getGender() != null && !patient.getGender().isEmpty()) {
            body.append("Gender: ").append(patient.getGender()).append("\n");
        }

        body.append("\n");
        body.append(
                "Please login to your doctor portal to view the patient details and provide medical assistance.\n\n");
        body.append("Thank you for being a part of RxIncredible!\n\n");
        body.append("Best regards,\n");
        body.append("RxIncredible Team\n");
        body.append("Medical & Pharmacy Services");

        return body.toString();
    }

    /**
     * Send email to doctor/analyst when an order is assigned to them
     */
    public void sendOrderAssignedToProfessionalEmail(Order order, User professional, String professionalType) {
        if (professional == null || professional.getEmail() == null || professional.getEmail().isEmpty()) {
            logger.warn("Cannot send email: Professional or email not found for order {}. Email will not be sent.",
                    order.getId());
            return;
        }

        String toEmail = professional.getEmail();

        // Validate email format
        if (!isValidEmail(toEmail)) {
            logger.warn("Cannot send email: Invalid email format {}. Email will not be sent.", toEmail);
            return;
        }

        // Check if email is a test/internal email that can't receive mail
        if (toEmail.endsWith("@rxincredible.com") || toEmail.endsWith("@test.com") || toEmail.endsWith("@example.com")
                || toEmail.endsWith("@googlemail.com") || toEmail.contains("mailer-daemon")) {
            logger.warn("Cannot send email to test/internal email address: {}. Email will not be sent.", toEmail);
            return;
        }

        String subject = "New Patient Order Assigned - Order " + order.getOrderNumber();
        String body = buildOrderAssignedToProfessionalEmailBody(order, professional, professionalType);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("RxIncredible <info@rxincredible.com>");
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            logger.info("Order assigned email sent to {}: {}", professionalType, toEmail);
        } catch (Exception e) {
            logger.error("Failed to send order assigned email to {}: {}", professionalType, e.getMessage());
        }
    }

    private String buildOrderAssignedToProfessionalEmailBody(Order order, User professional, String professionalType) {
        StringBuilder body = new StringBuilder();
        String title = "ANALYST".equals(professionalType) ? "Analyst" : "Doctor";
        body.append("Dear ").append(title).append(" ").append(professional.getFullName()).append(",\n\n");
        body.append("A new patient order has been assigned to you on RxIncredible.\n\n");
        body.append("Order Details:\n");
        body.append("-------------\n");
        body.append("Order Number: ").append(order.getOrderNumber()).append("\n");
        body.append("Service Type: ").append(order.getServiceType()).append("\n");
        if (order.getPriority() != null && !order.getPriority().isEmpty()) {
            body.append("Priority: ").append(order.getPriority()).append("\n");
        }
        body.append("Status: ").append(order.getStatus()).append("\n\n");

        User patient = order.getUser();
        if (patient != null) {
            body.append("Patient Details:\n");
            body.append("-------------\n");
            body.append("Name: ").append(patient.getFullName()).append("\n");
            body.append("Email: ").append(patient.getEmail()).append("\n");
            if (patient.getPhone() != null && !patient.getPhone().isEmpty()) {
                body.append("Phone: ").append(patient.getPhone()).append("\n");
            }
            if (patient.getAge() != null && patient.getAge() > 0) {
                body.append("Age: ").append(patient.getAge()).append("\n");
            }
            if (patient.getGender() != null && !patient.getGender().isEmpty()) {
                body.append("Gender: ").append(patient.getGender()).append("\n");
            }
        }

        body.append("\n");
        body.append("Please login to your portal to view the order details and provide medical assistance.\n\n");
        body.append("Thank you for being a part of RxIncredible!\n\n");
        body.append("Best regards,\n");
        body.append("RxIncredible Team\n");
        body.append("Medical & Pharmacy Services");

        return body.toString();
    }
}