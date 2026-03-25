package org.example.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.TableMetadata;
import org.example.service.DynamoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tables")
public class TableController {

    private final DynamoService dynamoService;
    private final ObjectMapper mapper;

    public TableController(DynamoService dynamoService, ObjectMapper mapper) {
        this.dynamoService = dynamoService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listTables(
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
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
        TableMetadata meta = dynamoService.getTable(userId, tableName);
        if (meta == null) return ResponseEntity.notFound().build();

        try {
            List<String> columns = mapper.readValue(meta.getColumns(), new TypeReference<>() {});
            return ResponseEntity.ok(Map.of("table", meta.getTableName(), "status", meta.getStatus(), "columns", columns));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("table", meta.getTableName(), "status", meta.getStatus(), "columns", List.of()));
        }
    }
}
