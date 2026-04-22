package com.rxincredible.service;

import com.rxincredible.entity.Document;
import com.rxincredible.entity.Order;
import com.rxincredible.entity.User;
import com.rxincredible.repository.DocumentRepository;
import com.rxincredible.repository.OrderRepository;
import com.rxincredible.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {
    
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    
    @Value("${app.upload.directory}")
    private String uploadDirectory;
    
    public DocumentService(DocumentRepository documentRepository, UserRepository userRepository, OrderRepository orderRepository) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }
    
    @Transactional
    public Document uploadDocument(MultipartFile file, Long userId, String category, Long orderId, String description) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Order order = null;
        if (orderId != null) {
            order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found"));
        }
        
        // Generate unique filename
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString() + extension;
        
        // Create upload directory based on order or user or category
        Path uploadPath;
        String filePathStr;
        if (category != null && category.equals("BILL")) {
            // Create folder for bills - but still link to order if available
            uploadPath = Paths.get(uploadDirectory, "bill");
            filePathStr = "/uploads/documents/bill/" + uniqueFileName;
        } else if (orderId != null) {
            // Create folder for each order
            uploadPath = Paths.get(uploadDirectory, "order_" + orderId);
            filePathStr = "/uploads/documents/order_" + orderId + "/" + uniqueFileName;
        } else {
            // Create folder for user if no order
            uploadPath = Paths.get(uploadDirectory, "user_" + userId);
            filePathStr = "/uploads/documents/user_" + userId + "/" + uniqueFileName;
        }
        
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        
        // Save file to the upload directory
        Path filePath = uploadPath.resolve(uniqueFileName);
        Files.copy(file.getInputStream(), filePath);
        
        // Determine file type
        String fileType = "OTHER";
        String mimeType = file.getContentType();
        if (mimeType != null) {
            if (mimeType.equals("application/pdf")) {
                fileType = "PDF";
            } else if (mimeType.startsWith("image/")) {
                fileType = "IMAGE";
            }
        }
        
        Document document = new Document();
        document.setFileName(uniqueFileName);
        document.setOriginalFileName(originalFilename);
        document.setFileType(fileType);
        document.setMimeType(mimeType);
        document.setFileSize(file.getSize());
        // Store the file path (relative path for portability)
        document.setFilePath(filePathStr);
        document.setCategory(category);
        document.setUser(user);
        // Always link to order if orderId is provided
        if (orderId != null) {
            document.setOrder(order);
        }
        document.setStatus("UPLOADED");
        document.setDescription(description);
        
        return documentRepository.save(document);
    }
    
    @Transactional
    public Document saveDocumentFromBase64(String fileName, String originalFileName, String mimeType, 
            Long fileSize, String fileData, Long userId, String category, Long orderId, String description) {
        
        System.out.println("[saveDocumentFromBase64] Starting save for file: " + fileName + ", userId: " + userId);
        
        // Validate file size (max 10MB)
        if (fileSize != null && fileSize > 10 * 1024 * 1024) {
            System.err.println("[saveDocumentFromBase64] File size exceeds 10MB limit: " + fileSize + " bytes");
            throw new IllegalArgumentException("File size exceeds 10MB limit");
        }
        
        // Validate base64 data is not null or empty
        if (fileData == null || fileData.trim().isEmpty()) {
            System.err.println("[saveDocumentFromBase64] Base64 data is null or empty");
            throw new IllegalArgumentException("Base64 data is required");
        }
        
        // Validate required fields
        if (fileName == null || fileName.trim().isEmpty()) {
            System.err.println("[saveDocumentFromBase64] File name is null or empty");
            throw new IllegalArgumentException("File name is required");
        }
        
        if (userId == null) {
            System.err.println("[saveDocumentFromBase64] User ID is null");
            throw new IllegalArgumentException("User ID is required");
        }
        
        // Set default category if not provided
        if (category == null || category.trim().isEmpty()) {
            category = "OTHER";
            System.out.println("[saveDocumentFromBase64] Warning: Category not provided, using default 'OTHER'");
        }
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    System.err.println("[saveDocumentFromBase64] User not found: " + userId);
                    return new RuntimeException("User not found");
                });
        
        Order order = null;
        if (orderId != null) {
            order = orderRepository.findById(orderId)
                    .orElseThrow(() -> {
                        System.err.println("[saveDocumentFromBase64] Order not found: " + orderId);
                        return new RuntimeException("Order not found");
                    });
        }
        
        // Generate unique filename
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString() + extension;
        
        // Create upload directory based on order or user or category
        Path uploadPath;
        String filePathStr;
        if (category != null && category.equals("BILL")) {
            // Create folder for bills - but still link to order if available
            uploadPath = Paths.get(uploadDirectory, "bill");
            filePathStr = "/uploads/documents/bill/" + uniqueFileName;
        } else if (orderId != null) {
            // Create folder for each order
            uploadPath = Paths.get(uploadDirectory, "order_" + orderId);
            filePathStr = "/uploads/documents/order_" + orderId + "/" + uniqueFileName;
        } else {
            // Create folder for user if no order
            uploadPath = Paths.get(uploadDirectory, "user_" + userId);
            filePathStr = "/uploads/documents/user_" + userId + "/" + uniqueFileName;
        }
        
        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                System.out.println("[saveDocumentFromBase64] Created upload directory: " + uploadPath);
            }
            
            // Decode base64 and save file to the upload directory
            // Handle data URL prefix if present (e.g., "data:image/jpeg;base64,")
            String base64Data = fileData;
            if (fileData != null && fileData.contains(",")) {
                base64Data = fileData.substring(fileData.indexOf(",") + 1);
                System.out.println("[saveDocumentFromBase64] Stripped data URL prefix from base64");
            }
            
            // Safely decode base64 with try-catch
            byte[] decodedBytes;
            try {
                decodedBytes = Base64.getDecoder().decode(base64Data);
                System.out.println("[saveDocumentFromBase64] Successfully decoded base64, size: " + decodedBytes.length + " bytes");
            } catch (IllegalArgumentException e) {
                System.err.println("[saveDocumentFromBase64] Invalid base64 data: " + e.getMessage());
                throw new IllegalArgumentException("Invalid base64 data: " + e.getMessage(), e);
            }
            
            // Validate decoded file size matches reported size (with 10% tolerance for encoding overhead)
            if (fileSize != null) {
                long decodedSize = decodedBytes.length;
                long sizeDifference = Math.abs(decodedSize - fileSize);
                double tolerance = fileSize * 0.1; // 10% tolerance
                if (sizeDifference > tolerance) {
                    System.err.println("[saveDocumentFromBase64] Decoded size mismatch: reported=" + fileSize + ", actual=" + decodedSize);
                    throw new IllegalArgumentException("Decoded file size does not match reported size");
                }
            }
            
            Path filePath = uploadPath.resolve(uniqueFileName);
            Files.write(filePath, decodedBytes);
            System.out.println("[saveDocumentFromBase64] Saved file to: " + filePath);
            
            // Determine file type
            String fileType = "OTHER";
            if (mimeType != null) {
                if (mimeType.equals("application/pdf")) {
                    fileType = "PDF";
                } else if (mimeType.startsWith("image/")) {
                    fileType = "IMAGE";
                }
            }
            
            Document document = new Document();
            document.setFileName(uniqueFileName);
            document.setOriginalFileName(originalFileName);
            document.setFileType(fileType);
            document.setMimeType(mimeType);
            document.setFileSize(fileSize != null ? fileSize : (long) decodedBytes.length);
            // Store the file path (relative path for portability)
            document.setFilePath(filePathStr);
            document.setCategory(category);
            document.setUser(user);
            // Always link to order if orderId is provided
            if (orderId != null) {
                document.setOrder(order);
            }
            document.setStatus("UPLOADED");
            document.setDescription(description);
            
            Document savedDocument = documentRepository.save(document);
            System.out.println("[saveDocumentFromBase64] Successfully saved document with ID: " + savedDocument.getId());
            return savedDocument;
            
        } catch (IOException e) {
            System.err.println("[saveDocumentFromBase64] IOException while saving file: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to save file: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // Re-throw validation errors
            throw e;
        } catch (Exception e) {
            System.err.println("[saveDocumentFromBase64] Unexpected error: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Unexpected error while saving file: " + e.getMessage(), e);
        }
    }
    
    public List<Document> getAllDocuments() {
        return documentRepository.findAllWithUser();
    }
    
    public List<Document> getDocumentsByUserId(Long userId) {
        return documentRepository.findByUserId(userId);
    }
    
    public List<Document> getDocumentsByOrderId(Long orderId) {
        return documentRepository.findByOrderId(orderId);
    }
    
    public List<Document> getDocumentsByUserIdAndCategory(Long userId, String category) {
        return documentRepository.findByUserIdAndCategory(userId, category);
    }
    
    public Optional<Document> getDocumentById(Long id) {
        return documentRepository.findById(id);
    }
    
    @Transactional
    public Document updateDocumentStatus(Long id, String status) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        document.setStatus(status);
        return documentRepository.save(document);
    }
    
    @Transactional
    public void deleteDocument(Long id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // Delete the file from filesystem
        if (document.getFilePath() != null) {
            try {
                // Extract the folder and filename from the path
                String filePath = document.getFilePath();
                // Remove /uploads/documents/ prefix and get the actual path
                String relativePath = filePath.replace("/uploads/documents/", "");
                Path fullPath = Paths.get(uploadDirectory, relativePath);
                Files.deleteIfExists(fullPath);
            } catch (IOException e) {
                // Log warning but don't fail the delete
                System.err.println("Failed to delete file: " + e.getMessage());
            }
        }
        
        documentRepository.deleteById(id);
    }
    
    @Transactional
    public void linkDocumentToOrder(Long documentId, Long orderId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        document.setOrder(order);
        documentRepository.save(document);
    }
    
    public String getUploadDirectory() {
        return uploadDirectory;
    }
}
