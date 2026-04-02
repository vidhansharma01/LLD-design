# HLD — Distributed Stream Processing System (Kafka-like)

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min  
> **Category:** Event Streaming / Message Broker / Distributed Systems

---

## Table of Contents

1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [Storage Layer — Log-Structured Segments](#51-storage-layer--log-structured-segments)
   - 5.2 [Replication & Leader Election](#52-replication--leader-election)
   - 5.3 [Consumer Groups & Offset Management](#53-consumer-groups--offset-management)
   - 5.4 [Producer & Message Delivery Guarantees](#54-producer--message-delivery-guarantees)
   - 5.5 [Cluster Metadata & Coordination (ZooKeeper → KRaft)](#55-cluster-metadata--coordination-zookeeper--kraft)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [Observability & Security](#8-observability--security)
9. [SDE 3 Signals Checklist](#9-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Functional Requirements (Core)

| # | Requirement |
|---|---|
| FR-1 | Producers can **publish messages** to named topics |
| FR-2 | Topics are split into **partitions** for parallelism |
| FR-3 | Consumers can **subscribe** and read messages at any offset |
| FR-4 | Messages are **durably stored** for a configurable retention period |
| FR-5 | Multiple **consumer groups** can independently consume the same topic |
| FR-6 | Support **at-least-once**, **at-most-once**, and **exactly-once** delivery semantics |
| FR-7 | Producers can choose **sync / async** publishing modes |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Throughput** | Millions of messages/sec across the cluster |
| **Latency** | p99 end-to-end < 10 ms for normal loads |
| **Durability** | No data loss once acknowledged (replication factor ≥ 3) |
| **Availability** | 99.99% (4 nines); survive broker failures without losing writes |
| **Scalability** | Horizontal scale by adding brokers / partitions |
| **Ordering** | Guaranteed ordering **within a partition** |
| **Retention** | Time-based (e.g., 7 days) or size-based (e.g., 1 TB per partition) |

### Out of Scope
- Complex stream processing (joins, windowing) — that's Kafka Streams / Flink territory  
- Schema registry  
- Multi-tenancy billing  

---

## 2. Capacity Estimation

### Assumptions

| Parameter | Value |
|---|---|
| Number of topics | 10,000 |
| Average partitions per topic | 10 |
| Message size (avg) | 1 KB |
| Target write throughput | 10 million messages/sec |
| Replication factor | 3 |
| Retention period | 7 days |

### Calculations

```
Write throughput (raw):
  10M msg/sec × 1 KB = 10 GB/s raw writes

With replication factor 3:
  10 GB/s × 3 = 30 GB/s total disk writes across cluster

Storage required (7 days):
  10 GB/s × 86,400 sec/day × 7 days = ~6 PB raw
  With 3× replication = ~18 PB total (across all broker disks)
  Assume 50 brokers → each broker holds ~360 TB

Network bandwidth per broker:
  Incoming: 600 MB/s (producer writes)
  Outgoing: 600 MB/s × 3 replicas = 1.8 GB/s (replication) + consumer reads ~1.2 GB/s
  Total egress: ~3 GB/s per broker → use 25 Gbps NICs

Partition count:
  10,000 topics × 10 partitions = 100,000 partitions
  50 brokers → 2,000 partitions per broker (manageable)
```

### Broker Fleet Sizing

| Component | Count | Spec |
|---|---|---|
| Brokers | 50 | 128 GB RAM, 24× 4TB NVMe, 25 Gbps NIC |
| Controller (KRaft) | 3 dedicated | High-availability quorum |
| Monitoring | 3 | Prometheus + Grafana |

---

## 3. API Design

### Producer API

```http
# Publish a single message
POST /v1/topics/{topic_name}/messages
{
  "partition_key": "user_123",      // optional; null = round-robin
  "headers": { "source": "web" },
  "payload": "<base64-encoded bytes>",
  "idempotency_key": "req-xyz-001"  // for exactly-once
}
Response 200:
{
  "partition": 4,
  "offset": 1023456,
  "timestamp": "2026-04-01T10:00:00Z"
}

# Batch publish (preferred for throughput)
POST /v1/topics/{topic_name}/messages/batch
{
  "messages": [ ... ]   // up to 1MB or 1000 records per batch
}
```

### Consumer API

```http
# Fetch messages from a partition (pull model)
GET /v1/topics/{topic_name}/partitions/{partition_id}/messages
    ?offset=1023456&limit=500&timeout_ms=1000

Response 200:
{
  "messages": [
    { "offset": 1023456, "timestamp": "...", "headers": {}, "payload": "..." },
    ...
  ],
  "next_offset": 1023956,
  "high_watermark": 1024000   // latest committed offset in partition
}

# Commit consumer offset
POST /v1/consumer-groups/{group_id}/offsets
{
  "topic": "orders",
  "partition": 4,
  "offset": 1023956
}
```

### Admin API

```http
# Create topic
POST /v1/admin/topics
{
  "name": "order-events",
  "partition_count": 20,
  "replication_factor": 3,
  "retention_ms": 604800000,   // 7 days
  "retention_bytes": 1099511627776  // 1 TB per partition
}

# List topics
GET /v1/admin/topics

# Describe topic (partition leaders, in-sync replicas)
GET /v1/admin/topics/{topic_name}

# Delete topic
DELETE /v1/admin/topics/{topic_name}
```

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         PRODUCERS                                       │
│  [Web Service]  [Mobile Backend]  [Analytics Collector]  [IoT Device]  │
└────────────────────────┬────────────────────────────────────────────────┘
                         │  TCP / custom binary protocol (like Kafka wire)
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    PRODUCER CLIENT LIBRARY                              │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────────┐   │
│  │  Partitioner │  │  RecordAccum │  │  NetworkClient (async I/O) │   │
│  │(key → part.) │  │  (batching)  │  │  (Sender thread)           │   │
│  └──────────────┘  └──────────────┘  └────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────────────────┘
                         │
          ┌──────────────▼──────────────┐
          │      LOAD BALANCER / DNS    │  (client-side metadata cache)
          └──────────┬──────────────────┘
                     │
    ┌────────────────▼────────────────────────────────────────┐
    │                   BROKER CLUSTER                        │
    │                                                         │
    │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
    │  │  Broker 1   │  │  Broker 2   │  │  Broker 3   │    │
    │  │ (Leader for │  │ (Leader for │  │ (Leader for │    │
    │  │  partitions │  │  partitions │  │  partitions │    │
    │  │  T1-P0,P1)  │  │  T1-P2,P3) │  │  T1-P4,P5) │    │
    │  │             │  │             │  │             │    │
    │  │  [Log Seg]  │  │  [Log Seg]  │  │  [Log Seg]  │    │
    │  │  [Index]    │  │  [Index]    │  │  [Index]    │    │
    │  │  [ISR Mgr]  │  │  [ISR Mgr]  │  │  [ISR Mgr]  │    │
    │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘    │
    │         │  Replication   │  Replication   │            │
    │         └────────────────┤                │            │
    │                          └────────────────┘            │
    │                                                         │
    │  ┌──────────────────────────────────────────────────┐  │
    │  │        KRaft Controller Quorum (3 nodes)         │  │
    │  │  (Cluster metadata, Leader election, Topic CRUD) │  │
    │  └──────────────────────────────────────────────────┘  │
    └─────────────────────────────┬───────────────────────────┘
                                  │  Pull model
    ┌─────────────────────────────▼───────────────────────────┐
    │                   CONSUMER LAYER                        │
    │                                                         │
    │  Consumer Group A (orders-processor)                    │
    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
    │  │  Consumer 1  │  │  Consumer 2  │  │  Consumer 3  │  │
    │  │  Partition 0 │  │  Partition 1 │  │  Partition 2 │  │
    │  └──────────────┘  └──────────────┘  └──────────────┘  │
    │                                                         │
    │  Consumer Group B (analytics-pipeline) — reads same topic│
    │  ┌──────────────┐  ┌──────────────┐                    │
    │  │  Consumer 4  │  │  Consumer 5  │                    │
    │  └──────────────┘  └──────────────┘                    │
    └─────────────────────────────────────────────────────────┘
                                  │
    ┌─────────────────────────────▼───────────────────────────┐
    │              OFFSET STORE (internal topic)              │
    │         __consumer_offsets (replicated, 50 partitions)  │
    └─────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| **Producer Client** | Batch messages, apply partitioning, retry on failure, handle acks |
| **Broker** | Store log segments, serve reads/writes, manage replicas |
| **Partitioner** | Map message key → partition index (murmur2 hash by default) |
| **KRaft Controller** | Cluster metadata, leader election, broker liveness |
| **Consumer Group Coordinator** | Partition assignment, session heartbeats, rebalancing |
| **Offset Store** | Tracks committed consumer offsets per group/topic/partition |

---

## 5. Deep Dives

### 5.1 Storage Layer — Log-Structured Segments

#### Core Abstraction: The Commit Log

Each partition is an **append-only, ordered, immutable log** of records. This is the most fundamental design decision in the system.

```
Partition: orders-topic / partition-0

Segment files on disk:
├── 00000000000000000000.log      (oldest segment — closed)
├── 00000000000000000000.index    (sparse offset index)
├── 00000000000000000000.timeindex (timestamp index)
├── 00000000000002345678.log
├── 00000000000002345678.index
├── 00000000000002345678.timeindex
└── 00000000000005678901.log      (active segment — open for writes)
    00000000000005678901.index    (active index)
```

#### Log Segment Structure

```
.log file (binary, sequential):
┌──────────────────────────────────────────────────────────┐
│ RecordBatch 1                                            │
│  ├── baseOffset (int64)       → offset of first record   │
│  ├── batchLength (int32)      → total batch size in bytes │
│  ├── magic (int8)             → record format version    │
│  ├── attributes               → compression, timestamps  │
│  ├── lastOffsetDelta (int32)  → records in this batch    │
│  ├── firstTimestamp / maxTimestamp                       │
│  ├── CRC (int32)              → data integrity           │
│  └── Records[]                                           │
│       ├── attributes, timestampDelta, offsetDelta        │
│       ├── key (nullable bytes)                           │
│       └── value (bytes)                                  │
├──────────────────────────────────────────────────────────┤
│ RecordBatch 2 ...                                        │
└──────────────────────────────────────────────────────────┘

.index file (sparse index — every N bytes):
┌───────────────────────────────┐
│ relativeOffset | filePosition │
│ 0              | 0            │
│ 1000           | 1048576      │  (every ~1MB or 1000 records)
│ 2000           | 2097152      │
└───────────────────────────────┘
```

#### Why Append-Only Log?

| Benefit | Explanation |
|---|---|
| **Sequential I/O** | Sequential disk writes are 100–1000× faster than random I/O |
| **OS Page Cache Leverage** | No JVM heap needed; kernel caches hot data transparently |
| **Zero-Copy** | `sendfile()` syscall moves data from page cache → NIC without user-space copy |
| **Simplicity** | No in-place updates, no fragmentation, no compaction complexity during reads |
| **Replay** | Immutability allows any consumer to re-read from any offset |

#### Log Retention & Compaction

**Time/Size-based Retention:**
```
Broker checks segments older than retention.ms or larger than retention.bytes
→ Deletes entire closed segments (atomic, safe)
→ Active segment is never deleted
```

**Log Compaction (for changelog topics):**
```
Only keep the LATEST record per key within a partition.
Used for: DB changelog (CDC), snapshot topics, config stores.

Before compaction:            After compaction:
key=A → val=1                key=A → val=3   (latest)
key=B → val=2                key=B → val=5   (latest)
key=A → val=3                key=C → val=4   (latest)
key=C → val=4
key=B → val=5
```

#### Read Path Optimization

```
Consumer requests offset 1500 for partition-0:

Step 1: Binary search on segment files (by base offset in filename)
        → Find segment starting at offset 0 (covers 0..2345677)

Step 2: Binary search in .index file
        → relativeOffset 1500 → filePosition 1,572,864

Step 3: Seek to byte 1,572,864 in .log file (O(1) disk seek)

Step 4: Sequential read from that position

Total: O(log N) on segments + O(log K) on index → effectively O(1) amortized
```

---

### 5.2 Replication & Leader Election

#### ISR (In-Sync Replica) Model

```
Topic: orders / Partition 0
Replication Factor: 3

Leader: Broker-1
ISR: [Broker-1, Broker-2, Broker-3]

Write Flow:
Producer → Broker-1 (leader)
              │
              ├──► Broker-2 (follower) fetches and replicates
              │         ACK to leader once log appended
              │
              └──► Broker-3 (follower) fetches and replicates
                        ACK to leader once log appended

Leader responds to producer only after min.insync.replicas ACKs received.
```

#### acks Configuration & Durability Trade-offs

| acks value | Behavior | Durability | Latency |
|---|---|---|---|
| `acks=0` | Fire and forget | None (data loss possible) | Lowest |
| `acks=1` | Leader ACK only | Survives leader crash if replica in sync | Low |
| `acks=-1` (all) | All ISR must ACK | Strongest — survives broker failure | Highest |

**Recommendation for production:** `acks=-1` + `min.insync.replicas=2` (out of RF=3)

#### Leader Election via KRaft

```
Old model (ZooKeeper):
  ZooKeeper ephemeral znodes → race condition on failure → slow (30s+)

New model (KRaft — Raft consensus):
  KRaft Controller Quorum (3 dedicated nodes):
    ├── Active controller: holds latest metadata log
    ├── Standby controllers: replicate metadata log
    └── On failure: Raft leader election in milliseconds

Leader election for a partition:
  1. Controller detects broker failure (via heartbeat timeout)
  2. Controller picks new leader from ISR (prefers oldest replica = most caught up)
  3. Broadcasts LeaderAndIsr request to all affected brokers
  4. New leader begins serving reads/writes
  5. Followers start fetching from new leader

Time to elect new leader: < 1 second (vs. 30+ seconds with ZooKeeper)
```

#### Unclean Leader Election

```
Scenario: All ISR replicas crashed; only out-of-sync replica available.

unclean.leader.election.enable = false (default, recommended):
  → Partition stays OFFLINE until ISR replica recovers
  → No data loss, but unavailability

unclean.leader.election.enable = true:
  → Out-of-sync replica becomes leader immediately
  → System stays available but messages may be lost
  → Only for use cases where availability > durability (e.g., metrics)
```

#### High Watermark (HW) & Log End Offset (LEO)

```
Broker 1 (Leader):    LEO = 100
Broker 2 (Follower):  LEO = 98    (slightly behind)
Broker 3 (Follower):  LEO = 99

High Watermark = min(all ISR LEOs) = 98

Consumers can only read up to offset 98 (the HW).
This prevents consumers from reading uncommitted data that could be rolled back if the leader fails.
```

---

### 5.3 Consumer Groups & Offset Management

#### Partition Assignment & Rebalancing

```
Consumer Group: order-processors
Topic: orders (20 partitions)

3 consumers → each gets ~6-7 partitions (Range assignor or CooperativeSticky)

Consumer 1: partitions [0, 1, 2, 3, 4, 5, 6]
Consumer 2: partitions [7, 8, 9, 10, 11, 12, 13]
Consumer 3: partitions [14, 15, 16, 17, 18, 19]

--- Consumer 4 joins ---
Rebalance triggered by Group Coordinator (broker)

EAGER rebalance (stop-the-world):
  All consumers revoke all partitions → re-assign → resume
  → Simple but causes full pause during rebalance

COOPERATIVE STICKY rebalance (incremental):
  Only moved partitions are revoked → minimal disruption
  → Preferred for production (Kafka >= 2.4)

Rule: #partitions ≥ #consumers for full parallelism
      #consumers > #partitions → idle consumers
```

#### Offset Commit Strategies

```
Auto-commit (enable.auto.commit=true, default 5s):
  + Simple
  - At-least-once: may re-process records after crash
  - Risk: offsets committed before processing completes

Manual commit (enable.auto.commit=false):
  commitSync()  → blocks until broker ACKs → lower throughput
  commitAsync() → non-blocking → risk of out-of-order commits

Exactly-once: Use Kafka Transactions
  1. Producer: transactional.id set → gets unique PID from broker
  2. Producer sends messages + offset commit in one atomic transaction
  3. Consumers with isolation.level=read_committed skip uncommitted records
```

#### Dead Letter Queue (DLQ) Pattern

```
Consumer processing fails repeatedly:
  → Publish message to ${topic_name}.DLQ topic
  → Continue consuming main topic (don't block partition progress)
  → Separate DLQ consumer handles retry / alerting / manual inspection

DLQ topic structure:
  Original topic: orders
  DLQ topic:      orders.dlq
  Retry topics:   orders.retry.1, orders.retry.2, orders.retry.3
```

#### __consumer_offsets Internal Topic

```
50 partitions by default (hashed by group_id)
Replication factor = 3

Stored as compacted log (only latest offset per group/topic/partition):
  Key:   [group_id, topic, partition]
  Value: [offset, metadata, timestamp]

Group Coordinator = broker that is leader for the __consumer_offsets
partition corresponding to the consumer group's hash.
```

---

### 5.4 Producer & Message Delivery Guarantees

#### Producer Batching & Compression

```
Producer config:
  batch.size = 16KB       → accumulate messages before sending
  linger.ms = 5           → wait up to 5ms for more records to batch
  compression.type = lz4  → compress entire batch (typical 4-6× reduction)
  buffer.memory = 32MB    → total in-memory buffer for unsent batches

Flow:
  record1 → RecordAccumulator (deque per partition)
  record2 → RecordAccumulator (same partition → same batch)
  ...
  after 5ms or 16KB → Sender thread picks batch → sends to broker leader
```

#### Idempotent Producer (Exactly-Once within one session)

```
Problem: Producer retries can cause duplicates
  Producer sends batch → broker receives, appends, sends ACK
  ACK lost in network → producer retries → broker appends again → DUPLICATE

Solution: enable.idempotence = true
  Each producer assigned a unique PID (Producer ID) by broker
  Each message batch has a monotonically increasing sequence number
  Broker deduplicates: if seq already seen for (PID, partition) → skip
  No change in throughput or latency

Deduplication window: last 5 in-flight batches per partition (configurable)
```

#### Transactional Producer (Exactly-Once Semantics — E2E)

```
transactional.id = "payment-service-txn-1"

API:
  producer.initTransactions()
  try {
    producer.beginTransaction()
    producer.send("transfers", transferRecord)
    producer.send("audit-log", auditRecord)
    producer.sendOffsetsToTransaction(offsets, consumerGroupId)
    producer.commitTransaction()       // atomic
  } catch (Exception e) {
    producer.abortTransaction()        // rollback
  }

Guarantees:
  1. All messages in transaction appear atomically (all or nothing)
  2. Consume-process-produce loop is exactly-once
  3. Broker uses two-phase commit protocol internally
```

---

### 5.5 Cluster Metadata & Coordination (ZooKeeper → KRaft)

#### ZooKeeper Era (Legacy, pre-Kafka 3.0)

```
ZooKeeper used for:
  ├── Broker registration (ephemeral znodes)
  ├── Controller election (/controller znode)
  ├── Topic config storage
  ├── Partition leader state
  └── Consumer group membership (old clients only)

Problems:
  ├── Two systems to operate and scale
  ├── ZooKeeper bottleneck at ~200K znodes
  ├── Metadata propagation lag (seconds on large clusters)
  └── Complex failure modes (split brain between ZK and Kafka)
```

#### KRaft Mode (Kafka >= 3.3, production-ready)

```
Cluster Metadata Log:
  KRaft controller maintains a replicated event log (Raft consensus)
  All cluster state = replay of this log (topics, partitions, ISR, configs...)

Controller Quorum (3 dedicated brokers):
  ├── Active Controller: processes all metadata mutations
  ├── Followers: replicate metadata log, ready to take over
  └── Raft leader election: O(seconds) on failure

Broker Fetch from Controller:
  Brokers pull metadata updates from active controller
  ├── On startup: full snapshot + incremental log
  └── On change: near-real-time propagation via fetch loop

Advantages over ZooKeeper:
  ├── Single system — simpler ops, single config
  ├── Supports millions of partitions (vs. 200K ZK limit)
  ├── Faster recovery: milliseconds vs. 30+ seconds
  └── Event-sourced metadata: full audit history, point-in-time recovery
```

---

## 6. Scale & Resilience

### Scaling Strategy

#### Horizontal Broker Scaling

```
Adding a broker to the cluster:
  1. Start new broker with unique broker.id
  2. KRaft controller detects new broker via heartbeat
  3. Admin triggers partition reassignment (kafka-reassign-partitions.sh)
  4. Leader begins replicating to new broker
  5. New broker joins ISR once caught up
  6. Admin can trigger preferred-leader election to balance leadership

Key metric: partition leadership balance across brokers
```

#### Partition Scaling (Increasing Partitions)

```
Only possible to INCREASE partition count (not decrease).
Warning: For keyed topics, adding partitions breaks key → partition mapping
         (existing messages in old partitions, new messages in new partitions)

Best practice: Over-provision partitions at topic creation
  → Rule of thumb: max(target_throughput / broker_throughput, consumer_parallelism)
  → For 10 consumers max, create ≥ 10 partitions
```

### Hotspot / Hot Partition Problem

```
Cause: Skewed partition key distribution
  → One partition receives 90% of traffic
  → One broker becomes bottleneck

Mitections:
  1. Salting: Append random suffix to key: user_id → user_id_3
              → Data spread, but partitions are no longer per-user
              → Merge on read side

  2. Custom Partitioner: Detect hot keys, use separate "VIP" partitions
                         → complex routing logic

  3. Throughput monitoring: Alert on partition-level byte rate imbalance

  4. Time-based partitioning: For time-series data, route by time bucket
                               not entity key
```

### Failure Modes & Mitigations

| Failure | Impact | Mitigation |
|---|---|---|
| **Broker crash** | Partitions where it was leader become unavailable | ISR election kicks in < 1s (KRaft). Clients retry. |
| **Network partition** | Broker cut off from controller | Shrinks ISR; leader stays, followers removed from ISR |
| **Disk failure** | All partitions on that disk unavailable | RAID, or replica on another broker promotes to leader |
| **Controller failure** | No metadata changes possible | KRaft Raft election picks new active controller in seconds |
| **Slow consumer** | Consumer lag grows | Monitor lag metric; scale out consumer group or increase partitions |
| **Producer flooding** | Broker overwhelmed | Quota API on broker; rate-limit per client principal |
| **Message Poison Pill** | Consumer loops forever on bad message | DLQ pattern; skip after N retries |
| **Split brain** | Two brokers both think they are leader | ISR + epoch fencing; leader with stale epoch is rejected by broker |

### Cross-DC Replication (MirrorMaker 2)

```
Active-Active Multi-Region Setup:

  Region US-EAST                    Region EU-WEST
  ┌────────────────┐                ┌────────────────┐
  │  Kafka Cluster │  ───────────► │  Kafka Cluster │
  │   (primary)    │  MirrorMaker2 │   (replica)    │
  └────────────────┘               └────────────────┘
        ▲                                  │
        └──────────────────────────────────┘
              (bidirectional sync for Active-Active)

MirrorMaker2:
  - Replication lag: typically < 1 second for active topics
  - Preserves offsets via remote offset translation
  - Supports topic renaming: us-east.orders → eu-west.orders
  - Used for: DR, geo-routing, aggregation
```

---

## 7. Trade-offs & Alternatives

### Key Design Trade-offs

| Decision | Choice Made | Alternative | Why This Choice |
|---|---|---|---|
| **Pull vs. Push** | Pull (consumers poll) | Push (broker pushes) | Consumer controls rate; no back-pressure complexity; consumers can pause/replay |
| **Log vs. Queue** | Append-only log | Delete-on-consume queue (RabbitMQ) | Replay capability; multiple consumer groups; simpler broker (no selective delete) |
| **Storage** | Disk-first (log segments) | Memory-first (Redis Streams) | Capacity: disk is 10-50× cheaper per GB; ms latency acceptable |
| **Ordering** | Per-partition ordering | Global ordering | Global ordering requires 1 partition → no parallelism; per-partition is sufficient for most use cases |
| **Replication** | Leader-follower (ISR) | Multi-master / leaderless | Simpler consistency model; easier offset tracking; leader is source of truth |
| **Coordination** | KRaft Raft consensus | ZooKeeper | Eliminates operational burden; faster recovery; better scalability |
| **Compression** | Batch-level (LZ4/Snappy/GZIP/ZSTD) | Per-message | Better compression ratio (cross-message patterns); amortized CPU cost |

### Kafka vs. Alternatives

| System | Model | When to Choose |
|---|---|---|
| **Kafka** | Log-based, partitioned | High throughput, replay, event sourcing, strong ordering per key |
| **RabbitMQ** | AMQP queue with routing | Complex routing (fanout, topic, direct exchanges); task queues |
| **AWS SQS** | Managed queue | Simple decoupling, serverless, don't want to operate infrastructure |
| **AWS Kinesis** | Managed Kafka-like | AWS-native; simpler ops; lower throughput ceiling than self-managed Kafka |
| **Redis Streams** | In-memory log | Sub-millisecond latency; smaller datasets; ephemeral streams |
| **Pulsar** | Topic segments on BookKeeper | Multi-tenancy, geo-replication, tiered storage out of the box |

### Kafka vs. Pulsar

```
Kafka:
  + Mature ecosystem (Kafka Connect, Kafka Streams, ksqlDB)
  + Extremely high throughput (millions/sec per broker)
  + Simpler architecture (broker = storage + serving)
  - Partition count limits scalability (though KRaft alleviates)
  - Scaling storage requires scaling compute too

Pulsar:
  + Compute-storage separation (BookKeeper for storage, Brokers for serving)
  + Instant scaling: add brokers without partition rebalancing
  + Native multi-tenancy and geo-replication
  - Less mature ecosystem
  - More complex operational model (ZooKeeper + BookKeeper + Brokers)
```

---

## 8. Observability & Security

### Key Metrics to Monitor

| Category | Metric | Alert Threshold |
|---|---|---|
| **Producer** | `record-error-rate` | > 0.01% |
| **Producer** | `request-latency-avg` | > 50ms |
| **Broker** | `bytes-in-per-sec` per partition | > 80% of NIC capacity |
| **Broker** | `under-replicated-partitions` | > 0 (critical) |
| **Broker** | `offline-partitions-count` | > 0 (critical — page on-call) |
| **Broker** | `active-controller-count` | != 1 (split brain or no controller) |
| **Consumer** | `consumer-lag` per group/topic/partition | > configurable threshold |
| **Consumer** | `records-consumed-rate` | Sudden drop |
| **JVM** | GC pause duration | > 1s |
| **Disk** | Disk I/O utilization | > 85% |

### Distributed Tracing

```
Producer adds trace context to message headers:
  X-Trace-Id: abc123
  X-Span-Id:  456def

Consumer extracts headers, creates child span:
  Enables E2E trace: API Gateway → Producer → Kafka → Consumer → DB
  Tool: OpenTelemetry + Jaeger
```

### Security Model

| Concern | Solution |
|---|---|
| **Authentication** | SASL/SCRAM-SHA-512 (username/password) or mTLS (client certificates) |
| **Authorization** | Kafka ACLs per principal: `READ`/`WRITE`/`CREATE`/`DELETE` per topic |
| **Encryption in transit** | TLS everywhere (broker-to-broker, client-to-broker) |
| **Encryption at rest** | Disk-level encryption (OS/platform managed); or application-level payload encryption |
| **Audit logging** | Kafka Ranger plugin or custom authorizer logs all access attempts |
| **Secret management** | Broker keystore / truststore via Vault or AWS Secrets Manager |
| **Network isolation** | VPC-only access; private endpoints; security groups restrict ports |

---

## 9. SDE 3 Signals Checklist

Use this during your actual interview to ensure you've covered all dimensions:

```
✅ REQUIREMENTS
   □ Asked about throughput, latency, durability, ordering needs
   □ Distinguished functional vs. non-functional
   □ Explicitly called out what's out of scope

✅ CAPACITY
   □ Estimated write RPS, storage, network bandwidth
   □ Derived broker count and disk sizing
   □ Mentioned replication amplification on storage

✅ ARCHITECTURE
   □ Sketched producers → brokers → consumers
   □ Explained partitioning and why it enables parallelism
   □ Covered replication for durability
   □ Mentioned consumer groups for independent consumption

✅ DEEP DIVES (pick at least 2-3)
   □ Log segment structure + why append-only is fast (sequential I/O, zero-copy)
   □ ISR + High Watermark + acks configuration
   □ Leader election mechanism (KRaft/Raft)
   □ Consumer group protocol + rebalancing (eager vs. cooperative sticky)
   □ Exactly-once semantics (idempotent + transactional producer)
   □ Log compaction vs. retention

✅ SCALE & RESILIENCE
   □ Hotspot / skewed partition key problem
   □ Broker failure → ISR election
   □ Cross-DC replication via MirrorMaker2
   □ Consumer lag as a health signal
   □ DLQ pattern for poison messages

✅ TRADE-OFFS
   □ Pull vs. push model
   □ Log vs. delete-on-consume queue
   □ acks=1 vs. acks=-1 (durability vs. latency)
   □ Kafka vs. Pulsar vs. SQS

✅ OBSERVABILITY
   □ under-replicated-partitions (critical alert)
   □ consumer-lag monitoring
   □ Distributed tracing via message headers

✅ SECURITY
   □ SASL or mTLS for authentication
   □ ACLs for per-topic authorization
   □ TLS in transit
```

---

## Appendix: Quick Reference — Key Kafka Configs

### Broker (Critical)

```properties
# Replication & Durability
default.replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false

# Retention
log.retention.hours=168          # 7 days
log.segment.bytes=1073741824     # 1 GB per segment
log.retention.check.interval.ms=300000

# Performance
num.io.threads=8
num.network.threads=3
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
```

### Producer (Critical)

```properties
acks=-1                          # wait for all ISR acks
retries=2147483647               # retry forever (with idempotence)
max.in.flight.requests.per.connection=5
enable.idempotence=true
compression.type=lz4
batch.size=65536                 # 64 KB
linger.ms=5
```

### Consumer (Critical)

```properties
enable.auto.commit=false
isolation.level=read_committed   # for exactly-once consumers
max.poll.records=500
session.timeout.ms=45000
heartbeat.interval.ms=3000
partition.assignment.strategy=CooperativeStickyAssignor
```
