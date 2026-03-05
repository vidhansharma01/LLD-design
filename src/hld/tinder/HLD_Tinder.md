# 💕 High-Level Design (HLD) — Tinder (Dating / Matchmaking App)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Swipe Recommendation (Discovery), Matching Algorithm, Real-time Chat, Geo-proximity, Profile Ranking, Privacy

---

## 1. Requirements

### 1.1 Functional Requirements
- Users create a **profile** (photos, bio, age, preferences).
- Users see a **swipe deck** — a stack of recommended profiles based on location, preferences, and compatibility.
- Users **swipe Right (Like)** or **Left (Pass)** on profiles.
- If both users swipe right → **IT'S A MATCH** — both notified.
- Matched users can **send messages** (text, GIFs, photos).
- **Discovery filters**: age range, distance, gender.
- Support **Boost** (paid — profile shown 10× more for 30 min).
- Support **Super Like** (paid — special notification to the other person).
- **Block / Report** other users.
- Support **Gold / Plus / Platinum** subscription tiers with premium features.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Swipe deck latency** | < 200 ms to load next 10 profiles |
| **Match notification** | < 1 sec after mutual swipe |
| **Chat message delivery** | < 500 ms (real-time feel) |
| **Availability** | 99.99% for swipe/match; 99.9% for chat |
| **Scale** | 75M DAU, 1.6B swipes/day, 26M matches/day |
| **Privacy** | Exact location never shared; only approximate distance shown |
| **Geo range** | Discovery within configurable radius (1–100 km) |

### 1.3 Out of Scope
- Video calling (Tinder Video — separate service)
- Safety features (background checks)
- Passport (swipe in different cities — Premium tier; same core architecture)

---

## 2. Capacity Estimation

```
DAU                    = 75 million
Swipes/day             = 1.6 billion → ~18,500/sec avg, ~100K/sec peak
Matches/day            = 26 million → ~300/sec
Messages/day           = 500 million → ~5,800/sec
Profile photos         = avg 5 per user → 75M × 5 = 375M photos → ~750 GB (thumbnails)
Geolocation updates    = 75M users × 1 update/30 min = ~40K writes/sec
Discovery deck per user = 10 profiles pre-fetched; refreshed every ~5 swipes
Swipe events/sec       = 18,500 writes to swipe store
```

---

## 3. High-Level Architecture

```
 User App (iOS / Android)
        │
        ├── Load swipe deck ────────────────────────────▶ API Gateway
        ├── Swipe Left / Right ─────────────────────────▶ API Gateway
        ├── Chat message ───────────────────────────────▶ API Gateway
        └── Update location ────────────────────────────▶ API Gateway
                                                                │
                   ┌────────────────────────────────────────────┤
                   │                    │                        │
          ┌────────▼────────┐  ┌────────▼────────┐  ┌──────────▼──────────┐
          │  Discovery      │  │  Swipe /        │  │  Chat               │
          │  Service        │  │  Match Service  │  │  Service            │
          │  (deck gen.)    │  │                 │  │  (WebSocket)        │
          └────────┬────────┘  └────────┬────────┘  └──────────┬──────────┘
                   │                    │                        │
          ┌────────▼──────┐    ┌────────▼──────┐    ┌──────────▼──────────┐
          │  Redis        │    │  Swipe DB     │    │  Message DB         │
          │  (location,   │    │  (Cassandra)  │    │  (Cassandra)        │
          │   deck cache, │    │               │    │                     │
          │   matches)    │    └───────────────┘    └─────────────────────┘
          └───────────────┘
                   │
          ┌────────▼──────────────────────┐
          │  Profile Service              │
          │  (PostgreSQL + S3 for photos) │
          └───────────────────────────────┘
                   │
          ┌────────▼──────────────────────┐
          │  Recommendation Engine        │
          │  (ELO score, ML ranking)      │
          └───────────────────────────────┘
                   │
          ┌────────▼──────────────────────┐
          │  Kafka Event Bus              │
          │  (SwipeRight, Match,          │
          │   MessageSent, LocationUpdate)│
          └───────────────────────────────┘
```

---

## 4. Core Components

### 4.1 Discovery Service — Building the Swipe Deck

**The key product experience.** When a user opens Tinder, they need to see ~10 relevant profiles instantly.

**Two-stage pipeline:**

```
Stage 1: Candidate retrieval (thousands of candidates → ~500)
  Filter by:
  a) Location: within user's distance preference (e.g., 50 km)
     Redis GEO: GEORADIUS users:{cityId} <lng> <lat> 50 km COUNT 2000
  b) Age: within preference range (e.g., 25–35)
  c) Gender preference
  d) Not already swiped (exclude seen profiles)
  e) Not blocked
  → Result: ~500 eligible candidates

Stage 2: Ranking (500 → top 10 to show)
  Score each candidate using:
  - ELO score (profile attractiveness / desirability signal)
  - Mutual interest score (would they likely swipe right on me?)
  - Activity recency (active users ranked higher — avoid serving inactive profiles)
  - Photo quality score (ML face detection + composition scoring)
  - Preference match degree (age gap, verified profile, etc.)
  → Return top 10 ranked profiles for swipe deck
```

**Pre-computation strategy:**
- Deck is pre-built and cached per user (Redis LIST).
- Refreshed every time user swipes through 5 profiles (background prefetch).
- On app open: deck is already ready → < 200 ms.

### 4.2 ELO Rating System (Profile Desirability)

Tinder's (formerly public) approach to profile ranking — inspired by chess ELO.

```
Every swipe is a "match" signal:
  Right swipe on User B by User A → A "challenged" B
  B's ELO score depends on A's own ELO:

  Outcome: A liked B
    If A has HIGH ELO (attractive): B's ELO increases (high-quality person liked me)
    If A has LOW ELO: B's ELO increases less

ELO update formula:
  expected_score_A = 1 / (1 + 10^((elo_B - elo_A) / 400))
  new_elo_B = elo_B + K × (1 - expected_score_A)   // 1 = A liked B (win for B)
  K = 32 (adjustment factor)

ELO stored per user in PostgreSQL; updated asynchronously via Kafka:
  SwipeRight event → Kafka → ELO Updater Service → batch updates every 5 min
```

**Note:** Tinder no longer publicly confirms ELO but some desirability-based ranking is strongly implied by their patents and engineering blog posts.

### 4.3 Location Service & Privacy

```
User opens app → Client sends location update:
  POST /location { lat, lng }  (approximate — not exact street address)
        │
        ▼
Location Service:
  Store in Redis GEO:
  GEOADD users:{cityId} <lng> <lat> <userId>
  HSET user_meta:{userId} last_location_update <now>

Privacy:
  NEVER store exact location in profile
  NEVER show exact location in discovery
  Only "X km away" computed at query time from Redis GEO distance
  
Location update frequency:
  Foreground: every 10 min (reasonable battery impact)
  Background: every 30 min
  On login: immediate update

Location expiry:
  If no update in 24 hr → exclude from geo-radius results (user offline)
  Redis TTL: EXPIREAT user_location:{userId} now + 24hr
```

### 4.4 Swipe Processing & Match Detection

```
User swipes RIGHT on Profile B:

1. Client: POST /swipe { targetUserId: B, direction: RIGHT }

2. Swipe Service:
   a) Write swipe to Cassandra (async, eventually consistent):
      INSERT INTO swipes (user_id, target_id, direction, swiped_at) ...

   b) Check if B already swiped RIGHT on A:
      Redis: SISMEMBER right_swipers:{B} {A}   → O(1) check
      If YES → IT'S A MATCH!

   c) Add A to B's right_swipers set:
      SADD right_swipers:{A} {B}    (keep for future match detection)

3. On MATCH:
   → Create match record in Matches DB
   → SADD matches:{A} {B}  and  SADD matches:{B} {A}  (Redis SETs)
   → Publish MatchCreated event → Kafka → Notification Service
   → Push notification to both users immediately (< 1 sec)
   → Unlock chat between A and B

4. Return swipe result to client:
   { match: true/false, matchId: "..." }
```

**Why Redis for right-swipers check?**
```
Cassandra write is async → can't reliably query "did B swipe right on A?" at swipe time
Redis SET SISMEMBER → O(1), sub-millisecond
Redis SET SADD → O(1), sub-millisecond
The match detection hot-path must be < 5ms total → Redis is the only option
```

**Memory estimation for Redis right-swiper sets:**
```
75M DAU, avg 100 right swipes per user in last 90 days
75M users × 100 user-IDs × 8 bytes = ~60 GB per 90-day window
→ Fits in Redis Cluster (TTL = 90 days; discard old swipe signals)
```

### 4.5 Chat Service (Message Delivery)

```
Only matched users can chat. Match → chat unlocked.

Message flow:
  User A sends "Hey! 👋" to User B
  Client → WebSocket → Chat Service
                │
  Store message in Cassandra:
    INSERT INTO messages (match_id, sender_id, msg_id, content, sent_at) ...
                │
  Push to User B's WebSocket (if online):
    PUBLISH chat:{B} { matchId, senderId, content, msgId }  → Redis PubSub
    → Chat Service node holding B's connection delivers message
                │
  If B is offline:
    Store in unread_{B} Redis list
    Send push notification (APNs / FCM):
    "User A sent you a message: Hey! 👋"
                │
  B opens app → WebSocket connect → receive missed messages via REST:
    GET /messages/{matchId}?after={lastSeenMsgId}
```

**Message storage (Cassandra):**
```sql
CREATE TABLE messages (
    match_id    UUID,
    msg_id      TIMEUUID,     -- time-sortable, globally unique
    sender_id   UUID,
    content     TEXT,
    media_url   TEXT,         -- for GIFs, photos
    msg_type    TEXT,         -- TEXT | GIF | IMAGE | REACTION
    sent_at     TIMESTAMP,
    PRIMARY KEY (match_id, msg_id)
) WITH CLUSTERING ORDER BY (msg_id DESC);
-- Partition by match_id → all messages for a conversation co-located
-- TIMEUUID ensures chronological order within partition
```

### 4.6 Profile Service

```sql
-- PostgreSQL
CREATE TABLE profiles (
    user_id         UUID PRIMARY KEY,
    name            TEXT,
    birth_date      DATE,
    gender          TEXT,
    looking_for     TEXT[],     -- ['woman', 'man', 'nonbinary']
    bio             TEXT,       -- max 500 chars
    job_title       TEXT,
    company         TEXT,
    school          TEXT,
    elo_score       DOUBLE,
    is_verified     BOOLEAN,    -- blue tick (gov ID verified)
    is_premium      BOOLEAN,
    subscription    TEXT,       -- FREE | PLUS | GOLD | PLATINUM
    min_age_pref    INT,
    max_age_pref    INT,
    max_distance_km INT,
    created_at      TIMESTAMP,
    last_active_at  TIMESTAMP
);

CREATE TABLE profile_photos (
    user_id         UUID,
    photo_order     INT,
    s3_url          TEXT,
    thumbnail_url   TEXT,
    is_primary      BOOLEAN,
    PRIMARY KEY (user_id, photo_order)
);
```

**Photo pipeline:**
```
User uploads photo → S3 (pre-signed URL direct upload)
        │
Lambda trigger → Image Processing:
  Resize: original + thumbnail (200×200) + card (400×500)
  Face detection: verify at least one face present
  NSFW check: ML classifier → auto-reject explicit content
  CDN: all sizes pushed to CloudFront
```

### 4.7 Boost & Super Like (Premium Features)

```
BOOST (10× visibility for 30 min):
  → User activates Boost
  → Set Redis key: boost:{userId} = 1 EX 1800 (30 min TTL)
  → Discovery Service: if boost:{userId} exists → multiplier 10× on ELO score
     → Profile appears ~10× more in other users' discovery decks

SUPER LIKE:
  → Swipe RIGHT with Super Like flag
  → Target user sees a special blue star on swiper's profile
  → When target user sees superliked profile in deck → highlighted
  → If target swipes right back → MATCH (same as regular match)
  Stored: SADD super_likers:{target} {userId}
  → Deck enrichment: check if a profile in deck has super-liked me → flag it
```

---

## 5. Swipe Exhaustion (No Profiles Left)

```
Tinder Free: limited to 100 right swipes per 12 hours
  → Redis counter: INCR right_swipes:{userId}:{12hr_window}
  → If count > 100 → return 429 with "Out of Likes" screen

Discovery deck exhausted (all nearby profiles seen):
  → Expand search radius by 10 km
  → Relax age preference slightly (configurable)
  → Surface profiles from "Explore" tab (global trending)
  → Show "You've seen everyone in your area" prompt

User hasn't been swiped in X days:
  → Temporarily re-introduce to other users' decks (fresh start signal)
```

---

## 6. Data Flow — Swipe Right → Match → Chat

```
User A swipes RIGHT on User B

POST /swipe { targetId: B, direction: RIGHT }
        │
        ▼
Swipe Service:
  SISMEMBER right_swipers:{B} A  → true (B already liked A!)
        │ YES → IT'S A MATCH
        ▼
  SADD matches:{A} B
  SADD matches:{B} A
  INSERT INTO matches (match_id, user1, user2, matched_at) ...
  DELETE right_swipers:{A} A  (cleanup)
        │
  Kafka: MatchCreated { matchId, userA, userB }
        │
  Notification Service:
    Push to A: "You matched with [B's name]! 🎉"
    Push to B: "You matched with [A's name]! 🎉"
    (< 500ms from swipe to notification)
        │
  Client response: { match: true, matchId, profileB: {...} }
  Confetti animation in app 🎊

User A opens chat → types "Hey!"
  WebSocket → Chat Service
  → Store in Cassandra
  → Deliver to B via WebSocket / Push
```

---

## 7. Privacy & Safety

| Feature | Implementation |
|---|---|
| **No exact location** | Only distance in km shown in discovery |
| **Location data** | Never stored in profile; ephemeral in Redis (24hr TTL) |
| **Block user** | SADD blocked:{A} {B}; excluded from geo-radius results |
| **Report user** | Moderation queue; repeated reports → human review |
| **Unmatch** | Delete match record; revoke chat access; remove from each other's match lists |
| **Photo Safety** | NSFW ML scan on upload; face detection required |
| **Incognito mode** | (Gold+) Only show to users you've already swiped right on |

---

## 8. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Match detection** | Redis SISMEMBER (O(1)) | Sub-ms check; requires 60 GB Redis for swipe history |
| **Location store** | Redis GEO | Geo-radius queries; privacy (ephemeral, no history) |
| **Discovery ranking** | ELO + ML scoring | Quality recs; ELO manipulation risk |
| **Swipe storage** | Cassandra (async) | Write-optimized; eventual consistency fine for swipes |
| **Chat storage** | Cassandra (partition by matchId) | Time-series messages; partition co-location |
| **Deck pre-computation** | Redis LIST (cache 10 profiles) | Fast deck load; stale deck risk (user status changes) |
| **Right-swiper TTL** | 90 days in Redis | Memory bound; swipes > 90 days ago unlikely to match |

---

## 9. Scalability

| Layer | Strategy |
|---|---|
| Geo-radius queries | Redis GEO Cluster; sharded by city / region |
| Swipe writes | Kafka → async Cassandra batch (18K/sec easily handled) |
| Match detection | Redis SET per user; O(1) check; Cluster for 60GB dataset |
| Chat service | Stateful WebSocket; sticky sessions; horizontal scale |
| Discovery ranking | Pre-computed per user; refreshed every 5 swipes |
| Photo serving | S3 + CloudFront CDN; 90%+ cache hit for profiles |
| ELO updates | Async via Kafka; batched every 5 min; < 1% score drift |

---

## 10. Monitoring & Observability

| Metric | Alert |
|---|---|
| **Swipe deck load time** | P99 > 300ms |
| **Match notification delay** | > 2 sec |
| **Chat message delivery** | > 1 sec for online user |
| **Geo-radius query time** | P99 > 20ms (Redis) |
| **Right-swiper set memory** | Redis memory > 80% |
| **Photo upload failure** | > 1% |
| **ELO score update lag** | > 30 min behind live swipes |

---

## 11. Future Enhancements
- **Video profiles** — short looping videos complement photos (Tinder Loops).
- **Voice notes** — send voice messages in chat.
- **Relationship goals filter** — explicitly match on long-term vs casual.
- **AI-powered icebreakers** — generated opening lines based on profile content.
- **Deception detection** — ML model to flag fake / catfish profiles.

---

*Document prepared for SDE 3 system design interviews. Focus areas: ELO-based profile ranking, Redis SISMEMBER for O(1) match detection, geo-privacy (approximate location only), swipe deck pre-computation, real-time chat with WebSocket + Cassandra, and boost/super-like premium features.*
