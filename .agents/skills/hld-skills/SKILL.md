---
name: High Level Design (HLD) for SDE 3 Interviews
description: A comprehensive skill guide for tackling High Level Design interviews at the SDE 3 level. Covers frameworks, common system patterns, trade-off analysis, scalability strategies, and example walkthroughs for complex distributed systems.
---

# High Level Design (HLD) for SDE 3 Interviews

## Overview

At the SDE 3 level, HLD interviews go beyond drawing boxes and arrows. You are expected to:
- Lead the design conversation with **ownership and clarity**
- Make **justified trade-offs** with awareness of cost, latency, consistency, and complexity
- Reason about **scale** (millions of users, petabytes of data)
- Proactively raise **failure modes**, **bottlenecks**, and **resilience strategies**
- Demonstrate **depth** in at least 2–3 subsystems

---

## 1. Interview Framework (Structured Approach)

Follow this structure for every HLD problem. Spend time proportionally based on complexity.

### Step 1 — Clarify Requirements (3–5 min)
Always start by asking clarifying questions. Do not assume.

**Functional Requirements:**
- What are the core use cases? (e.g., upload, read, search)
- Who are the clients? (mobile, web, other services)
- Any specific features to exclude?

**Non-Functional Requirements:**
- Scale: Daily Active Users (DAU), Requests Per Second (RPS)
- Latency: p99 read/write latency targets
- Availability: 99.9% vs. 99.99%?
- Consistency: Strong vs. eventual?
- Data retention and storage requirements
- Geographic distribution needed?

### Step 2 — Capacity Estimation (2–3 min)
Back-of-envelope math demonstrates senior thinking.

| Metric | Typical Estimation Approach |
|---|---|
| RPS | DAU × avg requests/day ÷ 86,400 |
| Storage | records/day × avg record size × retention days |
| Bandwidth | RPS × avg payload size |
| Cache size | Hot data × hit ratio assumption |

**Example:** 100M DAU, each user sends 10 messages/day
- Write RPS: 100M × 10 / 86,400 ≈ **~11,600 RPS**
- If each message = 1 KB: **~11.6 MB/s write throughput**

### Step 3 — Define the API (2–3 min)
Define clean REST or gRPC endpoints covering all functional requirements.

```
POST /v1/messages          { sender_id, receiver_id, content, type }
GET  /v1/messages/{id}
GET  /v1/conversations/{id}/messages?cursor=&limit=
```

### Step 4 — High-Level Architecture (10–12 min)
Draw and explain the core components:

```
Clients → CDN → API Gateway → Load Balancer
                                ↓
                         Service Layer (stateless)
                                ↓
              ┌─────────────────┬──────────────┐
           Cache           Database         Message Queue
          (Redis)      (Primary/Replica)     (Kafka)
                                ↓
                       Object Storage (S3)
```

Walk through each component's **responsibility**, **choice of technology**, and **why**.

### Step 5 — Deep Dive into Key Components (10–15 min)
Pick 2–3 critical sub-systems and go deep. This is where SDE 3 differentiation happens.

Common deep-dive areas:
- Database schema and indexing strategy
- Sharding / partitioning approach
- Data replication and consistency model
- Cache invalidation strategy
- Message queue design and consumer groups
- Rate limiting and auth

### Step 6 — Handle Scale & Resilience (5–7 min)
- Horizontal scaling strategy
- Hotspot / celebrity problem
- Circuit breakers, retries, timeouts
- Data center failover
- Graceful degradation

### Step 7 — Trade-offs & Alternatives (3–5 min)
Proactively discuss what you chose NOT to do and why.

---

## 2. Key Design Principles

### CAP Theorem
A distributed system can only guarantee **two** of:
- **C**onsistency — every read sees the latest write
- **A**vailability — every request gets a response
- **P**artition Tolerance — system continues despite network splits

| System Type | Choice | Example |
|---|---|---|
| Financial ledger | CP | HBase, Zookeeper |
| Social feed | AP | Cassandra, DynamoDB |
| Search index | AP (eventual) | Elasticsearch |

### PACELC Extension
Even without partition, there is a trade-off between **Latency** and **Consistency**.

### BASE vs ACID
| ACID | BASE |
|---|---|
| Strong consistency | Eventual consistency |
| Relational DBs | NoSQL / distributed DBs |
| Rollback on failure | Accept temporary inconsistency |

---

## 3. Core Building Blocks

### 3.1 Load Balancers
- **Layer 4 (Transport):** Routes by IP + port. Fast, no content inspection. e.g., AWS NLB
- **Layer 7 (Application):** Routes by URL, headers, cookies. Smarter. e.g., AWS ALB, Nginx
- Algorithms: Round Robin, Least Connections, IP Hash, Weighted
- **Health checks** are critical — remove unhealthy instances automatically

### 3.2 Caching
| Layer | Tool | Use Case |
|---|---|---|
| CDN | Cloudfront, Fastly | Static assets, API responses |
| App-level | Redis, Memcached | Session data, hot DB records |
| DB query cache | Built-in (MySQL) | Repeated identical queries |

**Eviction Policies:** LRU, LFU, TTL-based

**Cache Invalidation Strategies:**
- **Write-through:** Write to cache and DB simultaneously. Consistent but slower writes.
- **Write-behind (async):** Write to cache, async flush to DB. Fast writes, risk of data loss.
- **Cache-aside (lazy loading):** Read from cache; on miss, load from DB and populate cache.
- **Read-through:** Cache handles DB reads behind the scenes.

**Cache Stampede Problem:** When cache expires, many requests hit DB simultaneously.
- Solution: Probabilistic early expiration, distributed locks, or staggered TTLs.

### 3.3 Databases

#### SQL vs NoSQL Decision Matrix
| Criteria | Use SQL | Use NoSQL |
|---|---|---|
| Data structure | Structured, relational | Flexible, hierarchical |
| Consistency need | Strong ACID | Eventual OK |
| Query complexity | Complex joins | Simple key-based lookups |
| Scale pattern | Vertical + read replicas | Horizontal sharding |

#### Sharding Strategies
- **Range-based:** Easy range queries but hotspot risk
- **Hash-based:** Uniform distribution but no range queries
- **Directory-based:** Flexible mapping via lookup table; overhead of maintaining it
- **Geo-based:** Shard by region; good for geo-local needs

#### Replication
- **Single-leader:** All writes go to primary, reads from replicas. Simple; leader is bottleneck.
- **Multi-leader:** Writes accepted at multiple nodes. Complex conflict resolution.
- **Leaderless (quorum):** Writes confirmed by N/2+1 nodes. Used in DynamoDB, Cassandra.

### 3.4 Message Queues & Event Streaming
| Technology | Model | Best For |
|---|---|---|
| Kafka | Log-based, topic partitions | High-throughput, replay, event sourcing |
| RabbitMQ | Queue with routing | Task queues, RPC patterns |
| SQS (AWS) | Managed queue | Simple decoupling, serverless |
| Pub/Sub (GCP) | Fan-out messaging | Notification systems |

**Kafka Key Concepts:**
- **Topic** → logical stream; split into **partitions**
- **Consumer Groups** → parallel consumption; each partition consumed by one consumer per group
- **Offset** → tracked per partition; enables replay and at-least-once delivery
- **Idempotent consumers** handle duplicate messages safely

### 3.5 API Gateway
Responsibilities:
- Auth (JWT validation, OAuth)
- Rate limiting
- Request routing
- SSL termination
- Request/response transformation
- Logging & Tracing

### 3.6 Service Discovery
- **Client-side:** Service queries registry (Eureka), picks instance to call
- **Server-side:** Load balancer queries registry (Consul, AWS ELB)
- **DNS-based:** Services registered as DNS records

### 3.7 Consistent Hashing
Used in distributed caches and databases to minimize key remapping when nodes are added/removed.
- Virtual nodes (vnodes) improve load distribution
- Used by: Amazon DynamoDB, Apache Cassandra, Memcached

### 3.8 Bloom Filters
Probabilistic data structure — fast membership test with no false negatives (may have false positives).
- Use case: Check if a user ID exists before hitting DB, checking URL crawl history
- Space-efficient; trade-off is false positive rate

---

## 4. Scalability Patterns

### Horizontal vs. Vertical Scaling
| | Horizontal | Vertical |
|---|---|---|
| Method | Add more machines | Upgrade existing machine |
| Limit | Network + coordination overhead | Hardware ceiling |
| Cost | Commodity hardware | Expensive |
| Preferred for | Stateless services | Stateful legacy systems |

### Read-Heavy Systems
- Add **read replicas** for DB
- Serve from **CDN** or **edge cache**
- **Materialized views** for expensive queries
- **Denormalize** data for fast reads

### Write-Heavy Systems
- **Kafka / queue** to buffer write spikes
- **Write-behind caching**
- **Sharding** to distribute write load
- **LSM-tree** based stores (Cassandra, RocksDB) — optimized for writes

### Dealing with Hotspots (Celebrity Problem)
Problem: One entity (user, key) gets disproportionate traffic.
- Add a **random suffix** to cache keys for celebrities, store N copies
- Use **CDN push** for expected viral content
- Rate-limit or throttle at edge for individual keys

---

## 5. Reliability & Fault Tolerance

### Failure Modes to Discuss
- Single Point of Failure (SPOF) — always identify and eliminate or mitigate
- Network partitions — use replicas and async consistency
- Cascading failures — break with circuit breakers
- Data corruption — checksums, WAL (write-ahead log)

### Resilience Patterns
| Pattern | Purpose | Example |
|---|---|---|
| Circuit Breaker | Stop calling a failing dependency | Netflix Hystrix |
| Retry with Backoff | Retry transient failures | Exponential backoff + jitter |
| Bulkhead | Isolate resources per service | Separate thread pools |
| Timeout | Fail fast on slow dependencies | gRPC deadlines |
| Idempotency | Safe to retry writes | Idempotency keys on POST |
| Saga Pattern | Distributed transaction management | Choreography or orchestration |

### Data Durability
- **WAL (Write-Ahead Log):** Changes written to log before applying; enables crash recovery
- **Replication factor ≥ 3:** Survive node failures
- **Backups + point-in-time recovery (PITR)**
- **CRC / checksums** on data blocks

---

## 6. Common System Archetypes

### 6.1 URL Shortener (e.g., Bit.ly)
**Key Design Decisions:**
- ID generation: Base62 encoding of auto-increment ID or hash (MD5 first 7 chars)
- 301 (permanent) vs 302 (temporary) redirect — use 302 to keep analytics trackable
- Cache short-to-long mapping in Redis; very read-heavy
- DB: Simple KV store (DynamoDB) or SQL with index on short_code

**Scale:** 100M URLs, ~10B redirects/day → 116K RPS reads, cache hit rate ~99%

---

### 6.2 Messaging System (e.g., WhatsApp, Slack)
**Key Design Decisions:**
- **WebSocket** connections for real-time bi-directional messaging
- **Message fanout:** For group chats, fan out to all members' inboxes or use a push model
- **Message ordering:** Sequence numbers per conversation; use Kafka partitioned by chat_id
- **Offline delivery:** Store messages in DB; push via APNs/FCM on reconnect
- **Read receipts:** Dedicated event stream; client ACKs trigger delivery/read event

**Storage:** Separate hot (recent) and cold (archived) message storage

---

### 6.3 News Feed / Timeline (e.g., Twitter, Instagram)
**Two Approaches:**
| | Push (Fanout on Write) | Pull (Fanout on Read) |
|---|---|---|
| Write | Pre-compute feed at write time | Store only in author's timeline |
| Read | Fast — feed already computed | Slower — merge N timelines |
| Best for | Typical users (<1K followers) | Celebrity accounts (millions of followers) |

**Hybrid:** Use push for normal users, pull for celebrities. LinkedIn / Facebook use this.

**Infrastructure:** Redis sorted set (score = timestamp) per user_id for feed storage.

---

### 6.4 Ride-Sharing Service (e.g., Uber, Lyft)
**Key Subsystems:**
1. **Location Service:** Drivers push GPS every 5s → Redis Geo (geospatial index)
2. **Matching Service:** Find nearest N drivers → run matching algorithm
3. **Trip Service:** Manages trip state machine (requested → accepted → in-progress → completed)
4. **Pricing Service:** Surge pricing via supply/demand ratio per geo-cell
5. **Notification Service:** Push to driver app via persistent WebSocket or APNs/FCM

**Geo-indexing:** Use **Geohash** or **S2 geometry** to partition map into cells for fast spatial lookups.

---

### 6.5 Video Streaming Service (e.g., YouTube, Netflix)
**Key Design Decisions:**
- **Ingestion pipeline:** Raw video → transcoding service (FFmpeg workers) → multiple resolutions/formats → S3
- **CDN delivery:** Edge servers cache popular content; origin pull for cold content
- **Adaptive Bitrate Streaming (ABR):** HLS or DASH — client switches quality based on bandwidth
- **Chunked storage:** Videos split into 2–10s segments; enables seeking without full download
- **Metadata service:** Video title, description, tags stored in relational DB; search via Elasticsearch

**Bottleneck:** Transcoding is CPU intensive → use job queue (SQS) + auto-scaling worker fleet.

---

### 6.6 Distributed Search (e.g., Google, Elasticsearch)
**Key Components:**
1. **Crawler:** Fetches web pages; Frontier (URL queue) + politeness (robots.txt, rate limiting)
2. **Indexer:** Tokenises and builds inverted index (term → list of doc IDs + positions)
3. **Ranking:** TF-IDF, BM25, PageRank signals merged; ML re-ranking layer
4. **Query processor:** Parse query → lookup inverted index → rank → merge results

**Scale:** Inverted index sharded by term hash or document range; replicated for availability.

---

### 6.7 Rate Limiter
**Algorithms:**
| Algorithm | Description | Pros/Cons |
|---|---|---|
| Token Bucket | Refill tokens at fixed rate; consume per request | Smooth, allows burst |
| Leaky Bucket | Queue requests; drain at fixed rate | No burst, predictable output |
| Fixed Window | Count requests per time window | Simple; boundary burst problem |
| Sliding Window Log | Log each request timestamp; count in window | Accurate; memory intensive |
| Sliding Window Counter | Weighted hybrid of adjacent fixed windows | Balanced accuracy + memory |

**Distributed Rate Limiting:** Use Redis with Lua scripts for atomic counter update + TTL.

---

### 6.8 Distributed Cache (e.g., Redis Cluster, Memcached)
**Key Decisions:**
- **Consistent hashing** for key distribution across nodes
- **Replication:** Primary + at least one replica per shard
- **Eviction:** LRU by default; consider LFU for skewed access patterns
- **Persistence:** RDB snapshots + AOF log for durability in Redis
- **Cluster mode:** Redis Cluster uses 16384 hash slots divided across nodes

---

### 6.9 Distributed File Storage (e.g., GFS, HDFS, S3)
**Architecture:**
- **Name Node (metadata server):** Tracks file → chunk mappings, namespace
- **Chunk Servers (data nodes):** Store actual data chunks (e.g., 64MB each)
- **Replication:** Each chunk replicated 3× across different racks

**S3-like Object Store:**
- Flat namespace (bucket + key); no real directories
- Multi-part upload for large files; parallel upload of parts
- Versioning, lifecycle policies, presigned URLs for secure access

---

### 6.10 Distributed ID Generator (e.g., Snowflake IDs)
Requirements: Globally unique, roughly sortable, high throughput, no coordination bottleneck.

**Snowflake format (64-bit):**
```
[1 bit: sign][41 bits: timestamp ms][10 bits: machine/datacenter ID][12 bits: sequence]
```
- Generates ~4096 IDs/ms per machine
- Time-sortable (enables range queries by creation time)

**Alternatives:** UUID v4 (random, not sortable), ULID (sortable UUID), DB auto-increment (not distributed).

---

## 7. Data Consistency Patterns

### Saga Pattern (Distributed Transactions)
- Break transaction into local transactions, each publishing events
- **Choreography:** Each service listens and reacts to events — decoupled but hard to trace
- **Orchestration:** Central saga orchestrator directs each step — easier to reason about

### Event Sourcing
- Store all state changes as an immutable sequence of events
- Current state rebuilt by replaying events
- Enables audit trail, time travel, event-driven projections

### CQRS (Command Query Responsibility Segregation)
- Separate read model (optimised for queries) from write model (optimised for commands)
- Write side emits events; read side subscribes and updates its own projection
- Enables independent scaling of reads and writes

---

## 8. Observability

A system without observability is a black box. Always address this.

### Three Pillars
1. **Metrics:** Counters, gauges, histograms (Prometheus + Grafana)
2. **Logs:** Structured JSON logs aggregated in ELK or Loki
3. **Traces:** Distributed tracing across services (Jaeger, Zipkin, OpenTelemetry)

### Key Metrics to Monitor
- **Latency:** p50, p95, p99
- **Error rate:** 4xx, 5xx per service
- **Throughput:** RPS per endpoint
- **Resource utilisation:** CPU, memory, disk I/O, network
- **Queue depth:** Lag in Kafka consumer groups
- **Cache hit rate:** Redis hit/miss ratio

### Alerting
- Define SLOs (Service Level Objectives) and alert on SLI (indicators) breaches
- Avoid alert fatigue — only alert on actionable, customer-impacting conditions

---

## 9. Security Considerations

Always raise security proactively in interviews.

| Concern | Mitigation |
|---|---|
| Authentication | OAuth 2.0 / JWT; rotate secrets |
| Authorization | RBAC or ABAC; least privilege |
| Data in transit | TLS everywhere (mTLS for internal) |
| Data at rest | AES-256 encryption; KMS for key management |
| SQL Injection | Parameterised queries / ORM |
| Rate limiting | Protect APIs from abuse |
| DDoS | WAF + CDN absorbs volumetric attacks |
| PII / GDPR | Data masking, retention policies, right to erasure |

---

## 10. Trade-Off Discussion Cheat Sheet

Use this to structure your trade-off analysis:

| Decision | Option A | Option B | When to choose A |
|---|---|---|---|
| DB type | SQL (Postgres) | NoSQL (Cassandra) | Relational data, complex queries |
| Messaging | Kafka | RabbitMQ | High throughput, event replay needed |
| Caching | Write-through | Write-behind | Consistency > performance |
| API | REST | gRPC | Internal microservices needing performance |
| Consistency | Strong | Eventual | Financial / inventory data |
| Search | Elasticsearch | DB full-text | Large corpus, faceted search |
| File storage | S3 | HDFS | Cloud-native, infrequent access |
| Fan-out | Push | Pull | Low follower count users |

---

## 11. Common Mistakes to Avoid

1. **Jumping into design without clarifying requirements** — always ask first
2. **Over-engineering from the start** — start simple, scale when needed
3. **Ignoring failure scenarios** — proactively discuss fault tolerance
4. **Single point of failure** — every critical component should have redundancy
5. **Not quantifying estimates** — use numbers; avoid "it's fast enough"
6. **Ignoring data consistency** — always state your consistency model
7. **Not explaining trade-offs** — why did you choose X over Y?
8. **Shallow coverage of everything** — go deep on 2–3 components
9. **Neglecting observability** — monitoring and alerting is part of the design
10. **Forgetting security** — mention auth/encryption even briefly

---

## 12. Evaluation Rubric at SDE 3 Level

| Dimension | What Interviewers Look For |
|---|---|
| Problem Scoping | Asks right questions; defines clear boundaries |
| Architecture | Clean, modular design with correct component choices |
| Scalability | Quantified estimates; identifies bottlenecks; horizontal scaling |
| Deep Dive | Technical depth in key components; not surface-level |
| Trade-offs | Proactively discusses alternatives; justifies choices |
| Reliability | Identifies failure modes; proposes mitigation |
| Communication | Structured, clear; leads the conversation |
| Adaptability | Responds well to follow-up challenges and pivots |
