# HLD — Google Analytics: User Analytics Dashboard & Pipeline

> **Interview Type:** SDE 3 System Design
> **Difficulty:** Hard
> **Tags:** Analytics, Data Pipeline, Real-time Processing, Time-series, OLAP, Stream Processing

---

## 1. Requirements Clarification

### 1.1 Functional Requirements

| # | Requirement |
|---|---|
| F1 | Website owners embed a JS tracking snippet → browser sends events (page views, clicks, conversions) to our collection endpoint |
| F2 | Store raw events durably and immutably |
| F3 | Aggregate events in real-time AND in batch for different report windows |
| F4 | Serve analytics dashboard with metrics: active users, page views, sessions, bounce rate, conversion rate, traffic sources |
| F5 | Support filtering by date range, country, device type, browser, referrer, UTM campaign parameters |
| F6 | Support custom events (user-defined event names + properties) |
| F7 | Real-time view: active users in last 30 minutes (near real-time, ~1 min lag okay) |
| F8 | Historical reports: up to 5 years of aggregated data |

### 1.2 Non-Functional Requirements

| Dimension | Target |
|---|---|
| **Scale** | 10M websites tracked; 1B+ events/day ingested |
| **Ingestion Latency** | < 200ms acknowledgement from collection endpoint (fire and forget acceptable) |
| **Dashboard Query Latency** | < 2s for pre-aggregated reports; < 10s for custom ad-hoc queries |
| **Availability** | 99.99% for ingestion endpoint; 99.9% for dashboard |
| **Data Durability** | No event loss after acknowledgement (replicated at least 3×) |
| **Consistency** | Eventual — reports can lag by up to a few minutes for real-time, hours for deep historical |
| **Data Retention** | Raw events: 90 days. Aggregated metrics: 5 years |
| **Multi-tenancy** | Each customer (website/app) sees only their own data |

### 1.3 Out of Scope
- ML-based anomaly detection and predictive analytics
- Session replay / heatmaps
- Advertising attribution (DoubleClick territory)
- User-level PII storage (GDPR-compliant; anonymous IDs only)

---

## 2. Capacity Estimation

### Traffic & Ingestion
```
10M websites × avg 100 events/day = 1B events/day
Write RPS  = 1B / 86,400 ≈ 11,600 RPS (baseline)
Peak RPS   = 3× baseline ≈ 35,000 RPS (business hours spike)
```

### Storage — Raw Events
```
Avg event payload (compressed) ≈ 200 bytes
Daily raw data = 1B × 200 B = 200 GB/day
90-day retention = 200 GB × 90 ≈ 18 TB raw (before replication)
With 3× replication = ~54 TB
```

### Storage — Aggregated Data
```
Aggregation reduces 1B events → ~50M rows/day in pre-computed tables
50M × 100 bytes avg row = 5 GB/day aggregated
5 years = 5 × 365 × 5 GB ≈ 9 TB aggregated (much more manageable)
```

### Bandwidth
```
Write: 35,000 RPS × 200 B = ~7 MB/s inbound
Dashboard reads: 1M concurrent users × occasional queries ≈ moderate; served from OLAP cache
```

---

## 3. API Design

### 3.1 Collection API (Public — embedded in JS snippet)

```http
POST /collect
Content-Type: application/json

{
  "tid": "UA-12345-1",          // tracking ID (website identifier)
  "cid": "a3f2bc94",            // client ID (anonymous, cookie-based)
  "t":   "pageview",            // hit type: pageview | event | transaction
  "dp":  "/home",               // document path
  "dt":  "Home Page",           // document title
  "dr":  "https://google.com",  // referrer
  "ul":  "en-US",               // user language
  "sr":  "1920x1080",           // screen resolution
  "uip": "203.0.113.42",        // user IP (for geo-resolution; discarded after)
  "ts":  1712101200000,          // client-side timestamp (ms)
  "ec":  "button",              // event category (for type=event)
  "ea":  "click",               // event action
  "el":  "signup-cta",          // event label
  "ev":  1                      // event value
}

Response: HTTP 204 No Content  (fire-and-forget)
```

> **Design note:** Also accept a 1×1 transparent GIF GET request as fallback for environments where CORS blocks POST (legacy GA behaviour).

### 3.2 Reporting API (Internal — dashboard backend)

```http
# Real-time active users
GET /api/v1/reports/{property_id}/realtime/active-users
Response: { "active_users": 4231, "as_of": "2024-04-03T10:00:00Z" }

# Aggregated report
GET /api/v1/reports/{property_id}/metrics
  ?start_date=2024-03-01
  &end_date=2024-03-31
  &dimensions=country,device_type
  &metrics=pageviews,sessions,bounce_rate
  &filters=country==IN;device_type==mobile

# Custom event funnel
POST /api/v1/reports/{property_id}/funnel
  { "steps": ["page_view:/checkout", "event:purchase"], "date_range": {...} }
```

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CLIENT SIDE                                       │
│   Browser / Mobile App                                                      │
│   [JS Tracking Snippet / SDK]                                               │
└─────────────────────────────────────────────┬───────────────────────────────┘
                                              │  POST /collect (HTTPS)
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        COLLECTION LAYER                                     │
│                                                                             │
│   CDN / Anycast DNS  →  Collection Servers (stateless, horizontally scaled) │
│   [Geo-routed, Global PoPs]      [Validate → Enrich → Kafka Produce]        │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │  Kafka Topic: raw_events
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       STREAM PROCESSING LAYER                               │
│                                                                             │
│   ┌──────────────────────────┐    ┌───────────────────────────────────────┐ │
│   │  Real-Time Processor     │    │  Batch Processor (Apache Spark)       │ │
│   │  (Apache Flink)          │    │  [Hourly / Daily aggregation jobs]    │ │
│   │  - Active user windows   │    │  [Reads from raw event store]         │ │
│   │  - 1-min sliding windows │    │  [Writes to OLAP store]               │ │
│   └──────────┬───────────────┘    └───────────────┬───────────────────────┘ │
└──────────────┼────────────────────────────────────┼────────────────────────┘
               │                                    │
               ▼                                    ▼
┌──────────────────────────┐     ┌──────────────────────────────────────────┐
│  Real-Time Store         │     │  OLAP / Analytics Store                  │
│  (Redis / Druid RT)      │     │  (Apache Druid / ClickHouse / BigQuery) │
│  [Active users, 30-min   │     │  [Pre-aggregated dimensions + metrics]   │
│   sliding window counts] │     │  [Historical, queryable in < 2s]         │
└──────────────────────────┘     └──────────────────────────────────────────┘
               │                                    │
               └────────────────┬───────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     QUERY / SERVING LAYER                                   │
│                                                                             │
│   Dashboard API Service  →  Redis Query Cache  →  OLAP Store               │
│   [Route query to RT or historical based on time range]                    │
│   [Auth, multi-tenancy isolation, rate limiting]                            │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   ▼
                         ┌──────────────────┐
                         │  Dashboard UI    │
                         │  (React SPA)     │
                         └──────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                     COLD / RAW STORAGE                                      │
│   Kafka → (Collection Sink) → Object Storage (GCS / S3)                    │
│   [Partitioned by property_id + date, Parquet format, 90-day retention]    │
│   [Used for batch re-processing and audit]                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Component Deep Dives

### 5.1 Deep Dive — Collection & Ingestion Pipeline

**Goal:** Accept billions of events/day with ultra-low latency acknowledgement, high availability, and zero data loss after ACK.

#### Collection Server Design
```
Browser  ──HTTPS──►  CDN (Anycast)  ──►  Collection Server (stateless)
                                              │
                                    1. Validate (tid exists, payload schema)
                                    2. Parse & enrich:
                                       - Geo-resolve IP → (country, city) → DROP IP
                                       - Parse User-Agent → (device, OS, browser)
                                       - Normalize timestamp (client vs server clock skew)
                                    3. Produce to Kafka (async, batched)
                                    4. Return HTTP 204 immediately
```

**Clock Skew Handling:**
- Client sends `ts` (client timestamp) AND server stamps `server_ts` at receipt
- If `abs(server_ts - ts) > 4 hours` → use `server_ts` (avoids time-traveller events)
- Collection servers are stateless; no session affinity needed

**Kafka Topic Design:**
```
Topic: raw_events
Partitions: 1,024 (high parallelism)
Partition key: property_id (ensures all events for one website go to same partition set)
Replication factor: 3
Retention: 7 days (event data also sinks to GCS for 90-day retention)
```

> **Why 1024 partitions?** 35,000 peak RPS / ~34 events per partition per second → manageable per-broker load, and allows scaling Flink consumers to 1024 parallel tasks.

**Idempotent Producer:**
- Kafka producer set to `acks=all`, `enable.idempotence=true`
- Ensures exactly-once produce semantics; no duplicate events in Kafka

#### Handling Bot Traffic
- Detect bots via User-Agent string matching (IAB/ABC International list)
- Throttle known bot IPs at CDN layer (WAF rules)
- Server-side JS challenge for suspicious clients (CAPTCHAless invisible verification)
- Mark bot events in payload; filtered from analytics but stored for audit

---

### 5.2 Deep Dive — Real-Time Processing (Apache Flink)

**Goal:** Power the "real-time" dashboard panel — active users in last 30 minutes, page views in last 5 minutes.

#### Session Window vs. Sliding Window

```
┌─────────────────────────────────────────────────────┐
│  Flink Job: RealtimeMetricsJob                      │
│                                                     │
│  Source: Kafka raw_events                           │
│     │                                               │
│     ▼                                               │
│  Filter (bot events, invalid property IDs)          │
│     │                                               │
│     ▼                                               │
│  Key by: property_id                                │
│     │                                               │
│     ├──► Sliding Window [30min, slide 1min]         │
│     │      → count distinct cid (active users)      │
│     │      → emit to Redis as SET per property_id   │
│     │                                               │
│     └──► Tumbling Window [1min]                     │
│            → count pageviews, events by type        │
│            → write to Druid real-time ingestion     │
└─────────────────────────────────────────────────────┘
```

**Counting Distinct Users at Scale:**
- Exact distinct count (HyperLogLog is 99.8% accurate) → trade off ~0.2% error for O(1) memory
- Use **HyperLogLog** data structure per sliding window per property
- Redis stores HLL sketches: `PFADD prop:12345:active {cid1} {cid2}` → `PFCOUNT prop:12345:active`
- Window cleanup: TTL set to 35 minutes per key

**Late Data Handling:**
- Watermark = `max_seen_event_ts - 2 minutes` (allow up to 2 min late arrivals from mobile clients)
- Events arriving after watermark → dropped for real-time; still stored in raw for batch correction

---

### 5.3 Deep Dive — Batch Processing & OLAP Store

**Goal:** Power historical dashboard reports with sub-2s query latency for arbitrary dimension combinations.

#### Lambda Architecture Approach

```
Raw Events (GCS / S3)
        │
        ▼
  Spark Batch Jobs (hourly + daily)
        │
        ▼
  Pre-Aggregated Tables in ClickHouse / Druid

  Dimensions: property_id, date, hour, country, device, browser, os, campaign, source, medium
  Metrics: pageviews, sessions, new_users, bounce_rate, avg_session_duration, conversions
```

**ClickHouse Schema Design (example):**
```sql
CREATE TABLE pageviews_hourly (
    property_id   UInt64,
    event_date    Date,
    event_hour    UInt8,
    country       LowCardinality(String),
    device_type   LowCardinality(String),    -- desktop | mobile | tablet
    browser       LowCardinality(String),
    source        String,                    -- UTM source
    medium        String,                    -- UTM medium
    pageviews     UInt64,
    sessions      UInt64,
    new_users     UInt64,
    bounces       UInt64,
    total_duration UInt64                    -- sum in seconds
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (property_id, event_date, event_hour, country, device_type)
;
```

**Why ClickHouse over Druid?**
- ClickHouse: Better for SQL-native ad-hoc queries, easier ops, column-store
- Druid: Better for sub-second time-series roll-ups, built-in real-time ingestion, TimeSeries native
- **Trade-off:** Use Druid for real-time ingestion; ClickHouse for deep historical (5yr) queries

#### Re-processing / Backfill
- Spark batch jobs are idempotent: `INSERT OVERWRITE` for the processed partition
- If a bug is found in aggregation logic → re-run Spark job for affected date range from raw GCS data
- Raw events on GCS act as the "source of truth" log

---

### 5.4 Deep Dive — Query Serving Layer & Dashboard API

**Goal:** Route queries to the right store, ensure sub-2s SLA, enforce multi-tenancy, and cache aggressively.

#### Query Routing Logic
```
Dashboard API receives query
        │
        ├─► If time range is "last 30 min" / "today (last 1hr)"
        │        → Hit Redis (HLL for active users) + Druid real-time segments
        │
        └─► If time range is historical (>1 hr ago)
                 → Hit ClickHouse (pre-aggregated hourly/daily tables)
                 → If not in cache, query ClickHouse → store result in Redis with 5-min TTL
```

**Multi-Tenancy Isolation:**
- Every query is scoped by `property_id` (tenant ID)
- `property_id` is a mandatory WHERE clause added server-side before forwarding to OLAP
- API tokens are scoped to one or more `property_id`s — enforced at API Gateway level
- Row-level security in ClickHouse: per-user policies via role bindings

**Query Cache (Redis):**
```
Key:   "report:{property_id}:{query_hash}"
Value: Serialized JSON result set
TTL:   5 minutes for recent data; 1 hour for historical (changes only on batch job run)
```

- Query hash = SHA-256 of (property_id + metrics + dimensions + filters + date_range)
- Cache invalidated by batch job on completion (publish invalidation event to Redis Pub/Sub)

---

## 6. Scalability & Resilience

### 6.1 Horizontal Scaling Strategy

| Component | Scaling Approach |
|---|---|
| Collection Servers | Stateless; auto-scale behind ALB based on RPS |
| Kafka | Add brokers + increase partitions (requires topic reconfiguration) |
| Flink | Add task manager slots; auto-scale via Kubernetes HPA |
| ClickHouse | Horizontal sharding by `property_id` hash; each shard has 2 replicas |
| Redis | Redis Cluster (consistent hashing across 16384 slots); 3 shards × 2 replicas |
| Dashboard API | Stateless; auto-scale behind ALB |

### 6.2 Hot Property Problem
**Problem:** A viral website (e.g., major news site) suddenly sends 100× normal traffic.

**Solutions:**
- **Collection:** Anycast + CDN absorbs at edge; stateless collection servers scale horizontally
- **Kafka:** Hot property's events land on same partition set → may become hot partitions
  - Mitigation: Sub-partition by `property_id % sub_partition_count` inside the partition key
  - Or: dedicated Kafka partitions for top-N high-volume properties (identified proactively)
- **Dashboard Queries:** Cache at Redis with longer TTL for viral properties; serve stale-but-fast

### 6.3 Failure Modes & Mitigations

| Failure | Impact | Mitigation |
|---|---|---|
| Collection server crash | Drop in-flight events | In-memory buffer persisted to local disk before crash (WAL); restart picks up |
| Kafka broker failure | Temporary ingestion pause | Replication factor=3; leader election in <30s; collection servers buffer locally |
| Flink job failure | Real-time metrics stale | Flink checkpoints to GCS every 30s; restart from checkpoint |
| ClickHouse node down | Slow/failed dashboard queries | Each shard has replica; query routed to replica automatically |
| Redis cache failure | Dashboard queries hit OLAP directly | Graceful fallback: query OLAP with slightly higher latency; Redis cluster recovers |
| GCS unavailability | Batch job failures | GCS SLA 99.99%; retry with exponential backoff; run batch job on resumption |

### 6.4 Rate Limiting
- **Per client (IP/tracking ID):** Max 2,000 events/min → return HTTP 429 at CDN layer
- **Per property (tracking ID):** Plan-based quota (e.g., 10M events/month for free tier)
- **Dashboard API:** 100 queries/min per API token; heavier quotas for paid tiers

### 6.5 Data Durability Guarantees
```
Collection Server → [Kafka, acks=all, replication=3] → GCS Sink (durability checkpoint)
                                                             │
                                                         Parquet files
                                                         (immutable, versioned)
```
- After event is ACKed by Kafka (post `acks=all`), durability is guaranteed
- GCS sink ensures data survives even if Kafka retention window expires

---

## 7. Data Retention & Privacy (GDPR / CCPA)

| Data Type | Retention | Notes |
|---|---|---|
| Raw events (GCS) | 90 days | Auto-deleted via lifecycle policy |
| Aggregated metrics | 5 years | No PII; safe to retain |
| IP addresses | Not stored | Resolved to geo at collection time, then discarded |
| Client ID (cid) | Persistent cookie (~2 yr) | Anonymous random UUID; not linked to PII |
| User-ID linking | Optional (website-owner opt-in) | Treated as sensitive; encrypted at rest |

**Right to Erasure:** Since raw events store anonymous CIDs (not PII), no erasure required by default. If property owner uses user-ID feature, a deletion pipeline re-writes affected Parquet files (expensive but rare; async job).

---

## 8. Observability

### 8.1 Key Metrics to Monitor

```
Ingestion health:
  - Collection server RPS (p95, p99 latency)
  - Kafka lag per consumer group (Flink, GCS sink)
  - Kafka produce error rate

Processing health:
  - Flink checkpoint duration
  - Flink backpressure ratio per operator
  - Batch job duration (SLA: hourly job completes within 45 min)

Dashboard health:
  - Dashboard API p95 / p99 query latency
  - Redis cache hit rate (target: >90%)
  - ClickHouse query latency (p99)
  - Error rate on reporting endpoints
```

### 8.2 Tooling

| Pillar | Tool |
|---|---|
| Metrics | Prometheus + Grafana (dashboards per component) |
| Logs | Structured JSON → Google Cloud Logging / ELK |
| Traces | OpenTelemetry → Jaeger (trace collection request end-to-end) |
| Alerting | PagerDuty via Grafana alerts on SLO breaches |

**SLOs:**
- Ingestion: 99.99% of events ACKed within 200ms
- Dashboard queries: 99th percentile < 2s
- Real-time active users: lag < 2 minutes

---

## 9. Security

| Concern | Mitigation |
|---|---|
| Auth (Collection) | Tracking ID (tid) validated; not a secret — spoofing mitigated by server-side data validation |
| Auth (Dashboard API) | OAuth 2.0 bearer tokens; property_id scoped to token |
| Data in transit | TLS 1.3 everywhere; mTLS for internal microservice calls |
| Data at rest | AES-256 on GCS and ClickHouse disks; KMS key management |
| Multi-tenancy isolation | property_id mandatory in every DB query; enforced server-side |
| Bot abuse | WAF + IAB bot list filtering at CDN |
| DDoS | Anycast CDN absorbs volumetric; rate limiting at edge |
| PII | IP discarded post geo-resolution; no email/name stored |

---

## 10. Trade-offs & Alternatives

| Decision | Chosen | Alternative | Rationale |
|---|---|---|---|
| Stream processor | Apache Flink | Spark Streaming, Kafka Streams | Flink has true streaming (vs. micro-batch), best low-latency windowing |
| OLAP store | ClickHouse + Druid | BigQuery, Redshift | ClickHouse: faster ad-hoc; Druid: native real-time. BigQuery lacks sub-second RT |
| Raw storage | GCS Parquet | HDFS | Cloud-native, managed, cost-effective, serverless; HDFS requires ops overhead |
| Consistency model | Eventual (AP) | Strong (CP) | Analytics is inherently approximate; trading strong consistency for availability and throughput |
| Real-time cardinality | HyperLogLog | Exact count | HLL: O(log log N) space vs O(N); 0.2% error acceptable for DAU counts |
| Kafka partition key | property_id | random | Ordering per property guaranteed; hot partition risk mitigated by sub-partitioning |
| Collection ACK | HTTP 204 (fire-and-forget) | Synchronous batch confirm | Minimises client-perceived latency; durability guaranteed via Kafka acks=all server-side |

### Lambda vs. Kappa Architecture
- **Lambda (chosen):** Separate batch and stream layers. More complex ops but optimized for both real-time and accurate historical queries.
- **Kappa:** Single stream layer (Flink recomputes everything). Simpler but unbounded cost for re-processing 5 years of data.
- **Decision:** Lambda because historical accuracy and 5-year retention make batch re-processing from raw storage more practical.

---

## 11. System Evolution & Future Enhancements

| Enhancement | Approach |
|---|---|
| User-journey funnel analysis | Store sessionized events in columnar format; Flink session windowing |
| A/B test reporting | Add experiment_id dimension to aggregation schema |
| Cohort analysis | Daily user-cohort snapshots in ClickHouse; sliding retention queries |
| Anomaly detection | ML model (Isolation Forest) consuming Flink output stream; alert on unusual metric spikes |
| Multi-region deployment | Active-active collection (geo-routed); single-region OLAP with async replication |
| Cost optimization | Tiered storage: hot data in ClickHouse (SSD), cold data in GCS-backed Parquet queried via BigQuery |

---

## 12. Summary — Key Design Decisions

```
                    ┌────────────────────────────────────────────────────┐
                    │            DESIGN DECISION SUMMARY                │
                    ├────────────────────┬───────────────────────────────┤
                    │ Component          │ Choice & Why                  │
                    ├────────────────────┼───────────────────────────────┤
                    │ Ingestion          │ Stateless servers + Kafka     │
                    │                   │ (acks=all) — durability +      │
                    │                   │ horizontal scale               │
                    ├────────────────────┼───────────────────────────────┤
                    │ Real-time          │ Flink + HyperLogLog + Redis   │
                    │                   │ — low-latency approx. counts   │
                    ├────────────────────┼───────────────────────────────┤
                    │ Historical         │ Spark batch + ClickHouse      │
                    │                   │ — accurate, fast, 5yr history  │
                    ├────────────────────┼───────────────────────────────┤
                    │ Consistency        │ Eventual / AP — analytics     │
                    │                   │ tolerates minutes of lag       │
                    ├────────────────────┼───────────────────────────────┤
                    │ Multi-tenancy      │ property_id isolation in all  │
                    │                   │ queries; API token scoping     │
                    ├────────────────────┼───────────────────────────────┤
                    │ Privacy            │ IP discarded post-geo; CIDs   │
                    │                   │ are anonymous UUIDs            │
                    └────────────────────┴───────────────────────────────┘
```
