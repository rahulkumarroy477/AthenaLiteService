# AthenaLite-FileProcessor

S3-triggered Python Lambda that processes uploaded data files. Converts raw files to Parquet and registers table metadata in DynamoDB.

## Architecture Role

Triggered automatically when a file lands in `s3://athenalite-data-ap/raw/`. It:

1. Downloads the raw file (CSV, JSON, or Parquet)
2. Parses column names and types using pandas
3. Converts to Parquet format (if not already)
4. Uploads Parquet to `s3://athenalite-data-ap/parquet/{userId}/{tableName}.parquet`
5. Writes table metadata to DynamoDB with status `READY`

If processing fails, metadata is written with status `FAILED`.

## S3 Key Structure

```
raw/{userId}/{tableName}.{csv|json|parquet}      ← input (from upload)
parquet/{userId}/{tableName}.parquet               ← output (this Lambda)
```

## DynamoDB Record Written

Table: `AthenaLiteTables`

| Field | Example |
|-------|---------|
| userId | `user@example.com` |
| tableName | `sales_data` |
| s3RawKey | `raw/user@example.com/sales_data.csv` |
| s3ParquetKey | `parquet/user@example.com/sales_data.parquet` |
| status | `READY` or `FAILED` |
| columns | `[{"name":"id","type":"BIGINT"},...]` |
| createdAt | ISO timestamp |

## Environment Variables

| Variable | Value |
|----------|-------|
| `S3_BUCKET` | `athenalite-data-ap` |
| `DYNAMODB_TABLE` | `AthenaLiteTables` |

## Lambda Configuration

| Setting | Value |
|---------|-------|
| Runtime | Python 3.12 |
| Handler | `handler.handler` |
| Memory | 1024 MB |
| Timeout | 120 seconds |
| Layer | `AWSSDKPandas-Python312` |
| Trigger | S3 `s3:ObjectCreated:*` on prefix `raw/` |

## Dependencies

- pandas
- pyarrow
- boto3 (included in Lambda runtime)
