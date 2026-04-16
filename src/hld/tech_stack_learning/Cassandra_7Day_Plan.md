# 🗄️ Apache Cassandra — 7-Day Learning Plan
> **Target Audience:** Senior Software Engineer, beginner in Cassandra
> **Goal:** Go from zero to production-ready usage of Cassandra with deep conceptual understanding

---

## 🤔 What Is Apache Cassandra?

Apache Cassandra is a **distributed, wide-column NoSQL database** designed for:
- **High availability** — no single point of failure
- **Horizontal scalability** — add nodes without downtime
- **Write-optimized** — handles millions of writes/second
- **Multi-datacenter** replication out of the box

> Originally developed at **Facebook** for Inbox search (2008), open-sourced in 2009, now used by **Apple, Netflix, Uber, Discord, Twitter, Instagram**.

### What Problems Does It Solve?

| Problem | Cassandra Answer |
|---|---|
| Single DB can't handle write load | Distribute writes across all nodes equally |
| Master-replica lag on failover | No master — every node is equal (peer-to-peer) |
| Global apps need geo replication | Native multi-DC replication config |
| Schema changes break live traffic | Flexible schema; add columns without downtime |

---

## 📅 7-Day Plan Overview

| Day | Topic | Focus |
|---|---|---|
| **Day 1** | Architecture & Core Concepts | How Cassandra works internally |
| **Day 2** | Data Modeling — The Right Way | Partition keys, clustering, wide rows |
| **Day 3** | CQL — Cassandra Query Language | CRUD, tables, indexes, TTL |
| **Day 4** | Replication & Consistency | CAP tradeoffs, quorum, tuneable consistency |
| **Day 5** | Internals — Write/Read Path | Memtables, SSTables, Bloom Filters, Compaction |
| **Day 6** | Operations & Monitoring | nodetool, repair, performance tuning |
| **Day 7** | Java Driver + Production Patterns | Spring Boot integration, anti-patterns, real examples |

---
---

# 📅 Day 1 — Architecture & Core Concepts

## 1.1 The Cassandra Ring — Peer-to-Peer Architecture

Unlike MySQL (one primary + replicas) or MongoDB (primary + secondaries), **every Cassandra node is equal**. There is no master.

```
Traditional DB:                    Cassandra:
                                   
  [Primary]                        Node A ←→ Node B
  /       \                          ↕               ↕
[Read]  [Read]                     Node D ←→ Node C
                                   
  SPOF on Primary failure          Any node can serve any request
  Writes only to Primary           Writes go to any node (coordinator)
```

### The Ring Topology

Cassandra nodes form a **logical ring**. Each node is responsible for a range of data (called a **token range**).

```
         Node A [0 - 25]
        /                \
Node D [76 - 100]     Node B [26 - 50]
        \                /
         Node C [51 - 75]

Data with token 40 → owned by Node B
Data with token 80 → owned by Node D
```

### Token and Consistent Hashing

- Each row has a **partition key** (e.g., `user_id`)
- Cassandra hashes the partition key → produces a **token** (64-bit integer)
- The token determines **which node(s)** store that data

```
partition_key "user-123" → Murmur3Hash → token -4567891234
token -4567891234 → falls in Node B's range → stored on Node B
```

## 1.2 Virtual Nodes (VNodes)

**Problem with basic ring:** When you add a node, redistribution is uneven (one new node, one old node must transfer data).

**VNodes solution:** Each physical node owns **multiple small, non-contiguous token ranges** (default: 256 vnodes per node).

```
Without VNodes:        With VNodes:
Node A: [0-25]         Node A: [0-5][20-25][50-55][80-85]...
Node B: [26-50]        Node B: [6-10][30-35][60-65][90-95]...
Node C: [51-100]       Node C: [11-19][40-49][70-79][96-100]...
```

**Benefits:**
- New node takes small chunks from **all** existing nodes → balanced distribution
- Better load balance when nodes have different capacities

## 1.3 Coordinator Node

When a client sends a query, it hits **any node** — that node becomes the **coordinator**.

```
Client → Node A (coordinator)
  Node A determines: "partition key X belongs to Node C and Node D"
  Node A forwards request to Node C and Node D
  Node A waits for response (based on consistency level)
  Node A returns result to client
```

The coordinator:
- Routes requests (doesn't need to own the data)
- Applies consistency level logic (how many nodes must respond)
- Handles retries and timeouts

## 1.4 Keyspace and Tables

```
Cassandra
  └── Keyspace (≈ Database in SQL)
        └── Table (≈ Table in SQL, but wide-column)
              └── Row (identified by partition key)
                    └── Columns (can differ per row)
```

### Keyspace Properties

```sql
CREATE KEYSPACE ecommerce
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'datacenter1': 3,   -- 3 replicas in DC1
  'datacenter2': 2    -- 2 replicas in DC2 (for DR)
}
AND durable_writes = true;
```

---
---

# 📅 Day 2 — Data Modeling (Most Critical Skill)

> ⚠️ **This is the most important day.** Bad data modeling = bad performance in Cassandra. Unlike SQL, you design your tables **around your queries**, not around your entities.

## 2.1 The Golden Rule: Query-First Design

In SQL: Design normalized tables → write any query you want.
In Cassandra: **Know your queries first → design tables to answer them.**

```
SQL approach:              Cassandra approach:
entities → tables          queries → tables
normalize first            denormalize by design
joins at query time        no joins; pre-join at write time
```

## 2.2 Partition Key — The Most Important Concept

The **partition key** determines:
1. Which node stores the data (via token hash)
2. What data is co-located on the same node (and disk)
3. What can be queried efficiently

```sql
CREATE TABLE users (
    user_id   UUID,
    email     TEXT,
    name      TEXT,
    PRIMARY KEY (user_id)   -- user_id is the partition key
);
```

**All rows with the same partition key are stored together on the same node.**

### Composite Partition Key

You can use multiple columns as the partition key:

```sql
PRIMARY KEY ((country, city), user_id)
--           ^^^^^^^^^^^^^             composite partition key
--                           ^^^^^^^   clustering key
```

Data for ("India", "Mumbai") all lives on the same node — useful for geo-queries.

## 2.3 Clustering Key — Ordering Within a Partition

The **clustering key** sorts rows within a partition.

```sql
CREATE TABLE user_messages (
    user_id    UUID,
    msg_time   TIMESTAMP,
    message    TEXT,
    sender_id  UUID,
    PRIMARY KEY (user_id, msg_time)
    --          ^^^^^^^  ^^^^^^^^
    --          partition  clustering
) WITH CLUSTERING ORDER BY (msg_time DESC);
```

This table stores:
- All messages for a user **in the same partition** (same node)
- Messages sorted by time **descending** on disk

```
Partition: user_id = "alice"
  msg_time=2024-01-03 | "Hello!" | sender=bob
  msg_time=2024-01-02 | "Hi"     | sender=charlie
  msg_time=2024-01-01 | "Hey"    | sender=dave
```

Querying latest 10 messages is a **single disk seek** — extremely fast.

## 2.4 Wide Rows — Cassandra's Superpower

A wide row is a partition that contains many rows (columns across time or sequence).

```
user_id: alice-uuid
  ├── [2024-01-01] msg: "Hey!"
  ├── [2024-01-02] msg: "How are you?"
  ├── [2024-01-03] msg: "Meet tomorrow?"
  ├── ... (can have millions of rows in this partition)
  └── [2024-12-31] msg: "Happy New Year!"
```

A single read for `user_id = 'alice-uuid'` with `LIMIT 10` retrieves the 10 most recent messages — all from **one node, one disk read**.

## 2.5 Denormalization — Design Pattern

**Problem:** Find all orders for a user.

```sql
-- SQL approach (normalized):
SELECT * FROM orders WHERE user_id = ?;  -- one table

-- Cassandra approach (denormalized):
-- Table 1: Look up order by order_id
CREATE TABLE orders_by_id (
    order_id UUID PRIMARY KEY,
    user_id  UUID,
    amount   DECIMAL,
    status   TEXT
);

-- Table 2: Look up all orders for a user (different partition key!)
CREATE TABLE orders_by_user (
    user_id    UUID,
    created_at TIMESTAMP,
    order_id   UUID,
    amount     DECIMAL,
    status     TEXT,
    PRIMARY KEY (user_id, created_at, order_id)
) WITH CLUSTERING ORDER BY (created_at DESC);
```

You write to **both tables** on every order creation. You read from the appropriate table depending on the query.

**Rule:** For N different query patterns → N tables.

## 2.6 Partition Sizing Guidelines

| Guideline | Recommended Value |
|---|---|
| Max rows per partition | < 100,000 (soft limit) |
| Max partition size on disk | < 100 MB |
| Ideal partition size | 1–10 MB |

**Hotspot anti-pattern:** `date` as partition key → all today's writes go to one node.
**Fix:** Use `(user_id, date)` or bucket by hour: `(date_hour)`.

---
---

# 📅 Day 3 — CQL: Cassandra Query Language

CQL looks like SQL but **behaves very differently**. Understanding these differences is critical.

## 3.1 Keyspace & Table Operations

```sql
-- Create keyspace
CREATE KEYSPACE blog
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};

USE blog;

-- Create table
CREATE TABLE posts (
    author_id   UUID,
    created_at  TIMESTAMP,
    post_id     UUID,
    title       TEXT,
    content     TEXT,
    tags        SET<TEXT>,
    metadata    MAP<TEXT, TEXT>,
    PRIMARY KEY (author_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC)
  AND default_time_to_live = 2592000;  -- 30 days TTL

-- Add a column (no downtime!)
ALTER TABLE posts ADD view_count COUNTER;

-- Drop table
DROP TABLE posts;
```

## 3.2 Data Types

| CQL Type | Java Equivalent | Notes |
|---|---|---|
| `UUID` | `java.util.UUID` | Use `uuid()` for random, `timeuuid()` for time-ordered |
| `TIMEUUID` | `java.util.UUID` | Encodes timestamp — sortable |
| `TEXT` | `String` | UTF-8 string |
| `INT` | `int` | 32-bit |
| `BIGINT` | `long` | 64-bit |
| `DECIMAL` | `BigDecimal` | Arbitrary precision |
| `TIMESTAMP` | `java.time.Instant` | Millisecond precision |
| `BOOLEAN` | `boolean` | |
| `BLOB` | `ByteBuffer` | Raw bytes |
| `LIST<T>` | `List<T>` | Ordered, allows duplicates |
| `SET<T>` | `Set<T>` | Unordered, no duplicates |
| `MAP<K,V>` | `Map<K,V>` | Key-value pairs |
| `FROZEN<T>` | Serialized | Nested types must be frozen |

## 3.3 CRUD Operations

```sql
-- INSERT (always an upsert — no "duplicate key" error)
INSERT INTO posts (author_id, created_at, post_id, title, content, tags)
VALUES (
    550e8400-e29b-41d4-a716-446655440000,
    toTimestamp(now()),
    uuid(),
    'Understanding Cassandra',
    'Cassandra is a distributed database...',
    {'cassandra', 'nosql', 'databases'}
)
USING TTL 86400    -- expires in 1 day
AND TIMESTAMP 1700000000000000;  -- custom write timestamp (microseconds)

-- SELECT — must filter by partition key
SELECT * FROM posts
WHERE author_id = 550e8400-e29b-41d4-a716-446655440000;

-- SELECT with clustering key range
SELECT title, created_at FROM posts
WHERE author_id = 550e8400-e29b-41d4-a716-446655440000
  AND created_at >= '2024-01-01'
  AND created_at <= '2024-01-31'
LIMIT 10;

-- UPDATE (also an upsert)
UPDATE posts
SET title = 'Deep Understanding of Cassandra'
WHERE author_id = 550e8400-e29b-41d4-a716-446655440000
  AND created_at = '2024-01-15 10:00:00'
  AND post_id = 660e8400-e29b-41d4-a716-446655440001;

-- DELETE a specific column
DELETE title FROM posts
WHERE author_id = ... AND created_at = ... AND post_id = ...;

-- DELETE an entire row
DELETE FROM posts
WHERE author_id = ... AND created_at = ... AND post_id = ...;
```

## 3.4 TTL (Time-To-Live)

TTL tells Cassandra to **automatically expire** data after N seconds.

```sql
-- Set TTL on insert
INSERT INTO sessions (session_id, user_id, data)
VALUES (uuid(), uuid(), 'token123')
USING TTL 3600;  -- expires in 1 hour

-- Check remaining TTL
SELECT TTL(data) FROM sessions WHERE session_id = ?;

-- Update TTL on existing row
UPDATE sessions USING TTL 7200
SET data = 'refreshed-token'
WHERE session_id = ?;
```

**How TTL works internally:** Cassandra writes a **tombstone** at the expiry time. The data isn't immediately deleted but is hidden from reads and removed later during **compaction**.

## 3.5 Secondary Indexes

```sql
-- Secondary index (use sparingly — low cardinality columns only)
CREATE INDEX ON posts (tags);  -- SET column index

-- MATERIALIZED VIEW — better alternative to secondary index
CREATE MATERIALIZED VIEW posts_by_tag AS
    SELECT * FROM posts
    WHERE author_id IS NOT NULL
      AND created_at IS NOT NULL
      AND post_id IS NOT NULL
    PRIMARY KEY (tag, author_id, created_at, post_id);
-- Note: tags must be a scalar column for MV; for SET use SASI or Solr
```

> ⚠️ **Warning:** Secondary indexes scan all nodes — avoid on high-cardinality columns. Use a separate denormalized table instead.

## 3.6 Lightweight Transactions (LWT)

LWT provides **compare-and-set** (CAS) semantics using Paxos. Use sparingly — expensive (~4x latency).

```sql
-- Insert only if not exists
INSERT INTO users (user_id, email)
VALUES (uuid(), 'alice@example.com')
IF NOT EXISTS;

-- Update only if current value matches
UPDATE users SET email = 'newalice@example.com'
WHERE user_id = ?
IF email = 'alice@example.com';

-- Returns: [applied] boolean + current row values
```

---
---

# 📅 Day 4 — Replication & Consistency

## 4.1 Replication in Cassandra

When a write comes in, the coordinator replicates it to **RF (Replication Factor)** nodes.

```
RF = 3:
  Write for partition key "alice" → token 1234
  
  Primary node for token 1234: Node A
  Replicas (next 2 nodes clockwise): Node B, Node C
  
  All 3 nodes (A, B, C) receive the write
```

### Replication Strategies

**SimpleStrategy** — for single datacenter / development:
```sql
CREATE KEYSPACE dev_ks
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 3
};
```

**NetworkTopologyStrategy** — for production / multi-DC:
```sql
CREATE KEYSPACE prod_ks
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'us-east': 3,   -- 3 replicas in US East DC
  'eu-west': 3    -- 3 replicas in EU West DC
};
```

## 4.2 Consistency Levels

Consistency level controls **how many replicas must acknowledge** a read/write before the coordinator returns success.

### Write Consistency Levels

| Level | Description | Nodes Required |
|---|---|---|
| `ONE` | Fastest — 1 node acknowledges | 1 |
| `QUORUM` | Majority — (RF/2 + 1) nodes | 2 of 3 |
| `ALL` | All replicas must acknowledge | RF (3 of 3) |
| `LOCAL_QUORUM` | Quorum within local DC only | (local RF/2 + 1) |
| `EACH_QUORUM` | Quorum in every DC | Per DC majority |
| `ANY` | Even a hint counts | 1 (hinted handoff) |

### Read Consistency Levels

| Level | Description |
|---|---|
| `ONE` | Read from 1 replica (fastest, possibly stale) |
| `QUORUM` | Read from majority, coordinator picks latest by timestamp |
| `ALL` | Read from all replicas (slowest, strongest consistency) |
| `LOCAL_ONE` | Read from 1 node in local DC |
| `LOCAL_QUORUM` | Quorum in local DC — best for multi-DC |

## 4.3 The Consistency Formula

```
Strong Consistency = Write CL + Read CL > RF

Example (RF=3):
  Write QUORUM (2) + Read QUORUM (2) = 4 > 3 ✅ (strongly consistent)
  Write ONE (1)   + Read ONE (1)    = 2 < 3 ❌ (eventually consistent)
  Write ALL (3)   + Read ONE (1)    = 4 > 3 ✅ (strongly consistent)
```

## 4.4 Hinted Handoff

When a replica node is **temporarily down**, the coordinator stores the write as a **hint** and replays it when the node comes back.

```
Node C is down:
  Write comes in with CL=ONE
  Coordinator writes to Node A (success: CL=ONE met)
  Coordinator stores HINT: "Node C needs this write"
  
  Node C comes back online:
  Coordinator replays hint → Node C catches up
```

> Hints are stored for up to `max_hint_window_in_ms` (default: 3 hours).

## 4.5 Read Repair

When a read is served, Cassandra can compare data across replicas and fix inconsistencies in the background.

```
Read with CL=QUORUM from Nodes A and B:
  Node A returns: { name: "Alice", age: 30 }  ← timestamp T2
  Node B returns: { name: "Alice", age: 29 }  ← timestamp T1 (stale)
  
  Coordinator picks Node A's version (higher timestamp)
  Returns { name: "Alice", age: 30 } to client
  Async: sends repair write to Node B to update age=30
```

## 4.6 CAP Theorem — Where Cassandra Sits

Cassandra makes a **tunable trade-off**:

```
CL = ONE  → AP (Available + Partition Tolerant) — eventual consistency
CL = QUORUM → CP (Consistent + Partition Tolerant) within the quorum
CL = ALL → CP but sacrifices Availability (if any node is down, write fails)
```

**Cassandra is often described as "AP" by default** (availability over strict consistency), but it's more accurately described as **tunable**.

---
---

# 📅 Day 5 — Internals: Write Path, Read Path, Compaction

Understanding internals is critical for performance tuning and debugging.

## 5.1 Write Path — Blazing Fast Writes

When Cassandra receives a write:

```
Step 1: Write to Commit Log (WAL) — sequential disk write, crash safety
         ↓
Step 2: Write to Memtable — in-memory, sorted data structure
         ↓  (async, when memtable is full or threshold reached)
Step 3: Flush Memtable → SSTable on disk (immutable, sorted)
         ↓  (background)
Step 4: Compaction — merge SSTables, remove tombstones
```

### Commit Log (Write-Ahead Log)

- **Sequential** disk write → very fast (no seeking)
- Survives crash — replayed on restart to recover unflushed memtable data
- Truncated after memtable flush

### Memtable

- In-memory sorted data structure (like a sorted `TreeMap`)
- One memtable per table
- Flushed to disk when full (configurable, default ~64MB per table)

### SSTable (Sorted String Table)

- **Immutable** — written once, never modified
- Contains data sorted by partition key + clustering key
- Multiple SSTables can exist per table (before compaction merges them)
- Each SSTable has accompanying files:

```
data-1-Data.db        ← actual row data
data-1-Index.db       ← partition key → data file offset
data-1-Filter.db      ← Bloom Filter (in-memory membership check)
data-1-Summary.db     ← sparse index for finding partitions
data-1-Statistics.db  ← metadata, min/max clustering values
data-1-TOC.txt        ← table of contents listing all files
```

## 5.2 Read Path — Layered Lookup

```
Read Request
    ↓
1. Check Row Cache (if enabled — rare in practice)
    ↓ cache miss
2. Check Bloom Filter for each SSTable
   → "Is this partition key definitely NOT in this SSTable?"
   → Skip SSTables that say "NO" (definitely absent)
    ↓
3. Check Partition Key Cache
   → Maps partition key → SSTable file offset
    ↓ cache miss
4. Check Partition Summary → find range in Partition Index
    ↓
5. Check Partition Index → exact file offset in Data file
    ↓
6. Read from SSTable Data file
    ↓
7. Merge results from Memtable + all relevant SSTables
   → Latest timestamp wins per column (last-write-wins)
    ↓
Return result
```

### Bloom Filter in Read Path

Each SSTable has its own Bloom Filter loaded in memory. Before reading an SSTable:
```
BF.mightContain(partitionKey)?
  → false (definitely not here) → skip this SSTable entirely
  → true (probably here) → read the SSTable
```

With RF=3 and 10 SSTables per node, Bloom Filters can eliminate 8+ SSTable reads per query.

## 5.3 Tombstones — Deletes in Cassandra

Cassandra **never immediately deletes data**. Instead it writes a **tombstone** — a marker saying "this data was deleted at time T".

```
Row before delete:   { user_id: "alice", name: "Alice", age: 30 }
After DELETE:        { user_id: "alice", tombstone @ T=1700000000 }

During read: tombstone masks all writes with timestamp < T
During compaction: tombstones + original data are removed together
```

### Tombstone Dangers

If tombstones accumulate faster than compaction removes them, reads slow down because they must scan all tombstones.

```
Danger signs:
  - Queries timing out
  - GC pressure
  - Log warnings: "Read X tombstones in query"
```

**Mitigation:**
- Set TTL on time-series data instead of explicit deletes
- Trigger compaction more frequently
- Use `tombstone_warn_threshold` and `tombstone_failure_threshold`

## 5.4 Compaction — Merging SSTables

Compaction merges multiple SSTables into fewer, larger SSTables and removes:
- Expired TTL data
- Tombstones (after `gc_grace_seconds`, default: 10 days)
- Duplicate/overwritten values (keeps latest timestamp)

### Compaction Strategies

| Strategy | Best For | Description |
|---|---|---|
| `STCS` (Size-Tiered) | Write-heavy | Merges SSTables of similar size; default |
| `LCS` (Leveled) | Read-heavy | Small fixed-size SSTables in levels; predictable reads |
| `TWCS` (Time-Window) | Time-series | Groups SSTables by time window; efficient TTL cleanup |

```sql
-- Set compaction strategy
CREATE TABLE metrics (...)
WITH compaction = {
  'class': 'TimeWindowCompactionStrategy',
  'compaction_window_unit': 'HOURS',
  'compaction_window_size': 1
};
```

---
---

# 📅 Day 6 — Operations, Monitoring & Performance

## 6.1 `nodetool` — Your Primary Operations Tool

```bash
# Cluster status — see all nodes and their state
nodetool status
# Output:
# Datacenter: us-east
# Status=Up/Down, State=Normal/Leaving/Joining/Moving
# UN = Up/Normal, DN = Down/Normal
# UN  192.168.1.1  75.0 GiB  256 tokens  Rack: r1
# UN  192.168.1.2  74.5 GiB  256 tokens  Rack: r2

# Ring — shows token ranges
nodetool ring

# Info — JVM heap, caches, key stats
nodetool info

# Compaction status — see pending compactions
nodetool compactionstats

# Force flush memtables to disk (before maintenance)
nodetool flush [keyspace] [table]

# Trigger compaction manually
nodetool compact [keyspace] [table]

# Repair — sync data across replicas (run weekly)
nodetool repair -pr keyspace table  # -pr = primary range only

# See current operations
nodetool tpstats   # Thread pool stats (write/read/compaction queues)

# Garbage collector stats
nodetool gcstats

# Table-level statistics (most important for tuning)
nodetool cfstats keyspace.table
# Shows: read/write latency, bloom filter false positive rate, 
#        partition size, SSTable count, tombstone stats
```

## 6.2 Anti-Entropy Repair

Over time, nodes can drift (missed hints, network issues). **Repair** re-syncs all replicas.

```bash
# Full repair (expensive — avoid during peak hours)
nodetool repair keyspace

# Primary range repair (less I/O — recommended for regular maintenance)
nodetool repair -pr keyspace

# Incremental repair (Cassandra 4+, most efficient)
nodetool repair --incremental keyspace
```

**Best practice:** Run repair on every node within `gc_grace_seconds` (10 days default). Use Reaper (open-source) for automated repair scheduling.

## 6.3 Key Performance Metrics to Monitor

```
Read Latency (p99):   nodetool cfstats → "Read latency"
Write Latency (p99):  nodetool cfstats → "Write latency"
Pending Tasks:        nodetool tpstats → CompactionExecutor.Pending
SSTable Count:        nodetool cfstats → "SSTable count" (high = compaction debt)
Bloom FP Rate:        nodetool cfstats → "Bloom filter false positives"
GC Pause:             nodetool gcstats → pause time > 200ms = problem
Heap Usage:           nodetool info → Heap Memory (keep < 75% usage)
Tombstone Warnings:   grep cassandra.log "tombstones"
```

## 6.4 Cassandra Configuration (`cassandra.yaml`)

```yaml
# Heap size — critical: set to 8GB for most workloads (never > 16GB due to GC)
# Set via cassandra-env.sh
MAX_HEAP_SIZE="8G"
HEAP_NEWSIZE="2G"  # Young generation

# Compaction throughput (MB/s) — throttle during peak hours
compaction_throughput_mb_per_sec: 64

# Hinted handoff window
max_hint_window_in_ms: 10800000  # 3 hours

# GC grace seconds — don't delete tombstones before this
gc_grace_seconds: 864000  # 10 days

# Data directories (put on fast SSDs)
data_file_directories:
  - /data/cassandra/data

# Commit log (put on separate disk from data)
commitlog_directory: /commitlog/cassandra

# Read/Write timeouts
read_request_timeout_in_ms: 5000
write_request_timeout_in_ms: 2000
```

## 6.5 Performance Tuning Tips

```
1. SSTable Count Too High?
   → Trigger nodetool compact
   → Consider LCS compaction for read-heavy workloads

2. High GC Pauses?
   → Reduce heap to 8GB (large heaps cause long GC)
   → Switch to G1GC or ZGC
   → Reduce row cache / key cache sizes

3. Bloom Filter False Positive Rate > 1%?
   → Increase bloom_filter_fp_chance (lower = larger, more accurate BF)
   → nodetool upgradesstables to regenerate BFs

4. High Read Latency?
   → Check SSTable count (compaction backlog?)
   → Check tombstone count (many deletes?)
   → Enable key cache (default ON)
   → Move to LCS compaction

5. Partition Too Large?
   → nodetool cfstats → "Partition size" max
   → Re-model with narrower partition key
```

---
---

# 📅 Day 7 — Java Driver + Production Patterns

## 7.1 Java Driver Setup (DataStax Driver 4.x)

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.datastax.oss</groupId>
    <artifactId>java-driver-core</artifactId>
    <version>4.17.0</version>
</dependency>
<dependency>
    <groupId>com.datastax.oss</groupId>
    <artifactId>java-driver-query-builder</artifactId>
    <version>4.17.0</version>
</dependency>
<dependency>
    <groupId>com.datastax.oss</groupId>
    <artifactId>java-driver-mapper-processor</artifactId>
    <version>4.17.0</version>
</dependency>
```

## 7.2 CqlSession — Connection Management

```java
// CassandraConfig.java
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import java.net.InetSocketAddress;

public class CassandraConfig {

    /**
     * Create a CqlSession — this is the main entry point.
     * One CqlSession per application (thread-safe, connection pool managed internally).
     * Never create a new session per request — it's expensive.
     */
    public static CqlSession createSession() {
        return CqlSession.builder()
            .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
            .addContactPoint(new InetSocketAddress("127.0.0.2", 9042)) // multiple contact points
            .withLocalDatacenter("us-east")       // for DC-aware load balancing
            .withKeyspace("ecommerce")
            .build();
    }

    public static void main(String[] args) {
        // Always use try-with-resources — session.close() releases connections
        try (CqlSession session = createSession()) {
            System.out.println("Connected to Cassandra: " + session.getMetadata().getClusterName());
        }
    }
}
```

## 7.3 PreparedStatements — MUST USE in Production

```java
// UserRepository.java
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import java.util.UUID;

public class UserRepository {

    private final CqlSession session;

    // Prepared statements — compiled once, executed many times
    // NEVER use string concatenation for queries (injection risk + performance)
    private final PreparedStatement insertStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement updateEmailStmt;
    private final PreparedStatement deleteStmt;

    public UserRepository(CqlSession session) {
        this.session = session;

        // Prepare at startup — driver validates against schema
        this.insertStmt = session.prepare(
            "INSERT INTO users (user_id, email, name, created_at) " +
            "VALUES (:user_id, :email, :name, toTimestamp(now()))"
        );

        this.selectByIdStmt = session.prepare(
            "SELECT * FROM users WHERE user_id = :user_id"
        );

        this.updateEmailStmt = session.prepare(
            "UPDATE users SET email = :email WHERE user_id = :user_id " +
            "IF email = :old_email"  // LWT — compare and set
        );

        this.deleteStmt = session.prepare(
            "DELETE FROM users WHERE user_id = :user_id"
        );
    }

    /** Insert a new user */
    public void createUser(UUID userId, String email, String name) {
        BoundStatement bound = insertStmt.bind()
            .setUuid("user_id", userId)
            .setString("email", email)
            .setString("name", name);
        session.execute(bound);
    }

    /** Get user by ID — returns null if not found */
    public Row getUserById(UUID userId) {
        BoundStatement bound = selectByIdStmt.bind()
            .setUuid("user_id", userId);
        ResultSet rs = session.execute(bound);
        return rs.one(); // null if not found
    }

    /** Update email with optimistic locking (LWT) */
    public boolean updateEmail(UUID userId, String oldEmail, String newEmail) {
        BoundStatement bound = updateEmailStmt.bind()
            .setString("email", newEmail)
            .setUuid("user_id", userId)
            .setString("old_email", oldEmail);

        ResultSet rs = session.execute(bound);
        Row result = rs.one();
        return result != null && result.getBoolean("[applied]");
    }

    /** Delete user */
    public void deleteUser(UUID userId) {
        session.execute(deleteStmt.bind().setUuid("user_id", userId));
    }
}
```

## 7.4 Async Operations — Non-Blocking Cassandra Calls

```java
// AsyncUserRepository.java
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

public class AsyncUserRepository {

    private final CqlSession session;
    private final PreparedStatement selectStmt;

    public AsyncUserRepository(CqlSession session) {
        this.session = session;
        this.selectStmt = session.prepare("SELECT * FROM users WHERE user_id = ?");
    }

    /** Non-blocking read — returns CompletableFuture */
    public CompletableFuture<Row> getUserAsync(UUID userId) {
        BoundStatement bound = selectStmt.bind(userId);

        // executeAsync returns CompletionStage — convert to CompletableFuture
        return session.executeAsync(bound)
            .thenApply(AsyncResultSet::one)
            .toCompletableFuture();
    }

    /** Fan-out: fetch multiple users in parallel */
    public CompletableFuture<Void> fetchMultipleUsers(java.util.List<UUID> userIds) {
        java.util.List<CompletableFuture<Row>> futures = userIds.stream()
            .map(this::getUserAsync)
            .collect(java.util.stream.Collectors.toList());

        // Wait for all to complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                futures.forEach(f -> {
                    Row row = f.join();
                    if (row != null) {
                        System.out.println("User: " + row.getString("name"));
                    }
                });
            });
    }
}
```

## 7.5 Batch Statements — When to Use (and When NOT To)

```java
// LOGGED BATCH — atomic (all-or-nothing), expensive (uses Paxos)
// Use ONLY for keeping denormalized tables in sync
public void insertOrderWithBatch(UUID orderId, UUID userId, java.math.BigDecimal amount) {
    PreparedStatement insertByIdStmt = session.prepare(
        "INSERT INTO orders_by_id (order_id, user_id, amount, created_at) " +
        "VALUES (?, ?, ?, toTimestamp(now()))"
    );
    PreparedStatement insertByUserStmt = session.prepare(
        "INSERT INTO orders_by_user (user_id, created_at, order_id, amount) " +
        "VALUES (?, toTimestamp(now()), ?, ?)"
    );

    // Batch keeps both denormalized tables in sync
    BatchStatement batch = BatchStatement.newInstance(DefaultBatchType.LOGGED)
        .add(insertByIdStmt.bind(orderId, userId, amount))
        .add(insertByUserStmt.bind(userId, orderId, amount));

    session.execute(batch);
}

// ❌ WRONG: Using batch to bulk-load independent rows (no atomicity needed)
// This routes ALL writes through a single coordinator — kills performance!
// Instead: use async executeAsync() in a loop with a semaphore
```

## 7.6 Pagination — Handling Large Result Sets

```java
// PaginationExample.java
public void paginateResults(CqlSession session, UUID userId) {
    PreparedStatement stmt = session.prepare(
        "SELECT * FROM posts WHERE author_id = ? LIMIT 10"
    );

    // Use page state for cursor-based pagination (no OFFSET in Cassandra!)
    ByteBuffer pagingState = null;

    do {
        BoundStatement bound = stmt.bind(userId)
            .setPageSize(10);                         // fetch 10 per page

        if (pagingState != null) {
            bound = bound.setPagingState(pagingState); // resume from cursor
        }

        ResultSet rs = session.execute(bound);

        // Process current page
        for (Row row : rs.currentPage()) {
            System.out.println(row.getString("title"));
        }

        // Get cursor for next page
        pagingState = rs.getExecutionInfo().getPagingState();

    } while (pagingState != null);
}
```

## 7.7 Spring Boot Integration

```java
// application.yml
spring:
  cassandra:
    contact-points: localhost:9042
    local-datacenter: us-east
    keyspace-name: ecommerce
    schema-action: CREATE_IF_NOT_EXISTS

// Entity
@Table("products")
public class Product {
    @PrimaryKeyColumn(name = "category_id", type = PARTITIONED)
    private UUID categoryId;

    @PrimaryKeyColumn(name = "created_at", type = CLUSTERED,
                      ordering = DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "product_id", type = CLUSTERED)
    private UUID productId;

    @Column("name")
    private String name;

    @Column("price")
    private BigDecimal price;
}

// Repository
@Repository
public interface ProductRepository
    extends ReactiveCassandraRepository<Product, UUID> {

    // Spring Data generates CQL automatically
    Flux<Product> findByCategoryId(UUID categoryId);

    Flux<Product> findByCategoryIdAndCreatedAtBetween(
        UUID categoryId, Instant from, Instant to);
}

// Service
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public Flux<Product> getRecentProducts(UUID categoryId) {
        return productRepository.findByCategoryId(categoryId)
            .take(20);  // Limit to 20
    }
}
```

---

## 🔁 Review — 7-Day Summary

### Core Mental Model

```
SQL Mental Model → Cassandra Mental Model

Entity → Query
Normalize → Denormalize
1 table for all queries → 1 table per query pattern
Joins at read time → Pre-join at write time
Schema then query → Query then schema
Rows are independent → Rows in a partition co-located
```

### Partition Key Decision Framework

```
Ask yourself:
1. What is my most frequent query? → That's your partition key
2. What columns do I filter by in WHERE? → Those must be in PRIMARY KEY
3. What columns do I sort by? → Those are clustering keys
4. Am I going to do range queries? → Clustering key, with right ORDER
5. Will this partition get too large? → Add bucketing (e.g., by month)
```

### Consistency Level Quick Reference

| Use Case | Write CL | Read CL | Why |
|---|---|---|---|
| High throughput, OK with eventual | ONE | ONE | Maximum performance |
| Strong consistency (financial) | QUORUM | QUORUM | R+W > RF |
| Multi-DC, read local | LOCAL_QUORUM | LOCAL_QUORUM | Low latency per DC |
| Logging / analytics | ANY | ONE | Write durability relaxed |

### Common Anti-Patterns to Avoid

| Anti-Pattern | Problem | Fix |
|---|---|---|
| `SELECT * FROM table` (no partition key) | Full cluster scan | Always filter by partition key |
| Huge partitions (millions of rows) | Hot nodes, OOM | Add time bucketing to partition key |
| `date` as partition key | All today's writes → 1 node | Use `(user_id, date)` instead |
| Batch for performance | Routes all to 1 coordinator | Use async executeAsync() |
| Secondary index on high cardinality | Scatter-gather across all nodes | Denormalize into new table |
| Unbounded `IN` clause (IN 1000+ items) | Scatter-gather | Use async parallel queries |
| Too many tombstones | Slow reads | Use TTL instead of explicit deletes |

---

## 📚 Resources & Next Steps

### Official Resources
- [Apache Cassandra Documentation](https://cassandra.apache.org/doc/latest/)
- [DataStax Java Driver Docs](https://docs.datastax.com/en/developer/java-driver/4.17/)
- [Cassandra Data Modeling](https://cassandra.apache.org/doc/latest/cassandra/data_modeling/)

### Tools to Learn
- **cqlsh** — Cassandra shell (like `mysql` CLI)
- **DataStax Studio** — GUI for data modeling
- **Reaper** — Automated repair scheduling
- **Cassandra Exporter + Grafana** — Metrics dashboards
- **NoSQLBench** — Load testing for Cassandra

### After This Plan
- **Day 8:** Practice schema design for 3 real systems (Messaging, Time-series, E-commerce)
- **Day 9:** Set up a local 3-node cluster with Docker
- **Day 10:** Write a complete Spring Boot app with Cassandra

```yaml
# Quick Docker setup for local 3-node cluster
# docker-compose.yml
version: '3'
services:
  cassandra-1:
    image: cassandra:4.1
    environment:
      CASSANDRA_CLUSTER_NAME: test-cluster
      CASSANDRA_DC: datacenter1
  cassandra-2:
    image: cassandra:4.1
    environment:
      CASSANDRA_CLUSTER_NAME: test-cluster
      CASSANDRA_DC: datacenter1
      CASSANDRA_SEEDS: cassandra-1
  cassandra-3:
    image: cassandra:4.1
    environment:
      CASSANDRA_CLUSTER_NAME: test-cluster
      CASSANDRA_DC: datacenter1
      CASSANDRA_SEEDS: cassandra-1
```

---

*Prepared for SDE 3 level engineers. Focus: data modeling, distributed architecture, production operations, Java driver usage.*
