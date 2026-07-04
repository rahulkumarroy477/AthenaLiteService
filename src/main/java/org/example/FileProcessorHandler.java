package org.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * S3-triggered Lambda that processes uploaded files:
 * 1. Downloads raw file from S3
 * 2. Parses column names from CSV/JSON header
 * 3. Copies file to parquet/ prefix (DuckDB reads CSV directly)
 * 4. Writes table metadata to DynamoDB with status READY
 */
public class FileProcessorHandler implements RequestHandler<S3Event, String> {

    private static final String BUCKET = System.getenv("S3_BUCKET") != null
            ? System.getenv("S3_BUCKET") : "athenalite-data-ap-south-1";
    private static final String TABLE_NAME = System.getenv("DYNAMODB_TABLE") != null
            ? System.getenv("DYNAMODB_TABLE") : "AthenaLiteTables";
    private static final Region REGION = Region.of(
            System.getenv("AWS_REGION") != null ? System.getenv("AWS_REGION") : "ap-south-1");

    private final S3Client s3;
    private final DynamoDbTable<org.example.model.TableMetadata> metadataTable;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileProcessorHandler() {
        this.s3 = S3Client.builder().region(REGION).build();
        DynamoDbClient dynamoClient = DynamoDbClient.builder().region(REGION).build();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoClient).build();
        this.metadataTable = enhanced.table(TABLE_NAME, TableSchema.fromBean(org.example.model.TableMetadata.class));
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
            String rawKey = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
            context.getLogger().log("Processing: " + rawKey);

            try {
                processFile(rawKey, context);
            } catch (Exception e) {
                context.getLogger().log("ERROR processing " + rawKey + ": " + e.getMessage());
                writeFailedMetadata(rawKey, e.getMessage());
            }
        }
        return "OK";
    }

    private void processFile(String rawKey, Context context) throws Exception {
        // Parse userId and tableName from key: raw/{userId}/{tableName}.{ext}
        // rawKey = "raw/user@example.com/sales_data.csv"
        String withoutPrefix = rawKey.substring("raw/".length()); // "user@example.com/sales_data.csv"
        int slashIdx = withoutPrefix.indexOf('/');
        String userId = withoutPrefix.substring(0, slashIdx);
        String fileName = withoutPrefix.substring(slashIdx + 1);
        String tableName = fileName.replaceAll("\\.[^.]+$", ""); // remove extension
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        context.getLogger().log("userId=" + userId + ", tableName=" + tableName + ", ext=" + extension);

        // Download the file
        byte[] fileContent = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(BUCKET).key(rawKey).build()).asByteArray();

        // Parse columns from first line (CSV) or first object (JSON)
        List<Map<String, String>> columns = parseColumns(fileContent, extension);

        // Copy to parquet/ prefix (DuckDB can read CSV directly via httpfs)
        String parquetKey = "parquet/" + userId + "/" + tableName + "." + extension;
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(BUCKET).sourceKey(rawKey)
                .destinationBucket(BUCKET).destinationKey(parquetKey).build());

        context.getLogger().log("Copied to " + parquetKey);

        // Write metadata to DynamoDB
        org.example.model.TableMetadata metadata = new org.example.model.TableMetadata();
        metadata.setUserId(userId);
        metadata.setTableName(tableName);
        metadata.setS3RawKey(rawKey);
        metadata.setS3ParquetKey(parquetKey);
        metadata.setStatus("READY");
        metadata.setColumns(objectMapper.writeValueAsString(columns));
        metadata.setCreatedAt(Instant.now().toString());

        metadataTable.putItem(metadata);
        context.getLogger().log("Metadata saved for table: " + tableName);
    }

    private List<Map<String, String>> parseColumns(byte[] content, String extension) throws Exception {
        List<Map<String, String>> columns = new ArrayList<>();

        if ("csv".equals(extension)) {
            // Read first line for headers
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
            String headerLine = reader.readLine();
            if (headerLine != null) {
                // Remove BOM if present
                if (headerLine.startsWith("\uFEFF")) {
                    headerLine = headerLine.substring(1);
                }
                String[] headers = headerLine.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (String header : headers) {
                    String name = header.trim().replaceAll("^\"|\"$", "");
                    columns.add(Map.of("name", name, "type", "VARCHAR"));
                }
            }
        } else if ("json".equals(extension)) {
            // Read first line/object for keys
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                if (line.contains("}")) break; // first complete object
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> firstObj = objectMapper.readValue(
                    sb.toString().replaceAll("^\\[", "").trim(), Map.class);
            for (String key : firstObj.keySet()) {
                columns.add(Map.of("name", key, "type", "VARCHAR"));
            }
        } else {
            // Parquet — can't easily parse without a library, just mark as unknown
            columns.add(Map.of("name", "unknown", "type", "UNKNOWN"));
        }

        return columns;
    }

    private void writeFailedMetadata(String rawKey, String error) {
        try {
            String withoutPrefix = rawKey.substring("raw/".length());
            int slashIdx = withoutPrefix.indexOf('/');
            String userId = withoutPrefix.substring(0, slashIdx);
            String fileName = withoutPrefix.substring(slashIdx + 1);
            String tableName = fileName.replaceAll("\\.[^.]+$", "");

            org.example.model.TableMetadata metadata = new org.example.model.TableMetadata();
            metadata.setUserId(userId);
            metadata.setTableName(tableName);
            metadata.setS3RawKey(rawKey);
            metadata.setStatus("FAILED");
            metadata.setCreatedAt(Instant.now().toString());

            metadataTable.putItem(metadata);
        } catch (Exception ignored) {}
    }
}
