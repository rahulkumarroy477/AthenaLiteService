# AthenaLite — AWS Account Migration Guide

Complete end-to-end steps to switch AthenaLite to a new AWS account.

## Prerequisites

- AWS CLI installed
- New AWS account with root/admin access
- Access Key ID + Secret Access Key for the new account

## Step 0: Set AWS Credentials (temporary, session-only)

```bash
unset AWS_SESSION_TOKEN
unset AWS_SECURITY_TOKEN
export AWS_ACCESS_KEY_ID=<your-access-key>
export AWS_SECRET_ACCESS_KEY=<your-secret-key>
export AWS_DEFAULT_REGION=ap-south-1
```

Verify:
```bash
aws sts get-caller-identity
```

---

## Step 1: Create S3 Bucket

```bash
aws s3 mb s3://athenalite-data-ap-south-1 --region ap-south-1
```

---

## Step 2: Create DynamoDB Tables

```bash
# Table 1: AthenaLiteTables
aws dynamodb create-table \
  --table-name AthenaLiteTables \
  --attribute-definitions AttributeName=userId,AttributeType=S AttributeName=tableName,AttributeType=S \
  --key-schema AttributeName=userId,KeyType=HASH AttributeName=tableName,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --region ap-south-1

# Table 2: AthenaLiteQueries
aws dynamodb create-table \
  --table-name AthenaLiteQueries \
  --attribute-definitions AttributeName=userId,AttributeType=S AttributeName=queryId,AttributeType=S \
  --key-schema AttributeName=userId,KeyType=HASH AttributeName=queryId,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --region ap-south-1
```

---

## Step 3: Create SQS Queue

```bash
aws sqs create-queue \
  --queue-name AthenaLiteQueryQueue \
  --attributes VisibilityTimeout=120 \
  --region ap-south-1
```

Note the queue URL from the output (format: `https://sqs.ap-south-1.amazonaws.com/<ACCOUNT_ID>/AthenaLiteQueryQueue`).

---

## Step 4: Create IAM Role for Lambda

```bash
# Create the role
aws iam create-role \
  --role-name AthenaLite-Lambda-Role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "lambda.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach permissions
aws iam attach-role-policy --role-name AthenaLite-Lambda-Role --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
aws iam attach-role-policy --role-name AthenaLite-Lambda-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess
aws iam attach-role-policy --role-name AthenaLite-Lambda-Role --policy-arn arn:aws:iam::aws:policy/AmazonSQSFullAccess
aws iam attach-role-policy --role-name AthenaLite-Lambda-Role --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
```

Wait ~10 seconds for the role to propagate.

---

## Step 5: Create Lambda Functions

### 5a: AthenaLite-Service (40 MB — direct upload)

```bash
cd /Users/raahhuul/Desktop/AthenaLiteService
mvn clean package -q -DskipTests

aws lambda create-function \
  --function-name AthenaLite-Service \
  --runtime java17 \
  --handler org.example.StreamLambdaHandler::handleRequest \
  --memory-size 1024 \
  --timeout 30 \
  --role arn:aws:iam::<ACCOUNT_ID>:role/AthenaLite-Lambda-Role \
  --zip-file fileb://target/AthenaLiteService-1.0-SNAPSHOT-lambda-package.zip \
  --environment "Variables={S3_BUCKET=athenalite-data-ap-south-1,DYNAMODB_TABLE=AthenaLiteTables,DYNAMODB_QUERY_TABLE=AthenaLiteQueries,QUERY_QUEUE_URL=https://sqs.ap-south-1.amazonaws.com/<ACCOUNT_ID>/AthenaLiteQueryQueue}" \
  --region ap-south-1
```

### 5b: AthenaLite-QueryWorker (119 MB — upload via S3)

```bash
cd /Users/raahhuul/Desktop/microservices-practice/AthenaLiteQueryWorker
mvn clean package -q -DskipTests

# Upload zip to S3 first (too large for direct upload)
aws s3 cp target/AthenaLiteQueryWorker-1.0-SNAPSHOT-lambda-package.zip \
  s3://athenalite-data-ap-south-1/lambda-deployments/AthenaLite-QueryWorker-initial.zip

# Create Lambda from S3
aws lambda create-function \
  --function-name AthenaLite-QueryWorker \
  --runtime java17 \
  --handler org.example.SqsQueryHandler::handleRequest \
  --memory-size 1024 \
  --timeout 120 \
  --role arn:aws:iam::<ACCOUNT_ID>:role/AthenaLite-Lambda-Role \
  --code S3Bucket=athenalite-data-ap-south-1,S3Key=lambda-deployments/AthenaLite-QueryWorker-initial.zip \
  --environment "Variables={S3_BUCKET=athenalite-data-ap-south-1,DYNAMODB_QUERY_TABLE=AthenaLiteQueries}" \
  --region ap-south-1
```

### 5c: Add SQS Trigger to QueryWorker

```bash
aws lambda create-event-source-mapping \
  --function-name AthenaLite-QueryWorker \
  --event-source-arn arn:aws:sqs:ap-south-1:<ACCOUNT_ID>:AthenaLiteQueryQueue \
  --batch-size 1
```

---

## Step 6: Create API Gateway

```bash
# Create REST API
API_ID=$(aws apigateway create-rest-api \
  --name AthenaLite-API \
  --endpoint-configuration types=REGIONAL \
  --query 'id' --output text)

# Get root resource ID
ROOT_ID=$(aws apigateway get-resources \
  --rest-api-id $API_ID \
  --query 'items[0].id' --output text)

# Create {proxy+} resource
PROXY_ID=$(aws apigateway create-resource \
  --rest-api-id $API_ID \
  --parent-id $ROOT_ID \
  --path-part '{proxy+}' \
  --query 'id' --output text)

# Create ANY method
aws apigateway put-method \
  --rest-api-id $API_ID \
  --resource-id $PROXY_ID \
  --http-method ANY \
  --authorization-type NONE

# Set Lambda proxy integration
aws apigateway put-integration \
  --rest-api-id $API_ID \
  --resource-id $PROXY_ID \
  --http-method ANY \
  --type AWS_PROXY \
  --integration-http-method POST \
  --uri arn:aws:apigateway:ap-south-1:lambda:path/2015-03-31/functions/arn:aws:lambda:ap-south-1:<ACCOUNT_ID>:function:AthenaLite-Service/invocations

# Grant API Gateway permission to invoke Lambda
aws lambda add-permission \
  --function-name AthenaLite-Service \
  --statement-id apigateway-invoke \
  --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:ap-south-1:<ACCOUNT_ID>:${API_ID}/*/*/{proxy+}"

# Deploy to Prod stage
aws apigateway create-deployment \
  --rest-api-id $API_ID \
  --stage-name Prod

echo "API URL: https://${API_ID}.execute-api.ap-south-1.amazonaws.com/Prod"
```

---

## Step 7: Create Cognito User Pool

```bash
# Create User Pool
POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name AthenaLite-Users \
  --auto-verified-attributes email \
  --username-attributes email \
  --policies 'PasswordPolicy={MinimumLength=8,RequireUppercase=true,RequireLowercase=true,RequireNumbers=true,RequireSymbols=false}' \
  --query 'UserPool.Id' --output text)

# Create App Client (no secret — for frontend)
CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-name AthenaLite-WebApp \
  --no-generate-secret \
  --explicit-auth-flows ALLOW_USER_SRP_AUTH ALLOW_REFRESH_TOKEN_AUTH \
  --query 'UserPoolClient.ClientId' --output text)

echo "User Pool ID: $POOL_ID"
echo "Client ID: $CLIENT_ID"
```

---

## Step 8: Create GitHub Deploy User

```bash
# Create IAM user
aws iam create-user --user-name github-deployer

# Create deploy policy (least privilege)
aws iam create-policy \
  --policy-name GitHubDeployPolicy \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": ["s3:PutObject", "s3:GetObject"],
        "Resource": "arn:aws:s3:::athenalite-data-ap-south-1/lambda-deployments/*"
      },
      {
        "Effect": "Allow",
        "Action": ["lambda:UpdateFunctionCode"],
        "Resource": [
          "arn:aws:lambda:ap-south-1:<ACCOUNT_ID>:function:AthenaLite-Service",
          "arn:aws:lambda:ap-south-1:<ACCOUNT_ID>:function:AthenaLite-QueryWorker"
        ]
      }
    ]
  }'

# Attach policy
aws iam attach-user-policy \
  --user-name github-deployer \
  --policy-arn arn:aws:iam::<ACCOUNT_ID>:policy/GitHubDeployPolicy

# Create access key for GitHub
aws iam create-access-key --user-name github-deployer
```

Save the AccessKeyId and SecretAccessKey from the output.

---

## Step 9: Update Code (hardcoded fallbacks)

### `AthenaLiteService/src/main/resources/application.properties`
```properties
aws.sqs.query-queue-url=${QUERY_QUEUE_URL:https://sqs.ap-south-1.amazonaws.com/<ACCOUNT_ID>/AthenaLiteQueryQueue}
aws.cognito.user-pool-id=${COGNITO_USER_POOL_ID:<POOL_ID>}
aws.cognito.client-id=${COGNITO_CLIENT_ID:<CLIENT_ID>}
```

### `AthenaLiteQueryWorker/src/main/java/org/example/SqsQueryHandler.java`
Update bucket name fallback if changed.

### Both `template.yml` and `deploy.yml` files
Update bucket name if changed.

---

## Step 10: Update GitHub Secrets

Go to both repos → Settings → Secrets → Actions:

| Secret | Value |
|--------|-------|
| `AWS_ACCESS_KEY_ID` | From Step 8 output |
| `AWS_SECRET_ACCESS_KEY` | From Step 8 output |

Repos:
- https://github.com/rahulkumarroy477/AthenaLiteService/settings/secrets/actions
- https://github.com/rahulkumarroy477/AthenaLiteQueryWorker/settings/secrets/actions

---

## Step 11: Update Vercel Env Vars

| Variable | Value |
|----------|-------|
| API URL | `https://<API_ID>.execute-api.ap-south-1.amazonaws.com/Prod` |
| Cognito User Pool ID | From Step 7 |
| Cognito Client ID | From Step 7 |

Redeploy the frontend on Vercel after updating.

---

## Step 12: Push Code & Verify

```bash
cd /Users/raahhuul/Desktop/AthenaLiteService
git add . && git commit -m "switch to new AWS account" && git push origin main

cd /Users/raahhuul/Desktop/microservices-practice/AthenaLiteQueryWorker
git add . && git commit -m "switch to new AWS account" && git push origin main
```

### Verify:
```bash
# Health check
curl https://<API_ID>.execute-api.ap-south-1.amazonaws.com/Prod/ping
# Expected: {"pong":"Hello, World!"}
```

---

## Cleanup (old account)

Delete resources from the old account to avoid charges:
```bash
aws lambda delete-function --function-name AthenaLite-Service
aws lambda delete-function --function-name AthenaLite-QueryWorker
aws dynamodb delete-table --table-name AthenaLiteTables
aws dynamodb delete-table --table-name AthenaLiteQueries
aws sqs delete-queue --queue-url <old-queue-url>
aws s3 rb s3://<old-bucket> --force
```

---

## Quick Reference (Current Setup)

| Resource | Value |
|----------|-------|
| AWS Account | <ACCOUNT_ID> |
| Region | ap-south-1 |
| S3 Bucket | athenalite-data-ap-south-1 |
| SQS Queue | AthenaLiteQueryQueue |
| DynamoDB Tables | AthenaLiteTables, AthenaLiteQueries |
| API Gateway | https://<API_ID>.execute-api.ap-south-1.amazonaws.com/Prod |
| Cognito Pool | <COGNITO_POOL_ID> |
| Cognito Client | <COGNITO_CLIENT_ID> |
| GitHub Deploy User | github-deployer |
