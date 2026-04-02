# HLD — Distributed Key-Value Store (DynamoDB / Cassandra / Redis-like)

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min  
> **Category:** Distributed Storage / Database Internals / NoSQL

---

## Table of Contents

1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [Storage Engine — LSM-Tree vs. B-Tree](#51-storage-engine--lsm-tree-vs-b-tree)
   - 5.2 [Consistent Hashing & Data Distribution](#52-consistent-hashing--data-distribution)
   - 5.3 [Replication & Quorum Consistency](#53-replication--quorum-consistency)
   - 5.4 [Conflict Resolution & Vector Clocks](#54-conflict-resolution--vector-clocks)
   - 5.5 [Failure Detection & Gossip Protocol](#55-failure-detection--gossip-protocol)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [Observability & Security](#8-observability--security)
9. [SDE 3 Signals Checklist](#9-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Functional Requirements

| # | Requirement |
|---|---|
| FR-1 | `PUT(key, value)` — store or update a key-value pair |
| FR-2 | `GET(key)` → value — retrieve by key |
| FR-3 | `DELETE(key)` — soft-delete a key |
| FR-4 | Support **TTL** (time-to-live) expiry per key |
| FR-5 | Keys and values are **arbitrary byte arrays** (no schema enforcement) |
| FR-6 | Support **batch get / batch put** for efficiency |
| FR-7 | Optional: **conditional writes** (CAS — compare-and-swap) |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Scale** | Petabyte-scale data; billions of keys |
| **Throughput** | 1 million reads/sec + 500K writes/sec |
| **Latency** | p99 read < 5 ms, p99 write < 10 ms |
| **Availability** | 99.999% (5 nines); always writable even during failures |
| **Durability** | Zero data loss once write is acknowledged |
| **Consistency** | Tunable (eventual by default, strong on demand) |
| **Horizontal Scalability** | Add nodes transparently without downtime |
| **Fault Tolerance** | Survive up to N-1 node failures in a replication group |

### Clarifying Questions to Ask in Interview

```
1. What is the max size of a key? (e.g., 1 KB) Value? (e.g., 1 MB)
2. Do we need range queries, or only point lookups? (impacts storage engine)
3. Do we need strong consistency, or is eventual acceptable?
4. Single-region or multi-region? Active-Active or Active-Passive?
5. Do we need transaction support (multi-key atomic operations)?
6. Any specific hotspot patterns? (e.g., time-series data → timestamp keys)
```

### Out of Scope
- Secondary indexes / query by non-key attributes (that's DynamoDB GSI territory)
- Multi-key ACID transactions with arbitrary isolation levels
- Full-text search

---

## 2. Capacity Estimation

### Assumptions

| Parameter | Value |
|---|---|
| Total keys | 1 trillion (10¹²) |
| Avg key size | 128 bytes |
| Avg value size | 1 KB |
| Replication factor | 3 |
| Read:Write ratio | 2:1 |
| Read RPS | 1,000,000 |
| Write RPS | 500,000 |

### Calculations

```
Total raw data:
  10¹² keys × (128 B key + 1 KB value) ≈ 10¹² × 1.1 KB ≈ 1.1 PB raw

With replication (RF=3):
  1.1 PB × 3 ≈ 3.3 PB total storage across cluster

Per-node storage (assuming 10 TB SSDs):
  3.3 PB / 10 TB = 330 nodes minimum
  Target 60-70% utilization → ~500 storage nodes

Write throughput per node:
  500K writes/sec ÷ 500 nodes = 1,000 writes/sec per node (very manageable)

Write amplification (LSM compaction):
  Each write → memtable → L0 SSTables → L1 → L2 compaction
  Typical amplification: 10-30× for leveled compaction
  → 500K logical writes/sec × 10 = 5M physical writes/sec cluster-wide
  → ~10,000 physical writes/sec per node (well within NVMe capacity)

Network bandwidth:
  500K writes/sec × 1 KB × 3 replicas = 1.5 GB/s cluster-wide write traffic
  1M reads/sec × 1 KB = 1 GB/s read traffic
  Total: ~2.5 GB/s cluster network → 10 Gbps NICs sufficient per node
```

### Node Fleet Sizing

| Component | Count | Spec |
|---|---|---|
| Storage Nodes | 500 | 64 GB RAM, 10 TB NVMe SSD, 10 Gbps NIC |
| Coordinator Nodes | 10 | 32 GB RAM, stateless, handle routing |
| Monitoring | 3 | Prometheus + Grafana |

---

## 3. API Design

### Client-Facing API

```http
# PUT — store or update a key
PUT /v1/keys/{key}
Headers:
  X-TTL-Seconds: 3600           (optional)
  X-Consistency: quorum         (one | quorum | all)
  X-Condition-Version: 42       (optional CAS — only write if version matches)
Body: <raw bytes or JSON>

Response 200:
{
  "key": "user:123:profile",
  "version": 43,                // monotonically increasing per key
  "timestamp": "2026-04-01T10:00:00Z"
}
Response 412 (Precondition Failed): version mismatch on CAS


# GET — retrieve by key
GET /v1/keys/{key}
Headers:
  X-Consistency: one            (one | quorum | all)
  X-Read-Repair: true           (default true)

Response 200:
{
  "key": "user:123:profile",
  "value": "<base64-encoded bytes>",
  "version": 43,
  "ttl_remaining_sec": 3587,
  "timestamp": "2026-04-01T10:00:00Z"
}
Response 404: key not found (or expired)


# DELETE — soft-delete (tombstone)
DELETE /v1/keys/{key}

Response 204 No Content


# Batch GET
POST /v1/keys/batch-get
{
  "keys": ["user:123", "user:456", "product:789"],
  "consistency": "one"
}
Response 200:
{
  "results": [
    { "key": "user:123", "value": "...", "version": 43 },
    { "key": "user:456", "found": false },
    { "key": "product:789", "value": "...", "version": 7 }
  ]
}


# Batch PUT
POST /v1/keys/batch-put
{
  "items": [
    { "key": "user:123", "value": "...", "ttl_sec": 3600 },
    { "key": "user:456", "value": "..." }
  ],
  "consistency": "quorum"
}
```

### Internal APIs (Node-to-Node)

```
# Replication (coordinator → replica)
REPLICATE key value version vector_clock

# Read repair (async, after serving GET)
REPAIR key value version_vector

# Anti-entropy (background sync between nodes)
MERKLE_SYNC range_hash

# Gossip (node health propagation)
GOSSIP node_state_list
```

---

## 4. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                           CLIENTS                                    │
│     [Web Servers]  [Microservices]  [Mobile Backends]  [ML Jobs]    │
└──────────────────────────┬───────────────────────────────────────────┘
                           │  HTTP/gRPC (or custom binary protocol)
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     CLIENT LIBRARY / SDK                             │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────────┐  │
│  │  Consistent     │  │  Request Routing │  │  Local Cache       │  │
│  │  Hashing Ring   │  │  (key → nodes)   │  │  (optional, L1)    │  │
│  └─────────────────┘  └──────────────────┘  └────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────────┘
                           │  Direct to responsible node(s)
┌──────────────────────────▼───────────────────────────────────────────┐
│                    COORDINATOR LAYER (stateless)                     │
│   ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐       │
│   │  Coord-1  │  │  Coord-2  │  │  Coord-3  │  │  Coord-N  │       │
│   │           │  │           │  │           │  │           │       │
│   │ • Route   │  │ • Route   │  │ • Route   │  │ • Route   │       │
│   │ • Quorum  │  │ • Quorum  │  │ • Quorum  │  │ • Quorum  │       │
│   │ • Auth    │  │ • Auth    │  │ • Auth    │  │ • Auth    │       │
│   └───────────┘  └───────────┘  └───────────┘  └───────────┘       │
└──────────────────────────┬───────────────────────────────────────────┘
                           │  Fan-out to N replica nodes
┌──────────────────────────▼───────────────────────────────────────────┐
│                    STORAGE NODE RING (consistent hashing)            │
│                                                                      │
│         Node A [tokens: 0-90]      Node B [tokens: 91-180]          │
│         ┌──────────────────┐       ┌──────────────────┐             │
│         │  ┌────────────┐  │       │  ┌────────────┐  │             │
│         │  │ MemTable   │  │       │  │ MemTable   │  │             │
│         │  │ (in RAM)   │  │       │  │ (in RAM)   │  │             │
│         │  └─────┬──────┘  │       │  └─────┬──────┘  │             │
│         │        │ flush   │       │        │ flush   │             │
│         │  ┌─────▼──────┐  │       │  ┌─────▼──────┐  │             │
│         │  │  SSTable   │  │       │  │  SSTable   │  │             │
│         │  │  L0,L1,L2  │  │◄──────┤  │  L0,L1,L2  │  │             │
│         │  └────────────┘  │ Repl. │  └────────────┘  │             │
│         │  ┌────────────┐  │       │  ┌────────────┐  │             │
│         │  │   Bloom    │  │       │  │   Bloom    │  │             │
│         │  │  Filter    │  │       │  │  Filter    │  │             │
│         │  └────────────┘  │       │  └────────────┘  │             │
│         │  ┌────────────┐  │       │                  │             │
│         │  │Merkle Tree │  │       │                  │             │
│         │  └────────────┘  │       │                  │             │
│         └──────────────────┘       └──────────────────┘             │
│                                                                      │
│         Node C [tokens: 181-270]   Node D [tokens: 271-360]         │
│         (same structure...)        (same structure...)               │
│                                                                      │
│         ← Gossip protocol propagates health state across all nodes → │
└──────────────────────────────────────────────────────────────────────┘
```

### Request Flow (Write Path)

```
Client PUT(key="user:123", value=...)
  ↓
1. Client SDK: hash(key) → position on ring → find coordinator node
   ↓
2. Coordinator determines preference list:
   N=3 nodes responsible for this key range (primary + 2 replicas)
   ↓
3. Coordinator sends write to all 3 nodes in parallel
   ↓
4. Each node: append to WAL → write to MemTable → ACK coordinator
   ↓
5. Coordinator waits for W=2 ACKs (quorum write)
   ↓
6. Return success to client (even if 3rd replica hasn't ACKed yet)
   ↓
7. 3rd replica eventually replicates asynchronously (hinted handoff if down)
```

### Request Flow (Read Path)

```
Client GET(key="user:123")
  ↓
1. Client SDK: hash(key) → find responsible nodes
   ↓
2. Coordinator sends read to R=2 nodes in parallel (quorum read)
   ↓
3. Nodes check:
   a. Bloom filter → key might exist
   b. Block cache (in-memory) → cache hit? return immediately
   c. SSTable index → locate key in L0..Ln
   d. Return value + version/vector clock
   ↓
4. Coordinator: compare versions from R responses
   → if same version: return to client
   → if different versions: resolve conflict (last-write-wins or vector clock)
   → trigger async read repair to sync stale replica
   ↓
5. Return value to client
```

---

## 5. Deep Dives

### 5.1 Storage Engine — LSM-Tree vs. B-Tree

#### Why LSM-Tree for Write-Heavy KV Stores?

```
B-Tree:
  Writes go directly to disk pages (random I/O)
  ├── Pro: Fast point reads (O(log N))
  └── Con: Write amplification from in-place updates + page splits

LSM-Tree (Log-Structured Merge Tree):
  Writes are sequential (WAL → MemTable → SSTable flush)
  ├── Pro: Sequential writes = 10-100× faster than random
  ├── Pro: High write throughput without random I/O
  └── Con: Read amplification (may check L0..Ln SSTables)
           Space amplification (old versions until compaction)
```

#### LSM-Tree Internals

```
Write Path:
  1. Write to WAL (Write-Ahead Log) on disk — sequential write, crash recovery
     [record format: key_len | key | value_len | value | timestamp | checksum]

  2. Write to MemTable (in-memory sorted structure: Red-Black Tree or SkipList)
     → MemTable is sorted by key → fast binary search reads

  3. When MemTable reaches threshold (e.g., 64 MB):
     → Flush to disk as SSTable (Sorted String Table)
     → SSTable is immutable once written
     → New MemTable starts for incoming writes

SSTable Structure on Disk:
  ┌─────────────────────────────────────────────────────┐
  │  Data Blocks (key-value pairs, sorted by key)       │
  │  ┌────────────────────────────────────────────┐     │
  │  │ key=user:001 | value=... | v=5 | ts=...    │     │
  │  │ key=user:002 | value=... | v=3 | ts=...    │     │
  │  │ key=user:003 | TOMBSTONE | v=6 | ts=...    │ ← delete marker
  │  └────────────────────────────────────────────┘     │
  │                                                     │
  │  Index Block (sparse: every 16th key → data offset) │
  │  ┌────────────────────────────────────────────┐     │
  │  │ user:001 → byte offset 0                   │     │
  │  │ user:017 → byte offset 16384               │     │
  │  └────────────────────────────────────────────┘     │
  │                                                     │
  │  Bloom Filter (per SSTable)                         │
  │  Filter Block: probabilistic membership test        │
  │  → If bloom says NO → skip this SSTable entirely    │
  │  → If bloom says YES → do binary search in index    │
  │                                                     │
  │  Footer: index offset, bloom offset, compression    │
  └─────────────────────────────────────────────────────┘
```

#### Compaction Strategies

```
LEVELED COMPACTION (RocksDB, LevelDB default):
  L0: 4 SSTables (fresh flushes from MemTable)
  L1: 10 SSTables (max 10 MB total)
  L2: 100 SSTables (max 100 MB total)
  L3: 1000 SSTables (max 1 GB total)
  ...

  Rule: When L(n) is full → merge overlapping SSTables from L(n) into L(n+1)
  → Each level is 10× larger than previous
  → Only one version of each key exists in L1+ (no key overlap within a level)
  → Read amplification: check L0 (all files) + 1 file per L1..Ln = O(log N)
  → Space amplification: ~1.1× (minimal extra space)
  → Write amplification: ~30× (data written many times through levels)
  → Best for: read-heavy workloads, bounded space usage

SIZE-TIERED COMPACTION (Cassandra default):
  Group similarly-sized SSTables → merge when threshold count reached
  → Pro: Low write amplification (~10×)
  → Con: High space amplification (2× during compaction); worse read performance
  → Best for: write-heavy workloads, time-series data

TIERED + LEVELED (RocksDB Universal):
  Hybrid approach; configurable trade-off point
```

#### Read Path with Bloom Filters

```
GET key="user:999"
  ↓
Step 1: Check MemTable → not found (O(log M))
  ↓
Step 2: Check Immutable MemTable (being flushed) → not found
  ↓
Step 3: For each SSTable level L0 → L1 → L2 → ...:
    a. Query Bloom Filter for "user:999"
       → If NO: skip this SSTable entirely (99% of cases for non-existent keys)
       → If YES (maybe): proceed
    b. Binary search in Index Block → find data block offset
    c. Check Block Cache (LRU in RAM) → cache hit? return immediately
    d. Read data block from disk → scan for key → found? return value

False positive rate of Bloom Filter:
  p = (1 - e^(-kn/m))^k  where k=hash functions, n=items, m=bits
  Typical: 1% false positive with ~10 bits per key
  → 10 billion keys × 10 bits = 12.5 GB bloom filter (fits in RAM)
```

---

### 5.2 Consistent Hashing & Data Distribution

#### Why Consistent Hashing?

```
Naive approach: hash(key) % N servers
  Problem: Adding/removing 1 server remaps ~N/N+1 keys → massive data movement

Consistent Hashing:
  Map both servers and keys onto a hash ring [0, 2^32)
  → Each key is assigned to the first server clockwise from its position
  → Adding/removing 1 server remaps only K/N keys (K = total keys, N = nodes)
```

#### Hash Ring with Virtual Nodes

```
Physical Ring:
  Node A at position 100
  Node B at position 200
  Node C at position 300

Problem with physical ring: uneven load if nodes placed unevenly

Virtual Nodes (vnodes):
  Each physical node gets V virtual tokens scattered around the ring
  (e.g., V=150 vnodes per node in Cassandra)

  Node A owns tokens: [25, 67, 143, 201, 289, ...]   (150 positions)
  Node B owns tokens: [12, 89, 155, 223, 301, ...]   (150 positions)
  Node C owns tokens: [40, 112, 178, 245, 320, ...]  (150 positions)

Benefits:
  1. Even load distribution (150 tokens × 3 nodes = 450 positions across ring)
  2. Gradual data movement when node added/removed
  3. Handle heterogeneous hardware (fast node → more vnodes)

Replication (N=3):
  key K → primary node P → also replicate to next 2 clockwise nodes
  → Preference list: [P, P+1, P+2] on the ring

  key hash = 150
  Primary:  Node A (closest clockwise: owns token 155 → Node B... wait)
  Actually: the node owning the token just ≥ 150 in clockwise direction
```

#### Consistent Hashing Lookup

```java
// Pseudocode for coordinator routing
int hash = murmur3Hash(key);                    // O(1)
Token token = ring.ceilingToken(hash);          // O(log V×N) — TreeMap lookup
Node primary = ring.getNode(token);             // O(1)
List<Node> preferenceList = ring.getNextNNodes(token, replicationFactor);
// preferenceList = [primary, replica1, replica2]
```

#### Data Rebalancing on Node Add/Remove

```
Node X joins ring (gets V tokens):
  For each new token position t:
    → Find key range [prev_token..t] that X now owns
    → Primary of that range (old node) streams data to X
    → Only K/N total keys move (N = new node count)

Node X leaves ring (or fails):
  → Its token positions removed from ring
  → Keys now map to next clockwise node
  → Replication kicks in from another replica immediately
  → Background: receives full copy from surviving replicas
```

---

### 5.3 Replication & Quorum Consistency

#### Quorum Protocol (NWR Model)

```
N = Replication factor (number of replicas per key)
W = Write quorum (nodes that must ACK a write)
R = Read quorum (nodes that must respond to a read)

Consistency guarantee: W + R > N → reads always see latest write

Common configurations:
┌─────────────┬───┬───┬───┬──────────────────────────────────────┐
│ Mode        │ N │ W │ R │ Guarantee                            │
├─────────────┼───┼───┼───┼──────────────────────────────────────┤
│ Strong      │ 3 │ 3 │ 2 │ Always consistent (W+R=5 > N=3)     │
│ Quorum      │ 3 │ 2 │ 2 │ Consistent (W+R=4 > N=3) ← default │
│ Eventual    │ 3 │ 1 │ 1 │ No guarantee; maximum availability   │
│ Write fast  │ 3 │ 1 │ 3 │ Consistent reads; fast writes        │
└─────────────┴───┴───┴───┴──────────────────────────────────────┘

DynamoDB exposes this as:
  ConsistentRead=true  → R=N (read from all replicas, compare)
  ConsistentRead=false → R=1 (eventually consistent, cheapest)
```

#### Sloppy Quorum & Hinted Handoff

```
Problem: One of the N responsible nodes is down
  → Strict quorum would REJECT the write (availability loss)

Sloppy Quorum:
  → Accept write at any W available nodes (even if not in preference list)
  → Store with a "hint": "this data belongs to Node X"
  → When Node X recovers, hinted node delivers stored data → delete local copy

Example:
  Preference list for key K: [A, B, C]
  Node C is down

  Strict quorum: FAIL if W=3, or proceed if W=2 (B+A ACK)
  Sloppy quorum: write to A + B + D (next node after C)
    → D stores hint: "deliver to C when it recovers"
    → When C recovers: D sends hint data to C, deletes its copy

Result: Higher availability at cost of potential momentary inconsistency
```

#### Read Repair

```
Read repair happens after serving a GET when replicas disagree:

Client reads from R=2 nodes:
  Node A returns: version=5, value="Alice"
  Node B returns: version=3, value="Alice_old"   ← stale replica

Coordinator:
  → Pick highest version (5) → return to client immediately
  → Async: send latest value (version=5) to Node B → B updates
  → Node B is now in sync

Read repair heals stale data without blocking the client response.
Types:
  Read repair: triggered on read (only heals keys that are accessed)
  Anti-entropy repair: background Merkle tree comparison (heals unread keys)
```

---

### 5.4 Conflict Resolution & Vector Clocks

#### Why Conflicts Happen

```
Concurrent writes to same key in distributed system:

Timeline:
  t=0: key="balance", value=100
  t=1: Client A reads balance=100
  t=2: Client B reads balance=100
  t=3: Client A writes balance=90 (deducted $10)
  t=4: Client B writes balance=80 (deducted $20) [using stale read!]
  t=5: Which write wins? Node 1 saw A's write, Node 2 saw B's write.
```

#### Strategy 1: Last Write Wins (LWW) — Simplest

```
Each write tagged with wall-clock timestamp (or Lamport timestamp)
Conflict resolution: higher timestamp wins

Pros:  Simple, no client involvement required
Cons:  Clock skew in distributed systems → wrong "winner"
       Data loss: concurrent writes — one silently discarded

Used by: Cassandra (default), DynamoDB (when no versioning)
Suitable for: Idempotent writes, time-series data, cache replacement
NOT suitable for: Financial data, shopping carts, inventory
```

#### Strategy 2: Vector Clocks — Precise Causality

```
Vector clock = map of {nodeId → logicalClock}
  Tracks causal relationships between writes across nodes

Write sequence:
  Initial:  VC = {}
  Client A writes → Node 1 processes: VC = {N1: 1}
  Client A reads from N1 (VC={N1:1}) → writes again:
    → N1: VC = {N1: 2}  [increments own counter]

Concurrent write:
  Client B reads stale (VC={}) → writes to Node 2:
    → N2: VC = {N2: 1}

Later, N1 and N2 try to merge:
  N1 has: value="90",  VC={N1:2}
  N2 has: value="80",  VC={N2:1}

  Can N1 dominate N2?  N1.VC[N2] = 0 < N2.VC[N2] = 1 → NO, concurrent!
  → Conflict detected → return both versions to client for resolution

Client-resolved conflict:
  Application decides: "balance = 80" (deduct both transactions? merge?)
  → Writes resolved value with merged VC: {N1:2, N2:1, N1:3}

Pros:  Accurate causality tracking; no silent data loss
Cons:  Vector grows with node count; complex; requires client-side resolution

Used by: Amazon Dynamo DB (original paper), Riak
```

#### Strategy 3: CRDTs (Conflict-free Replicated Data Types)

```
Specially designed data structures that merge automatically:

G-Counter (Grow-only counter):
  Each node tracks its own count increment
  Merge: take max per node across all replicas
  Result: total = sum of all max values

  Node A contribution: 5 (incremented 5 times)
  Node B contribution: 3 (incremented 3 times)
  Total: 8 (no matter what order you merge)

LWW-Register: last-write-wins register (uses timestamps)
OR-Set: add/remove set where concurrent adds dominate removes

Used by: Redis CRDT (Enterprise), Riak types, distributed counters

Suitable for: User presence, view counters, shopping cart items
             (not for balances requiring exact correctness)
```

---

### 5.5 Failure Detection & Gossip Protocol

#### Gossip-Based Failure Detection

```
Problem: Central health-check service is a SPOF.
         Polling N nodes from coordinator → O(N) load.

Gossip Protocol:
  Each node maintains a membership list:
  {nodeId → {heartbeat_counter, timestamp_of_last_update}}

  Every T seconds (e.g., T=1s):
  → Node X picks K random nodes (e.g., K=3) from its list
  → X sends its entire membership list to those K nodes
  → Recipients merge: update any node entries with higher heartbeat

  Convergence: All nodes know about a failure in O(log N) rounds
  (each round, info spreads to K more nodes. After r rounds: K^r nodes know)

  Failure detection:
  → If Node Y's heartbeat hasn't incremented in T_fail seconds (e.g., 5s)
    → Mark Y as "SUSPECT"
  → If still not incremented after T_remove seconds (e.g., 30s)
    → Mark Y as "DEAD" → remove from ring
    → Trigger replication to restore replication factor
```

#### Gossip Message Format

```
NodeId: "node-A"
Heartbeat: 10042
Timestamp: 1711962001
MembershipList: [
  { nodeId: "node-B", heartbeat: 9987, timestamp: 1711961998, status: "UP" },
  { nodeId: "node-C", heartbeat: 3,    timestamp: 1711961900, status: "SUSPECT" },
  { nodeId: "node-D", heartbeat: 8821, timestamp: 1711962000, status: "UP" }
]
```

#### Phi Accrual Failure Detector (used by Cassandra)

```
Instead of a binary UP/DOWN threshold:
  → Outputs a continuous suspicion level φ (phi)
  → φ = -log10(P_later) where P_later = probability of heartbeat arriving

  φ < 1:   Not suspicious
  φ = 1:   10% probability of failure
  φ = 5:   99.999% probability of failure (typically treat as dead)

Advantage over fixed-timeout:
  Self-calibrates to actual network conditions
  Low false-positive rate even under high load
  Cassandra uses φ=8 as DEAD threshold
```

#### Anti-Entropy Using Merkle Trees

```
Purpose: Detect and repair data inconsistencies between replicas
         (Read repair only fixes keys that are accessed)

Merkle Tree per node (per key range):
  → Leaf nodes = hash of individual key-value pairs
  → Internal nodes = hash of children hashes
  → Root hash = fingerprint of entire key range

Sync protocol (two nodes A and B for same key range):
  1. Exchange root hashes
  2. If root hashes MATCH → ranges are identical → stop
  3. If DIFFER → exchange children hashes recursively
  4. Identify divergent leaf ranges (O(diff) not O(total))
  5. Exchange only divergent key-value pairs

Result: Efficient repair of stale replicas without full data transfer
Cassandra runs repair weekly; DynamoDB runs continuously in background
```

---

## 6. Scale & Resilience

### Handling Hotspot Keys

```
Problem: Certain keys receive massively disproportionate traffic
Examples:
  - Trending hashtag counter: #WorldCup → millions of reads/sec
  - Popular product inventory: iPhone launch → reads + CAS writes
  - Celebrity user profile: Beyonce → millions of reads

Solution 1 — Client-side key salting (for reads):
  Store N copies: "iphone:inventory#0" through "iphone:inventory#9"
  → Round-robin reads across 10 copies (10× throughput)
  → Background reconciliation to keep copies in sync
  → Works for read-heavy hotspots

Solution 2 — Dedicated "hot" node pools:
  Detect hot keys via coordinator metrics (requests/sec per key)
  → Route hot keys to a dedicated pool of more powerful nodes
  → Isolates hotspot from affecting other keys

Solution 3 — Local caching (DAX in DynamoDB):
  Add in-memory caching layer in front of KV store
  Ultra-hot reads served from cache without hitting storage nodes
  Redis / Memcached in front of KV store

Solution 4 — Request coalescing:
  Coordinator buffers identical GET requests for same key for T=1ms
  → Single backend request services N clients simultaneously
```

### Failure Scenarios

| Failure | Detection | Recovery |
|---|---|---|
| **Single node crash** | Gossip marks SUSPECT in ~5s, DEAD in ~30s | ISR election; hinted handoff data delivered on recovery |
| **Network partition** | Gossip stops propagating across split | Sloppy quorum on each side; anti-entropy repair when healed |
| **Disk failure** | I/O error on SSTable read | Bootstrap from other replicas via streaming |
| **Memory exhaustion** | MemTable flush fails | GC tuning; cascading flush triggers; may OOM if not managed |
| **Compaction backlog** | Write stall when L0 SSTables accumulate | Throttle writes; add SSDs; tune compaction throughput |
| **ZooKeeper / coordinator down** | Client SDK detects connection failure | Retry with backoff; switch to another coordinator |
| **Data center outage** | All nodes in DC unreachable | Route traffic to multi-region replicas (if configured) |
| **Clock skew** | LWW picks wrong winner | Use Lamport clocks or vector clocks; NTP tightening |

### Multi-Region Deployment

```
Active-Active (DynamoDB Global Tables model):
  ┌──────────────────┐      ┌──────────────────┐
  │   US-EAST        │◄────►│   EU-WEST        │
  │  KV Cluster      │      │  KV Cluster      │
  └──────────────────┘      └──────────────────┘
         ▲                          ▲
         │  ┌──────────────────┐    │
         └──┤   AP-SOUTHEAST   ├────┘
            │  KV Cluster      │
            └──────────────────┘

Cross-region replication:
  → Asynchronous (< 1s lag under normal conditions)
  → LWW or CRDT conflict resolution
  → Trade-off: lower write latency (local ACK) vs. potential conflict

Active-Passive (simpler DR):
  → Writes only to primary region
  → Replica region receives async stream
  → Failover: promote replica to primary, update DNS
  → RPO: seconds; RTO: minutes
```

---

## 7. Trade-offs & Alternatives

### Core Design Decisions

| Decision | Choice Made | Alternative | Rationale |
|---|---|---|---|
| **Data model** | Flat KV (byte arrays) | Structured types (DynamoDB Item) | Maximum flexibility; schema-free |
| **Storage engine** | LSM-Tree | B-Tree (PostgreSQL, InnoDB) | Write-optimized; sequential I/O |
| **Distribution** | Consistent hashing | Range partitioning (Bigtable, HBase) | No router needed; client-side routing; even distribution |
| **Consistency** | Tunable (NWR model) | Always-strong (Spanner, TrueTime) | Better availability; suits most use cases |
| **Failure detection** | Gossip (decentralized) | Centralized heartbeat server | No SPOF; scales to thousands of nodes |
| **Conflict resolution** | LWW (default) + vector clocks (optional) | Operational transform | LWW simple and sufficient for most; VC for shopping-cart-like data |
| **Compaction** | Leveled (RocksDB) | Size-tiered (Cassandra default) | Better read performance; bounded space amplification |

### System Comparisons

| System | Storage | Consistency | Distribution | Best For |
|---|---|---|---|---|
| **Redis** | In-memory + optional AOF | Strong (single node) | Cluster (hash slots) | Caching, sub-ms latency, ephemeral data |
| **Memcached** | Pure in-memory | Eventual (no replication) | Client-side sharding | Pure caching, simple, high throughput |
| **DynamoDB** | LSM-tree (proprietary) | Tunable (eventual/strong) | Consistent hashing | Serverless, managed, variable workloads |
| **Cassandra** | LSM-tree (SSTable) | Tunable (CL=ONE..ALL) | Consistent hashing + vnodes | Write-heavy, time-series, multi-DC |
| **RocksDB** | LSM-tree (embeddable) | Strong (single-node embedded) | Application-managed | Embedded storage engine for other DBs |
| **etcd** | B-Tree (bbolt) | Strong (Raft) | Raft consensus group | Config store, leader election, small data |
| **TiKV** | RocksDB + Raft | Strong (Raft groups) | Range partitioning | ACID transactions, strong consistency |

---

## 8. Observability & Security

### Key Metrics

| Category | Metric | Alert Threshold |
|---|---|---|
| **Latency** | `get_latency_p99` | > 5 ms |
| **Latency** | `put_latency_p99` | > 10 ms |
| **Throughput** | `reads_per_sec`, `writes_per_sec` | < 80% capacity |
| **Storage** | `disk_usage_percent` per node | > 75% |
| **LSM** | `l0_file_count` (RocksDB) | > 20 (write stall imminent) |
| **LSM** | `compaction_pending_bytes` | > 10 GB (backlog growing) |
| **Replication** | `under_replicated_key_ranges` | > 0 |
| **Gossip** | `dead_node_count` | > 0 (immediate alert) |
| **Cache** | `block_cache_hit_rate` | < 90% |
| **GC** | `gc_pause_p99` (JVM nodes) | > 500 ms |

### Dashboards to Build

```
1. Cluster Health Dashboard:
   - Node ring status (all nodes green/yellow/red)
   - Under-replicated ranges heatmap
   - Gossip convergence time

2. Performance Dashboard:
   - Read/write latency histograms (p50/p95/p99)
   - Throughput by node and overall
   - Block cache hit rate trend

3. LSM Health Dashboard:
   - L0 file count per node (approaching write stall?)
   - Compaction throughput vs. write throughput
   - Space amplification ratio

4. Consumer-Facing SLO Dashboard:
   - Error rate (4xx, 5xx)
   - Availability % (30-day rolling)
   - Slow query log (> 10ms)
```

### Security

| Concern | Mitigation |
|---|---|
| **Authentication** | mTLS between clients and nodes; API keys for external clients |
| **Authorization** | Key-prefix-based ACLs (e.g., `service-A` can only access `svc-a:*` keys) |
| **Encryption in transit** | TLS 1.3 on all connections (client ↔ node, node ↔ node) |
| **Encryption at rest** | AES-256-GCM on SSTable files; KMS-managed envelope keys |
| **Network isolation** | Nodes in private VPC; no public IPs; security groups restrict ports |
| **Rate limiting** | Per-client token bucket on coordinator (quota enforcement) |
| **Audit logging** | All `PUT`/`DELETE` operations logged with client identity + timestamp |
| **Tenant isolation** | Key namespace prefixes enforced at API level to prevent cross-tenant access |

---

## 9. SDE 3 Signals Checklist

```
✅ REQUIREMENTS
   □ Asked about key/value size limits
   □ Asked about consistency requirements (strong vs. eventual)
   □ Asked about range queries vs. point lookups
   □ Distinguished single-region vs. multi-region

✅ CAPACITY
   □ Estimated total data volume (petabyte scale)
   □ Derived node count from per-node storage capacity
   □ Accounted for replication amplification (3×)
   □ Estimated write amplification from LSM compaction

✅ ARCHITECTURE
   □ Sketched coordinator → storage ring → clients
   □ Explained consistent hashing for routing
   □ Covered replication for durability
   □ Explained both write path and read path

✅ DEEP DIVES (pick 2-3 to go deep on)
   □ LSM-Tree: MemTable → SSTable flush, Bloom filter, Compaction
   □ Consistent hashing + virtual nodes, rebalancing on node add/remove
   □ NWR quorum model: W+R>N for consistency
   □ Conflict resolution: LWW vs. vector clocks vs. CRDTs
   □ Gossip protocol + Phi accrual failure detector
   □ Hinted handoff + sloppy quorum for high availability
   □ Merkle tree anti-entropy repair

✅ SCALE & RESILIENCE
   □ Hotspot key problem + mitigations (salting, dedicated pools, caching)
   □ Single node failure + recovery via replication
   □ Network partition handling (sloppy quorum)
   □ Multi-region: Active-Active vs. Active-Passive trade-offs
   □ Compaction backlog / write stall mitigation

✅ TRADE-OFFS
   □ LSM-Tree vs. B-Tree (write vs. read amplification)
   □ Consistent hashing vs. range partitioning
   □ Eventual vs. strong consistency (and when you need strong)
   □ LWW vs. vector clocks (simplicity vs. precision)
   □ Gossip vs. centralized failure detection

✅ OBSERVABILITY
   □ under_replicated_key_ranges → critical alert
   □ l0_file_count → write stall prediction
   □ consumer-facing p99 latency SLO

✅ SECURITY
   □ mTLS between nodes
   □ Key-prefix ACLs for tenant isolation
   □ Encryption at rest (AES-256 + KMS)
```

---

## Appendix: Key Configuration Reference

### Storage Engine (RocksDB-like)

```properties
# MemTable
write_buffer_size=67108864          # 64 MB per MemTable
max_write_buffer_number=3           # max 3 immutable MemTables before stall

# L0 (write stall/stop thresholds)
level0_slowdown_writes_trigger=20   # slow writes when L0 has 20 files
level0_stop_writes_trigger=36       # stop writes at 36 files

# Compaction
compaction_style=kLeveledCompaction
max_bytes_for_level_base=268435456  # 256 MB for L1
max_bytes_for_level_multiplier=10   # each level 10× larger

# Cache
block_cache_size=8589934592         # 8 GB block cache per node

# Bloom Filter
bloom_bits_per_key=10               # ~1% false positive rate
```

### Cluster Configuration

```yaml
replication_factor: 3
write_consistency: QUORUM          # W=2 out of N=3
read_consistency: ONE              # R=1 (eventual), or QUORUM for strong

gossip:
  interval_ms: 1000
  fanout: 3                        # gossip to 3 random nodes per cycle
  suspect_threshold_ms: 5000       # mark SUSPECT if no heartbeat for 5s
  dead_threshold_ms: 30000         # mark DEAD after 30s

hinted_handoff:
  enabled: true
  throttle_in_kb_per_sec: 1024    # cap redelivery bandwidth

anti_entropy:
  repair_interval_hours: 24        # full Merkle repair every 24h
  parallel_repair_sessions: 4
```
