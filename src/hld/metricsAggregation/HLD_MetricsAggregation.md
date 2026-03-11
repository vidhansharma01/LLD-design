# High-Level Design: Distributed Metrics Logging & Aggregation System (SDE3 Interview)

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: what types of data (metrics vs logs vs traces), granularity, retention.
2. Define scale and latency SLOs.
3. Walk through the ingestion pipeline (the write path).
4. Deep-dive the aggregation engine (the hardest part).
5. Cover the query path, alerting, storage tiering, and failure handling.

---

## 1. Problem Statement And Scope

Design a distributed system like **Prometheus + Grafana + ELK Stack** combined:
- **Metrics collection**: Numerical time-series data (CPU %, request latency, error rate, DB connections).
- **Log aggregation**: Structured and unstructured log lines from thousands of services.
- **Aggregation**: Roll up raw data into minute/hour/day aggregates for efficient querying.
- **Alerting**: Evaluate rules against aggregated data; fire alerts when thresholds breached.
- **Dashboards & Querying**: Flexible query language for time-series analysis and log search.
- **Multi-tenancy**: Separate namespaces per team/service for access control and quota.

**Real-world analogues**: Datadog, New Relic, AWS CloudWatch, Prometheus+Thanos, Splunk.

### 1.1 The Three Pillars Of Observability

```
┌──────────────────────────────────────────────────────────┐
│               Observability                              │
│                                                          │
│  Metrics         Logs               Traces               │
│  (WHAT is wrong) (WHY it is wrong)  (WHERE it is wrong)  │
│                                                          │
│  Numerical,      Text strings,      Distributed request  │
│  time-series     structured/unstruc spans across         │
│  counters,        log events per    microservices         │
│  gauges,          request           (Jaeger, Zipkin)      │
│  histograms       (ELK, Loki)       Out of scope here     │
└──────────────────────────────────────────────────────────┘
```

**This HLD focuses on Metrics and Logs** (traces are a separate system).

### 1.2 In Scope
- Metrics ingestion pipeline (push and pull models).
- Log aggregation pipeline.
- Time-series storage with tiered retention.
- Aggregation engine (streaming and batch).
- Query API for dashboards.
- Alerting engine (rule evaluation, notification).
- Multi-tenancy and access control.

### 1.3 Out Of Scope
- Distributed tracing (Jaeger/Zipkin).
- APM (Application Performance Monitoring) internals.
- Full-text search engine internals at Lucene level.

---

## 2. Requirements

### 2.1 Functional Requirements
1. Services push metrics (counters, gauges, histograms) at configurable intervals (default: 15s).
2. System supports pull-based collection via agent scraping endpoints (`/metrics`).
3. Log lines ingested from services, tagged with service name, host, environment, severity.
4. Raw data queryable for recent window (last 2 hours full resolution).
5. Aggregated data queryable for longer windows (last 7 days minute-level; last 1 year hour-level).
6. Alert rules: "if `error_rate > 5%` for 5 consecutive minutes → fire alert".
7. Multi-tenancy: each team sees only their own metrics/logs.
8. Dashboard: render time-series charts, current value panels, log search.
9. Data retention: raw = 2 hours; minute aggregates = 30 days; hour aggregates = 1 year.

### 2.2 Non-Functional Requirements
- **Scale**: 10,000 instrumented services; 1 million unique metric series; 500 GB logs/day.
- **Write throughput**: 1,000,000 metric data points/sec; 100,000 log lines/sec.
- **Query latency**: Dashboard queries P99 < 2 seconds.
- **Alert evaluation latency**: Alert fires within 60 seconds of threshold breach.
- **Availability**: 99.9% (brief write drops tolerable; reads must be eventually consistent).
- **Durability**: Aggregated data (permanent insights) must never be lost. Raw data loss of < 0.01% acceptable.

---

## 3. Back-Of-The-Envelope Capacity Planning

### 3.1 Metrics Volume

```
Metric data point = { metric_name, tags, value, timestamp }
                  = ~200 bytes per point (with tags)

Write throughput:
  1M data points/sec × 200 bytes = 200 MB/s raw ingestion bandwidth.
  Per hour: 200 MB/s × 3600 = 720 GB/hr raw data.
  Per day:  720 GB × 24 = ~17 TB/day raw.

With 2-hour raw retention → active raw store: 17 TB × (2/24) ≈ 1.4 TB in memory/fast SSD.
After aggregation to 1-minute granularity:
  Each series has 1 point/min instead of 1 point/15s → 4× compression.
  1 minute aggregates: 1M series × 1440 min/day × 200 bytes = ~288 GB/day.
  30-day retention: 288 GB × 30 ≈ 8.6 TB (manageable).
Hour aggregates: 1M series × 24 hours × 200 bytes/day × 365 days ≈ 1.7 TB/year.
```

### 3.2 Log Volume

```
500 GB logs/day ÷ 86400s = ~5.8 MB/s ingest.
Each log line: ~500 bytes average.
Raw logs/sec: 5.8 MB/s ÷ 500 bytes ≈ 12,000 lines/sec (peak ~100K/sec burst).

Storage:
  Hot (7 days):  500 GB × 7 = 3.5 TB (on SSD-backed Elasticsearch).
  Warm (30 days): 500 GB × 30 = 15 TB (compressed, slower disks).
  Cold (90 days): 500 GB × 90 = 45 TB (S3/GCS object store, query via Athena).
  Log compression ratio: ~10:1 for text → cold store actual: ~4.5 TB.
```

### 3.3 Kafka Throughput
```
Metrics: 1M points/sec × 200 bytes = 200 MB/s → 200 Kafka partitions × 1 MB/s each.
Logs: 12K lines/sec × 500 bytes = 6 MB/s → 20 Kafka partitions.
Total ingest bus: ~206 MB/s → well within Kafka's capacity (GB/s per cluster).
```

---

## 4. Metric Types — Full Explanation

Understanding metric types is foundational. Each type has different aggregation semantics.

### 4.1 Counter
```
Definition: Monotonically increasing integer. Never decreases (only resets to 0 on restart).
Examples: total_http_requests, total_bytes_sent, total_errors.

Storage: Raw counter value at each timestamp.
Aggregation: rate(counter) = (value_t2 - value_t1) / (t2 - t1).
  → "requests per second" = rate of increase.

Reset handling: If counter resets (service restart), ingestion pipeline detects
  value_t2 < value_t1 → treats as reset → adds old max to new value for continuity.
  Without reset handling: negative rates appear — confuses users.

Use when: Counting events where cumulative total matters and rate-of-increase is the insight.
```

### 4.2 Gauge
```
Definition: Arbitrary float value that can increase or decrease freely.
Examples: cpu_usage_percent, memory_used_bytes, active_connections, queue_depth.

Storage: Raw value at each timestamp.
Aggregation: average(gauge), max(gauge), min(gauge) over time window.
  → avg CPU over last 5 minutes.

Use when: Measuring current state of something that fluctuates.
```

### 4.3 Histogram
```
Definition: Distribution of observed values, bucketed by range.
Examples: http_request_duration_ms, db_query_latency_us, payload_size_bytes.

Storage: Per collection interval, store:
  - Bucket counts: how many observations fell in each range.
    e.g., [0-10ms: 500], [10-50ms: 200], [50-100ms: 50], [100ms+: 10].
  - sum: total of all observed values.
  - count: total number of observations.

Aggregation:
  - Percentiles estimated from buckets using linear interpolation.
  - P50 (median) = find bucket where cumulative count crosses 50%.
  - P99 = find bucket where cumulative count crosses 99%.

Why histogram over summary:
  - Histograms can be aggregated across instances: merge bucket counts.
  - Summaries can NOT be aggregated (percentile of percentiles is wrong).

Why not store raw samples:
  - 1M requests/sec × 8 bytes each = 8 MB/s for a single latency metric → too expensive.
  - Histogram: ~20 buckets × 4 bytes = 80 bytes/interval → 99%+ compression.

Use when: Measuring latency, size, or duration distributions where percentiles matter.
```

### 4.4 Summary
```
Definition: Pre-computed quantiles on the client side.
Examples: rpc_duration_seconds{quantile="0.99"} = 0.254.

Difference from Histogram:
  - Summary computes quantiles IN the application.
  - No merging across replicas → unusable in distributed systems at scale.
  - Only use when a single process and client-side computation is acceptable.

Recommendation: Prefer Histogram over Summary for distributed systems.
```

---

## 5. High-Level API Design

### 5.1 Metrics Ingestion API (Push Model)

```http
# Push metric data points (from services directly or via agent)
POST /v1/ingest/metrics
Content-Type: application/x-protobuf   # binary for efficiency
{
  "series": [
    {
      "metric": "http_requests_total",
      "tags": { "service": "api-gateway", "method": "GET", "status": "200", "region": "us-east" },
      "points": [
        { "timestamp": 1710172800, "value": 1250430 },
        { "timestamp": 1710172815, "value": 1250812 }
      ],
      "type": "COUNTER"
    },
    {
      "metric": "request_duration_ms",
      "tags": { "service": "api-gateway", "endpoint": "/v1/search" },
      "type": "HISTOGRAM",
      "histogram": {
        "buckets": [10, 50, 100, 250, 500, 1000],
        "counts":  [820, 150,  60,  30,  10,    5],
        "sum": 28500,
        "count": 1075
      },
      "timestamp": 1710172800
    }
  ],
  "tenant_id": "team-platform",
  "agent_version": "1.4.2"
}
→ { "accepted": 2, "rejected": 0 }
```

### 5.2 Pull Model (Prometheus-Compatible Scraping)

```
# Service exposes /metrics endpoint (Prometheus text format)
GET http://api-gateway-pod-3:8080/metrics

Response (text/plain):
  # HELP http_requests_total Total HTTP requests
  # TYPE http_requests_total counter
  http_requests_total{method="GET",status="200"} 1250430
  http_requests_total{method="POST",status="500"} 42
  
  # HELP request_duration_ms Request latency histogram
  # TYPE request_duration_ms histogram
  request_duration_ms_bucket{le="10"}   820
  request_duration_ms_bucket{le="50"}   970
  request_duration_ms_bucket{le="+Inf"} 1075
  request_duration_ms_sum   28500
  request_duration_ms_count 1075

Collection Agent (runs per host or per Kubernetes namespace):
  → Scrapes all registered targets every 15 seconds.
  → Converts to internal protobuf format.
  → Sends to ingestion API (POST /v1/ingest/metrics).
```

### 5.3 Log Ingestion API

```http
# Ingest log lines (structured JSON preferred)
POST /v1/ingest/logs
{
  "logs": [
    {
      "timestamp": "2024-03-11T21:00:00.123Z",
      "level": "ERROR",
      "service": "payment-service",
      "host": "payment-pod-7",
      "trace_id": "abc123",
      "message": "Payment gateway timeout after 30s",
      "fields": { "gateway": "razorpay", "amount": 500, "user_id": "USR_123" }
    }
  ],
  "tenant_id": "team-payments"
}
```

### 5.4 Query API

```http
# Time-series metric query (PromQL-like)
POST /v1/query/metrics
{
  "query": "rate(http_requests_total{service='api-gateway', status='500'}[5m])",
  "start": "2024-03-11T20:00:00Z",
  "end":   "2024-03-11T21:00:00Z",
  "step":  "1m"                          # resolution: 1 data point per minute
}
→ {
    "resultType": "matrix",
    "result": [
      {
        "metric": { "service": "api-gateway", "status": "500" },
        "values": [[1710172800, "0.42"], [1710172860, "0.38"], ...]
      }
    ]
  }

# Log search query
POST /v1/query/logs
{
  "query": "level=ERROR AND service=payment-service AND message:timeout",
  "start": "2024-03-11T20:00:00Z",
  "end":   "2024-03-11T21:00:00Z",
  "limit": 100,
  "cursor": null
}

# Alert rule management
POST /v1/alerts/rules
{
  "name": "high_error_rate",
  "query": "rate(http_requests_total{status=~'5..'}[5m]) > 0.05",
  "for":   "5m",          # must be true for 5 consecutive minutes
  "severity": "critical",
  "notify": ["slack:#alerts-critical", "pagerduty:team-platform"]
}
```

---

## 6. Data Model

### 6.1 Time-Series Data Model

```
Metric Series = unique combination of (metric_name + all tag key-value pairs).

Example series:
  http_requests_total{service="api-gw", method="GET", status="200", region="us-east"}
  http_requests_total{service="api-gw", method="POST", status="500", region="us-east"}
  These are TWO different series (different tag values).

Series ID:
  series_id = hash(metric_name + sorted_tags_string)
  e.g., MD5("http_requests_total|method=GET|region=us-east|service=api-gw|status=200")
  Stored as 8-byte integer for compactness.

Series metadata table (PostgreSQL):
  CREATE TABLE series_metadata (
    series_id    BIGINT PRIMARY KEY,
    metric_name  VARCHAR(128) NOT NULL,
    tags         JSONB NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    first_seen   TIMESTAMP NOT NULL,
    last_seen    TIMESTAMP NOT NULL,
    INDEX (metric_name, tenant_id),
    INDEX USING GIN (tags)   -- for tag-based filtering
  );

Data point (stored in TSDB):
  (series_id BIGINT, timestamp INT64, value FLOAT64)
  = 20 bytes per point (without compression).
  With compression: ~2-4 bytes/point (Gorilla/delta-of-delta encoding).
```

### 6.2 Time-Series Storage Layout (Chunk-Based)

```
Chunk = compressed block of ~120 data points for one series (covers ~30 min at 15s resolution).

Chunk format:
  [ series_id | chunk_start_ts | chunk_end_ts | num_points | compressed_data ]

Compression techniques:
  Timestamp delta encoding:
    Store first timestamp as absolute. Subsequent = delta from previous.
    Timestamps increase by ~15s always → deltas are small → varint encodes in 1-2 bytes.
    Savings: 8 bytes → ~1-2 bytes per timestamp.

  XOR floating-point compression (Gorilla encoding):
    Store first value as full float64.
    Subsequent = XOR of current and previous floating-point bits.
    Leading zeros of XOR result stored compactly (most values are close to previous → small XOR).
    Savings: 8 bytes → ~1-3 bytes per value on average.

  Combined: ~20 bytes/point raw → ~2-4 bytes/point compressed → 5-10× compression.
```

### 6.3 Aggregated Data Model

```
-- Pre-aggregated rollups stored in columnar store
CREATE TABLE metric_aggregates (
  series_id   BIGINT NOT NULL,
  period_type ENUM('1m','5m','1h','1d') NOT NULL,
  period_ts   BIGINT NOT NULL,    -- start of the period (unix epoch)
  min_val     DOUBLE NOT NULL,
  max_val     DOUBLE NOT NULL,
  sum_val     DOUBLE NOT NULL,
  count_val   BIGINT NOT NULL,
  avg_val     DOUBLE GENERATED ALWAYS AS (sum_val / count_val) STORED,
  p50         DOUBLE,             -- estimated from histogram if available
  p95         DOUBLE,
  p99         DOUBLE,
  PRIMARY KEY (series_id, period_type, period_ts)
);
-- Stored in columnar format (Parquet on S3 / ClickHouse) for analytical queries.
```

### 6.4 Log Storage Model

```
Each log line stored as JSON document in Elasticsearch:
{
  "_id":       "log_uuid",
  "timestamp": "2024-03-11T21:00:00.123Z",
  "tenant_id": "team-payments",
  "level":     "ERROR",
  "service":   "payment-service",
  "host":      "payment-pod-7",
  "message":   "Payment gateway timeout after 30s",
  "fields":    { "gateway": "razorpay", "amount": 500 },
  "trace_id":  "abc123"
}

Elasticsearch index strategy:
  → Daily roll-over index: logs-2024.03.11, logs-2024.03.12, etc.
  → Index alias: logs-current → points to today's index.
  → Shard per day: 30 shards per index (one per day of month).
  → After 7 days: close hot index → transition to warm tier.
  → After 30 days: export to S3 Parquet → delete from Elasticsearch.
```

---

## 7. High-Level Architecture

```text
+-----------------------  Data Sources  -------------------------+
| Services (push)  |  Agents (scrape /metrics)  |  Log shippers |
+----+-------------+---+------------------------+--------+-------+
     |                 |                                 |
     v                 v                                 v
+----+-----------------+-----------+        +------------+-------+
|       Ingest Gateway             |        |   Log Ingest       |
|  (Auth, Validation, Rate Limit)  |        |   Gateway          |
+------------------+---------------+        +----------+---------+
                   |                                   |
                   v                                   v
        +----------+-----------+          +------------+---------+
        |   Kafka Cluster       |          |   Kafka Cluster      |
        |   (metric_events)     |          |   (log_events)       |
        |   500 MB/s capacity   |          |   50 MB/s capacity   |
        +---+-----------+-------+          +-----+------+---------+
            |           |                        |      |
            v           v                        v      v
     +------+---+ +-----+-----+         +--------+  +--+--------+
     |Aggregation| |Raw TSDB   |         |Elastic- |  |S3 Cold   |
     |Stream     | |Writer     |         |search   |  |Log Store |
     |Processor  | |(Chunk     |         |Writer   |  |(Parquet) |
     |(Flink/    | | Store)    |         |(Hot 7d) |  |(90d)     |
     | Spark SS) | |           |         +---------+  +----------+
     +------+----+ +-----------+
            |
     +------+-------+----------+
     |               |          |
     v               v          v
+--------+     +--------+   +--------+
|1-min   |     |1-hour  |   |1-day   |
|Rollup  |     |Rollup  |   |Rollup  |
|Store   |     |Store   |   |Store   |
|(Click- |     |(Click- |   |(S3 /   |
| House) |     | House) |   | Parquet|
+--------+     +--------+   +--------+
     |               |          |
     +-------+--------+----------+
             |
     +-------+-------+
     | Query Service  |
     | (PromQL parser |
     |  + plan)       |
     +-------+-------+
             |
     +-------+-------+
     | Alert Engine  |
     | (Rule eval,   |
     |  Notification)|
     +-------+-------+
             |
     +-------+-------+
     | Dashboard     |
     | Service       |
     | (Grafana-like)|
     +---------------+
```

---

## 8. Detailed Component Design

### 8.1 Ingest Gateway

**First point of contact for all incoming data.**

```
Responsibilities:
  1. Authentication: Validate API key / service account.
  2. Authorization: Verify tenant_id matches credentials.
  3. Schema validation: Required fields present, metric names valid,
     timestamp within acceptable window (not too far in past or future).
  4. Rate limiting: Per-tenant write quota enforcement.
     e.g., team-payments: max 100K data points/sec.
     Enforced via Redis sliding window counter.
  5. Schema normalization: Convert Prometheus text format or JSON to
     internal protobuf format.
  6. Publish to Kafka: batch points into Kafka ProducerRecord with
     partition key = series_id (ensures ordering per series).
  7. Return 202 Accepted immediately (async; don't wait for storage write).

Rate limiting details:
  → Redis key: rate:{tenant_id}:{minute_bucket}
  → INCR with EX 60 (auto-expires after 1 minute).
  → If count > quota: HTTP 429 with Retry-After header.
  → Soft limit: warn at 80%; hard limit: reject at 100%.
  → Different quotas per tier: free=10K pts/sec, pro=100K, enterprise=unlimited.

Timestamp validation:
  → Accept data up to 1 hour in the past (late-arriving metrics from crashed services).
  → Reject data more than 10 minutes in the future (clock skew detection).
  → Why past tolerance: services may buffer locally on network outage and flush on recovery.
```

### 8.2 Kafka Topic Design

**Kafka is the central nervous system — decouples ingest from storage.**

```
Topic: metric_events
  Partition key: series_id % num_partitions
  Partitions: 200
  Retention: 48 hours (allows consumer replay if storage is down temporarily)
  Replication factor: 3
  Compression: LZ4 (fast, good ratio for metric data)

Why partition by series_id?
  → All data points for the same series go to the same partition.
  → Preserves ordering within a series (important for rate() computation).
  → Aggregation stream processor sees consecutive points of same series → efficient windowing.

Topic: log_events
  Partition key: service_name hash
  Partitions: 50
  Retention: 48 hours
  Compression: GZIP (logs compress better than metrics; latency less critical)

Topic: aggregated_metrics
  Published by Aggregation Stream Processor.
  Consumers: ClickHouse writer, Alert Engine, Cold storage writer.

Topic: alert_events
  Published by Alert Engine when rules fire or recover.
  Consumers: Notification Service (Slack, PagerDuty, email).
```

### 8.3 Raw Time-Series DB Writer (TSDB)

**Stores full-resolution data for the short retention window (2 hours).**

```
Technology choice: Custom chunk-based TSDB (like Prometheus TSDB or VictoriaMetrics).

Design:
  → Each series has an in-memory write buffer (head chunk).
  → Points arrive → appended to in-memory chunk.
  → When chunk is full (120 points ≈ 30 min) or chunk is 30 min old:
    → Compress chunk using Gorilla encoding.
    → Flush to SSD-backed chunk store.
  → Chunks older than 2 hours → deleted (TTL-based GC).

Write path:
  Kafka consumer (TSDB writer) → decode protobuf → lookup series_id →
  append to in-memory chunk → periodically flush to disk.

Chunk store layout on disk:
  chunks/{series_id}/{chunk_start_ts}.chunk
  → One file per chunk per series.
  → Files up to 2 hours old; GC deletes older ones.

Memory management:
  Head chunks kept in memory for fast appends.
  Memory usage: 1M active series × 5 KB avg chunk size = 5 GB RAM for head chunks.
  Completed chunks on SSD: 1.4 TB (from capacity estimate).

Read path for raw queries (last 2 hours):
  → Lookup series_id from metadata index.
  → Scan chunk files covering requested time range.
  → Decompress → stream data points to query engine.
```

### 8.4 Aggregation Stream Processor — The Most Complex Component

**Transforms high-volume raw points into low-volume rollups.**

**Technology**: Apache Flink (stateful stream processing) or Kafka Streams.

#### 8.4.1 Why Streaming Aggregation (Not Batch)?

```
Option A: Batch (run Spark job every minute to aggregate last minute's data).
  Problem: Spark job startup takes 30-60 seconds → aggregation available 1-2 min late.
           Queries for "last 5 minutes" always have stale data.
           Complex for real-time alerting (alert latency = batch interval).

Option B: Streaming (Flink continuously aggregates as data arrives).
  Benefit: Aggregates available within seconds of data arriving.
           Alert engine evaluates fresh aggregates continuously.
           Watermark-based late data handling built in.

Decision: Streaming aggregation with Flink.
```

#### 8.4.2 Tumbling Window Aggregation (1-Minute Rollup)

```
Flink job: MetricAggregator

Input: Kafka topic metric_events.
Output: Kafka topic aggregated_metrics (1-min rollups).

Processing logic per partition (keyed by series_id):

  TumblingEventTimeWindow(60 seconds):
    For each (series_id, 1-minute window):
      - Accumulate all data points in the window.
      - Compute: min, max, sum, count, avg.
      - If histogram: merge bucket counts; estimate p50/p95/p99.
      - Emit: AggregateRecord { series_id, window_start, min, max, sum, count, p50, p95, p99 }

Event time vs processing time:
  Event time: timestamp embedded in the data point (what the metric actually represents).
  Processing time: when the message arrives at Flink (subject to network delay).

Why event time?
  A service uploads buffered metrics after recovering from network outage.
  Event timestamps are 45 minutes ago → must go into their correct time window.
  Processing time would put them in the current window → wrong aggregation.

Watermark:
  Flink advances watermark when it has seen events up to a certain timestamp.
  Watermark = max(event_timestamps_seen) - 60s (60s allowed lateness).
  Window triggers when watermark passes window end time.
  Late events (arrive after watermark) → fire into already-closed window (late emission).
  After max_lateness (e.g., 10 min): late events dropped (trade-off: correctness vs resource).
```

#### 8.4.3 Multi-Level Rollup Strategy

```
Level 1: 1-minute rollup (from raw points, streaming)
  → Produced by Flink tumbling window job.
  → Input: raw Kafka metric_events.
  → Output: ClickHouse table metric_1m_aggregates.
  → Retention: 30 days.

Level 2: 1-hour rollup (from 1-minute rollups, streaming)
  → Input: Kafka aggregated_1m (written by Level 1).
  → Aggregate: 60 one-minute records → 1 one-hour record.
  → min = min(min_1m), max = max(max_1m), sum = sum(sum_1m), count = sum(count_1m).
  → avg = total_sum / total_count (NOT avg of avgs — common mistake!).
  → Output: ClickHouse table metric_1h_aggregates.
  → Retention: 1 year.

Level 3: 1-day rollup (from 1-hour rollups, batch nightly)
  → Scheduled Spark job: aggregate 24 hourly records → 1 daily record.
  → Output: Parquet on S3/GCS partitioned by (metric_name, date).
  → Retention: 5 years (for capacity planning trend analysis).

WHY NOT avg of avgs (critical correctness point):
  1-min window 1: sum=500, count=10 → avg=50
  1-min window 2: sum=100, count=100 → avg=1
  
  Wrong: avg_1h = (50 + 1) / 2 = 25.5
  Right: avg_1h = (500 + 100) / (10 + 100) = 5.45

  Always aggregate sum and count separately; compute avg at query time from them.
```

#### 8.4.4 Histogram Merging In Aggregation

```
Histograms from multiple service instances must be merged correctly.

Instance A report for 1 minute:
  bucket[le=10ms]:  500
  bucket[le=50ms]:  200
  bucket[le=100ms]: 50
  sum: 12000, count: 750

Instance B report for 1 minute:
  bucket[le=10ms]:  300
  bucket[le=50ms]:  100
  bucket[le=100ms]: 30
  sum: 8000, count: 430

Merged (add bucket counts element-wise):
  bucket[le=10ms]:  800  (500+300)
  bucket[le=50ms]:  300  (200+100)
  bucket[le=100ms]: 80   (50+30)
  sum: 20000, count: 1180

P99 estimation from merged histogram:
  Target count = 99th percentile = 1180 × 0.99 = 1168.2
  Cumulative: le=10ms: 800, le=50ms: 1100, le=100ms: 1180
  P99 falls between 50ms and 100ms bucket.
  Linear interpolation:
    fraction = (1168.2 - 1100) / (1180 - 1100) = 68.2 / 80 = 0.8525
    P99 = 50 + (100 - 50) × 0.8525 = 50 + 42.6 ≈ 92.6ms

This works additive across instances because bucket boundaries are shared.
This is why: histograms are preferred over summaries for distributed systems.
```

### 8.5 ClickHouse — Aggregated Metrics Store

**ClickHouse is the right choice for time-series aggregated data.**

```
Why ClickHouse?
  → Columnar storage: queries select few columns from billions of rows → minimal I/O.
  → Vectorized execution: processes data in SIMD batches → fast aggregation.
  → MergeTree engine: data sorted by (series_id, timestamp) → fast time range scans.
  → Built-in TTL: automatically drop data older than retention period.
  → Compression: ClickHouse achieves 10–50× compression on time-series data.
  → Horizontal scaling: Distributed table across shards.

Table design:
  CREATE TABLE metric_1m_aggregates (
    series_id  UInt64,
    metric_name LowCardinality(String),   -- stored as dictionary (low memory)
    tags       Map(String, String),
    tenant_id  LowCardinality(String),
    period_ts  DateTime,
    min_val    Float64,
    max_val    Float64,
    sum_val    Float64,
    count_val  UInt64,
    p50        Nullable(Float64),
    p95        Nullable(Float64),
    p99        Nullable(Float64)
  )
  ENGINE = MergeTree()
  PARTITION BY toYYYYMM(period_ts)         -- monthly partitions for easy pruning
  ORDER BY (tenant_id, metric_name, series_id, period_ts)
  TTL period_ts + INTERVAL 30 DAY          -- auto-delete after 30 days
  SETTINGS index_granularity = 8192;

Sharding:
  → Distribute by series_id across N ClickHouse shards.
  → Each shard has 2 replicas (with ZooKeeper coordination).
  → Query: scatter-gather across shards → merge results.
```

### 8.6 Query Service

**Translates user queries into storage-layer reads with query planning.**

#### 8.6.1 Query Planning (Resolution Selection)

```
Smart resolution selection based on requested time range:

Requested range → Storage tier used:
  < 2 hours     → Raw TSDB (full resolution, every 15s)
  2h – 30 days  → ClickHouse 1-minute aggregates
  30d – 1 year  → ClickHouse 1-hour aggregates
  > 1 year      → S3 Parquet 1-day aggregates (via Athena/Spark)

WHY this matters:
  Query for "last 24 hours, 1-minute resolution":
  → 1440 points per series (fine).
  → Served from ClickHouse 1m aggregates → fast.

  Query for "last 6 months, 1-minute resolution":
  → 259,200 points (too much data, slow to render, over-resolution).
  → System auto-downsample to 1-hour → 4,320 points (reasonable).
  → User rarely needs per-minute data for 6-month trend view.
```

#### 8.6.2 PromQL Evaluation

```
PromQL (Prometheus Query Language) — the industry standard for metric queries.

Common PromQL operators:

rate(metric[5m]):
  Computes per-second rate of increase of a counter over 5-minute window.
  = (last_value - first_value) / duration_in_seconds
  Handles counter resets automatically.

irate(metric[5m]):
  Instantaneous rate using last two data points in window.
  More responsive to spikes; less stable for long-window alerting.

sum by (service)(rate(http_requests_total[5m])):
  Aggregate rate across all series, grouped by "service" tag.
  Result: one series per unique service value.

histogram_quantile(0.95, rate(request_duration_ms_bucket[5m])):
  Computes P95 latency from histogram data.
  rate() applied to each bucket → per-second rate of observations in each bucket.
  histogram_quantile uses these rates to estimate percentile.

Query execution engine:
  1. Parse PromQL → AST.
  2. Resolve series: SELECT series_id WHERE metric_name AND tags match.
  3. Determine time range and resolution → choose storage tier.
  4. Fetch data points (parallel fetches for multiple series).
  5. Evaluate AST bottom-up (functions applied in order).
  6. Return matrix result (series of [timestamp, value] pairs).

Caching:
  → Query result cache in Redis: key = hash(query + time_range_rounded).
  → TTL = 30s for real-time dashboards; 5 min for historical range.
  → Rounded time range: align to resolution boundary to maximize cache hits.
    "last 1 hour" normalizes to nearest minute boundary → same cache key for multiple users.
```

### 8.7 Alerting Engine — Rule Evaluation

**Continuously evaluates alert conditions and fires when thresholds are breached.**

#### 8.7.1 Alert Rule Evaluation Loop

```
Alert rule example:
  name: high_5xx_rate
  query: rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m]) > 0.05
  for: 5m        # condition must be true for 5 consecutive minutes before firing
  labels: { severity: critical, team: platform }
  annotations: { summary: "5xx rate > 5% on {{ $labels.service }}" }

Evaluation flow (runs every 1 minute per rule):

  1. Execute PromQL query against aggregated store (ClickHouse 1m).
  2. For each series in result (one per service):
     a. Is value > threshold (0.05)?
        YES → mark as "pending" (condition is true NOW).
        NO  → clear any pending state. Rule is safe.
     b. Has "pending" state lasted >= for duration (5 min)?
        YES → Transition to "firing" state → send alert.
        NO  → Stay "pending" (short spike, not yet alerting).
  3. Is rule "firing" but now condition false?
     YES → Transition to "resolved" → send resolution notification.

Why "for" duration (pending state)?
  Without it: a 1-second spike triggers alert + flood of notifications.
  With 5-minute "for": only sustained problems trigger alerts → fewer false positives.
  Trade-off: delayed detection (5 min) vs alert fatigue reduction.

Alert deduplication:
  → Alertmanager receives firing alerts → groups similar alerts.
  → Same alert firing from 10 pods → one grouped notification (not 10 separate).
  → Inhibition rules: if "cluster down" alert fires → suppress all individual service alerts.
  → Silences: acknowledge an alert → suppress notifications for defined window.
```

#### 8.7.2 Alert State Machine

```
INACTIVE (condition never true)
    │
    │ condition becomes true
    ▼
PENDING (condition true, but "for" duration not yet elapsed)
    │                                    │
    │ "for" duration elapsed              │ condition becomes false
    ▼                                    ▼
FIRING (alert sent; notifications dispatched)  → INACTIVE
    │
    │ condition becomes false
    ▼
RESOLVED (resolved notification sent) → INACTIVE
```

#### 8.7.3 Notification Routing

```
Alert fires → Alertmanager → route by labels:

Route tree:
  - match severity=critical:
      → PagerDuty (24x7 oncall escalation)
      → Slack #alerts-critical
  - match severity=warning:
      → Slack #alerts-warning
      → Email (business hours only)
  - match team=payments:
      → Override: always PagerDuty for payments team regardless of severity.
  - default:
      → Slack #alerts-general

Notification channels:
  - Slack: webhook API (formatted message with graph thumbnail).
  - PagerDuty: events API v2 (deduplication_key = alert_name + labels hash).
  - Email: SES/SendGrid with HTML template.
  - Webhook: generic HTTP callback for custom integrations.
```

### 8.8 Log Aggregation Pipeline

```
Log flow:
  Service → Log Shipper (Fluentd/Filebeat/Vector) → Kafka → Elasticsearch Writer → Elasticsearch

Log Shipper (runs as sidecar or daemonset per node):
  → Tails log files or reads from stdout/stderr.
  → Parses structured JSON logs natively.
  → For unstructured logs: attempts regex extraction of level, timestamp, message.
  → Adds host, pod, namespace metadata automatically.
  → Buffers in local disk (max 1GB); retries on Kafka unavailability.
  → Batch sends to Kafka (max 100ms batch delay to balance latency vs throughput).

Elasticsearch Writer (Kafka consumer):
  → Reads from log_events topic.
  → Bulk-indexes to Elasticsearch: POST /_bulk (500 docs per batch).
  → Retry on partial failures (individual doc failures).
  → 5-min lag between log emission and searchability (indexing takes time).

Log search:
  → Elasticsearch full-text search: match query on "message" field.
  → Exact filter: term query on "level", "service", "host" fields.
  → Time range: range query on "timestamp" field.
  → Results sorted by timestamp DESC.
  → Cursor-based pagination via search_after (not offset — Elasticsearch limits deep pages).
```

---

## 9. Storage Tiering And Retention

### 9.1 The Full Data Lifecycle

```
┌──────────────────────────────────────────────────────────────────────┐
│                     Data Lifecycle                                   │
│                                                                      │
│  Raw Data (TSDB)     1-min Aggr        1-hour Aggr    1-day Aggr    │
│  ──────────────      ───────────       ───────────    ──────────    │
│  Retention: 2h       Retention: 30d    Retention: 1y  Retention: 5y │
│  Storage: SSD RAM    Storage: CH       Storage: CH    S3 Parquet    │
│  Size: 1.4 TB hot    Size: 8.6 TB      Size: 1.7 TB   Size: ~100 GB │
│  Resolution: 15s     Resolution: 1m    Res: 1h        Res: 1d       │
└──────────────────────────────────────────────────────────────────────┘
```

### 9.2 Logs Tiering

```
Hot   (0–7 days):   Elasticsearch (SSD) — full text search, fast retrieval.
Warm  (7–30 days):  Elasticsearch (HDD/warm tier) — search slower, storage cheaper.
Cold  (30–90 days): S3 Parquet via Amazon Athena/Presto — query on-demand (minutes).
Archive (90d+):     S3 Glacier — regulatory hold; retrieval takes hours.

Tiering automation:
  → Elasticsearch ILM (Index Lifecycle Management) policy:
    "After 7 days: move to warm tier. After 30 days: export to S3 and delete from ES."
  → Export job: Elasticsearch Snapshot API → S3 bucket → convert to Parquet.

S3 query for cold logs:
  → Athena: SQL on Parquet files partitioned by (date, service).
  → "SELECT * FROM cold_logs WHERE date='2024-01-15' AND service='payment' AND level='ERROR'"
  → Latency: 10-60 seconds (batch query, not real-time).
  → Cost: $5 per TB scanned (vs continuous Elasticsearch cluster cost).
```

### 9.3 Compaction And GC

```
TSDB compaction:
  → Completed chunks (30-min windows) merged into larger 2-hour blocks.
  → Block = collection of chunks for all series in that 2-hour window.
  → Blocks indexed by min/max timestamp for fast range query pruning.
  → Blocks older than 2 hours: GC thread deletes them (frees disk).

ClickHouse background merges:
  → MergeTree engine: new data written as small Parts files.
  → Background merge: combine small parts → larger sorted parts → better compression.
  → Merged parts: deduplicate, re-sort by (series_id, timestamp).
  → Old parts deleted after successful merge.

TTL enforcement:
  → ClickHouse TTL config: period_ts + INTERVAL 30 DAY.
  → Background thread scans partitions; drops expired data by partition (efficient — whole partition delete).
```

---

## 10. Consistency, Idempotency, And Failure Handling

### 10.1 Idempotent Ingestion

```
Problem: Network retry causes same metric data point ingested twice.
→ Counter inflated incorrectly; gauge duplicated.

Solutions:
  For counters:
    → Re-ingesting same value at same timestamp is harmless:
      TSDB writer: "if point already exists at (series_id, timestamp) → skip."
      Dedup window: keep last-seen timestamp per series; skip if new_ts <= last_ts.

  For log lines:
    → Each log has a client-generated log_id (UUID).
    → Elasticsearch: _id = log_id → natural dedup (re-indexing same id overwrites, not duplicates).

  For aggregates:
    → Aggregation job deduplicates by (series_id, window_start).
    → ClickHouse INSERT with same primary key: deduplicated on merge.
    → At-least-once Kafka delivery → possible duplicate aggregates → ClickHouse handles on merge.
```

### 10.2 Late Data Handling

```
A service was network-partitioned for 30 minutes. On recovery, it pushes 30 min of buffered metrics.
These metrics have timestamps 30 minutes ago.

Handling:
  Ingest Gateway: accepts data up to 1 hour old.
  Kafka: published to correct time-ordered position (Kafka doesn't sort; time ordering is per-partition).
  Flink watermark: configured with 10-minute allowed lateness.
  For data older than watermark's allowed lateness past:
    → Late emission into already-closed and emitted windows.
    → ClickHouse: late aggregate record upserted (UPDATE on conflict → not available in all setups).
    → Alternative: Flink side output for very late events → separate repair pipeline.

Impact on alerts:
  → Alert that fired 30 minutes ago based on incomplete data may need re-evaluation.
  → Alert Engine re-evaluates last N periods on late data arrival → fires retrospective alert if warranted.
  → Or: tolerate slight inaccuracy in historical alerting (only current state matters for oncall).
```

### 10.3 Kafka Consumer Failure Recovery

```
Scenario: Aggregation Flink job crashes mid-window.

Recovery:
  → Flink checkpoints state every 30s to S3 (in-flight window accumulators saved).
  → On restart: Flink restores from last checkpoint.
  → Re-reads Kafka from last committed offset → reprocesses events since checkpoint.
  → Result: at-least-once processing (window may be duplicated → idempotent sink handles).

Kafka consumer lag monitoring:
  → Alert: consumer lag > 100K messages (means writers are falling behind ingest).
  → Scale out: add more Flink TaskManagers (parallelism up).
  → Max parallelism = number of Kafka partitions (200 for metrics).
```

---

## 11. Multi-Tenancy

```
Tenant isolation:
  → Every data point / log line tagged with tenant_id at ingest.
  → Kafka: all tenants on shared topics, tenant_id in message header.
  → ClickHouse: tenant_id in first position of ORDER BY (efficient per-tenant scans).
  → Elasticsearch: per-tenant index prefix: logs-{tenant_id}-{date}.
  → Query service: all queries automatically AND tenant_id = requesting_tenant.
    → Users cannot query other tenants' data (enforced server-side, not client trust).

Quota management:
  → Per-tenant write quota (data points/sec).
  → Per-tenant storage quota (GB).
  → Per-tenant query QPS (prevent one tenant from starving others).
  → Quotas stored in PostgreSQL; cached in Redis; enforced at Ingest Gateway.

Data isolation:
  → Tenant A cannot name a metric "tenant_b_secret_metric" and read it.
  → All metadata (series_metadata table) partitioned by tenant_id.
  → Series IDX = hash(tenant_id + metric_name + tags) → tenant_id embedded in hash space.
```

---

## 12. Observability On The Observability System

**The metrics system must monitor itself (meta-monitoring).**

```
Key self-metrics:
  ingestion_gateway_rate{tenant}:        data points/sec accepted, rejected.
  kafka_consumer_lag{topic,group}:       message backlog in Kafka.
  tsdb_write_latency_ms:                 TSDB chunk write time.
  clickhouse_insert_latency_ms:          aggregate insert time.
  query_latency_ms{percentile}:          query execution time distribution.
  alert_evaluation_duration_ms:          how long each rule takes to evaluate.
  storage_used_gb{tier}:                 hot/warm/cold storage utilization.

Self-alerting pitfall:
  → Metrics system down → cannot alert on its own failure.
  → Solution: dead man's switch.
    → Watchdog alert: "alert_watchdog" fires every 1 minute (always).
    → External system (PagerDuty) receives the heartbeat; if NOT received for 5 min → escalate.
    → This detects complete metrics system failure from outside.
```

---

## 13. Major Trade-Offs And Why

### 13.1 Push vs Pull Model For Metrics Collection

```
Pull (Prometheus model):
  → Collector scrapes /metrics endpoint on each service every 15s.
  → Service doesn't need to know where to send data.
  → Easy to detect dead services (scrape fails → alert).
  → Works well in Kubernetes (service discovery via k8s API).
  Downside: Harder for short-lived jobs (FaaS, batch jobs that complete before scrape).

Push (StatsD / Datadog agent model):
  → Services push data to a collector/aggregator.
  → Works for short-lived jobs (push on completion or buffer locally).
  → Multi-datacenter: services push to local agent → agent forwards to central.
  Downside: Service must know endpoint; overwhelming push storms possible.

Decision: Support BOTH.
  → Pull for long-lived services (Kubernetes pods, servers).
  → Push API for short-lived jobs, Lambda, serverless.
  → Agent as local aggregator (both receives push and does pull from local processes).
```

### 13.2 Stream Aggregation vs Batch Aggregation

```
Stream (Flink):
  → Low latency aggregates (seconds fresh).
  → Real-time alerting possible.
  → Complex state management (watermarks, late data).
  → Higher operational complexity.

Batch (Spark every 5 minutes):
  → Simpler, reliable, late data handled naturally (wait before processing).
  → 5+ minute alert latency.
  → Higher query latency for "last N minutes" dashboards.

Decision: Stream for 1-minute rollups (real-time dashboards and alerting).
           Batch nightly for 1-day rollups (historical trend analysis where freshness not critical).
```

### 13.3 ClickHouse vs TimescaleDB vs InfluxDB

| Aspect | ClickHouse | TimescaleDB | InfluxDB |
|---|---|---|---|
| Query language | SQL | SQL (PostgreSQL extension) | Flux / InfluxQL |
| Scale | PB-scale columnar | TB-scale | TB-scale |
| Compression | 10-50× | 5-10× | 8-12× |
| Joins | Supported | Full PG joins | Limited |
| Aggregation speed | Fastest (vectorized) | Fast | Fast |
| Operational complexity | High | Medium | Medium |

**Decision**: ClickHouse for aggregated metric store at scale. TimescaleDB acceptable for smaller deployments.

### 13.4 Elasticsearch vs ClickHouse For Log Search

```
Elasticsearch:
  → Full-text inverted index → fast fuzzy/keyword search.
  → Slower for aggregations over billions of records.
  → More memory-hungry (JVM).

ClickHouse:
  → Columnar → fast aggregations (count errors by service last hour).
  → Full-text search possible but slower per-document.
  → More efficient for structured log analytics.

Decision: Elasticsearch for interactive log search (filtering by keyword, message text).
           ClickHouse for log aggregations (error rate trends, top error messages by count).
           Logs can be dual-written to both if budget allows.
```

---

## 14. Interview-Ready Deep Dive Talking Points

**"How do you handle 1M metric data points/second?"**
> Kafka as buffer (200 partitions, 500 MB/s capacity) absorbs burst writes. Ingest Gateway validates, normalizes, and publishes to Kafka without blocking on storage. TSDB Writers (parallel consumers) append to in-memory chunks; flushes to SSD asynchronously. At-least-once Kafka delivery + TSDB dedup on (series_id, timestamp) ensures no duplicate points.

**"How do you compute P99 latency across a distributed service?"**
> Services report histograms (not raw samples or pre-computed percentiles). Bucket boundaries are standardized across all instances. Histograms are merged by summing bucket counts element-wise. P99 estimated from merged histogram using linear interpolation within the bucket that contains the 99th percentile cumulative count. This is the ONLY correct way — summaries cannot be merged across instances.

**"How does alerting work with sub-minute detection?"**
> Alert Engine queries 1-minute aggregates from ClickHouse (available within seconds of window close, thanks to Flink streaming aggregation). Rule evaluated every 1 minute. "for: 5m" pending state prevents spikes from firing. Sustained breaches for 5+ minutes → alert fires. Total detection latency: up to 6 minutes worst case (1 streaming lag + 5 pending duration).

**"How do you query 6 months of data efficiently?"**
> Query planner selects appropriate storage tier based on requested range × resolution. Last 2 hours → raw TSDB. 2h–30d → ClickHouse 1-minute aggregates. 30d–1y → ClickHouse 1-hour aggregates. >1y → S3 Parquet via Athena. ClickHouse columnar format + time-partitioning → only relevant partitions scanned. Query result cached in Redis (rounded time boundaries maximize cache hit rate).

**"What happens if Flink crashes mid-aggregation window?"**
> Flink checkpoints in-flight window state to S3 every 30 seconds. On restart, restores from last checkpoint. Re-reads Kafka from committed offset → reprocesses events since checkpoint. Idempotent ClickHouse insert handles duplicate aggregates (deduplication on merge). Maximum data loss: 30s of aggregates (equal to checkpoint interval).

---

## 15. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Scope: metrics vs logs vs traces; metric types (counter/gauge/histogram) |
| 5–12 min | Capacity: 1M pts/sec, storage tiers, Kafka sizing |
| 12–20 min | Ingestion pipeline: Gateway → Kafka → TSDB Writer (push + pull models) |
| 20–30 min | Aggregation engine: Flink windows, event time, watermarks, histogram merging |
| 30–38 min | Query planning, PromQL evaluation, ClickHouse tier selection, caching |
| 38–43 min | Alerting engine, state machine, deduplication, Alertmanager routing |
| 43–45 min | Trade-offs, multi-tenancy, failure handling |

---

## 16. Extensions To Mention If Time Permits

- **Exemplars**: Random sample traces linked to histogram observations (P99 spike → click to see the exact trace that caused it — OpenTelemetry exemplars).
- **Anomaly detection**: ML-based baseline learning per metric series; alert on deviation from predicted range (seasonal patterns removed).
- **Adaptive sampling for logs**: At high error rate, sample 100% of logs. At normal rate, sample 10% → massive cost reduction.
- **Cost attribution**: Per-tenant, per-service cost dashboard (how many data points ingested, how much storage used → chargeback to teams).
- **Grafana integration**: The query API is Grafana-compatible → teams plug in Grafana as dashboard layer without building custom UI.
- **Continuous profiling**: Flame graphs sampled from production services (pprof) stored alongside metrics (Go/Java profilers).
- **SLO tracking**: Define SLOs (e.g., "99.9% of requests served in < 200ms per week") → automatically computed from histogram data → burn rate alerts.
