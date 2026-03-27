package org.example.service;

import org.example.model.QueryMetadata;
import org.example.model.TableMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.List;

@Service
public class DynamoService {

    private final DynamoDbTable<TableMetadata> tableMetadata;
    private final DynamoDbTable<QueryMetadata> queryMetadata;

    public DynamoService(@Value("${aws.s3.region}") String region,
                         @Value("${aws.dynamodb.table}") String tableName,
                         @Value("${aws.dynamodb.query-table}") String queryTableName) {
        DynamoDbClient client = DynamoDbClient.builder().region(Region.of(region)).build();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
        this.tableMetadata = enhanced.table(tableName, TableSchema.fromBean(TableMetadata.class));
        this.queryMetadata = enhanced.table(queryTableName, TableSchema.fromBean(QueryMetadata.class));
    }

    // Table metadata operations
    public void saveTable(TableMetadata metadata) {
        tableMetadata.putItem(metadata);
    }

    public List<TableMetadata> getTablesForUser(String userId) {
        return tableMetadata.query(QueryConditional.keyEqualTo(k -> k.partitionValue(userId)))
                .items().stream().toList();
    }

    public TableMetadata getTable(String userId, String tableName) {
        return tableMetadata.getItem(r -> r.key(k -> k.partitionValue(userId).sortValue(tableName)));
    }

    public void deleteTable(String userId, String tableName) {
        tableMetadata.deleteItem(r -> r.key(k -> k.partitionValue(userId).sortValue(tableName)));
    }

    // Query metadata operations
    public void saveQuery(QueryMetadata metadata) {
        queryMetadata.putItem(metadata);
    }

    public QueryMetadata getQuery(String userId, String queryId) {
        return queryMetadata.getItem(r -> r.key(k -> k.partitionValue(userId).sortValue(queryId)));
    }
}
