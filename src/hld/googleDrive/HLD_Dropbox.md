# High-Level Design: Dropbox (SDE 3 Interview)

> **Companion document**: For a complete Google Drive architecture, see `HLD_GoogleDrive.md` in the same directory.
> This document covers **Dropbox-specific** design patterns and the key differentiators from Google Drive.
> In an interview, you can design *either* system — the fundamentals overlap ~70%; the key differences are called out explicitly.

---

## 0. Interview Navigation Guide

**Dropbox vs Google Drive — What Interviewers Look For:**

| Dimension | Dropbox Focus | Google Drive Focus |
|---|---|---|
| Core innovation | Block-level sync (delta sync) | Real-time collaboration (Docs/Sheets) |
| Storage model | Content-addressable block store | Object store + revision pointers |
| Sync protocol | Block diff → upload only changed blocks | File-level change detection |
| Desktop client | Native OS sync agent (core UX) | Browser-first; sync agent secondary |
| Deduplication | Global block-level dedup | File-level content hash dedup |
| Collaboration | Dropbox Paper, comments | Google Docs (OT/CRDT real-time) |

**45-Minute Time Budget:**
| Time | Topic |
|---|---|
| 0–5 min | Clarify scope and requirements |
| 5–10 min | Capacity estimation |
| 10–15 min | API design |
| 15–25 min | High-level architecture + component roles |
| 25–35 min | Deep dive: block-level sync OR deduplication OR metadata DB |
| 35–42 min | Reliability, CAP trade-offs, caching, security |
| 42–45 min | Trade-offs, extensions, open questions |

---

## 1. Problem Statement and Scope

Design a cloud file synchronization and storage service (à la Dropbox) that:
- Syncs files across multiple devices in near-real-time.
- Stores files in the cloud with version history.
- Enables file sharing and collaboration.
- Operates efficiently on slow/intermittent networks with large files.
- Provides high durability and availability globally.

### 1.1 In Scope
- File upload, download, delete, restore.
- **Block-level sync** (Dropbox's most distinctive feature).
- Desktop sync client protocol.
- File sharing and permissions.
- Version history and file recovery.
- Deduplication pipeline.
- LAN sync between devices on the same network.
- Notifications and activity feed.

### 1.2 Out of Scope
- Real-time collaborative editing engine internals (Dropbox Paper's CRDT layer).
- Billing and subscription management.
- Admin console and enterprise DLP internals.
- Native client UI implementation details.

---

## 2. Requirements

### 2.1 Functional Requirements
1. User uploads a file → available on all linked devices within seconds.
2. File modifications → only the changed **blocks** (not the whole file) are re-synced.
3. User can restore any previous version of a file (180 days for paid plans).
4. User can share files and folders with configurable permissions (view / edit / comment).
5. Files deleted on one device are removed on all devices but recoverable from trash.
6. Two devices modifying the same file concurrently → conflict copy created automatically.
7. Devices on the same LAN can sync files peer-to-peer (LAN sync) without hitting the cloud.
8. Selective sync: device can choose specific folders to keep locally.
9. Large file support: handle files up to 2 TB per file.
10. Offline support: edits made offline are synced when connectivity is restored.

### 2.2 Non-Functional Requirements
- **Availability**: 99.99% for the core sync and download paths.
- **Durability**: 99.999999999% (11 nines) for stored data.
- **Latency**:
  - Small file sync (< 1 MB): end-to-end < 5 seconds on a 10 Mbps link.
  - Metadata API P99 < 100 ms.
  - Block upload P99 < 500 ms per 4 MB block on good connectivity.
- **Consistency**:
  - Strong read-after-write for the uploading user's own devices.
  - Eventual consistency across other devices (seconds to propagate).
- **Scalability**: Hundreds of millions of users; hundreds of petabytes to exabytes of data.
- **Bandwidth efficiency**: Minimize bytes transmitted; dedup + delta sync are first-class concerns.

---

## 3. Capacity Estimation

### Assumptions
| Parameter | Value |
|---|---|
| Total users | 700 million registered |
| DAU | 100 million |
| Average storage per user (used) | 5 GB |
| Total logical storage | 700M × 5 GB = 3.5 EB |
| With 3× replication + erasure coding overhead | ~10–12 EB |
| Block size | 4 MB (default) |
| Average deduplication ratio | 30% blocks are shared |
| Effective unique storage | ~7 EB (after dedup) |

### 3.1 Upload Throughput
- Daily uploads: 100M DAU × 3 file changes/day = 300M file events/day
- Avg changed file size: 2 MB (mostly documents/photos; large videos are rarer)
- Daily upload bytes: 300M × 2 MB = 600 TB/day
- Average: 600 TB / 86,400 s ≈ **7 GB/s** ingest (raw)
- Block-level dedup savings: ~30-50% → effective origin ingest ≈ **3–5 GB/s**
- Peak (3×): ~15 GB/s → ~120 Gbps

### 3.2 Metadata QPS
- List/stat reads: 100M users × 20/day / 86,400 ≈ **23K reads/sec** (avg), ×3 peak = 70K
- Writes (file changes): 100M × 3 / 86,400 ≈ **3,500/sec** avg, ×3 peak = 10.5K

### 3.3 File Counts
- Average files per user: 10,000
- Total files: 700M × 10,000 = **7 trillion** file records
- With versions (avg 5 versions kept): ~35 trillion revision records

---

## 4. API Design

### 4.1 File Operations

```http
# Initiate upload (get upload session + pre-signed block URLs)
POST /2/files/upload_session/start
{
  "close": false
}
→ { "session_id": "abc123" }

# Upload block
POST /2/files/upload_session/append_v2
Dropbox-API-Arg: { "cursor": { "session_id": "abc123", "offset": 0 } }
Content-Type: application/octet-stream
[binary data — exactly 4 MB block]

# Close/commit upload session
POST /2/files/upload_session/finish
Dropbox-API-Arg: {
  "cursor": { "session_id": "abc123", "offset": 4194304 },
  "commit": { "path": "/Documents/report.pdf", "mode": "overwrite" }
}
→ FileMetadata { id, name, path_lower, size, content_hash, ... }

# Download a file
GET /2/files/download
Dropbox-API-Arg: { "path": "/Documents/report.pdf" }

# Delete
POST /2/files/delete_v2
{ "path": "/Documents/report.pdf" }

# List folder
POST /2/files/list_folder
{ "path": "/Documents", "recursive": false, "limit": 200 }
→ { "entries": [...], "cursor": "...", "has_more": false }
```

### 4.2 Delta Sync (Core Innovation)

```http
# Get initial cursor for sync client
POST /2/files/list_folder/get_latest_cursor
{ "path": "", "recursive": true }
→ { "cursor": "AAHk7..." }

# Poll for changes since cursor (long-poll, blocks up to 90s)
POST /2/files/list_folder/longpoll
{ "cursor": "AAHk7...", "timeout": 30 }
→ { "changes": true, "backoff": 0 }

# Fetch the actual changes
POST /2/files/list_folder/continue
{ "cursor": "AAHk7..." }
→ { "entries": [changed file metadata...], "cursor": "BBi8...", "has_more": false }
```

### 4.3 Block Upload API (Internal — for advanced deep dive)

```http
# Query which blocks the server already has (dedup check)
POST /internal/blocks/query
{ "block_hashes": ["sha256_a", "sha256_b", "sha256_c", ...] }
→ { "needed": ["sha256_b"] }

# Upload only missing blocks
PUT /internal/blocks/{sha256_hash}
[4 MB binary data]
→ 200 OK

# Commit file: reference blocks by hash
POST /internal/files/commit
{
  "path": "/Documents/report.pdf",
  "block_list": ["sha256_a", "sha256_b", "sha256_c"],
  "size": 12582912,
  "mtime": 1704067200
}
```

> **SDE3 Talking Point**: This two-phase approach (query → upload missing blocks → commit) is what gives Dropbox superior bandwidth efficiency. If you modify 1 line in a 100 MB file, only the 4 MB block containing that line is re-uploaded.

### 4.4 Sharing

```http
# Share a file or folder
POST /2/sharing/share_folder
{ "path": "/Documents", "acl_update_policy": "editors" }
→ { "shared_folder_id": "...", ... }

# Add a member to a shared folder
POST /2/sharing/add_folder_member
{
  "shared_folder_id": "...",
  "members": [{ "member": { ".tag": "email", "email": "bob@example.com" }, "access_level": "viewer" }]
}

# Create a shared link (public URL)
POST /2/sharing/create_shared_link_with_settings
{
  "path": "/Reports/Q4.pdf",
  "settings": { "requested_visibility": "public" }
}
→ { "url": "https://www.dropbox.com/s/xyz/Q4.pdf?dl=0", ...}
```

---

## 5. Data Model

### 5.1 File Namespace and Metadata

```sql
-- File/folder metadata (per-user or per-namespace)
CREATE TABLE file_journal (
  namespace_id  BIGINT       NOT NULL,   -- user's root is namespace_id = user_id
  server_path   VARCHAR(4096) NOT NULL,  -- canonical lowercase path
  file_id       VARCHAR(64)  NOT NULL,  -- globally unique, stable across renames
  is_deleted    BOOLEAN      DEFAULT FALSE,
  is_dir        BOOLEAN      DEFAULT FALSE,
  size_bytes    BIGINT,
  mtime         TIMESTAMP,
  blocklist_id  VARCHAR(64),             -- FK to block_manifests
  content_hash  CHAR(64),               -- SHA-256 of file content
  revision      BIGINT       NOT NULL,  -- monotonically increasing per file
  server_rev    BIGINT       NOT NULL,  -- global journal cursor at time of write
  PRIMARY KEY (namespace_id, server_path),
  INDEX (file_id),
  INDEX (namespace_id, server_rev)       -- enables delta scan since cursor
);
```

> **Why `server_rev` index?** Sync clients use a cursor = last `server_rev` they processed. A single range scan `WHERE namespace_id = ? AND server_rev > cursor ORDER BY server_rev` returns all new changes efficiently — O(changes) not O(total files).

### 5.2 Block Manifest (the heart of block-level sync)

```sql
-- Maps a file revision to its ordered block list
CREATE TABLE block_manifests (
  blocklist_id  VARCHAR(64)  PRIMARY KEY,   -- hash of the ordered block hash list
  block_count   INT          NOT NULL,
  total_bytes   BIGINT,
  created_at    TIMESTAMP
);

CREATE TABLE manifest_blocks (
  blocklist_id  VARCHAR(64)  NOT NULL,
  block_index   INT          NOT NULL,       -- position in file
  block_hash    CHAR(64)     NOT NULL,       -- SHA-256 of block content
  block_size    INT          NOT NULL,       -- actual size (last block may be < 4 MB)
  PRIMARY KEY (blocklist_id, block_index),
  INDEX (block_hash)                          -- reverse lookup: which files use this block
);
```

### 5.3 Block Store

```sql
-- Actual block catalog (the deduplication layer)
CREATE TABLE blocks (
  block_hash    CHAR(64)     PRIMARY KEY,    -- SHA-256 content hash
  size_bytes    INT          NOT NULL,
  storage_key   VARCHAR(512) NOT NULL,       -- S3/GCS object key
  ref_count     INT          NOT NULL DEFAULT 1,   -- number of manifests referencing this block
  created_at    TIMESTAMP
);
```

> **Dedup math**: If 1M users upload the same 4 MB block (e.g., a common PDF page), `blocks` has 1 row. `ref_count = 1M`. Only 4 MB stored. The `ref_count` enables safe GC (delete block only when ref_count reaches 0).

### 5.4 Shared Folder ACL

```sql
CREATE TABLE shared_folders (
  shared_folder_id  VARCHAR(64) PRIMARY KEY,
  owner_namespace   BIGINT     NOT NULL,
  policy_acl        ENUM('owner_only','editors_only','anyone') DEFAULT 'owner_only',
  link_policy       ENUM('private','team','public') DEFAULT 'private',
  created_at        TIMESTAMP
);

CREATE TABLE folder_members (
  shared_folder_id  VARCHAR(64) NOT NULL,
  user_id           BIGINT     NOT NULL,
  access_level      ENUM('owner','editor','viewer') NOT NULL,
  PRIMARY KEY (shared_folder_id, user_id),
  INDEX (user_id)
);
```

### 5.5 Device Registry

```sql
-- Each sync client registered per user
CREATE TABLE devices (
  device_id       VARCHAR(64) PRIMARY KEY,
  user_id         BIGINT     NOT NULL,
  platform        ENUM('windows','macos','linux','ios','android','web') NOT NULL,
  last_cursor     BIGINT,                     -- last server_rev this device processed
  last_seen       TIMESTAMP,
  INDEX (user_id)
);
```

---

## 6. High-Level Architecture

```
+------------------------------- Clients --------------------------------+
|  Desktop Agent (Win/Mac/Linux)  |  Mobile (iOS/Android)  |  Web App  |
+-----------------------------------+---------------------+--------------+
                                    |
                       [Global Load Balancer / Anycast DNS]
                       [CloudFront / Fastly CDN for downloads]
                                    |
              +---------------------+--------------------+
              |                     |                    |
              v                     v                    v
     +----------------+   +------------------+   +-----------+
     | Metadata API   |   | Block Upload     |   | Block     |
     | Service        |   | Service          |   | Download  |
     | (Delta, ACL,   |   | (Chunked,        |   | Service   |
     |  Namespace)    |   |  Dedup check)    |   | (Signed   |
     +-------+--------+   +--------+---------+   | URLs)     |
             |                     |             +-----------+
             v                     v
     +------------------+  +------------------+
     | Metadata Store   |  | Block Store      |
     | (MySQL / Vitess) |  | Catalog (MySQL)  |
     | + Redis cache    |  +--------+---------+
     +-------+----------+           |
             |                      v
             |              [Object Storage: S3 / GCS]
             |              (Immutable 4 MB blocks, content-addressed)
             |
     +-------+----------+   +-------------------+   +------------------+
     | Namespace/Journal|   | Notification Svc  |   | LAN Sync         |
     | Service (cursor) |   | (SSE / WebSocket) |   | Discovery Svc    |
     +------------------+   +-------------------+   +------------------+

             Kafka (Change Events) connects all services asynchronously
```

### 6.1 Control Plane vs Data Plane

| Plane | Components | Characteristics |
|---|---|---|
| **Control plane** | Metadata API, Namespace Service, ACL Service | Strongly consistent, lower throughput, Spanner/Vitess |
| **Data plane** | Block Upload, Block Download, Object Store, CDN | High throughput, horizontally scalable, eventually consistent |

> **Interview insight**: The control plane and data plane are independently scaled. Rename/move operations are purely control-plane (no bytes moved — only metadata updated). This is a massive efficiency gain over older FTP-style systems.

---

## 7. Deep Dive: Block-Level Sync (Dropbox's Core Innovation)

This is the most differentiating aspect of Dropbox. Be ready to go very deep here.

### 7.1 How Files Are Broken Into Blocks

Dropbox uses **content-defined chunking** (not fixed-size) for optimal deduplication:

```
Fixed-size chunking (naive):
File: [A][B][C][D][E][F]   (each = 4 MB)
                Insert 1 byte at start
     [X][A][B][C][D][E][F]
Blocks shifted → ALL blocks differ from original → no dedup

Content-defined chunking (Rabin fingerprint / rolling hash):
Finds natural split points in the content stream.
Insert 1 byte at start → only the first boundary block changes.
Remaining blocks identical → stored once, referenced again.
```

**Algorithm (Rabin fingerprint / CDC):**
```
1. Slide a rolling hash window (e.g., 64 bytes) over file bytes.
2. When hash modulo P == target: this is a chunk boundary.
3. Target chunk size: 4 MB avg (min 1 MB, max 8 MB to bound variance).
4. SHA-256 each chunk → block_hash.
5. Upload session = query server for which block_hashes are needed → upload only those.
6. Commit: send ordered list of block_hashes → server reconstructs full file.
```

> **SDE3 key insight**: CDC means inserting a byte at the beginning of a 1 GB file only invalidates ~1-2 chunks (the early ones). All subsequent chunks remain identical and are stored by reference — near-zero bandwidth used.

### 7.2 Upload Flow (Step by Step)

```
Desktop Client                           Metadata API       Block Store
--------------                           ------------       -----------
1. File write detected (inotify/FSEvents/ReadDirectoryChanges)
2. Client hashes the file using CDC → block list [h1, h2, h3, h4, h5]
3. POST /internal/blocks/query { block_hashes: [h1..h5] }
                                        ← { needed: [h2, h4] }  (h1,h3,h5 already exist)
4. PUT /internal/blocks/h2 [4 MB data]
   PUT /internal/blocks/h4 [4 MB data]
                                                              ← 200 OK (stored in S3)
5. POST /internal/files/commit
   { path, block_list:[h1,h2,h3,h4,h5], size, mtime }
                                        ← { server_rev: 42, revision: 7 }
6. Server (Metadata API):
   a. Write block_manifests + manifest_blocks rows.
   b. Update file_journal (new server_rev, new blocklist_id).
   c. Emit change event to Kafka.
7. Other devices receive SSE/long-poll notification → fetch delta changes.
```

**Bandwidth saved**: If `h1, h3, h5` already exist (3 × 4 MB = 12 MB of 20 MB file), only 8 MB uploaded.

### 7.3 Download Flow

```
1. Client requests file: GET /2/files/download?path=...
2. Metadata API returns file metadata including blocklist_id.
3. Client (or server) looks up block_list from block_manifests.
4. For each block_hash:
   a. Generate presigned S3 URL (TTL: 5 min), bound to block_hash key.
   b. Client downloads block directly from S3/CDN.
5. Client reassembles blocks in order → original file.
```

**Optimization**: If multiple blocks of the same file are on CDN, they can be fetched in parallel (8+ concurrent block downloads → much faster than sequential single-stream).

### 7.4 Delta Sync — Detecting Changes

```
Client                      Server
------                      ------
┌──────────────────┐
│ File Watcher     │
│ (inotify/FSEvents│
│ /ReadDirChanges) │
└────────┬─────────┘
         │ file modified event
         ▼
┌──────────────────┐
│ Local DB (SQLite)│  ← tracks: path → (mtime, content_hash, server_rev)
│ of file states   │
└────────┬─────────┘
         │ compare mtime + content hash
         ▼
  changed? → compute CDC blocks → query server → upload delta
```

**Server-side cursor:**
- Every change to the namespace increments a global `server_rev` counter (per namespace).
- The sync client remembers the last `server_rev` it processed.
- On poll: `SELECT * FROM file_journal WHERE namespace_id = ? AND server_rev > ? ORDER BY server_rev LIMIT 500`
- Returns only the file entries that changed since the client's last sync point.
- Efficient even with millions of files: O(changes) not O(all files).

---

## 8. Deep Dive: Deduplication Pipeline

### 8.1 Global Block Deduplication

```
Two users uploading the same 4 GB movie:

User A's client:
  - Compute CDC block hashes: [h1, h2, h3, ..., h1024]
  - POST /blocks/query → server: "all needed"
  - Upload all 1024 blocks (~4 MB each)
  - Commit → blocks table: 1024 rows, ref_count=1 each

User B's client (same movie):
  - Compute CDC blocks: [h1, h2, h3, ..., h1024]  (identical movie → identical hashes)
  - POST /blocks/query → server: "none needed" (all exist!)
  - Upload: 0 bytes!! Just commit the blocklist.
  - Commit → manifest_blocks rows pointing to existing blocks, ref_count bumped to 2
  - Network bytes uploaded: ~200 bytes (just the commit payload)
```

**Storage savings**: With millions of users sharing common files (OS install media, popular PDFs, common template files), deduplication can achieve 30–40% storage reduction.

### 8.2 Dedup Hash Security (Convergent Encryption)

**Problem**: If attacker knows `block_hash`, they can "claim" to own a file without uploading it (hash proof-of-ownership attack).

**Solution — Convergent Encryption:**
```
block_data → AES-256-CBC(block_data, key=SHA-256(block_data)) → ciphertext
stored_block = ciphertext
block_hash = SHA-256(ciphertext)  ← server stores this
```

- Two users with identical plaintext → identical ciphertext (because key = hash of plaintext).
- Dedup still works (identical ciphertexts → same stored block).
- Server never has plaintext keys.
- Attacker who knows the hash can't reconstruct data without the original plaintext (key).

> **Trade-off**: Does not provide true zero-knowledge (server can deduce file content by probing hash). Full client-side encryption breaks server-side dedup. Dropbox's answer: convergent encryption is a reasonable middle ground.

### 8.3 Garbage Collection for Blocks

```
Trigger: file deleted OR new revision uploaded (old blocks may be orphaned)
GC Algorithm:
  1. Decrement ref_count on all blocks in old blocklist.
  2. Collect all blocks WHERE ref_count = 0 AND last_ref_removal > 7 days ago.
     (7-day grace period: trash recovery window)
  3. Delete from S3 + delete from blocks table.
  4. Run periodically (daily batch job).
```

**Why grace period?** User may trash a file and immediately restore it. Block GC should not trigger until permanent deletion is confirmed.

---

## 9. LAN Sync (Local Area Network Peer-to-Peer Sync)

A Dropbox-unique feature that reduces cloud bandwidth significantly for corporate environments.

### How It Works

```
Device A (modified file)         Device B (same network)
--------------------------       --------------------------
1. File change detected
2. Broadcast LAN announcement to local network:
   "I have new version of file_id=xyz, block_hashes=[h1,h2,h3]"
   (UDP multicast on local subnet, or mDNS)

                                  3. B is interested in file_id=xyz
                                  4. B checks which of [h1,h2,h3] it needs
                                  5. B requests missing blocks from A over TCP (direct LAN)
                                     Zero cloud bandwidth consumed!
                                  6. B assembles file, confirms with server:
                                     "I have file_id=xyz at revision R"
                                  7. Server marks B's cursor as up-to-date
```

**Benefits:**
- No cloud upload/download bandwidth for intra-office sync.
- Much faster: LAN speeds (1–10 Gbps) vs internet (10–1000 Mbps).
- Reduces Dropbox's CDN/origin bandwidth costs significantly.
- Critical for large media files in corporate environments.

**Security consideration**: LAN sync uses TLS peer-to-peer connections authenticated with short-lived tokens issued by the cloud server (prevents unauthorized LAN peers from intercepting data).

---

## 10. Conflict Resolution

### Conflict Detection

```
Scenario: User edits file on Laptop and Phone simultaneously (both offline).

Both devices locally record:
  base_revision = 5

Laptop: makes edits → offline upload queued → revision_6_laptop
Phone:  makes edits → offline upload queued → revision_6_phone

On reconnect:
  - Laptop uploads first → server accepts → server_revision = 6
  - Phone tries to upload → server detects:
      phone's base_revision (5) != current server revision (6)
      → CONFLICT
```

### Conflict Resolution Strategies

| Strategy | When Applied | Behavior |
|---|---|---|
| **Conflict copy** | Binary files (PDF, images, executables) | Both versions kept; rename loser to `"filename (phone's conflicted copy 2024-01-15).ext"` |
| **3-way merge** | Plain text / code files | Merge base + local + remote → combined; conflicts marked with `<<<<<<` markers |
| **Last Write Wins** | Not Dropbox's default — explicit opt-in only | Silent data loss risk |
| **Real-time OT/CRDT** | Dropbox Paper only | Collaborative editing; no conflicts by design |

**The "conflict copy" approach is Dropbox's default** — never silently loses data. Users see both versions and can manually reconcile.

---

## 11. Metadata Store Architecture

### 11.1 Technology Choice: MySQL + Vitess (or Spanner)

Dropbox historically used MySQL sharded with a custom framework. Key considerations:

| Aspect | MySQL + Vitess | Google Spanner |
|---|---|---|
| Consistency | Strong (per-shard ACID) | Global linearizable |
| Cross-shard tx | 2PC (expensive) | Native (TrueTime) |
| Operational | Complex sharding rules | Managed (but GCP lock-in) |
| Cost | Commodity hardware | Premium managed service |

**Dropbox's choice**: MySQL + sharding → later Vitess for automated horizontal scale. Avoid cross-shard transactions by design (each namespace is a shard unit).

### 11.2 Sharding Strategy

```
Primary shard key: namespace_id (= user_id for personal, shared_folder_id for teams)

Shard assignment: consistent hashing of namespace_id → shard bucket
  namespace_id → hash → shard_id → MySQL cluster

Why namespace_id as shard key?
  - All file operations for one user go to ONE shard → no cross-shard joins
  - Shared folders introduce cross-namespace complexity → handled by fanout
```

**Shared folder challenge:**
- Alice shares a folder with Bob and Carol.
- Alice's file changes must appear in Bob's and Carol's namespace views.
- **Solution**: Fanout service — on Alice's change event, write to Bob's and Carol's `file_journal` namespaces (possibly different shards) via async Kafka consumers.
- **Trade-off**: Eventual consistency for recipients (seconds delay); strong immediately for Alice.

### 11.3 Hot Namespace Problem

A user with millions of files and thousands of concurrent devices = hot shard.

Mitigations:
- **Read replicas**: Metadata reads served from replicas; writes to primary.
- **Redis cache**: Hot file metadata cached (TTL: 30s) in Redis cluster.
- **Rate limiting**: Per-user API rate limits prevent runaway sync clients from overwhelming a shard.
- **Namespace splitting**: Very large professional accounts get dedicated shards.

---

## 12. Notification and Real-Time Sync

### Architecture

```
File Change
    │
    ▼
Metadata Service → writes to file_journal (server_rev++)
    │
    ▼
Kafka topic: namespace_changes (partitioned by namespace_id)
    │
    ▼
Notification Fanout Service (consumer):
    │
    ├──→ SSE/Long-poll dispatcher (per device_id from devices table)
    │         Pushes: "namespace changed; new cursor available"
    │
    └──→ Mobile Push (APNs/FCM) for mobile devices in background
              Pushes: "Drive changed; sync when convenient"
```

### Push Mechanism Comparison

| Mechanism | Latency | Battery Impact | Reliability | Use Case |
|---|---|---|---|---|
| **SSE (Server-Sent Events)** | < 1s | Medium | High | Active desktop clients |
| **WebSocket** | < 1s | Medium | High | Web app, active editing |
| **Long-polling** | 1–30s | Low | High | Desktop background sync |
| **APNs/FCM push** | 1–60s | Very low | Medium (Apple/Google controlled) | Mobile background |

**Dropbox combines**: Long-polling for the desktop sync agent (lightweight, works through firewalls / NAT), SSE for web/mobile app when in foreground, mobile push for background.

---

## 13. Scalability and Reliability

### 13.1 Horizontal Scaling

| Component | Scaling Strategy |
|---|---|
| Metadata API | Stateless; add instances behind LB; shard reads to replicas |
| Block Upload Service | Stateless; add instances; S3 is the bottleneck (auto-scales) |
| Block Store (S3/GCS) | Managed object store; auto-scales storage and throughput |
| Redis cache | Redis Cluster with consistent hashing; add nodes dynamically |
| Kafka | Add partitions and consumers; partition by namespace_id |
| Long-poll servers | Stateful (hold open connections); horizontal scale + sticky routing |

### 13.2 Failure Mode Analysis

| Failure | Impact | Mitigation |
|---|---|---|
| Metadata DB shard outage | Writes blocked for affected users | Multi-AZ MySQL (automatic failover <30s); sync clients buffer locally |
| Block Upload Service crash | Upload in progress lost | Client retries entire upload session; block-level idempotency (S3 PUT is idempotent by hash) |
| S3 region outage | Block uploads fail | Multi-region S3 replication; writes routed to alternate region |
| Redis cache failure | Cache miss storm, increased DB load | Read-through fallback; auto-scale DB read replicas |
| Kafka consumer lag | Delayed notifications to devices | Auto-scale consumers; DLQ for poison messages; alert at 10K lag |
| Long-poll server crash | Clients reconnect; catch up via cursor | Clients reconnect automatically; cursor-based sync = no lost changes |
| LAN sync peer crash | Falls back to cloud download | Transparent fallback; cloud is always authoritative |

### 13.3 Multi-Region Active-Active

```
US-EAST (Primary writes)          EU-WEST (Secondary)
┌───────────────────────┐         ┌───────────────────────┐
│ API Gateway           │         │ API Gateway           │
│ Metadata Service      │◄───────►│ Metadata Service      │
│ MySQL (Primary)       │  async  │ MySQL (Replica)       │
│ Block Upload Service  │         │ Block Upload Service  │
│ S3 (multi-region)     │◄───────►│ S3 (multi-region)     │
│ Redis cluster         │         │ Redis cluster         │
│ CDN PoPs              │         │ CDN PoPs              │
└───────────────────────┘         └───────────────────────┘
             │                              │
         Kafka (cross-region replication)
```

- **Writes**: Routed to nearest region; cross-region replication async (seconds lag).
- **Reads**: Served from nearest region replica (metadata may be slightly stale — acceptable).
- **Blob downloads**: CDN is global; latency minimal regardless of region.
- **Failover**: Global load balancer health checks; automatic reroute in <60s.

---

## 14. Caching Strategy

### Cache Tiers

```
L1 — Desktop client (SQLite local DB):
  → File metadata (path, mtime, content_hash, block_list)
  → Critical for offline operation and avoiding repeated server round-trips
  → Invalidated by server_rev changes from delta feed

L2 — CDN edge:
  → Block blobs (immutable by hash → cache forever, no invalidation needed!)
  → Public shared link blobs
  → Thumbnails and previews
  → TTL: infinite (content-addressed = immutable; new content = new hash = new CDN key)

L3 — Redis cluster (server-side):
  → File metadata: {namespace_id, path} → metadata  TTL: 30s
  → ACL resolution: {user_id, folder_id} → permissions  TTL: 60s (security: short)
  → Dedup check: block_hash exists? → boolean  TTL: 1 hour
  → Cursor → server_rev: for hot namespaces, pre-fetched   TTL: 5s
```

> **Critical insight**: Block blobs are content-addressed (key = SHA-256 of content). This means they are **naturally immutable** and **never need cache invalidation** at CDN. A changed file = new block hashes = new CDN keys. This is a massive operational simplicity win.

### Cache Invalidation (Metadata)

- On file/folder rename/move: delete Redis key for old path; write new path.
- On permission change: immediately evict ACL cache entries for the folder (security-critical; don't wait for TTL).
- On quota update: write-through to Redis.

---

## 15. Security

### 15.1 Encryption

| Layer | Approach |
|---|---|
| In transit | TLS 1.3 (client→API, API→services, LAN sync peer TLS) |
| At rest (blocks) | AES-256 (server-managed keys or customer-managed via KMS) |
| Convergent encryption | Block key = SHA-256(plaintext block) → enables dedup while encrypting |
| SSKS (server-side key storage) | Default; Dropbox holds keys |
| Dropbox Business Advanced | Customer-managed keys; some dedup breaks |

### 15.2 Authentication and Authorization

```
OAuth 2.0:
  - Authorization Code flow for desktop/mobile apps
  - Device Authorization flow for headless clients
  - JWT access tokens (short-lived, 1h) + Refresh tokens (long-lived)

ACL evaluation:
  1. Check if caller is file owner.
  2. Check explicit folder_members row.
  3. Check shared link policy (anyone/team/private).
  4. Deny by default.
```

### 15.3 Signed Block URLs

- Block downloads issued as pre-signed S3 URLs (TTL: 10 minutes).
- URL includes `block_hash`, `user_id`, `expiry_timestamp`, `HMAC signature`.
- Even if URL leaks, it expires and is bound to a specific block (not the whole file).

### 15.4 Audit and Compliance

- Every API call logged with: user_id, device_id, action, file_path, IP, timestamp.
- Admin-visible audit log for Dropbox Business accounts.
- GDPR: data deletion within 30 days of account closure; data residency options for EU.
- SOC 2 Type II, ISO 27001 compliance.

---

## 16. Trade-Off Analysis

### 16.1 Content-Defined Chunking vs Fixed-Size Chunking

| | Fixed-size | Content-Defined (CDC) |
|---|---|---|
| Dedup effectiveness | Low (insertions shift all blocks) | High (only boundary blocks change) |
| Implementation | Simple | Complex (rolling hash) |
| Block size variance | None | ±2x around target size |
| CPU overhead | Minimal | Moderate (rolling hash computation) |
| **Decision** | | **CDC wins**: superior dedup ratio justifies complexity |

### 16.2 MySQL + Vitess vs Spanner for Metadata

| | MySQL + Vitess | Spanner |
|---|---|---|
| Cross-shard transactions | Complex (2PC) | Native |
| Operational complexity | High (shard keys, migrations) | Low (managed) |
| Cloud portability | Multi-cloud / on-prem | GCP lock-in |
| Cost | Lower (commodity) | Higher (premium) |
| **Decision** | **Dropbox chose MySQL + Vitess** | **Google Drive chose Spanner** |

### 16.3 Global Dedup vs Per-User Block Storage

| | Global dedup | Per-user blocks |
|---|---|---|
| Storage savings | High (30–50%) | None |
| Security | Convergent encryption (complex) | Simple per-user AES keys |
| Hash proof-of-ownership attack | Risk exists | No risk |
| **Decision** | **Global dedup with convergent encryption** | (trade-off accepted) |

### 16.4 LAN Sync vs Cloud-Only

| | LAN Sync | Cloud-Only |
|---|---|---|
| Speed | 1–10 Gbps (LAN) | 10–1000 Mbps (internet) |
| Bandwidth cost | $0 for intra-office | Charged to Dropbox |
| Complexity | High (peer discovery, auth, fallback) | None |
| **Decision** | **LAN sync enabled for desktop** | (corporate environments benefit greatly) |

### 16.5 Strong vs Eventual Consistency for Cross-Device Sync

| | Strong | Eventual |
|---|---|---|
| User experience | Instant cross-device visibility | Seconds of lag |
| Implementation | Expensive (Spanner/Paxos) | Cheap (async replication) |
| Dropbox choice | **Eventual** (seconds lag acceptable) | (strong only for uploader's own view) |

---

## 17. Dropbox vs Google Drive — Key Differentiators Summary

| Feature | Dropbox | Google Drive |
|---|---|---|
| **Core strength** | Block-level sync, desktop client | Cloud Docs, collaboration |
| **Sync protocol** | CDC blocks, delta sync | File-level diff, change feed cursor |
| **Deduplication** | Global block-level (CDC + convergent enc) | File-level content hash |
| **Offline first** | Native desktop agent, SQLite local DB | Browser-first; offline secondary |
| **Collaboration** | Dropbox Paper (separate product) | Google Docs/Sheets (native) |
| **Metadata DB** | MySQL + Vitess | Google Cloud Spanner |
| **LAN sync** | Yes (P2P block exchange) | No |
| **Block storage** | Custom block store on S3 | Google Cloud Storage (GCS) |
| **Conflict resolution** | Conflict copy (default) | Conflict copy (default) |
| **API style** | REST with cursor-based delta | REST with page token delta |

---

## 18. Interview-Ready Talking Points

**"How does Dropbox avoid uploading the same 100 MB file twice?"**
> Block-level deduplication: file split into 4 MB CDC blocks, each hashed with SHA-256. Before upload, client queries server with block hashes. Server returns only the hashes it doesn't have. Client uploads only missing blocks. A file already seen by any other user = 0 bytes uploaded at commit time (just a commit payload with block_list).

**"How does Dropbox sync efficiently when you change one line in a 1 GB file?"**
> Content-defined chunking (CDC) with rolling hash. Only the 4 MB block(s) containing the changed line have a new hash. Client queries → uploads only those 1–2 blocks (~4-8 MB out of 1 GB) → commit. 99.2% bandwidth saving on this operation.

**"What happens if two devices edit the same file offline?"**
> Conflict detected at commit time: client's base_revision != current server revision. System creates a conflict copy with timestamp in the filename. No data lost. User manually reconciles. For text files, three-way merge with base ancestor is attempted.

**"How does LAN sync work and is it secure?"**
> Devices broadcast mDNS announcements on local subnet advertising file change events with block hashes. Interested peers request blocks directly over TCP with TLS, authenticated by short-lived tokens issued by the cloud server. Zero cloud bytes for intra-office sync.

**"What breaks first at Dropbox's scale?"**
> 1. Hot namespace shard (power user with millions of files + thousands of devices) → dedicated shard + read replicas.
> 2. Block store hot key (extremely popular block referenced by millions of users) → CDN absorbs; object store auto-scales.
> 3. Long-poll server on device reconnect storm (office power outage → all devices reconnect simultaneously) → exponential backoff + jitter.
> 4. Kafka consumer lag on viral share events → auto-scale consumers + alert.

**"Why block-level dedup rather than file-level?"**
> File-level dedup works only for identical files. Block-level works for files that share common sections — same intro paragraph in different documents, same video frames, same binary library linked into different executables. Block-level achieves 10–50x better dedup ratio in practice.

---

## 19. Extensions to Mention If Time Allows

- **Dropbox Paper**: Collaborative markdown documents using CRDT (similar to Google Docs OT). Merges concurrent edits without conflicts.
- **Dropbox Business Teams**: Shared team spaces with admin controls, centralized billing, data residency.
- **Smart Sync**: Files visible in file system (metadata synced) but content downloaded on-demand (not pre-cached). Solves disk space problem for users with terabytes of cloud data.
- **Rewind feature**: Roll back the entire Dropbox (not just one file) to any point in 180 days — requires event sourcing of all change events.
- **Dropbox Transfer**: Large file delivery (like WeTransfer) — separate product built on the same block store.
- **Photo management (Carousel)**: Timeline of photos across devices; backed by same block store with thumbnail generation pipeline.
- **Dropbox API for Developers**: OAuth2, webhooks for change notifications, Paper API for document management.
- **Selective sync + Smart Sync**: Desktop can choose to not sync certain folders locally (just keep metadata). Smart Sync extends this to on-demand retrieval.
