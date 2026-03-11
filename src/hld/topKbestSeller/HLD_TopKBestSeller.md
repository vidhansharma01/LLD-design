# High-Level Design: Top-K Elements System (App Store Rankings / Amazon Bestsellers) — SDE3 Interview

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: what defines a "score", freshness requirement, K value, multi-dimensional ranking.
2. Walk through the naive solution and explain why it fails at scale.
3. Introduce the data structures: Min-Heap, Redis Sorted Set, Count-Min Sketch.
4. Design the ingestion pipeline (the write path) and ranking pipeline (the compute path).
5. Cover the read path, approximate vs exact trade-offs, and failure handling.

---

## 1. Problem Statement And Variations

**Core Problem**: Given a continuous stream of events (purchases, downloads, clicks), maintain and serve the **Top-K items** by count in real time.

### 1.1 Real-World Manifestations

| System | Items | Score Signal | K | Freshness |
|---|---|---|---|---|
| Amazon Bestsellers | Products | Units sold in last 24h | 100 | Hourly update |
| Apple App Store | Apps | Downloads in last 7 days | 200 | Daily update |
| Twitter Trending | Hashtags | Tweet frequency (last 24h) | 10 | Near real-time (minutes) |
| YouTube Trending | Videos | Views + likes + comments composite | 50 | Hourly update |
| Google Search Trends | Queries | Search frequency | 20 | Near real-time |
| Flipkart Flash Sale | Products | Units sold in last 1h | 50 | Every 5 min |
| News Trending | Articles | Shares + views in last 1h | 10 | Every 5 min |

### 1.2 Dimensions Of The Problem

```
Three key design axes to clarify in interview:

1. Freshness window: Is it "all-time Top-K" or "Top-K in last N hours/days"?
   - All-time: simpler (cumulative counter).
   - Windowed: harder (need to subtract old counts, or use sliding window).

2. Exact vs Approximate: Is exact rank ordering required?
   - Exact Top-K: expensive at scale, requires exact counts.
   - Approximate Top-K: acceptable error (e.g., "within 95% accuracy") → much cheaper.

3. Update frequency: How often does the list refresh?
   - Real-time (< 1 min): streaming pipeline required.
   - Near-real-time (5-60 min): micro-batch acceptable.
   - Daily: simple batch job.
```

**This HLD designs a system that supports ALL three axes** with configurable parameters.

---

## 2. Requirements

### 2.1 Functional Requirements
1. Ingest a continuous stream of events: each event = { item_id, category, timestamp }.
2. Maintain Top-K items globally and per-category, over a sliding time window (configurable: 1h, 24h, 7d).
3. Serve Top-K list with item score, rank, rank change (↑↓), and item metadata.
4. Support multiple K values simultaneously (Top-10, Top-50, Top-100).
5. Support "near-me" view: given an item_id, show items ranked above and below it.
6. Historical: what was Top-K last Thursday? Last month?
7. Personalized ranking: re-sort Top-K by user's category affinity (optional premium feature).

### 2.2 Non-Functional Requirements
- **Scale**: 500 million items tracked; 1 million events/second at peak (Amazon Prime Day, App Store launch rush).
- **Read throughput**: 100,000 reads/sec (users loading homepage, store front page).
- **Write throughput**: 1,000,000 events/sec.
- **Latency**: Top-K read: P99 < 50ms. Item rank lookup: P99 < 100ms.
- **Freshness**: Top-K list updated at most every 60 seconds (acceptable for most use cases).
- **Accuracy**: Top-100 list should contain the actual top-100 items with > 99% probability.

---

## 3. Back-Of-The-Envelope Capacity Planning

```
Events:
  1M events/sec × 64 bytes/event = 64 MB/s ingest bandwidth.
  Per hour: 64 MB/s × 3600 = ~230 GB raw events.
  Per day: ~5.5 TB raw events.

Counts store:
  500M items × 8 bytes (item_id) × 8 bytes (count) = ~8 GB for all counters.
  With category breakdown (100 categories): 8 GB × 100 = 800 GB — too large for Redis.
  Solution: Only track items with meaningful activity (< 10M active items at any time).
  10M active items × 100 bytes (id + count + metadata ref) ≈ 1 GB → feasible in Redis.

Top-K store:
  K=100 per category (100 categories) + global K=100:
  101 × 100 × ~200 bytes = 2 MB → trivially small; can live in Redis and be fully cached in CDN.

Kafka:
  1M events/sec × 64 bytes = 64 MB/s → 100 Kafka partitions × 640 KB/s each.
  Retention: 48 hours (replay buffer).
```

---

## 4. The Naive Solution And Why It Fails

### 4.1 Naive Approach: Sort Everything

```
For each Top-K query:
  SELECT item_id, COUNT(*) as cnt
  FROM events
  WHERE timestamp > NOW() - INTERVAL '24 HOURS'
  GROUP BY item_id
  ORDER BY cnt DESC
  LIMIT 100;

Problems:
  1. Full table scan: 1M events/sec × 86400s = 86.4 billion events in 24h.
     → Scanning 86.4B rows per query is infeasible (would take minutes).
  2. COUNT aggregation on sliding window: "now - 24 hours" changes every second.
     → No simple pre-aggregation possible.
  3. Write-heavy: inserting 1M rows/sec to a single DB → write bottleneck.
  4. No real-time update: query reruns every time → high latency.

Verdict: Works for < 1M rows. Completely infeasible at billion-event scale.
```

### 4.2 Why Exact Counting Is Expensive At Scale

```
Exact count for 24-hour window requires tracking every event's timestamp.
For 1M events/sec × 86400s = 86.4B events to maintain in a sliding window.

Even if we bucket into 1-second counters:
  86400 buckets × 500M items × 8 bytes = 3.5 × 10^14 bytes = 346 TB.
  Clearly infeasible in memory.

Even per-active-item (10M active):
  86400 buckets × 10M items × 8 bytes = 6.9 TB → still too large.

Key insight: We do NOT need exact counts for a Top-K list.
  If item A truly has 1,000,000 downloads and item B has 999,999,
  showing them in wrong order matters much less than the cost of computing exact order.
  Approximate top-K with high accuracy (> 99%) is sufficient and achievable cheaply.
```

---

## 5. Core Data Structures For Top-K

### 5.1 Min-Heap (Exact Top-K For Small N)

```
A Min-Heap of size K maintains the Top-K elements seen so far.

Min-Heap property: Parent ≤ children. Root = minimum element in heap.

Algorithm:
  Initialize: empty heap of max size K.
  For each item with count c:
    If heap.size < K:
      Push (c, item_id) into heap.
    Elif c > heap.top():      // c greater than current minimum in top-K
      heap.pop()              // evict the smallest of current top-K
      heap.push(c, item_id)  // add new item

  Final heap contains Top-K items.

Complexity:
  Time: O(N log K) — for N total items, each heap operation is O(log K).
  Space: O(K) — only K items held in memory at any time.

Example (K=3, processing items with counts):
  Items: A=100, B=50, C=200, D=75, E=300, F=120
  
  Process A=100: heap=[100]
  Process B=50:  heap=[50, 100]
  Process C=200: heap=[50, 100, 200]
  Process D=75:  75 > 50 (min)? YES → pop 50, push 75. heap=[75, 100, 200]
  Process E=300: 300 > 75? YES → pop 75, push 300. heap=[100, 200, 300]
  Process F=120: 120 > 100? YES → pop 100, push 120. heap=[120, 200, 300]
  
  Top-3: {E=300, C=200, F=120} ✓

Limitation: Requires all counts in memory simultaneously.
  For 500M items → O(500M) memory for counts → infeasible.
  Min-Heap is best for final aggregation step (after counts are determined),
  not for maintaining counts themselves.
```

### 5.2 Redis Sorted Set (Real-Time Top-K For Active Items)

```
Redis Sorted Set stores (member=item_id, score=count).
Sorted ascending by score internally; ZREVRANGE gives descending (highest first).

Operations:
  Increment item count:   ZINCRBY topk:global:24h "item_123" 1
  Get Top-100:            ZREVRANGE topk:global:24h 0 99 WITHSCORES
  Get item rank:          ZREVRANK topk:global:24h "item_123"
  Get item score:         ZSCORE topk:global:24h "item_123"
  Cardinality:            ZCARD topk:global:24h

Complexity: All operations O(log N) where N = number of tracked items.

Limitations:
  Memory: N items × ~50 bytes each.
  For 10M active items: 10M × 50 = 500 MB → feasible.
  For 500M items (all catalog): 500M × 50 = 25 GB → expensive but possible with Redis Cluster.

The Redis Sorted Set IS the right answer for exact Top-K on moderate-to-large item sets.
The question is: how do you maintain scores accurately at 1M events/sec?
→ Answer: not directly ZINCRBY for each event (lock contention). Use pre-aggregation.
```

### 5.3 Count-Min Sketch (Approximate Frequency For Massive Item Sets)

```
Count-Min Sketch is a probabilistic data structure that estimates item frequencies
using sublinear space. It trades exactness for memory efficiency.

Structure:
  d rows × w columns of integer counters, initialized to 0.
  d independent hash functions h1, h2, ..., hd.
  
  d = log(1/δ)       // δ = probability of exceeding error bound
  w = e / ε          // ε = error factor, e = Euler's number ≈ 2.718
  
  For 1% error with 99% confidence: ε=0.01, δ=0.01 → d=5, w=272 → 1360 counters.
  Memory: 1360 × 4 bytes = 5.4 KB. Regardless of how many distinct items!

Update (item X arrives):
  For i in 1..d:
    counters[i][h_i(X) % w] += 1

Query (estimate frequency of item X):
  For i in 1..d:
    estimates[i] = counters[i][h_i(X) % w]
  return min(estimates)  // minimum over all rows = best estimate

Why minimum?
  Hash collisions inflate individual row counts (false sharing with other items).
  The row with MINIMUM count has the fewest collisions → closest to true count.
  True count ≤ min(estimates) ≤ true count + ε × N  (where N = total events).

Concrete example (d=3 rows, w=5 columns):
  h1(apple) = 2,  h2(apple) = 4,  h3(apple) = 1
  h1(mango) = 2,  h2(mango) = 1,  h3(mango) = 3  ← collision with apple at row 1, col 2

  apple appears 10 times, mango appears 3 times.
  counters[1][2] = 13 (apple 10 + mango 3 collision)
  counters[2][4] = 10 (apple only)
  counters[3][1] = 10 (apple only)
  
  estimate(apple) = min(13, 10, 10) = 10 ✓ (exact!)
  estimate(mango) = min(13, ..., ...) — depends on other rows.

Error guarantee:
  With ε=0.01 and N=1 billion total events:
  Estimated count ≤ True count + 0.01 × 1B = True count + 10,000,000.
  
  For Top-K: items in Top-K typically have counts >> 10M.
  Items with similar counts might be misranked → acceptable for approximate Top-K.

Use Case for Top-K:
  → Not storing all item IDs (just hash buckets).
  → Cannot enumerate Top-K directly from sketch alone.
  → Use sketch to FILTER candidates: query sketch for any item → estimate count.
  → Combine with a small exact heap of candidates (Heavy Hitters algorithms).
```

### 5.4 Heavy Hitters Algorithm (Space Saving / Lossy Counting)

```
Heavy Hitters algorithms find items that appear more than (events / k) times
using O(k) memory — ideal for Top-K.

Space-Saving Algorithm (Metwally 2005):
  Maintains exactly k (item, count, error) tuples.
  
  Invariant: Any item with true frequency > N/k is in the data structure.
  (Where N = total events seen — guarantees Top-K is always present.)

  On new event for item X:
    Case 1: X is already tracked.
      → Increment its counter.
    Case 2: X is not tracked AND structure has < k slots.
      → Add X with count = 1.
    Case 3: X is not tracked AND structure is full.
      → Find minimum-count item Y. Evict Y.
      → Add X with count = min_count + 1 (conservatively — inherits Y's minimum).
      → Record error = min_count (how much could X's count be overestimated by).
  
  Final Top-K: sorted by (count - error) as lower bound of actual count.

Memory: O(k) items, not O(distinct items). For k=1000: tiny.

Accuracy: Any item with true count > N/k is guaranteed in the structure.
  For Top-100 from 1B events: any item with count > 1B/100 = 10M appears.
  Items with count per million can be missed — but these would never be in Top-100.

This is the algorithm Twitter, Streaming databases use internally for trending topics.
```

### 5.5 Choosing The Right Algorithm

```
┌─────────────────────────────────────────────────────────┐
│  Algorithm Selection Guide                               │
│                                                         │
│  Small item set (< 1M items)                            │
│  → Redis Sorted Set: exact, fast, operationally simple. │
│                                                         │
│  Large item set (1M – 10M items), moderate accuracy     │
│  → Redis Sorted Set with pre-aggregation (still works). │
│                                                         │
│  Massive item set (> 100M items) OR massive event stream│
│  → Count-Min Sketch + Space-Saving in stream processor. │
│  → Use sketch per time window; flush Top-K to Redis.   │
│                                                         │
│  Final ranking from candidates:                         │
│  → Min-Heap on candidate items to extract Top-K.       │
└─────────────────────────────────────────────────────────┘
```

---

## 6. Windowing Strategies

### 6.1 All-Time Count (Cumulative)

```
Simplest: ZINCRBY global:alltime item_id 1 on every event.
Problem: Counts never decay → stale rankings.
  An app popular in 2018 but dead now still ranks high.
Use when: "All-time bestseller" lists where cumulative popularity is the metric.
```

### 6.2 Fixed Time-Window (Tumbling Window)

```
Divide time into non-overlapping buckets (1-hour, 1-day).
Maintain separate count per bucket:  ZINCRBY topk:hour:2024031120:global item 1
At the end of each hour: compute Top-K from that bucket → snapshot.
Reset counts at start of next bucket.

Problems:
  - Boundary effect: event at 11:59 PM vs 12:00 AM in different windows.
  - "Last 24 hours" requires aggregating 24 hourly buckets.
  - Not truly sliding — scores jump at window boundaries.

Use when: "Today's bestsellers", "This week's top apps" with explicit period labels.
```

### 6.3 Sliding Window (Time-Decayed Count)

```
"Top 100 items by sales in the LAST 24 HOURS" — the hardest variant.

Approach A: Bucket-based sliding (recommended for large scale)
  → Divide window into M sub-buckets (e.g., 24 hourly buckets for 24h window).
  → Maintain separate ZSET per bucket: topk:bucket:{hour_ts}:global
  → Score = sum of counts in last 24 buckets.
  
  On each event:
    ZINCRBY topk:bucket:{current_hour}:global item_id 1
  
  On each read (Top-K):
    ZUNIONSTORE topk:sliding:global 24 topk:bucket:{h1} topk:bucket:{h2} ... topk:bucket:{h24}
    ZREVRANGE topk:sliding:global 0 999
  
  ZUNIONSTORE: unions 24 ZSETs, summing scores for common members.
  Old bucket eviction: EXPIRE topk:bucket:{old_hour} after 25 hours (auto-cleanup).
  
  Trade-off: Score is approximate (bucket granularity = 1 hour; true sliding would need second-level).
  For most use cases, hour-level sliding is sufficient (user doesn't notice 1-hour boundary effect).

Approach B: Exponential Decay Scoring
  → Instead of subtracting old events, decay all scores continuously.
  → score = score × decay_factor + new_events
  → decay_factor = e^(-λt) where λ = decay rate (λ = ln(2)/half_life).
  → For 24h half-life: λ = ln(2)/86400 ≈ 8×10^-6 per second.
  
  On each event:
    current = ZSCORE topk:global item_id
    time_elapsed = now - last_update_time[item_id]
    decayed = current × exp(-λ × time_elapsed) + 1   // add new event weight
    ZADD topk:global decayed item_id
  
  Benefits: Truly continuous sliding; no bucket boundaries; recent events weighted more.
  Problems: Floating-point drift; all items need periodic decay even without events.
  Practical: works well for trending hashtags (Twitter-style) where recency matters most.
```

### 6.4 Multi-Window Architecture

```
Production systems (Amazon, App Store) typically maintain multiple windows simultaneously:

Key namespace:
  topk:global:1h       → last 1 hour (trending NOW)
  topk:global:24h      → last 24 hours (today's bestseller)
  topk:global:7d       → last 7 days (this week)
  topk:global:30d      → last 30 days (this month)
  topk:category:{cat}:24h → category-specific 24h top

Maintained by:
  → Separate Flink jobs per window.
  → Or: one Flink job with multiple parallel window operators.
  → Redis writes use pipelining (one batch per event group, not one ZADD per event).
```

---

## 7. High-Level Architecture

```text
+------------------  Event Sources  -------------------+
| Purchase events | Download events | Click events      |
+-------+----------+-------+----------+--------+--------+
        |                  |                   |
        v                  v                   v
+-------+------------------+-------------------+--------+
|              Kafka (event_stream topic)               |
|      Partitions: 100,  Partition key: item_id        |
+----+---------------------------+----+------------------+
     |                           |    |
     v                           v    v
+----+-------+          +--------+----+-------+
|Stream      |          |Raw Event    |       |
|Aggregation |          |Logger (S3   |       |
|Service     |          |Parquet)     |       |
|(Flink)     |          |for history  |       |
+----+-------+          +-------------+       |
     |                                        |
     +--------+------------------+            |
     |        |                  |            |
     v        v                  v            v
+--------+  +--------+     +--------+     +--------+
|1h Top-K|  |24h     |     |7d Top-K|     |Exact   |
|Window  |  |Top-K   |     |Window  |     |Count   |
|Redis   |  |Window  |     |Redis   |     |Store   |
|Sorted  |  |Redis   |     |Sorted  |     |(PG)    |
|Set     |  |Sorted  |     |Set     |     |        |
+--------+  |Set     |     +--------+     +--------+
            +--------+
                |
        +-------+-------+
        |               |
        v               v
   +--------+     +----------+
   |Top-K   |     |Ranking   |
   |API     |     |Engine    |
   |Service |     |(Score    |
   |        |     | Norm,    |
   |        |     | Rank     |
   |        |     | Change)  |
   +---+----+     +----------+
       |
  +----+------+
  | Redis     |
  | Top-K     |
  | Result    |
  | Cache     |
  +-----------+
       |
  [CDN Cache] → Clients
```

---

## 8. Detailed Component Design

### 8.1 Event Ingestion And Kafka Partitioning

```
Event producer (e.g., purchase service):
  {
    "event_id":   "EVT_uuid",          // for deduplication
    "item_id":    "ASIN_B08N5WRWNW",   // Amazon ASIN / App Store bundle ID
    "category":   "Electronics/Phones",
    "quantity":   1,                   // units purchased (can be > 1)
    "timestamp":  1710172800,
    "user_id":    "USR_123",           // for dedup (same user buying twice = 2 events)
    "source":     "web"               // web|app|api
  }

Kafka configuration:
  Topic: event_stream
  Partitions: 100
  Partition key: item_id % 100
  
  Why partition by item_id?
    → All events for same item go to same partition.
    → Stream processor maintains local count per item (no cross-partition coordination).
    → Guaranteed ordering of events per item → accurate time-windowed counting.

  Retention: 48 hours (replay buffer for Flink job recovery or reprocessing).
  Compression: LZ4 (fast for event data).
  Replication factor: 3 (tolerate 2 broker failures).
```

### 8.2 Stream Aggregation Service (Flink)

**The core computation engine. Transforms event stream into item counts and Top-K lists.**

#### 8.2.1 Count Aggregation (Per Window)

```
Flink job: ItemCountAggregator

Input: Kafka event_stream.

Stage 1: Event parsing and validation.
  → Parse JSON → Event object.
  → Idempotency check: deduplicate on event_id (Flink State: HashMap<event_id, boolean>).
    Use TTL on state: clear event_id from dedup map after 1 hour (beyond replay window).
  → Validate: timestamp within 1 hour of now (reject stale or future events).
  → fanout: emit (global, item_id, quantity) AND (category, item_id, quantity).

Stage 2: Tumbling window count per item per scope (global + category).
  KeyedStream by (scope, item_id):
    TumblingEventTimeWindow(60 seconds):
      Sum quantity for (scope, item_id) in this 1-minute window.
      Emit: { scope, item_id, window_start, count_60s }

Stage 3: Aggregated counts to multiple downstream sinks.
  → Kafka output topic: item_counts_1m  { scope, item_id, window_start, count_60s }
  → PostgreSQL: INSERT/UPSERT into item_minute_counts (for historical queries).

Watermark: allowed lateness = 60 seconds.
  Events up to 60s late accepted into their correct tumbling window.
  Beyond 60s late: dropped (small loss acceptable; very late events are rare).
```

#### 8.2.2 Window Aggregation To Top-K

```
Flink job: TopKUpdater

Input: Kafka item_counts_1m.

For each 1-minute count record { scope, item_id, window_start, count_60s }:

  For each desired window (1h, 24h, 7d):
    Step 1: Look up item's existing score in Flink keyed state (scope → Map<item_id, score>).
            With 24h window and 1-min buckets: maintain a circular buffer of 1440 count values.

    Step 2: Compute new score.
      Sliding window with bucket: score = sum of counts in last N buckets.
      Drop the oldest bucket, add the new one.
      score_update = new_count_60s - expired_count_60s

    Step 3: Emit score update: { scope, item_id, new_total_score }

  Output sink: Redis updater.
    ZADD topk:{scope}:{window} new_total_score item_id

Memory per Flink task:
  Active items per partition: ~100K items × 1440 buckets × 4 bytes = 576 MB.
  With 100 partitions: each handles 100K items. Total RAM: 100 × 576 MB = 57 GB spread across cluster.
  Flink checkpoints this state to S3 every 60 seconds for fault tolerance.
```

#### 8.2.3 Count-Min Sketch For Massive Item Sets

```
If item set is too large (> 10M active items) for exact per-item tracking:

Flink job with Count-Min Sketch (one sketch per window per scope):
  On each event for item X:
    sketch.update(X, quantity)
  
  Parallel: maintain a Space-Saving structure (k=10,000 slots) to track Top-K candidates.
    Space-Saving.add(X, quantity)
  
  Every 60 seconds:
    candidates = space_saving.top(200)  // 2× K for safety margin
    For each candidate in candidates:
      estimated_count = sketch.estimate(candidate)
    Sort candidates by estimated_count DESC → extract Top-100.
    Publish Top-100 to Redis ZSET (replace full list, not incremental update).

Result: Approximate Top-K with guaranteed recall > 99.9% for items in true Top-K.
Memory: sketch ≈ 5 KB. Space-Saving ≈ 10,000 × 20 bytes = 200 KB per window per Flink task.
Savings: Instead of tracking 100M items × 8 bytes = 800 MB, using 205 KB per task.

Trade-off: Slight inaccuracy at the boundary (rank ~100 items may be misranked among themselves).
           Top-10 items are almost always correct (they have counts far exceeding error margin).
```

### 8.3 Redis Sorted Set — Top-K Store

```
Key schema:
  topk:global:1h        → Global all-category, last 1 hour
  topk:global:24h       → Global all-category, last 24 hours
  topk:global:7d        → Global all-category, last 7 days
  topk:cat:{cat_id}:24h → Category-specific, last 24 hours

Read operations:
  Top-100 global (24h): ZREVRANGE topk:global:24h 0 99 WITHSCORES
  Item rank: ZREVRANK topk:global:24h "ASIN_xyz"   → 0-indexed; rank = result + 1
  Item score: ZSCORE topk:global:24h "ASIN_xyz"
  Near-me (items around rank #50):
    ZREVRANGE topk:global:24h 45 55 WITHSCORES      → items at ranks 46-56

Write operations (from Flink via Redis client):
  ZADD topk:global:24h GT new_score item_id  → Or exact value (not GT — scores can decrease as old window expires)
  Note: for sliding windows, scores CAN decrease (old bucket dropped).
        So use ZADD without GT flag for windowed scores (allow decrease).

Memory: 10M active items × ~50 bytes = 500 MB per ZSET × 5 windows = 2.5 GB.
Redis Cluster: distribute keys across 6 master nodes with 1 replica each = 12 nodes.
```

### 8.4 Ranking Engine (Score Normalization And Composite Score)

**Raw event counts are rarely sufficient for fair ranking. Real systems use composite scores.**

#### 8.4.1 Amazon Bestseller Score Factors

```
Raw count: units sold in last 24h.
Adjustments:
  1. Sales velocity: recent hours weighted more than older hours.
     score = Σ (hourly_sales_h × recency_weight_h)
     recency_weights: last hour = 2.0×, 2-6 hours ago = 1.5×, 7-24 hours ago = 1.0×.

  2. Price normalization: low-price items have more units volume.
     Shouldn't rank $1 earbuds above $500 headphones just because more units sold.
     Adjustment: score × log(price / median_category_price + 1).
     This reduces the advantage of low-price items while preserving signal.

  3. Cancellation and return deduction:
     score = raw_sales - 0.8 × returns_24h  (returns penalize heavily).

  4. Category normalization (within-category ranking):
     Electronics sell 100× more units than Astronomy Books.
     Category score percentile: rank within category, not absolute count.

  5. New item boost (for discovery):
     Items < 7 days old get a temporary multiplier to appear in rankings.
     Prevents new items from being invisible despite healthy sales.

Composite score formula:
  final_score = (raw_sales × recency_weight - return_penalty)
              × log(price_adjustment)
              × category_percentile_boost
              × new_item_boost
```

#### 8.4.2 App Store Ranking Factors (Apple/Google Play)

```
Signals used (approximate, as actual algorithms are proprietary):
  - Downloads in last 24h/7d (primary signal).
  - Revenue (paid apps and in-app purchases).
  - Ratings and review count (quality signal; prevents gaming via bot downloads).
  - Rating change velocity (app improving vs deteriorating).
  - Engagement: DAU/MAU ratio (retained users vs downloaded-and-forgotten).
  - Crash rate (quality floor; high crash rate → penalized in rankings).
  - Uninstall rate (negative signal; users regretting download).

Composite score:
  downloads_score = normalize(downloads_24h)          // 0-1
  quality_score   = normalize(rating × log(reviews))  // 0-1
  retention_score = normalize(dau_mau_ratio)           // 0-1
  
  final_score = 0.5 × downloads_score
              + 0.3 × quality_score
              + 0.2 × retention_score

Interview insight: The composite score is computed by the Ranking Engine separately
from raw count tracking. Raw counts flow through Kafka → Flink → Redis.
Composite scoring is an offline-computed adjustment factor per item, updated hourly.
```

### 8.5 Rank Change Tracking

```
Users love seeing "↑ 5 positions" or "↓ 2 positions" next to ranked items.

Implementation:
  → Every hour, snapshot current Top-K ranks to a snapshot table.
  → At next render: compute delta = current_rank - previous_rank_from_snapshot.
    - Negative delta = moved up (good): ↑ |delta|
    - Positive delta = moved down: ↓ |delta|
    - Zero: →

Snapshot table (PostgreSQL):
  CREATE TABLE rank_snapshots (
    snapshot_id   VARCHAR(64) PRIMARY KEY,
    scope         VARCHAR(64) NOT NULL,
    window        VARCHAR(8)  NOT NULL,
    rank          INT         NOT NULL,
    item_id       VARCHAR(128) NOT NULL,
    score         BIGINT      NOT NULL,
    snapshotted_at TIMESTAMP  NOT NULL,
    INDEX (scope, window, snapshotted_at, rank)
  );

Rank change query:
  SELECT current.item_id,
         current.rank as current_rank,
         previous.rank as previous_rank,
         (previous.rank - current.rank) as rank_change
  FROM rank_snapshots current
  JOIN rank_snapshots previous
    ON current.item_id = previous.item_id
    AND current.scope = previous.scope
    AND current.window = previous.window
  WHERE current.snapshotted_at = :latest_snapshot
    AND previous.snapshotted_at = :previous_snapshot;

Cached: this join result cached in Redis for 5 minutes (computed once per snapshot interval).
```

### 8.6 Top-K API Service — The Read Path

```
GET /v1/topk?scope=global&window=24h&k=100&category=Electronics

Flow:
  1. Check L1 cache: Redis key topk_response:global:24h:100:Electronics (TTL: 30s).
     Cache HIT (> 99% of requests): return JSON immediately. < 5ms.
     Cache MISS: proceed to Step 2.

  2. Fetch Top-100 from Redis Sorted Set:
     ZREVRANGE topk:cat:Electronics:24h 0 99 WITHSCORES   → [(item_id, score), ...]

  3. Fetch item metadata (name, image, price, rating) for 100 items:
     Redis pipeline: HGETALL item:{id}:meta for each of 100 items.
     Cache miss on item metadata: fetch from Item Service / catalog DB → populate cache.

  4. Fetch rank changes: GET rank_change:cat:Electronics:24h (pre-computed, Step 8.5).

  5. Merge: [(item_id, score, rank, rank_change, metadata)]

  6. Compute derived fields:
     - rank_change display: "↑5", "↓2", "NEW" (if item < 48h old on this list).
     - score display: "12,450 sold today".

  7. Write to L1 cache (TTL: 30s) → return.

Item rank lookup (non-top-K item):
  GET /v1/topk/rank?item_id=ASIN_xyz&scope=global&window=24h
  → ZREVRANK + ZSCORE → O(log N) → < 2ms → return with no caching needed.
```

### 8.7 Historical Top-K Queries

```
"What were yesterday's Top-100 bestsellers?"

Approach: Pre-computed hourly snapshots in PostgreSQL.

  Daily batch job (runs at midnight):
    ZREVRANGE topk:global:24h 0 999 WITHSCORES
    → INSERT INTO rank_snapshots (scope, window, rank, item_id, score, snapshotted_at)
    → Values = current top-1000 with their scores.

Query for historical:
  SELECT item_id, rank, score, snapshotted_at
  FROM rank_snapshots
  WHERE scope = 'global'
    AND window = '24h'
    AND DATE(snapshotted_at) = '2024-03-11'
  ORDER BY rank ASC
  LIMIT 100;

Retention: 365 daily snapshots × 1000 items × 200 bytes = ~73 MB/year.  Trivially small.
Older snapshots: archive to S3 Parquet, query via Athena.

Why not query Redis directly for historical?
  → Redis only holds current window (sliding 24h or 7d).
  → Past windows evicted as items age out.
  → Historical = always from PostgreSQL snapshots.
```

---

## 9. End-To-End Critical Flows

### 9.1 Normal Purchase Flow → Bestseller Update

```
1. User purchases "Echo Dot" (item_id=ASIN_B09B8YWXDF) at 10:30 PM.
2. Purchase Service publishes Kafka event (partition key = ASIN_B09B8YWXDF % 100 = 37).
3. Flink partition 37 receives event:
   a. Dedup check: event_id seen before? No → proceed.
   b. Validate timestamp: within 1 hour → OK.
   c. Fanout: emit (global, ASIN_B09B8YWXDF, 1) AND (Electronics, ASIN_B09B8YWXDF, 1).
   d. Tumbling 1-min window accumulates: Echo Dot count += 1.
4. At 10:31 PM (window close): emit { global, ASIN_B09B8YWXDF, window_start=10:30, count_60s=42 }
5. TopKUpdater receives count record:
   a. Update 24h sliding bucket: subtract expired 10:30 AM count, add new 10:30 PM count.
   b. New 24h score for Echo Dot = 58,421.
   c. ZADD topk:global:24h 58421 ASIN_B09B8YWXDF
   d. ZADD topk:cat:Electronics:24h 58421 ASIN_B09B8YWXDF
6. Echo Dot's rank in global 24h: ZREVRANK topk:global:24h ASIN_B09B8YWXDF → rank 5.
7. Next API read of Top-100: includes Echo Dot at rank 5 (within 60s of purchase).
```

### 9.2 Flash Sale Burst (1M Events/sec For One Item)

```
Scenario: "Big Billion Day" — one item gets 1M purchases in 60 seconds.
Problem: 1M ZINCRBY operations on same item → Redis single-threaded bottleneck.

Solution: Count first, write once.

Flink window approach:
  → 1M events for ASIN_xyz in 1-minute window → Flink accumulates count = 1,000,000.
  → At window boundary: emit single count_60s = 1,000,000.
  → ONE ZADD to Redis: ZADD topk:global:24h 1000000+current_score ASIN_xyz
  → ONE Redis write regardless of how many events → no bottleneck.

This is the key insight: always pre-aggregate in Flink before writing to Redis.
Never write one Redis operation per event.

What about ZINCRBY for real-time within a window?
  → For real-time dashboards (product page showing "live sales"):
    → Use Redis counter: INCRBY sales:realtime:ASIN_xyz quantity (not the ranking ZSETs).
    → Separate fast-path for display; ranking ZSETs updated at minute granularity.
```

### 9.3 Daily Snapshot And Rank Change Computation

```
Cron job at 00:00:00 UTC (midnight):
1. READ current Top-1000 from Redis:
   ZREVRANGE topk:global:24h 0 999 WITHSCORES → top_1000_current
   ZREVRANGE topk:cat:{each_category}:24h 0 999 WITHSCORES → category_tops

2. COMPARE with yesterday's snapshot from PostgreSQL:
   SELECT item_id, rank FROM rank_snapshots
   WHERE scope='global' AND window='24h'
     AND snapshotted_at = (SELECT MAX(snapshotted_at) FROM rank_snapshots WHERE ...)

3. Compute rank changes: {item_id → delta_rank}.

4. INSERT new snapshot into rank_snapshots.

5. UPSERT rank_change cache in Redis:
   HSET rank_change:global:24h ASIN_B09B8YWXDF "+5"
   HSET rank_change:global:24h ASIN_NEW "NEW"
   TTL: 25 hours (until next snapshot).

6. Invalidate Top-K response cache: DEL topk_response:global:24h:* pattern.
   Next API reads rebuild cache with new rank changes.
```

---

## 10. Handling Edge Cases

### 10.1 New Item Entry ("Cold Start")

```
A brand new item with no history:
  → Score = 0. Not in any ZSET initially.
  → First event → ZADD inserts item into ZSET.
  → Starts at bottom, climbs as events accumulate.

Discovery problem: New item with great demand but not yet in Top-K:
  → Ranking Engine applies new_item_boost: items < 7 days old get 1.3× score multiplier.
  → This boosts new items into visibility faster.
  → Multiplier decays linearly to 1.0 after 7 days.
```

### 10.2 Item Removal (Out Of Stock, Banned, Discontinued)

```
Item banned or out of stock:
  → Admin action calls: ZREM topk:global:24h item_id (removes from ALL ZSETs).
  → ZADD event_blocklist item_id → ingestion pipeline skips events for blocked items.
  → Historical snapshots preserve the data (audit trail).
  → New events for this item: Flink pipeline checks blocklist at ingestion → drops event.

Blocklist implementation:
  Redis SET: blocked_items → {item_id, item_id2, ...}
  Flink: on each event, SISMEMBER blocked_items event.item_id → skip if member.
  Refresh: Flink checks blocklist every 60 seconds (not per-event to avoid Redis hotspot).
```

### 10.3 Score Manipulation / Gaming Prevention

```
Sellers gaming bestseller rankings via fake purchases:
  Signal                  | Defense
  ─────────────────────── | ───────────────────────────────
  Many orders, same user  | Dedup by user_id within 24h window per item
  Many orders, same IP    | IP velocity check (INCR ip:{ip}:item:{id} EX 3600 > threshold)
  Same device fingerprint | Device fingerprint dedup
  Burst pattern (bot-like)| ML anomaly detection: sudden 10×spike in seconds
  Low engagement (no use) | Amazon: requires delivery confirmation before counting
  Incentivized reviews    | Separate review score from sales rank

Implementation:
  → Flink enriches each event with user velocity signal.
  → Events flagged as suspicious: routed to suspect_events Kafka topic.
  → Suspect events counted with 0.1× weight (soft reject, not hard reject — avoid false positives).
  → ML model (XGBoost) classifies events as organic/suspicious. Retrained weekly.
```

### 10.4 Tie-Breaking

```
Two items with same count in same window:
  → Add sub-second precision to score (like leaderboard tie-breaking).
  → Or: alphabetical by item_id (deterministic but not meaningful).
  → Or: by item rating as tiebreaker (better-reviewed item ranks higher).

Implementation: composite score in ZSET:
  zset_score = sales_count × 10^6 + avg_rating × 10^4 + launch_date_bonus
  
  This packs multiple ranking signals into a single float64 without losing ordering.
```

---

## 11. Caching Strategy

```
L1 — CDN Cache (CloudFront / Fastly):
  → Global Top-100 response: TTL 60 seconds.
    Reasoning: 60s freshness SLO; CDN handles 90% of reads with 60s stale data.
  → Category Top-100: TTL 120 seconds (slightly less volatile).
  → Historical (yesterday's Top-K): TTL 1 hour (static; changes only on new daily snapshot).

L2 — Application Redis Cache:
  → topk_response:{scope}:{window}:{k}:{category}: TTL 30s (fast path for top-K response JSON).
  → item:{id}:meta (name, image, price): TTL 1 hour.
  → rank_change:{scope}:{window}: TTL 25 hours (refreshed at snapshot time).
  → blocked_items SET: TTL not set; manually updated.

L3 — Redis Sorted Sets (live data):
  → topk:global:24h etc. — always up-to-date (primary data store for rankings).
  → Direct reads only on cache miss (< 1% of requests).

Cache invalidation:
  → Top-K response cache: invalidated hourly at snapshot time + TTL expiry.
  → Item metadata: invalidated on catalog update (event-driven via Kafka item_update topic).
  → No cache stampede on simultaneous expiry: use jitter ± 5s on TTL values.
```

---

## 12. Consistency, Idempotency, And Failure Handling

### 12.1 Exactly-Once Event Processing

```
Kafka exactly-once semantics (EOS):
  → Flink Consumer: read from Kafka with offset commits inside Flink checkpoints.
  → Event dedup state in Flink keyed by event_id (TTL: 1 hour).
  → Flink-to-Redis sink: idempotent writes (ZADD with explicit score; replaying same
    event updates score to same value → no drift).
  → Flink-to-PostgreSQL: INSERT ... ON CONFLICT DO UPDATE (upsert → safe to replay).
  → Result: at-least-once delivery + idempotent sinks = effectively exactly-once.
```

### 12.2 Flink Job Recovery

```
Failure scenario: Flink TaskManager crashes mid-window.
  → Flink restores from last checkpoint (every 60s to S3).
  → Reprocesses Kafka events since checkpoint offset.
  → Window state restored from checkpoint → correct counts reconstructed.
  → Redis ZADD with correct final count after window close → no double-counting.

Checkpoint interval trade-off:
  → Short interval (10s): lower recovery lag but higher checkpointing overhead.
  → Long interval (5 min): lower overhead but longer recovery time.
  → 60s is a common sweet spot for Top-K systems.
```

### 12.3 Redis Failure Recovery

```
Redis Cluster with 3 replicas per shard:
  → Primary failure → automatic failover to replica (< 30s).
  → Sorted set data preserved from replica (async replication, < 1s lag).

Full cluster failure (catastrophic):
  → Warm-up from PostgreSQL: last hourly snapshot + replay recent events from Kafka.
    → Latency to recover: Kafka replay of last 1 hour + Flink recompute = ~5 min.
  → During recovery: serve stale Top-K from CDN cache (60s TTL; CDN extends TTL on error "cache on error").
  → After recovery: Flink backfills recent 1 hour → Redis back to current state.
```

### 12.4 Late Events (Network Delays)

```
Purchase event delayed by 45 minutes (network partition in a remote warehouse).
Event timestamp: 45 minutes ago. 

Flink watermark allows 60s lateness → 45-minute-late event EXCEEDS allowed lateness.

Options:
  Option A: Drop the event. Lose the count.
    → Conservative; avoids complexity. Acceptable for high-volume items (1 event of millions).

  Option B: Assign to current window (wrong time attribution, but counts).
    → Event counted in current period, not correct historical period.
    → Slight inaccuracy in per-hour breakdown; total 24h count correct.

  Option C: Repair pipeline → replay the event to the correct time window.
    → Complex. Run a separate repair Flink job on buffered-event Kafka topic.
    → Only worth it for individual high-value events (B2B large order).

Decision: Option B for small events; Option C for large quantity events (quantity > threshold).
```

---

## 13. Major Trade-Offs And Why

### 13.1 Exact Count vs Approximate Count (Count-Min Sketch)

| Aspect | Exact (Redis ZADD) | Approximate (Sketch) |
|---|---|---|
| Accuracy | 100% exact | ~99%+ for top items |
| Memory | O(N active items) | O(k) regardless of N |
| Complexity | Simple | Moderate |
| Top-K boundary error | None | Possible rank swap at boundary |

**Decision**: Exact for up to 10M active items (Redis 500 MB). Approximate sketch for larger item sets.

### 13.2 Pre-Aggregation In Flink vs Direct Redis Writes

- **Direct ZINCRBY per event**: Simplest. Breaks at 1M events/sec (Redis single-threaded saturated).
- **Pre-aggregate in Flink (1-min windows)**: One Redis write per item per minute. Scales to any throughput.
- **Decision**: Always pre-aggregate. Even at 1K events/sec, pre-aggregation is cleaner and easier to scale.

### 13.3 Sliding Window vs Tumbling Window

- **Sliding** (true "last 24 hours"): Accurate, complex (bucket arrays, Flink state), score can decrease.
- **Tumbling** (hourly batch): Simple, slight boundary effect, scores don't decrease mid-window.
- **Decision**: Hourly tumbling windows with ZUNIONSTORE for aggregate. Simple, good enough for "last 24 hours" label.

### 13.4 Composite Score vs Pure Count

- **Pure count**: Simple, gameable (bot purchases), unfair (cheap vs expensive items).
- **Composite score**: Fairer, requires per-item metadata (price, rating, return rate), harder to explain.
- **Decision**: Pure count for interview simplicity. Mention composite as "real-world enhancement."

---

## 14. Interview-Ready Deep Dive Talking Points

**"How does Amazon maintain its bestseller list in real time?"**
> Events flow through Kafka (partitioned by item_id for ordering). Flink 1-minute tumbling windows aggregate purchase counts per item. At window close, one ZADD per item updates the Redis Sorted Set sliding window (24 buckets for 24-hour window, one per hour). Top-K served from Redis ZREVRANGE. Total lag: < 60 seconds.

**"Why not just count with a SQL GROUP BY and ORDER BY?"**
> At 1M events/sec × 86400s = 86 billion events, a GROUP BY full table scan takes minutes. The active dataset doesn't fit in DB buffer pool. Real-time requirement (< 60s update) rules out batch SQL. Redis Sorted Set ZADD/ZREVRANGE is O(log N) and serves millions of reads/sec.

**"How do you handle a sliding window for 'last 24 hours'?"**
> Maintain 24 hourly bucket ZSET keys. ZINCRBY within current hour. For Top-K read, ZUNIONSTORE the 24 buckets → combined scores. Old bucket expires after 25 hours (TTL). Score for each item = sum of its counts in all 24 buckets. This is approximate (hour-level granularity) but sufficient for "last 24 hours" label.

**"What is Count-Min Sketch and when would you use it?"**
> A 2D array of counters with d hash functions. Update: increment d counters per event. Query: return minimum of d counters for an item (minimum reduces false-inflation from hash collisions). For 500M item catalog: sketch uses 5 KB vs exact tracking needs GBs. Used when item set is too massive for exact tracking; accuracy is ~99% for Top-K items (which have counts >> error margin).

**"How do you prevent sellers from gaming rankings with fake purchases?"**
> User-level dedup (same user can only count once per item per window). IP velocity limiting. Bot detection via order pattern ML (burst, no engagement). Amazon uses delivery confirmation before counting. Flagged events get 0.1× weight (soft reject). Space-Saving algorithm's FIFO nature naturally self-corrects: sustained organic sales re-enter; bots removed from tracking on next eviction cycle.

---

## 15. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Scope: window type, exactness, scale (1M events/sec, 500M items) |
| 5–12 min | Naive SQL approach and why it fails; data structure overview |
| 12–20 min | Min-Heap, Redis Sorted Set, Count-Min Sketch — full explanation with examples |
| 20–30 min | Architecture: Kafka → Flink window aggregation → Redis ZADD pipeline |
| 30–37 min | Sliding window (bucket approach + ZUNIONSTORE), rank changes, caching |
| 37–43 min | Flash sale burst handling, gaming prevention, failure recovery |
| 43–45 min | Trade-offs, approximate vs exact, composite scoring extensions |

---

## 16. Extensions To Mention If Time Permits

- **Personalized Top-K**: Re-sort Top-K by user's past category affinity (collaborative filtering). Top-K list is a candidate set; re-ranking is personalized.
- **Geo-Local Bestsellers**: Separate ZSET per region. "Top products in Mumbai" vs "Top products in Delhi".
- **Real-Time Product Page**: "5 sold in the last hour" — served from Redis counter (INCR sales:realtime:{id} EX 3600), NOT from ranking ZSET (too coarse).
- **A/B Testing Rank Position**: Serving different Top-K lists to different user cohorts to test ranking algorithm changes.
- **Trending vs Bestselling**: Trending = score is rate-of-change of count (second derivative), not absolute count. An item going from 100 to 1000 downloads/hour is "trending up" even if not in absolute Top-K.
- **Heavy Hitters In Stream SQL**: Apache Flink SQL's TOPN operator internally uses Space-Saving algorithm — same as designed here but as a SQL query: `SELECT * FROM purchases GROUP BY item_id ORDER BY COUNT(*) DESC LIMIT 100`.
