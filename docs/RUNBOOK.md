# AthenaLite — Troubleshooting Runbook

## Issue 1: DuckDB GLIBC Error

**Error:** `Extension "[internal] could not be loaded: /lib64/libc.so.6: version 'GLIBC_2.28' not found`

**Cause:** DuckDB requires GLIBC 2.28+ but Lambda `java17` on x86_64 uses Amazon Linux 2 (GLIBC 2.26).

**Fix:** Change QueryWorker Lambda runtime to `java21` (runs on Amazon Linux 2023, GLIBC 2.34).

```bash
aws lambda update-function-configuration \
  --function-name AthenaLite-QueryWorker \
  --runtime java21
```

Also update in code: `pom.xml`, `template.yml`, `deploy.yml`.

---

## Issue 2: Table Not Found in Query

**Error:** `Catalog Error: Table with name X does not exist! Did you mean "Y"?`

**Cause:** Table names use underscores (`_`) not hyphens (`-`). The upload sanitizes filenames.

**Fix:** Use the exact table name shown in the sidebar. Check DynamoDB:

```bash
aws dynamodb scan --table-name AthenaLiteTables --query 'Items[].tableName'
```

---

## Issue 3: File Uploaded but Table Not Appearing

**Cause:** FileProcessor Lambda didn't trigger or failed.

**Diagnosis:**
```bash
# Check if file reached S3
aws s3 ls s3://athenalite-data-ap-south-1/raw/ --recursive

# Check if FileProcessor ran
aws logs describe-log-streams --log-group-name /aws/lambda/AthenaLite-FileProcessor --order-by LastEventTime --descending --limit 1

# Check DynamoDB for metadata
aws dynamodb scan --table-name AthenaLiteTables
```

**Common causes:**
- S3 notification not configured (check `aws s3api get-bucket-notification-configuration --bucket athenalite-data-ap-south-1`)
- FileProcessor Lambda missing or wrong handler
- IAM permissions missing (S3 read + DynamoDB write)

---

## Issue 4: Query Stuck in RUNNING

**Cause:** QueryWorker Lambda timed out or crashed.

**Diagnosis:**
```bash
# Check query status
aws dynamodb scan --table-name AthenaLiteQueries

# Check SQS for stuck messages
aws sqs get-queue-attributes \
  --queue-url https://sqs.ap-south-1.amazonaws.com/<ACCOUNT_ID>/AthenaLiteQueryQueue \
  --attribute-names All

# Check QueryWorker logs
aws logs describe-log-streams --log-group-name /aws/lambda/AthenaLite-QueryWorker --order-by LastEventTime --descending --limit 1
```

**Fix:** Increase Lambda timeout (currently 120s) or memory if OOM.

---

## Issue 5: API Returns "Missing or invalid Authorization header"

**Cause:** Frontend not passing JWT token, or Cognito config mismatch.

**Diagnosis:**
- Check browser console for auth errors
- Verify Vercel env vars match:
  - `VITE_COGNITO_USER_POOL_ID` = your pool ID
  - `VITE_COGNITO_CLIENT_ID` = your client ID
- Verify backend `application.properties` has the same Cognito values

**Quick test (bypass auth):**
```bash
curl https://<API_ID>.execute-api.ap-south-1.amazonaws.com/Prod/ping
# Should return {"pong":"Hello, World!"} — this endpoint has no auth
```

---

## Issue 6: GitHub Actions Deploy Fails

**Cause:** AWS credentials expired or insufficient permissions.

**Diagnosis:** Check Actions tab on GitHub repo for error logs.

**Common fixes:**
- Update `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` secrets in GitHub
- Verify `github-deployer` IAM user has `GitHubDeployPolicy` attached
- Policy must allow `s3:PutObject` on `lambda-deployments/*` and `lambda:UpdateFunctionCode` on all 3 functions

---

## Issue 7: Lambda Zip Too Large for Direct Upload

**Error:** `RequestEntityTooLargeException: Request must be smaller than 70167211 bytes`

**Cause:** Lambda direct upload limit is ~50 MB. QueryWorker zip is ~119 MB (DuckDB).

**Fix:** Upload via S3 first:
```bash
aws s3 cp target/FILE.zip s3://athenalite-data-ap-south-1/lambda-deployments/FILE.zip
aws lambda update-function-code \
  --function-name FUNCTION_NAME \
  --s3-bucket athenalite-data-ap-south-1 \
  --s3-key lambda-deployments/FILE.zip
```

---

## Issue 8: S3 Upload Timeout in CI/CD

**Cause:** Large zip + slow network.

**Fix:** Add timeout options:
```bash
aws s3 cp FILE s3://BUCKET/KEY --cli-read-timeout 300 --cli-connect-timeout 300
```

Or use multipart threshold config in `~/.aws/config`.

---

## Issue 9: DynamoDB Table Wrong Schema

**Error:** Queries by `userId + queryId` fail, or items not found.

**Diagnosis:**
```bash
aws dynamodb describe-table --table-name AthenaLiteQueries --query 'Table.KeySchema'
```

**Expected schemas:**
- `AthenaLiteTables`: PK=`userId`, SK=`tableName`
- `AthenaLiteQueries`: PK=`userId`, SK=`queryId`

**Fix:** Delete and recreate:
```bash
aws dynamodb delete-table --table-name TABLE_NAME
# Wait for deletion, then:
aws dynamodb create-table \
  --table-name TABLE_NAME \
  --attribute-definitions AttributeName=userId,AttributeType=S AttributeName=SORT_KEY,AttributeType=S \
  --key-schema AttributeName=userId,KeyType=HASH AttributeName=SORT_KEY,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST
```

---

## Issue 10: CORS Errors on Frontend

**Error:** Browser blocks API requests with CORS error.

**Cause:** API Gateway not returning CORS headers, or backend not handling OPTIONS.

**Diagnosis:** Check browser Network tab for the preflight OPTIONS request.

**Fix:** Ensure `CorsConfig.java` in AthenaLiteService allows your Vercel domain. Check that API Gateway doesn't strip headers.

---

## Issue 11: Files Auto-Deleted (Can't Query)

**Cause:** S3 lifecycle rule deletes `raw/`, `parquet/`, `results/` after 1 day.

**Fix:** This is by design (cost savings). Re-upload the file to query again. Lambda deployment zips in `lambda-deployments/` are NOT affected.

---

## Quick Health Checks

```bash
# 1. API alive?
curl https://<API_ID>.execute-api.ap-south-1.amazonaws.com/Prod/ping

# 2. All Lambdas active?
aws lambda list-functions --query 'Functions[].{Name:FunctionName,State:State}'

# 3. SQS queue healthy?
aws sqs get-queue-attributes --queue-url <QUEUE_URL> --attribute-names ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible

# 4. Tables in DynamoDB?
aws dynamodb scan --table-name AthenaLiteTables --query 'Count'

# 5. S3 bucket accessible?
aws s3 ls s3://athenalite-data-ap-south-1/
```

---

## Architecture Diagram

```
Frontend (Vercel) → API Gateway → AthenaLite-Service (Lambda)
                                      ├── S3 upload (raw/)
                                      ├── DynamoDB (metadata + queries)
                                      └── SQS (query dispatch)
                                            └── AthenaLite-QueryWorker (Lambda + DuckDB)
                                                  ├── S3 read (parquet/)
                                                  ├── S3 write (results/)
                                                  └── DynamoDB (update status)

S3 raw/ upload → AthenaLite-FileProcessor (Lambda)
                      ├── S3 copy (parquet/)
                      └── DynamoDB (write table metadata)
```

---

## Current Config (July 2026)

| Resource | Value |
|----------|-------|
| AWS Account | <ACCOUNT_ID> |
| Region | ap-south-1 |
| S3 Bucket | athenalite-data-ap-south-1 |
| SQS Queue | AthenaLiteQueryQueue |
| DynamoDB | AthenaLiteTables, AthenaLiteQueries |
| API Gateway | https://<API_ID>.execute-api.ap-south-1.amazonaws.com/Prod |
| Cognito Pool | <COGNITO_POOL_ID> |
| Cognito Client | <COGNITO_CLIENT_ID> |
| GitHub Deploy User | github-deployer |
| Billing Alarm | $1 threshold → <YOUR_EMAIL> |
