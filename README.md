# AthenaLiteService serverless API
The AthenaLiteService project, created with [`aws-serverless-java-container`](https://github.com/aws/serverless-java-container).

The starter project defines a simple `/ping` resource that can accept `GET` requests with its tests.

The project folder also includes a `template.yml` file. You can use this [SAM](https://github.com/awslabs/serverless-application-model) file to deploy the project to AWS Lambda and Amazon API Gateway or test in local with the [SAM CLI](https://github.com/awslabs/aws-sam-cli). 

## Pre-requisites
* [AWS CLI](https://aws.amazon.com/cli/)
* [SAM CLI](https://github.com/awslabs/aws-sam-cli)
* [Gradle](https://gradle.org/) or [Maven](https://maven.apache.org/)

## Building the project
You can use the SAM CLI to quickly build the project
```bash
$ mvn archetype:generate -DartifactId=AthenaLiteService -DarchetypeGroupId=com.amazonaws.serverless.archetypes -DarchetypeArtifactId=aws-serverless-jersey-archetype -DarchetypeVersion=3.0.1 -DgroupId=org.example -Dversion=1.0-SNAPSHOT -Dinteractive=false
$ cd AthenaLiteService
$ sam build
Building resource 'AthenaLiteServiceFunction'
Running JavaGradleWorkflow:GradleBuild
Running JavaGradleWorkflow:CopyArtifacts

Build Succeeded

Built Artifacts  : .aws-sam/build
Built Template   : .aws-sam/build/template.yaml

Commands you can use next
=========================
[*] Invoke Function: sam local invoke
[*] Deploy: sam deploy --guided
```

## Testing locally with the SAM CLI

From the project root folder - where the `template.yml` file is located - start the API with the SAM CLI.

```bash
$ sam local start-api

...
Mounting com.amazonaws.serverless.archetypes.StreamLambdaHandler::handleRequest (java11) at http://127.0.0.1:3000/{proxy+} [OPTIONS GET HEAD POST PUT DELETE PATCH]
...
```

Using a new shell, you can send a test ping request to your API:

```bash
$ curl -s http://127.0.0.1:3000/ping | python -m json.tool

{
    "pong": "Hello, World!"
}
``` 

## Deploying to AWS
To deploy the application in your AWS account, you can use the SAM CLI's guided deployment process and follow the instructions on the screen

```
$ sam deploy --guided
```

Once the deployment is completed, the SAM CLI will print out the stack's outputs, including the new application URL. You can use `curl` or a web browser to make a call to the URL

```
...
-------------------------------------------------------------------------------------------------------------
OutputKey-Description                        OutputValue
-------------------------------------------------------------------------------------------------------------
AthenaLiteServiceApi - URL for application            https://xxxxxxxxxx.execute-api.us-west-2.amazonaws.com/Prod/pets
-------------------------------------------------------------------------------------------------------------
```

Copy the `OutputValue` into a browser or use curl to test your first request:

```bash
$ curl -s https://xxxxxxx.execute-api.us-west-2.amazonaws.com/Prod/ping | python -m json.tool

{
    "pong": "Hello, World!"
}
```



To make AthenaLite a fully functional production application, you would need a backend architecture that handles file storage, metadata management, and a serverless query engine.
Since the app is named AthenaLite, the most natural fit is an AWS-based serverless architecture. Here is the recommended architecture and the APIs you would need to implement:
1. High-Level Architecture
   Storage: AWS S3 to store the uploaded CSV, JSON, and Parquet files.
   Query Engine: AWS Athena to execute SQL directly against the files in S3.
   Metadata Catalog: AWS Glue Data Catalog to store table schemas and partitions.
   Database: PostgreSQL or DynamoDB to store user accounts, query history, and file-to-table mappings.
   Compute: Node.js (Express) or Python (FastAPI) running on AWS Lambda or App Runner.
2. Required API Endpoints
   Authentication API
   POST /api/auth/register: Create a new user account.
   POST /api/auth/login: Authenticate user and return a JWT (JSON Web Token).
   POST /api/auth/logout: Invalidate the user session.
   File & Table Management API
   POST /api/upload:
   Logic: Receives the file, generates a unique S3 key, and uploads it.
   Glue Integration: Triggers a Glue Crawler or uses the CreateTable API to define the schema in the Data Catalog so Athena can "see" it as a table.
   Response: Returns the generated tableName.
   GET /api/tables: Returns a list of all tables available to the current user.
   DELETE /api/tables/:name: Deletes the table definition and the underlying file in S3.
   Query Execution API
   POST /api/query:
   Input: { "query": "SELECT * FROM my_table LIMIT 10" }
   Logic:
   Validates the SQL for security (e.g., preventing DROP TABLE).
   Calls the Athena StartQueryExecution API.
   Polls Athena for completion (since Athena is asynchronous).
   Retrieves results using GetQueryResults.
   Response: Returns the first 100 rows as JSON and a pre-signed S3 URL for the full CSV download.
3. Data Flow Example
   Upload: User drops sales.csv → Backend uploads to s3://athena-lite-bucket/user-123/sales.csv → Backend runs CREATE EXTERNAL TABLE sales (...) in Athena.
   Query: User runs SELECT region, SUM(sales) FROM sales GROUP BY 1 → Backend sends this to Athena → Athena scans the CSV in S3 → Backend returns the aggregated results to the UI.
4. Security Considerations
   S3 Sandboxing: Ensure users can only query files within their own S3 prefix (e.g., s3://bucket/user-id/*).
   Query Limits: Implement a timeout and a data-scanned limit (e.g., max 1GB per query) to control AWS costs.
   SQL Injection: Use the Athena SDK's parameterization or a strict SQL parser to ensure users can't execute malicious commands.
   This architecture is highly scalable because it is entirely serverless—you only pay for the storage you use and the data Athena actually scans during queries.

