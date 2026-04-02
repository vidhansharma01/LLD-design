# HLD — K Most Shared Articles Across Multiple Time Windows

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** Real-Time Analytics / Stream Processing / Leaderboard Systems
> **Related Patterns:** Top-K, Sliding Window Aggregation, Kappa Architecture

---

## Table of Contents

1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [Multi-Window Aggregation — Circular Bucket Strategy](#51-multi-window-aggregation--circular-bucket-strategy)
   - 5.2 [Count-Min Sketch + Space-Saving for Top-K](#52-count-min-sketch--space-saving-for-top-k)
   - 5.3 [Redis Sorted Sets as Real-Time Leaderboard](#53-redis-sorted-sets-as-real-time-leaderboard)
   - 5.4 [Stream Processing with Apache Flink](#54-stream-processing-with-apache-flink)
   - 5.5 [Deduplication and Exactly-Once Counting](#55-deduplication-and-exactly-once-counting)
6. [Scale and Resilience](#6-scale-and-resilience)
7. [Trade-offs and Alternatives](#7-trade-offs-and-alternatives)
8. [Observability and Security](#8-observability-and-security)
9. [SDE 3 Signals Checklist](#9-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Functional Requirements

| # | Requirement |
|---|---|
| FR-1 | Track **share events** for articles in real time (social share, email, copy-link) |
| FR-2 | Return **Top-K most shared articles** for each window: **5 min**, **1 hour**, **24 hours** |
| FR-3 | All three windows must be queryable **simultaneously** and **independently** |
| FR-4 | Support **per-category** Top-K (e.g., Top-K in Politics, Sports, Tech) |
| FR-5 | Each article returned should include its share count within that window |
| FR-6 | **Deduplication**: same user sharing same article twice in a session counts once |
| FR-7 | Support **historical snapshots**: "What was Top-K at 3 PM yesterday?" |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Write throughput** | 50,000 share events/sec sustained; 200,000 peak (breaking news) |
| **Read throughput** | 500,000 reads/sec (served via CDN) |
| **Read latency** | p99 < 20 ms |
| **Freshness** | 5-min window refreshed every 30 s; 1h and 24h every 60 s |
| **Availability** | 99.99% — Top-K always readable, even during failures |
| **Accuracy** | True Top-K items present with >= 99% probability |
| **Retention** | Share events: 30 days; snapshots: 90 days |

### Clarifying Questions to Ask

```
1. What counts as a "share"? Social? Copy-link? Email? WhatsApp?
2. Deduplication scope: per-session? per-day? per-user-per-article?
3. Does sharing same article on FB + Twitter count as 1 or 2?
4. K = how many? Top-10? Top-100? Fixed or variable?
5. Per-region Top-K needed? (US vs. India vs. UK)
6. Is approximate Top-K acceptable (99%+ accuracy)?
```

### What Makes This Problem Hard

```
Three concurrent time windows with very different scales:
  5-min:   smallest window, highest precision, changes fastest
  1-hour:  medium window, balance of freshness and stability
  24-hour: largest window, most stable but needs efficient expiry

Key challenges:
  1. Sliding vs. tumbling: "last 5 minutes" slides every second
  2. Multi-window management: each window has different bucket count and retention
  3. Hot articles (breaking news): one article gets 90% of all shares
  4. Deduplication at scale: tracking 200K events/sec for per-user dedup
  5. Exactly-once counting across failures and stream replays
```

### Out of Scope

- Full personalized recommendation engine  
- Article content analysis / NLP signals  
- Comment / like / view tracking (separate systems)

---

## 2. Capacity Estimation

### Assumptions

| Parameter | Value |
|---|---|
| Total articles | 50 million |
| Active articles (shared in last 24h) | 500,000 |
| DAU | 200 million |
| Avg shares per user per day | 3 |
| Peak share RPS | 200,000 events/sec |
| Sustained share RPS | 50,000 events/sec |
| Event payload size | ~200 bytes |
| K (top articles) | 100 per window per category |
| Categories | 20 |

### Write Side

```
Sustained:  50K events/sec  x  200 bytes = 10 MB/s incoming
Peak:       200K events/sec x  200 bytes = 40 MB/s incoming

Daily events: 200M users x 3 shares = 600M events/day
  600M x 200 bytes = 120 GB raw events/day

Kafka (RF=3, 30-day retention):
  120 GB x 3 replicas = 360 GB/day
  360 GB x 30 days    = ~10.8 TB total

Kafka partition count:
  Peak 200K events/sec / 10K events/partition = 20 partitions
  Add 5x safety -> 100 partitions (key = article_id % 100)
  5 brokers x 2 TB SSD = 10 TB total
```

### Flink State

```
Per article, per window — circular bucket array:
  5-min  window: 30 buckets  (one per 10 seconds)
  1-hour window: 60 buckets  (one per minute)
  24-hour window: 144 buckets (one per 10 minutes)

Total buckets: 30 + 60 + 144 = 234
Per article state: 234 x 8 bytes = 1,872 bytes ~ 2 KB

Active articles: 500,000
Per Flink task (100 partitions, 5K articles/task):
  5,000 x 2 KB = 10 MB state per task
Total: 100 tasks x 10 MB = 1 GB (easily fits in memory)
```

### Redis

```
Per ZSET (one per window per category):
  Windows: 3 | Scopes: 21 (1 global + 20 categories) | Total: 63 ZSETs
  Active articles per ZSET: 500,000
  Per entry: ~70 bytes (article_id + score + skiplist overhead)
  Per ZSET: 500K x 70 = 35 MB
  Total: 63 x 35 MB = 2.2 GB

Plus article metadata cache: ~500K x 200B = 100 MB
Total Redis: ~5 GB -> single 16 GB Redis Cluster (3 master + 3 replica)
```

### Read Side and Freshness Budget

```
Reads: 500K/sec
CDN cache hit: 99%+ (same Top-K for all users)
  Origin receives ~5K reads/sec (one miss per CDN PoP per TTL)

CDN bandwidth: 500K x 30 KB = 15 GB/s (handled by CDN globally)

Freshness budget for 5-min window (target: <= 30 s):
  Share event -> Kafka publish:       <  5 ms
  Kafka -> Flink consumption:         <  1 s
  Flink 10-second micro-bucket:       < 10 s
  Flink -> Redis ZADD flush:          <  1 s
  CDN TTL:                            < 20 s
  Total:                              < 27 s  OK
```

---

## 3. API Design

### Read API (Consumer-Facing)

```http
GET /v1/trending/articles
    ?window=5min        # 5min | 1hour | 24hour
    &category=sports    # optional; omit for global
    &k=10               # 1..100, default 10
    &cursor=            # opaque pagination cursor

Response 200:
{
  "window": "5min",
  "category": "sports",
  "generated_at": "2026-04-01T10:00:30Z",
  "valid_until":  "2026-04-01T10:01:00Z",
  "articles": [
    {
      "rank": 1,
      "article_id": "art_789xyz",
      "title": "Breaking: Championship Final Result",
      "url": "https://news.example.com/sports/championship",
      "thumbnail": "https://cdn.example.com/img/art_789.jpg",
      "share_count": 45231,
      "rank_change": "+3",
      "category": "sports",
      "published_at": "2026-04-01T09:00:00Z"
    }
  ],
  "next_cursor": "eyJvZmZzZXQiOjEwfQ=="
}


GET /v1/trending/articles/{article_id}/rank
    ?window=24hour&category=global
Response 200:
{
  "article_id": "art_789xyz",
  "window": "24hour",
  "rank": 7,
  "share_count": 123456,
  "percentile": 99.9
}


GET /v1/trending/articles/history
    ?window=1hour&at=2026-04-01T09:00:00Z&category=global&k=10
Response 200: (same structure, served from PostgreSQL snapshot)
```

### Write API (Internal)

```http
POST /v1/internal/share-events
{
  "article_id":  "art_789xyz",
  "user_id":     "usr_abc123",
  "platform":    "twitter",
  "session_id":  "sess_xyz",
  "timestamp":   "2026-04-01T10:00:01.234Z",
  "category":    "sports"
}
Response 202 Accepted
{ "event_id": "evt_123", "status": "accepted" }


POST /v1/internal/share-events/batch
{ "events": [ {...}, {...} ] }  // up to 100 per batch
```

---

## 4. High-Level Architecture

```
SHARE EVENT SOURCES
  [Web App]  [Mobile iOS/Android]  [3rd Party API]
       |
       v  POST /v1/internal/share-events
SHARE SERVICE (stateless, 10 pods)
  1. Validate event
  2. Dedup: Bloom Filter (user+article+day)
  3. Publish to Kafka (acks=1)
  4. Return 202 Accepted
       |
       v  article_id % 100 -> partition
KAFKA CLUSTER
  Topic: article-shares  (100 partitions, RF=3, 30-day retention)
  Topic: dedup-bloom-sync (10 partitions, for cross-pod Bloom sync)
       |
  +----+------------------------+
  |    |                        |
  v    v                        v
FLINK JOB 1         FLINK JOB 2         S3 ARCHIVER
ShareEventAggregator  TopKUpdater        (raw events -> Parquet)
  - Circular buffer   - Rolling window
  - per article         sums (5min, 1h,
  - per partition       24h)
  - Emits deltas      - Flushes to Redis
    every 10 s          every 30 s
  |                        |
  v                        v
Kafka [article-counts-10s] -> Redis Cluster
                              (63 ZSETs: 3 windows x 21 scopes)
                              topk:5min:global
                              topk:1hour:sports
                              ...
                                   |
                                   v
                         TRENDING API SERVICE (5 pods)
                           - ZREVRANGE top 100
                           - HMGET article metadata
                           - Enrich rank_change
                           - Cache-Control: max-age=30
                                   |
                                   v
                         CDN (CloudFront / Fastly)
                           TTL: 5min->30s, 1h->60s, 24h->120s
                           Cache key: window + category + k
                           99%+ cache hit rate
                                   |
                                   v
                             End Users (500K rps)

SNAPSHOT SERVICE (side car)
  - Hourly Top-K snapshots -> PostgreSQL (7d hot) + S3 (90d cold)
  - Serves /v1/trending/articles/history queries
```

---

## 5. Deep Dives

### 5.1 Multi-Window Aggregation — Circular Bucket Strategy

#### The Three Window Problem

```
We need three CONCURRENT sliding windows:
  W1: last  5 minutes  -> captures viral / breaking content
  W2: last  1 hour     -> broader trending signal
  W3: last 24 hours    -> daily trending

"Sliding window" means: as time moves, oldest events expire automatically.

Exact approach: track each event's timestamp across N seconds.
  5-min exact: 300 buckets/article x 8B x 500K articles = 1.2 GB  -- too large
  Our approach: micro-bucket (10s granularity) = 30 buckets/article -- 10x cheaper

Accuracy trade-off: at most +-10 s edge-bucket error.
For a news trending use case: +-10 seconds is completely acceptable.
```

#### Bucket Configuration

```
Window    | Bucket size | # Buckets | Accuracy
----------|-------------|-----------|----------------
5 min     | 10 seconds  |     30    | +-10 seconds
1 hour    | 60 seconds  |     60    | +-60 seconds
24 hours  | 10 minutes  |    144    | +-10 minutes
```

#### Circular Buffer Mechanics (Flink State per Article)

```java
// Pseudocode for per-article state maintained in Flink task

class ArticleWindowState {
    long[] buckets5min  = new long[30];   // 10s buckets
    long[] buckets1h    = new long[60];   // 1m buckets
    long[] buckets24h   = new long[144];  // 10m buckets
    int ptr5min = 0, ptr1h = 0, ptr24h = 0;

    void onShareEvent(long eventTs) {
        // 5-min window
        int slot = (int)((eventTs / 10_000) % 30);
        if (slot != ptr5min) {
            clearSlots(buckets5min, ptr5min, slot);   // zero out expired buckets
            ptr5min = slot;
        }
        buckets5min[slot]++;

        // 1-hour window
        int slotH = (int)((eventTs / 60_000) % 60);
        if (slotH != ptr1h) { clearSlots(buckets1h, ptr1h, slotH); ptr1h = slotH; }
        buckets1h[slotH]++;

        // 24-hour window
        int slot24 = (int)((eventTs / 600_000) % 144);
        if (slot24 != ptr24h) { clearSlots(buckets24h, ptr24h, slot24); ptr24h = slot24; }
        buckets24h[slot24]++;
    }

    long getScore5min()  { return sum(buckets5min); }
    long getScore1h()    { return sum(buckets1h);   }
    long getScore24h()   { return sum(buckets24h);  }
}

// clearSlots() zeroes expired buckets LAZILY (only on slot advance).
// Inactive articles cost ZERO CPU -- old data cleared on next access.
```

#### Why This Design Wins

| Property | Per-second exact | Micro-bucket (our choice) |
|---|---|---|
| State per article | 300+ x 8B | 234 x 8B |
| Total state (500K articles) | 1.2 GB+ | ~1 GB |
| Accuracy | Exact | +-10 seconds |
| Complexity | High (exact expiry triggers) | Low (circular pointer) |
| CPU per event | Constant | Constant |

---

### 5.2 Count-Min Sketch + Space-Saving for Top-K

**When to use:** Active articles exceed Redis ZSET memory capacity (millions vs. our 500K).

#### Count-Min Sketch

```
2D array of w x d counters. Estimates frequencies in sublinear space.

Parameters (1% error, 99% confidence):
  e = 0.001, d = 0.01
  d_rows = ceil(ln(1/0.01)) = 5
  w_cols = ceil(2.718 / 0.001) = 2,718
  Memory: 5 x 2,718 x 4 bytes = 54 KB -- for ALL 50M articles!

Update (share event for article X):
  for row i in 1..5:
    sketch[i][hash_i(X) % 2718] += 1

Query (estimate share count of article X):
  return min(sketch[i][hash_i(X) % 2718] for i in 1..5)

Error bound: estimated <= true + 0.001 x N
  For 24h at 600M events: error <= 600,000 over-count
  Top articles have millions of shares -> ranked correctly
```

#### Space-Saving Algorithm (Heavy Hitters)

```
Maintains exactly k (article_id, count, maxError) triples.
Guarantees: Any article with true count > N/k is always present.

For Top-100 at 100x safety (k=10,000):
  Guaranteed present if count > 600M / 10,000 = 60,000 shares.

On new share event for article A:
  if A in tracker:       tracker[A].count++
  elif slots available:  tracker[A] = {count:1, error:0}
  else:
    min_entry = tracker.min()             // O(1) with built-in min-heap
    tracker[A] = {count: min_entry.count + 1, error: min_entry.count}
    tracker.remove(min_entry)

Final Top-K: sort by (count - maxError) desc, pick top K.

Memory: 10,000 x 32 bytes = 320 KB per window
        3 windows x 20 categories x 320 KB = ~19 MB per Flink task
```

#### When to Use Each Approach

```
Active articles < 1M AND Redis memory available:
  -> Exact Redis ZSET (our default: 500K articles x 70B = 35 MB)
  -> Simpler, accurate, fast ZREVRANGE

Active articles > 5M OR memory constraints:
  -> Flink: Count-Min Sketch + Space-Saving (in streaming layer)
  -> Output only Top-1000 candidates to Redis ZSET
  -> Redis ZSET holds 1000 x 70B = 70 KB per ZSET

For this design: use exact Redis ZSETs.
Mention sketches as the scalability path for 50M+ articles.
```

---

### 5.3 Redis Sorted Sets as Real-Time Leaderboard

#### ZSET Operations

```
Write path — Flink flushes every 30 seconds using pipelined ZADD:

  PIPELINE:
    ZADD topk:5min:global  45231 art_789     // set EXACT current window score
    ZADD topk:5min:sports  12000 art_789     // same article, category ZSET
    ZADD topk:1hour:global 180000 art_222
    ZADD topk:24hour:global 2100000 art_333
    ...
  EXEC

  Why ZADD (not ZINCRBY)?
  -> ZADD sets the absolute score (idempotent on replay)
  -> ZINCRBY is additive: double-counted on Flink failure + replay

Read path:
  ZREVRANGE topk:5min:global 0 99 WITHSCORES   // O(log N + K)
  -> Returns: [art_789, 45231, art_456, 41000, ..., art_100, 1205]

  ZREVRANK topk:5min:global art_789    // rank of specific article (0-indexed)
  ZSCORE   topk:5min:global art_789    // score of specific article
```

#### Expiring Articles from ZSETs

```
Problem: Articles shared yesterday still exist in 24h ZSET with stale scores.
         ZSETs do NOT auto-expire individual members.

Solution (Flink-driven lazy expiry):
  When Flink computes new window score for an article:
  -> If circular buffer sum == 0 (all buckets zeroed):
       ZREM topk:5min:global art_123    // remove from leaderboard
  -> Otherwise:
       ZADD topk:5min:global <new_score> art_123

  Flink always knows which articles went silent -> zero writes for idle articles.

Backup: Periodic Lua script trims ZSET to max 100K members:
```

```lua
-- Atomic: update score AND trim ZSET to maxSize members
local key     = KEYS[1]
local article  = ARGV[1]
local newScore = ARGV[2]
local maxSize  = ARGV[3]

redis.call('ZADD', key, newScore, article)
local size = redis.call('ZCARD', key)
if size > tonumber(maxSize) then
  redis.call('ZREMRANGEBYRANK', key, 0, size - tonumber(maxSize) - 1)
end
return redis.call('ZREVRANK', key, article)
```

---

### 5.4 Stream Processing with Apache Flink

#### Two-Job Architecture

```
JOB 1: ShareEventAggregator
  Input:  Kafka [article-shares], 100 partitions
  Output: Kafka [article-counts-10s]

  - 1 Flink task per Kafka partition (100 tasks)
  - Each task maintains ArticleWindowState for its articles (5K articles/task)
  - Every 10 seconds (tumbling trigger):
      emit {article_id, delta_5min, delta_1h, delta_24h}
      "delta" = shares added in THIS 10s bucket
  - State backend: RocksDB (spills to NVMe if needed)
  - Checkpoint: every 60s to S3 (incremental)

  Why emit deltas not cumulative totals?
  -> Smaller messages downstream
  -> Job 2 independently accumulates; safe to restart separately
  -> Less coupling between jobs

JOB 2: TopKUpdater
  Input:  Kafka [article-counts-10s]
  Output: Redis ZSETs (direct pipelined ZADD)

  - Maintains rolling window totals per article
  - Every 30 seconds: flush scores to all 63 Redis ZSETs
  - Triggers ZREM for zero-score articles
  - Checkpoint: every 60s to S3
```

#### Flink Watermarks for Out-of-Order Events

```
Problem: Network delays cause out-of-order events.
  Share at t=10:00:05 arrives at Flink at t=10:00:09.
  The 10:00:00-10:00:10 bucket may already be closed.

Solution: Event-time with 5-second allowed lateness.

  WatermarkStrategy
    .forBoundedOutOfOrderness(Duration.ofSeconds(5))
    .withTimestampAssigner(e -> e.timestamp)

  Watermark = max(event_timestamp_seen) - 5 seconds
  Events arriving up to 5s late: processed in correct bucket
  Events arriving >5s late:      dropped (tracked in late_events_dropped metric)

Result: 99.9%+ events correctly bucketed.
```

#### Flink Recovery

```
State backend: RocksDB (embedded, writes to local NVMe, snapshots to S3)
Checkpoint interval: 60 seconds
  On failure: restore from last checkpoint
  Re-process: idempotent (same events + same state = same circular buffer)

Recovery time:
  Download checkpoint from S3: ~30 s for 1 GB state
  Restore RocksDB state:       ~10 s
  Start consuming from saved Kafka offset: immediate
  Total:                       ~40 s  (acceptable for trending use case)

Exactly-once semantics:
  Source (Kafka offsets) committed atomically with checkpoint
  Sink (Redis ZADD) is idempotent
  -> No double-counting even on retry
```

---

### 5.5 Deduplication and Exactly-Once Counting

#### The Dedup Problem

```
User shares article, then:
  1. App retries (network error)    -> same event published twice
  2. User double-taps share button  -> two events within 1 second
  3. User shares on FB + Twitter    -> two events, same intent

Rules:
  Same user + article + day   -> count ONCE (dedup key = hash(user_id:article_id:date))
  Same user + article + different day -> count AGAIN (genuine re-share)
  Different platforms same day -> count ONCE
```

#### Bloom Filter Deduplication

```
Bloom Filter per pod per day:
  n = 200M users x 3 shares/day / 10 pods = 60M per pod
  p = 0.001 (0.1% false positive rate -- acceptable)

  Size = -(n * ln(p)) / (ln(2)^2) = 86 MB per pod  -- fits in pod RAM

Per-pod routing: route by user_id % 10 to specific pod
  -> Each pod handles 1/10th of users -> 86 MB / 10 = 8.6 MB per pod

Or: Use Redis Bloom Filter (RedisBloom module):
  BF.ADD  dedup:2026-04-01 "usr_123:art_789"    // O(1)
  BF.EXISTS dedup:2026-04-01 "usr_123:art_789"  // O(1)
  -> Shared across all Share Service pods (pods fully stateless)
  -> TTL: expire at midnight (day boundary)

False positive consequence: 0.1% of unique shares counted as duplicates
  -> Minor under-count; article still correctly ranked

Second guard (Flink):
  Flink maintains session-level Bloom Filter per Kafka partition
  Catches in-flight duplicates that bypass the service layer
```

#### Idempotency Under Replay

```
Problem: Flink task restarts, replays 60s of events from Kafka.
         Same share event processed twice.

If using ZINCRBY:
  First process: ZINCRBY topk:5min:global  +47 art_789   // score = 45231
  On replay:     ZINCRBY topk:5min:global  +47 art_789   // score = 45278 WRONG!

If using ZADD (our approach):
  Flink restores exact circular buffer from checkpoint
  Same events -> same circular buffer state -> same sum -> same score
  ZADD topk:5min:global 45231 art_789  (on original run)
  ZADD topk:5min:global 45231 art_789  (on replay)   CORRECT: idempotent!

Key insight: Absolute score from Flink circular buffer = idempotent sink.
```

---

## 6. Scale and Resilience

### Breaking News Hotspot (Celebrity Article Problem)

```
Scenario: Major election result breaks.
  art_789 receives 80% of ALL share events.
  Kafka partition: article_id % 100 -> all events hit partition 17.
  Normal load: 200K / 100 = 2K events/sec per partition.
  Hot load:    160K events/sec on partition 17 -- 80x overload!

Mitigation 1 -- Key salting (detect hot articles):
  Share Service monitors real-time rate per article_id (sliding window counter)
  If article_id rate > 10K events/sec:
    Set hot_article flag in metadata store (TTL 5min)
  Hot article producers: use key = article_id + "#" + random(0..9)
    -> Spreads hot article across 10 sub-partitions
  Flink: 10 tasks each locally count, second-stage combiner task aggregates

Mitigation 2 -- Client-side batching:
  Share Service pods buffer events for same article within 100ms window
  200 clients share art_789 in 100ms -> 1 Kafka message with count=200
  -> Reduces Kafka throughput by 200x for hot article

Mitigation 3 -- Dedicated hot topic:
  For known events (World Cup Final, election night): pre-provision
  a separate Kafka topic with 500 partitions for expected hot articles
```

### Failure Scenarios

| Failure | Detection | Impact | Recovery |
|---|---|---|---|
| **Share Service pod crash** | K8s liveness probe -> restart in 10s | Minor: events delayed | Client SDK retries; Bloom state in Redis survives |
| **Kafka broker failure** | Kafka ISR alert | Partitions unavailable <30s | Leader election from ISR replicas |
| **Flink task crash** | JobManager heartbeat | Job pauses processing | JM restarts task, restores from S3 checkpoint (<60s lag) |
| **Flink checkpoint fail** | Checkpoint timeout metric | No state persisted | Alert ops; job continues but recovery window grows |
| **Redis primary crash** | Redis Sentinel detects | Reads/writes paused ~5-10s | Redis Cluster promotes replica; ZSETs preserved |
| **Redis data loss** | ZCARD = 0 on all ZSETs | Top-K returns empty | Fallback: serve PostgreSQL snapshot; Flink rewrites ZSETs |
| **CDN outage** | Synthetic monitors | All users hit origin | Auto-scale origin pods; Redis handles 500K reads/sec directly |

### Redis Data Loss Recovery

```
Step 1: Flink Job 2 detects ZCARD = 0 on ZSETs (health check every 30s)
Step 2: Warm-up mode:
  -> Read last snapshot from PostgreSQL (< 1 hour old)
  -> Bulk ZADD 100 articles per ZSET (63 ZSETs -> 6,300 ZADD ops) in < 5 seconds
Step 3: Flink resumes normal operation; live scores overwrite snapshot data
Step 4: Accuracy restored to real-time within ~5 minutes

During recovery: serve snapshot-based Top-K with response header:
  X-Trending-Freshness: snapshot
  X-Snapshot-Age: 3245  (seconds since last snapshot)
```

### Multi-Region Architecture

```
Regional Top-K (US, EU, AP-SOUTHEAST):
  Each region runs full stack (Kafka + Flink + Redis + API)
  No cross-region coordination for regional Top-K
  -> Zero cross-region latency
  -> US Top-K reflects US shares; EU Top-K reflects EU shares

Global Top-K:
  Separate Flink job "GlobalAggregator" reads from all regional Kafka
  Merges regional counts -> writes to a Global Redis ZSET
  Latency: +100-200ms (cross-region replication)

Failover:
  DNS failover to healthy region (TTL=60s)
  Circuit Breaker: if Redis error rate > 5% -> serve PostgreSQL snapshot
  Fallback circuit: API -> Redis -> PostgreSQL snapshot -> S3 static file
```

---

## 7. Trade-offs and Alternatives

### Key Design Decisions

| Decision | Choice Made | Alternative | Rationale |
|---|---|---|---|
| **Window type** | Micro-buckets (circular buffer) | Per-second exact sliding | 10x less state; +-10s accuracy OK for news trending |
| **Stream processor** | Apache Flink | Kafka Streams, Spark Streaming | RocksDB state for large state; native event-time; exactly-once; lowest latency |
| **Leaderboard store** | Exact Redis ZSET | Count-Min Sketch + Heap | Only 500K active articles (35 MB); accuracy is higher with exact approach |
| **Score update** | Batch ZADD every 30s | ZINCRBY per event | 200K ZINCRBY/sec saturates Redis; batching reduces to ~500 ZADD/sec |
| **Dedup strategy** | Bloom filter (0.1% FP) | Exact Redis SET | Exact set needs 600M keys/day; 0.1% under-count acceptable |
| **Read serving** | CDN + 30s TTL | Server-side Redis cache | Same global response for all users; CDN reduces origin load 99%+ |
| **Historical queries** | PostgreSQL snapshots + S3 | Replay Kafka | Snapshot is O(1); Kafka replay takes minutes per day of history |
| **Architecture** | Kappa (stream-only) | Lambda (batch + stream) | One codebase; Flink can replay Kafka for correction; simpler ops |

### Flink vs. Kafka Streams vs. Spark Streaming

| Feature | Flink | Kafka Streams | Spark Streaming |
|---|---|---|---|
| **Latency** | Event-time, milliseconds | Event-time, milliseconds | Micro-batch, seconds |
| **State backend** | RocksDB (large, disk-spill) | RocksDB (limited) | In-memory (limited) |
| **Exactly-once** | Native (2PC) | Within Kafka | With idempotent sinks |
| **Deployment** | Separate cluster | Embedded library | Spark cluster |
| **Operational complexity** | High | Low | Medium |
| **Best for** | Large stateful streaming | Kafka-centric, simple apps | Large batch-like analysis |

**Choose Flink** here: large per-article state, sub-second latency, exactly-once with Redis.

**Choose Kafka Streams** if: smaller team, operational simplicity > 5-min latency, only 1h/24h windows needed.

### Lambda vs. Kappa Architecture

```
Lambda Architecture (two paths):
  Speed Layer (Flink)  ->
                          Merge Layer -> Query
  Batch Layer (Spark)  ->

  Pros: Batch corrects streaming inaccuracies
  Cons: Two codebases; complex merge logic; duplicate effort

Kappa Architecture (our choice, single streaming path):
  Streaming (Flink) -> Query
  Historical: replay Kafka (30-day retention)

  Pros: One codebase; simpler ops; Flink replays Kafka for re-computation
  Cons: Kafka must retain 30 days of events (10.8 TB -- manageable)

For news trending: 99%+ approximate accuracy is fine.
No need for batch correction layer. Kappa wins.
```

---

## 8. Observability and Security

### Key Metrics

| Category | Metric | Alert Threshold |
|---|---|---|
| **Freshness** | `top_k_last_updated_age_seconds` per window | > 60s (5min window) |
| **Flink** | `kafka.consumer.lag` per partition | > 10,000 events |
| **Flink** | `checkpoint.duration_ms` | > 30,000 ms |
| **Flink** | `late_records_dropped_per_sec` | > 0.1% of total |
| **Redis** | `zset_member_count` per ZSET | < 1,000 (Flink likely stopped) |
| **Redis** | `ops_per_sec` | > 80% of capacity |
| **Share Svc** | `bloom_false_positive_rate` | > 0.5% |
| **Share Svc** | `kafka_publish_error_rate` | > 0.01% |
| **API** | `p99_latency_ms` | > 20 ms |
| **CDN** | `cache_hit_rate` | < 99% |

### Dashboards to Build

```
1. Freshness Monitor (most important):
   - Gauge: "Time since last Top-K update" per window per category
   - Alert: 5-min window not refreshed in > 60 seconds

2. Kafka Health:
   - Consumer lag heatmap (partition x consumer group)
   - Events/sec per partition (detect hot partitions)

3. Trending Leaderboard Live View:
   - Real-time rank chart for top-10 articles (animated)
   - Share velocity (events/sec) per article

4. Accuracy Validation (weekly):
   - Compare Redis ZSET rank output vs. exact Spark batch job from S3
   - Alert if accuracy < 99%
```

### Security

| Concern | Mitigation |
|---|---|
| **Rate limiting** | Max 10 share events/min per user per article (idempotency enforced) |
| **Bot / spam shares** | Anomaly detection: IP making > 100 events/min -> block + flag |
| **Internal API auth** | Share Service -> Kafka: mTLS client certs; K8s service accounts |
| **Redis access** | Redis AUTH + internal CIDR allowlist; no public exposure |
| **CDN cache poisoning** | Cache key includes content hash; purge API requires Bearer token |
| **PII in events** | user_id hashed (SHA-256) before persisting to S3; raw IDs TTL 7d in Kafka |
| **Audit trail** | All Top-K writes logged with Flink job ID + checkpoint ID for replay traceability |

---

## 9. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Asked what counts as a "share" (platform-specific?)
  [ ] Clarified dedup scope (per-session? per-day? per-platform?)
  [ ] Confirmed K value and whether variable
  [ ] Separated global vs. per-category vs. per-region
  [ ] Asked about historical queries

CAPACITY
  [ ] 600M events/day -> 120 GB raw -> 10.8 TB Kafka (30 days)
  [ ] Flink state: 2 KB/article x 500K = 1 GB (fits in RAM)
  [ ] Redis: 63 ZSETs x 35 MB = 2.2 GB
  [ ] CDN absorbs 99% of 500K reads/sec; origin sees only ~5K/sec
  [ ] Freshness budget: event -> Flink -> Redis -> CDN -> user in <27s

ARCHITECTURE
  [ ] Two-tier Flink: Job 1 (bucket aggregation) + Job 2 (top-K updater)
  [ ] Kafka partition key = article_id % 100 (avoids Flink shuffle)
  [ ] Redis ZSET as leaderboard (ZREVRANGE for top-K, ZREVRANK for rank)
  [ ] CDN as final serving layer (99%+ cache hit)
  [ ] Snapshot service for historical queries

DEEP DIVES (pick 2-3)
  [ ] Circular buffer approach: 30/60/144 micro-buckets per window per article
  [ ] Count-Min Sketch + Space-Saving (for 50M+ article scale-out path)
  [ ] Why ZADD not ZINCRBY (idempotency on replay)
  [ ] Bloom filter dedup: size math, partitioned-by-user routing
  [ ] Flink watermarks + 5-second allowed lateness for late events
  [ ] RocksDB state backend + S3 checkpoint for failure recovery

SCALE AND RESILIENCE
  [ ] Hot article: key salting (article_id + random suffix)
  [ ] Redis full data loss: warm-up from PostgreSQL snapshot (<5 min recovery)
  [ ] Flink failure: checkpoint restore in ~40 seconds
  [ ] Multi-region: regional Top-K independent; Global aggregation Flink job
  [ ] Breaking news: client-side batching reduces Kafka fan-out by 200x

TRADE-OFFS
  [ ] Micro-bucket vs. exact sliding (10x less state, +-10s OK)
  [ ] ZADD vs. ZINCRBY (absolute vs. incremental -- idempotency)
  [ ] Flink vs. Kafka Streams (state size, latency, operational complexity)
  [ ] Bloom filter vs. exact Redis SET for dedup
  [ ] Kappa vs. Lambda architecture

OBSERVABILITY
  [ ] Freshness gauge per window (primary alert)
  [ ] Kafka consumer lag per partition
  [ ] Accuracy validation vs. batch Spark job on S3

SECURITY
  [ ] user_id hashed in S3 persistence (PII)
  [ ] Bot detection + rate limiting on share API
  [ ] mTLS between internal services
```

---

## Appendix: End-to-End Data Flow

```
WRITE PATH:
  User shares article
    -> Share Service: validate, Bloom Filter dedup, publish to Kafka
    -> Kafka [article-shares, partition = article_id % 100]
    -> Flink Job 1: per-article circular buffer update every 10s micro-bucket
                     emits {article_id, delta_5min, delta_1h, delta_24h}
    -> Kafka [article-counts-10s]
    -> Flink Job 2: accumulates rolling window sums
                     ZADD all 63 ZSETs every 30s (pipelined)
                     ZREM zero-score articles
    -> Redis [topk:5min:global, topk:1hour:sports, ...]
    -> Snapshot Service: hourly snapshot to PostgreSQL + S3

READ PATH:
  User requests "Top 10 articles, last 5 minutes, Sports"
    -> CDN edge: cache hit? Return JSON (age < 30s)
    -> CDN miss  -> Trending API Service:
         ZREVRANGE topk:5min:sports 0 9 WITHSCORES          // O(log N + 10)
         HMGET article:art_789 article:art_456 ...           // batch metadata fetch
         Enrich with rank_change (compare vs. last snapshot)
         Serialize JSON, set Cache-Control: max-age=30
         Return to CDN -> CDN caches -> serve to user

  Historical query "Top 10, 1-hour window, at 2026-04-01T09:00:00Z":
    -> Trending API Service -> PostgreSQL [rank_snapshots]:
         SELECT * FROM rank_snapshots
         WHERE window='1hour' AND snapshot_at='2026-04-01T09:00:00Z'
               AND category='global'
         ORDER BY rank ASC LIMIT 10;
    -> O(1) indexed lookup, result in <5 ms
```
