package org.example.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.TableMetadata;
import org.example.util.InputValidator;
import org.example.service.DynamoService;
import org.example.service.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tables")
public class TableController {

    private static final Logger log = LoggerFactory.getLogger(TableController.class);
    private final DynamoService dynamoService;
    private final S3Service s3Service;
    private final ObjectMapper mapper;

    public TableController(DynamoService dynamoService, S3Service s3Service, ObjectMapper mapper) {
        this.dynamoService = dynamoService;
        this.s3Service = s3Service;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<?> listTables(
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        if (!InputValidator.isValidUserId(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid userId"));
        }
        return ResponseEntity.ok(dynamoService.getTablesForUser(userId).stream()
                .map(t -> Map.of(
                        "name", t.getTableName(),
                        "database", "default",
                        "status", t.getStatus(),
                        "createdAt", t.getCreatedAt()
                )).toList());
    }

    @GetMapping("/{tableName}/metadata")
    public ResponseEntity<?> getMetadata(
            @PathVariable String tableName,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        if (!InputValidator.isValidUserId(userId) || !InputValidator.isValidTableName(tableName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid input"));
        }

        TableMetadata meta = dynamoService.getTable(userId, tableName);
        if (meta == null) return ResponseEntity.notFound().build();

        try {
            List<Map<String, String>> columns = mapper.readValue(meta.getColumns(), new TypeReference<>() {});
            return ResponseEntity.ok(Map.of("table", meta.getTableName(), "status", meta.getStatus(), "columns", columns));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("table", meta.getTableName(), "status", meta.getStatus(), "columns", List.of()));
        }
    }

    @DeleteMapping("/{tableName}")
    public ResponseEntity<?> deleteTable(
            @PathVariable String tableName,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        if (!InputValidator.isValidUserId(userId) || !InputValidator.isValidTableName(tableName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid input"));
        }

        TableMetadata meta = dynamoService.getTable(userId, tableName);
        if (meta == null) return ResponseEntity.notFound().build();

        log.info("Deleting table '{}' for user '{}'", tableName, userId);
        try { s3Service.deleteFile(meta.getS3RawKey()); } catch (Exception e) {
            log.warn("Failed to delete S3 raw key for table '{}': {}", tableName, e.getMessage());
        }
        try { s3Service.deleteFile(meta.getS3ParquetKey()); } catch (Exception e) {
            log.warn("Failed to delete S3 parquet key for table '{}': {}", tableName, e.getMessage());
        }
        dynamoService.deleteTable(userId, tableName);

        return ResponseEntity.ok(Map.of("success", true, "deleted", tableName));
    }

    @PutMapping("/{tableName}/rename")
    public ResponseEntity<?> renameTable(
            @PathVariable String tableName,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam("newName") String newName) {
        if (!InputValidator.isValidUserId(userId) || !InputValidator.isValidTableName(tableName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid input"));
        }

        String sanitized = InputValidator.sanitizeTableName(newName);
        if (!InputValidator.isValidTableName(sanitized)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid table name"));
        }

        TableMetadata existing = dynamoService.getTable(userId, tableName);
        if (existing == null) return ResponseEntity.notFound().build();

        if (dynamoService.getTable(userId, sanitized) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "table already exists"));
        }

        log.info("Renaming table '{}' to '{}' for user '{}'", tableName, sanitized, userId);

        // Copy S3 files to new keys
        String rawExt = existing.getS3RawKey().substring(existing.getS3RawKey().lastIndexOf('.'));
        String newRawKey = "raw/" + userId + "/" + sanitized + rawExt;
        String newParquetKey = "parquet/" + userId + "/" + sanitized + ".parquet";

        s3Service.copyFile(existing.getS3RawKey(), newRawKey);
        s3Service.copyFile(existing.getS3ParquetKey(), newParquetKey);

        // Create new DynamoDB record
        TableMetadata renamed = new TableMetadata();
        renamed.setUserId(userId);
        renamed.setTableName(sanitized);
        renamed.setS3RawKey(newRawKey);
        renamed.setS3ParquetKey(newParquetKey);
        renamed.setStatus(existing.getStatus());
        renamed.setColumns(existing.getColumns());
        renamed.setCreatedAt(existing.getCreatedAt());
        dynamoService.saveTable(renamed);

        // Delete old S3 files + DynamoDB record
        try { s3Service.deleteFile(existing.getS3RawKey()); } catch (Exception e) {
            log.warn("Failed to delete old S3 raw key during rename: {}", e.getMessage());
        }
        try { s3Service.deleteFile(existing.getS3ParquetKey()); } catch (Exception e) {
            log.warn("Failed to delete old S3 parquet key during rename: {}", e.getMessage());
        }
        dynamoService.deleteTable(userId, tableName);

        return ResponseEntity.ok(Map.of("success", true, "oldName", tableName, "newName", sanitized));
    }
}
