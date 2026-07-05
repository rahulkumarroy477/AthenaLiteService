# AthenaLite — Performance Benchmarks

## Test Environment
- Lambda: Java 21, 1024 MB memory, 120s timeout
- DuckDB engine via httpfs (reads from S3)
- Data: ~2 MB CSV files (~10K-20K rows each)
- Tables: `salesdataapril2026`, `salesdatamarch2026`, `sales_2026_05_00000`

## Results Summary

| Metric | Value |
|--------|-------|
| **Minimum** | 823ms |
| **Maximum** | 20.1s |
| **Average (warm)** | ~1.4s |
| **Average (cold start)** | ~3.5s |
| **Timeout buffer** | 120s - 20s = 100s remaining |

## Query Performance by Complexity

| Level | Query Type | Execution Time |
|-------|-----------|----------------|
| 1 | Simple COUNT / SELECT LIMIT | 823ms - 1023ms |
| 2 | Basic GROUP BY | ~1.1s |
| 3 | Multi-column aggregation with CAST | ~1.3-1.4s |
| 4 | Cross-table UNION ALL | ~1.3-1.4s |
| 5 | Window functions (SUM OVER, ROW_NUMBER) | ~1.4-1.6s |
| 6 | Cross-table FULL OUTER JOIN | ~2.2s |
| 7 | Self-join (O(n²)) | **20.1s** |

## Cold Start Impact

First query after Lambda goes idle (~15 min) takes 3-5s due to:
- JVM startup
- DuckDB initialization
- httpfs extension loading
- S3 connection establishment

Subsequent (warm) queries drop to 1-2s.

## Sample Queries Used

### Level 1: Simple (fastest)
```sql
SELECT COUNT(*) as total_rows FROM "salesdataapril2026"
```

### Level 2: Basic aggregation
```sql
SELECT Marketplace, COUNT(*) as transactions
FROM "salesdataapril2026"
GROUP BY Marketplace
ORDER BY transactions DESC
```

### Level 3: Multi-column aggregation
```sql
SELECT Marketplace, "Transaction Type",
       COUNT(*) as cnt,
       SUM(CAST("Sales Price (Marketplace Currency)" AS DOUBLE)) as total_sales,
       AVG(CAST("Estimated Earnings (Marketplace Currency)" AS DOUBLE)) as avg_earnings
FROM "salesdataapril2026"
GROUP BY Marketplace, "Transaction Type"
ORDER BY total_sales DESC
```

### Level 4: Cross-table UNION
```sql
SELECT 'April' as month, Marketplace, COUNT(*) as cnt,
       SUM(CAST("Estimated Earnings (Marketplace Currency)" AS DOUBLE)) as earnings
FROM "salesdataapril2026"
GROUP BY Marketplace
UNION ALL
SELECT 'March' as month, Marketplace, COUNT(*) as cnt,
       SUM(CAST("Estimated Earnings (Marketplace Currency)" AS DOUBLE)) as earnings
FROM "salesdatamarch2026"
GROUP BY Marketplace
ORDER BY month, earnings DESC
```

### Level 5: Window functions
```sql
SELECT Marketplace, "Item Name", "Transaction Time",
       CAST("Sales Price (Marketplace Currency)" AS DOUBLE) as price,
       SUM(CAST("Sales Price (Marketplace Currency)" AS DOUBLE)) OVER (PARTITION BY Marketplace ORDER BY "Transaction Time") as running_total,
       ROW_NUMBER() OVER (PARTITION BY Marketplace ORDER BY "Transaction Time" DESC) as rank
FROM "salesdataapril2026"
WHERE "Transaction Type" = 'Charge'
```

### Level 6: Cross-table JOIN
```sql
SELECT a.Marketplace, a."Item Name",
       COUNT(DISTINCT a."App User ID") as unique_users_apr,
       COUNT(DISTINCT b."App User ID") as unique_users_mar
FROM "salesdataapril2026" a
FULL OUTER JOIN "salesdatamarch2026" b
  ON a.Marketplace = b.Marketplace AND a."Item Name" = b."Item Name"
GROUP BY a.Marketplace, a."Item Name"
ORDER BY unique_users_apr DESC
LIMIT 20
```

### Level 7: Self-join (slowest)
```sql
SELECT a."App User ID", COUNT(DISTINCT a."Item Name") as items_bought,
       SUM(CAST(a."Sales Price (Marketplace Currency)" AS DOUBLE)) as total_spent
FROM "salesdataapril2026" a
JOIN "salesdataapril2026" b ON a."App User ID" = b."App User ID" AND a."Transaction ID" != b."Transaction ID"
WHERE a."Transaction Type" = 'Charge' AND a."App User ID" != ''
GROUP BY a."App User ID"
ORDER BY total_spent DESC
LIMIT 20
```

## Estimated Limits by File Size

| File Size | Rows (approx) | Simple query | Complex query | Self-join |
|-----------|---------------|--------------|---------------|-----------|
| 2 MB | ~10K | <1s | ~1.5s | ~20s |
| 5 MB | ~20K | <2s | ~3s | ~60s |
| 20 MB | ~100K | <5s | ~15s | ⚠️ timeout |
| 50 MB | ~250K | <10s | ~45s | ❌ timeout |

## Recommendations
- Avoid self-joins on files > 5 MB
- For large files, use LIMIT or WHERE clauses to reduce scan
- Cold start penalty is fixed (~3s) regardless of query complexity
- Upload limit: 50 MB (presigned URL, direct to S3)
