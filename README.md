# AthenaLiteService

API gateway microservice for AthenaLite — a serverless SQL query engine. Built with Spring Boot 4, deployed on AWS Lambda via API Gateway.

## Architecture Role

This is the central orchestrator. It handles file uploads, table metadata, and query dispatch. It does **not** execute queries — it pushes jobs to SQS for the QueryWorker to process.

## API Endpoints

### Upload
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/upload` | Upload CSV/JSON/Parquet file to S3 (`raw/` prefix). Accepts optional `tableName` param. |

### Tables
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/tables` | List all tables for a user |
| GET | `/api/tables/{name}/metadata` | Get table columns and status |

### Query
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/query` | Submit a SQL query (async, returns `queryId`) |
| GET | `/api/query/status/{queryId}` | Poll query execution status |
| GET | `/api/query/results/{queryId}` | Download query result CSV |

### Health
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ping` | Health check |

All endpoints accept `userId` as a query parameter (defaults to `default`).

## AWS Resources

| Resource | Name |
|----------|------|
| S3 Bucket | `athenalite-data-ap` |
| DynamoDB | `AthenaLiteTables` (table metadata) |
| DynamoDB | `AthenaLiteQueryMetadata` (query tracking) |
| SQS Queue | `AthenaLiteQueryQueue` |

## Environment Variables

| Variable | Value |
|----------|-------|
| `S3_BUCKET` | `athenalite-data-ap` |
| `DYNAMODB_TABLE` | `AthenaLiteTables` |
| `DYNAMODB_QUERY_TABLE` | `AthenaLiteQueryMetadata` |
| `QUERY_QUEUE_URL` | SQS queue URL |

`AWS_REGION` is set automatically by Lambda.

## Build & Deploy

```bash
mvn clean package
# Upload target/AthenaLiteService-1.0-SNAPSHOT-lambda-package.zip to Lambda
```

Lambda handler: `org.example.StreamLambdaHandler::handleRequest`
Runtime: Java 17

## Data Flow

```
User uploads file → S3 (raw/) → FileProcessor Lambda → Parquet + DynamoDB metadata
User runs query   → SQS queue → QueryWorker Lambda → Results CSV in S3
User polls status → DynamoDB → Downloads CSV
```

## Tech Stack

- Spring Boot 4
- AWS Lambda + API Gateway
- AWS S3 (SDK v2)
- AWS DynamoDB Enhanced Client
- AWS SQS
- Jackson
