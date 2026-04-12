# 🐦 High-Level Design (HLD) — Twitter Feed (News Feed System)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Fan-out on Write vs Read, Feed Generation, Timeline Ranking, Hybrid Model for Celebrity Users, Real-time Tweets

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **post tweets** (text ≤ 280 chars, images, videos, polls).
- Users see a **Home Timeline** — a ranked, personalized feed of tweets from followed accounts + recommended content.
- **Follow / Unfollow** users.
- Users can **retweet, quote-tweet, like, reply** to tweets.
- **Real-time delivery** — tweets appear in followers' feeds within seconds.
- **Trending topics** — top hashtags globally and per location.
- **Search** — full-text search over tweets.
- **Notifications** — when someone likes/retweets/replies to your tweet or follows you.
- **User Profile Timeline** — all tweets by a specific user, reverse-chronological.

### 1.2 Non-Functional Requirements
| Property               | Target                                          |
| ---------------------- | ----------------------------------------------- |
| **Feed load latency**  | < 200 ms P99                                    |
| **Tweet post latency** | < 500 ms P99 (accepted)                         |
| **Fan-out latency**    | Tweet in followers' feeds within 5 sec          |
| **Availability**       | 99.99% for read (feed); 99.9% for write (tweet) |
| **Scale**              | 500M DAU, 500M tweets/day, 300B feed reads/day  |
| **Read : Write ratio** | 1000 : 1 (extremely read-heavy)                 |
| **Consistency**        | Eventual — slight delay in feed is acceptable   |

### 1.3 Out of Scope
- Direct Messages (DMs)
- Twitter Spaces (audio rooms)
- Ad serving / promoted tweets (separate system)

---

## 2. Capacity Estimation

```
DAU                     = 500 million
Tweets/day              = 500 million → ~5,800/sec avg, ~50K/sec peak
Feed reads/day          = 300 billion → ~3.5M req/sec avg, ~15M/sec peak
Avg followers/user      = 200; Avg followees/user = 200
"Celebrity" accounts    = ~50K users with > 1M followers (e.g., Obama: 130M followers)
Tweet size              = ~280 bytes (text) + metadata ≈ ~1 KB
Storage/day             = 500M × 1KB = ~500 GB/day
Home Timeline cache     = 500M users × 800 tweets × 8 bytes (tweetId) = ~3.2 TB Redis
Fanout writes/day       = 500M tweets × 200 followers avg = 100B writes/day
```

---

## 3. High-Level Architecture

```
 Client (Web / Mobile)
        │
        ├── POST /tweet ─────────────────────────────────────▶ Tweet Ingestion Service
        │                                                              │
        │                                                       Kafka: TweetCreated
        │                                                              │
        └── GET /feed ──▶ API Gateway ──▶ Feed Service ◀─── Feed Cache (Redis)
                                                │
                                    ┌───────────┼────────────────┐
                                    ▼           ▼                ▼
                              Fan-out      Timeline          Ranking
                              Service      Service           Service (ML)
                              (write)      (read)
                                    │           │
                          ┌─────────┼───────────┼──────────┐
                          ▼         ▼           ▼          ▼
                      User        Tweet       Social      Search
                      Service     DB          Graph       (Elasticsearch)
                      (follows)   (Cassandra) (Redis +
                                             DynamoDB)
```

---

## 4. The Core Problem — Fan-out Strategy

This is the **most important design decision** in news feed systems. Three approaches:

---

### 4.1 Fan-out on Write (Push Model) — for Regular Users

When a user tweets, **immediately write the tweet to all followers' feed caches**.

```
User A (200 followers) posts a tweet
        │
        ▼
Tweet stored in Tweet DB
        │
        ▼
Fan-out Worker (Kafka consumer):
  reads followers of User A (200 users)
  for each follower → LPUSH feed:{followerId} tweetId
                      LTRIM feed:{followerId} 0 799   (keep last 800 tweets)
        │
        ▼
When Follower B opens feed:
  → Redis LRANGE feed:{userB} 0 19   (first 20 tweets, sub-millisecond)
  → Enrich with tweet content from Tweet DB
  → Return pre-built feed
```

**Pros:** Feed reads are O(1) — just a Redis list lookup.  
**Cons:** Celebrity with 100M followers → 100M Redis writes per tweet (write amplification).

---

### 4.2 Fan-out on Read (Pull Model) — for Celebrity Users

When a user opens their feed, **dynamically merge tweets from all followed accounts**.

```
User B opens feed
        │
        ▼
Feed Service:
  Get followees of User B (200 accounts)
  For each followee → fetch their latest tweets from Tweet DB
  Merge-sort all tweets by timestamp (priority queue)
  Apply ranking model
  Return top 20
```

**Pros:** No write amplification — tweet posted once.  
**Cons:** Feed read is O(K × N) where K = followees, N = tweets to scan → slow for feed load.

---

### 4.3 ✅ Hybrid Model (Production Approach — Twitter's Actual Design)

```
On Tweet Posted:
  if user.followerCount < 1M:
      → Fan-out on WRITE (push tweetId to followers' feed cache in Redis)
  else (celebrity — Obama, Elon, Cristiano):
      → Fan-out on READ (do NOT pre-push; followers pull at read time)

On Feed Read (for User B who follows both regular + celebrity users):
  1. LRANGE feed:{userB} 0 799       → pre-built tweets from regular followees
  2. For each celebrity followed by B:
       → fetch latest 20 tweets from Tweet DB
  3. Merge both sets (priority queue on timestamp)
  4. Apply ML ranking
  5. Return top 20
```

**Threshold:** ~1M followers typically separates "push" from "pull" users.  
**Result:** O(1) for most users; only a few hundred celebrity accounts need pull-on-read.

---

## 5. Core Components

### 5.1 Tweet Ingestion Service

```
POST /tweet { text, media_urls, replyToId, quotedTweetId }

1. Validate (length ≤ 280, auth, rate limits: 300 tweets/3hr)
2. Assign tweetId (Snowflake ID — time-sortable, globally unique)
3. Store tweet in:
   → Tweet DB (Cassandra) — source of truth
   → Media handled separately via pre-signed S3 URL
4. Publish TweetCreated event to Kafka
5. Return 201 Created with tweetId
```

**Snowflake ID (Twitter's actual approach):**
```
64-bit ID breakdown:
  41 bits → timestamp (ms since epoch) — enables time-ordering
  10 bits → machine ID (datacenter + worker) — uniqueness
  12 bits → sequence number (up to 4096 tweets/ms per machine)

Benefits: sortable by time, globally unique, no central coordinator
```

### 5.2 Fan-out Service (Kafka Consumers)

```
Kafka topic: tweet-created
Consumer group: fanout-workers (auto-scaled)

For each TweetCreated event:
  1. Fetch followers list from Social Graph Service
     (Redis for < 1M followers; DynamoDB for large accounts)
  2. If follower count < 1M:
     Batch write tweetId into Redis lists:
       PIPELINE:
         LPUSH feed:{follower1} tweetId
         LPUSH feed:{follower2} tweetId
         ...
       Redis pipeline — 1,000 LPUSH per batch → minimize round trips
  3. If follower count ≥ 1M:
     → Skip fan-out; tweet stored only in Tweet DB + user timeline

Fan-out rate:
  Regular user (200 followers) → 200 Redis writes per tweet
  Peak: 50K tweets/sec × 200 = 10M Redis writes/sec (within Redis Cluster limits)
```

### 5.3 Feed Service (Read Path)

```
GET /v1/feed?cursor={lastSeenTweetId}&limit=20

1. Fetch pre-built feed from Redis:
   LRANGE feed:{userId} 0 199  (buffer 200 tweetIds for pagination)

2. Identify celebrities followed by this user:
   (cached in user_celebrity_follows:{userId} Redis SET, TTL: 1hr)
   For each celebrity → fetch recent 50 tweets from Tweet DB

3. Merge & deduplicate all tweetIds (priority queue on tweetId timestamp)

4. Ranking Layer:
   Apply ML model score per tweet:
     score = f(engagement_rate, recency, user_interest_match, social_proof)
   Sort by score descending

5. Fetch full tweet content for top 20 tweetIds:
   → Redis cache: tweet:{tweetId} (TTL: 1hr)
   → On miss: Cassandra lookup + back-fill cache

6. Return enriched tweet list with cursor for next page
```

### 5.4 Ranking Service (ML Ranking Layer)

Twitter's feed is NOT purely reverse-chronological — it's ranked.

```
Feature signals:
  Tweet features:
    - recency (time since posted)
    - engagement velocity (likes + retweets in first 30 min)
    - media richness (has image/video → higher CTR)
    - author credibility (verified, follower count)

  User-tweet affinity:
    - how often does user engage with this author
    - topic overlap (user interest profile vs tweet topic)
    - graph closeness (mutual follows, RTs of same accounts)

  Context:
    - time of day, device type
    - session position (tweet #1 vs #30 in session)

Ranking model:
  Two-stage:
    1. Lightweight scorer (logistic regression) → score all 200 candidates fast
    2. Deep model (neural network) → re-rank top 50 → return top 20

Output: ranked list of 20 tweetIds → enriched with content → returned to client
```

### 5.5 Social Graph Service

The Social Graph Service answers two core questions at massive scale:
- **Who does user A follow?** (following list — needed for feed read + fan-out)
- **Who follows user A?** (follower list — needed for fan-out on write)

And advanced graph queries:
- **Mutual follows** — does B follow A back?
- **2nd-degree connections** — "People you may know" (friends of friends)
- **Intersection** — which of A's followees does B also follow?

---

#### 5.5.1 Database Selection — Why NOT a Graph DB (Neo4j)?

The instinctive choice for a social graph is a **graph database** (Neo4j, Amazon Neptune). But at Twitter's scale, this is the **wrong choice**. Here's why:

| Criteria                   | Graph DB (Neo4j)                                                     | Our Choice                                              |
| -------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------- |
| **Scale**                  | Struggles beyond ~1B edges; hard to shard                            | DynamoDB: unlimited horizontal scale                    |
| **Sharding**               | Graph topology makes partitioning very hard (edges cross partitions) | Key-value lookup shards trivially by userId             |
| **Query type**             | Excels at deep traversal (6 degrees of separation)                   | Twitter only needs 1-hop (direct follows) at query time |
| **Operational complexity** | Complex cluster management                                           | DynamoDB: fully managed                                 |
| **Latency at scale**       | Multi-hop traversals slow under load                                 | Single-row DynamoDB lookup: ~5ms                        |

> **Key Insight:** Twitter's social graph operations are almost exclusively **1-hop lookups** (get direct followers/followees). You don't need a graph DB for that — you need a fast key-value store with range scan.

Deep graph traversal (friend-of-friend) runs **offline** as a batch job (Spark on the social graph export), not in real-time queries.

---

#### 5.5.2 Storage Architecture — Three-Tier Model

```
Tier 1: Redis (Hot Cache)
  → Following list for ALL users (who user A follows — small, ~200 ids)
  → Follower list ONLY for regular users (< 1M followers)
  → Sub-millisecond lookup; used by Fan-out Service on every tweet

Tier 2: DynamoDB (Warm Store — Source of Truth)
  → Persistent, durable storage for ALL follow relationships
  → Paginated follower/following scans for API responses
  → Celebrity follower lists (too large for Redis SET)

Tier 3: S3 + Spark (Cold — Graph Analytics)
  → Periodic full graph export to S3 (daily)
  → Spark jobs: "People You May Know", graph clustering, spam detection
  → Output fed back into recommendation service
```

---

#### 5.5.3 DynamoDB Data Model

Two separate tables for **bidirectional O(1) lookup** without scanning.

**Table 1: `following` — "Who does user A follow?"**

```
PK (partition key) : follower_id   (UUID of the user doing the following)
SK (sort key)      : followee_id   (UUID of the account being followed)

Attributes:
  followed_at   TIMESTAMP    when the follow happened
  is_close_friend BOOLEAN    Twitter's "close friends" circle feature

Example rows:
  follower_id=alice | followee_id=bob     | followed_at=2024-01-01
  follower_id=alice | followee_id=obama   | followed_at=2024-01-05
  follower_id=alice | followee_id=elon    | followed_at=2023-12-01

Query: "Who does alice follow?" → PK=alice, scan all SK → O(1) partition lookup
```

**Table 2: `followers` — "Who follows user A?"**

```
PK (partition key) : followee_id   (UUID of the account being followed)
SK (sort key)      : follower_id   (UUID of the user doing the following)

Attributes:
  followed_at   TIMESTAMP
  follower_count_approx BIGINT   (denormalized on the followee row)

Example rows:
  followee_id=obama | follower_id=alice   | followed_at=2024-01-05
  followee_id=obama | follower_id=bob     | followed_at=2023-08-10
  followee_id=obama | follower_id=charlie | followed_at=2022-05-20

Query: "Who follows obama?" → PK=obama, paginated SK scan → O(1) per page
```

> **Why two tables?** DynamoDB (like Cassandra) doesn't support secondary index scans efficiently at scale. Duplicating data into two separate tables (one per read direction) is the standard pattern — write to both atomically using DynamoDB Transactions.

---

#### 5.5.4 Redis Cache Layout

```
Key                        Type   Content                          TTL
─────────────────────────────────────────────────────────────────────────
following:{userId}         SET    Set of followeeIds               1 hour
                                  (all users; ~200 entries avg)

followers:{userId}         SET    Set of followerIds               1 hour
                                  (ONLY for regular users < 1M)

follow_count:{userId}      STRING follower count (approximate)     5 min
                                  (updated via INCR/DECR)

celebrity_ids              SET    Global set of celebrity userIds  1 hour
                                  (users with > 1M followers)
                                  Used by Fan-out Service to skip push
```

**Why Redis SET for following list?**
- Instant `SISMEMBER` check: "does A follow B?" → O(1) — used in mutual follow check
- `SINTERSTORE`: compute intersection of two users' following sets — "you both follow X"
- `SCARD`: follower count — O(1)
- Fits entirely in memory: 200 followees × 8 bytes × 500M users = ~800 GB (sharded across Redis cluster)

---

#### 5.5.5 Operations — Detailed Flow

**`follow(A, B)` — User A follows User B**

```
1. Write to DynamoDB Transaction (atomic):
   PUT  following    {PK=A, SK=B, followed_at=now()}
   PUT  followers    {PK=B, SK=A, followed_at=now()}
   UPDATE user_stats {PK=B}: following_count + 1   (on user A's row)
   UPDATE user_stats {PK=A}: follower_count  + 1   (on user B's row)

2. Update Redis cache (async, best-effort):
   SADD following:{A} B
   INCR follow_count:{B}

3. Publish FollowEvent to Kafka:
   { followerId: A, followeeId: B, timestamp: now() }
        │
        ▼
   Fan-out Service (if B is regular user):
     Fetch B's recent 10 tweets → LPUSH into feed:{A}
     → A immediately sees B's latest tweets in their feed
```

**`unfollow(A, B)` — User A unfollows User B**

```
1. DynamoDB Transaction:
   DELETE following {PK=A, SK=B}
   DELETE followers {PK=B, SK=A}

2. Redis cache update (async):
   SREM following:{A} B
   DECR follow_count:{B}

3. Feed cleanup (lazy — NOT immediate):
   No immediate feed purge (complex + unnecessary)
   → B's tweets naturally disappear from A's feed on next rebuild
   → Feed TTL is 24hr; stale tweets excluded by "is this user still followed?" check
```

**`getFollowers(userId, limit=100, cursor)` — Paginated follower list**

```
if userId is regular user (follower count < 1M):
  1. Check Redis: SMEMBERS followers:{userId}
     → Return set (entire list fits in memory)

if userId is celebrity (follower count ≥ 1M):
  1. DynamoDB paginated query:
     PK = userId (in followers table)
     SK > cursor  (range scan from last seen followerId)
     Limit = 100
     → Returns 100 followerIds + NextPageToken
  2. Batch fetch user profiles for the 100 ids (parallel DynamoDB gets)
  3. Return enriched follower list + cursor for next page
```

**`isMutualFollow(A, B)` — Do A and B follow each other?**

```
Parallel lookup (both in Redis):
  SISMEMBER following:{A} B   → does A follow B?
  SISMEMBER following:{B} A   → does B follow A?

Both O(1). Result in < 1ms.
Used to show "Follows you" badge on profile pages.
```

**`getCommonFollows(A, B)` — Who do A and B both follow?**

```
Redis SINTER following:{A} following:{B}
→ Set intersection → O(min(|A|, |B|))
→ Returns common followeeIds

Use case: "You and @alice both follow @obama" — social proof on tweet engagement
```

---

#### 5.5.6 Handling the Celebrity Problem

A celebrity like Obama has **130M followers**. Storing 130M follower IDs in a Redis SET is infeasible (~1 GB per celebrity SET × thousands of celebrities).

```
Strategy: Tiered Storage by follower count

< 10K followers   → Redis SET (full list in memory)
10K – 1M          → Redis SET for active portion + DynamoDB for full list
> 1M (celebrity)  → DynamoDB ONLY; no Redis follower SET

Fan-out Service checks: SISMEMBER celebrity_ids {userId}
  → If celebrity: skip Redis fan-out entirely
  → followers fetched from DynamoDB in paginated batches for fan-out
    (background job; not on hot path)
```

For fan-out of celebrity tweets, the Fan-out Service uses **scheduled workers** that paginate through the DynamoDB `followers` table in chunks of 1,000 and write tweetIds to follower feeds in batches.

---

#### 5.5.7 "People You May Know" — Offline Graph Processing

2nd-degree relationship queries ("friends of friends") are **too expensive** to run in real-time over DynamoDB. These run as **offline Spark jobs** on the full graph export.

```
Daily Job (Spark on S3 graph export):
  Input: all (followerId, followeeId) edges

  Algorithm (Friend-of-Friend):
    For each user A:
      followees = getFollowing(A)
      for each followee B in followees:
        followees_of_B = getFollowing(B)
        candidates = followees_of_B - followees  (not already followed)
        score each candidate by frequency (how many of A's followees follow them)

  Output: ranked recommendation list per user
  Written to: DynamoDB recommendations table — {userId → [suggestedUserId, score]}
  TTL: refreshed daily
```

---

#### 5.5.8 Scalability Summary

| Concern                       | Solution                                                                       |
| ----------------------------- | ------------------------------------------------------------------------------ |
| **130M follower celebrity**   | DynamoDB-only; no Redis SET; paginated fan-out via background workers          |
| **Follow write throughput**   | DynamoDB auto-scales; Kafka decouples fan-out from write path                  |
| **getFollowers read latency** | Redis for regular users (< 1ms); DynamoDB for celebrities (~5ms paginated)     |
| **Mutual follow check**       | Redis SISMEMBER on both users' following SET — O(1), < 1ms                     |
| **Graph analytics**           | Offline Spark jobs on daily S3 export — doesn't impact live traffic            |
| **Data consistency**          | DynamoDB Transactions ensure both `following` + `followers` written atomically |

### 5.6 Tweet Database — Cassandra

```sql
CREATE TABLE tweets (
    tweet_id      BIGINT PRIMARY KEY,    -- Snowflake ID (time-sortable)
    user_id       UUID,
    text          TEXT,
    media_urls    LIST<TEXT>,
    reply_to_id   BIGINT,
    quoted_id     BIGINT,
    lang          TEXT,
    like_count    COUNTER,
    retweet_count COUNTER,
    reply_count   COUNTER,
    created_at    TIMESTAMP,
    is_deleted    BOOLEAN
);

-- User's own tweet timeline (profile page)
CREATE TABLE user_timeline (
    user_id    UUID,
    tweet_id   BIGINT,
    created_at TIMESTAMP,
    PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);
```

### 5.7 Redis Cache Layout

Redis is the **central nervous system** of this architecture — it absorbs 90%+ of all read traffic. Each key namespace uses a specific Redis data type chosen for its exact access pattern. Using the wrong data type is a common mistake that wastes memory and adds latency.

---

#### 5.7.1 `feed:{userId}` → **LIST**

**Purpose:** Pre-built home timeline for each user — a ranked list of tweetIds ready to serve instantly.

**Why LIST?**
- Ordered (insertion order preserved) — Fan-out Workers `LPUSH` newest tweets at the head.
- `LRANGE` retrieves a page in O(S+N) where S = start offset, N = count — effectively O(1) for small pages.
- `LTRIM` automatically caps the list so it doesn't grow unbounded.
- No other data type gives you ordered, bounded, head-insertable sequences this efficiently.

```
Fan-out Worker (on tweet posted by user A):
  LPUSH feed:{followerB} {tweetId}      → insert at head (newest first)
  LPUSH feed:{followerC} {tweetId}
  LTRIM feed:{followerB} 0 799          → keep max 800 tweetIds (tail trimmed)

Feed Service (user B opens app):
  LRANGE feed:{userB} 0 19              → fetch first 20 tweetIds → O(1)
  LRANGE feed:{userB} 20 39             → next page (cursor-based pagination)
```

**Memory sizing:**
```
800 tweetIds × 8 bytes (BIGINT Snowflake ID) = 6.4 KB per user
500M users × 6.4 KB = ~3.2 TB total
Redis Cluster: 50 nodes × 64 GB RAM = 3.2 TB ✓
```

**TTL:** 24 hours. If a user hasn't opened the app in 24hr, their feed cache expires. On next login, the Feed Service rebuilds it by reading from Cassandra — acceptable cold-start latency (~200ms) vs wasting 6.4 KB per inactive user.

**Eviction:** `allkeys-lru` policy on the feed namespace shard. Least-recently-accessed user feeds evicted first when memory pressure is high.

---

#### 5.7.2 `tweet:{tweetId}` → **HASH**

**Purpose:** Full tweet content cache — avoids hitting Cassandra on every feed read. Feed Service fetches 20 tweetIds from the LIST, then needs the actual tweet content for each.

**Why HASH?**
- A HASH stores named fields — each tweet field is a separate hash field.
- You can fetch only the fields you need (`HMGET tweet:{id} text author_id like_count`) instead of the whole object.
- More memory-efficient than storing a JSON string (no repeated key names in memory, uses Redis ziplist encoding for small hashes).
- Allows atomic field-level updates: `HINCRBY tweet:{id} like_count 1` — no read-modify-write needed.

```
On tweet creation (Tweet Ingestion Service):
  HSET tweet:{tweetId}
    text          "Hello World"
    author_id     "uuid-of-alice"
    author_name   "Alice"
    created_at    "1700000000000"
    like_count    "0"
    retweet_count "0"
    media_urls    "[]"
    is_deleted    "false"
  EXPIRE tweet:{tweetId} 3600          → 1 hour TTL

Feed Service (enriching 20 tweetIds):
  PIPELINE:
    HMGET tweet:{id1} text author_name like_count created_at
    HMGET tweet:{id2} text author_name like_count created_at
    ...20 parallel HMGET in single round trip...
  → On cache miss: fetch from Cassandra → HSET back into Redis
```

**Memory sizing:**
```
~500 bytes per tweet HASH (fields + overhead)
Hot tweets (top 20% traffic): ~100M tweets × 500 bytes = ~50 GB
Comfortably fits in a 3-node Redis cluster shard
```

**TTL:** 1 hour. Viral tweets get cache hits continuously and their TTL resets on each write (like_count update), so they stay warm. Cold tweets expire and are re-fetched from Cassandra on next access.

---

#### 5.7.3 `following:{userId}` → **SET**

**Purpose:** The list of accounts that user A follows — used by the Fan-out Service on every tweet to identify which users' feeds to update, and by the Feed Service to identify which celebrities to pull-on-read.

**Why SET?**
- Unordered membership structure — we don't need order, just "is B in A's following list?".
- `SISMEMBER` is O(1) — instant mutual-follow check.
- `SINTER` computes intersection of two users' following lists — "you both follow Obama."
- `SCARD` returns follower count — O(1), no full scan needed.
- Deduplication is free — adding the same followeeId twice has no effect.

```
On follow(A, B):
  SADD following:{A} {B_userId}         → add to A's following set

On fan-out (tweet posted, need celebrity check):
  SISMEMBER celebrity_ids {authorId}    → is author a celebrity?
  SMEMBERS following:{userId}           → get all followees for intersection

Mutual follow check (show "Follows you" badge):
  PARALLEL:
    SISMEMBER following:{A} {B}         → does A follow B?
    SISMEMBER following:{B} {A}         → does B follow A?
  Both O(1), result in < 1ms

Common follows ("You and @alice both follow @obama"):
  SINTER following:{A} following:{B}    → O(min(|A|, |B|))
```

**Memory sizing:**
```
Avg 200 followees × 8 bytes = 1.6 KB per user
500M users × 1.6 KB = ~800 GB
Sharded across Redis Cluster (key: following:{userId} hashes to a slot)
```

**TTL:** 1 hour. On miss, the Fan-out Service loads from DynamoDB `following` table and populates Redis. The miss rate is low for active users since they tweet/open the app frequently, refreshing the TTL.

---

#### 5.7.4 `user_celebrity_follows:{userId}` → **SET**

**Purpose:** Tracks which celebrity accounts a specific user follows — used by the Feed Service to know which celebrity timelines to pull-on-read and merge into the feed.

**Why a separate SET from `following:{userId}`?**
- `following:{userId}` contains ALL followees (~200 entries). Scanning all 200 to find celebrities on every feed read is wasteful.
- This SET is a pre-filtered subset — only the celebrity followeeIds (accounts with >1M followers).
- Feed Service only needs this small set (~5–10 celebrities avg per user) to issue targeted pull-on-read fetches.

```
On follow(A, celebrity_B):
  SADD following:{A} {B}                → main following SET
  IF B in celebrity_ids:
    SADD user_celebrity_follows:{A} {B} → also add to celebrity subset

Feed Service (building feed for user A):
  SMEMBERS user_celebrity_follows:{A}   → ["obama_id", "elon_id", "cristiano_id"]
  For each celebrity → fetch latest 20 tweets from Cassandra user_timeline
  Merge with pre-built Redis LIST feed
```

**TTL:** 1 hour. Rebuilt from DynamoDB on miss by intersecting `following` set with `celebrity_ids` set.

---

#### 5.7.5 `trending:global` and `trending:{country}` → **ZSET (Sorted Set)**

**Purpose:** Real-time trending hashtags globally and per country/region — displayed in the Explore tab.

**Why ZSET?**
- A Sorted Set stores members with a floating-point score, sorted in ascending order.
- `ZINCRBY` atomically increments a hashtag's score (tweet velocity count) — O(log N).
- `ZREVRANGE trending:global 0 9` instantly returns top 10 hashtags by score — O(log N + M).
- `ZRANGEBYSCORE` can filter by score threshold (e.g., only hashtags with >1000 uses in the last hour).
- No other data structure gives you O(log N) ranked insertion + O(log N + M) top-K retrieval.

```
Flink Streaming Job (consumes hashtag events):
  On each HashtagUsed event { hashtag, country, timestamp }:
    ZINCRBY trending:global 1 "#SystemDesign"
    ZINCRBY trending:{country} 1 "#SystemDesign"

Feed / Explore Service:
  ZREVRANGE trending:global 0 9 WITHSCORES
  → ["#SystemDesign" 48291, "#AI" 32100, "#Cricket" 28500, ...]
  → Top 10 globally, with their velocity scores

Score decay (prevents old trends from staying forever):
  Flink applies time-decay: score = raw_count × e^(-λ × age_in_hours)
  Or: Flink resets ZSET every hour with fresh counts from sliding window
```

**Memory sizing:**
```
~50K unique hashtags globally at any time
50K × ~50 bytes avg = ~2.5 MB per ZSET (trivially small)
One ZSET per country (~200 countries) = ~500 MB total — fits easily
```

**TTL:** 15 minutes. Flink rewrites the ZSET every 15 minutes from its sliding window aggregation. Old ZSET expires, new one written atomically.

---

#### 5.7.6 `tweet_likes:{tweetId}` → **STRING (INCR counter)**

**Purpose:** Real-time like/retweet counts for tweets displayed in the feed. We cannot update Cassandra on every like — at peak, a viral tweet gets millions of likes/sec.

**Why STRING with INCR?**
- `INCR tweet_likes:{tweetId}` is an atomic O(1) counter increment — no race conditions.
- Absorbs millions of like events per second without touching Cassandra.
- Batch-synced to Cassandra every 5 minutes (periodic background job).

```
Like Service (user likes a tweet):
  INCR tweet_likes:{tweetId}            → atomic increment, < 0.1ms
  INCR tweet_retweets:{tweetId}         → retweet count
  Publish LikeEvent to Kafka            → for notification service

Background Sync Job (every 5 minutes):
  For each hot tweet:
    likes = GET tweet_likes:{tweetId}
    UPDATE tweets SET like_count = {likes} WHERE tweet_id = {tweetId}
    → Cassandra COUNTER column update

Feed Service (display counts):
  MGET tweet_likes:{id1} tweet_likes:{id2} ... tweet_likes:{id20}
  → Batch fetch all 20 counts in single round trip
  → Falls back to Cassandra like_count if Redis key missing (cold tweet)
```

**Memory sizing:**
```
Hot tweets (active for ~24hr): ~10M tweets × 16 bytes = ~160 MB (negligible)
```

**TTL:** No explicit TTL — key persists until the tweet goes cold (not accessed). Evicted by `allkeys-lru` policy when under memory pressure. On eviction, the count is already synced to Cassandra.

---

#### 5.7.7 Full Redis Namespace Summary

| Key Pattern | Type | Commands Used | TTL | Memory (total) |
|---|---|---|---|---|
| `feed:{userId}` | LIST | `LPUSH`, `LTRIM`, `LRANGE` | 24 hr | ~3.2 TB |
| `tweet:{tweetId}` | HASH | `HSET`, `HMGET`, `HINCRBY` | 1 hr | ~50 GB (hot) |
| `following:{userId}` | SET | `SADD`, `SREM`, `SISMEMBER`, `SINTER`, `SMEMBERS` | 1 hr | ~800 GB |
| `user_celebrity_follows:{userId}` | SET | `SADD`, `SMEMBERS` | 1 hr | ~5 GB |
| `trending:global` / `trending:{country}` | ZSET | `ZINCRBY`, `ZREVRANGE` | 15 min | ~500 MB |
| `tweet_likes:{tweetId}` | STRING | `INCR`, `GET`, `MGET` | LRU evicted | ~160 MB |
| `celebrity_ids` | SET | `SISMEMBER`, `SADD` | 1 hr | ~400 KB |

**Total Redis footprint:** ~4 TB → served by a 50-node Redis Cluster (80 GB RAM each).

---

#### 5.7.8 Redis Failure Handling

| Failure | Impact | Mitigation |
|---|---|---|
| **Node failure** | ~1/50 of keys unavailable | Replica promotion in <30s; cache miss falls back to Cassandra |
| **Feed key evicted** | User's feed must be rebuilt | Cold-start rebuild: read latest 800 tweetIds from Cassandra user_timeline table (~200ms) |
| **Tweet HASH evicted** | Content fetch misses cache | HMGET miss → Cassandra read → HSET back (cache-aside pattern) |
| **Redis cluster split** | Writes may go to wrong shard | Redis Cluster rejects writes during partition; clients retry after reconfig |
| **Full memory** | New writes rejected | `allkeys-lru` policy evicts coldest keys; add cluster nodes proactively at 75% usage |

---

## 6. Data Flow — Tweet Posted & Delivered

```
User A (regular, 500 followers) tweets "Hello World"
         │
         ▼ POST /tweet
  Tweet Ingestion Service
         │
  ┌──────┼──────────────────────────────────────┐
  ▼      ▼                                      ▼
Tweet DB  Kafka: TweetCreated             User A's timeline
(Cassandra)                               (user_timeline table, LPUSH)
         │
         ▼
Fan-out Worker (Kafka consumer)
  fetches followers of A: [B, C, D, ... 500 users]
         │
  Redis PIPELINE:
    LPUSH feed:B tweetId_X
    LPUSH feed:C tweetId_X
    ...500 LPUSHes...
    (LTRIM each list to 800)
         │
  ~500 Redis writes complete in < 50 ms (pipelined)

User B opens feed:
  LRANGE feed:B 0 19
  → tweetId_X is already there!
  Fetch tweet content → enriched → returned in < 10ms
```

---

## 7. Trending Topics

```
Tweet posted → extract hashtags → Kafka: HashtagUsed event
                                          │
                                  Flink Streaming Job
                                  (sliding window: last 1hr)
                                  counts per hashtag per geo-region
                                          │
                                  ZINCRBY trending:global #{hashtag} 1
                                  ZINCRBY trending:{country} #{hashtag} 1
                                          │
                                  ZREVRANGE trending:global 0 9
                                  → Top 10 trending globally (TTL: 15 min)

Trending score = velocity (events in last 1hr) weighted by geographic spread
```

---

## 8. Search

- **Elasticsearch** cluster — all tweets indexed on creation (via Kafka consumer).
- Full-text search over tweet text, hashtags, user mentions.
- **Recency bias** in ranking: `score = text_relevance × time_decay_factor`.
- Index sharded by time-range (tweets older than 7 days in cold index).
- Real-time indexing: < 10 sec from tweet creation to searchable.

---

## 9. Handling Edge Cases

| Scenario                     | Handling                                                                                            |
| ---------------------------- | --------------------------------------------------------------------------------------------------- |
| **New user (cold start)**    | No followees → show trending + recommended accounts → "Who to follow"                               |
| **User follows celebrity**   | FollowEvent → Kafka → pre-populate feed with celebrity's recent 10 tweets                           |
| **User unfollows**           | Remove from following SET; at next feed load, tweets naturally absent                               |
| **Tweet deleted**            | Mark `is_deleted = true` in Cassandra; remove from Redis cache; excluded from feeds on next refresh |
| **Inactive user feed**       | Feed cache expires (TTL: 24hr); rebuilt from scratch on next login                                  |
| **Fan-out backlog (peak)**   | Kafka consumer lag tolerated; eventual delivery within 5–30 sec                                     |
| **Redis node failure**       | Replica promotion; cache miss → rebuild from Cassandra on demand                                    |
| **Duplicate tweets in feed** | De-duplicate by tweetId when merging pre-built + celebrity pull feeds                               |

---

## 10. Scalability Deep-Dive

### Fan-out Bottlenecks & Solutions

```
Problem: 50K tweets/sec × 200 avg followers = 10M Redis writes/sec

Solutions:
1. Redis Cluster (horizontal sharding):
   feed:{userId} partitioned by userId
   → 50+ Redis nodes → 200K writes/sec per node (well within limits)

2. Async fan-out (Kafka buffers spike):
   Tweet ingestion returns immediately
   Fan-out happens asynchronously → user sees tweet in ~2-5 sec (acceptable)

3. Batch pipeline writes:
   Redis PIPELINE: group 1,000 LPUSHes → single round trip
   Reduces 10M RPC calls → 10K pipelined batches

4. Skip inactive users:
   If user hasn't logged in > 7 days → don't push to their feed cache
   → On login, rebuild feed from Cassandra
   → Reduces fan-out by ~40% (inactive user ratio)
```

### Read Scalability

```
300B feed reads/day = 3.5M req/sec
  → 90%+ served from Redis (pre-built feed cache)
  → Feed Service is stateless — scale horizontally (100+ instances)
  → Redis Cluster: read replicas per shard
  → CDN for static assets (profile pics, media thumbnails)
```

---

## 11. Consistency Model

| Operation         | Consistency                         | Reason                                |
| ----------------- | ----------------------------------- | ------------------------------------- |
| Tweet storage     | Strong (Cassandra quorum write)     | Never lose a tweet                    |
| Feed delivery     | Eventual (< 5 sec)                  | Slight lag acceptable                 |
| Like / RT counts  | Eventual (Redis → periodic DB sync) | Approximate counts fine               |
| Follow / Unfollow | Eventual (< 30 sec propagation)     | Fan-out is async                      |
| Search indexing   | Eventual (< 10 sec)                 | Near-real-time acceptable             |
| Profile data      | Strong read-after-write             | User expects to see their own changes |

---

## 12. Key Design Decisions & Trade-offs

| Decision                | Choice                                     | Trade-off                                                            |
| ----------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| **Fan-out model**       | Hybrid push/pull (threshold ~1M followers) | Optimal balance of write amp vs read latency                         |
| **Tweet ID**            | Snowflake (time-sortable)                  | Global uniqueness + chronological ordering without DB sort           |
| **Feed cache**          | Redis LIST per user                        | O(1) feed read; 3.2 TB total — feasible                              |
| **Feed ranking**        | ML two-stage model                         | Better engagement than pure reverse-chron; latency cost              |
| **Tweet DB**            | Cassandra                                  | Write-optimized; sorted by tweetId (time order); scales to 500GB/day |
| **Skip inactive users** | 7-day inactivity threshold                 | Reduces fan-out 40%; rebuild on login                                |
| **Trending**            | Flink sliding window + Redis ZSET          | Real-time; expires naturally                                         |

---

## 13. Monitoring & Observability

| Signal                     | Metric / Alert                             |
| -------------------------- | ------------------------------------------ |
| **Feed load latency**      | P99 < 200ms; alert if > 400ms              |
| **Fan-out lag**            | Kafka consumer lag; alert if > 1M messages |
| **Redis memory**           | Utilization > 80%; add cluster nodes       |
| **Tweet DB write latency** | P99 < 50ms; alert if > 100ms               |
| **Feed cache hit rate**    | > 95%; alert if < 90%                      |
| **Celebrity pull latency** | P99 < 50ms for celebrity timeline fetch    |

---

## 14. Future Enhancements
- **For You tab** — Twitter-recommended content beyond followees (ML-driven).
- **Federated timeline** — incorporate replies and likes from mutual follows.
- **Context annotations** — label misinformation / satire on tweets.
- **Graph neural network** — use social graph structure for better affinity scoring.
- **Edge caching** — push hot celebrity tweets to CDN edge for fastest global delivery.

---

*Document prepared for SDE 3 system design interviews. Focus areas: hybrid fan-out (push vs pull), Snowflake ID generation, ML feed ranking, Redis LIST as feed cache, celebrity problem, and Kafka-based async fan-out.*
