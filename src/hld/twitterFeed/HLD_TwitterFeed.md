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
| Property | Target |
|---|---|
| **Feed load latency** | < 200 ms P99 |
| **Tweet post latency** | < 500 ms P99 (accepted) |
| **Fan-out latency** | Tweet in followers' feeds within 5 sec |
| **Availability** | 99.99% for read (feed); 99.9% for write (tweet) |
| **Scale** | 500M DAU, 500M tweets/day, 300B feed reads/day |
| **Read : Write ratio** | 1000 : 1 (extremely read-heavy) |
| **Consistency** | Eventual — slight delay in feed is acceptable |

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

Stores follow/follower relationships.

```
Storage:
  Following list (who does user A follow?):
    → Redis SET: following:{userId} → {followeeId1, followeeId2, ...}
    → DynamoDB for users with > 10K follows (overflow)

  Follower list (who follows user A?):
    → DynamoDB (partition: userId, sort: followerId)
    → Redis for regular users (< 1M followers): followers:{userId} SET

Operations:
  follow(A, B):
    → ADD B to following:{A}
    → ADD A to followers:{B}
    → Kafka: FollowEvent → Fan-out Service (deliver B's recent tweets to A's feed)

  getFollowers(userId, limit, cursor):
    → paginated scan of DynamoDB followers table
```

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

| Key | Type | Content | TTL |
|---|---|---|---|
| `feed:{userId}` | LIST | Ordered tweetIds (pre-built feed) | 24 hr |
| `tweet:{tweetId}` | HASH | Full tweet object | 1 hr |
| `following:{userId}` | SET | Set of followeeIds | 1 hr |
| `user_celebrity_follows:{userId}` | SET | Celebrity followeeIds | 1 hr |
| `trending:global` | ZSET | hashtag → score | 15 min |
| `tweet_likes:{tweetId}` | COUNTER | Like count | Sync every 5 min |

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

| Scenario | Handling |
|---|---|
| **New user (cold start)** | No followees → show trending + recommended accounts → "Who to follow" |
| **User follows celebrity** | FollowEvent → Kafka → pre-populate feed with celebrity's recent 10 tweets |
| **User unfollows** | Remove from following SET; at next feed load, tweets naturally absent |
| **Tweet deleted** | Mark `is_deleted = true` in Cassandra; remove from Redis cache; excluded from feeds on next refresh |
| **Inactive user feed** | Feed cache expires (TTL: 24hr); rebuilt from scratch on next login |
| **Fan-out backlog (peak)** | Kafka consumer lag tolerated; eventual delivery within 5–30 sec |
| **Redis node failure** | Replica promotion; cache miss → rebuild from Cassandra on demand |
| **Duplicate tweets in feed** | De-duplicate by tweetId when merging pre-built + celebrity pull feeds |

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

| Operation | Consistency | Reason |
|---|---|---|
| Tweet storage | Strong (Cassandra quorum write) | Never lose a tweet |
| Feed delivery | Eventual (< 5 sec) | Slight lag acceptable |
| Like / RT counts | Eventual (Redis → periodic DB sync) | Approximate counts fine |
| Follow / Unfollow | Eventual (< 30 sec propagation) | Fan-out is async |
| Search indexing | Eventual (< 10 sec) | Near-real-time acceptable |
| Profile data | Strong read-after-write | User expects to see their own changes |

---

## 12. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Fan-out model** | Hybrid push/pull (threshold ~1M followers) | Optimal balance of write amp vs read latency |
| **Tweet ID** | Snowflake (time-sortable) | Global uniqueness + chronological ordering without DB sort |
| **Feed cache** | Redis LIST per user | O(1) feed read; 3.2 TB total — feasible |
| **Feed ranking** | ML two-stage model | Better engagement than pure reverse-chron; latency cost |
| **Tweet DB** | Cassandra | Write-optimized; sorted by tweetId (time order); scales to 500GB/day |
| **Skip inactive users** | 7-day inactivity threshold | Reduces fan-out 40%; rebuild on login |
| **Trending** | Flink sliding window + Redis ZSET | Real-time; expires naturally |

---

## 13. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Feed load latency** | P99 < 200ms; alert if > 400ms |
| **Fan-out lag** | Kafka consumer lag; alert if > 1M messages |
| **Redis memory** | Utilization > 80%; add cluster nodes |
| **Tweet DB write latency** | P99 < 50ms; alert if > 100ms |
| **Feed cache hit rate** | > 95%; alert if < 90% |
| **Celebrity pull latency** | P99 < 50ms for celebrity timeline fetch |

---

## 14. Future Enhancements
- **For You tab** — Twitter-recommended content beyond followees (ML-driven).
- **Federated timeline** — incorporate replies and likes from mutual follows.
- **Context annotations** — label misinformation / satire on tweets.
- **Graph neural network** — use social graph structure for better affinity scoring.
- **Edge caching** — push hot celebrity tweets to CDN edge for fastest global delivery.

---

*Document prepared for SDE 3 system design interviews. Focus areas: hybrid fan-out (push vs pull), Snowflake ID generation, ML feed ranking, Redis LIST as feed cache, celebrity problem, and Kafka-based async fan-out.*
