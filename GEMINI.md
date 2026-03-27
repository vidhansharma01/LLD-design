# Project Context — High Level Design (HLD) for SDE 3 Interviews

This project contains HLD study materials and design documents prepared for SDE 3-level system design interviews.

---

## About This Project

- **Purpose:** Reference designs, patterns, and frameworks for cracking High Level Design rounds at FAANG/top-tier companies at the SDE 3 level.
- **Skill file:** `.agents/skills/hld-skills/SKILL.md` — full HLD skill guide for the AI assistant.
- **Design docs:** Located in `src/` as structured markdown/Java files for specific system designs.

---

## Coding Standards & Conventions

- Design documents are written in Markdown.
- Java files in `src/` are used for Low Level Design (LLD) implementations.
- HLD documents should follow the interview framework defined in the SKILL.md.

---

# High Level Design — SDE 3 Interview Reference

## 1. Interview Framework (7 Steps)

| Step | Activity | Time |
|---|---|---|
| 1 | **Clarify Requirements** (functional + non-functional) | 3–5 min |
| 2 | **Capacity Estimation** (RPS, storage, bandwidth) | 2–3 min |
| 3 | **Define API** (REST/gRPC endpoints) | 2–3 min |
| 4 | **High-Level Architecture** (draw and explain core components) | 10–12 min |
| 5 | **Deep Dive** into 2–3 critical subsystems | 10–15 min |
| 6 | **Scale & Resilience** (bottlenecks, failure modes, HA) | 5–7 min |
| 7 | **Trade-offs & Alternatives** (justify your choices) | 3–5 min |

---

## 2. Key Design Principles

### CAP Theorem
A distributed system can only guarantee **two** of:
- **C**onsistency — every read sees the latest write
- **A**vailability — every request gets a response
- **P**artition Tolerance — continues despite network splits

| System | Trade-off | Example |
|---|---|---|
| Financial ledger | CP | HBase, Zookeeper |
| Social feed | AP | Cassandra, DynamoDB |
| Search engine | AP (eventual) | Elasticsearch |

### BASE vs ACID
| ACID | BASE |
|---|---|
| Strong consistency | Eventual consistency |
| Relational DBs | NoSQL / distributed DBs |
| Rollback on failure | Accept temporary inconsistency |

---

## 3. Core Building Blocks

### Caching
- **Strategies:** Write-through, Write-behind, Cache-aside, Read-through
- **Eviction:** LRU, LFU, TTL-based
- **Cache Stampede:** Use probabilistic early expiration or distributed locks
- **Tools:** Redis (rich data structures), Memcached (simple, fast)

### Databases

#### SQL vs NoSQL
| Use SQL | Use NoSQL |
|---|---|
| Relational data, complex joins | Flexible schema, key-based lookups |
| Strong ACID guarantees | Eventual consistency OK |
| Vertical + read replicas | Horizontal sharding |

#### Sharding Strategies
- **Hash-based:** Uniform distribution; no range queries
- **Range-based:** Range queries easy; hotspot risk
- **Geo-based:** Shard by region; geo-local access patterns

#### Replication
- **Single-leader:** Writes to primary, reads from replicas
- **Multi-leader:** Complex conflict resolution; multi-region writes
- **Leaderless (quorum):** N/2+1 write confirmation (Cassandra, DynamoDB)

### Message Queues
| Tool | Model | Best For |
|---|---|---|
| Kafka | Log-based, partitioned topics | High throughput, event replay |
| RabbitMQ | Queue with routing | Task queues, RPC |
| SQS | Managed queue | Simple decoupling, serverless |

**Kafka Key Concepts:** Topics → Partitions → Consumer Groups → Offsets. Idempotent consumers handle duplicate delivery.

### Consistent Hashing
- Minimises key remapping when nodes are added/removed
- Virtual nodes (vnodes) improve load balance
- Used by: Cassandra, DynamoDB, Redis Cluster

### Bloom Filters
- Probabilistic membership test; no false negatives, possible false positives
- Use case: Check before hitting DB, URL deduplication in crawlers

---

## 4. Scalability Patterns

### Read-Heavy
- Read replicas, CDN, materialized views, denormalization

### Write-Heavy
- Queue (Kafka) to buffer spikes, write-behind caching, sharding, LSM-tree stores (Cassandra, RocksDB)

### Hotspot / Celebrity Problem
- Random suffix on cache keys → store N copies
- CDN push for expected viral content
- Rate-limit at edge per individual key

---

## 5. Reliability & Fault Tolerance

| Pattern | Purpose |
|---|---|
| Circuit Breaker | Stop calling a failing dependency |
| Retry + Exponential Backoff | Handle transient failures safely |
| Bulkhead | Isolate resources per service/pool |
| Timeout | Fail fast on slow dependencies |
| Idempotency | Safe to retry writes (idempotency keys) |
| Saga Pattern | Manage distributed transactions |

**Data Durability:** WAL (Write-Ahead Log), replication factor ≥ 3, PITR backups, checksums.

---

## 6. Common System Archetypes

### URL Shortener
- Base62 encode auto-increment ID → short code
- Use 302 redirect (not 301) to preserve analytics
- Cache short→long mapping in Redis (high read:write ratio)

### Messaging System (WhatsApp / Slack)
- WebSocket for real-time bi-directional communication
- Kafka partitioned by `chat_id` for ordered message delivery
- Offline delivery: store in DB, push via APNs/FCM on reconnect

### News Feed (Twitter / Instagram)
| | Push (Fanout on Write) | Pull (Fanout on Read) |
|---|---|---|
| Read | Fast — pre-computed | Slow — merge N timelines |
| Best for | Normal users | Celebrity accounts |
- **Hybrid:** Push for normal users, Pull for celebrities
- Redis sorted set per `user_id` (score = timestamp) for feed storage

### Ride-Sharing (Uber)
1. **Location Service:** Driver GPS every 5s → Redis Geo
2. **Matching Service:** Find nearest N drivers using Geohash / S2
3. **Trip Service:** State machine (requested → accepted → in-progress → completed)
4. **Surge Pricing:** Supply/demand ratio per geo-cell

### Video Streaming (YouTube / Netflix)
- Transcoding pipeline: raw video → FFmpeg workers → multiple resolutions → S3
- **Adaptive Bitrate (ABR):** HLS/DASH — client switches quality by bandwidth
- **CDN:** Edge caches popular segments; origin pull for cold content
- Videos chunked into 2–10s segments for efficient seeking

### Rate Limiter
| Algorithm | Pros/Cons |
|---|---|
| Token Bucket | Allows burst; smooth |
| Leaky Bucket | Predictable output; no burst |
| Sliding Window Counter | Balanced accuracy + memory |
- Distributed: Redis + Lua scripts for atomic counter + TTL

### Distributed ID Generator (Snowflake)
```
[1b: sign][41b: timestamp ms][10b: machine ID][12b: sequence]
```
- ~4096 IDs/ms per machine; time-sortable

### Distributed File Storage (S3-like)
- Flat namespace: bucket + key
- Multi-part upload for large files (parallel parts)
- Metadata server (NameNode) + chunk servers (DataNodes), replication factor = 3

---

## 7. Data Consistency Patterns

### Saga Pattern
- **Choreography:** Services react to events independently — decoupled but hard to trace
- **Orchestration:** Central orchestrator directs each step — easier to reason about

### Event Sourcing
- Immutable event log; current state rebuilt by replaying events
- Enables audit trail, time travel, event-driven projections

### CQRS
- Separate read model (optimised for queries) from write model (commands)
- Independent scaling of read and write paths

---

## 8. Observability

| Pillar | Tool | Covers |
|---|---|---|
| Metrics | Prometheus + Grafana | Latency (p50/p95/p99), RPS, error rate |
| Logs | ELK / Loki | Structured JSON logs, searchable |
| Traces | Jaeger / OpenTelemetry | Distributed request tracing |

**Key Metrics:** Latency, error rate, throughput, CPU/memory, Kafka consumer lag, cache hit ratio.

---

## 9. Security Checklist

| Area | Approach |
|---|---|
| Authentication | OAuth 2.0 / JWT |
| Authorization | RBAC / ABAC, least privilege |
| Data in transit | TLS everywhere; mTLS internally |
| Data at rest | AES-256; KMS key management |
| Rate limiting | Protect all public APIs |
| DDoS | WAF + CDN absorption |
| PII / GDPR | Data masking, retention policies, right to erasure |

---

## 10. Trade-Off Cheat Sheet

| Decision | Option A | Option B | Choose A when... |
|---|---|---|---|
| DB type | SQL (Postgres) | NoSQL (Cassandra) | Relational data, complex queries |
| Messaging | Kafka | RabbitMQ | High throughput, replay needed |
| Caching | Write-through | Write-behind | Consistency > performance |
| API | REST | gRPC | Internal services needing performance |
| Fan-out | Push | Pull | Low follower count |
| File storage | S3 | HDFS | Cloud-native, infrequent access |
| Search | Elasticsearch | DB full-text | Large corpus, faceted filtering |

---

## 11. Common Mistakes to Avoid

1. Jumping into design without clarifying requirements
2. Over-engineering from the start — start simple, scale when needed
3. Ignoring failure scenarios and single points of failure
4. Not quantifying estimates — always use numbers
5. Ignoring data consistency models
6. Not justifying trade-offs
7. Going shallow on every component — go deep on 2–3
8. Neglecting observability and security

---

## 12. SDE 3 Evaluation Rubric

| Dimension | What Interviewers Assess |
|---|---|
| Problem Scoping | Asks right questions; defines clear boundaries |
| Architecture | Clean, modular; correct component choices |
| Scalability | Quantified estimates; identifies bottlenecks |
| Deep Dive | Technical depth in key subsystems |
| Trade-offs | Proactively discusses alternatives; justifies choices |
| Reliability | Identifies failure modes; proposes mitigations |
| Communication | Structured, clear; leads the conversation |
| Adaptability | Responds well to follow-up challenges |
