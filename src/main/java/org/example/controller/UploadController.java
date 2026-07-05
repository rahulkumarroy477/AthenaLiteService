package org.example.controller;

import org.example.service.S3Service;
import org.example.util.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);
    private static final Set<String> ALLOWED = Set.of("csv", "json", "parquet");
    private final S3Service s3Service;

    public UploadController(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @GetMapping("/upload/presigned-url")
    public ResponseEntity<Map<String, Object>> getPresignedUrl(
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "tableName", required = false) String customTableName) {

        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing fileName"));
        }

        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED.contains(extension)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported file type: " + extension));
        }

        String tableName = customTableName != null && !customTableName.isBlank()
                ? InputValidator.sanitizeTableName(customTableName)
                : InputValidator.sanitizeTableName(fileName.replaceAll("\\.[^.]+$", ""));

        if (!InputValidator.isValidTableName(tableName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid table name"));
        }
        if (!InputValidator.isValidUserId(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid userId"));
        }

        String s3Key = "raw/" + userId + "/" + tableName + "." + extension;
        String contentType = switch (extension) {
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            default -> "application/octet-stream";
        };

        String presignedUrl = s3Service.generatePresignedPutUrl(s3Key, contentType);
        log.info("Generated presigned URL for {}", s3Key);

        return ResponseEntity.ok(Map.of(
                "presignedUrl", presignedUrl,
                "s3Key", s3Key,
                "table", tableName
        ));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "tableName", required = false) String customTableName) throws IOException {

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing filename"));
        }

        String extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED.contains(extension)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported file type: " + extension));
        }

        String tableName = customTableName != null && !customTableName.isBlank()
                ? InputValidator.sanitizeTableName(customTableName)
                : InputValidator.sanitizeTableName(originalName.replaceAll("\\.[^.]+$", ""));

        if (!InputValidator.isValidTableName(tableName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid table name"));
        }
        if (!InputValidator.isValidUserId(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid userId"));
        }
        String s3Key = "raw/" + userId + "/" + tableName + "." + extension;

        s3Service.uploadFile(s3Key, file.getBytes(), file.getContentType());
        log.info("Uploaded {} to {}", originalName, s3Key);

        // Microservice 2 (S3 trigger) handles: parse columns, convert to parquet, write DDB metadata
        return ResponseEntity.ok(Map.of(
                "success", true,
                "table", tableName,
                "status", "PROCESSING"
        ));
    }
}
