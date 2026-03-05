# 🔍 High-Level Design (HLD) — Search Autocomplete System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Trie Data Structure, Top-K Suggestions, Real-time Updates, Ranking, Distributed Trie, Latency Optimization

---

## 1. Requirements

### 1.1 Functional Requirements
- As a user types in a search box, return **top 5–10 autocomplete suggestions** after each keystroke.
- Suggestions are **ranked by frequency/relevance** (most searched first).
- Support **personalization** — factor in user's search history.
- Support **trending queries** — recently popular terms ranked higher.
- Handle **typos and fuzzy matching** (optional, stretch goal).
- Support **multiple languages / locales**.
- Filter **offensive / blacklisted terms** from suggestions.
- System learns from **new searches in near-real-time** (updated within minutes).

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Latency** | < 20 ms P99 per keystroke (end-to-end) |
| **Availability** | 99.99% — autocomplete must never block search |
| **Scale** | 10M QPS at peak (Google scale); 5B searches/day |
| **Freshness** | Top-K updated every 10–15 minutes from new searches |
| **Accuracy** | Top result matches user intent > 70% of the time |
| **Storage** | ~10M unique query prefixes; each with top-10 suggestions |

### 1.3 Out of Scope
- Full-text search result ranking (downstream system)
- Voice search input handling
- Image/video search suggestions

---

## 2. Capacity Estimation

```
Daily searches            = 5 billion → ~58K/sec avg, ~10M/sec peak
Query length (avg)        = 20 characters → 20 keystrokes per query
Autocomplete requests/day = 5B × 20 keystrokes = 100B requests/day
                          = ~1.2M req/sec avg, ~200M req/sec peak (extreme)
Unique prefixes           = ~1 billion (all prefixes of all unique queries)
Top-10 suggestions/prefix = 10 × 30 bytes (avg term) = ~300 bytes per prefix
Storage (prefix → top-10) = 1B × 300B ≈ 300 GB (fits in distributed Redis)
Query log writes/day      = 5B raw search events → aggregated in batch
```

---

## 3. High-Level Architecture

```
 User types "trave..."
        │  (each keystroke)
        ▼
 ┌──────────────────┐
 │   API Gateway    │  (rate limiting, auth bypass for autocomplete)
 └──────┬───────────┘
        │  GET /autocomplete?q=trave&lang=en&userId=U123
        ▼
 ┌──────────────────────────────────────────────┐
 │         Autocomplete Service                 │
 │  (stateless, horizontally scaled)            │
 │                                              │
 │  1. Query local in-process cache (Caffeine)  │
 │  2. Query Redis (distributed prefix store)   │
 │  3. Re-rank with personalization signals     │
 │  4. Filter blacklisted terms                 │
 │  5. Return top-5–10 suggestions              │
 └──────────┬───────────────────────────────────┘
            │
  ┌─────────┼──────────────────────┐
  ▼         ▼                      ▼
┌────────┐ ┌────────────────────┐ ┌──────────────────┐
│ Redis  │ │  Personalization   │ │  Blacklist Store  │
│ Prefix │ │  Service           │ │  (Redis Set)      │
│ Store  │ │  (User history)    │ └──────────────────┘
└────────┘ └────────────────────┘

           ┌─────────────────────────────────────┐
           │        Data Pipeline (Offline)       │
           │                                      │
           │  Search Logs → Kafka → Flink         │
           │  → Aggregate query frequencies       │
           │  → Rebuild prefix→top-K mapping      │
           │  → Push to Redis (every 10–15 min)   │
           └─────────────────────────────────────┘
```

---

## 4. Core Data Structure — Trie

### 4.1 Trie Basics

A **Trie (prefix tree)** maps each character prefix to its children and stores top-K suggestions at each node.

```
 Insert: "travel", "traveler", "train", "trade", "trap"

         root
          │
          t
          │
          r
         / \
        a   i
       /|    \
      v  d   n
      |   \
      e    e
     / \
    l   r
          \
           e
           |
           r

At node "trave" → top suggestions: ["travel" (1M), "traveler" (500K), ...]
At node "tra"   → top suggestions: ["travel" (1M), "trade" (800K), "trap" (600K)]
```

### 4.2 Storing Top-K at Each Node

**Naive approach:** Traverse all descendants of a prefix node → count → sort.  
**Problem:** Too slow at query time for deep tries with billions of queries.

**Optimized approach:** Store **top-K suggestions pre-computed at each node**.

```
Node "trave" stores:
{
  topK: [
    { query: "travel", score: 1_000_000 },
    { query: "traveler", score: 500_000 },
    { query: "travel guide", score: 300_000 },
    { query: "travel insurance", score: 200_000 },
    { query: "travel visa", score: 100_000 }
  ]
}
```

**Query lookup = O(L) where L = prefix length** — just traverse to the node, read stored topK.  
**Update = O(L × K) — propagate score changes up the tree** (done offline in batch).

### 4.3 Why Not Trie in Distributed Memory?

A full trie for 1B unique prefixes in a single server's memory is impractical (~300 GB). Instead:

**Map each prefix → Redis Hash key** (effectively a hash table of prefixes):

```
Redis keys (one key per prefix):
  autocomplete:en:t       → [("travel",1M), ("trump",800K), ...]
  autocomplete:en:tr      → [("travel",1M), ("trump",700K), ("trade",600K), ...]
  autocomplete:en:tra     → [("travel",1M), ("trade",600K), ("trap",400K), ...]
  autocomplete:en:trav    → [("travel",1M), ("traveler",500K), ...]
  autocomplete:en:trave   → [("travel",1M), ("traveler",500K), ...]
  autocomplete:en:travel  → [("travel",1M), ("travel guide",300K), ...]

Redis data type: ZSET (sorted set) — score = query frequency
  ZREVRANGE autocomplete:en:trave 0 9 WITHSCORES
  → returns top 10 suggestions for prefix "trave" in O(log N)
```

---

## 5. Core Components

### 5.1 Autocomplete Service (Read Path)

```java
List<Suggestion> getSuggestions(String prefix, String lang, String userId) {
    String key = "autocomplete:" + lang + ":" + prefix.toLowerCase();

    // Layer 1: In-process cache (Caffeine, per service node)
    List<Suggestion> cached = localCache.getIfPresent(key);
    if (cached != null) return personalize(cached, userId);

    // Layer 2: Redis distributed cache
    List<Suggestion> suggestions = redis.zrevrangeWithScores(key, 0, 9);
    if (suggestions.isEmpty()) {
        // Fallback: try shorter prefix (graceful degradation)
        suggestions = fallbackToShorterPrefix(prefix, lang);
    }

    // Filter blacklisted terms
    suggestions = filter(suggestions, blacklist);

    // Personalize: boost queries from user's recent history
    suggestions = personalize(suggestions, userId);

    // Cache locally (TTL: 30 sec — balance freshness vs performance)
    localCache.put(key, suggestions, 30, SECONDS);

    return suggestions.subList(0, Math.min(10, suggestions.size()));
}
```

**Multi-layer caching breakdown:**
| Layer | Cache Type | Latency | TTL | Hit Rate |
|---|---|---|---|---|
| 1 | In-process (Caffeine, per node) | < 1 ms | 30 sec | ~60% |
| 2 | Redis Cluster (shared) | ~2–5 ms | 10 min | ~35% |
| 3 | DB / Trie rebuild (fallback) | > 50 ms | — | 5% |

**Total target: < 10 ms at the service layer → < 20 ms end-to-end with network.**

### 5.2 Query Aggregation Pipeline (Write Path)

Searches feed the frequency data that drives suggestions.

```
User searches "travel destinations"
        │
        ▼
Search Event → Kafka topic: search-queries
  { query: "travel destinations", userId, timestamp, lang, country }
        │
        ▼
Flink Streaming Job (1-minute tumbling window):
  Count query frequency per (query, lang, country)
  Aggregate across window → { "travel destinations": 42_500 }
        │
        ▼
Hourly Batch Job (Spark — every 10 min):
  1. Read aggregated counts from Kafka (last 10 min)
  2. Merge with historical frequency DB
  3. Apply decay: score = 0.9 × historical_score + 0.1 × new_count
     (exponential moving average — balances freshness vs stability)
  4. Recompute top-K for all affected prefixes
  5. Push updates to Redis:
       ZADD autocomplete:en:trave score "travel destinations"
       ZREMRANGEBYRANK autocomplete:en:trave 0 -11  (keep top 10 only)
  6. Propagate to ancestor prefixes (up the trie)
```

**Trie update propagation:**
```
Query "travel destinations" score increases by 5%
  → Update node "travel destinations"
  → Update node "travel destination"
  → Update node "travel destinatio"
  ...
  → Update node "t"

All ancestor prefixes' top-K may need updating.
Optimization: only propagate if new score would displace existing top-10.
```

### 5.3 Ranking & Scoring

Raw frequency alone is not enough:

```
Final Score = base_frequency_score
            × recency_factor        (trending query in last 1 hr → boost)
            × geographic_factor     (popular in user's country)
            × quality_score         (penalize very short/long/malformed queries)

Recency factor:
  If query frequency in last 1 hr > 5× average of last 7 days:
    recency_factor = 1.5  (trending)
  Else:
    recency_factor = 1.0

Example scores:
  "travel" → base=1M, recency=1.0, geo=1.0 → 1,000,000
  "travel ban" → base=50K, recency=1.5 (breaking news) → 75,000
  → "travel ban" surfaces near top during trending period
```

### 5.4 Personalization Layer

```
UserHistory {
  recent_searches: ["travel to japan", "japan visa", "tokyo hotel"]  (last 30 days)
  frequent_searches: ["travel", "flight booking"]
}

Personalization boost:
  If suggestion prefix matches any user recent/frequent search term:
    boost score by 2×
  
Example:
  Prefix: "tra"
  Global top: ["travel" (1M), "trade" (800K), "traffic" (600K)]
  User searches "japan": boost "travel" further (user recently searched travel)
  
  Personalized result: ["travel" (2M boosted), "trade" (800K), "traffic" (600K)]
```

- User history stored in **Redis (sorted set)**: `user_history:{userId}` → recent search terms.
- TTL: 30 days; max 50 entries (LTRIM to cap size).
- Only active for logged-in users; anonymous → global suggestions only.

### 5.5 Blacklist & Content Moderation

```
Blacklist maintained in Redis SET: blacklist:en
  SISMEMBER blacklist:en "offensive_term" → O(1) lookup

Auto-flagging:
  If a term's search count spikes suddenly (>10× in 1 hr) → human review queue
  (Catch coordinated manipulation of autocomplete)

Category filtering:
  Adult content, hate speech, spam → filtered from general autocomplete
  Separate adult mode with explicit opt-in (age verification)
```

---

## 6. Distributed System Design

### 6.1 Redis Cluster Layout

```
Redis Cluster (sharded by prefix key):
  Shard 1: prefixes starting with a–f    (handles "apple", "book", "car"...)
  Shard 2: prefixes starting with g–m
  Shard 3: prefixes starting with n–s
  Shard 4: prefixes starting with t–z

Each shard: 1 primary + 2 replicas → read from replicas (read-heavy)

Memory per shard:
  300 GB total / 4 shards = 75 GB/shard
  With 3× replication: 225 GB/shard (RAM)
  → Use Redis 7 with LFU eviction for long-tail prefixes
```

### 6.2 Autocomplete Service Scaling

```
Load balancer routes by:
  - Round-robin (default)
  - Consistent hash on prefix → same service node → local cache warm

Each service node:
  - Caffeine in-process cache: 2 GB heap for hot prefixes (80% of traffic)
  - Redis client pool: 50 connections to Redis cluster
  - Target: < 10ms P99 for 95% of requests served from local cache
```

---

## 7. Data Flow — Keystroke to Suggestion

```
User types: t → tr → tra → trav → trave

Keystroke: "trave"
        │
        │ GET /autocomplete?q=trave&lang=en
        ▼
Autocomplete Service Node 3
        │
Check Caffeine local cache:
  "autocomplete:en:trave" → HIT (from previous user's request)
        │
        ▼
Apply personalization boost (userId logged in):
  userHistory = ["travel to bali", "travel deals"]
  Boost "travel" and "travel deals"
        │
        ▼
Apply blacklist filter (O(1) per suggestion)
        │
        ▼
Return in < 5ms:
  ["travel", "traveler", "travel deals", "travel guide", "travel insurance"]
        │
        ▼
Client renders dropdown in browser
Total E2E (< 20 ms including network)
```

---

## 8. Handling Special Cases

| Scenario | Handling |
|---|---|
| **Single-char prefix ("t")** | Only return top 5 (avoid overwhelming UI); billions of matches, highly cached |
| **Empty prefix** | Return globally trending searches (no prefix lookup) |
| **Unknown prefix (no results)** | Fallback to shorter prefix (remove last char iteratively) |
| **Very long prefix (> 15 chars)** | Likely complete query; return exact match first, then fuzzy |
| **Non-Latin scripts** | Separate Redis key namespace per lang code (`:zh:`, `:ar:`, `:hi:`) |
| **Trending term not in top-K yet** | Flink near-real-time pipeline pushes update within 10 min |
| **Typo ("travle" instead of "travel")** | Basic: no fuzzy (fast path). Advanced: BK-tree or Levenshtein precomputed corrections |
| **Redis node failure** | Replica promotion (automatic); local cache serves during failover |

---

## 9. Trie vs Redis ZSET — Architecture Decision

| | In-Memory Trie (single server) | Redis ZSET per prefix |
|---|---|---|
| **Query latency** | O(L) — very fast | O(log K + L) — fast |
| **Update latency** | O(L × K) — fast | O(log K) per prefix — fast |
| **Scale** | Single machine limit (~50 GB) | Horizontally scalable (300+ GB) |
| **Failure** | Single point of failure | Redis Cluster — HA |
| **Recommendation** | Small-scale / in-interview design | Production design |

**Interview tip:** Start with the Trie to show data structure depth, then propose Redis ZSET as the production-scale solution.

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Data structure** | Redis ZSET per prefix | Scalable; slight overhead vs in-memory trie |
| **Top-K precomputed** | Stored at each prefix node | O(1) lookup; batch updates every 10 min (eventual) |
| **Score decay** | Exponential moving average | Balances freshness vs stability of suggestions |
| **Caching layers** | Local Caffeine + Redis | < 1ms hot path; reduces Redis load by 60% |
| **Freshness** | 10-min batch updates | Near-real-time trending; not truly real-time |
| **Personalization** | Score boost (not full re-rank) | Personalized suggestions without full ML rerank |
| **Blacklist enforcement** | Post-fetch Redis SET lookup | Clean separation; update blacklist without rebuilding index |
| **Partition strategy** | Shard by first char of prefix | Even distribution; popular prefixes distributed |

---

## 11. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **P99 latency** | < 20ms end-to-end; alert if > 30ms |
| **Local cache hit rate** | > 60%; if < 50% → cache tuning needed |
| **Redis hit rate** | > 90% of cache misses resolved in Redis |
| **Suggestion freshness** | Trending query appears in top-K within < 15 min of spike |
| **Blacklist violations** | Any blacklisted term surfacing in production → immediate alert |
| **Kafka lag (update pipeline)** | Alert if aggregation job falls > 30 min behind |

---

## 12. Future Enhancements
- **Fuzzy matching / typo correction** — BK-tree for edit distance; pre-generated common corrections.
- **Semantic suggestions** — "flights" also suggests "plane tickets", "airfare" (synonym expansion via embeddings).
- **Image / entity autocomplete** — suggest celebrity names, product images inline.
- **Real-time trending** (< 1 min freshness) — bypass batch pipeline with Flink direct Redis writes for spike detection.
- **Multi-word phrase completion** — `n`-gram models for phrase-level suggestions.

---

*Document prepared for SDE 3 system design interviews. Focus areas: Trie data structure + Redis ZSET at scale, top-K precomputation, multi-layer caching strategy, near-real-time score aggregation, personalization boost, and distributed sharding.*
