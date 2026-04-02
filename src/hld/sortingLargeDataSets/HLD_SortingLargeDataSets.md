# HLD — Distributed Sorting System for Large Data Sets

> **SDE 3 Interview Reference | Estimated Design Time: 45 min**

---

## Table of Contents
1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dive — Core Components](#5-deep-dive--core-components)
   - 5.1 [Data Ingestion & Partitioning](#51-data-ingestion--partitioning)
   - 5.2 [Distributed Sort Engine (MapReduce / External Sort)](#52-distributed-sort-engine-mapreduce--external-sort)
   - 5.3 [Merge & Output Service](#53-merge--output-service)
   - 5.4 [Metadata & Job Tracking Service](#54-metadata--job-tracking-service)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [Observability & Security](#8-observability--security)
9. [Key Interview Talking Points](#9-key-interview-talking-points)

---

## 1. Requirements Clarification

### Functional Requirements

| # | Requirement |
|---|---|
| FR1 | Accept large input data sets (files, streams, or database tables) for sorting |
| FR2 | Support sorting by one or more columns / keys |
| FR3 | Support both ascending and descending order |
| FR4 | Produce sorted output as a file, stream, or DB table |
| FR5 | Allow the caller to query job status (submitted → in-progress → completed/failed) |
| FR6 | Support re-running or retrying a failed sort job |
| FR7 | Support different data types: integers, strings, floats, composite keys |

### Non-Functional Requirements

| # | Requirement | Target |
|---|---|---|
| NFR1 | Sort data sets of **up to 100 TB** in a single job | |
| NFR2 | Throughput | Process **1 TB/hr** per average job |
| NFR3 | Latency (small jobs ≤ 10 GB) | Complete in **< 5 minutes** |
| NFR4 | Consistency | Output is **deterministic** — same input → same sorted output |
| NFR5 | Availability | **99.9%** job submission availability |
| NFR6 | Fault Tolerance | Resume from last checkpoint; no full restart on failure |
| NFR7 | Scalability | Linear scaling: 2× workers → ~2× throughput |

### Scope Decisions (State These Explicitly)

- **In scope:** Batch sort jobs (files/object store)
- **Out of scope:** Real-time stream sorting (continuous / unbounded data) — that's a different problem
- **Out of scope:** Custom sort functions (UDFs) — can be added later

---

## 2. Capacity Estimation

### Assumptions

| Parameter | Value |
|---|---|
| Average job size | 1 TB |
| Jobs per day | 500 |
| Peak concurrent jobs | 50 |
| Average record size | 1 KB |
| Sorting key size | ~50 bytes |
| Worker nodes | 1,000 |
| Worker memory per node | 64 GB |
| Worker disk per node | 2 TB NVMe SSD |

### Calculations

```
Total daily data processed:
  500 jobs × 1 TB = 500 TB/day

Records per 1 TB job:
  1 TB / 1 KB per record = ~1 Billion records

Per-node data share (1 TB job, 100 workers):
  1 TB / 100 workers = 10 GB per worker → fits comfortably in 64 GB RAM

Max internal sort per node:
  64 GB RAM → sort ~64M records in memory (1 KB each) before spilling to disk

Temp disk per node (2 passes):
  10 GB input × 2 (for merge temp files) = 20 GB → well within 2 TB NVMe

Key-only index size (for global sample):
  1B records × 50 bytes key = 50 GB → 0.05% of total data; manageable

Network bandwidth for shuffle phase:
  1 TB shuffled across 100 workers = 10 GB/worker sent & received
  At 10 Gbps NIC: 1 TB shuffle ≈ 13 minutes (acceptable)
```

---

## 3. API Design

### 3.1 Submit Sort Job

```
POST /v1/sort-jobs
Content-Type: application/json

{
  "input": {
    "type": "S3",                        // S3 | HDFS | GCS | Kafka
    "uri": "s3://bucket/dataset/",
    "format": "CSV",                     // CSV | JSON | Parquet | ORC
    "schema": {
      "columns": ["id", "name", "score"],
      "delimiter": ","
    }
  },
  "sort_keys": [
    { "column": "score", "order": "DESC" },
    { "column": "name",  "order": "ASC"  }  // secondary key
  ],
  "output": {
    "type": "S3",
    "uri": "s3://bucket/sorted-output/",
    "format": "Parquet"
  },
  "options": {
    "num_partitions": 200,               // optional: override auto-partition
    "deduplication": false,
    "compression": "SNAPPY"
  }
}

Response 202 Accepted:
{
  "job_id": "sort-job-abc123",
  "status": "SUBMITTED",
  "estimated_completion_seconds": 1800
}
```

### 3.2 Get Job Status

```
GET /v1/sort-jobs/{job_id}

Response 200 OK:
{
  "job_id": "sort-job-abc123",
  "status": "IN_PROGRESS",              // SUBMITTED | IN_PROGRESS | COMPLETED | FAILED
  "progress_percent": 62,
  "phases": {
    "partition": "COMPLETED",
    "local_sort": "COMPLETED",
    "shuffle": "IN_PROGRESS",
    "merge": "PENDING"
  },
  "created_at": "2026-04-03T10:00:00Z",
  "updated_at": "2026-04-03T10:24:00Z",
  "output_uri": null                    // populated when COMPLETED
}
```

### 3.3 Cancel / Retry Job

```
DELETE /v1/sort-jobs/{job_id}          // Cancel

POST   /v1/sort-jobs/{job_id}/retry    // Retry from last checkpoint
```

### 3.4 List Jobs

```
GET /v1/sort-jobs?status=IN_PROGRESS&limit=20&cursor=<token>
```

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENT / CALLER                            │
│              (Batch pipeline, ML training, ETL tool)                │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │ HTTPS (REST)
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          API GATEWAY                                │
│              (Auth, Rate Limiting, TLS Termination)                 │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       JOB COORDINATOR SERVICE                       │
│       (validates job, assigns workers, tracks phases, exposes       │
│        status API, writes to Metadata DB)                           │
└──────────┬───────────────┬────────────────────────┬────────────────┘
           │               │                        │
           ▼               ▼                        ▼
┌──────────────┐  ┌────────────────┐   ┌─────────────────────────────┐
│ METADATA DB  │  │  JOB QUEUE     │   │     SAMPLE SERVICE           │
│ (PostgreSQL) │  │  (Kafka)       │   │  (Reads sample, builds       │
│  job status, │  │  tasks per     │   │   global sort range bounds)  │
│  checkpoints │  │  phase         │   └─────────────────────────────┘
└──────────────┘  └───────┬────────┘
                          │
        ┌─────────────────┼──────────────────┐
        ▼                 ▼                  ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  WORKER NODE │  │  WORKER NODE │  │  WORKER NODE │  ... (1000 nodes)
│              │  │              │  │              │
│ 1. Read      │  │ 1. Read      │  │ 1. Read      │
│    partition │  │    partition │  │    partition │
│ 2. Local     │  │ 2. Local     │  │ 2. Local     │
│    Sort      │  │    Sort      │  │    Sort      │
│ 3. Range-    │  │ 3. Range-    │  │ 3. Range-    │
│    partition │  │    partition │  │    partition │
│    & Shuffle │  │    & Shuffle │  │    & Shuffle │
│ 4. Merge-    │  │ 4. Merge-    │  │ 4. Merge-    │
│    sort      │  │    sort      │  │    sort      │
│    received  │  │    received  │  │    received  │
│    partitions│  │    partitions│  │    partitions│
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       │                 │                  │
       └─────────────────┼──────────────────┘
                         ▼
              ┌──────────────────────┐
              │   OBJECT STORE       │
              │   (S3 / HDFS / GCS)  │
              │   Sorted output      │
              │   partitions written │
              └──────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| **API Gateway** | TLS, auth, rate limiting, route to Job Coordinator |
| **Job Coordinator** | Accept jobs, validate, assign tasks to workers via Kafka, track phase state |
| **Sample Service** | Reservoir-sample ~0.1% of input; build histogram; compute `P` range pivots |
| **Worker Node** | Stateless executor: read chunk → local sort → range-partition → shuffle → merge-sort |
| **Metadata DB** | Persist job definitions, phase state, checkpoint offsets |
| **Job Queue (Kafka)** | Fan-out tasks to workers; enables exactly-once task assignment |
| **Object Store (S3)** | Durable staging for input data, intermediate shuffled files, final sorted output |

---

## 5. Deep Dive — Core Components

### 5.1 Data Ingestion & Partitioning

#### Problem
Input data (e.g., 1 TB CSV on S3) must be split into chunks small enough for individual workers to handle in memory without I/O overload.

#### Strategy: Input Splitting

```
Input: 1 TB file (or directory of files) on S3

Step 1 — List all objects:
  Job Coordinator calls S3 ListObjects → gets list of files with sizes

Step 2 — Compute splits:
  Target split size = Worker RAM / 4   →  64 GB / 4 = 16 GB
  Number of splits for 1 TB = 1 TB / 16 GB ≈ 65 splits → assign 65 workers

Step 3 — Assign splits via Kafka:
  For each split, publish a task message:
  {
    "task_type": "MAP",
    "job_id": "sort-job-abc123",
    "split": { "bucket": "my-bucket", "key": "data.csv",
                "byte_start": 0, "byte_end": 17179869184 }
  }
```

#### S3 Byte-Range Reads
Workers use `S3 GET` with `Range` header for their byte slice. This avoids downloading the entire file.

#### Parquet / ORC Files
Use row-group metadata for split boundaries — splits align with row groups, avoiding partial record reads.

---

### 5.2 Distributed Sort Engine (MapReduce / External Sort)

This is the **#1 deep-dive area**. The algorithm is a distributed variant of **External Merge Sort** combined with **TeraSort** sampling.

#### Phase 1 — Sampling (Global Range Estimation)

```
Problem: How do we distribute records to workers such that each worker
         owns a contiguous, non-overlapping sort-key range?

Solution: Reservoir Sampling + Range Partitioning

Step 1 — Sample:
  Each worker samples 0.1% of its split → 65 workers × 16 GB × 0.1% = ~1 GB of samples
  Samples pushed to Sample Service.

Step 2 — Build Histogram:
  Sample Service sorts the ~1 Billion sampled keys.
  Divides into P buckets (P = number of reduce workers, e.g., 200).
  Pivot keys = [p1, p2, ..., p199]  →  define 200 non-overlapping ranges.

Step 3 — Broadcast Pivots:
  Job Coordinator writes pivots to distributed config (Zookeeper / etcd).
  All workers read pivots before shuffle phase.
```

#### Phase 2 — Local Sort (Map Phase)

```
Each worker:
  1. Reads its input split from S3 (byte-range GET)
  2. If data fits in RAM: use in-memory sort (TimSort / Radix Sort)
  3. If data exceeds RAM: External Sort (spill-merge)
     a. Read chunk that fits in RAM (e.g., 10 GB)
     b. Sort in memory → write sorted run to local NVMe
     c. Repeat until input exhausted  →  N sorted runs on disk
     d. K-way merge of N runs →  single sorted sequence
  4. Loaded into memory for range-partitioning

  Algorithm choice:
  - Numeric keys → Radix Sort (O(nk), k = key length, cache-friendly)
  - String keys → TimSort (O(n log n), exploits existing order in data)
```

#### Phase 3 — Shuffle (Range-Partition)

```
Each worker scans its locally sorted data:
  For every record:
    Determine target bucket (binary search on pivot array)
    Append record to corresponding output buffer for that reducer

  Once buffer threshold reached → flush buffer to reducer's inbox on S3:
    s3://temp/job-abc123/reducer-042/from-worker-017-run-003.parquet

  Design Note:
  - Buffers are sorted (they come from sorted local data)
  - Each reducer will receive M pre-sorted files (M = number of map workers)
  - Network is the bottleneck — compress buffers with Snappy before upload
```

#### Phase 4 — Merge Sort (Reduce Phase)

```
Each Reduce worker:
  1. Downloads all files for its assigned key range from S3
     (e.g., reducer-042 gets files from all 65 map workers for range [p41, p42))
  2. Runs P-way merge using a Min-Heap (Priority Queue):
     - Heap size = number of input files (65)
     - Continuously pop minimum → write to output buffer
     - When input file exhausted, remove from heap
  3. Write final merged, sorted output partition to S3:
     s3://output/job-abc123/part-042.parquet

Complexity:
  - Each record is read twice (local sort → shuffle) and written twice
  - Overall: O(N log N) distributed, O(N log P) merge phase
  - P-way merge heap operations: O(N log P) where P = # map workers

Final output:
  200 sorted partition files on S3.
  Metadata records which key range each partition covers.
  Consumer can read partitions in order or parallel-merge for downstream use.
```

#### External Sort Memory Model (Per Worker)

```
┌──────────────────────────────────────────────────────┐
│                  Worker Node (64 GB RAM)              │
│                                                       │
│  Input Buffer: 10 GB  (stream records from S3)        │
│  Sort Buffer:  40 GB  (in-memory sort → sorted run)   │
│  Output Buffer: 4 GB  (per-reducer fanout buffers)    │
│  Heap (merge):  1 GB  (priority queue for K-way merge)│
│  OS / JVM overhead: 9 GB                              │
└──────────────────────────────────────────────────────┘

Spill files on NVMe SSD:
  Up to 10 sorted runs × 10 GB each = 100 GB (well within 2 TB NVMe)
```

---

### 5.3 Merge & Output Service

#### Output Guarantees

| Guarantee | Mechanism |
|---|---|
| **Global sort order** | Pivots ensure non-overlapping key ranges per partition |
| **No data loss** | Each input split is assigned atomically; task success ack written to Metadata DB |
| **No duplicates** | Idempotent task IDs; workers skip already-completed tasks (checked at startup) |
| **Completeness** | Job Coordinator verifies all P output partitions exist before marking job COMPLETED |

#### Output Format

- Each output partition is a **Parquet file** with embedded min/max key statistics
- A **manifest file** is written to S3:
  ```json
  {
    "job_id": "sort-job-abc123",
    "sort_keys": [{"column": "score", "order": "DESC"}],
    "partitions": [
      { "index": 0, "file": "s3://output/part-000.parquet", "min_key": null,  "max_key": 9999 },
      { "index": 1, "file": "s3://output/part-001.parquet", "min_key": 8500,  "max_key": 9998 },
      ...
    ]
  }
  ```
- Downstream consumers can use the manifest to seek to a specific key range without reading all partitions.

---

### 5.4 Metadata & Job Tracking Service

#### Schema (PostgreSQL)

```sql
-- Job definitions
CREATE TABLE sort_jobs (
    job_id          VARCHAR(64) PRIMARY KEY,
    status          VARCHAR(20) NOT NULL,    -- SUBMITTED|IN_PROGRESS|COMPLETED|FAILED
    input_uri       TEXT NOT NULL,
    output_uri      TEXT,
    sort_keys       JSONB NOT NULL,
    num_partitions  INTEGER,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

-- Phase tracking per job
CREATE TABLE job_phases (
    job_id      VARCHAR(64) REFERENCES sort_jobs(job_id),
    phase_name  VARCHAR(30),             -- SAMPLE|MAP|SHUFFLE|REDUCE
    status      VARCHAR(20),             -- PENDING|IN_PROGRESS|COMPLETED|FAILED
    started_at  TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ,
    PRIMARY KEY (job_id, phase_name)
);

-- Per-task checkpoint
CREATE TABLE tasks (
    task_id         VARCHAR(64) PRIMARY KEY,
    job_id          VARCHAR(64) REFERENCES sort_jobs(job_id),
    phase           VARCHAR(30),
    worker_id       VARCHAR(64),
    status          VARCHAR(20),
    attempt_count   INTEGER DEFAULT 0,
    last_heartbeat  TIMESTAMPTZ,
    output_uri      TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
```

#### Task Heartbeat & Failure Detection

```
Worker sends heartbeat every 30 seconds to Job Coordinator:
  PUT /internal/tasks/{task_id}/heartbeat

Job Coordinator runs a background scheduler:
  Every 60 seconds, scan tasks WHERE last_heartbeat < NOW() - 90s
  If found: mark task as FAILED → re-enqueue on Kafka for another worker

This handles:
  - Worker node crash
  - Network partition
  - OOM kills
  - Silent hangs (worker still running but not progressing)
```

---

## 6. Scale & Resilience

### 6.1 Horizontal Scaling

| Component | Scaling Strategy |
|---|---|
| **Worker Nodes** | Auto-scale pool based on queue depth in Kafka; use spot/preemptible instances |
| **Job Coordinator** | Stateless; run 3 replicas behind a Load Balancer; state in DB & Kafka |
| **Metadata DB** | Primary + 2 read replicas; shard by job_id range if needed |
| **Object Store (S3)** | Horizontally infinite; use multi-part upload for large intermediates |
| **Kafka** | Increase partitions; scale consumer groups proportionally |

### 6.2 Fault Tolerance

#### Scenario: Worker Node Dies Mid-Sort

```
1. Worker heartbeat stops → Job Coordinator detects after 90s
2. All incomplete tasks for that worker → re-queued on Kafka
3. New worker picks up task from the beginning of its split
   (input on S3 is idempotent; no side effects from partial work)
4. If shuffle already written: new worker detects via task-ID
   in Metadata DB and skips re-shuffle for completed sub-tasks
5. No full job restart needed
```

#### Scenario: Job Coordinator Crashes

```
1. Other replica takes over (stateless + DB-backed)
2. Reads all IN_PROGRESS jobs from Metadata DB on startup
3. Resumes monitoring heartbeats; re-queues stale tasks
```

#### Scenario: Kafka Broker Failure

```
Kafka replication factor = 3 → tolerates 2 broker failures
Consumer offsets committed per task completion → no duplicate task execution
```

#### Scenario: Skewed Data (Hotspot Keys)

```
Problem: Many records have same sort key value (e.g., many users with score=100)
         → single reducer overloaded

Solution — Secondary Sort Key:
  Always add a secondary tiebreaker key (e.g., row_id or hash) so that
  even identical primary keys spread across reducers for the shuffle phase.

Alternative — Salting:
  For deduplication use-cases: append a hash suffix during shuffle,
  strip during final merge write.
```

### 6.3 Data Skew Detection

```
After sampling phase, compute histogram percentile distribution of keys.
If any bucket contains > 3× the average:
  → Split that bucket: allocate 2 reducers for that range
  → Update pivot list accordingly
  → Rebroadcast updated pivots before shuffle phase begins
```

### 6.4 Network Bottleneck Mitigation

| Technique | Description |
|---|---|
| **Compression** | Snappy compress shuffle files (3–5× reduction) |
| **Combiner (Pre-aggregation)** | Not applicable for sort, but flush buffers in large batches |
| **Rack-aware shuffle** | Prefer same-rack S3 endpoints; minimize cross-AZ traffic |
| **Partition pruning** | If downstream consumer only needs a key range, output only relevant partitions |

---

## 7. Trade-offs & Alternatives

### 7.1 Algorithm Alternatives

| Approach | Pros | Cons | Use When |
|---|---|---|---|
| **Distributed External Merge Sort** (this design) | Deterministic, handles arbitrary data sizes, no size limit | Multi-phase complexity, shuffle is network-heavy | General purpose large-scale sorting |
| **Sample Sort (TeraSort)** | Very cache-efficient, fewer passes, used in benchmarks | Requires good sampling, uneven pivot = skew | High-performance batch jobs with good key distribution |
| **Bitonic / Odd-Even Sort** | Works well on parallel hardware (GPU, FPGA) | O(log² N) comparisons, complex to implement | GPU clusters, specialized hardware |
| **Reservoir Sampling + External Sort** | Simple, low coordination overhead | Not optimal for already-sorted or nearly-sorted data | Smaller scale, simpler infra |

### 7.2 Framework Alternatives

| Tool | Pros | Cons |
|---|---|---|
| **Apache Spark (this design's inspiration)** | Well-tested, rich ecosystem, dynamic resource allocation | JVM overhead, complex tuning, expensive shuffle |
| **Apache Flink** | Low latency, exactly-once semantics | Overkill for pure batch sort |
| **Custom C++ workers** | 5–10× faster than JVM for I/O-bound sort | Large engineering investment |
| **ClickHouse / DuckDB** | In-database columnar sort, SQL interface | Limited to data that fits in a cluster, less flexible |
| **AWS Glue / GCP Dataflow** | Managed, no infra maintenance | Less control, higher cost, vendor lock-in |

### 7.3 Storage Alternatives

| | S3-based (this design) | HDFS-based |
|---|---|---|
| **Durability** | 11 nines (S3) | Depends on replication factor |
| **Throughput** | High (parallel GET) | Very high (local reads) |
| **Shuffle cost** | Network I/O to S3 | Localhost disk I/O (faster) |
| **Simplicity** | High | Requires HDFS cluster management |
| **Best for** | Cloud-native, elastic scaling | On-premise, latency-sensitive |

### 7.4 When to NOT Do Distributed Sorting

```
If data < 1 GB:      → Single-node sort (just use sort command or DataFrame.sort())
If data < 100 GB:    → Single powerful machine (r7g.16xlarge, 512 GB RAM) — no network overhead
If data is columnar: → Use database ORDER BY with proper indexes
If data is a stream: → Use a streaming sort (different problem: bounded window sort)
```

---

## 8. Observability & Security

### 8.1 Metrics (Prometheus + Grafana)

| Metric | Description |
|---|---|
| `sort_job_duration_seconds` | End-to-end job duration histogram |
| `sort_phase_duration_seconds{phase}` | Duration per phase (sample/map/shuffle/reduce) |
| `worker_throughput_bytes_per_second` | Records processed per second per worker |
| `shuffle_bytes_total` | Total data shuffled (key cost metric) |
| `task_retry_count` | Number of task retries (signals instability) |
| `active_jobs` | Number of concurrently running jobs |
| `queue_depth` | Kafka consumer lag per phase queue |

### 8.2 Logs (ELK / Loki)

```json
{
  "timestamp": "2026-04-03T10:15:00Z",
  "level": "INFO",
  "job_id": "sort-job-abc123",
  "task_id": "task-worker-017-map",
  "phase": "MAP",
  "worker_id": "worker-017",
  "records_processed": 42000000,
  "duration_ms": 45000,
  "spill_count": 3
}
```

### 8.3 Tracing (OpenTelemetry / Jaeger)

- Full trace per job_id spanning all phases
- Identify which phase is the bottleneck
- Detect P99 outlier tasks causing job tail latency

### 8.4 Security

| Concern | Approach |
|---|---|
| Auth | OAuth 2.0 / API keys for job submission |
| Authorization | RBAC — only job owner or admin can cancel/retry |
| Data in transit | TLS for all API calls; S3 via HTTPS |
| Data at rest | S3 server-side encryption (SSE-KMS) |
| PII | Data masking option for sensitive columns before sorting |
| Isolation | Each job uses a unique S3 prefix; cross-job access denied via IAM |

---

## 9. Key Interview Talking Points

### ✅ Proactively Raise These

1. **Why sampling is critical** — Without it, all data with score=100 goes to one reducer → catastrophic skew
2. **External sort necessity** — When working set exceeds RAM, spill-merge avoids OOM
3. **Idempotency of tasks** — Worker retries must not produce duplicate data; task output URI is deterministic
4. **Two-pass vs three-pass** — More passes = less RAM needed per worker but more disk I/O; tune based on hardware
5. **Sort stability** — Mention whether stable sort is required (stable = equal keys preserve input order); TimSort is stable, Radix Sort needs care
6. **Compression trade-off** — Snappy is fast but low compression; Zstd is slower but 2× better ratio; choose based on network vs CPU cost
7. **Cost optimization** — Use spot/preemptible instances for workers; short-lived jobs tolerate interruption with checkpoint recovery

### 🔢 Numbers to Remember

| Metric | Value |
|---|---|
| External sort threshold | When data > worker RAM / 2 |
| Shuffle bottleneck | Network bandwidth (10 Gbps = 1.2 GB/s per node) |
| Sampling rate | 0.1% sufficient for 200-partition histogram |
| Typical sort speed (Spark) | ~1 TB / 30 workers / 5 min (Daytona Sort benchmark) |
| TeraSort record (2016) | 100 TB sorted in 98.8 seconds using 2000 nodes |
| Redis sorted set limit | 2³² elements (~4B) — not suitable for TB-scale sort |

### 🎯 Follow-Up Questions & Answers

| Question | Answer |
|---|---|
| "How do you handle already-sorted input?" | Detect during sampling — if keys are nearly sorted, skip map phase and go straight to range-partition with minimal local sort work (TimSort benefits from existing order) |
| "What if a single key has billions of records?" | Data skew. Use secondary sort key or salting during shuffle; allocate extra reducers for that bucket |
| "How do you sort in real time?" | Different problem — use bounded window sort with Flink/Spark Streaming; sliding window Top-K is a related pattern |
| "How do you do a stable sort at scale?" | Use a composite key: (sort_key, input_offset). This makes all keys unique while preserving original relative order for ties |
| "Can you sort data that's larger than total cluster storage?" | Yes, with a streaming merge approach — stream data through the cluster in multiple passes, writing partial results to object store after each pass |
| "How would you sort 1 PB?" | Scale up workers (10,000 nodes), increase partitions (2000), use more sampling for pivot accuracy; architecture is same, just larger |

---

## Summary — Architecture Decision Log (ADL)

| Decision | Choice | Rationale |
|---|---|---|
| Sort algorithm | Distributed External Merge Sort + TeraSort sampling | Industry-proven, handles arbitrary size, linear scaling |
| Shuffle storage | Object Store (S3) | Decouples shuffle from compute; fault-tolerant, elastic |
| Metadata store | PostgreSQL | ACID, relational, sufficient for job/task state |
| Task queue | Kafka | Durable, replayable, enables at-least-once task delivery |
| Compression | Snappy | Low CPU overhead; acceptable ratio for time-sensitive jobs |
| Output format | Parquet + manifest | Columnar for fast downstream reads; manifest for range-seeking |
| Consistency | Strong (job-level) | Sort output correctness is non-negotiable |
| Availability | AP during job execution | Prefer progress over stopping; retries handle transient failures |
