# AthenaLite — Complete System Documentation

A serverless SQL query engine that lets you upload CSV/JSON/Parquet files and run SQL queries against them using DuckDB — all without managing any servers.

**Live:** Deployed on AWS (Lambda + S3 + DynamoDB) with a React frontend on Vercel.

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Tech Stack](#tech-stack)
3. [System Components](#system-components)
4. [Data Flow — End to End](#data-flow--end-to-end)
5. [Authentication Flow](#authentication-flow)
6. [File Upload Flow (Presigned URL)](#file-upload-flow-presigned-url)
7. [Query Execution Flow](#query-execution-flow)
8. [AWS Resources](#aws-resources)
9. [CI/CD Pipeline](#cicd-pipeline)
10. [API Reference](#api-reference)
11. [Database Schemas](#database-schemas)
12. [Performance Characteristics](#performance-characteristics)
13. [Cost & Scaling](#cost--scaling)
14. [Security](#security)
15. [Local Development](#local-development)
16. [Deployment](#deployment)

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              FRONTEND (Vercel)                                │
│                                                                              │
│   React 19 + TypeScript + Vite + Tailwind CSS + CodeMirror (SQL Editor)      │
│   AWS Amplify (Cognito Auth) + Framer Motion (Animations)                    │
└──────────────────────┬───────────────────────────────┬───────────────────────┘
                       │ API calls (JWT auth)           │ Direct S3 PUT
                       ▼                               ▼
┌─────────────────────────────────┐    ┌──────────────────────────────────┐
│        API Gateway (REST)        │    │         S3 Bucket                 │
│   Regional, Proxy to Lambda      │    │   athenalite-data-ap-south-1     │
└──────────────────┬───────────────┘    └──────────┬───────────────────────┘
                   │                               │ S3 Event (ObjectCreated)
                   ▼                               ▼
┌─────────────────────────────────┐    ┌──────────────────────────────────┐
│     AthenaLite-Service           │    │    AthenaLite-FileProcessor      │
│     (Spring Boot 4 + Lambda)     │    │    (Java 17, Lambda)             │
│                                  │    │                                  │
│  • GET  /api/tables              │    │  • Parse CSV/JSON headers        │
│  • GET  /api/upload/presigned-url│    │  • Copy to parquet/ prefix       │
│  • POST /api/query               │    │  • Write metadata to DynamoDB    │
│  • GET  /api/query/status/:id    │    │  • Status: READY or FAILED       │
│  • GET  /api/query/results/:id   │    │                                  │
└──────────┬───────────────────────┘    └──────────────────────────────────┘
           │ SQS SendMessage
           ▼
┌─────────────────────────────────┐
│       SQS: AthenaLiteQueryQueue  │
│       (Standard, 120s visibility)│
└──────────────────┬───────────────┘
                   │ Trigger (batch size 1)
                   ▼
┌─────────────────────────────────┐
│     AthenaLite-QueryWorker       │
│     (Java 21, Lambda + DuckDB)   │
│                                  │
│  • Load httpfs extension         │
│  • Create views from S3 files    │
│  • Execute SQL via DuckDB        │
│  • Write results CSV to S3       │
│  • Update status in DynamoDB     │
└──────────────────────────────────┘

┌─────────────────────────────────┐    ┌──────────────────────────────────┐
│    DynamoDB: AthenaLiteTables    │    │   DynamoDB: AthenaLiteQueries    │
│                                  │    │                                  │
│  PK: userId                      │    │  PK: userId                      │
│  SK: tableName                   │    │  SK: queryId                     │
│  • s3RawKey, s3ParquetKey        │    │  • status, executionTime, error  │
│  • columns (JSON), status        │    │  • ttl (auto-expire)             │
└──────────────────────────────────┘    └──────────────────────────────────┘
```

---

## Tech Stack

### Frontend
| Technology | Purpose |
|-----------|---------|
| React 19 | UI framework |
| TypeScript | Type safety |
| Vite | Build tool + dev server |
| Tailwind CSS 4 | Styling |
| CodeMirror | SQL editor with syntax highlighting |
| Framer Motion | Animations |
| react-resizable-panels | Split pane layout |
| react-dropzone | File drag-and-drop upload |
| AWS Amplify | Cognito authentication |

### Backend
| Technology | Purpose |
|-----------|---------|
| Java 17/21 | Lambda runtime |
| Spring Boot 4 | REST API framework |
| AWS Lambda | Serverless compute |
| AWS SDK v2 | S3, DynamoDB, SQS clients |
| DuckDB (JDBC) | In-memory SQL engine |
| httpfs extension | DuckDB reads S3 files directly |
| Jackson | JSON serialization |

### Infrastructure
| Service | Purpose |
|---------|---------|
| AWS Lambda | 3 functions (Service, FileProcessor, QueryWorker) |
| API Gateway | REST endpoint, proxy to Lambda |
| S3 | File storage (raw, processed, results) |
| DynamoDB | Metadata + query tracking |
| SQS | Async query dispatch |
| Cognito | User authentication |
| CloudWatch | Logging + billing alarm |

---

## System Components

### 1. AthenaLite-Service (API Gateway)
The central orchestrator. Handles all API requests: file upload URLs, table listing, query submission, and result retrieval.

- **Runtime:** Java 17 on Lambda
- **Framework:** Spring Boot 4 with `aws-serverless-java-container`
- **Handler:** `org.example.StreamLambdaHandler::handleRequest`
- **Memory:** 1024 MB | **Timeout:** 30s

### 2. AthenaLite-FileProcessor (S3 Trigger)
Processes uploaded files. Parses column metadata, copies to query-ready location, and registers the table.

- **Runtime:** Java 17 on Lambda
- **Handler:** `org.example.FileProcessorHandler::handleRequest`
- **Trigger:** S3 `ObjectCreated` on `raw/` prefix
- **Memory:** 1024 MB | **Timeout:** 120s

### 3. AthenaLite-QueryWorker (SQS Consumer)
Executes SQL queries using DuckDB. Reads files from S3 via httpfs, runs the query, writes results.

- **Runtime:** Java 21 on Lambda (requires GLIBC 2.28+ for DuckDB)
- **Handler:** `org.example.SqsQueryHandler::handleRequest`
- **Trigger:** SQS `AthenaLiteQueryQueue` (batch size 1)
- **Memory:** 1024 MB | **Timeout:** 120s
- **Key dependency:** DuckDB with httpfs extension (~119 MB zip)

---

## Data Flow — End to End

### Complete user journey from signup to query results:

```
1. USER SIGNS UP
   Browser → Cognito → Create account → Verify email → Get JWT token

2. USER UPLOADS FILE
   Browser → GET /api/upload/presigned-url (with JWT)
   Service → Generate S3 presigned PUT URL (5 min expiry)
   Browser → PUT file directly to S3 (up to 50 MB, bypasses API Gateway)
   File lands in: s3://bucket/raw/{userId}/{tableName}.{ext}

3. FILE PROCESSING (automatic)
   S3 event fires → AthenaLite-FileProcessor Lambda
   FileProcessor:
     • Downloads file from raw/
     • Parses column names from CSV header (or JSON keys)
     • Copies file to parquet/{userId}/{tableName}.{ext}
     • Writes to DynamoDB AthenaLiteTables:
       - userId, tableName, s3RawKey, s3ParquetKey
       - columns: [{"name":"col1","type":"VARCHAR"}, ...]
       - status: "READY"
       - createdAt: ISO timestamp

4. USER SUBMITS QUERY
   Browser → POST /api/query { userId, tableName, sql }
   Service:
     • Validates SQL (blocks dangerous functions)
     • Generates queryId (qr_{timestamp})
     • Creates result S3 key: results/{userId}/{queryId}.csv
     • Builds QueryJob: { userId, tableName, sql, s3ParquetKey, resultKey }
     • Sends QueryJob JSON to SQS
     • Writes to DynamoDB AthenaLiteQueries: status = "RUNNING"
     • Returns queryId to browser

5. QUERY EXECUTION (async)
   SQS triggers → AthenaLite-QueryWorker Lambda
   QueryWorker:
     • Starts in-memory DuckDB instance
     • Loads httpfs extension + configures S3 credentials
     • Loads ALL user's tables as views:
       CREATE VIEW "tableName" AS SELECT * FROM read_csv_auto('s3://...')
     • Executes user's SQL query
     • Converts ResultSet to CSV string
     • Uploads CSV to s3://bucket/results/{userId}/{queryId}.csv
     • Updates DynamoDB: status = "COMPLETED", executionTime = "Xms"
     • On failure: status = "FAILED", error = "message"

6. USER POLLS FOR RESULTS
   Browser → GET /api/query/status/{queryId} (polling every 2s)
   Service → Reads DynamoDB → Returns { status, executionTime, error }

7. USER DOWNLOADS RESULTS
   Browser → GET /api/query/results/{queryId}
   Service → Reads CSV from S3 → Returns JSON array to browser
   Frontend → Renders results in sortable table
```

---

## Authentication Flow

```
┌─────────┐         ┌──────────┐         ┌─────────────────┐
│ Browser │───1────▶│ Cognito  │         │ AthenaLite API  │
│         │◀──2─────│          │         │                 │
│         │───3─────────────────────────▶│                 │
│         │         │          │◀──4─────│ (verify JWT)    │
│         │◀──5─────────────────────────│                 │
└─────────┘         └──────────┘         └─────────────────┘

1. Sign up / Sign in (email + password)
2. Return JWT tokens (id_token, access_token, refresh_token)
3. API request with header: Authorization: Bearer {id_token}
4. Service validates JWT against Cognito JWKS endpoint
5. Return response (or 401 if invalid)
```

- **JWT Validation:** `JwtAuthFilter` fetches JWKS from Cognito, validates signature, expiry, audience
- **User ID extraction:** Email from JWT `email` claim used as `userId` for all operations
- **Token refresh:** Handled automatically by AWS Amplify on the frontend

---

## File Upload Flow (Presigned URL)

Why presigned URLs? API Gateway has a 10 MB payload limit, Lambda has 6 MB. Presigned URLs bypass both, allowing uploads up to 50 MB directly to S3.

```
Browser                    Service                     S3
  │                          │                          │
  │ GET /presigned-url       │                          │
  │ ?fileName=data.csv       │                          │
  │ &userId=user@email.com   │                          │
  │─────────────────────────▶│                          │
  │                          │ Generate presigned PUT   │
  │                          │ URL (5 min expiry)       │
  │◀─────────────────────────│                          │
  │ { presignedUrl, table }  │                          │
  │                          │                          │
  │ PUT file (up to 50 MB)   │                          │
  │─────────────────────────────────────────────────────▶│
  │                          │                          │ Store in raw/
  │◀─────────────────────────────────────────────────────│
  │ 200 OK                   │                          │
  │                          │              S3 Event fires
  │                          │                          │───▶ FileProcessor
```

---

## Query Execution Flow

```
Browser          Service          SQS            QueryWorker         S3          DynamoDB
  │                │               │                │                │              │
  │ POST /query    │               │                │                │              │
  │───────────────▶│               │                │                │              │
  │                │──send msg────▶│                │                │              │
  │                │───────────────────────────────────────────────────────────────▶│ write RUNNING
  │◀───────────────│               │                │                │              │
  │ { queryId }    │               │                │                │              │
  │                │               │──trigger──────▶│                │              │
  │                │               │                │──read file────▶│              │
  │                │               │                │◀───────────────│              │
  │                │               │                │ execute SQL     │              │
  │                │               │                │──write CSV─────▶│              │
  │                │               │                │──────────────────────────────▶│ write COMPLETED
  │                │               │                │                │              │
  │ GET /status    │               │                │                │              │
  │───────────────▶│───────────────────────────────────────────────────────────────▶│ read status
  │◀───────────────│               │                │                │              │
  │ { COMPLETED }  │               │                │                │              │
  │                │               │                │                │              │
  │ GET /results   │               │                │                │              │
  │───────────────▶│──────────────────────────────────────────────────read CSV─────▶│
  │◀───────────────│               │                │                │              │
  │ [results JSON] │               │                │                │              │
```

---

## AWS Resources

| Service | Resource Name | Configuration |
|---------|--------------|---------------|
| S3 | `athenalite-data-ap-south-1` | Lifecycle: raw/, parquet/, results/ expire in 1 day |
| DynamoDB | `AthenaLiteTables` | PK: userId, SK: tableName, On-demand |
| DynamoDB | `AthenaLiteQueries` | PK: userId, SK: queryId, On-demand |
| SQS | `AthenaLiteQueryQueue` | Standard, 120s visibility timeout |
| Lambda | `AthenaLite-Service` | Java 17, 1024 MB, 30s timeout |
| Lambda | `AthenaLite-FileProcessor` | Java 17, 1024 MB, 120s timeout |
| Lambda | `AthenaLite-QueryWorker` | Java 21, 1024 MB, 120s timeout |
| API Gateway | `AthenaLite-API` | REST, Regional, proxy {proxy+} → Service |
| Cognito | `AthenaLite-Users` | Email sign-in, no client secret |
| CloudWatch | `BillingAlarm-1USD` | Alert when charges exceed $1 |

### S3 Key Structure
```
athenalite-data-ap-south-1/
├── raw/{userId}/{tableName}.{csv|json|parquet}        ← uploaded files
├── parquet/{userId}/{tableName}.{csv|json|parquet}    ← query-ready files
├── results/{userId}/{queryId}.csv                     ← query results
└── lambda-deployments/                                ← Lambda code zips (no expiry)
    ├── AthenaLite-Service-{timestamp}.zip
    └── AthenaLite-QueryWorker-{timestamp}.zip
```

---

## CI/CD Pipeline

```
Developer pushes to main
        │
        ▼
┌─────────────────────────────────────────────────────┐
│              GitHub Actions (deploy.yml)              │
│                                                     │
│  1. Checkout code                                   │
│  2. Setup Java (Corretto 17/21)                     │
│  3. mvn clean package -DskipTests                   │
│  4. Upload zip to S3 (lambda-deployments/)          │
│  5. aws lambda update-function-code                 │
│     • AthenaLite-Service                            │
│     • AthenaLite-FileProcessor (same zip)           │
│     • AthenaLite-QueryWorker (separate zip)         │
└─────────────────────────────────────────────────────┘
        │
        ▼
  Lambda picks up new code on next invocation (no restart needed)
```

### Frontend (Vercel)
Push to `main` on `AthenaLiteUI` repo → Vercel auto-deploys.

### Backend (GitHub Actions)
- `AthenaLiteService` repo → deploys Service + FileProcessor
- `AthenaLiteQueryWorker` repo → deploys QueryWorker

### IAM: `github-deployer` user
Minimal permissions: S3 PutObject on `lambda-deployments/*` + `lambda:UpdateFunctionCode` on all 3 functions.

---

## API Reference

All endpoints require `Authorization: Bearer {jwt}` header (except `/ping`).

### Health
| Method | Endpoint | Response |
|--------|----------|----------|
| GET | `/ping` | `{"pong":"Hello, World!"}` |

### Upload
| Method | Endpoint | Params | Response |
|--------|----------|--------|----------|
| GET | `/api/upload/presigned-url` | `fileName`, `userId`, `tableName?` | `{ presignedUrl, s3Key, table }` |
| POST | `/api/upload` | FormData: `file`, `userId`, `tableName?` | `{ success, table, status }` (legacy, 6 MB limit) |

### Tables
| Method | Endpoint | Params | Response |
|--------|----------|--------|----------|
| GET | `/api/tables` | `userId` | `[{ tableName, status, columns, ... }]` |
| GET | `/api/tables/{name}/metadata` | `userId` | `{ tableName, columns, status, ... }` |

### Query
| Method | Endpoint | Body/Params | Response |
|--------|----------|-------------|----------|
| POST | `/api/query` | `{ userId, tableName, sql }` | `{ queryId }` |
| GET | `/api/query/status/{queryId}` | `userId` | `{ status, executionTime, error }` |
| GET | `/api/query/results/{queryId}` | `userId` | `[{ row1 }, { row2 }, ...]` |

---

## Database Schemas

### AthenaLiteTables
```json
{
  "userId": "user@email.com",        // Partition Key
  "tableName": "sales_data",         // Sort Key
  "s3RawKey": "raw/user/sales_data.csv",
  "s3ParquetKey": "parquet/user/sales_data.csv",
  "status": "READY",                 // PROCESSING | READY | FAILED
  "columns": "[{\"name\":\"id\",\"type\":\"VARCHAR\"},...]",
  "createdAt": "2026-07-04T07:50:04Z"
}
```

### AthenaLiteQueries
```json
{
  "userId": "user@email.com",        // Partition Key
  "queryId": "qr_1783260466622",     // Sort Key
  "status": "COMPLETED",             // RUNNING | COMPLETED | FAILED
  "executionTime": "1385ms",
  "error": "",                       // Error message if FAILED
  "ttl": 1783209600                  // Auto-expire (epoch seconds)
}
```

---

## Performance Characteristics

| Query Type | Warm Execution | Cold Start |
|-----------|---------------|------------|
| Simple COUNT | ~800ms | ~3.5s |
| GROUP BY aggregation | ~1.1-1.4s | ~3.5-4s |
| Window functions | ~1.5-1.6s | ~4s |
| Cross-table JOIN | ~2.2s | ~4.5s |
| Self-join (O(n²)) | ~20s | ~23s |

### File Size Limits
| File Size | Simple Query | Complex Query |
|-----------|-------------|---------------|
| 2 MB (~10K rows) | <1s | ~1.5s |
| 20 MB (~100K rows) | <5s | ~15s |
| 50 MB (~250K rows) | <10s | ~45s |

---

## Cost & Scaling

### Free Tier Coverage (12 months)
- Lambda: 1M requests/month — ✅
- S3: 5 GB storage — ✅ (lifecycle deletes user files after 1 day)
- DynamoDB: 25 GB, 25 RCU/WCU — ✅
- API Gateway: 1M calls/month — ✅
- Cognito: 50K MAU (always free) — ✅

### Billing Alarm
CloudWatch alarm triggers email at $1 threshold.

### Scaling Limits
- Concurrent queries: Limited by Lambda concurrency (default 1000)
- Max file size: 50 MB (Lambda timeout constraint)
- Max query time: 120 seconds
- Users: Unlimited (Cognito scales automatically)

---

## Security

| Layer | Mechanism |
|-------|-----------|
| Authentication | AWS Cognito (email + password, JWT tokens) |
| API Authorization | JWT validation via JWKS on every request |
| S3 Upload | Presigned URLs (5 min expiry, scoped to user prefix) |
| SQL Injection | Blocked dangerous functions (read_csv, write_parquet, http_get, etc.) |
| Data Isolation | All data keyed by userId — users can only see their own tables/queries |
| IAM | Least-privilege roles, separate deploy user with minimal permissions |
| Secrets | AWS credentials in GitHub Secrets, never in code |
| CORS | S3 CORS configured for browser direct uploads |

---

## Local Development

### Frontend
```bash
cd AthenaLite-Frontend
npm install
npm run dev          # http://localhost:3000
```

Update `.env` with:
```
VITE_API_BASE=https://<api-id>.execute-api.ap-south-1.amazonaws.com/Prod
VITE_COGNITO_USER_POOL_ID=<pool-id>
VITE_COGNITO_CLIENT_ID=<client-id>
```

### Backend (build only — runs on Lambda)
```bash
cd AthenaLiteService
mvn clean package    # Output: target/AthenaLiteService-1.0-SNAPSHOT-lambda-package.zip

cd AthenaLiteQueryWorker
mvn clean package    # Output: target/AthenaLiteQueryWorker-1.0-SNAPSHOT-lambda-package.zip
```

---

## Deployment

### First-time Setup
See `AWS-ACCOUNT-MIGRATION.md` for complete step-by-step resource creation.

### Subsequent Deploys
Just push to `main` — GitHub Actions handles everything:
```bash
git add . && git commit -m "your changes" && git push origin main
```

### Manual Deploy (emergency)
```bash
# Service (< 50 MB, direct upload)
aws lambda update-function-code \
  --function-name AthenaLite-Service \
  --zip-file fileb://target/AthenaLiteService-1.0-SNAPSHOT-lambda-package.zip

# QueryWorker (> 50 MB, via S3)
aws s3 cp target/AthenaLiteQueryWorker-1.0-SNAPSHOT-lambda-package.zip s3://athenalite-data-ap-south-1/lambda-deployments/
aws lambda update-function-code \
  --function-name AthenaLite-QueryWorker \
  --s3-bucket athenalite-data-ap-south-1 \
  --s3-key lambda-deployments/AthenaLiteQueryWorker-1.0-SNAPSHOT-lambda-package.zip
```

---

## Repositories

| Repo | What | Deploy Target |
|------|------|---------------|
| [AthenaLiteService](https://github.com/rahulkumarroy477/AthenaLiteService) | API + FileProcessor (Java) | AWS Lambda |
| [AthenaLiteQueryWorker](https://github.com/rahulkumarroy477/AthenaLiteQueryWorker) | DuckDB Query Engine (Java) | AWS Lambda |
| [AthenaLiteUI](https://github.com/rahulkumarroy477/AthenaLiteUI) | React Frontend | Vercel |
