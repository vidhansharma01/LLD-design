# High-Level Design: Spotify (SDE3 Interview)

## 0. How To Use This Document In Interview
This write-up is intentionally deep. In a real SDE3 interview, present in this order:
1. Clarify scope and constraints.
2. Show API and data model.
3. Draw architecture and playback flow.
4. Explain scale numbers and critical bottlenecks.
5. Cover trade-offs, reliability, security, and extensions.

If the interviewer interrupts for deep-dive, jump directly to the relevant section.

---

## 1. Problem Statement And Scope
Design a Spotify-like platform that supports:
- Music streaming with low startup latency and low buffering.
- Search across tracks, albums, artists, playlists, podcasts.
- Playlists (including collaborative edits).
- Personalized recommendations.
- Free and premium tiers.
- Offline downloads for premium users.
- Cross-device playback handoff (Spotify Connect style).

### 1.1 Explicitly In Scope
- Core backend architecture.
- Data storage model and partitioning.
- Streaming pipeline and CDN usage.
- Recommendation data flow.
- Reliability, observability, and security.

### 1.2 Out Of Scope (For This HLD)
- UI details.
- Payment gateway internals.
- Deep DRM cryptography implementation internals.
- Exact ML model architecture internals at feature-level.

---

## 2. Requirements

### 2.1 Functional Requirements
1. User can search for track/artist/album/playlist/podcast.
2. User can stream audio with adaptive quality.
3. User can create/edit/delete playlists.
4. User can like/save tracks and follow artists/users.
5. Premium user can download content for offline listening.
6. System supports play queue operations: play, pause, skip, seek, next.
7. Recommendations: home feed, daily mixes, discover weekly.
8. Free tier supports ads and limited skips.
9. Playback events are captured for analytics, royalties, and recommendations.
10. Cross-device control: mobile controls desktop/speaker.

### 2.2 Non-Functional Requirements
- Availability:
  - Playback control plane: 99.99%.
  - Search and playlists: 99.95%.
- Latency:
  - First audio byte (P99): less than 300 ms.
  - Search (P99): less than 150 ms.
- Scale:
  - Hundreds of millions of users.
  - Tens of millions of concurrent streams.
- Durability:
  - Playlist and library writes should not be lost.
- Consistency:
  - Strong consistency for subscription/entitlement checks.
  - Eventual consistency acceptable for recommendations and analytics.
- Security:
  - Encrypted media delivery, signed URLs/tokens, rights enforcement by geography.

---

## 3. Back-Of-The-Envelope Capacity Planning

Assumptions (interview-friendly, tunable):
- MAU: 700 million
- DAU: 300 million
- Avg listening/user/day: 90 minutes
- Avg track length: 3.5 minutes
- Avg effective bitrate served: 160 kbps (mix of quality tiers)
- Peak traffic factor over daily average: 2.5x
- Catalog size: 120 million tracks

### 3.1 Plays And Concurrency
- Plays/day ~= 300M * (90/3.5) ~= 7.7B plays/day
- Average concurrent listening seconds:
  - 300M * 90 * 60 = 1.62e12 listening seconds/day
  - Average concurrent streams = 1.62e12 / 86400 ~= 18.75M
  - Peak concurrent streams ~= 18.75M * 2.5 ~= 46.9M

### 3.2 Throughput
- Peak egress bitrate ~= 46.9M * 160 kbps ~= 7.5 Tbps
- This must be offloaded to CDN edges; origin cannot handle this economically.

### 3.3 Storage
Per track storage (approx):
- 96 kbps version: ~2.5 MB
- 160 kbps version: ~4.2 MB
- 320 kbps version: ~8.4 MB
- Total/track for 3 encodings: ~15.1 MB

Catalog media size:
- 120M * 15.1 MB ~= 1.8 PB
- With replication and multi-region copies: 3.5-5 PB practical footprint.

Metadata:
- Track/album/artist metadata + indexes typically in TB scale (much smaller than media).

### 3.4 Event Ingestion
Playback events (start, progress, skip, complete, pause, seek):
- If each play generates 4-8 events:
  - 7.7B plays/day => 30B-60B events/day
  - 350K-700K events/sec average during peak windows
- Requires partitioned event bus + stream processing.

---

## 4. High-Level API Design

Representative APIs (not exhaustive):

### 4.1 Search
```http
GET /v1/search?q={query}&type=track,artist,album,playlist&limit=20&offset=0
```
Response includes ranked entities with availability flags for user region.

### 4.2 Track Metadata
```http
GET /v1/tracks/{trackId}
```

### 4.3 Playback Token / Stream Manifest
```http
POST /v1/playback/token
{
  "trackId": "trk_123",
  "deviceId": "dev_456",
  "qualityPreference": "auto",
  "positionMs": 0
}
```
Returns:
- signed stream URL (or signed manifest URL)
- DRM license endpoint/token
- allowed qualities
- expiry

### 4.4 Playlist APIs
```http
POST   /v1/playlists
PATCH  /v1/playlists/{playlistId}
POST   /v1/playlists/{playlistId}/tracks
DELETE /v1/playlists/{playlistId}/tracks/{playlistTrackId}
GET    /v1/playlists/{playlistId}
```

### 4.5 Library APIs
```http
PUT    /v1/me/liked-tracks/{trackId}
DELETE /v1/me/liked-tracks/{trackId}
GET    /v1/me/liked-tracks?cursor=...
```

### 4.6 Connect APIs (Cross-Device)
```http
POST /v1/connect/sessions/{sessionId}/commands
{
  "command": "PAUSE",
  "targetDeviceId": "desktop-1"
}
```

### 4.7 Event Ingestion API (Client Batch)
```http
POST /v1/events/playback:batch
[
  {"eventType":"PLAY_START", "ts":..., "trackId":"...", "deviceId":"..."},
  {"eventType":"PLAY_PROGRESS", "ts":..., "positionMs":30000}
]
```

---

## 5. Data Model (Core Entities)

### 5.1 Relational Metadata (Track Catalog)
Use relational DB for correctness and rich queries.

```sql
CREATE TABLE tracks (
  track_id            BIGINT PRIMARY KEY,
  album_id            BIGINT NOT NULL,
  title               VARCHAR(256) NOT NULL,
  duration_ms         INT NOT NULL,
  isrc                VARCHAR(32),
  explicit_flag       BOOLEAN DEFAULT FALSE,
  language_code       VARCHAR(8),
  created_at          TIMESTAMP NOT NULL,
  updated_at          TIMESTAMP NOT NULL
);

CREATE TABLE track_availability (
  track_id            BIGINT NOT NULL,
  country_code        CHAR(2) NOT NULL,
  start_time          TIMESTAMP,
  end_time            TIMESTAMP,
  PRIMARY KEY (track_id, country_code)
);

CREATE TABLE media_assets (
  track_id            BIGINT NOT NULL,
  quality_kbps        INT NOT NULL,
  codec               VARCHAR(16) NOT NULL,
  object_key          VARCHAR(512) NOT NULL,
  checksum            VARCHAR(128) NOT NULL,
  PRIMARY KEY (track_id, quality_kbps, codec)
);
```

### 5.2 User Library And Playlist Store (NoSQL)
User-centric access patterns need horizontal scale.

```text
Table: user_library
Partition key: user_id
Sort key: item_type#liked_at#item_id

Table: playlists
Partition key: playlist_id
Columns: owner_id, name, visibility, version, created_at, updated_at

Table: playlist_items
Partition key: playlist_id
Sort key: position_key
Columns: track_id, added_by, added_at, tombstone
```

`position_key` can use lexicographic fractional indexing for efficient inserts between items.

### 5.3 Playback Events (Immutable)
```text
Topic: playback_events
Key: user_id (or user_id + session_id)
Value: event payload with idempotency key, timestamp, track_id, position, device, network
Retention: 7-30 days on bus, then data lake/warehouse
```

### 5.4 Subscription And Entitlement
```text
Table: subscriptions (user_id -> plan, status, renew_at)
Table: entitlements (user_id -> feature flags)
Table: region_rights_cache (track_id + country -> allow/deny)
```

---

## 6. High-Level Architecture

```text
+----------------------- Clients -----------------------+
| iOS / Android / Web / Desktop / TV / Speaker          |
+-------------------------+-----------------------------+
                          |
                          v
                   +--------------+
                   | API Gateway  |
                   +------+-------+
                          |
    +---------------------+-----------------------------+
    |                     |                             |
    v                     v                             v
+-----------+     +---------------+             +---------------+
| Auth      |     | Playback Ctrl |             | Search Service|
| Service   |     | Service       |             | (OpenSearch)  |
+-----------+     +-------+-------+             +-------+-------+
                          |                             |
                          v                             v
                  +---------------+              +--------------+
                  | Entitlement   |              | Catalog      |
                  | + Rights      |              | Metadata API |
                  +-------+-------+              +------+-------+
                          |                             |
                          +--------------+--------------+
                                         |
                                         v
                                  +-------------+
                                  | Redis Cache |
                                  +------+------+ 
                                         |
                                         v
                                +------------------+
                                | Metadata DB      |
                                | (RDBMS)          |
                                +------------------+

Audio path:
Client -> Playback Ctrl -> signed CDN URL -> CDN Edge -> Object Storage Origin

Data/ML path:
Client events -> Ingestion API -> Kafka/Pulsar -> Stream Processor ->
  (a) Real-time counters/cache
  (b) Data lake/warehouse
  (c) Feature store -> Recommender training + serving
```

### 6.1 Why This Split Works
- Control plane (auth, entitlement, metadata, token issuance) is separate from data plane (actual audio bytes from CDN).
- This minimizes backend bandwidth cost and improves global performance.
- Stateless microservices scale horizontally behind load balancers.

---

## 7. Detailed Component Design

## 7.1 API Gateway
Responsibilities:
- AuthN/AuthZ enforcement.
- Request routing, rate limiting, and abuse prevention.
- Device and app version checks.
- Request tracing headers.

SDE3 note: keep gateway thin; business logic belongs in downstream services.

## 7.2 Playback Control Service
Responsibilities:
- Validate user session and device.
- Check subscription plan (free/premium).
- Check geo licensing and policy (explicit content filters, age restrictions).
- Select initial quality and codec from client capability + network hints.
- Mint short-lived signed stream token/manifest URL.

Token payload should include:
- user_id, track_id, allowed_bitrates, country, entitlement flags, device_id, exp.

Keep TTL short (5-10 minutes) to limit leakage risk.

## 7.3 Entitlement And Rights Service
Responsibilities:
- Determine whether user can play specific content in specific region.
- Validate subscription state and restrictions.
- Enforce policy for offline playback renewals.

Design:
- Source of truth in strongly consistent DB.
- Aggressive cache for `(user_plan, track_region)` decisions.
- Invalidation via change-data-capture and event-driven cache updates.

## 7.4 Catalog Service
Responsibilities:
- Manage track/album/artist metadata.
- Expose read-optimized APIs.
- Publish metadata changes to search indexing and recommendation pipelines.

Data:
- RDBMS for correctness.
- Redis read-through cache for hot entities.

## 7.5 Search Service
Design:
- OpenSearch/Elasticsearch indexes for tracks, artists, albums, playlists.
- Dedicated analyzers for multilingual tokenization.
- Ranking formula blends lexical relevance + popularity + personalization + availability.

Query strategy:
1. Retrieve top N lexical matches.
2. Filter by market/licensing.
3. Re-rank with personalization signals.

Autocomplete:
- Prefix trie or completion suggester index.
- Can keep top prefixes in Redis for very low latency.

## 7.6 Playlist Service (Collaborative)
Core challenge: concurrent edits.

Approach:
- Every playlist has `version`.
- Mutating requests include expected version.
- If mismatch, server can:
  - reject with conflict (client retries by refetching), or
  - merge safe operations (append/remove) while preserving causality.

For large scale:
- Keep per-playlist append log of operations.
- Materialize final order asynchronously.
- Idempotency key on every client mutation to avoid duplicate insertions.

## 7.7 Library Service
High-write, user-keyed workload.
- Store likes/follows/recently played in user-partitioned NoSQL.
- Keep small profile summary cache for quick home rendering.

## 7.8 Media Ingestion Pipeline
When label uploads master audio:
1. Validate file and metadata quality.
2. Transcode into multiple formats/bitrates.
3. Loudness normalization.
4. Fingerprinting, waveform generation, optional content checks.
5. Encrypt/package for streaming.
6. Write to object storage.
7. Publish media-ready event.
8. Warm CDN for trending candidates.

Pipeline is asynchronous and queue-driven for reliability and retries.

## 7.9 DRM And Key Management
- Media encrypted at rest and in delivery packaging.
- License server returns decryption keys to trusted clients.
- Keys never embedded in media files.
- Device policies can block rooted/jailbroken environments.

## 7.10 Recommendation Platform
Two-stage architecture:
1. Candidate generation:
   - collaborative filtering embeddings
   - content embeddings (audio/text metadata)
   - popularity/trending
   - editorial candidates
2. Ranking:
   - model scoring based on user context, recency, novelty, skip risk

Serving strategy:
- Home feed precomputed nearline.
- Session-based reranking online with fresh signals.

Feature freshness:
- real-time features (last 30 min behavior)
- batch features (7-day and 30-day affinities)

## 7.11 Connect Service (Cross-Device)
- Maintain active device sessions using WebSocket or MQTT.
- Each user has one active playback leader device; others are controllers.
- Commands are sequenced and ACKed to prevent out-of-order state.
- Heartbeats detect stale devices and clean sessions.

## 7.12 Ads Service (Free Tier)
- Ad decisioning between songs and at session start.
- Frequency capping and user targeting constraints.
- Impression and completion events tracked for billing.

Hard rule: ad insertion must not break playback continuity.

---

## 8. End-To-End Critical Flows

## 8.1 Play A Track (Online)
1. Client requests metadata and playback token.
2. Gateway authenticates user.
3. Playback Control calls Entitlement/Rights.
4. Service returns signed manifest URL + DRM license token.
5. Client fetches segments from nearest CDN edge.
6. Client sends playback events in batch every few seconds.
7. If network degrades, client lowers bitrate (ABR).

Failure fallback:
- If token issuance fails, retry with exponential backoff.
- If CDN edge misses, fetch from origin or adjacent PoP.
- If high bitrate unavailable, downgrade without stopping playback.

## 8.2 Download For Offline
1. Premium user requests offline authorization.
2. Server issues download token tied to user, device, expiration.
3. Client downloads encrypted media segments.
4. Client stores encrypted files and local manifest.
5. Playback offline requires valid local license.
6. License renewal required every N days (e.g., 30 days).

If premium expires, local licenses are not renewed.

## 8.3 Collaborative Playlist Edit
1. User A and B open playlist version 21.
2. A adds track X with expected version 21.
3. Server commits as version 22.
4. B deletes track Y with expected version 21.
5. Conflict detected; B gets 409 + latest version.
6. Client auto-retries with transformed operation.

## 8.4 Search Index Update
1. Catalog update event emitted (track renamed, rights changed).
2. Indexer consumes event and updates search index.
3. Cache invalidation event evicts stale metadata entries.
4. New results visible within target lag (for example < 60 sec).

---

## 9. Consistency, Idempotency, And Transactions

Consistency choices:
- Strong consistency required for:
  - subscription status
  - entitlement checks
  - payment-linked features
- Eventual consistency acceptable for:
  - recommendations
  - popularity counters
  - search indexing delay

Idempotency:
- All write APIs accept `Idempotency-Key`.
- Event consumers dedupe by `(event_id, source)`.

Transactions:
- Use DB transactions only for local bounded writes.
- Cross-service consistency via saga/event choreography.

---

## 10. Caching Strategy

Cache tiers:
1. Client cache:
   - track metadata, artwork, last playlists.
2. Edge cache (CDN):
   - media segments, artwork, static assets.
3. Service cache (Redis):
   - hot track metadata, rights snapshot, user profile summary.

Invalidation:
- Event-driven invalidation for metadata changes.
- Time-based TTL as safety net.

Hot key protection:
- Use request coalescing and per-key locks to avoid cache stampede.
- Keep top charts pre-warmed.

---

## 11. Data Partitioning And Scaling

### 11.1 User-Centric Data
Shard by `user_id` for library/history/preferences.
- Pros: locality for user home requests.
- Cons: celebrity user hotspot is uncommon, manageable.

### 11.2 Playlist Data
Shard by `playlist_id`.
- Large collaborative playlists may become hot partitions.
- Mitigations:
  - operation log split by time buckets
  - read replicas and cache
  - write rate limits for abusive automation

### 11.3 Event Stream
Partition by `user_id` or `session_id` to preserve ordering per user session.
- Ensure enough partitions for parallel consumer scaling.

### 11.4 Search Index Sharding
- Separate indexes by entity type and region if needed.
- Use alias-based reindexing for zero-downtime schema migrations.

---

## 12. Adaptive Bitrate (ABR) Strategy

Client computes next segment quality using:
- observed throughput over recent segment downloads
- current buffer length
- device constraints (CPU, decoder)
- user preference (data saver vs high quality)

Example policy:
- Buffer < 8s: drop one quality step.
- Buffer > 25s and throughput headroom > 30%: raise one step.
- Frequent switches damped by hysteresis to avoid oscillation.

Benefits:
- keeps playback smooth on variable mobile networks.

---

## 13. Recommendation System Design In Detail

## 13.1 Data Inputs
- Explicit: likes, follows, playlist adds, dislikes.
- Implicit: skips, completion rate, replay, dwell time.
- Context: local time, day-of-week, device, network, activity proxy.
- Content: audio embeddings, artist/genre taxonomy, NLP tags.

## 13.2 Offline Training
- Daily batch jobs compute:
  - user embeddings
  - track embeddings
  - similarity graph
  - long-term user taste vectors

## 13.3 Nearline Updates
- Stream processors update short-term session features.
- Recently skipped or recently overplayed tracks are downranked quickly.

## 13.4 Online Serving
- Candidate fetch from multiple sources (CF, content, trending, editorial).
- Rank with model.
- Post-process with business rules:
  - diversity constraints
  - artist repetition cap
  - freshness limits
  - explicit-content filters

## 13.5 Cold Start
- New user: bootstrap from onboarding genres/artists.
- New track: leverage content embeddings + editorial seeding.

## 13.6 Evaluation
Offline metrics:
- NDCG, MAP, recall@K.

Online metrics:
- session length
- save rate
- skip rate
- day-7 retention uplift

---

## 14. Royalties And Reporting

Each qualifying play must be auditable.
Pipeline:
1. Ingest raw playback events.
2. Sessionize and dedupe.
3. Apply policy (minimum seconds threshold, fraud checks).
4. Aggregate by region, track, rights holder, plan type.
5. Export monthly statement and billing reports.

Data quality controls:
- late event handling window
- exactly-once semantics for payout aggregates
- immutable ledger tables for audit

---

## 15. Security, Privacy, And Compliance

Security controls:
- TLS everywhere.
- Signed short-lived playback URLs.
- Token binding to user/device where feasible.
- WAF + bot detection.
- Secret management via KMS/HSM.

Privacy and compliance:
- Data minimization for analytics payloads.
- User data export and deletion workflows.
- Region-specific storage and access controls.
- Access auditing for internal tools.

Anti-abuse:
- detect scripted playback farms and fake streams.
- anomaly detection on device fingerprints, session patterns, and geo behavior.

---

## 16. Reliability And Disaster Recovery

## 16.1 Multi-Region Design
- Control plane: active-active across at least two regions.
- Data stores:
  - metadata DB with cross-region replicas and controlled failover
  - caches regional with eventual refill
  - object storage multi-region replication
- CDN provides global edge resilience.

## 16.2 Failure Scenarios
1. Region outage:
   - shift traffic at gateway/global LB.
   - use secondary region for control APIs.
2. Search cluster degradation:
   - fallback to cached/trending results.
3. Recommendation service down:
   - fallback to precomputed static mixes/top charts.
4. Event bus lag spike:
   - prioritize critical topics, autoscale consumers, preserve durability.
5. Rights service unavailable:
   - fail closed for restricted content, fail open only for explicitly safe caches.

## 16.3 Deployment Safety
- Blue/green or canary deployments.
- Feature flags for risky changes.
- Automatic rollback on SLO regression.

---

## 17. Observability And SLOs

Golden signals per service:
- latency (P50/P95/P99)
- traffic
- errors
- saturation

Playback-focused metrics:
- first-audio-byte latency
- rebuffer ratio
- average bitrate delivered
- playback failure rate
- DRM license fetch latency

Data platform metrics:
- ingestion lag
- consumer lag
- drop/duplication rate
- feature freshness lag

Example SLOs:
- Playback token API success >= 99.99%
- First audio byte P99 <= 300 ms
- Rebuffer ratio <= 0.5%
- Search P99 <= 150 ms

Alerting:
- Multi-window burn-rate alerts for SLO breaches.

---

## 18. Cost Optimization Levers

1. Maximize CDN cache hit ratio (top content pre-warm, tune cache keys).
2. Use cheaper cold storage tiers for infrequently played tracks.
3. Right-size transcoding and ML batch clusters (spot/preemptible where safe).
4. Control high-cardinality telemetry volume via smart sampling.
5. Compress events and batch client uploads.
6. Separate compute for free vs premium workloads if economics differ.

---

## 19. Major Trade-Offs And Why

1. RDBMS vs NoSQL for playlists:
- RDBMS gives transactions but may struggle at massive write fanout.
- NoSQL gives scale; conflict management shifts to app layer.
- Chosen hybrid: metadata relational, high-write user artifacts in NoSQL.

2. Precompute recommendations vs fully online:
- Fully online is fresh but expensive and latency-sensitive.
- Precompute is cheap/stable but less fresh.
- Chosen blend: nearline precompute + online rerank.

3. Strict consistency everywhere vs selective:
- Strict globally increases latency and cost.
- Eventual acceptable for non-critical surfaces.
- Chosen: strict only where correctness/business risk demands.

4. Single region vs multi-region active-active:
- Single region simpler and cheaper.
- Active-active improves uptime and global latency but complex data semantics.
- At Spotify scale, active-active is justified.

---

## 20. Interview-Ready Deep Dive Talking Points

If interviewer asks "how does playback stay fast?":
- Emphasize control-plane/data-plane split.
- Signed URL issuance is cheap; media bytes come from CDN edge.
- ABR + prefetch + short segments minimize stalls.

If interviewer asks "how to prevent playlist edit conflicts?":
- Versioned writes, conflict detection, retry/merge policy, idempotency keys.

If interviewer asks "how to scale recommendations?":
- Multi-stage retrieval/ranking pipeline, batch + nearline feature updates, A/B validation.

If interviewer asks "how do royalties stay correct?":
- Immutable event log, dedupe/sessionization, payout ledger with auditability.

If interviewer asks "what fails first at scale?":
- Cache stampede, hot partitions, event lag, rights service dependency.
- Show concrete mitigations for each.

---

## 21. Possible 45-Minute Interview Narrative

1. Minutes 0-5:
   - Clarify scope, SLOs, and product constraints.
2. Minutes 5-12:
   - APIs and core entities.
3. Minutes 12-20:
   - High-level architecture and playback flow.
4. Minutes 20-28:
   - Data stores, sharding, caching, consistency.
5. Minutes 28-35:
   - Recommendations and event pipeline.
6. Minutes 35-42:
   - Reliability, security, trade-offs.
7. Minutes 42-45:
   - Extensions and cost optimization.

---

## 22. Extensions You Can Mention If Time Permits

- Podcasts and video podcasts with dynamic ad insertion.
- Social graph feed (friends listening now).
- Live audio sessions.
- Creator analytics portal with near-real-time dashboards.
- HiFi/lossless tier with different encoding and CDN economics.

---

## 23. Final Summary
This architecture scales by separating heavy audio delivery from control APIs, using CDN-first streaming, user-centric data partitioning, event-driven analytics/ML pipelines, and selective consistency. It balances low latency, high availability, rights compliance, recommendation quality, and cost efficiency.

For SDE3 interviews, the strongest differentiator is not naming technologies; it is explaining why each design choice is made, what can fail, and how you contain blast radius.
