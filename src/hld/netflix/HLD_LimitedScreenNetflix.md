# 🎬 High-Level Design (HLD) — Netflix (Video Streaming + Concurrent Screen Limiting)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Concurrent Stream Limiting, Session Management, Adaptive Streaming (HLS/DASH), CDN, Recommendation Engine, Encoding Pipeline

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **stream movies and TV shows** on multiple devices.
- **Limit concurrent streams** per subscription plan:
  | Plan | Max Concurrent Screens |
  |---|---|
  | Standard (SD) | 1 |
  | Standard (HD) | 2 |
  | Premium (4K) | 4 |
- Users can view and **manage active sessions** (kick a device).
- **Adaptive bitrate streaming** — auto-adjusts quality based on bandwidth.
- **Profiles** — up to 5 sub-profiles per account.
- **Content recommendation** — personalized homepage per profile.
- **Download for offline** (mobile — Premium plan).
- **Continue Watching** — resume from last watched position across devices.
- **Search** across titles, genres, actors.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Stream start latency** | < 2 sec to first frame |
| **Buffering rate** | < 0.5% of streaming sessions |
| **Concurrent stream enforcement** | < 500 ms to reject an over-limit session |
| **Screen limit accuracy** | Zero tolerance — no plan can exceed its screen limit |
| **Availability** | 99.99% for streaming; CDN self-heals |
| **Scale** | 260M subscribers, 100M+ concurrent streams at peak |
| **Consistency for screen limits** | Strong — must prevent over-limit, not eventually consistent |

---

## 2. Capacity Estimation

```
Subscribers           = 260 million
Concurrent streams    = 100 million at peak (weekday evening)
Stream start events   = ~5M/hr during peak → ~1,400/sec
Heartbeats/sec        = 100M streams × 1 hb/30sec = ~3.3M heartbeats/sec
Content library       = 36,000 titles → multiple resolutions → ~1 Exabyte
Avg video bitrate     = 5 Mbps (HD) → 100M streams × 5 Mbps = 500 Tbps peak CDN bandwidth
Recommendations read  = 100M pages/day → ~1,200/sec
```

---

## 3. High-Level Architecture

```
 Client (TV / Mobile / Web / Console)
        │
        ├── Login / Profile select ────────────────▶ API Gateway (Auth)
        ├── Browse / Search / Recommend ───────────▶ API Gateway
        ├── Play request ──────────────────────────▶ API Gateway
        │                                                  │
        │               ┌───────────────────────────────────┤
        │               │               │                   │
        │     ┌─────────▼───────┐  ┌────▼─────────┐  ┌─────▼──────────┐
        │     │  Stream Session │  │  Content     │  │  Recommendation │
        │     │  Service        │  │  Catalog     │  │  Engine         │
        │     │  (screen limit  │  │  Service     │  │                 │
        │     │   enforcement)  │  └──────────────┘  └─────────────────┘
        │     └─────────┬───────┘
        │               │ Approved → issue streaming token
        │               ▼
        │     ┌─────────────────┐
        │     │  Playback       │
        │     │  Service        │
        │     │  (CDN URL gen,  │
        │     │   DRM license)  │
        │     └─────────────────┘
        │
        └── GET video chunks ──────────────────────▶ CDN Edge (Open Connect)
                                                      (video bytes served directly)
```

---

## 4. ⭐ Concurrent Screen Limiting — The Core Design Problem

This is the **primary interview focus**. Preventing a user from exceeding their plan's screen limit requires strong consistency and low latency enforcement.

### 4.1 The Problem

```
User has Premium plan (4 screens allowed).
Currently: Screen A, B, C are actively streaming (3 active).
Screen D attempts to start → should be ALLOWED (4th screen).
Screen E attempts to start → should be REJECTED (5th screen).

Challenges:
  - 100M concurrent streams → high write throughput
  - Distributed system → must coordinate across all nodes
  - Must be strongly consistent — two screens can't simultaneously "win" the 4th slot
  - Heartbeat-based liveness — stale sessions must be evicted automatically
```

### 4.2 Data Model for Active Sessions

```
Redis Hash per account (source of truth for active sessions):

Key:   active_sessions:{accountId}
Type:  Redis HASH
Field: sessionId (UUID)
Value: { profileId, deviceId, deviceType, startedAt, lastHeartbeat, title }

Example:
  HGETALL active_sessions:ACC_1234
  → {
      "sess_abc": { profileId: P1, deviceId: "iPhone12", lastHeartbeat: 1741201800 },
      "sess_def": { profileId: P2, deviceId: "Samsung TV", lastHeartbeat: 1741201795 },
      "sess_ghi": { profileId: P1, deviceId: "iPad", lastHeartbeat: 1741201790 }
    }

Plan limit stored in:
  HGET account:{accountId} max_screens → 4
```

### 4.3 Stream Start — Atomic Enforcement with Lua Script

**The critical operation must be atomic — check count + add session in one Redis round-trip.**

```lua
-- Redis Lua Script: atomically check limit and add session
-- KEYS[1] = active_sessions:{accountId}
-- KEYS[2] = account:{accountId}
-- ARGV[1] = sessionId
-- ARGV[2] = sessionData (JSON)
-- ARGV[3] = current timestamp

local sessionKey = KEYS[1]
local accountKey = KEYS[2]
local sessionId  = ARGV[1]
local sessionData = ARGV[2]
local now        = tonumber(ARGV[3])
local HEARTBEAT_TIMEOUT = 60   -- seconds; session considered stale

-- Step 1: Evict stale sessions (no heartbeat in 60 sec)
local allSessions = redis.call('HGETALL', sessionKey)
for i = 1, #allSessions, 2 do
    local sid  = allSessions[i]
    local data = cjson.decode(allSessions[i+1])
    if (now - data.lastHeartbeat) > HEARTBEAT_TIMEOUT then
        redis.call('HDEL', sessionKey, sid)
    end
end

-- Step 2: Count remaining active sessions
local activeCount = redis.call('HLEN', sessionKey)

-- Step 3: Get plan limit for this account
local maxScreens = tonumber(redis.call('HGET', accountKey, 'max_screens'))

-- Step 4: Enforce limit
if activeCount >= maxScreens then
    return {0, activeCount}   -- REJECTED: limit reached
end

-- Step 5: Add new session atomically
redis.call('HSET', sessionKey, sessionId, sessionData)
return {1, activeCount + 1}   -- SUCCESS
```

**Why Lua?**
- Redis executes the entire script atomically — no race condition possible.
- Two devices starting simultaneously cannot both "win" the last slot.
- Single round-trip to Redis → < 5 ms enforcement latency.

### 4.4 Heartbeat Mechanism (Session Liveness)

```
While streaming, client sends heartbeat every 30 sec:
  POST /heartbeat { sessionId, accountId, positionSec }
          │
          ▼
  Session Service:
    HSET active_sessions:{accountId} {sessionId}
         '{"lastHeartbeat": now, "positionSec": 1234}'

If heartbeat stops (client crashes, device off, network lost):
  Session becomes stale after 60 sec (detected in Lua on next play attempt)
  OR background cleanup job runs every 60 sec and evicts stale sessions

Heartbeat also serves as watch progress update:
  → Kafka: HeartbeatEvent → WatchHistory Service → "Continue Watching" position
```

### 4.5 Stream Stop — Session Cleanup

```
User presses STOP / closes app:
  POST /stream/stop { sessionId, accountId }
          │
          ▼
  HDEL active_sessions:{accountId} {sessionId}
  Kafka: StreamStopped → analytics, recommendations update
```

### 4.6 Active Session Management (Manage Devices UI)

```
GET /sessions → List all active sessions for account
  HGETALL active_sessions:{accountId}
  Enrich with device names, profile names → return to user

User kicks a device:
  DELETE /sessions/{sessionId}
  → HDEL active_sessions:{accountId} {sessionId}
  → Invalidate streaming token for that session (DRM license revoked)
  → Kicked device's next video chunk request fails DRM → player stops

Kicked device experience:
  Every 30 sec: player validates DRM license
  On revocation → player shows: "Your session was ended on another device"
```

---

## 5. Streaming Token & DRM Flow

```
Stream Start Approved (Lua script returned success):
        │
        ▼
Playback Service:
  1. Generate streaming token (JWT):
     { accountId, sessionId, contentId, expiresAt: now + 4hr, quality: "4K" }
     Signed with Playback Service private key

  2. Issue DRM License (Widevine for Android/Web, FairPlay for iOS):
     Content key fetched from KMS
     License duration: 4 hr (client must renew; renewal checks active session still valid)

  3. Return to client:
     { streamingToken, drmLicense, cdnManifestUrl }

Client → CDN:
  GET /manifest.m3u8?token={streamingToken}
  CDN validates JWT (public key) → serves video chunks
  No Playback Service hit per chunk → CDN fully autonomous

DRM License Renewal (every 4 hr):
  Client calls Playback Service with sessionId
  → Verify session still active (HEXISTS active_sessions:{accountId} {sessionId})
  → If kicked → refuse renewal → player stops
```

---

## 6. Video Encoding Pipeline

```
Content team uploads raw master video (ProRes / RAW)
        │
        ▼
Netflix Encoding Farm (Distributed):
  Per-scene encoding optimization (Netflix's Dynamic Optimizer):
    Complex scenes (action) → higher bitrate
    Simple scenes (dialogue) → lower bitrate
    Result: 20% better quality at same file size vs uniform bitrate

  Output profiles (per title):
    4K HDR:   15–20 Mbps (H.265/HEVC)
    1080p:    5.8 Mbps   (H.264 / AV1)
    720p:     2.35 Mbps
    480p:     720 Kbps
    360p:     340 Kbps

  Video split into 4–10 sec segments (CMAF chunks for DASH/HLS compatibility)
        │
        ▼
S3 / Netflix-owned CDN origin storage (multiple regions)
        │
        ▼
Open Connect Appliances (OCA) — Netflix's own CDN:
  Custom hardware deployed in ISP data centers globally (~1,000+ PoPs)
  Popular titles pre-loaded nightly during off-peak hours
  95%+ of Netflix traffic served from OCA (zero Netflix-origin bandwidth)
```

### 6.1 Adaptive Bitrate (ABR) Streaming

```
Client player (Netflix VMAF-optimized player):
  Initialization: probe bandwidth → estimate available throughput
  Start: request lowest quality segment for fast startup
  Ramp up: as buffer fills (> 30 sec), upgrade quality
  Adapt: monitor segment download time vs segment duration
         if downloadTime > 80% of segmentDuration → downgrade

Buffer thresholds:
  < 5 sec  → emergency downgrade to 360p / lowest available
  5–15 sec → hold current quality
  > 30 sec → upgrade one level

Pre-fetch:
  While playing current segment → download next 3 segments (30 sec buffer)
  User seeking: pause pre-fetch, request segment at seek point, resume

Quality per second metric (VMAF):
  Netflix uses VMAF (Video Multi-method Assessment Fusion) not just bitrate
  Optimizes for perceived quality, not just kbps
```

---

## 7. Content Catalog & Metadata Service

```
PostgreSQL (master catalog — strongly consistent):
  titles, seasons, episodes, genres, cast, ratings, available_regions

Redis (hot metadata cache — 99% of reads):
  Key:  title:{contentId}
  TTL:  12 hr
  Data: title, description, poster_url, genres, average_rating, maturity_rating

Elasticsearch:
  Full-text search: title, description, cast, genre
  Filters: release_year, language, maturity_rating, duration
  Real-time index on new title addition (Kafka → ES consumer)

Image/Artwork CDN:
  Personalized artwork: A/B tested thumbnails per user segment
  Optimized for CTR: ML-selected thumbnail based on viewing history
```

---

## 8. Recommendation Engine

```
Two-stage architecture:

Stage 1: Candidate Generation (~1,000 titles)
  Sources:
  a) Collaborative filtering (users with similar taste)
  b) Content-based (similar genre/cast/director to watched)
  c) Continue Watching (in-progress titles)
  d) Trending in user's region
  e) Newly added matching user's preferences

Stage 2: Ranking (1,000 → top 40 rows × 10 titles per row)
  Netflix Recommendation Model (deep neural network):
  Features:
    - User: viewing history embeddings, time-of-day, device type
    - Content: genre, rating, engagement rate in similar cohort
    - Context: days since last login, subscription duration

  Row-level grouping:
    "Because you watched Squid Game: [thriller row]"
    "Top Picks for [ProfileName]: [personalized row]"
    "Trending in India: [regional row]"

Offline batch (Spark): weekly retraining of base model
Online: per-session feature updates (last 3 watched, time of day)
```

---

## 9. Watch History & Continue Watching

```
Heartbeat: { sessionId, contentId, profileId, positionSec } every 30 sec
  → Kafka: WatchProgress
  → Flink: latest position per (profileId, contentId)
  → Cassandra:

CREATE TABLE watch_history (
    profile_id  UUID,
    content_id  BIGINT,
    position_sec INT,
    watched_at   TIMESTAMP,
    completed    BOOLEAN,
    PRIMARY KEY (profile_id, content_id)
);

Continue Watching row:
  Query: SELECT * FROM watch_history WHERE profile_id = ? AND completed = false
         ORDER BY watched_at DESC LIMIT 20
  Served from: Redis ZSET (score = watched_at) for < 5ms response

Cross-device resume:
  Device A pauses at 45:32
  → Heartbeat writes 45:32 to Cassandra
  Device B opens same show → fetches position 45:32 → offers "Resume from 45:32"
```

---

## 10. Offline Download (Mobile)

```
Premium user downloads episode:
  1. App requests download token from Playback Service
     → Verify Premium plan
     → Verify device is registered (max 4 download devices)
     → Issue offline DRM license (Widevine Offline / FairPlay Persistent)
     License TTL: 30 days (must be online within 30 days to renew)

  2. App downloads encrypted video file from CDN → local storage
     File remains encrypted → useless without DRM license key

  3. On playback: decrypt locally using license key

  4. License expiry events:
     → Netflix server marks download license as expired
     → On next app open (internet): client checks license validity
     → If expired: content marked unplayable; re-download required

Download limit:
  Redis: downloads:{accountId}:{deviceId} → count
  Max 25 downloads per device
  INCR on download; DECR on deletion; check before issuing license
```

---

## 11. Failure Handling & Edge Cases

| Scenario | Handling |
|---|---|
| **Redis node failure during stream start** | Redis Cluster replica promotion; brief retry (< 1 sec); Lua script retried |
| **Heartbeat lost (network blip)** | 60-sec grace period before session considered stale; player retries heartbeat |
| **Both devices start simultaneously (race)** | Lua script is atomic — exactly one wins the last slot; other gets 429 |
| **User on VPN (geo-bypass)** | IP geo-check + billing address cross-validate; restrict if mismatch |
| **CDN PoP failure** | Client falls back to next nearest PoP; OpenConnect has 1,000+ PoPs |
| **Content key (DRM) service down** | Cached license valid for 4 hr; user impact only on new session start |
| **Account shared across locations (Netflix crackdown)** | Household IP verification; prompt to add device as "Extra Member" |

---

## 12. Netflix's Paid Sharing / Household Verification

```
Netflix's real-world approach (2023 rollout):
  "Household" = the network where the account owner primarily streams

Verification mechanism:
  Primary device connects from home IP → "home IP" registered
  Device connects from different IP:
    → If trusted device (previously verified) → allow with screen limit
    → If new location → prompt: "Verify you're part of this household"
    → Email verification code / connect to home WiFi to verify

Technical signals for household detection:
  - Public IP address of device
  - WiFi network SSID (mobile app)
  - Device registration history
  - Geolocation consistency

Extra Member (paid add-on):
  Account holder pays extra → one additional person outside household
  Gets own login + separate profile in household's plan
```

---

## 13. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Screen limit enforcement** | Redis Lua (atomic check + add) | Strong consistency; Redis SPOF mitigated by Cluster |
| **Session liveness** | Heartbeat every 30 sec + 60 sec TTL | 60-sec grace avoids false eviction on network blip |
| **Concurrency control** | Lua script (no distributed lock) | No deadlock; single Redis round-trip; eventual cleanup |
| **CDN** | Netflix Open Connect (own CDN) | ~95% traffic at ISP level; massive cost saving vs public CDN |
| **Encoding** | Per-scene dynamic optimizer (AV1) | 20% quality gain; expensive encoding compute |
| **DRM** | Widevine + FairPlay (per-session license) | Kick = revoke license; 4hr window on network loss |
| **Heartbeat store** | Redis HASH (per account) | Account-level view; efficient for session management page |
| **Watch progress** | Cassandra (upsert) | High write throughput; eventual cross-device consistency acceptable |

---

## 14. Monitoring & Observability

| Metric | Alert |
|---|---|
| **Stream start latency** | P99 > 3 sec |
| **Screen limit enforcement latency** | P99 > 500 ms |
| **Heartbeat success rate** | < 99.5% (network issues) |
| **Buffering ratio** | > 0.5% of sessions → CDN issue |
| **Stale session eviction count** | Spike → client-side crash wave |
| **DRM license failure rate** | > 0.1% → KMS issue |
| **Redis memory** | Active sessions > 80% capacity |

---

## 15. Future Enhancements
- **Live events** (sports) — ultra-low latency mode (< 5 sec) via WebRTC / LHLS.
- **Interactive content** — Bandersnatch-style choose-your-own-adventure (session state per choice).
- **Spatial Audio / Dolby Atmos** — separate audio track per user's audio config.
- **AI-generated trailers** — personalized 30-sec clip highlights based on viewer's taste.

---

*Document prepared for SDE 3 system design interviews. PRIMARY focus: concurrent screen limiting via Redis Lua atomic scripts + heartbeat-based session liveness. Secondary: HLS/DASH adaptive streaming, Open Connect CDN, per-scene dynamic encoding, DRM license management, and recommendation engine.*
