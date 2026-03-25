package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.QueryJob;
import org.example.model.QueryMetadata;
import org.example.model.TableMetadata;
import org.example.service.DynamoService;
import org.example.service.S3Service;
import org.example.service.SqsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);
    private final SqsService sqsService;
    private final DynamoService dynamoService;
    private final S3Service s3Service;
    private final ObjectMapper mapper;

    public QueryController(SqsService sqsService, DynamoService dynamoService,
                           S3Service s3Service, ObjectMapper mapper) {
        this.sqsService = sqsService;
        this.dynamoService = dynamoService;
        this.s3Service = s3Service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<?> submitQuery(@RequestBody Map<String, String> request) throws Exception {
        String sql = request.get("query");
        String userId = request.getOrDefault("userId", "default");
        String tableName = request.getOrDefault("tableName", "");

        if (sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing 'query' field"));
        }

        TableMetadata meta = dynamoService.getTable(userId, tableName);
        if (meta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "table not found: " + tableName));
        }
        if (!"READY".equals(meta.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "table not ready, status: " + meta.getStatus()));
        }

        String queryId = "qr_" + System.currentTimeMillis();
        String resultKey = "results/" + userId + "/" + queryId + ".csv";

        // Save RUNNING status to DDB
        QueryMetadata qm = new QueryMetadata();
        qm.setUserId(userId);
        qm.setQueryId(queryId);
        qm.setStatus("RUNNING");
        dynamoService.saveQuery(qm);

        // Dispatch to SQS
        QueryJob job = new QueryJob(userId, tableName, sql, meta.getS3ParquetKey(), resultKey);
        sqsService.sendMessage(mapper.writeValueAsString(job));
        log.info("Dispatched query job {} for table {}", queryId, tableName);

        return ResponseEntity.ok(Map.of("queryId", queryId, "status", "RUNNING"));
    }

    @GetMapping("/status/{queryId}")
    public ResponseEntity<?> getStatus(
            @PathVariable String queryId,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        QueryMetadata qm = dynamoService.getQuery(userId, queryId);
        if (qm == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok(Map.of(
                "queryId", qm.getQueryId(),
                "status", qm.getStatus(),
                "executionTime", qm.getExecutionTime() != null ? qm.getExecutionTime() : "",
                "error", qm.getError() != null ? qm.getError() : ""
        ));
    }

    @GetMapping("/results/{queryId}")
    public ResponseEntity<byte[]> downloadResults(
            @PathVariable String queryId,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        String resultKey = "results/" + userId + "/" + queryId + ".csv";
        try {
            byte[] data = s3Service.downloadFile(resultKey);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + queryId + ".csv")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }
}
