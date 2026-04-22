package com.rxincredible.controller;

import com.rxincredible.entity.Document;
import com.rxincredible.service.DocumentService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    
    private final DocumentService documentService;
    
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }
    
    // Upload single file using multipart
    @PostMapping("/upload")
    public ResponseEntity<Document> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") Long userId,
            @RequestParam("category") String category,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "description", required = false) String description) throws IOException {
        
        Document document = documentService.uploadDocument(file, userId, category, orderId, description);
        return ResponseEntity.ok(document);
    }
    
    // Upload multiple files as base64 (for frontend sending JSON with base64 data)
    @PostMapping("/upload-base64")
    public ResponseEntity<?> uploadDocumentsBase64(
            @RequestBody Map<String, Object> payload) {
        
        try {
            // Log request payload (without full base64)
            System.out.println("[upload-base64] Received request payload keys: " + payload.keySet());
            System.out.println("[upload-base64] Full payload: " + payload);
            
            // Validate userId is present and not null
            if (payload.get("userId") == null) {
                System.err.println("[upload-base64] Error: userId is null");
                return ResponseEntity.badRequest().body(Map.of("error", "userId is required and cannot be null"));
            }
            
            Long userId;
            try {
                userId = Long.valueOf(payload.get("userId").toString());
            } catch (NumberFormatException e) {
                System.err.println("[upload-base64] Error: Invalid userId format: " + payload.get("userId"));
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid userId format"));
            }
            
            // Validate files array is present and not null
            if (payload.get("files") == null) {
                System.err.println("[upload-base64] Error: files array is null");
                return ResponseEntity.badRequest().body(Map.of("error", "files array is required and cannot be null"));
            }
            
            List<Map<String, Object>> files;
            try {
                files = (List<Map<String, Object>>) payload.get("files");
            } catch (ClassCastException e) {
                System.err.println("[upload-base64] Error: Invalid files format");
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid files array format"));
            }
            
            if (files.isEmpty()) {
                System.err.println("[upload-base64] Error: files array is empty");
                return ResponseEntity.badRequest().body(Map.of("error", "files array cannot be empty"));
            }
            
            Long orderId = payload.get("orderId") != null ? Long.valueOf(payload.get("orderId").toString()) : null;
            
            System.out.println("[upload-base64] Processing " + files.size() + " files for userId: " + userId + ", orderId: " + orderId);
            
            List<Document> documents = new java.util.ArrayList<>();
            
            for (int i = 0; i < files.size(); i++) {
                Map<String, Object> fileData = files.get(i);
                
                // Log file info (without full base64)
                System.out.println("[upload-base64] Processing file " + (i + 1) + ": name=" + fileData.get("name") + ", type=" + fileData.get("type") + ", size=" + fileData.get("size") + ", category=" + fileData.get("category"));
                
                // Validate required fields
                if (fileData.get("name") == null) {
                    System.err.println("[upload-base64] Error: File name is null for file " + (i + 1));
                    return ResponseEntity.badRequest().body(Map.of("error", "File name is required for file " + (i + 1)));
                }
                
                if (fileData.get("content") == null) {
                    System.err.println("[upload-base64] Error: File content (base64) is null for file " + (i + 1));
                    return ResponseEntity.badRequest().body(Map.of("error", "File content (base64) is required for file " + (i + 1)));
                }
                
                String fileName = (String) fileData.get("name");
                String mimeType = (String) fileData.get("type");
                
                // Validate and parse file size
                Long fileSize;
                try {
                    fileSize = fileData.get("size") != null ? Long.valueOf(fileData.get("size").toString()) : null;
                } catch (NumberFormatException e) {
                    System.err.println("[upload-base64] Error: Invalid file size format for file " + (i + 1) + ": " + fileData.get("size"));
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid file size format for file " + (i + 1)));
                }
                
                // Validate file size (max 10MB)
                if (fileSize != null && fileSize > 10 * 1024 * 1024) {
                    System.err.println("[upload-base64] Error: File size exceeds 10MB limit for file " + (i + 1) + ": " + fileSize + " bytes");
                    return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 10MB limit for file " + (i + 1)));
                }
                
                String base64Data = (String) fileData.get("content");
                String category = (String) fileData.get("category");
                // Set default category if not provided
                if (category == null || category.trim().isEmpty()) {
                    category = "OTHER";
                    System.out.println("[upload-base64] Warning: Category not provided for file " + (i + 1) + ", using default 'OTHER'");
                }
                String description = (String) fileData.get("description");
                
                try {
                    Document document = documentService.saveDocumentFromBase64(
                            fileName, fileName, mimeType, fileSize, base64Data, userId, category, orderId, description
                    );
                    documents.add(document);
                    System.out.println("[upload-base64] Successfully saved document: " + document.getId());
                } catch (Exception e) {
                    System.err.println("[upload-base64] Error saving file " + (i + 1) + ": " + e.getMessage());
                    e.printStackTrace();
                    return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save file " + (i + 1) + ": " + e.getMessage()));
                }
            }
            
            System.out.println("[upload-base64] Successfully uploaded " + documents.size() + " documents");
            return ResponseEntity.ok(documents);
            
        } catch (Exception e) {
            System.err.println("[upload-base64] Unexpected error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }
    
    @GetMapping("/all")
    public ResponseEntity<List<Document>> getAllDocuments() {
        return ResponseEntity.ok(documentService.getAllDocuments());
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Document>> getDocumentsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(documentService.getDocumentsByUserId(userId));
    }
    
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<Document>> getDocumentsByOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(documentService.getDocumentsByOrderId(orderId));
    }
    
    @GetMapping("/user/{userId}/category/{category}")
    public ResponseEntity<List<Document>> getDocumentsByUserAndCategory(
            @PathVariable Long userId, 
            @PathVariable String category) {
        return ResponseEntity.ok(documentService.getDocumentsByUserIdAndCategory(userId, category));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocumentById(@PathVariable Long id) {
        return documentService.getDocumentById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    // Download file by ID - returns the file for download
    @GetMapping("/{id}/download")
    public ResponseEntity<?> downloadFile(@PathVariable Long id) {
        return documentService.getDocumentById(id)
                .map(doc -> {
                    try {
                        Path filePath = getFilePath(doc);
                        Resource resource = new UrlResource(filePath.toUri());
                        
                        System.out.println("Download - ID: " + id + ", filePath: " + doc.getFilePath() + ", resolved: " + filePath + ", exists: " + resource.exists());
                        
                        if (resource.exists() || doc.getFileData() != null) {
                            String contentType = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
                            
                            // If file data is stored as base64 in database
                            if (doc.getFileData() != null && doc.getFileData().length() > 0) {
                                byte[] fileBytes = java.util.Base64.getDecoder().decode(doc.getFileData());
                                return ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(contentType))
                                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                                            "attachment; filename=\"" + doc.getOriginalFileName() + "\"")
                                        .body(fileBytes);
                            }
                            
                            // If file is stored on disk
                            if (resource.exists()) {
                                return ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(contentType))
                                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                                            "attachment; filename=\"" + doc.getOriginalFileName() + "\"")
                                        .body(resource);
                            }
                            
                            return ResponseEntity.notFound().build();
                        } else {
                            return ResponseEntity.notFound().build();
                        }
                    } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                .body(Map.of("error", "Error downloading file: " + e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    // View file inline (for preview in browser)
    @GetMapping("/{id}/view")
    public ResponseEntity<?> viewFile(@PathVariable Long id) {
        return documentService.getDocumentById(id)
                .map(doc -> {
                    try {
                        Path filePath = getFilePath(doc);
                        Resource resource = new UrlResource(filePath.toUri());
                        
                        if (resource.exists() || doc.getFileData() != null) {
                            String contentType = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
                            
                            // If file data is stored as base64 in database
                            if (doc.getFileData() != null && doc.getFileData().length() > 0) {
                                byte[] fileBytes = java.util.Base64.getDecoder().decode(doc.getFileData());
                                return ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(contentType))
                                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                                            "inline; filename=\"" + doc.getOriginalFileName() + "\"")
                                        .body(fileBytes);
                            }
                            
                            // If file is stored on disk
                            if (resource.exists()) {
                                return ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(contentType))
                                        .header(HttpHeaders.CONTENT_DISPOSITION, 
                                            "inline; filename=\"" + doc.getOriginalFileName() + "\"")
                                        .body(resource);
                            }
                            
                            return ResponseEntity.notFound().build();
                        } else {
                            return ResponseEntity.notFound().build();
                        }
                    } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                .body(Map.of("error", "Error viewing file: " + e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    // Helper method to get file path
    private Path getFilePath(Document doc) throws MalformedURLException {
        String filePath = doc.getFilePath();
        if (filePath == null) {
            return Paths.get(documentService.getUploadDirectory(), doc.getFileName());
        }
        
        // Try different path formats
        if (filePath.startsWith("/uploads/documents/")) {
            String relativePath = filePath.replace("/uploads/documents/", "");
            return Paths.get(documentService.getUploadDirectory(), relativePath);
        } else if (filePath.startsWith("/uploads/")) {
            String relativePath = filePath.replace("/uploads/", "");
            return Paths.get(documentService.getUploadDirectory(), relativePath);
        } else if (filePath.startsWith("uploads/")) {
            String relativePath = filePath.replace("uploads/", "");
            return Paths.get(documentService.getUploadDirectory(), relativePath);
        }
        
        // If path doesn't match expected formats, try as-is
        return Paths.get(documentService.getUploadDirectory(), filePath);
    }
    
    // Get document with file data as base64
    @GetMapping("/{id}/data")
    public ResponseEntity<?> getDocumentData(@PathVariable Long id) {
        return documentService.getDocumentById(id)
                .map(doc -> {
                    try {
                        // Read file and convert to base64
                        String filePath = doc.getFilePath();
                        System.out.println("Fetching document data for ID: " + id + ", filePath: " + filePath);
                        
                        // Try different path formats
                        String relativePath = null;
                        
                        if (filePath != null) {
                            // Try various path formats
                            if (filePath.startsWith("/uploads/documents/")) {
                                relativePath = filePath.replace("/uploads/documents/", "");
                            } else if (filePath.startsWith("/uploads/")) {
                                relativePath = filePath.replace("/uploads/", "");
                            } else if (filePath.startsWith("uploads/")) {
                                relativePath = filePath.replace("uploads/", "");
                            } else {
                                relativePath = filePath;
                            }
                            
                            System.out.println("Looking for file at: " + documentService.getUploadDirectory() + "/" + relativePath);
                            
                            java.nio.file.Path fullPath = java.nio.file.Paths.get(
                                documentService.getUploadDirectory(), relativePath);
                            
                            System.out.println("Full path: " + fullPath + ", exists: " + java.nio.file.Files.exists(fullPath));
                            
                            if (java.nio.file.Files.exists(fullPath)) {
                                byte[] fileBytes = java.nio.file.Files.readAllBytes(fullPath);
                                String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);
                                
                                return ResponseEntity.ok((Object)Map.of(
                                    "document", doc,
                                    "fileData", base64
                                ));
                            }
                        }
                        
                        // Check if fileData is stored in database directly
                        if (doc.getFileData() != null && !doc.getFileData().isEmpty()) {
                            return ResponseEntity.ok((Object)Map.of(
                                "document", doc,
                                "fileData", doc.getFileData()
                            ));
                        }
                        return ResponseEntity.ok((Object)Map.of("document", doc));
                    } catch (Exception e) {
                        System.out.println("Error fetching document data: " + e.getMessage());
                        e.printStackTrace();
                        return ResponseEntity.ok((Object)Map.of("document", doc));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<Document> updateDocumentStatus(
            @PathVariable Long id, 
            @RequestParam String status) {
        return ResponseEntity.ok(documentService.updateDocumentStatus(id, status));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ResponseEntity.ok().build();
    }
    
    @PutMapping("/{documentId}/order/{orderId}")
    public ResponseEntity<Document> linkDocumentToOrder(
            @PathVariable Long documentId, 
            @PathVariable Long orderId) {
        documentService.linkDocumentToOrder(documentId, orderId);
        return documentService.getDocumentById(documentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
