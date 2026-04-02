# HLD — Distributed Job Scheduler

> **Interview Category:** Distributed Systems / Infrastructure Platform  
> **Difficulty:** SDE 3 / Staff Engineer  
> **Analogous Real Systems:** Apache Airflow, AWS Step Functions, GitHub Actions, Quartz Scheduler, LinkedIn Azkaban, Airbnb Chronos, Uber Cadence

---

## 1. Requirements Clarification

### Functional Requirements
- **Submit Jobs:** Clients can submit jobs (one-time or recurring via cron expressions)
- **Schedule Jobs:** Jobs execute at their designated time or on a trigger event
- **Job Types:**
  - One-time jobs (run once at a specific time)
  - Recurring jobs (cron-like: every 5 min, daily at 3 AM, etc.)
  - Dependency-based jobs (Job B runs only after Job A succeeds — DAG workflows)
- **Job Execution:** Workers pull and execute jobs; support multiple job handler types
- **Status Tracking:** Query job status (PENDING → RUNNING → SUCCEEDED / FAILED / RETRYING)
- **Cancellation:** Cancel a scheduled or running job
- **Retry Policy:** Configurable retries with backoff on failure
- **Notifications:** Webhook / email / Slack alerts on job completion or failure

### Non-Functional Requirements
- **Scale:** ~10M jobs/day (~116 RPS average), spikes up to ~5,000 RPS
- **Latency:** Jobs start execution within **< 5 seconds** of their scheduled time
- **Availability:** 99.99% (< 52 min/year downtime) — missed jobs cause revenue loss
- **Durability:** Zero job loss — every submitted job must execute exactly once (or fail with a recorded reason)
- **At-Least-Once vs Exactly-Once:** Default is at-least-once; workers must be idempotent
- **Horizontal Scalability:** Handle 10× job growth without redesign
- **Multi-tenancy:** Different teams/clients share the same infrastructure with isolation

### Out of Scope
- Real-time streaming jobs (covered by Flink/Spark Streaming)
- Long-running stateful workflows beyond simple DAGs (use Cadence/Temporal for that)

---

## 2. Capacity Estimation

### Traffic
| Metric | Calculation | Value |
|---|---|---|
| Job submissions/day | 10M jobs/day | ~116 write RPS |
| Job executions/day | ~10M | ~116 exec RPS |
| Status queries/day | ~50M (polls + webhooks) | ~580 read RPS |
| **Peak burst** | 10× burst for cron midnight jobs | ~1,200 RPS |

### Storage
| Data | Estimate | Notes |
|---|---|---|
| Job metadata | 10M jobs × 1 KB = **10 GB/day** | Config, cron expr, retry policy |
| Job execution logs | 10M execs × 5 KB = **50 GB/day** | Stdout, stderr per execution |
| Status/history | 365 days × 10 GB = **3.65 TB/year** | Retained for audit/replay |
| **Total** | ~20 TB/year | After compression (~50% ratio) |

### Workers
- Avg job duration: 30 seconds
- At peak: ~1,200 concurrent jobs
- Workers with 10 concurrent threads: **~120 worker instances**
- Scale to 500 workers under burst (horizontal auto-scaling)

---

## 3. API Design

### Job Management APIs

```http
# Submit a new job
POST /v1/jobs
{
  "name": "send-daily-digest",
  "type": "RECURRING",                    # ONE_TIME | RECURRING | DEPENDENT
  "cron_expression": "0 8 * * *",          # 8 AM daily (null for ONE_TIME)
  "scheduled_at": null,                    # ISO-8601 (for ONE_TIME)
  "handler": "email-digest-handler",       # Registered worker handler name
  "payload": { "user_segment": "active" },
  "retry_policy": { "max_attempts": 3, "backoff_seconds": 60 },
  "timeout_seconds": 300,
  "dependencies": [],                      # List of job_ids this job depends on
  "notify_on": ["FAILURE"],
  "webhook_url": "https://my-service/callback"
}
→ 201 Created { "job_id": "job_abc123", "status": "SCHEDULED" }

# Get job details
GET /v1/jobs/{job_id}
→ 200 OK { "job_id", "name", "status", "next_run_at", "last_run_at", "run_count" }

# List jobs (paginated)
GET /v1/jobs?status=RUNNING&page=1&limit=50

# Cancel a job
DELETE /v1/jobs/{job_id}
→ 200 OK { "job_id", "status": "CANCELLED" }

# Get execution history of a job
GET /v1/jobs/{job_id}/executions?limit=20

# Get a specific execution's logs/result
GET /v1/executions/{execution_id}
→ 200 OK { "execution_id", "job_id", "status", "started_at", "finished_at", "exit_code", "logs_url" }

# Trigger a job immediately (on-demand)
POST /v1/jobs/{job_id}/trigger
→ 202 Accepted { "execution_id": "exec_xyz789" }
```

### Worker Registration APIs (Internal)
```http
# Worker polls for a job to execute (pull-based)
POST /internal/v1/worker/poll
{ "worker_id": "w-001", "capabilities": ["email-handler", "http-handler"] }
→ 200 OK { "execution_id", "job_id", "handler", "payload", "timeout_seconds" }
# or 204 No Content if no job available

# Worker reports completion
POST /internal/v1/executions/{execution_id}/complete
{ "worker_id": "w-001", "status": "SUCCEEDED", "exit_code": 0, "output": "..." }

# Worker sends heartbeat (keep alive)
PUT /internal/v1/executions/{execution_id}/heartbeat
{ "worker_id": "w-001" }
```

---

## 4. High-Level Architecture

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                      │
│   Web UI / CLI / SDK / CI-CD Pipelines / Microservices                        │
└───────────────────────────┬───────────────────────────────────────────────────┘
                            │ HTTPS
┌───────────────────────────▼───────────────────────────────────────────────────┐
│                        API GATEWAY (Kong / AWS API GW)                         │
│        Auth (JWT/OAuth) · Rate Limiting · Routing · Request Logging            │
└───────┬────────────────────────────────────────────────┬──────────────────────┘
        │                                                │
        ▼                                                ▼
┌───────────────────┐                        ┌──────────────────────┐
│  JOB SERVICE      │                        │  EXECUTION SERVICE   │
│  (Stateless API)  │                        │  (Stateless API)     │
│                   │                        │                      │
│ • Submit/Cancel   │                        │ • Worker poll        │
│ • CRUD jobs       │                        │ • Heartbeat          │
│ • Query status    │                        │ • Mark complete/fail │
└───────┬───────────┘                        └──────────┬───────────┘
        │ Write job metadata                            │ Dispatch
        ▼                                               ▼
┌───────────────────────┐              ┌─────────────────────────────┐
│   JOB METADATA DB     │              │     JOB DISPATCH QUEUE      │
│   (PostgreSQL)        │◄─────────────│     (Redis + Kafka)         │
│                       │              │                             │
│ jobs, executions,     │              │ • Ready jobs queued here    │
│ retry_counts, deps    │              │   at trigger time           │
└───────────────────────┘              │ • Workers pull from queue   │
                                       └─────────────┬───────────────┘
                                                     │
                        ┌────────────────────────────┼────────────────────────────┐
                        ▼                            ▼                            ▼
               ┌────────────────┐          ┌────────────────┐           ┌────────────────┐
               │   WORKER #1    │          │   WORKER #2    │           │   WORKER #N    │
               │ (email-handler)│          │ (http-handler) │           │ (custom-fn)    │
               └────────────────┘          └────────────────┘           └────────────────┘
                        │                            │                            │
                        └────────────────────────────┴────────────────────────────┘
                                                     │ Writes execution outcome
                                                     ▼
                                        ┌────────────────────────┐
                                        │   SCHEDULER ENGINE     │
                                        │   (Leader-Elected)     │
                                        │                        │
                                        │ • Polls DB for due jobs│
                                        │ • Resolves DAG deps    │
                                        │ • Enqueues to Kafka    │
                                        │ • Manages retries      │
                                        └────────────────────────┘
                                                     │
                        ┌────────────────────────────┴────────────────────────────┐
                        ▼                                                         ▼
           ┌────────────────────────┐                               ┌─────────────────────────┐
           │   REDIS (Hot State)    │                               │  NOTIFICATION SERVICE   │
           │                       │                               │                         │
           │ • Job due-time index  │                               │ • Webhooks              │
           │ • Distributed lock    │                               │ • Email/Slack alerts    │
           │ • Worker heartbeats   │                               └─────────────────────────┘
           │ • Idempotency keys    │
           └────────────────────────┘
```

### Component Responsibilities

| Component | Technology Choice | Why |
|---|---|---|
| API Gateway | Kong / AWS API GW | Auth, rate limiting, observability in one layer |
| Job Service | Go / Java Spring Boot | Stateless; horizontal scaling |
| Execution Service | Go / Java Spring Boot | Separate concern from job management |
| Scheduler Engine | Go (leader-elected) | Critical path; leader election via etcd/ZooKeeper |
| Job Metadata DB | PostgreSQL | ACID guarantees; job state transitions need strong consistency |
| Dispatch Queue | Kafka | Durable, replayable; high throughput; worker fan-out |
| Hot State / Locks | Redis | Sorted sets for time-based scheduling; low-latency locks |
| Execution Logs | S3 + Elasticsearch | S3 for durability, ES for search |
| Notifications | Internal SNS / SES / Slack API | Fan-out notifications post execution |

---

## 5. Deep Dive — Core Subsystems

### 5.1 Scheduler Engine — The Heart of the System

This is the most critical component. It is responsible for scanning for due jobs and enqueueing them precisely on time.

#### Design: Polling-Based Scheduler (Not Cron Daemon)

```
Every 1 second:
  1. Query Redis Sorted Set: ZRANGEBYSCORE due_jobs -inf <current_epoch_ms>
  2. For each due job:
       a. Acquire distributed lock (SET job:<id>:lock NX EX 30)  → prevents duplicate dispatch
       b. Transition job status: SCHEDULED → ENQUEUED in DB
       c. Publish to Kafka topic: job-dispatch (partition by job_type or tenant_id)
       d. Update next_run_at in Redis Sorted Set (for recurring jobs)
  3. Release lock after enqueue confirmation
```

**Why Redis Sorted Set for due-time index?**
- `ZRANGEBYSCORE` is O(log N + M) — efficient for finding all due jobs
- Score = epoch_ms of next_run_at
- Can handle millions of scheduled jobs efficiently
- On submission: `ZADD due_jobs <epoch_ms> <job_id>`
- On trigger: `ZREM due_jobs <job_id>` + re-add with new score (recurring)

#### Leader Election

Multiple Scheduler Engine instances run, but **only one is active at a time** to avoid double-dispatch.

```
- Use ZooKeeper / etcd ephemeral nodes for leader election
- Active leader runs the polling loop
- Standbys monitor the leader node; auto-elect on failure
- Failover time: < 10 seconds (ZooKeeper session timeout)
```

#### Clock Drift Handling
- Sync all nodes via NTP (verified < 100ms drift)
- Use UTC timestamps everywhere; store epoch milliseconds
- Schedule 30 seconds ahead if clock skew is detected

---

### 5.2 Exactly-Once Execution — The Hardest Problem

**The Challenge:** In a distributed system, network failures can cause a job to be dispatched twice. Workers must handle duplicates.

#### Strategy: Idempotency Key + Two-Phase State Machine

```
Job State Machine:
SCHEDULED → ENQUEUED → CLAIMED → RUNNING → SUCCEEDED
                                         ↘ FAILED → RETRYING → RUNNING ...
                                         ↘ TIMED_OUT
                                         ↘ CANCELLED
```

**Step-by-step for safe execution:**
```
1. Scheduler enqueues job to Kafka with idempotency_key = job_id + scheduled_at
2. Worker consumes from Kafka:
   a. Calls Execution Service: POST /executions with idempotency_key
   b. DB: INSERT execution (ON CONFLICT idempotency_key DO NOTHING)
   c. If conflict → job already claimed → worker drops this message (dedup)
   d. If success → updates job status to RUNNING → starts execution
3. Worker sends heartbeat every 10s
4. On completion → marks execution SUCCEEDED / FAILED
5. Scheduler Engine watches for RUNNING jobs with heartbeat > 30s → declares them TIMED_OUT → requeues
```

**Idempotency Key design:**
```
idempotency_key = SHA256(job_id + scheduled_at_epoch + attempt_number)
```

---

### 5.3 DAG-Based Job Dependencies

Many real workflows require job chaining: "Run job B only after jobs A and C complete."

#### Dependency Schema
```sql
CREATE TABLE job_dependencies (
  job_id       UUID NOT NULL,
  depends_on   UUID NOT NULL,
  PRIMARY KEY (job_id, depends_on),
  FOREIGN KEY (job_id) REFERENCES jobs(id),
  FOREIGN KEY (depends_on) REFERENCES jobs(id)
);
```

#### Dependency Resolution Flow
```
1. When Job A completes (SUCCEEDED):
   → Scheduler queries: SELECT job_id FROM job_dependencies WHERE depends_on = 'A'
   → For each dependent job (e.g., B, C):
       • Check if ALL its dependencies are SUCCEEDED
       • If yes → mark B as SCHEDULED → ENQUEUE to Kafka
       • If not → keep waiting

2. For DAG cycle detection:
   → On job submission, run DFS on the dependency graph
   → Reject if a cycle is detected (return 400 Bad Request)
```

**Data Structure for in-memory DAG (Scheduler Engine):**
```python
graph = {
    "job_B": {"depends_on": {"job_A", "job_C"}},
    "job_C": {"depends_on": {"job_A"}},
}
# Topological sort via Kahn's algorithm at submission time
```

---

### 5.4 Worker Design — Pull vs Push

**Decision: Pull-based (workers poll for jobs)**

| | Push (Broker pushes to workers) | Pull (Workers poll queue) |
|---|---|---|
| Backpressure | None — fast workers get overwhelmed | Natural — worker only takes what it can handle |
| Worker awareness | Broker must track worker capacity | Workers self-regulate |
| Implementation | Complex worker registry | Simple; Kafka consumer group |
| Preferred for | Latency-critical, small payloads | **Job scheduling (our choice)** |

#### Worker Lifecycle
```
1. Worker starts → registers with worker registry (Redis SET w:<id> capabilities EX 60)
2. Polls Kafka consumer group for available jobs (long poll, 5s timeout)
3. Claims job → sends heartbeat every 10s
4. Executes handler (via plugin/subprocess/HTTP call)
5. Reports SUCCESS or FAILURE with output/logs
6. Logs uploaded to S3; execution record updated in DB
```

#### Worker Autoscaling
- Monitor Kafka consumer lag per topic partition
- Scale out workers if lag > threshold (e.g., > 1000 messages behind)
- Scale in when lag consistently < 100 for 5 minutes
- Use Kubernetes HPA with custom metrics adapter (KEDA)

---

### 5.5 Database Schema Design

```sql
-- Core job definition
CREATE TABLE jobs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL,
  name              VARCHAR(255) NOT NULL,
  type              ENUM('ONE_TIME', 'RECURRING', 'DEPENDENT') NOT NULL,
  handler           VARCHAR(255) NOT NULL,
  payload           JSONB,
  cron_expression   VARCHAR(100),           -- null for ONE_TIME
  scheduled_at      TIMESTAMPTZ,            -- null for RECURRING
  next_run_at       TIMESTAMPTZ,
  status            ENUM('ACTIVE', 'PAUSED', 'CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  retry_policy      JSONB,                  -- { max_attempts: 3, backoff: 60 }
  timeout_seconds   INT NOT NULL DEFAULT 300,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Each invocation of a job
CREATE TABLE job_executions (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id            UUID NOT NULL REFERENCES jobs(id),
  idempotency_key   VARCHAR(255) UNIQUE NOT NULL,
  status            ENUM('ENQUEUED','CLAIMED','RUNNING','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED'),
  attempt_number    INT NOT NULL DEFAULT 1,
  worker_id         VARCHAR(100),
  started_at        TIMESTAMPTZ,
  finished_at       TIMESTAMPTZ,
  last_heartbeat_at TIMESTAMPTZ,
  exit_code         INT,
  logs_s3_url       TEXT,
  error_message     TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- DAG dependencies
CREATE TABLE job_dependencies (
  job_id            UUID NOT NULL REFERENCES jobs(id),
  depends_on_job_id UUID NOT NULL REFERENCES jobs(id),
  PRIMARY KEY (job_id, depends_on_job_id)
);

-- Indexes
CREATE INDEX idx_jobs_next_run_at ON jobs(next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_executions_job_id ON job_executions(job_id);
CREATE INDEX idx_executions_status ON job_executions(status, last_heartbeat_at);
CREATE INDEX idx_jobs_tenant ON jobs(tenant_id, status);
```

---

## 6. Scale & Resilience

### 6.1 Handling Millions of Scheduled Jobs

**Challenge:** A single scheduler polling DB every second at scale is a bottleneck.

**Solution: Sharded Schedulers**
```
- Partition jobs by hash(job_id) mod N_shards
- Each scheduler instance is responsible for a subset of jobs
- Coordinator (via etcd) assigns shard ranges to scheduler instances
- If a scheduler dies → its shards are reassigned to healthy instances
- Similar to Kafka partition assignment via consumer groups
```

### 6.2 Handling Cron Midnight Burst ("The Thundering Herd")

**Problem:** Thousands of `0 0 * * *` jobs all trigger at midnight simultaneously.

**Solutions:**
1. **Jitter Injection:** Add `random(0, 60)` seconds offset to cron jobs at submission time
2. **Priority Queue:** High-priority jobs get dedicated Kafka partitions
3. **Worker Auto-scaling:** KEDA scales workers ahead of predicted burst (using calendar-aware ML model)
4. **Backpressure:** Kafka naturally queues excess jobs; workers drain at their own pace

### 6.3 Fault Tolerance

| Failure Mode | Detection | Recovery |
|---|---|---|
| Worker crashes mid-execution | Heartbeat missing > 30s | Scheduler marks TIMED_OUT, requeues (respecting max retries) |
| Scheduler Engine crashes | Leader election (ZooKeeper/etcd) | Standby becomes leader < 10s |
| Kafka broker failure | Replication factor = 3 | Consumers switch to replica partition leaders automatically |
| DB primary failure | PostgreSQL HA + Patroni | Replica promotes to primary; failover < 30s |
| Duplicate execution | Idempotency key in DB | Worker deduplicates at claim stage |
| Clock skew | NTP + skew detection | Scheduler adds buffer; alerts if drift > 1s |

### 6.4 High Availability Architecture

```
Region A (Primary)                      Region B (DR)
├── API Layer (3 instances)             ├── API Layer (standby)
├── Job Service (3 instances)           ├── Job Service (standby)
├── Scheduler Engine (1 leader + 2 sb)  ├── Execution Service (standby)
├── Kafka Cluster (3 brokers)       ←── ├── Kafka MirrorMaker (replicate)
├── PostgreSQL (1 primary + 2 replicas) ├── PostgreSQL (read replica, promote on failover)
├── Redis (1 primary + 1 replica)       ├── Redis (replica)
└── Worker Fleet (100 instances)        └── Worker Fleet (50 warm standby)

Global: Route53 / Cloud DNS for failover routing
RTO: < 5 minutes | RPO: < 30 seconds
```

---

## 7. Trade-offs & Alternatives

### 7.1 DB Choice: PostgreSQL vs Cassandra

| | PostgreSQL | Cassandra |
|---|---|---|
| Consistency | ✅ Strong ACID — critical for job state transitions | ❌ Eventual — risky for state machines |
| Query flexibility | ✅ Complex queries (DAG lookups, range scans) | ❌ Limited secondary indexes |
| Scale | ❌ Needs sharding at very large scale | ✅ Naturally distributed |
| **Verdict** | **Chosen** for job metadata | Could use for logs/audit trail |

### 7.2 Scheduling: Pull (Redis ZSET) vs Push (Kafka Delayed Messages)

| | Redis ZSET Polling | Kafka Delayed Messages |
|---|---|---|
| Precision | ✅ ~1-second precision | ❌ Limited native delay support (needs workaround) |
| Complexity | Medium | Higher (custom delay layer) |
| Scale | ✅ Millions of entries, O(log N) | ✅ High throughput |
| **Verdict** | **Chosen** for scheduling index | Kafka used for dispatch only |

### 7.3 Execution: Pull vs Push Workers

**Chose Pull** — workers control their own load. Prevents overloading a worker with jobs it can't handle.

### 7.4 Leader Election: ZooKeeper vs etcd vs DB-based

| | ZooKeeper | etcd | DB advisory locks |
|---|---|---|---|
| Reliability | ✅ Battle-tested | ✅ Cloud-native | ⚠️ DB dependency |
| Latency | ~1-5ms | ~1-5ms | ~5-20ms |
| Operational cost | ❌ Complex ops | ✅ Simpler | ✅ No extra infra |
| **Verdict** | **etcd** preferred for cloud deployments; DB advisory locks for simplicity |

### 7.5 Why Not Use Existing Tools?

| Tool | Limitation |
|---|---|
| Quartz Scheduler | Single-node; not cloud-native |
| Apache Airflow | Workflow-focused; poor for millions of lightweight jobs |
| Kubernetes CronJob | No retry intelligence; no dependency management; poor observability |
| AWS EventBridge | Vendor lock-in; limited payload size; no DAG |
| **Our system** | Full control, multi-tenant, exactly-once, DAG-capable |

---

## 8. Observability

### Metrics (Prometheus + Grafana)
| Metric | Alert Threshold |
|---|---|
| Job execution delay (scheduled vs actual start) | Alert if p99 > 10 seconds |
| Jobs in RUNNING state > timeout | Alert if any > 2× timeout |
| Kafka consumer lag | Alert if > 10,000 messages |
| Failed job rate | Alert if > 1% failure rate (sliding 5-min window) |
| Worker heartbeat misses | Alert if any worker > 3 missed heartbeats |

### Logs (ELK / Loki)
- Structured JSON logs with: `job_id`, `execution_id`, `tenant_id`, `worker_id`, `duration_ms`, `status`
- Execution stdout/stderr stored in S3; metadata in Elasticsearch for search
- Log retention: 30 days hot (ES), 1 year cold (S3 + Glacier)

### Traces (Jaeger / OpenTelemetry)
- Trace from Job Submission → Scheduler Enqueue → Worker Claim → Execution → Completion
- Distributed trace spans across services with `job_id` as trace tag

### Dashboards
1. **SLO Dashboard:** Job start latency, success rate, failure breakdown by handler
2. **Capacity Dashboard:** Worker utilization, Kafka lag, DB connection pool usage
3. **Tenant Dashboard:** Per-tenant job counts, success/failure rates, quota usage

---

## 9. Security

| Concern | Approach |
|---|---|
| Authentication | OAuth 2.0 / JWT per tenant; service accounts for internal worker APIs |
| Authorization | RBAC — job owners can cancel their own jobs; admins can manage all |
| Multi-tenancy isolation | `tenant_id` in every table; row-level security in PostgreSQL |
| Payload encryption | Job payloads encrypted at rest using AES-256 (KMS-managed keys) |
| Worker auth | Worker API tokens rotated every 24h; stored in HashiCorp Vault |
| Audit trail | Immutable execution history; every state transition logged with actor |
| Rate limiting | Per-tenant job submission limits (e.g., 10K jobs/day free tier) |

---

## 10. Extended Features (SDE 3 Discussion Topics)

### 10.1 Job Prioritization
```
- Jobs have a priority field: LOW | NORMAL | HIGH | CRITICAL
- Separate Kafka topics (or partitions) per priority tier
- Workers subscribed to HIGH topic process before LOW topic
- Critical jobs bypass queues — direct execution path
```

### 10.2 Resource-Aware Scheduling
```
- Jobs declare resource needs: { cpu: "2", memory: "4Gi" }
- Scheduler checks worker pool capacity before dispatch
- Workers register available capacity with Redis (SET w:<id>:capacity ...)
- Bin-packing algorithm to place jobs on workers efficiently
```

### 10.3 Rate Limiting per Tenant
```
- Each tenant has a quota: max concurrent jobs, submissions/minute
- Enforced at API Gateway + Job Service
- Soft limit: slow down (429 with Retry-After)
- Hard limit: reject (507 Insufficient Storage)
```

### 10.4 Backfill / Catch-up Mode
```
Problem: Scheduler was down for 2 hours; 120 hourly jobs missed.
Solutions:
  Option A (Backfill): Re-run all missed executions in sequence (Airflow default)
  Option B (Skip): Only run the latest scheduled slot, skip older ones
  Option C (Configurable): Let job owners choose per job — `missed_policy: BACKFILL | SKIP | LATEST`
```

---

## 11. Summary — Design Decisions at a Glance

| Decision | Choice | Rationale |
|---|---|---|
| Job state persistence | PostgreSQL | ACID; complex queries; reliable state machine |
| Scheduling index | Redis Sorted Set | O(log N) time-based lookup; in-memory speed |
| Dispatch mechanism | Kafka | Durable, replayable, high-throughput fan-out |
| Worker model | Pull-based | Natural backpressure; worker self-regulation |
| Exactly-once safety | Idempotency key + atomic DB claim | Prevents duplicate execution |
| Leader election | etcd | Fast, cloud-native leader detection |
| DAG dependencies | DB graph + Kahn's topological sort | Cycle detection at submit time |
| Midnight burst | Jitter + auto-scaling (KEDA) | Smooths thundering herd |
| Logs storage | S3 + Elasticsearch | Durable + searchable |
| Multi-tenancy | Row-level security + tenant_id partitioning | Isolation without separate infra |

---

*Document follows the SDE 3 HLD Interview Framework — see `.agents/skills/hld-skills/SKILL.md`*
