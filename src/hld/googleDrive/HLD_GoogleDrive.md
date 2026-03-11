# High-Level Design: Google Drive (SDE3 Interview)

## 0. How To Use This Document In Interview
This write-up is intentionally deep. In a real SDE3 interview, present in this order:
1. Clarify scope and constraints (functional + non-functional).
2. Show API surface and core data model.
3. Draw architecture and explain each component's role.
4. Walk through critical flows end-to-end (upload, download, share, sync).
5. Cover scalability numbers, consistency trade-offs, reliability, and security.

If the interviewer interrupts for deep-dive, jump directly to the relevant section.

---

## 1. Problem Statement And Scope

Design a cloud file storage and collaboration platform similar to Google Drive that supports:
- File upload, download, and deletion.
- Folder hierarchy and file organization.
- File sharing (view, comment, edit) with access control.
- Real-time collaboration on documents (Google Docs integration).
- File versioning and recovery (trash).
- Cross-device synchronization (desktop sync client, mobile, web).
- Full-text search across file names and document content.
- Notification system for shared file activity.

### 1.1 Explicitly In Scope
- Core backend architecture for file storage and metadata management.
- Upload/download pipeline with chunking and resumable uploads.
- Sync protocol and conflict resolution.
- Access control and sharing model.
- Versioning and file recovery.
- Search and indexing.
- Reliability, observability, and security.

### 1.2 Out Of Scope (For This HLD)
- Full collaborative editing engine internals (Operational Transformation / CRDT algorithms at code depth).
- Payment and storage quota billing internals.
- Native client UI and platform-specific sync agents.
- Deep anti-malware scanning pipeline internals.

---

## 2. Requirements

### 2.1 Functional Requirements
1. User can upload files of arbitrary size (up to 5 TB for a single file).
2. User can download a file or a specific version of a file.
3. User can organize files in a folder hierarchy.
4. User can share files/folders with other users with granular permissions (Viewer, Commenter, Editor, Owner).
5. User can create public share links with configurable access levels.
6. System tracks version history; user can restore any past version.
7. User can move files to trash and restore or permanently delete.
8. Desktop sync client monitors local folder and syncs changes bidirectionally.
9. User can search files by name, type, content, and owner.
10. Real-time notifications for share invites, comments, and edits.
11. Quota management: each user has a storage quota; uploads are rejected when quota is exhausted.

### 2.2 Non-Functional Requirements
- **Availability**: 99.99% for core upload/download and metadata APIs.
- **Durability**: 99.999999999% (11 nines) for stored file data.
- **Latency**:
  - Metadata APIs (list, stat): P99 < 100 ms.
  - Small file download (< 1 MB): TTFB P99 < 200 ms.
  - Search: P99 < 500 ms.
- **Throughput**: Support millions of concurrent file operations globally.
- **Consistency**:
  - Strong consistency for metadata writes (rename, move, share changes).
  - Strong read-after-write for the uploading user.
  - Eventual consistency acceptable for cross-region replica reads.
- **Security**: End-to-end encryption at rest and in transit; signed URLs for object access; fine-grained authorization enforcement.
- **Scalability**: Designed for hundreds of millions of users and petabytes to exabytes of total stored data.

---

## 3. Back-Of-The-Envelope Capacity Planning

### Assumptions (Interview-Friendly, Tunable)
| Parameter | Value |
|---|---|
| Total registered users | 2 billion |
| DAU | 500 million |
| Avg files per user | 2,000 |
| Avg file size | 1.5 MB |
| Daily new uploads per DAU | 5 |
| Avg upload size | 3 MB |
| Peak traffic multiplier | 3x |
| Storage per user quota | 15 GB (free tier) |

### 3.1 Storage Estimate
- Files in system: 2B * 2,000 = 4 trillion file records.
- Raw data: 2B users * 10 GB avg used = 20 exabytes raw storage.
- With 3x replication + metadata overhead: ~65 EB total cluster storage.
- Metadata (file records, ACL rows): manageable in the low PB range.

### 3.2 Upload Throughput
- Daily uploads: 500M DAU * 5 files = 2.5B uploads/day.
- Avg daily upload bytes: 2.5B * 3 MB = 7.5 PB/day ~ 87 GB/s average.
- Peak upload bandwidth: 87 GB/s * 3x ~ 260 GB/s ~ 2 Tbps (origin ingest).
- Content can be deduplicated and cached at CDN to reduce net origin ingress.

### 3.3 Metadata QPS
- List/stat API: ~500M users * 10 reads/day / 86400 s ~ 60K reads/sec average, 180K peak.
- Write ops (create, rename, share): ~500M * 5 / 86400 ~ 29K writes/sec peak ~87K.

### 3.4 Chunk-Level Deduplication Benefit
- If 30% of uploaded bytes are duplicates (common documents, OS files):
  - Net unique storage reduction: ~6 EB from 20 EB.

---

## 4. High-Level API Design

### 4.1 File And Folder CRUD

```http
# Create folder
POST /v3/files
{
  "name": "Projects",
  "mimeType": "application/vnd.google-apps.folder",
  "parents": ["root"]
}

# Upload file metadata + initiate resumable upload
POST /upload/drive/v3/files?uploadType=resumable
{
  "name": "design.pdf",
  "parents": ["folderId123"],
  "mimeType": "application/pdf"
}
# Response contains: Location header with upload session URI

# List files in a folder
GET /v3/files?q="'folderId123' in parents and trashed=false"
              &fields=files(id,name,mimeType,size,modifiedTime,owners)
              &pageSize=100&pageToken=...

# Get file metadata
GET /v3/files/{fileId}?fields=id,name,size,md5Checksum,parents,permissions

# Download file content
GET /v3/files/{fileId}?alt=media

# Delete (trash)
DELETE /v3/files/{fileId}
```

### 4.2 Resumable Upload Protocol (Critical Detail)

```http
# Step 1: Initiate — returns upload session URI
POST /upload/drive/v3/files?uploadType=resumable
X-Upload-Content-Type: application/pdf
X-Upload-Content-Length: 104857600
→ Location: https://upload.googleapis.com/upload/drive/v3/files?upload_id=xyz

# Step 2: Upload chunk (e.g., 8 MB chunks)
PUT https://...?upload_id=xyz
Content-Range: bytes 0-8388607/104857600
[binary chunk data]
→ 308 Resume Incomplete (with Range: bytes=0-8388607)

# Step 3: Resume after interruption
PUT https://...?upload_id=xyz
Content-Range: bytes */104857600
→ 308 with last received byte range

# Repeat until 200 OK with file resource returned
```

**Why resumable uploads matter**: For large files, restartable uploads prevent wasting bandwidth on retries from scratch. This is a key SDE3 talking point.

### 4.3 Sharing And Permissions

```http
# Share file with a user
POST /v3/files/{fileId}/permissions
{
  "type": "user",
  "role": "writer",
  "emailAddress": "colleague@example.com"
}

# Create public share link
POST /v3/files/{fileId}/permissions
{
  "type": "anyone",
  "role": "reader",
  "allowFileDiscovery": false
}

# List permissions
GET /v3/files/{fileId}/permissions

# Revoke access
DELETE /v3/files/{fileId}/permissions/{permissionId}
```

Roles hierarchy: `owner > organizer > fileOrganizer > writer > commenter > reader`

### 4.4 File Versioning

```http
# List revisions
GET /v3/files/{fileId}/revisions

# Download specific revision
GET /v3/files/{fileId}/revisions/{revisionId}?alt=media

# Pin a revision to prevent auto-deletion
PATCH /v3/files/{fileId}/revisions/{revisionId}
{
  "keepForever": true
}
```

### 4.5 Search

```http
GET /v3/files?q="fullText contains 'budget report' and mimeType='application/pdf'"
              &q="modifiedTime > '2024-01-01T00:00:00'"
              &orderBy=modifiedTime desc
```

### 4.6 Changes And Sync Feed

```http
# Get start/change page token
GET /v3/changes/startPageToken

# Poll for changes since last known token
GET /v3/changes?pageToken={token}&spaces=drive&includeRemoved=true
→ Returns list of changed files + nextPageToken + newStartPageToken
```

This is the backbone of desktop sync clients.

---

## 5. Data Model (Core Entities)

### 5.1 File Metadata Store

```sql
-- Central metadata (stored in distributed relational DB, e.g., Spanner)
CREATE TABLE files (
  file_id         VARCHAR(64)  PRIMARY KEY,   -- globally unique, random ID
  owner_id        VARCHAR(64)  NOT NULL,
  name            VARCHAR(1024) NOT NULL,
  mime_type       VARCHAR(256),
  size_bytes      BIGINT,
  trashed         BOOLEAN      DEFAULT FALSE,
  trashed_at      TIMESTAMP,
  created_at      TIMESTAMP    NOT NULL,
  modified_at     TIMESTAMP    NOT NULL,       -- updated on every write
  version         BIGINT       NOT NULL DEFAULT 1,
  md5_checksum    CHAR(32),
  sha256_checksum CHAR(64),
  head_revision_id VARCHAR(64)
);

-- Parent-child hierarchy (supports multiple parents per file / shortcuts)
CREATE TABLE file_tree (
  file_id         VARCHAR(64) NOT NULL,
  parent_id       VARCHAR(64) NOT NULL,        -- parent folder's file_id
  PRIMARY KEY (file_id, parent_id),
  INDEX (parent_id)                             -- allows listing children efficiently
);
```

**Why separate `file_tree`?**  
Google Drive allows a file to exist in multiple folders (shortcuts) and makes rename/move operations O(1) updates to the tree table without touching the file content.

### 5.2 File Revisions

```sql
CREATE TABLE revisions (
  revision_id     VARCHAR(64)  PRIMARY KEY,
  file_id         VARCHAR(64)  NOT NULL,
  version_number  BIGINT       NOT NULL,
  created_at      TIMESTAMP    NOT NULL,
  created_by      VARCHAR(64),
  size_bytes      BIGINT,
  md5_checksum    CHAR(32),
  storage_key     VARCHAR(512) NOT NULL,        -- key in object store (blob ref)
  keep_forever    BOOLEAN      DEFAULT FALSE,
  INDEX (file_id, version_number)
);
```

Auto-deletion policy: Keep ~100 revisions or 30 days, whichever is less (unless `keepForever=true`).

### 5.3 Access Control And Permissions

```sql
-- Every permission entry represents one ACL record
CREATE TABLE permissions (
  permission_id   VARCHAR(64) PRIMARY KEY,
  file_id         VARCHAR(64) NOT NULL,
  grantee_type    ENUM('user','group','domain','anyone') NOT NULL,
  grantee_id      VARCHAR(256),               -- email/group/domain or null for 'anyone'
  role            ENUM('reader','commenter','writer','organizer','owner') NOT NULL,
  inherited       BOOLEAN DEFAULT FALSE,      -- true if inherited from parent folder
  expires_at      TIMESTAMP,                  -- for time-limited shares
  INDEX (file_id),
  INDEX (grantee_id)
);
```

**ACL Inheritance Rule**: When sharing a folder, all children inherit the permission. The system stores both explicit and inherited rows (or resolves at query time via ancestor walk — trade-off discussed in Section 10).

### 5.4 User Quota

```sql
CREATE TABLE user_storage (
  user_id         VARCHAR(64) PRIMARY KEY,
  quota_bytes     BIGINT NOT NULL DEFAULT 16106127360,   -- 15 GB
  used_bytes      BIGINT NOT NULL DEFAULT 0,
  updated_at      TIMESTAMP
);
```

`used_bytes` is maintained via transactional increments on successful upload commits. Quota checks act as a gate before accepting upload sessions.

### 5.5 Object Storage Layout

Files are stored in object storage (like GCS) with the following key schema:

```
bucket/{owner_id_prefix}/{file_id}/{revision_id}/data
```

Benefits:
- Prefix-based access policies align with owner.
- Each revision is immutable; updates write a new object, update the head pointer.
- Deletion = metadata tombstone; garbage collection runs async.

### 5.6 Change Events (For Sync)

```text
Topic: file_changes
Key: user_id (ensures ordering per user's change feed)
Value: {
  change_id, user_id, file_id, change_type (CREATE|UPDATE|DELETE|MOVE|SHARE),
  timestamp, new_metadata_snapshot
}
```

Stored in Kafka with long retention. Sync clients and notification services consume this feed.

---

## 6. High-Level Architecture

```text
+-------------------------- Clients --------------------------+
| Web Browser / Desktop Sync Agent / Mobile App / Office SDK |
+-----------------------------+-------------------------------+
                              |
                      [Global Load Balancer]
                      [Cloud CDN for downloads]
                              |
               +--------------+--------------+
               |              |              |
               v              v              v
        +-----------+  +----------+   +----------+
        | API GW    |  | Upload   |   | Download |
        | (Auth,    |  | Service  |   | Service  |
        |  Route,   |  |          |   |          |
        |  RL)      |  +----+-----+   +----+-----+
        +-----+-----+       |              |
              |             v              v
   +----------+---------> [Object Storage: GCS / S3-compatible]
   |          |             (Chunked blobs, immutable revisions)
   |          |
   |   +------+--------+-------------------+
   |   |               |                   |
   v   v               v                   v
+-------+       +----------+       +----------------+
|Metadata|       |Permission|       |Search / Index  |
|Service |       |Service   |       |Service         |
|(Spanner|       |(Spanner) |       |(Elasticsearch) |
| /RDBMS)|       +-----+----+       +-------+--------+
+----+---+             |                    |
     |                 v                    v
     |           +----------+        [Full-text index]
     +---------> | Cache    |        [Metadata index]
                 | (Redis)  |
                 +-----+----+
                       |
               +-------+--------+
               |                |
               v                v
        +-----------+    +-------------+
        |Sync /     |    |Notification |
        |Change Feed|    |Service      |
        |(Kafka)    |    |(Pub/Sub)    |
        +-----------+    +-------------+
```

### 6.1 Control Plane vs Data Plane
- **Control plane**: Metadata Service, Permission Service, Auth — consistency-critical, runs on Spanner/RDBMS.
- **Data plane**: Upload Service, Download Service, Object Storage — high throughput, must be horizontally scalable and CDN-backed.

Separating them allows independent scaling: metadata operations and binary data transfers have completely different resource profiles.

---

## 7. Detailed Component Design

### 7.1 API Gateway
- **AuthN**: Validates OAuth 2.0 bearer tokens (or service account credentials).
- **AuthZ pre-check**: Fast coarse-grained check (is this a valid user?).
- **Rate limiting**: Per-user and per-app quotas.
- **Request routing**: Routes to Metadata Service, Upload Service, Download Service, etc.
- **Request tracing**: Injects trace IDs for distributed tracing.

SDE3 note: Gateway should remain stateless and thin. Business logic (file ownership checks, ACL evaluation) must live in downstream services.

### 7.2 Upload Service (Deep Dive)

The Upload Service is one of the most interview-critical components.

**Resumable Upload Flow:**
```
1. Client sends POST to initiate upload session.
2. Upload Service validates:
   - User authentication.
   - Storage quota (calls Quota Service; fails fast if over limit).
   - File size against per-file limits.
3. Returns unique upload_session_id bound to: user, file_id, size, mime_type, TTL.
4. Client sends chunks (typically 8–256 MB each).
5. Service stores chunk in temp staging area in Object Storage.
6. Service tracks received byte ranges in Redis (upload session state).
7. On final chunk receipt: assemble or verify (most object stores support compose/multipart).
8. Compute checksum; verify against client-provided checksum.
9. Atomically:
   - Write revision record.
   - Update file metadata (size, md5, head_revision_id, modified_at).
   - Update used_bytes in user_storage.
   - Emit file_changed event to Kafka.
10. Respond with completed file resource.
```

**Why chunked uploads?**
- Fault tolerance: resume from last ACKed chunk, not from byte 0.
- Parallelism: multi-part parallel upload significantly increases throughput.
- Memory efficiency: service never holds full file in memory.

**Deduplication (Content-Addressable Storage):**
- Before committing a chunk or full file, compute SHA-256.
- Check a blob hash index: if the blob already exists in object store, create a reference pointer instead of storing duplicate bytes.
- This is a significant cost optimization (30-50% storage savings in practice).

**Chunk Size Trade-offs:**

| Chunk Size | Pros | Cons |
|---|---|---|
| Small (256 KB – 1 MB) | Fast retry on failure, low memory | High request overhead |
| Medium (8–16 MB) | Balance of retryability and efficiency | Most common choice |
| Large (256 MB+) | Fewer requests, high throughput | Larger retry penalty on failure |

### 7.3 Download Service

- Validates access permission (calls Permission Service).
- Issues a signed, time-limited URL to the object storage blob (TTL: 5–15 minutes).
- Client downloads directly from object storage / CDN edge — backend is NOT in the data path.
- For large files, supports range requests (`Range: bytes=X-Y`) enabling parallel multi-part download and resumption.
- CDN caches public or shared-link files at edge nodes globally.

**Serving latency optimization:**
- Hot files (frequently accessed) are pinned at CDN indefinitely.
- Cold files served directly from object storage.
- File metadata (name, size, permissions) served from Redis cache (L1) → Spanner (L2).

### 7.4 Metadata Service

Owns the canonical state of all file records, folder hierarchy, and revision pointers.

**Technology choice: Google Cloud Spanner (or CockroachDB)**
- ACID transactions with linearizable reads across globally distributed nodes.
- This is the right choice because file operations (move, rename, share) often span multiple rows (e.g., update file_tree + files + permissions in one atomic transaction).
- Alternative: Strong-consistency PostgreSQL or MySQL with sharding (more operational complexity).

**Key operations:**
- `createFile(metadata, parentId, userId)` — transactionally inserts file + file_tree + initial revision.
- `moveFile(fileId, oldParentId, newParentId)` — updates file_tree atomically; does NOT touch blob data.
- `listChildren(folderId, pageToken, filters)` — secondary index scan on parent_id; uses cursor-based pagination.
- `trashFile(fileId)` — sets `trashed=true`, records `trashed_at`; content not touched.

### 7.5 Permission Service (ACL Evaluation Deep Dive)

**Storage**: Permissions stored in Spanner with a secondary index on `(file_id)` and `(grantee_id)`.

**ACL evaluation algorithm:**
```
function canAccess(userId, fileId, requiredRole):
  1. Check explicit permissions for (fileId, userId).
  2. Check permissions for any group memberships of userId.
  3. Walk ancestor folders (parent chain) checking inherited permissions.
  4. Check if file has 'anyone' permission with sufficient role.
  5. Deny by default if none of the above grants access.
```

**Ancestor walk challenge at scale:**
- Naïve walk = O(depth) DB reads per access check. Depth can be 10-20 levels.
- **Optimization 1**: Cache resolved ACL decisions in Redis with TTL. Invalidate on any permission change.
- **Optimization 2**: Store materialized path or ancestor list on each file record (denormalized for read speed; updated on move operations).
- **Optimization 3**: Use a dedicated permission cache service as a read-through layer.

**Sharing model:**
- `owner`: can do anything, transfer ownership.
- `organizer`: can organize (move/add to folder), share, edit.
- `writer`: can edit content, add/remove children in a folder.
- `commenter`: can read and comment only.
- `reader`: read-only.
- Sharing with a domain: all users of `@company.com` get access.

### 7.6 Sync Service / Change Feed (Desktop Sync Agent Protocol)

This is another SDE3-critical design area.

**Architecture:**
```
Desktop Client                         Server
-------------                          ------
[Local File Watcher]                   [Change Feed API]
      |                                       |
      |  POST /changes (local delta)          |
      +-------------------------------------->|
      |                                       |-> Kafka consumer
      |                               [Conflict Resolver]
      |  GET /changes?since=token             |
      |<--------------------------------------+
[Apply remote changes]
```

**Change detection:**
- **Server-side**: Any file operation emits a change event to Kafka. Sync API exposes a paginated feed of changes since a given `pageToken`.
- **Client-side**: OS file system watchers (inotify on Linux, FSEvents on macOS, ReadDirectoryChangesW on Windows) detect local mutations.

**Sync Algorithm (Simplified):**
```
1. On startup: fetch all changes since last sync token.
2. Continuously poll (or long-poll) for new changes.
3. For each remote change:
   a. Hash local file state.
   b. If local == last known server state: apply remote change safely.
   c. If local != last known server state AND != remote: CONFLICT.
4. Conflict resolution:
   a. Default: Create a conflict copy (rename one to "filename_conflict_timestamp.ext").
   b. Advanced: three-way merge for text files using base (common ancestor) + local + remote.
```

**Conflict Resolution Strategies:**

| Strategy | When Applied | Behavior |
|---|---|---|
| Last Write Wins | When client modification timestamp is trusted | Simpler but can lose data |
| Conflict Copy | Default for binary files | Safe; user sees both versions |
| Three-way merge | Text/code files only | Most user-friendly |
| Manual resolution | Collaborative docs (Docs/Sheets) | CRDT/OT in real-time editor |

### 7.7 Versioning And Retention

**Automatic versioning:**
- Every successful upload creates a new revision record pointing to a new blob.
- Head revision pointer is updated atomically with revision insert (one Spanner transaction).
- Old blobs are NOT deleted immediately; the garbage collector handles cleanup.

**Retention policy engine:**
- Google Workspace: configurable retention (e.g., 100 revisions, 30 days) per org policy.
- Consumer Drive: keeps ~100 revisions per file or 30 days, whichever is less.
- `keepForever` flag exempts a revision from policy deletion.
- Async GC job: finds revisions exceeding policy, removes blob references, reclaims quota.

**Trash and permanent deletion:**
- Trash = soft delete (`trashed=true`). Files remain in storage and count against quota.
- Permanent delete = hard delete. Metadata removed; blob scheduled for GC.
- Auto-empty trash after 30 days.

### 7.8 Search Service

**Two search dimensions:**
1. **File name / metadata search**: Filter by name, owner, type, modification date, shared-with.
2. **Full-text content search**: Search inside document text (Docs, PDFs, Sheets).

**Architecture:**
- **Indexing pipeline**: File creation/update event → content extraction service (OCR for images/PDFs, text extraction for Docs) → Elasticsearch index.
- **Index fields**: `file_id`, `owner_id`, `accessible_by[]`, `name_tokens`, `content_tokens`, `mime_type`, `created_at`, `modified_at`, `size`, `parent_ids[]`.
- **Authorization at query time**: Search results are filtered by `accessible_by` field matching the requesting user's identity (user ID + all group memberships).
- **Result ranking**: Blend of relevance score, recency, access frequency, file type priority.

**Scale challenge: per-user sharded indexes vs shared index:**
- Shared index is simpler to manage but requires post-filtering ACL checks at scale.
- Per-user shard gives hard isolation and simpler query; hard to manage for shared content.
- Google Drive uses a mixed approach: shared file content indexed globally, ACL filter applied at query time with efficient bit-set authorization.

### 7.9 Quota Service

- Central quota ledger per user in Spanner (strong consistency required).
- Before issuing an upload session: read `used_bytes`, compare to `quota_bytes`.
- On upload commit: `UPDATE user_storage SET used_bytes = used_bytes + $delta WHERE user_id = $uid`.
- On delete/trash: quota is NOT immediately reclaimed (reclaimed on permanent delete via async job).
- Cache quota state in Redis (TTL: 30s) to avoid hammering Spanner on every pre-check.

**Quota deduplication nuance**: if two users upload identical files, deduplication saves storage but quota should still charge each user — quota tracks logical ownership, not physical bytes. This is an important trade-off to mention.

### 7.10 Notification Service

- Consumes file_change events from Kafka.
- For each change, resolves the set of users who should be notified (all permissioned users on the file, minus the actor).
- Dispatches via: email, in-app push, web push, or mobile push.
- **Push channel for web clients**: Server-Sent Events (SSE) or WebSocket long-lived connection for real-time badges.
- **Fan-out challenge**: A file shared with a large group (1M users) needs careful fan-out — use async job queue to spread notifications rather than fan-out synchronously.

---

## 8. End-To-End Critical Flows

### 8.1 Upload A Large File (Resumable)

```
1. Client: POST /upload?type=resumable (metadata + size hint)
2. API GW: Authenticate, route to Upload Service.
3. Upload Service: Check quota → Issue upload session (stored in Redis, TTL: 7 days).
4. Client: PUT chunks sequentially (or in parallel for multipart).
5. Upload Service: Store each chunk in staging object storage prefix.
   Track byte ranges received in Redis session state.
6. On last chunk: verify total size + checksum.
7. Upload Service (atomic):
   a. Compose chunks into final blob in object storage.
   b. Write revision record to Spanner.
   c. Update file metadata (head_revision, size, modified_at) in Spanner.
   d. Increment user used_bytes.
   e. Emit CREATE/UPDATE event to Kafka.
8. Upload Service: Return 200 with complete file resource to client.
9. Kafka consumer: Index file in Elasticsearch, update change feed, notify relevant users.
```

**Failure handling:**
- Chunk upload fails → client retries the same chunk (idempotent PUT).
- Upload session DB (Redis) entry expires after 7 days of no activity → session abandoned.
- Object compose fails → rollback revision record; leave blobs for GC.

### 8.2 Download A File

```
1. Client: GET /v3/files/{fileId}?alt=media
2. API GW: Auth → routes to Download Service.
3. Download Service: Fetch metadata from Redis/Spanner.
4. Permission Service: Evaluate ACL for (userId, fileId) → check read permission.
5. Generate signed URL for blob (GCS signed URL, TTL: 10 min).
6. Return HTTP 302 redirect (or signed URL) to client.
7. Client: Downloads directly from GCS / CDN.
   Supports Range requests for partial/resumable download.
```

### 8.3 Share A File

```
1. Client: POST /v3/files/{fileId}/permissions {role: writer, email: bob@example.com}
2. Permission Service: Validate owner/organizer capability of caller.
3. Resolve email → userId (via user lookup service).
4. Write permission record to Spanner.
5. If fileId is a folder: recursively propagate inherited permission flags to children
   (async job for large folder trees).
6. Emit SHARE event to Kafka.
7. Notification Service: Email invitation to Bob + in-app notification.
```

**Recursive propagation challenge**: A folder with 100K files → propagating explicit rows would be expensive. Two approaches:
- **Eager**: Write inherited permission rows for all descendants (consistent but slow and expensive).
- **Lazy**: Store only explicit permissions; walk ancestor chain at authorization time (ACL evaluation does the inheritance traversal). Preferred at scale (Google's approach).

### 8.4 Sync Client Detecting Remote Change

```
1. Desktop client polls: GET /v3/changes?pageToken=X&spaces=drive
2. Server returns list of changed file IDs + metadata snapshots + nextPageToken.
3. Client processes each change:
   a. File created remotely → download to local folder.
   b. File modified remotely → compare with local hash.
      If local is clean: apply update.
      If local is dirty: conflict resolution flow.
   c. File deleted remotely → move local file to trash.
4. Client saves nextPageToken as new cursor for next poll.
```

**Long polling vs WebSocket vs SSE for push:**
- **Long polling**: Simple, works everywhere, but introduces 30-60s notification lag.
- **WebSocket**: Real-time, bidirectional, best for active collaboration.
- **SSE**: Server-side push, simpler than WebSocket, good for one-directional change feeds.

Google Drive desktop uses a combination: long-polling change feed for reliability, WebSocket for real-time notifications when window is active.

### 8.5 File Restore From Trash

```
1. Client: PATCH /v3/files/{fileId} {trashed: false}
2. Metadata Service: Verify file is in user's trash.
3. Update trashed=false, trashed_at=null.
4. Emit RESTORE event to Kafka.
```

### 8.6 File Version Restore

```
1. Client: POST /v3/files/{fileId}/revisions/{revisionId}/restore (logical)
   → In practice: copy old revision blob as a new revision
2. Metadata Service (atomic):
   a. Write a new revision record pointing to old blob (no blob copy needed — content-addressed).
   b. Update file head_revision_id to new revision.
   c. Update file metadata: size, md5, modified_at.
   d. Emit UPDATE event.
```

---

## 9. Consistency, Transactions, And Idempotency

### 9.1 Consistency Model Choices

| Operation | Consistency Requirement | Why |
|---|---|---|
| Upload metadata commit | Strong (ACID) | Avoid partial state: revision + file update must be atomic |
| ACL changes | Strong | Security: no window of unintended access |
| Quota updates | Strong | Prevent quota bypass (over-commit) |
| Search index update | Eventual | Slight lag acceptable; indexing is async |
| Cross-region metadata replication | Configurable (Spanner allows stale reads) | Latency vs freshness trade-off |
| Change feed delivery | At-least-once | Retry on consumer failure; clients must be idempotent |

### 9.2 Transactions In Spanner

Google Cloud Spanner supports multi-table read-write transactions with serializable isolation. Critical flows use explicit transactions:

```
BEGIN TRANSACTION
  INSERT INTO revisions (revision_id, file_id, ..., storage_key)
  UPDATE files SET head_revision_id = ?, size_bytes = ?, modified_at = NOW()
  UPDATE user_storage SET used_bytes = used_bytes + ? WHERE user_id = ?
COMMIT
```

If any step fails → entire transaction rolls back → no partial state visible.

### 9.3 Idempotency

- **Upload sessions**: Identified by `upload_session_id`. Re-uploading the same chunk is a no-op (tracked byte ranges).
- **Permission mutations**: Idempotent by design — granting an already-existing role is a safe upsert.
- **Change feed consumers**: Each event has a unique `change_id`; consumers maintain a dedup set (Redis sorted set by `change_id`) within a processing window.

---

## 10. Caching Strategy

### 10.1 Cache Tiers

```
L1 — Client-side cache (browser, desktop agent):
  → File metadata (TTL: 60s), thumbnails, recently accessed blobs
  → Critical for offline UX and reducing repeat network calls

L2 — Edge CDN cache:
  → File blobs for public links and high-traffic shared files
  → Thumbnails, preview images, static file renditions
  → TTL: hours to days depending on file immutability

L3 — Service cache (Redis):
  → File metadata: hot file_id → {name, size, mime, modified_at}  TTL: 5 min
  → Permission cache: (userId, fileId) → role  TTL: 2 min (short due to security)
  → ACL set: fileId → {user1:reader, user2:writer, ...}  TTL: 2 min
  → Quota state: userId → {used, quota}  TTL: 30s
  → Change page tokens: thin state for sync clients
```

### 10.2 Cache Invalidation

```
Event: file renamed or moved
  → Delete metadata cache entry for file_id.
  → Emit invalidation message to CDN for affected URLs.

Event: permission changed
  → Delete all permission cache entries for file_id.
  → Immediately (not on TTL expiry) — security requirement.

Event: quota updated
  → Update quota cache entry directly (write-through).
```

### 10.3 Cache Stampede Prevention

- **Mutex lock**: Only one backend thread fetches from Spanner for a given cache miss; others wait (Redis `SET NX` pattern).
- **Probabilistic early re-fetch**: Randomly re-cache entries before TTL expiry to avoid synchronized expiry under high load.
- **Pre-warming**: Popular files (trending shared links, org-wide announcements) cached proactively.

---

## 11. Data Partitioning And Scaling

### 11.1 Metadata Partitioning (Spanner)

Spanner uses range-based sharding with automatic split decisions. Key range design:

```
files table:    PRIMARY KEY (file_id)  — random UUIDs distribute load evenly.
file_tree:      PRIMARY KEY (parent_id, file_id) — enables efficient parent→children scan.
permissions:    PRIMARY KEY (file_id, permission_id) — collocated with file for join efficiency.
revisions:      INTERLEAVED IN PARENT files — Spanner feature that colocates related rows.
```

**Interleaving**: Spanner's interleaving feature stores child rows (revisions) physically adjacent to parent rows (files) on disk. This makes "fetch file + its revisions" extremely fast (single read I/O unit).

### 11.2 Object Storage Sharding

Object store (GCS) handles automatic sharding internally. The key schema provides:
```
{bucket}/{owner_prefix}/{file_id}/{revision_id}/data
```
- **owner prefix**: First 6 chars of owner ID. Prevents hotspot on single prefix.
- Avoids lexicographic hotspot by NOT using sequential timestamps as key prefix.

### 11.3 Kafka Partitioning For Change Feed

- Partition key: `user_id`.
- Ensures all changes for a given user arrive in order to a single partition/consumer.
- Allows sync clients to receive an ordered, consistent view of their file tree changes.
- Scale: if 500M DAU generates 30K change events/sec → 3,000 partitions at ~10 events/sec/partition.

### 11.4 Hot File Problem

A file shared publicly with millions of readers (viral link) creates extreme read hotspot.

Mitigations:
- CDN absorbs all download traffic (blob never fetched from origin per request).
- Metadata service: Redis cache with high TTL for public/anonymous reads.
- Permission check short-circuit: files with `role=reader, type=anyone` bypass per-user ACL evaluation entirely — just check public flag.

---

## 12. Durability, Reliability, And Disaster Recovery

### 12.1 Object Storage Durability

Google Cloud Storage provides 11 nines durability using:
- **Erasure coding**: Data split into shards (e.g., 6 data + 3 parity). Any 3 shards can be lost without data loss.
- **Multi-region buckets**: Data replicated across geographically separate data centers.
- **Checksumming**: CRC32C verified on every write and periodic integrity scans.

### 12.2 Metadata Durability (Spanner)

- Paxos consensus: all writes committed to quorum (majority of replicas) before acknowledging success.
- Multi-region Spanner instance: replicas in 3+ regions, tolerates loss of any single region.
- Point-in-time restore: Spanner supports version reads for up to 1 hour (for accidental data corruption recovery).

### 12.3 Multi-Region Active-Active Architecture

```
Region A (Primary)               Region B (Secondary)
+------------------+             +-------------------+
| API Gateway      |             | API Gateway       |
| Upload Service   |             | Upload Service    |
| Metadata (Spanner Leader) <--->| Metadata (Spanner Replica)|
| Object Storage   |             | Object Storage    |
| CDN PoPs         |             | CDN PoPs          |
+------------------+             +-------------------+
```

- Spanner leader handles all writes; replicas serve reads.
- If leader region goes down, Spanner auto-elects a new leader in another region (< 60s typical).
- Upload traffic can write to any region; if not leader region, write is forwarded internally.
- CDN globally distributed — download traffic does NOT depend on region connectivity.

### 12.4 Failure Mode Analysis

| Failure | Impact | Mitigation |
|---|---|---|
| Spanner region outage | Write unavailability < 60s | Auto-failover; multi-region replica |
| Redis cache failure | Cache miss storm | Read-through from Spanner; auto-scale Redis cluster |
| Kafka lag spike | Stale search index, delayed notifications | Auto-scale consumers; alert on lag threshold |
| Upload Service crash mid-upload | Session interrupted | Resumable session persisted in Redis; client resumes from last ACKed byte |
| Object storage partial write | Checksum mismatch | Transaction rolls back; GC cleans orphan object |
| CDN PoP outage | Slower downloads in region | Global load balancer reroutes to next closest PoP |
| Permission cache stale | Unauthorized access window | Short TTL (2 min) + immediate invalidation on ACL change |

---

## 13. Security And Privacy

### 13.1 Encryption
- **In transit**: TLS 1.3 everywhere (client → GW, GW → services, service → service, service → object storage).
- **At rest**: AES-256 encryption of all blobs in object storage. Google manages keys (or customer-managed keys via Google Cloud KMS).
- **Key rotation**: Automated key rotation without requiring re-encryption of data (envelope encryption pattern).

**Envelope Encryption:**
```
blog data → encrypted with Data Encryption Key (DEK)
DEK → encrypted with Key Encryption Key (KEK) stored in KMS
Only KEK is rotated; rotated KEK re-encrypts the DEK, not the blob.
```

### 13.2 Access Control Enforcement
- API Gateway validates OAuth 2.0 token signature and expiry.
- Every API call into Metadata or Download Service re-checks permission (no trust implicit from gateway).
- Signed download URLs are short-lived (TTL: 10–15 min) and bound to a specific blob — leaked URLs have limited blast radius.
- Download URLs are scoped to `file_id + revision_id + allowed_user_id` (optional user binding).

### 13.3 Audit Logging
- All access events (read, write, share, delete) written to an immutable append-only audit log (Cloud Logging / BigQuery).
- Workspace admins can query: "Who accessed this file in the last 30 days?"
- Unusual access patterns (geographic anomaly, excessive download) trigger security alerts.

### 13.4 Malware Scanning
- Uploaded files are scanned asynchronously by a malware engine.
- High-risk file types (executables, archives) are quarantined until cleared.
- Scan results stored in file metadata; infected files blocked from download/sharing.
- Content-addressed deduplication means a known-bad hash can immediately quarantine all references.

### 13.5 Privacy And Compliance
- GDPR: User data deletion workflows — file content + metadata deleted within 30 days of account deletion.
- Data residency: Org admins can constrain data to specific regions.
- Zero-knowledge option (planned/available for Workspace): client-side encryption before upload (Google never has plaintext keys).

---

## 14. Observability And SLOs

### 14.1 Golden Signals Per Service

| Service | Latency SLO | Error Rate SLO |
|---|---|---|
| Metadata API | P99 < 100 ms | < 0.01% |
| Upload initiation | P99 < 200 ms | < 0.1% |
| Download (TTFB) | P99 < 200 ms | < 0.01% |
| Permission check | P99 < 50 ms | < 0.001% |
| Search | P99 < 500 ms | < 0.1% |

### 14.2 Key Metrics

**Upload pipeline metrics:**
- Upload session creation rate.
- Chunk upload success/failure rate.
- Checksum mismatch rate (indicates corruption or client bug).
- Average upload throughput per session.
- Session abandonment rate (inactivity before completion).

**Sync metrics:**
- Change feed processing lag (time from server event to client application).
- Conflict rate (indicates sync health; high rate = user experience problem).
- Delta sync queue depth.

**Storage metrics:**
- Quota utilization distribution across users.
- Deduplication ratio (unique bytes / total logical bytes).
- GC lag (orphan blobs awaiting cleanup).

### 14.3 Alerting
- Multi-window error budget burn-rate alerts (SRE golden standard).
- Upload failure rate > 1% for 5 minutes → PagerDuty alert.
- Kafka consumer lag > 10K messages → auto-scale + alert.
- Spanner latency P99 > 200 ms → investigate hotspot / capacity.

---

## 15. Cost Optimization

1. **Deduplication**: Content-addressed blob storage. Estimated 30-50% storage savings for typical user populations.
2. **Storage tiering**: Hot files (recently modified/accessed) on SSD-backed storage; cold files (>90 days unaccessed) migrated to nearline/coldline (cheaper, higher latency). Done transparently via lifecycle policies.
3. **CDN cache hit ratio**: Higher CDN hit ratio = less origin egress cost. Tune cache TTL and pre-warm frequently shared files.
4. **Compression**: Compress compressible MIME types (text, code, Office docs) before storage.
5. **GC efficiency**: Timely garbage collection of orphan blobs and old revisions reclaims expensive storage.
6. **Quota enforcement**: Prevents runaway storage usage from automated scripts.
7. **Batching API calls**: Expose batch endpoints for clients (list + metadata in one round trip) to reduce per-request overhead.

---

## 16. Major Trade-Offs And Why

### 16.1 Eager vs Lazy ACL Inheritance
- **Eager** (write explicit rows on share): Fast read-time checks, simple logic. Expensive writes for large folder trees (100K files each needing a row).
- **Lazy** (resolve at read time via ancestor walk): Cheap writes, more complex and potentially slower read path.
- **Decision**: Lazy inheritance with aggressive caching. Ancestor depth is bounded (~20 max); cached resolution makes reads fast. Google Drive uses this approach.

### 16.2 Strong Consistency (Spanner) vs Eventual Consistency (DynamoDB/Cassandra) for Metadata
- Strong consistency prevents the "upload succeeded but file doesn't show in list" anomaly, which is very confusing for users.
- Eventual consistency would be cheaper but creates a class of user-facing bugs that erode trust.
- **Decision**: Spanner for all file metadata. Pay the cost; the correctness guarantee is mandatory.

### 16.3 Monolithic Object Storage vs Tiered Storage
- Single hot tier: simple but expensive.
- Tiered: significantly cheaper for cold data (10x cost reduction) but adds complexity (migration job, potential latency on cold access).
- **Decision**: Tiered storage with automatic lifecycle policies. Cold access penalty is acceptable for rarely accessed files.

### 16.4 Per-User Shard for Search vs Shared Index
- Per-user: hard isolation, no cross-user data leakage risk, simpler ACL filtering.
- Shared: better resource utilization, simpler management, efficient for shared files.
- **Decision**: Shared index with indexed `accessible_by` field. Carefully tested to prevent cross-user leakage. At Google's scale, shared index is much more cost-efficient.

### 16.5 Conflict Copy vs Last Write Wins for Sync
- LWW loses user data silently — unacceptable for a cloud drive product.
- Conflict copy: no data loss but creates clutter (users see duplicates).
- **Decision**: Conflict copy as the default safe choice. Three-way merge for text files where feasible.

---

## 17. Interview-Ready Deep Dive Talking Points

**If interviewer asks "how do you handle 5 TB file uploads?":**
> Resumable multipart upload. Client chunks the file (8–256 MB each). Each chunk is independently uploaded and confirmed. Upload session state persists in Redis. Client can resume from any chunk boundary after interruption. Final compose operation assembles chunks in object storage. Checksum verified end-to-end.

**If interviewer asks "how is ACL evaluated efficiently at scale?":**
> Lazy inheritance: only explicit permissions stored. Read-time ancestor walk (bounded depth). Aggressive per-(userId, fileId) cache in Redis with short TTL. Cache invalidated immediately on any ACL change for security. Ancestor list denormalized on file record to avoid recursive tree traversal.

**If interviewer asks "how does desktop sync work and what about conflicts?":**
> Client uses OS file-system watchers for local changes. Server changes polled via paginated change feed (with long-polling). Client hashes local file vs last-known server state. If both diverged → conflict copy created. For text files, three-way merge using common ancestor revision.

**If interviewer asks "what prevents quota bypass?":**
> Quota reads and increments use Spanner transactions (strong consistency). Upload session only granted if `available_bytes > requested_size`. Commit transaction atomically deducts bytes. Cache has a 30s TTL; security margin: upload session issuance is the hard gate, not just the cache.

**If interviewer asks "how is 11 nines durability achieved?":**
> Erasure coding within a data center (6+3 parity). Multi-region replication at object storage level. Periodic integrity scanning with checksums. All writes ACKed only after durability guarantee from storage layer.

**If interviewer asks "what breaks first at scale?":**
> 1. Kafka lag → delayed notifications/index = increase consumer parallelism.
> 2. Redis cache hot key (viral shared file) → cache read coalescing + CDN pre-pinning.
> 3. Spanner hotspot on specific file_id (many concurrent permissions) → advisory locks + rate limiting.
> 4. Recursive folder share propagation → async job queue with rate limiting.

---

## 18. Possible 45-Minute Interview Narrative

| Time | Topic |
|---|---|
| 0–5 min | Clarify scope: file types, size limits, sharing model, sync, versions |
| 5–12 min | Capacity planning: users, storage, QPS, bandwidth estimates |
| 12–18 min | API design: upload (resumable), download, share, search, sync feed |
| 18–28 min | Architecture diagram + component walkthrough |
| 28–35 min | Deep dive: upload pipeline OR sync conflict resolution OR ACL evaluation |
| 35–42 min | Reliability, consistency model, caching, security |
| 42–45 min | Trade-offs, cost optimization, extensions |

---

## 19. Extensions You Can Mention If Time Permits

- **Real-time collaborative editing**: Integration with Google Docs OT/CRDT engine for simultaneous multi-cursor editing of the same document.
- **Optical Character Recognition (OCR)**: Indexing text inside scanned PDFs and images for full-text search.
- **Drive AI features**: Semantic search ("find spreadsheets about Q3 revenue"), smart suggestions, auto-categorization.
- **Admin console**: Organization-wide visibility into file sharing, DLP (Data Loss Prevention) policy enforcement, block external sharing of sensitive files.
- **Audit and eDiscovery**: Compliance-grade audit logs for legal hold and regulatory requirements.
- **Offline-first mobile**: IndexedDB / SQLite local metadata store; conflict-aware sync engine on mobile.
- **Quota expansion and storage purchase**: Tiered subscription plans; storage pooled across Workspace accounts.
- **Drive SDK and third-party integrations**: Add-ons, OAuth scopes, webhook push notifications for third-party apps.
