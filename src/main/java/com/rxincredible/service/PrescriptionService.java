package com.rxincredible.service;

import com.rxincredible.entity.Prescription;
import com.rxincredible.entity.User;
import com.rxincredible.repository.PrescriptionRepository;
import com.rxincredible.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class PrescriptionService {
    
    private final PrescriptionRepository prescriptionRepository;
    private final UserRepository userRepository;
    
    @Value("${app.upload.directory:uploads/documents}")
    private String uploadDirectory;
    
    public PrescriptionService(PrescriptionRepository prescriptionRepository, UserRepository userRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.userRepository = userRepository;
    }
    
    @Transactional
    public Prescription createPrescription(Prescription prescription, Long userId, Long doctorId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        
        prescription.setUser(user);
        prescription.setDoctor(doctor);
        prescription.setStatus("PENDING");
        
        return prescriptionRepository.save(prescription);
    }
    
    public List<Prescription> findAllPrescriptions() {
        return prescriptionRepository.findAll();
    }
    
    public Optional<Prescription> findById(Long id) {
        return prescriptionRepository.findById(id);
    }
    
    public List<Prescription> findByUserId(Long userId) {
        return prescriptionRepository.findUserPrescriptions(userId);
    }
    
    public List<Prescription> findByDoctorId(Long doctorId) {
        return prescriptionRepository.findByDoctorId(doctorId);
    }
    
    public List<Prescription> findByStatus(String status) {
        return prescriptionRepository.findByStatusOrderByCreatedAtDesc(status);
    }
    
    @Transactional
    public Prescription updateStatus(Long id, String status) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        prescription.setStatus(status);
        return prescriptionRepository.save(prescription);
    }
    
    @Transactional
    public void deletePrescription(Long id) {
        prescriptionRepository.deleteById(id);
    }
    
    // Additional methods for doctor and admin
    
    public List<Prescription> findPendingPrescriptionsForDoctor(Long doctorId) {
        return prescriptionRepository.findByDoctorIdAndStatus(doctorId, "PENDING");
    }
    
    public List<Prescription> findDraftPrescriptionsForDoctor(Long doctorId) {
        return prescriptionRepository.findByDoctorIdAndStatus(doctorId, "DRAFT");
    }
    
    public List<Prescription> findPendingUnassignedPrescriptions() {
        return prescriptionRepository.findByDoctorIdIsNullAndStatus("PENDING");
    }
    
    public List<Prescription> findDraftPrescriptionsForAnalyst(Long analystId) {
        return prescriptionRepository.findByAnalystIdAndStatus(analystId, "DRAFT");
    }
    
    @Transactional
    public Prescription assignDoctorToPrescription(Long prescriptionId, Long doctorId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        
        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        
        if (!"DOCTOR".equals(doctor.getRole())) {
            throw new RuntimeException("User is not a doctor");
        }
        
        prescription.setDoctor(doctor);
        return prescriptionRepository.save(prescription);
    }
    
    // Generate prescription PDF
    public String generatePrescriptionPdf(Long prescriptionId) throws IOException {
        
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found with id: " + prescriptionId));
        
        System.out.println("=== PRESCRIPTION PDF SERVICE START ===");
        System.out.println("Prescription ID: " + prescriptionId);
        
        // Use path relative to the backend directory
        String baseDir = System.getProperty("user.dir");
        String uploadDir = baseDir + "/uploads/documents";
        
        File dir = new File(uploadDir);
        System.out.println("Using directory: " + dir.getAbsolutePath());
        
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        String fileName = "prescription_" + prescription.getId() + "_" + System.currentTimeMillis() + ".pdf";
        String filePath = uploadDir + "/" + fileName;
        
        System.out.println("File path: " + filePath);
        
        // Create the PDF
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        
        PDType1Font fontBold = PDType1Font.HELVETICA_BOLD;
        PDType1Font fontRegular = PDType1Font.HELVETICA;
        
        // Colors
        PDColor darkBlue = new PDColor(new float[] {0.12f, 0.23f, 0.54f}, PDDeviceRGB.INSTANCE);
        PDColor lightBlue = new PDColor(new float[] {0.94f, 0.96f, 1.0f}, PDDeviceRGB.INSTANCE);
        PDColor gray = new PDColor(new float[] {0.5f, 0.5f, 0.5f}, PDDeviceRGB.INSTANCE);
        
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        
        // Dark Blue Header Background
        contentStream.setNonStrokingColor(darkBlue);
        contentStream.addRect(0, 700, 595, 100);
        contentStream.fill();
        
        // Header Text (White)
        contentStream.setNonStrokingColor(1, 1, 1);
        contentStream.beginText();
        contentStream.setFont(fontBold, 28);
        contentStream.newLineAtOffset(50, 755);
        contentStream.showText("RxIncredible");
        contentStream.endText();
        
        contentStream.setNonStrokingColor(0.9f, 0.9f, 0.9f);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 12);
        contentStream.newLineAtOffset(52, 735);
        contentStream.showText("Medical & Pharmacy Services");
        contentStream.endText();
        
        // Prescription Title on Header
        contentStream.setNonStrokingColor(1, 1, 1);
        contentStream.beginText();
        contentStream.setFont(fontBold, 20);
        contentStream.newLineAtOffset(380, 765);
        contentStream.showText("PRESCRIPTION");
        contentStream.endText();
        
        // Prescription ID and Date
        String dateStr = prescription.getCreatedAt() != null ? 
            prescription.getCreatedAt().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")) : 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
        
        contentStream.setNonStrokingColor(darkBlue);
        contentStream.beginText();
        contentStream.setFont(fontBold, 12);
        contentStream.newLineAtOffset(50, 675);
        contentStream.showText("Prescription ID: #" + prescription.getId());
        contentStream.endText();
        
        contentStream.setNonStrokingColor(0.4f, 0.4f, 0.4f);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 11);
        contentStream.newLineAtOffset(400, 675);
        contentStream.showText("Date: " + dateStr);
        contentStream.endText();
        
        // Draw separator line
        contentStream.setStrokingColor(darkBlue);
        contentStream.setLineWidth(2);
        contentStream.moveTo(50, 665);
        contentStream.lineTo(545, 665);
        contentStream.stroke();
        
        // Patient Info Section - Light blue background
        contentStream.setNonStrokingColor(lightBlue);
        contentStream.addRect(45, 580, 505, 80);
        contentStream.fill();
        
        User patient = prescription.getUser();
        String patientName = patient != null ? patient.getFullName() : "N/A";
        String patientAge = patient != null && patient.getAge() != null ? patient.getAge().toString() : "N/A";
        String patientGender = patient != null ? patient.getGender() : "N/A";
        String patientPhone = patient != null ? patient.getPhone() : "N/A";
        String patientEmail = patient != null ? patient.getEmail() : "N/A";
        
        // Patient Info Header
        contentStream.setNonStrokingColor(darkBlue);
        contentStream.beginText();
        contentStream.setFont(fontBold, 14);
        contentStream.newLineAtOffset(55, 645);
        contentStream.showText("Patient Information");
        contentStream.endText();
        
        // Patient details
        contentStream.setNonStrokingColor(0.2f, 0.2f, 0.2f);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 11);
        contentStream.newLineAtOffset(55, 620);
        contentStream.showText("Name: " + patientName + "          Age: " + patientAge + " years          Gender: " + patientGender);
        contentStream.endText();
        
        contentStream.beginText();
        contentStream.setFont(fontRegular, 11);
        contentStream.newLineAtOffset(55, 600);
        contentStream.showText("Phone: " + patientPhone + "          Email: " + patientEmail);
        contentStream.endText();
        
        // Doctor Info Section
        contentStream.setNonStrokingColor(lightBlue);
        contentStream.addRect(45, 530, 505, 50);
        contentStream.fill();
        
        User doctor = prescription.getDoctor();
        String doctorName = doctor != null ? doctor.getFullName() : "N/A";
        String doctorSpec = doctor != null ? doctor.getSpecialization() : "General Medicine";
        
        contentStream.setNonStrokingColor(darkBlue);
        contentStream.beginText();
        contentStream.setFont(fontBold, 14);
        contentStream.newLineAtOffset(55, 565);
        contentStream.showText("Doctor Information");
        contentStream.endText();
        
        contentStream.setNonStrokingColor(0.2f, 0.2f, 0.2f);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 11);
        contentStream.newLineAtOffset(55, 545);
        contentStream.showText("Dr. " + doctorName + "          Specialization: " + doctorSpec);
        contentStream.endText();
        
        // Draw separator line
        contentStream.setStrokingColor(darkBlue);
        contentStream.setLineWidth(1);
        contentStream.moveTo(50, 515);
        contentStream.lineTo(545, 515);
        contentStream.stroke();
        
        // Rx Symbol
        contentStream.setNonStrokingColor(darkBlue);
        contentStream.beginText();
        contentStream.setFont(fontBold, 48);
        contentStream.newLineAtOffset(50, 460);
        contentStream.showText("Rx");
        contentStream.endText();
        
        // Prescription Details (medicines)
        if (prescription.getPrescriptionDetails() != null && !prescription.getPrescriptionDetails().isEmpty()) {
            contentStream.setNonStrokingColor(darkBlue);
            contentStream.beginText();
            contentStream.setFont(fontBold, 14);
            contentStream.newLineAtOffset(50, 430);
            contentStream.showText("Prescription Details:");
            contentStream.endText();
            
            // Draw prescription box
            contentStream.setStrokingColor(darkBlue);
            contentStream.setLineWidth(1);
            contentStream.addRect(45, 280, 505, 140);
            contentStream.stroke();
            
            // Split prescription details into multiple lines if needed
            String[] lines = prescription.getPrescriptionDetails().split("\n");
            int yPos = 400;
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                contentStream.setNonStrokingColor(0.15f, 0.15f, 0.15f);
                contentStream.beginText();
                contentStream.setFont(fontRegular, 11);
                contentStream.newLineAtOffset(55, yPos);
                contentStream.showText(line);
                contentStream.endText();
                yPos -= 18;
                if (yPos < 295) break;
            }
        }
        
        // Recommendations
        if (prescription.getRecommendations() != null && !prescription.getRecommendations().isEmpty()) {
            contentStream.setNonStrokingColor(darkBlue);
            contentStream.beginText();
            contentStream.setFont(fontBold, 14);
            contentStream.newLineAtOffset(50, 265);
            contentStream.showText("Recommendations:");
            contentStream.endText();
            
            String[] lines = prescription.getRecommendations().split("\n");
            int yPos = 245;
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                contentStream.setNonStrokingColor(0.2f, 0.2f, 0.2f);
                contentStream.beginText();
                contentStream.setFont(fontRegular, 11);
                contentStream.newLineAtOffset(55, yPos);
                contentStream.showText(line);
                contentStream.endText();
                yPos -= 18;
            }
        }
        
        // Signature Section
        contentStream.setStrokingColor(darkBlue);
        contentStream.setLineWidth(1);
        contentStream.moveTo(380, 120);
        contentStream.lineTo(545, 120);
        contentStream.stroke();
        
        contentStream.setNonStrokingColor(darkBlue);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 10);
        contentStream.newLineAtOffset(380, 105);
        contentStream.showText("Authorized Signature");
        contentStream.endText();
        
        contentStream.beginText();
        contentStream.setFont(fontBold, 11);
        contentStream.newLineAtOffset(380, 88);
        contentStream.showText("Dr. " + doctorName);
        contentStream.endText();
        
        // Footer
        contentStream.setNonStrokingColor(gray);
        contentStream.beginText();
        contentStream.setFont(fontRegular, 9);
        contentStream.newLineAtOffset(170, 40);
        contentStream.showText("Generated by RxIncredible - Your Trusted Online Pharmacy");
        contentStream.endText();
        
        contentStream.close();
        
        // Save
        document.save(filePath);
        document.close();
        
        System.out.println("PDF saved: " + filePath);
        System.out.println("=== PRESCRIPTION PDF SERVICE END ===");
        
        return fileName;
    }
    
    public String getUploadDirectory() {
        return uploadDirectory;
    }
    
    @Transactional
    public Prescription updatePrescriptionFilePath(Long id, String filePath) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        prescription.setFilePath(filePath);
        return prescriptionRepository.save(prescription);
    }
}
