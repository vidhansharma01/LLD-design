# HLD — Distributed Message Queue (like RabbitMQ)

> **Interview Time:** ~45 minutes | **Level:** SDE 3

---

## Table of Contents

1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dive — Core Components](#5-deep-dive--core-components)
   - 5.1 Message Broker Node
   - 5.2 Message Routing (Exchanges & Bindings)
   - 5.3 Persistence & Durability
   - 5.4 Consumer Delivery & Acknowledgement
   - 5.5 Cluster Coordination
6. [Scalability & Fault Tolerance](#6-scalability--fault-tolerance)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [Observability & Security](#8-observability--security)
9. [SDE 3 Edge Cases & Follow-up Challenges](#9-sde-3-edge-cases--follow-up-challenges)

---

## 1. Requirements Clarification

### 1.1 Clarifying Questions to Ask

> "Before I start designing, let me ask a few questions to scope this correctly."

- Are we building a **general-purpose broker** (like RabbitMQ) or a **log-based streaming** system (like Kafka)?
- Do we need **message routing** (exchanges, topics, fanout) or simple FIFO queues?
- What **delivery semantics** are required — at-most-once, at-least-once, or exactly-once?
- Do consumers need to **acknowledge** messages? Should unacked messages be redelivered?
- What is the expected **message size**? (bytes vs. megabytes matters architecturally)
- Do we need **ordered delivery** within a queue?
- How long should messages be **retained** — TTL per message? Dead-letter queues?
- Do we need **priority queues** (higher-priority messages delivered first)?
- **Multi-tenancy**: multiple teams / isolated environments?
- **Geographic distribution**: multi-region or single region?

### 1.2 Functional Requirements (Agreed Scope)

| # | Requirement |
|---|---|
| FR-1 | Producers publish messages to named **exchanges** |
| FR-2 | Exchanges route messages to one or more **queues** based on routing rules |
| FR-3 | Consumers subscribe to queues and receive messages |
| FR-4 | Consumer sends **acknowledgement (ACK)** after processing; unacked messages are redelivered |
| FR-5 | Messages can have a **TTL** and are routed to a **Dead Letter Queue (DLQ)** if expired or rejected |
| FR-6 | Support **in-order, at-least-once delivery** within a queue |
| FR-7 | Support **queue durability** — messages survive broker restarts |
| FR-8 | Support **fanout, direct, and topic routing** via exchanges |
| FR-9 | Provide **admin APIs** to create/delete exchanges, queues, and bindings |
| FR-10 | Support **priority queues** (optional, stretch goal) |

### 1.3 Non-Functional Requirements

| Requirement | Target |
|---|---|
| **Throughput** | 1M+ messages/sec across the cluster |
| **Latency** | p99 end-to-end < 10ms for small payloads |
| **Availability** | 99.99% (< 52 min downtime/year) |
| **Durability** | Zero message loss once broker acknowledges publish |
| **Ordering** | FIFO within a single queue |
| **Scalability** | Horizontally scalable; add brokers without downtime |
| **Multi-tenancy** | Logical isolation via virtual hosts (vhosts) |

---

## 2. Capacity Estimation

> "Let's size this for a large-scale deployment like those run by cloud providers."

### Assumptions
- **1,000 producers** publishing at an average of **1,000 msgs/sec** each
- **Average message size:** 1 KB
- **Message retention TTL:** 7 days for undelivered messages
- **Replication factor:** 3 (3 copies of each queue)

### Calculations

| Metric | Calculation | Result |
|---|---|---|
| **Total publish RPS** | 1,000 producers × 1,000 msg/s | **1M msg/s** |
| **Inbound bandwidth** | 1M × 1 KB | **~1 GB/s** |
| **Storage per day** | 1M msg/s × 86,400 s × 1 KB | **~86 TB/day** |
| **Storage (7-day TTL)** | 86 TB × 7 | **~602 TB** |
| **With 3× replication** | 602 TB × 3 | **~1.8 PB** |
| **Network (fanout avg 2)** | 1 GB/s inbound × 2 fanout × 3 replicas | **~6 GB/s** |

### Broker Node Sizing
- A single broker node can handle ~100K msg/s with sequential disk writes
- At 1M msg/s → minimum **10 broker nodes** (target 70% utilization → **~15 nodes**)
- Each node: 32 vCPU, 128 GB RAM, NVMe SSD

---

## 3. API Design

### 3.1 Admin/Management API (REST)

```
# Exchange Management
POST   /v1/vhosts/{vhost}/exchanges
        Body: { name, type: "direct|fanout|topic|headers", durable, auto_delete }
DELETE /v1/vhosts/{vhost}/exchanges/{name}

# Queue Management
POST   /v1/vhosts/{vhost}/queues
        Body: { name, durable, exclusive, auto_delete, arguments: { x-message-ttl, x-dead-letter-exchange, x-max-priority } }
DELETE /v1/vhosts/{vhost}/queues/{name}
GET    /v1/vhosts/{vhost}/queues/{name}/stats

# Binding Management
POST   /v1/vhosts/{vhost}/bindings
        Body: { exchange, queue, routing_key }
DELETE /v1/vhosts/{vhost}/bindings/{binding_id}
```

### 3.2 Producer API (AMQP over TCP or HTTP API)

```
# Publish a message
POST /v1/vhosts/{vhost}/exchanges/{exchange}/publish
Content-Type: application/json
{
  "routing_key": "order.created",
  "payload": "<base64-encoded body>",
  "properties": {
    "content_type": "application/json",
    "delivery_mode": 2,          // 1=transient, 2=persistent
    "priority": 5,               // 0-10
    "expiration": "60000",       // TTL in ms
    "message_id": "uuid-v4",
    "correlation_id": "req-uuid"
  }
}

# Response
{ "routed": true, "message_id": "uuid-v4" }
```

### 3.3 Consumer API (AMQP or Long-Poll REST)

```
# Basic Get (polling)
GET  /v1/vhosts/{vhost}/queues/{queue}/get?count=10&ackmode=ack_requeue_false

# Acknowledge messages
POST /v1/vhosts/{vhost}/queues/{queue}/ack
     Body: { delivery_tag: "dt-123", multiple: false }

# Negative Acknowledge (reject + optional requeue)
POST /v1/vhosts/{vhost}/queues/{queue}/nack
     Body: { delivery_tag: "dt-123", multiple: false, requeue: true }

# Purge queue
DELETE /v1/vhosts/{vhost}/queues/{queue}/contents
```

> **Note:** In production, consumers connect via **AMQP 0-9-1 over TCP** (persistent connection, channel multiplexing). The REST API is for management tooling and simple integrations.

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Client Layer                               │
│   Producers (AMQP / REST)          Consumers (AMQP / REST)         │
└────────────────┬───────────────────────────┬────────────────────────┘
                 │                           │
                 ▼                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Load Balancer (Layer 4 / NLB)                   │
│         Sticky sessions per AMQP connection (IP hash or DNS)        │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
           ┌───────────────────┼───────────────────┐
           ▼                   ▼                   ▼
  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
  │  Broker Node 1  │ │  Broker Node 2  │ │  Broker Node N  │
  │                 │ │                 │ │                 │
  │ • AMQP Engine   │ │ • AMQP Engine   │ │ • AMQP Engine   │
  │ • Exchange Mgr  │ │ • Exchange Mgr  │ │ • Exchange Mgr  │
  │ • Queue Engine  │ │ • Queue Engine  │ │ • Queue Engine  │
  │ • Message Store │ │ • Message Store │ │ • Message Store │
  │ • ACK Tracker   │ │ • ACK Tracker   │ │ • ACK Tracker   │
  └────────┬────────┘ └────────┬────────┘ └────────┬────────┘
           │                   │                   │
           └───────────────────┼───────────────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
  ┌─────────────────┐  ┌────────────────┐  ┌──────────────────┐
  │ Metadata Store  │  │  Replication   │  │  Distributed     │
  │  (etcd/ZK)      │  │  Log (Raft)    │  │  Storage Layer   │
  │                 │  │                │  │  (NVMe SSDs /    │
  │ • Vhost config  │  │ • Queue data   │  │   shared SAN)    │
  │ • Exchange defs │  │   replication  │  │                  │
  │ • Queue defs    │  │ • Leader elect │  │                  │
  │ • Bindings      │  │                │  │                  │
  │ • Cluster state │  │                │  │                  │
  └─────────────────┘  └────────────────┘  └──────────────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
  ┌─────────────────┐  ┌────────────────┐  ┌──────────────────┐
  │  Dead Letter    │  │  Management    │  │  Observability   │
  │    Service      │  │  Console (UI)  │  │  (Prometheus +   │
  │  (DLQ routing)  │  │                │  │   Grafana +      │
  │                 │  │                │  │   Jaeger)        │
  └─────────────────┘  └────────────────┘  └──────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| **Load Balancer** | TCP-level routing; long-lived AMQP connection affinity |
| **Broker Node** | Core message routing, storage, delivery engine |
| **AMQP Engine** | Protocol parsing, channel multiplexing, flow control |
| **Exchange Manager** | Route messages from exchange → queues using bindings |
| **Queue Engine** | FIFO ordering, priority heap, TTL enforcement, ACK tracking |
| **Message Store** | Persistent message storage with WAL and indexing |
| **Metadata Store (etcd)** | Cluster configuration, leader election, binding resolution |
| **Replication Log (Raft)** | Replicate queue data across broker nodes for HA |
| **Dead Letter Service** | Routes expired/rejected messages to DLQ exchanges |
| **Management Console** | REST API + web UI for operations |

---

## 5. Deep Dive — Core Components

### 5.1 Broker Node Internals

Each broker node handles producers and consumers for the queues it **owns** (is the leader of).

```
┌──────────────────────────────────────────────────────┐
│                     Broker Node                       │
│                                                      │
│  ┌─────────────┐   ┌──────────────┐                 │
│  │  AMQP       │   │  REST API    │                 │
│  │  Listener   │   │  Handler     │                 │
│  │  (TCP 5672) │   │  (HTTP 15672)│                 │
│  └──────┬──────┘   └──────┬───────┘                 │
│         └────────┬─────────┘                         │
│                  ▼                                    │
│         ┌─────────────────┐                          │
│         │  Channel Mgr    │  (Multiplexes N channels │
│         │                 │   over 1 TCP connection) │
│         └────────┬────────┘                          │
│                  ▼                                    │
│         ┌─────────────────┐                          │
│         │  Exchange Layer  │                          │
│         │  • Direct        │                          │
│         │  • Fanout        │                          │
│         │  • Topic (trie)  │                          │
│         │  • Headers       │                          │
│         └────────┬────────┘                          │
│                  ▼                                    │
│         ┌─────────────────┐   ┌──────────────────┐  │
│         │  Queue Process  │   │  ACK Tracker     │  │
│         │  (one per queue)│   │  (in-flight msgs) │  │
│         │  • Head/Tail ptr│   │  • Delivery tag  │  │
│         │  • Priority heap│   │  • Redelivery    │  │
│         │  • TTL scanner  │   │  • Prefetch count│  │
│         └────────┬────────┘   └──────────────────┘  │
│                  ▼                                    │
│         ┌─────────────────┐                          │
│         │  Message Store  │ ← WAL journal (write-first)
│         │  • Segment files│                          │
│         │  • Index file   │                          │
│         │  • Ack journal  │                          │
│         └─────────────────┘                          │
└──────────────────────────────────────────────────────┘
```

**Key design choices:**
- Each queue is managed by a **single Erlang-style process** (lightweight actor model) — prevents lock contention
- **Channel multiplexing:** A single TCP connection supports thousands of virtual channels; prevents connection overhead
- **Prefetch count (QoS):** Consumer declares how many unacked messages it can hold → prevents overwhelming slow consumers

---

### 5.2 Message Routing — Exchange Types & Bindings

```
Producer publishes to: exchange="orders" routing_key="order.created.EU"

Exchange Type: TOPIC
Bindings:
  queue="eu-orders"         routing_key="order.created.EU"   → MATCH ✓
  queue="all-orders"        routing_key="order.#"            → MATCH ✓ (wildcard)
  queue="payment-service"   routing_key="payment.*"          → NO MATCH ✗

Message copies delivered to: [eu-orders, all-orders]
```

#### Exchange Types

| Exchange Type | Routing Logic | Use Case |
|---|---|---|
| **Direct** | Exact match on routing key | Task queues, RPC |
| **Fanout** | Broadcast to ALL bound queues | Notifications, event broadcast |
| **Topic** | Pattern match (`*` = one word, `#` = zero or more) | Hierarchical event routing |
| **Headers** | Match on message headers (not routing key) | Complex conditional routing |

#### Binding Table (Metadata in etcd)

```
bindings: {
  "orders": {              // exchange name
    "eu-orders": ["order.created.EU", "order.updated.EU"],
    "all-orders": ["order.#"],
    "payment-svc": ["payment.*"]
  }
}
```

**Topic exchange routing** uses a **Trie / Radix Tree** for efficient `*` and `#` wildcard resolution in O(depth) time:

```
Trie nodes: order → created → EU
                            → US
                  → updated → EU
         payment → * (wildcard)
```

---

### 5.3 Persistence & Durability

#### Durability Guarantee

A message is acknowledged to the producer **only after** it has been:
1. Written to the **WAL (Write-Ahead Log)** on disk  
2. Replicated to **at least 1 follower** (quorum = 2/3 nodes)

```
Producer → Broker Leader
           Leader writes to WAL
           Leader replicates to 2 followers (Raft)
           Quorum (2 of 3) confirms
           Leader sends PUBLISH-CONFIRM back to producer ✓
```

#### Storage Layout (Message Store)

```
/data/queues/order-queue/
  ├── segments/
  │     ├── 0000000001.log     ← Append-only message segment (e.g. 256 MB each)
  │     ├── 0000000002.log
  │     └── 0000000003.log     ← Active (current write segment)
  ├── index/
  │     └── offset.idx         ← Maps message offset → byte position in segment file
  └── ack/
        └── ack.journal        ← Tracks which offsets have been ACKed (for cleanup/GC)
```

**Key design decisions:**

| Decision | Choice | Rationale |
|---|---|---|
| Write model | Sequential append-only | Maximum disk throughput; avoids random I/O |
| Message format | Fixed-length header + variable payload | Fast parsing; binary encoding (Protocol Buffers) |
| Index structure | Sparse index (every Nth message) | Low memory; O(1) segment lookup + O(N) linear scan within segment |
| Segment rollover | By size (256 MB) or time (1 hour) | Bounded file size; easier GC |
| Garbage collection | Compact when all messages in segment are ACKed | Reclaim disk space without affecting reads |
| Persistence level | `delivery_mode=2` = durable; persisted pre-ACK | Producer opt-in durability |

---

### 5.4 Consumer Delivery & Acknowledgement Model

#### Delivery Flow

```
┌──────────┐   SUBSCRIBE (queue, prefetch=10)   ┌────────┐
│ Consumer │ ─────────────────────────────────► │ Broker │
│          │                                     │        │
│          │ ◄──── message (delivery_tag=1) ──── │        │
│          │ ◄──── message (delivery_tag=2) ──── │        │
│          │        ...up to prefetch count       │        │
│          │                                     │        │
│          │ ──── ACK (delivery_tag=2,            │        │
│          │       multiple=false) ─────────────► │        │
│          │     Broker marks msg 2 as ACKed      │        │
│          │     Sends next message               │        │
│          │ ◄──── message (delivery_tag=3) ──── │        │
└──────────┘                                     └────────┘
```

#### Acknowledgement Modes

| Mode | Behaviour | Risk |
|---|---|---|
| **Auto-ACK** | Broker considers message delivered upon send | Message lost if consumer crashes before processing |
| **Manual ACK** | Consumer explicitly sends ACK after processing | At-least-once delivery; safe for critical workflows |
| **NACK + Requeue** | Consumer rejects; broker re-enqueues at tail | Enables retry; risk of infinite loop → use retry count |
| **NACK + Dead-letter** | Rejected messages route to DLQ | Poison message handling |

#### Unacknowledged Message Timeout

```
- Broker maintains a min-heap of (deadline, delivery_tag) per consumer channel
- Heartbeat goroutine scans heap every second
- If deadline exceeded → NACK the message → requeue
- Consumer channel closes after ACK timeout (default: 30 min, configurable)
```

#### Dead Letter Queue (DLQ) Flow

```
Message TTL expires OR maxRetries exceeded
           │
           ▼
  Exchange: x-dead-letter-exchange (configured on queue)
           │
           ▼
  DLQ Queue: "order-queue.dlq"
           │
           ▼
  DLQ Consumer reads, investigates, optionally republishes
```

---

### 5.5 Cluster Coordination (Raft-based Quorum Queues)

#### Queue Ownership Model

Each queue has:
- **1 Leader** — handles all reads and writes for that queue
- **N-1 Followers** — replicate the leader's WAL; stand by to become leader

```
                  ┌─────────────────────────────┐
                  │         Raft Group           │
                  │   (per queue or shard)       │
                  │                             │
                  │  ┌─────────┐  ┌──────────┐  │
         Write ──►│  │ Leader  │  │ Follower │  │
   (Broker Node 1)│  │  (N1)   │─►│   (N2)   │  │
                  │  └─────────┘  └──────────┘  │
                  │        │       ┌──────────┐  │
                  │        └──────►│ Follower │  │
                  │                │   (N3)   │  │
                  │                └──────────┘  │
                  └─────────────────────────────┘
```

#### Metadata Coordination (etcd)

- **Exchange definitions, queue definitions, bindings** stored in etcd (CP system)
- Cluster-wide **leader election** for special roles (e.g., TTL scanner, DL router) via etcd leases
- **Cluster membership** changes (add/remove broker) coordinated through etcd

```
etcd key structure:
  /rmq/vhosts/{vhost}/exchanges/{name}  → ExchangeConfig JSON
  /rmq/vhosts/{vhost}/queues/{name}     → QueueConfig JSON
  /rmq/vhosts/{vhost}/bindings/{id}     → BindingConfig JSON
  /rmq/cluster/nodes/{node_id}          → NodeConfig JSON
  /rmq/cluster/queue-leaders/{queue}    → Node ID of current leader
```

#### Leader Failover

```
1. Node 3 (leader for queue "orders") crashes
2. Followers N1 and N2 detect missing heartbeat (after 3s timeout)
3. N1 initiates Raft election, wins with N2's vote
4. N1 becomes new leader; updates etcd: /rmq/cluster/queue-leaders/orders = N1
5. Load balancer health checks see N3 is down; routes new AMQP connections away
6. Producers reconnect; resume publishing to N1
7. Failover time: ~5–10 seconds (configurable via Raft election timeout)
```

---

## 6. Scalability & Fault Tolerance

### 6.1 Horizontal Scaling Strategy

| Layer | Scaling Approach |
|---|---|
| **Broker Nodes** | Add nodes; queues redistribute via consistent hashing of queue names |
| **Queue Sharding** | High-throughput queues split into N shards (shard per partition); consumers assigned to shards |
| **etcd** | 3 or 5 node cluster; scales independently of broker layer |
| **Consumers** | Add consumer instances; broker distributes messages round-robin across consumers in same group |

### 6.2 Queue Sharding (for High-Throughput Queues)

When a single queue cannot keep up:

```
"order-queue" → Shard 0, Shard 1, Shard 2 (e.g. by producer routing_key hash)

Producer → hash(routing_key) % N shards → publish to order-queue-shard-{N}
Consumer Group → N consumers → each consumes from one shard (ordered within shard)
```

### 6.3 Flow Control & Backpressure

| Mechanism | Level | Description |
|---|---|---|
| **Channel QoS / Prefetch** | Consumer | Limits unacked in-flight messages per consumer |
| **Credit-based flow** | Producer | Broker issues credits; producer must not exceed |
| **Broker-level memory alarm** | Node | Pause publishers if broker RAM usage > 40% |
| **Disk alarm** | Node | Pause publishers if free disk < 50 MB |
| **Per-queue length limit** | Queue | Reject or drop oldest/newest when queue full |

### 6.4 Failure Scenarios & Mitigations

| Failure | Detection | Mitigation |
|---|---|---|
| **Broker node crash** | Raft heartbeat timeout (3s) | Raft election → new leader; ~5–10s failover |
| **Network partition** | etcd lease expiry | Minority partition pauses writes; majority continues |
| **Disk full** | Disk alarm | Block publishers; alert ops; route to overflow queue |
| **Consumer crash** | AMQP heartbeat timeout | Redelivery of unACKed messages after visibility timeout |
| **Message storm** | Queue depth monitoring | Enable per-queue publisher rate limit; enable flow control |
| **etcd unavailable** | All brokers detect | Cached bindings served; no new config changes; stale reads only |
| **Data corruption** | CRC checksum on each message | Skip/quarantine corrupted segment; alert + recover from replica |

### 6.5 Multi-Region Deployment

```
Region A (Primary):          Region B (DR):
┌─────────────────┐          ┌─────────────────┐
│  Broker Cluster │ ─────── ►│  Broker Cluster │
│  3 nodes        │  Shovel  │  3 nodes        │
│                 │  Plugin  │                 │
│  etcd cluster   │          │  etcd cluster   │
└─────────────────┘          └─────────────────┘
```

- **RabbitMQ Shovel** (or custom forwarder): async message forwarding across regions
- **Active-Active:** Both regions serve writes; conflict-free via separate vhosts
- **Active-Passive:** Primary serves all writes; standby replicates for DR; RPO ≈ replication lag

---

## 7. Trade-offs & Alternatives

### 7.1 RabbitMQ vs Kafka — When to Choose What

| Dimension | RabbitMQ (this design) | Kafka |
|---|---|---|
| **Delivery model** | Push to consumer; broker tracks offset | Pull by consumer; consumer tracks offset |
| **Message ordering** | FIFO within a queue | Ordered within partition |
| **Replay** | Not supported (messages deleted after ACK) | Full replay from any offset |
| **Routing** | Rich: exchanges, bindings, topic patterns | Topics only; no built-in routing logic |
| **Throughput** | ~50K–500K msg/s per node | Millions msg/s per node |
| **Latency** | Very low (sub-ms to ms range) | Low but slightly higher due to batching |
| **Use case** | Task queues, RPC, complex routing | Event streaming, analytics, log aggregation |
| **Consumer model** | Consumer groups (competing consumers) | Consumer groups with partition assignment |
| **Message size** | Moderate (< 10 MB) | Typically small (< 1 MB recommended) |

**Rule of thumb:** If you need **rich routing, RPC, task distribution** → RabbitMQ. If you need **event streaming, replay, high throughput** → Kafka.

### 7.2 AMQP vs gRPC vs HTTP

| Protocol | Pros | Cons | Use When |
|---|---|---|---|
| **AMQP 0-9-1** | Binary, full-duplex, native framing | Complex to implement clients | Broker-native communication |
| **AMQP 1.0** | Standardized, interoperable | Verbose | Cross-vendor interop |
| **gRPC streaming** | Modern, bidirectional, strongly typed | HTTP/2 only | Service-to-service |
| **HTTP REST** | Universal, easy debugging | Half-duplex, high overhead | Admin API, simple clients |

### 7.3 Replication: Raft vs Chain Replication

| | Raft | Chain Replication |
|---|---|---|
| **Writes** | Leader + quorum ACK | Head (first node); propagates to tail |
| **Reads** | Leader only (or followers with risk of staleness) | Tail (always latest committed state) |
| **Latency** | 1 network round trip (leader → quorum) | N hops in chain |
| **Throughput** | Leader is bottleneck | Tail is bottleneck |
| **Failure handling** | Automatic leader election | Chain reconfiguration needed |
| **Best for** | Coordination, metadata | Object storage (like S3) |

**Choice: Raft** — because it has mature open-source implementations (etcd, Consul), easier reasoning about failures, and standard tooling.

### 7.4 Push vs Pull Consumer Model

**RabbitMQ uses Push (broker-initiated delivery):**
- ✅ Lower latency — no polling
- ✅ Less wasted CPU (no empty polls)
- ❌ Consumer must signal capacity (prefetch QoS); broker must track delivery state per consumer

**Kafka uses Pull (consumer-initiated):**
- ✅ Consumer controls its own pace
- ✅ Simpler broker (no per-consumer state)
- ❌ Higher latency if poll interval is too long
- ❌ Wasted requests if queue is empty (mitigation: long-poll with configurable wait)

---

## 8. Observability & Security

### 8.1 Key Metrics

| Metric | Alert Threshold | Tool |
|---|---|---|
| **Queue depth (messages ready)** | > 100K unprocessed | Prometheus + Grafana |
| **Consumer utilization** | < 50% (under-consuming) | — |
| **Publish rate vs. consume rate** | publish >> consume | — |
| **Unacked message count** | Trending up | — |
| **Broker memory usage** | > 40% → flow control | — |
| **Disk usage** | > 70% → alert | — |
| **Replication lag** | > 1000 messages | — |
| **DLQ depth** | > 0 (investigate) | — |
| **p99 publish latency** | > 50ms | — |
| **p99 consume latency** | > 10ms (from enqueue to deliver) | — |

### 8.2 Distributed Tracing

- Each message carries a **correlation_id** and optional **trace context** (W3C TraceContext header)
- Brokers propagate trace context across publish → deliver → ACK flow
- Integrated with **OpenTelemetry → Jaeger** for end-to-end tracing

### 8.3 Security

| Layer | Mechanism |
|---|---|
| **Transport** | TLS 1.3 for all connections (producer, consumer, inter-node) |
| **Authentication** | SASL PLAIN / DIGEST-MD5 or LDAP/OAuth 2.0 token |
| **Authorization** | Per-vhost RBAC: configure, write, read permissions per exchange/queue |
| **Audit log** | All admin API operations logged (who, what, when) |
| **Secrets management** | TLS certs + credentials via HashiCorp Vault |
| **Network isolation** | Brokers in private subnet; LB exposed to producers/consumers |
| **Message encryption** | Application-layer encryption for sensitive payloads (end-to-end) |

---

## 9. SDE 3 Edge Cases & Follow-up Challenges

### 9.1 Exactly-Once Delivery

> **Interviewer ask:** "Can you support exactly-once delivery?"

True exactly-once requires cooperation between producer, broker, and consumer:

```
1. Producer assigns unique message_id (idempotency key)
2. Broker deduplicates: if message_id already stored → ACK without re-storing
   (Uses a bloom filter + recent message ID cache per exchange)
3. Consumer uses a local DB transaction:
   BEGIN TX;
     INSERT INTO processed_messages(msg_id) VALUES (?) ON CONFLICT IGNORE;
     -- process message, update business state
   COMMIT TX;
   Then send ACK to broker
4. If consumer crashes after processing but before ACK → redelivery detected as duplicate → skip
```

**Key insight:** Broker gives at-least-once; exactly-once requires idempotent consumers.

### 9.2 Priority Queues

```
Queue configured with: x-max-priority = 10

Internal structure: Min-heap (or 10 separate FIFO sub-queues) per priority level
  Priority 10 ←── delivered first (always drain higher priority before lower)
  Priority 5
  Priority 0 ←── lowest priority; may starve if high-priority messages never stop

Starvation prevention: Process K high-priority messages, then 1 lower-priority message
```

### 9.3 Message Deduplication Window

If the producer resends due to network timeout (didn't receive ACK), broker might get duplicates:

```
Solution:
- Producer assigns UUID message_id in properties
- Broker maintains a Redis SET (or bloom filter) of recent message_ids per queue
  with TTL = dedup_window (e.g., 5 minutes)
- On publish: IF message_id in dedup_cache → ACK without re-inserting → idempotent
- Redis key: "dedup:{vhost}:{exchange}:{routing_key}:{message_id}" → TTL 5 min
```

### 9.4 Poison Message Handling

A message that always causes consumer to crash → perpetual redelivery loop:

```
Solution: Track redelivery count in message metadata
  x-death header incremented on each requeue
  If x-death.count >= max_retries (e.g., 5):
    → NACK without requeue
    → Route to Dead Letter Exchange (DLX)
    → Land in DLQ for manual inspection
```

### 9.5 Slow Consumer Problem

A consumer that processes messages slower than they arrive:

```
Symptoms:
  - Queue depth growing unboundedly
  - Memory/disk pressure on broker

Mitigations:
  1. Prefetch limit (QoS): consumer sets prefetch=10, broker won't push more than 10 unacked
  2. Rate limiting: broker enforces max message delivery rate per consumer
  3. Auto-scaling: monitor queue depth, trigger consumer auto-scaling (K8s HPA)
  4. Queue TTL + DLQ: old messages expire, reducing queue depth automatically
```

---

## Summary — SDE 3 Signal Points

| Area | Key Points Covered |
|---|---|
| **Requirements** | Delivery semantics (at-least-once), durability, routing types, TTL, DLQ |
| **Capacity** | 1M msg/s, 602 TB raw, ~1.8 PB with replication, 15 broker nodes sized |
| **API** | REST admin + AMQP protocol for producers/consumers with delivery_tag, prefetch |
| **Architecture** | Layered design: LB → Broker Nodes → Raft replication → etcd metadata |
| **Deep Dive 1** | Message routing via Exchange types (Direct/Fanout/Topic) with trie-based matching |
| **Deep Dive 2** | Persistence via WAL + segment files + sparse index; quorum writes before ACK |
| **Deep Dive 3** | ACK/NACK model, prefetch, unacked timeout, DLQ flow, poison message handling |
| **Deep Dive 4** | Raft-based quorum queues, leader election, etcd coordination |
| **Scalability** | Queue sharding, backpressure/flow control, horizontal broker scaling |
| **Fault Tolerance** | Node failure → Raft failover in 5–10s; consumer crash → redelivery |
| **Trade-offs** | RabbitMQ vs Kafka, Push vs Pull, AMQP vs gRPC, Raft vs Chain Replication |
| **Observability** | 10 key metrics, distributed tracing with correlation_id, Prometheus + Jaeger |
| **Security** | TLS 1.3, SASL/OAuth, per-vhost RBAC, Vault for secrets |
| **Edge Cases** | Exactly-once delivery, priority queues, deduplication window, slow consumers |
