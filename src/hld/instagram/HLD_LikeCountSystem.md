# HLD — Instagram Like Count System (Especially for High-Profile Users)

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** Distributed Counters / Hot Key Problem / Write-Heavy Systems
> **Real-world Analogues:** Instagram Likes, Twitter Hearts, YouTube View Count, TikTok Likes
> **The Core Problem:** A celebrity post goes viral — 10 million likes in 10 minutes. How do you count them?

---

## How to Navigate This in 45 Minutes

```
[0:00 -  3:00]  Step 1: Clarify Requirements
[3:00 -  6:00]  Step 2: Capacity Estimation
[6:00 -  9:00]  Step 3: API Design
[9:00 - 20:00]  Step 4: High-Level Architecture
[20:00 - 38:00] Step 5: Deep Dives (pick 3 from 5)
[38:00 - 42:00] Step 6: Scale & Resilience
[42:00 - 45:00] Step 7: Trade-offs
```

---

## Table of Contents
1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [The Celebrity / Hot Key Problem](#51-the-celebrity--hot-key-problem)
   - 5.2 [Sharded Counter Design](#52-sharded-counter-design)
   - 5.3 [Like Storage — Uniqueness & Deduplication](#53-like-storage--uniqueness--deduplication)
   - 5.4 [Approximate vs Exact Counting](#54-approximate-vs-exact-counting)
   - 5.5 [Read Path — Serving Like Counts at Scale](#55-read-path--serving-like-counts-at-scale)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Clarifying Questions to Ask First

```
Q1: "When I say 'count likes', do we need:
     (a) The total like count shown on a post (3.2M likes)?
     (b) Whether the CURRENT USER has liked a post (heart icon filled/empty)?
     (c) Who liked the post (the list of likers)?
     All three have very different data models."
     → Don't assume. All three are needed. But they have different scale challenges.

Q2: "Does the exact count matter, or is approximate OK?
     Instagram actually shows '3.2M likes' not '3,218,492 likes'."
     → Approximate at celebrity scale, exact for normal users.

Q3: "What's the consistency requirement? Can a user like, and see
     the count increase after a 5-second delay?"
     → Most social platforms accept eventual consistency for like counts.

Q4: "Should a user be able to unlike? What happens to the count?"
     → Yes — adds the unlike (decrement) path.

Q5: "Is the like list (who liked) public or private?
     Instagram hid like counts in 2019. Is visibility configurable?"
     → Determines if we need to store individual like records or just counts.

Q6: "What's the SLA? How quickly after liking should the count update?"
     → E.g., self-count: < 1s; public count: eventual < 30s is OK.
```

### Functional Requirements

| # | Requirement | Core Challenge |
|---|---|---|
| FR-1 | User can **like** a post | Write at massive scale; hot posts |
| FR-2 | User can **unlike** a post (undo) | Decrement under concurrent updates |
| FR-3 | Show total **like count** on a post | Hot key reads for celebrity posts |
| FR-4 | Show whether **current user has liked** a post | Per-user, per-post boolean lookup |
| FR-5 | Show **who liked** a post (list of likers) | Optional — paginated list |
| FR-6 | Counts update in **near real-time** (< 30s delay) | Eventual consistency OK |
| FR-7 | Likes are **unique** — user cannot like same post twice | Deduplication at scale |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Scale** | 1B users; 100M posts; 10B total likes stored |
| **Like write RPS** | 100K likes/sec sustained; 1M/sec on viral celebrity posts |
| **Like read RPS** | 5M reads/sec (every feed load checks like count + user liked status) |
| **Count accuracy** | Exact for < 1K likes; within 1% for millions of likes |
| **Write latency** | p99 < 200ms for like action to confirm to user |
| **Read latency** | p99 < 50ms for like count served from cache |
| **Availability** | 99.99% — like button must always work |
| **Idempotency** | Liking twice = 1 like (not 2) |

---

## 2. Capacity Estimation

```
Users:         1 billion total; 500M DAU
Posts:         100M posts active; each post avg age = 2 weeks
Likes stored:  10B total like records (10 likes per post avg)

Like write rate:
  500M DAU × 20 likes/day = 10B like actions/day
  Sustained: 10B / 86400 = ~116K writes/sec
  Peak (celebrity posts): 10× = 1.16M writes/sec on a single hot post

Like read rate:
  Every feed load: 20 posts × 2 fields (count + user_liked_status) per post
  500M DAU × 10 feed loads/day / 86400 = ~58K feed loads/sec
  Each load = 20 × 2 = 40 Redis lookups for like data
  Total: 58K × 40 = ~2.3M reads/sec
  Peak: 5× = ~12M reads/sec

Storage:
  Like record: { user_id(16B) + post_id(16B) + created_at(8B) + status(1B) } = 41 bytes
  10B likes × 41 bytes = 410 GB raw like records
  With index (post_id, user_id): 2× = 820 GB
  With replication (3×): ~2.5 TB

Hot post example: Selena Gomez posts → 10M likes in 60 minutes
  = 10M / 3600 = ~2,778 likes/sec on ONE post_id
  = ~55,560 writes/min on a SINGLE key (the hot key problem)

Counter cache:
  100M active posts × 8 bytes (int64 count) = ~800 MB → fits in single Redis node
  User-liked-status cache: 500M users × 20 posts checked/day → not cached per user
    (hot posts: cache the SET of user_ids who liked → heavy; use Bloom filter instead)
```

---

## 3. API Design

```http
# ============================================================
# LIKE A POST (idempotent)
# ============================================================
PUT /v1/posts/{post_id}/likes
Authorization: Bearer {user_jwt}

# No request body needed (user_id comes from JWT)
# Method: PUT not POST (idempotent: liking twice = same result)

Response 200 (liked for first time):
{
  "liked": true,
  "like_count": 3218493,    # reflects this like (eventual; may be best-effort)
  "user_liked": true
}

Response 200 (already liked — idempotent return):
{
  "liked": true,
  "like_count": 3218493,
  "user_liked": true        # still true; no change
}


# ============================================================
# UNLIKE A POST (idempotent)
# ============================================================
DELETE /v1/posts/{post_id}/likes
Authorization: Bearer {user_jwt}

Response 200:
{
  "liked": false,
  "like_count": 3218492,
  "user_liked": false
}


# ============================================================
# GET LIKE COUNT + USER STATUS  (hot read path)
# ============================================================
GET /v1/posts/{post_id}/likes/summary
    ?viewer_id={user_id}    # optional — check if viewer liked

Response 200:
{
  "post_id":    "post_xyz",
  "like_count": 3218493,
  "user_liked": true,       # null if viewer_id not provided
  "approximate": true       # true for counts > 10K (warn client it's approx)
}


# ============================================================
# BATCH LIKE STATUS (for feed view — most important API)
# ============================================================
POST /v1/likes/batch-summary
{
  "post_ids":  ["post_a", "post_b", "post_c", ...],  # up to 20 posts
  "viewer_id":  "user_123"
}

Response 200:
{
  "summaries": [
    { "post_id": "post_a", "like_count": 9823,   "user_liked": true  },
    { "post_id": "post_b", "like_count": 342891, "user_liked": false },
    { "post_id": "post_c", "like_count": 1,      "user_liked": false }
  ]
}

# ONE batch call per feed load instead of 20 individual calls = 20× less overhead


# ============================================================
# GET WHO LIKED (paginated list) — optional feature
# ============================================================
GET /v1/posts/{post_id}/likers?cursor={cursor}&limit=50

Response 200:
{
  "likers": [
    { "user_id": "usr_001", "username": "alice", "profile_pic": "...", "liked_at": "..." },
    ...
  ],
  "next_cursor": "eyJ...",
  "total_count": 3218493
}
```

---

## 4. High-Level Architecture

```
┌───────────────────────────────────────────────────────────────────────────┐
│                          CLIENT (Mobile/Web)                              │
│    User taps ❤️ (like)          Feed loads (batch like summary)           │
└─────────────┬──────────────────────────────────────┬──────────────────────┘
              │ POST/PUT/DELETE /likes                │ GET /likes/batch-summary
              ▼                                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                API GATEWAY (Auth, Rate Limit, Routing)                  │
│   Rate limit: 10 likes/sec per user (prevent bot abuse)                 │
└──────────────────┬──────────────────────────────┬───────────────────────┘
                   │                              │
                   ▼                              ▼
       ┌───────────────────┐          ┌──────────────────────┐
       │   LIKE SERVICE    │          │    QUERY SERVICE     │
       │   (Write Path)    │          │    (Read Path)       │
       │                   │          │                      │
       │ 1. Dedup check    │          │ 1. Read from Redis   │
       │ 2. Write to DB    │          │    (cache-first)     │
       │ 3. Update counter │          │ 2. Batch post_ids    │
       │ 4. Publish event  │          │ 3. Bloom filter for  │
       └────────┬──────────┘          │    user_liked check  │
                │                     └──────────┬───────────┘
                ▼                                │
┌───────────────────────────────────────────────────────────────────────┐
│                      KAFKA (like-events)                              │
│  Topic: like-events  (partition key = post_id % 500)                 │
│  → Ensures ordering per post; all like/unlike for same post go       │
│    to same partition → sequential processing per post                │
└──────────────┬──────────────────────────────────────────────────────┘
               │  Fan-out to consumers
       ┌────────┼──────────────────────────────┐
       ▼        ▼                              ▼
┌──────────┐ ┌────────────────────┐  ┌────────────────────────────────┐
│  LIKE    │ │ COUNTER AGGREGATOR │  │  NOTIFICATION SERVICE          │
│  STORE   │ │                    │  │                                │
│(Cassandra│ │ Reads like-events  │  │ "Alice liked your photo"       │
│ or MySQL)│ │ Aggregates counts  │  │ Debounced for celebrities       │
│          │ │ Writes to Redis    │  │ (don't notify every like)      │
│ - Like   │ │  INCR/DECR counter │  │                                │
│   records│ │ Writes to DB       │  └────────────────────────────────┘
│   (who   │ │  (periodic flush)  │
│   liked) │ │                    │
└──────────┘ └────────┬───────────┘
                      │
            ┌─────────┴──────────┐
            ▼                    ▼
  ┌─────────────────┐  ┌──────────────────────┐
  │  REDIS          │  │  COUNTER DB          │
  │  (Hot counters) │  │  (PostgreSQL /       │
  │                 │  │   Cassandra)         │
  │  • like_count   │  │                      │
  │    per post     │  │  • Persistent count  │
  │  • Sharded for  │  │  • Hourly snapshot   │
  │    hot posts    │  │    from Redis        │
  │  • TTL: 7 days  │  │  • Source of truth   │
  └─────────────────┘  └──────────────────────┘
```

### Two Distinct Data Stores Needed

| Data | Store | Why |
|---|---|---|
| **Like records** (who liked) | Cassandra | Write-heavy; partition by post_id; 10B records |
| **Like count** (integer) | Redis + Counter DB | In-memory for hot reads; DB for persistence |
| **User-liked status** | Cassandra (lookup table) | "Has user X liked post Y?" — point lookup |

```
The fundamental split:
  "Did user X like post Y?" → Cassandra lookup: O(1) point read
  "How many likes does post Y have?" → Redis GETEX: O(1) in-memory counter
  "Who liked post Y?" → Cassandra SCAN with cursor pagination
```

---

## 5. Deep Dives

### 5.1 The Celebrity / Hot Key Problem

> **Interview tip:** Lead with this. It's the defining challenge of this system. Interviewers WANT you to identify and solve the hot key problem proactively.

#### What Is the Hot Key Problem?

```
Normal user post: 50 likes → 50 writes to like_count:{post_id} in Redis
  → Distributed across time → no problem

Celebrity post (Cristiano Ronaldo, Selena Gomez, BTS):
  → 10M likes in first hour = 2,778 likes/sec on ONE Redis key
  → All 2,778 writes/sec hit the SAME Redis instance
  → Redis single-threaded for atomic operations: INCR is O(1) but still
    100% of this instance's CPU is handling one key
  → Other keys on the same Redis shard are starved

Worse: Instagram has ~200 celebrity accounts with 100M+ followers
  → All 200 can post simultaneously during a live event
  → 200 hot keys × 2,778 writes/sec = 555,600 writes/sec on 200 Redis shards
```

#### Hot Key Detection

```
Detect hot posts before they become a problem:

Method 1: Client-side sampling
  Like Service: log every 1,000th like request to hot-key detector
  → Hot key = any post_id seen > 100 times in 1-second window
  → Probabilistic: P(detect a hot key) = 1 - (1 - 1/1000)^100 ≈ 10%
  → Good enough for early detection; tune sampling rate

Method 2: Redis key access monitoring
  Redis MONITOR command (not for production; too expensive)
  Redis --hotkeys option (Redis 4.0+): tracks top accessed keys
  → Run periodically; detect keys with > 10K ops/sec
  → Promote detected hot keys to sharded counter

Method 3: Proactive profiling
  For pre-known celebrities (accounts with > 10M followers):
    → Automatically apply sharded counter strategy from post creation
    → No need to detect — we KNOW it will be hot
    → Instagram's known celebrity list: ~500 accounts; mark them in user metadata
    user_profile: { user_id, follower_count, tier: NORMAL | CREATOR | CELEBRITY }
    → CELEBRITY tier: all their posts use sharded counters from day 1
```

#### Solutions to the Hot Key Problem

```
Solution 1: Redis Key Sharding (Recommended Primary Solution)

  Split a single counter across N shards:
    like_count:{post_id}:shard:0 = 312,000
    like_count:{post_id}:shard:1 = 298,500
    like_count:{post_id}:shard:2 = 319,200
    like_count:{post_id}:shard:3 = 301,100
    like_count:{post_id}:shard:4 = 287,693
    ...
    Total = sum(all shards) = 1,518,493

  On like write:
    shard = random.randint(0, N-1)   # or hash(user_id) % N
    INCR like_count:{post_id}:shard:{shard_num}
    → Each write goes to a random shard → N× write throughput

  On like count read:
    counts = MGET like_count:{post_id}:shard:0 ... like_count:{post_id}:shard:{N-1}
    total = sum(counts)
    → N network round trips (batched → 1 MGET call)
    → Read aggregates N shards → slightly more expensive than single key

  Shard count selection:
    Normal post:    N = 1  (no sharding needed)
    Creator post:   N = 10 (sustained ~500 likes/sec)
    Celebrity post: N = 100 (sustained ~5,000 likes/sec)
    Viral post:     N = 1000 (extreme: 50,000+ likes/sec)

  Dynamic sharding:
    Start with N=1; monitor write rate per key
    If write_rate > threshold:
      → Migrate to N=10: GETSET old_key 0; INCRBY new_shard_0 old_count
      → Transparent to readers (they sum across shards)

Solution 2: Local Buffering + Batch Flush (For Ultra-High Throughput)

  Like Service instances maintain in-memory counter per post:
    local_counters: { post_id -> atomic_count }
    Every 500ms: flush all local counts to Redis in one pipeline
    200 Like Service pods × 500 likes/sec each = 100K likes/sec
    → Redis receives 200 INCRBY operations every 500ms (not 100K individual INCRs)
    → 99.8% reduction in Redis operations!

  Tradeoff: Up to 500ms delay before count is reflected in Redis
    → Acceptable for like counts (eventual consistency OK)
    → On pod crash: lose up to 500ms of buffered likes
    → Mitigation: write to WAL before buffering; replay on restart

Solution 3: CDN / Read-Only Replica for Count Reads

  Don't solve write hot key; instead:
    Serve like counts from CDN-cached API responses for public posts
    Cache-control: public, max-age=10
    → CDN serves count; origin only handles cache misses (every 10s)
    → Origin load reduced by 100-1000× for popular posts

  Combine with sharded Redis: writes go to sharded counters; reads from CDN
```

---

### 5.2 Sharded Counter Design

#### Full Implementation

```python
class ShardedLikeCounter:
    """
    Manages sharded like counters for hot posts.
    Shard count is dynamically determined by post tier.
    """

    SHARD_COUNTS = {
        "NORMAL":    1,     # < 1K followers
        "CREATOR":   10,    # 1K - 10M followers
        "CELEBRITY": 100,   # > 10M followers
    }

    def __init__(self, redis_client, post_tier_cache):
        self.redis = redis_client
        self.tier_cache = post_tier_cache

    def increment(self, post_id: str, amount: int = 1) -> None:
        """Increment like count — writes to a random shard."""
        n_shards = self._get_shard_count(post_id)

        if n_shards == 1:
            # Fast path: single key (normal posts)
            self.redis.incr(f"like_count:{post_id}")
        else:
            # Distribute write across random shard
            shard = random.randint(0, n_shards - 1)
            key = f"like_count:{post_id}:shard:{shard}"
            self.redis.incrby(key, amount)

    def get_count(self, post_id: str) -> int:
        """Read like count — sums across all shards."""
        n_shards = self._get_shard_count(post_id)

        if n_shards == 1:
            # Fast path: single key
            val = self.redis.get(f"like_count:{post_id}")
            return int(val) if val else 0
        else:
            # Batch get all shard keys in one MGET command
            keys = [f"like_count:{post_id}:shard:{i}" for i in range(n_shards)]
            values = self.redis.mget(*keys)  # O(N) but single network round trip
            return sum(int(v) for v in values if v is not None)

    def get_count_batch(self, post_ids: List[str]) -> Dict[str, int]:
        """Batch read for feed loads — minimizes Redis round trips."""
        # Collect all keys for all posts
        all_keys = []
        post_key_ranges = {}
        idx = 0
        for post_id in post_ids:
            n_shards = self._get_shard_count(post_id)
            if n_shards == 1:
                keys = [f"like_count:{post_id}"]
            else:
                keys = [f"like_count:{post_id}:shard:{i}" for i in range(n_shards)]
            post_key_ranges[post_id] = (idx, idx + len(keys))
            all_keys.extend(keys)
            idx += len(keys)

        # ONE MGET call for all posts × all shards
        values = self.redis.mget(*all_keys)

        # Aggregate counts per post
        result = {}
        for post_id in post_ids:
            start, end = post_key_ranges[post_id]
            result[post_id] = sum(int(v) for v in values[start:end] if v is not None)

        return result

    def _get_shard_count(self, post_id: str) -> int:
        tier = self.tier_cache.get_post_tier(post_id)  # Cached in Redis
        return self.SHARD_COUNTS.get(tier, 1)
```

#### Counter Persistence (Redis → DB Flush)

```
Problem: Redis is in-memory; if cluster restarts, all counts are lost.

Solution: Periodic flush to Counter DB (PostgreSQL)

  Flush job runs every 5 minutes:
    1. SCAN all like_count:* keys in Redis
    2. For each post:
         total = sum(MGET all shards)
         UPSERT like_counts(post_id, count) SET count = total
    3. Keep Redis values; DB is a durable checkpoint

  On Redis restart / cold start:
    1. Load last checkpoint from DB (max 5 min stale)
    2. Replay Kafka like-events topic from last checkpoint timestamp
    3. Re-apply deltas → warm Redis counters in < 5 min

  Why not write-through (Redis + DB every write)?
    → 100K likes/sec × DB write = DB can't sustain this throughput
    → Write-behind (periodic flush) is the right pattern for counters
    → Accept: DB count may lag Redis by up to 5 minutes (acceptable)
```

---

### 5.3 Like Storage — Uniqueness & Deduplication

#### The Problem

```
User can like a post at most ONCE.
  If user double-taps (mobile haptic feedback bug), we must not count twice.
  If user spam-likes (bot behavior), count must not inflate.

At 100K likes/sec, we can't do:
  SELECT COUNT(*) FROM likes WHERE user_id=? AND post_id=? before every write
  → 100K DB reads/sec just for dedup → unsustainable
```

#### Cassandra Storage Model

```
Two tables needed:

Table 1: likes_by_post  (for like count reconstruction, paginated likers list)
  Partition key: post_id
  Clustering key: user_id
  → "Has user X liked post Y?" = point read on partition post_id, row user_id

  CREATE TABLE likes_by_post (
    post_id    UUID NOT NULL,
    user_id    UUID NOT NULL,
    liked_at   TIMESTAMP NOT NULL,
    status     TINYINT NOT NULL,    -- 1=liked, 0=unliked (soft delete)
    PRIMARY KEY (post_id, user_id)
  ) WITH CLUSTERING ORDER BY (user_id ASC)
    AND compaction = { 'class': 'LeveledCompactionStrategy' };

Table 2: likes_by_user  (for "see all posts I've liked" feature)
  Partition key: user_id
  Clustering key: liked_at DESC, post_id
  → Fast retrieval of user's like history

  CREATE TABLE likes_by_user (
    user_id    UUID NOT NULL,
    liked_at   TIMESTAMP NOT NULL,
    post_id    UUID NOT NULL,
    status     TINYINT NOT NULL,
    PRIMARY KEY (user_id, liked_at, post_id)
  ) WITH CLUSTERING ORDER BY (liked_at DESC, post_id ASC);

Both are written atomically via Cassandra BATCH:
  BEGIN BATCH
    INSERT INTO likes_by_post (post_id, user_id, liked_at, status) VALUES (?,?,?,1)
    INSERT INTO likes_by_user (user_id, liked_at, post_id, status) VALUES (?,?,?,1)
  APPLY BATCH
  → Atomic; either both succeed or both fail

Deduplication using Cassandra's LWT (Lightweight Transactions):
  INSERT INTO likes_by_post (post_id, user_id, liked_at, status)
  VALUES (?, ?, ?, 1)
  IF NOT EXISTS   ← LWT: only insert if row doesn't exist
  → Returns [applied: true] if new like
  → Returns [applied: false] if already liked (dedup!)
  
  LWT cost: 2× round trips (Paxos); use only for dedup; avoid in hot path
  
  Alternative for high throughput: Cassandra upsert + idempotent counter
    INSERT always; status column update (1→0→1 for like/unlike/like)
    → Eventually consistent; last-write-wins on status
    → Count from status=1 rows on read (eventually consistent)
```

#### Bloom Filter for User-Liked Status (Hot Posts)

```
Problem: "Has user X liked this celebrity post?" is checked on EVERY feed load.
  Celebrity post: 10M users liked it
  For every user viewing this post: check SET membership
  Cassandra reads for likes_by_post: 5M reads/sec for one popular post

Solution: Bloom Filter per post for hot posts

  Bloom filter: probabilistic data structure
    - Space-efficient: 1 bit per element (vs 16 bytes for UUID)
    - Query: "Is user X in this set?" = O(1), no disk I/O
    - False positives: possible (say "yes" when answer is "no")
    - False negatives: IMPOSSIBLE (never says "no" when answer is "yes")

  For like status: false positive means "shows heart as filled when you haven't liked"
    → On tap: they try to unlike → system says "you haven't liked this" → UI corrects
    → Acceptable UX degradation (much better than 5M DB reads/sec)

  Parameters for post with 10M likes:
    n = 10M elements (likers)
    p = 0.001 (0.1% false positive rate — 1 in 1000 wrong)
    m (bits) = -n × ln(p) / (ln(2)²) = -10M × ln(0.001) / 0.48 = 144M bits = 18 MB per post

  18 MB per celebrity post × 500 celebrity posts = 9 GB → fits in Redis Cluster!
    
    SETBIT user_liked:{post_id} {user_id_hash % filter_size} 1  (on like)
    GETBIT user_liked:{post_id} {user_id_hash % filter_size}    (on query)
    → O(1) per check; no Cassandra hit for popular posts

  For normal posts (< 10K likes): skip bloom filter; just hit Cassandra (small data)
  For celebrity posts: Bloom filter first → Cassandra only on false positive resolution

  RedisBloom module: purpose-built Bloom filter in Redis
    BF.ADD user_liked:{post_id} {user_id}
    BF.EXISTS user_liked:{post_id} {user_id}
    → Even simpler API
```

---

### 5.4 Approximate vs Exact Counting

> **Interview tip:** Instagram actually made this decision explicitly. They hide like counts in some regions. Discuss the accuracy vs performance trade-off.

#### When Exact Counts Are Necessary vs Approximate

```
Exact count needed:
  - Post with < 10K likes: user wants to see "4,823 likes" not "~5K likes"
  - Creator analytics dashboard: accurate engagement metrics for monetization
  - Audit/reporting: regulatory or advertiser accuracy requirements

Approximate count acceptable:
  - Post with > 10K likes: "3.2M likes" (human can't distinguish 3.2M from 3.218M)
  - Real-time feed display: counts change every second anyway; exact is impossible
  - Celebrity posts: showing "47.3M" is meaningful; "47,318,492" adds no value

Instagram's actual approach:
  - Below 1,000: show exact count "842 likes"
  - 1K-100K: show "4,823 likes" (still exact)
  - 100K+: show "342.9K likes" (1 decimal, rounded)
  - 1M+: show "3.2M likes" (1 decimal; implicit ~1% approximation)
```

#### HyperLogLog for Very Large Sets

```
Problem: Count unique likers for a post with 100M likes over its lifetime.
  Exact: store all 100M user IDs → 1.6 GB per post
  Goal: count unique users who liked in last 30 days

HyperLogLog (HLL): probabilistic cardinality estimator
  Accuracy: ±0.81% standard error
  Memory: 12 KB regardless of set size (even for trillions of unique elements!)
  Operations: PFADD (add element), PFCOUNT (count unique elements)

Use case for Instagram:
  "How many unique users liked content from this brand in Q1?"
  = Union of HLLs across multiple posts → PFMERGE + PFCOUNT
  
  Without HLL: need to deduplicate across 100M+ records → expensive
  With HLL: PFMERGE brand_hll post1_hll post2_hll ... → PFCOUNT = ~10ms

Redis HyperLogLog:
  PFADD unique_likers:{post_id}:{date} {user_id}  (on like event)
  PFCOUNT unique_likers:{post_id}:{date}
  PFMERGE weekly_unique unique_likers:{post_id}:{mon} ... {sun}

When to use HLL vs exact counter:
  Like count:        exact counter (Redis INCR/sharded) — it's just an integer
  Unique likers:     exact if < 1M (Cassandra); HLL for massive cardinality
  7-day reach:       always HLL (cross-post dedup is expensive exact)
  Advertiser reports: HLL with known ±0.81% error; disclosed to advertisers
```

---

### 5.5 Read Path — Serving Like Counts at Scale

#### Feed Load — The Most Frequent Query

```
When Alice opens Instagram:
  Feed: 20 posts shown
  For EACH post, need:
    a) like_count   → show "3.2M likes"
    b) user_liked   → show filled/empty heart icon

  Naive approach: 20 Cassandra + 20 Redis queries = 40 requests per feed load
  At 58K feed loads/sec: 58K × 40 = 2.3M backend requests/sec
```

#### Optimized Read Path

```
Step 1: Batch like count fetch (single Redis MGET)
  Request: GET like counts for post_ids = [A, B, C, ..., T] (20 posts)

  Like Query Service:
    for each post_id: determine shard count (from post tier cache)
    build all keys: [like_count:A, like_count:B:shard:0, like_count:B:shard:1, ...]
    MGET all_keys_in_one_call  ← single Redis pipeline
    aggregate per post_id

  Result: 20 counts in 1 Redis call (< 2ms)

Step 2: Batch user-liked status
  For each of 20 posts: "Has this user liked this post?"

  Option A: Cassandra multi-get (for low-follower posts)
    SELECT post_id, status FROM likes_by_post
    WHERE (post_id, user_id) IN (
      ('A', 'user_123'), ('B', 'user_123'), ..., ('T', 'user_123')
    )  ← Cassandra IN query on primary key = efficient
    Result: 20 likes status in ~5ms

  Option B: Redis SET membership for hot posts
    SMEMBERS user_liked_set:{post_id} is too large (10M members)
    Instead: SISMEMBER liked_by:{post_id} {user_id}
    But this set could be huge for celebrity posts → use Bloom Filter

  Option C: User-side cache "my likes" (most Instagram-like)
    On feed load: client sends list of post_ids it wants to check
    Server checks Redis key: my_likes:{user_id}
      → SET of all post_ids this user has liked (in last 30 days)
      → Loaded once per session; cached in-memory on server for session duration
    SMEMBERS my_likes:{user_id} = full set of user's likes
    Then check membership locally: post_id in user_likes_set?
    → 1 Redis SMEMBERS call → unlimited post membership checks!

    Size: avg user likes 20 posts/day × 30 days = 600 posts × 16 bytes = 9.6 KB per user
    → Small enough to cache in memory per session (kept in API server LRU cache)

Final Read Path:
  1. MGET all sharded like counts → 20 like counts in 1 ms
  2. SMEMBERS my_likes:{user_id} → cached in API server session (0ms if cached)
  3. Check membership: for each of 20 post_ids → local set lookup (0ms)
  4. Return batch response: 20 × {like_count, user_liked}
  Total latency: ~2ms for entire feed like data!
```

#### CDN Caching for Public Count API

```
For non-personalized count (just the number, not user_liked):
  Cache at CDN edge:
    GET /v1/posts/{post_id}/likes/count
    Cache-Control: public, max-age=30   (30-second TTL)
    → Celebrities' counts cached at edge; origin handles < 1 req/30s per post

  Personalized (user_liked=true/false): NOT cacheable at CDN
    → Requires user identity → private, not shareable
    → Must hit origin
    → Solved by user-side likes cache (Option C above)
```

---

## 6. Scale & Resilience

### Redis Cluster Architecture

```
Cluster: 20 nodes (10 primary + 10 replica)
Partitioning: consistent hashing (Redis Cluster native, 16384 hash slots)

Hot post counter assignment:
  Celebrity post with 1000 shards:
    like_count:{post_id}:shard:{0-999}
    → Distributed across all 16384 hash slots → land on many different nodes
    → Writes spread across all 20 nodes → no single-node bottleneck ✓

Memory:
  100M posts × avg 8 shards × 8 bytes = 6.4 GB → fits in cluster easily

Write throughput per node:
  Redis: ~100K ops/sec single-threaded
  20 nodes: 2M total ops/sec > our 100K writes/sec sustained requirement
                              > our 1M writes/sec peak (with buffering)
```

### Failure Scenarios

| Failure | Detection | Recovery |
|---|---|---|
| **Redis node crash** | Sentinel/Cluster heartbeat | Replica promoted in < 10s; some counts stale by in-memory buffer (< 500ms) |
| **Counter drift** (shards get out of sync) | Periodic reconciliation job | Compare Redis sum vs Cassandra count; correct Redis if drift > 0.1% |
| **Cassandra node failure** | CL=QUORUM still succeeds (RF=3) | Node rejoins; reads repair; no data loss |
| **Kafka lag** | Consumer lag metric | Scale out Counter Aggregator pods; Kafka retains 7 days |
| **Like storm** (viral video: 50K likes/sec) | Redis write rate monitor | Hot key detection → promote to N=1000 shards; CDN absorbs reads |
| **Double-like bug** (client sends twice) | Cassandra LWT `IF NOT EXISTS` | Idempotent; second write rejected; counter INCR only on first write |

### Counter Reconciliation Job

```
Problem: Redis counter and Cassandra like record count can diverge if:
  - Redis crashed and was restored from 5-min-old snapshot
  - Kafka event was dropped (rare but possible at-least-once edge case)
  - Network partition during dual-write

Daily reconciliation job (runs at 2 AM):
  For each post modified in last 24 hours:
    redis_count = sharded_counter.get_count(post_id)
    db_count    = SELECT COUNT(*) FROM likes_by_post
                  WHERE post_id=? AND status=1    # Cassandra row count
    if abs(redis_count - db_count) > 10:          # tolerance of 10 likes
        log.warn(f"Counter drift for {post_id}: Redis={redis_count}, DB={db_count}")
        redis.set(f"like_count:{post_id}", db_count)  # correct Redis from DB

  Cassandra is the source of truth for reconciliation (has the actual records)
  Redis is the fast cache (can be rebuilt from Cassandra)
```

---

## 7. Trade-offs & Alternatives

| Decision | Choice | Alternative | Reason |
|---|---|---|---|
| **Counter storage** | Sharded Redis counters | DB counter with UPDATE | Redis INCR = O(1), sub-millisecond; DB UPDATE = 5-10ms, 100K/sec unsustainable |
| **Celebrity strategy** | Sharded counter (N=100-1000) | Single hot key | Single key: Redis bottleneck at 2,778 writes/sec on celebrity post |
| **User-liked status** | Cassandra LWT + user liked SET cache | DB boolean check | Cassandra scales; user SET cache = O(1) check for entire feed; no per-post lookup |
| **Dedup mechanism** | Cassandra LWT `IF NOT EXISTS` | Redis SETNX per (user+post) | Cassandra: durable; Redis SETNX: fast but not persistent (loses on crash) |
| **Approximate counting** | Exact sharded counter + display rounding | HyperLogLog | Like count is just 1 integer (no cardinality estimation needed); HLL adds complexity without benefit |
| **Bloom filter** | For user-liked on celebrity posts only (> 10M likes) | Always Cassandra | Bloom: 18 MB vs 160 MB for user UUIDs; 1% false positive rate is acceptable |
| **Write buffering** | Local buffer + batch flush every 500ms | Write-through per like | Reduces Redis load 99.8%; accepts 500ms count update delay (acceptable) |
| **Read path** | User likes SET cache (SMEMBERS once per session) | Per-post SISMEMBER × 20 | SET cache: 1 Redis call for entire feed; per-post: 20 Redis calls per feed |

### Algorithm Comparison for Counting

| Approach | Accuracy | Memory | Write Speed | Read Speed |
|---|---|---|---|---|
| **Single Redis key (INCR)** | Exact | 8 bytes | Limited by 1 key | O(1) |
| **Sharded Redis (N shards)** | Exact | 8N bytes | N× throughput | O(N) read (MGET) |
| **Write buffer + batch** | Near-exact (500ms lag) | 8N + buffer | 99.8% less Redis writes | O(N) |
| **HyperLogLog** | ±0.81% | 12 KB fixed | Any throughput | O(1) |
| **DB counter (UPDATE)** | Exact | 8 bytes | Low (5ms/write) | O(1) |
| **Kafka streaming sum** | Exact (after processing) | Log only | Any throughput | Requires aggregation |

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Separated 3 different queries: count / user-liked-status / who-liked-list
  [ ] Asked about exact vs approximate count (Instagram rounds at 100K+)
  [ ] Asked about unlike support and deduplication
  [ ] Identified proactively: celebrity posts = hot key problem

CAPACITY
  [ ] 116K writes/sec sustained; 1M+/sec on celebrity post (2,778/sec on ONE key)
  [ ] 2.3M read requests/sec for feed like data
  [ ] Redis: 100M posts × 8 shards × 8B = 6.4 GB total (fits easily)
  [ ] Bloom filter: 18 MB per celebrity post × 500 posts = 9 GB in cluster

ARCHITECTURE
  [ ] Separate like RECORDS (Cassandra) from like COUNTS (Redis)
  [ ] Kafka between Like Service and Counter Aggregator (decouple write from count)
  [ ] Batch API for feed load (20 posts in 1 call; not 20 separate calls)
  [ ] User liked SET cache per session (1 Redis call for full feed check)

DEEP DIVES

  Celebrity / Hot Key:
    [ ] Proactively identified hot key problem before being asked
    [ ] Hot key detection: client sampling + Redis --hotkeys + proactive celebrity tier
    [ ] Three solutions: sharding (N=1-1000 by tier), write buffering, CDN caching
    [ ] Celebrity tier: auto-apply N=100 shards from post creation (no detection needed)

  Sharded Counter:
    [ ] Code-level design: shard on write (random), aggregate on read (MGET)
    [ ] Dynamic sharding: start N=1, promote to N=10/100 on detection
    [ ] Write-behind flush to PostgreSQL every 5 minutes (not write-through)
    [ ] Cold start: load from DB checkpoint + Kafka replay for delta

  Like Storage & Dedup:
    [ ] Two Cassandra tables: likes_by_post and likes_by_user
    [ ] Cassandra LWT (IF NOT EXISTS) for dedup (atomic, durable)
    [ ] Unlike = soft delete (status=0), not physical delete
    [ ] Bloom filter: 18 MB per 10M-liker post, 0.1% false positive acceptable

  Approximate Counting:
    [ ] Exact for < 10K; rounded display for > 100K
    [ ] HyperLogLog: 12 KB for any cardinality, ±0.81% error
    [ ] HLL use case: unique likers across post portfolio (advertiser analytics)
    [ ] Like count itself = exact integer (no HLL needed; HLL is for cardinality)

  Read Path:
    [ ] Batch MGET across all shards for 20 posts in 1 Redis call
    [ ] User likes SET (SMEMBERS once per session) → O(1) per-post check
    [ ] CDN caches public count (max-age=30s); personalized count is private
    [ ] Session-level LRU cache for user like set achieves zero Redis reads per feed post

SCALE & RESILIENCE
  [ ] Redis Cluster: 20 nodes; celebrity shards spread across all (no single bottleneck)
  [ ] Daily reconciliation job: Cassandra (source of truth) corrects Redis drift
  [ ] Redis failure: counter stale by 500ms buffer max; no data loss (WAL)
  [ ] Double-like protection: Cassandra LWT + idempotent write (only INCR on first)

TRADE-OFFS
  [ ] Sharded counter vs single key (throughput vs read complexity)
  [ ] Write buffer (batch) vs write-through (throughput vs count freshness)
  [ ] Bloom filter vs exact Cassandra lookup (memory vs 0.1% false positive)
  [ ] User likes SET cache vs per-post SISMEMBER (1 call vs N calls per feed)
```
