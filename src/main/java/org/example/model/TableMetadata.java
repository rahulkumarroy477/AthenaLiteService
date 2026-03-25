package org.example.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class TableMetadata {

    private String userId;
    private String tableName;
    private String s3RawKey;
    private String s3ParquetKey;
    private String status; // PROCESSING, READY, FAILED
    private String columns; // JSON array string: [{"name":"id","type":"BIGINT"},...]
    private String createdAt;

    @DynamoDbPartitionKey
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @DynamoDbSortKey
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getS3RawKey() { return s3RawKey; }
    public void setS3RawKey(String s3RawKey) { this.s3RawKey = s3RawKey; }

    public String getS3ParquetKey() { return s3ParquetKey; }
    public void setS3ParquetKey(String s3ParquetKey) { this.s3ParquetKey = s3ParquetKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getColumns() { return columns; }
    public void setColumns(String columns) { this.columns = columns; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
