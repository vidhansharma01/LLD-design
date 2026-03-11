# High-Level Design: Real-Time Game Leaderboard (SDE3 Interview)

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: leaderboard type (global, per-game, per-tournament, friends-only), update frequency, scale.
2. Establish latency, freshness, and consistency SLOs.
3. Walk through architecture with focus on the **Redis Sorted Set core**.
4. Deep-dive ranking data structures, score update pipeline, and fan-out.
5. Cover pagination, near-me rank, historical snapshots, and anti-cheat.

---

## 1. Problem Statement And Scope

Design a real-time leaderboard system for a large-scale multiplayer game (think PUBG Mobile, Call of Duty: Mobile, or a casual mobile game like Ludo King) supporting:

- **Global leaderboard**: Top players worldwide ranked by score.
- **Friends leaderboard**: Rank among a user's friend list.
- **Regional leaderboard**: Rank within country/state.
- **Tournament leaderboard**: Time-bounded competition with snapshotted final rankings.
- **Game-specific leaderboard**: Separate boards per game mode.
- Real-time rank updates as scores change during active gameplay.
- User's own rank retrieval (even if they are rank #5,000,000).
- Top-K retrieval with player profile info (name, avatar, score).
- Historical leaderboard snapshots (yesterday, last week, all-time).

### 1.1 In Scope
- Score ingestion pipeline (high-throughput).
- Leaderboard ranking engine (Redis Sorted Sets).
- Real-time rank push to active clients (WebSocket/SSE).
- Leaderboard reads: top-K, player rank, near-me (players around my rank).
- Friends leaderboard.
- Tournament mode with finalization.
- Anti-cheat score validation.

### 1.2 Out Of Scope
- Game engine and score computation internals.
- Payment/in-app purchase system.
- Full matchmaking system.

---

## 2. Requirements

### 2.1 Functional Requirements
1. Game server submits score updates after each match/round/event.
2. Global leaderboard shows top-K players (K = 100 for display) with live rank.
3. User can query their own rank at any time (even rank #50,000,000).
4. User can see players ranked above and below them ("near-me" view, ±10 ranks).
5. Friends leaderboard shows user's rank among friends.
6. Regional leaderboard (country/state-level ranking).
7. Leaderboard resets on configurable schedules (daily, weekly, seasonal).
8. Tournament leaderboard: fixed window, finalized at end, persistent snapshot.
9. Historical leaderboard: yesterday's, last week's top-K accessible.
10. Anti-cheat: reject impossible score jumps.

### 2.2 Non-Functional Requirements
- **Scale**: 100 million registered players; 10 million concurrent active players.
- **Write throughput**: 500,000 score updates/sec (post-match reporting from game servers).
- **Read throughput**: 2,000,000 leaderboard reads/sec (users opening app, match end screen).
- **Latency**:
  - Top-K read: P99 < 10ms.
  - Player rank lookup: P99 < 20ms.
  - Score update reflected in leaderboard: < 1 second (real-time freshness SLO).
- **Availability**: 99.99%. Leaderboard reads can tolerate slight staleness (eventual consistency acceptable).
- **Consistency**: Score updates are idempotent; highest score wins (not cumulative in many game types).

---

## 3. Back-Of-The-Envelope Capacity Planning

| Parameter | Value |
|---|---|
| Total players | 100 million |
| Concurrent active players | 10 million |
| Match score submissions/sec | 500,000 |
| Leaderboard read QPS | 2,000,000 |
| Avg score update size | 64 bytes |
| Friends per user (avg) | 50 |
| Top-K display | K = 100 |
| Leaderboard types | Global + 200 regional + 50 game modes |

### 3.1 Redis Memory Estimate

```
Global leaderboard: 100M players × (8 bytes score + 8 bytes member_id) ≈ 1.6 GB
  + overhead ≈ 3 GB total for global sorted set.

Regional (200 countries × 500K avg players) = manageable per shard.
Game mode (50 modes × 2M avg players) = 50 × 128 MB ≈ 6.4 GB.

Total Redis memory: ~20–25 GB → fits on a few Redis nodes.
Friends leaderboard: computed on-demand (no persistent sorted set) — explained later.
```

### 3.2 Write Throughput
- 500K updates/sec with ZADD = well within Redis cluster capacity (1M ops/sec per node).
- With Kafka as buffer: game servers write to Kafka → consumers write to Redis (decoupled ingestion).

### 3.3 Read Throughput
- 2M reads/sec → Redis handles trivially. ZRANGE (top-K) and ZRANK are O(log N).
- With read replicas: 5 Redis replica nodes × 400K reads/sec each = 2M reads/sec.

---

## 4. High-Level API Design

### 4.1 Score Submission (Game Server → Leaderboard Service)

```http
# Submit score after match (called by game server, not client)
POST /v1/leaderboard/scores
{
  "player_id": "PLR_123",
  "game_mode": "battle_royale",
  "score": 8500,
  "match_id": "MATCH_abc",           // for deduplication
  "region": "IN",
  "timestamp": "2024-03-11T21:00:00Z"
}
→ { "accepted": true, "current_rank": 4521 }

# Batch score update (more efficient for high-throughput)
POST /v1/leaderboard/scores/batch
{
  "updates": [
    { "player_id": "PLR_1", "score": 9000, "match_id": "M1" },
    { "player_id": "PLR_2", "score": 7500, "match_id": "M1" }
  ],
  "game_mode": "battle_royale",
  "region": "IN"
}
```

### 4.2 Leaderboard Reads (Client → API)

```http
# Get top-K global leaderboard
GET /v1/leaderboard/global?game_mode=battle_royale&limit=100&offset=0
→ {
    "total_players": 100000000,
    "entries": [
      { "rank": 1, "player_id": "PLR_x", "name": "ProGamer99", "avatar_url": "...", "score": 99500 },
      ...
    ],
    "updated_at": "2024-03-11T22:00:00Z"
  }

# Get player's rank and surrounding players ("near-me")
GET /v1/leaderboard/rank?player_id=PLR_123&game_mode=battle_royale&window=10
→ {
    "player_rank": 45210,
    "player_score": 8500,
    "surrounding": [
      { "rank": 45200, "player_id": "...", "name": "...", "score": 8510 },
      ...  // 10 above and 10 below
    ]
  }

# Friends leaderboard
GET /v1/leaderboard/friends?player_id=PLR_123&game_mode=battle_royale
→ {
    "player_rank_among_friends": 3,
    "total_friends": 50,
    "entries": [ ... top friends ranked by score ... ]
  }

# Regional leaderboard
GET /v1/leaderboard/regional?region=IN&game_mode=battle_royale&limit=100

# Tournament leaderboard
GET /v1/leaderboard/tournament/{tournamentId}?limit=100

# Historical leaderboard (yesterday, last_week, all_time)
GET /v1/leaderboard/historical?period=last_week&game_mode=battle_royale&limit=100
```

### 4.3 WebSocket — Real-Time Rank Push

```
WS /v1/leaderboard/live?game_mode=battle_royale&player_id=PLR_123

Client subscribes → server holds connection.
Server pushes:
  { "type": "rank_update", "your_rank": 4520, "your_score": 8500, "top3": [...] }
  { "type": "top_update", "entries": [...top 3 changed...] }
```

---

## 5. Data Model

### 5.1 Redis Sorted Set — The Core Data Structure

**The entire leaderboard revolves around Redis Sorted Sets (`ZSET`).**

```
Redis ZSET structure:
  Key: "lb:{game_mode}:{period}"     e.g., "lb:battle_royale:weekly"
  Score: player's numeric score      e.g., 8500.0
  Member: player_id string           e.g., "PLR_123"

Redis ZSET properties:
  - Members sorted by score in ASCENDING order internally.
  - All O(log N) for insert, update, delete, rank query.
  - O(log N + K) for range queries returning K results.
  - ZREVRANK for descending rank (rank 1 = highest score).
```

**Leaderboard key namespace:**
```
lb:global:battle_royale:alltime       → Global all-time leaderboard
lb:global:battle_royale:weekly        → This week's leaderboard (resets Monday)
lb:global:battle_royale:daily         → Today's leaderboard (resets midnight)
lb:regional:IN:battle_royale:weekly   → India regional weekly
lb:tournament:TOURN_xyz               → Tournament-specific leaderboard
```

**Core Redis commands used:**
```redis
# Update player score (creates if not exists)
ZADD lb:global:battle_royale:alltime GT 8500 "PLR_123"
# GT flag: only update if new score is GREATER THAN current score (best-score wins)

# Get player's rank (0-indexed, reversed = descending)
ZREVRANK lb:global:battle_royale:alltime "PLR_123"
# Returns: 45209 → rank = 45210 (1-indexed)

# Get player's score
ZSCORE lb:global:battle_royale:alltime "PLR_123"
# Returns: "8500"

# Get top-100 players with scores
ZREVRANGE lb:global:battle_royale:alltime 0 99 WITHSCORES

# Get players around a rank (near-me: rank 45200 to 45219)
ZREVRANGE lb:global:battle_royale:alltime 45199 45219 WITHSCORES

# Get rank range for "near-me" window
ZREVRANGEBYSCORE lb:global:battle_royale:alltime +inf -inf WITHSCORES LIMIT 45199 20
```

**Why GT flag for ZADD?**
In most games, the leaderboard shows **best score ever** (not latest score). Using `ZADD GT` ensures the score only updates if the new score is higher — a single atomic Redis operation with no read-modify-write race condition.

### 5.2 Persistent Score Store (PostgreSQL)

Redis is the live ranking engine. PostgreSQL is the source of truth for scores.

```sql
-- Player best scores per leaderboard type
CREATE TABLE player_scores (
  player_id       VARCHAR(64)  NOT NULL,
  game_mode       VARCHAR(64)  NOT NULL,
  period_key      VARCHAR(32)  NOT NULL,    -- 'alltime', '2024-W11', '2024-03-11'
  best_score      BIGINT NOT NULL DEFAULT 0,
  latest_score    BIGINT NOT NULL DEFAULT 0,
  match_count     INT NOT NULL DEFAULT 0,
  last_updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (player_id, game_mode, period_key),
  INDEX (game_mode, period_key, best_score DESC)   -- for DB-based top-K fallback
);

-- Individual match results (score submission log)
CREATE TABLE match_results (
  result_id       VARCHAR(64) PRIMARY KEY,
  match_id        VARCHAR(64) NOT NULL,
  player_id       VARCHAR(64) NOT NULL,
  game_mode       VARCHAR(64) NOT NULL,
  score           BIGINT NOT NULL,
  region          VARCHAR(8),
  submitted_at    TIMESTAMP NOT NULL,
  UNIQUE (match_id, player_id),             -- idempotency: one result per player per match
  INDEX (player_id, submitted_at DESC)
);
```

### 5.3 Historical Leaderboard Snapshots

```sql
-- Daily/weekly snapshot of top-K (recorded at period end)
CREATE TABLE leaderboard_snapshots (
  snapshot_id     VARCHAR(64) PRIMARY KEY,
  game_mode       VARCHAR(64)  NOT NULL,
  period_type     ENUM('daily','weekly','monthly','tournament') NOT NULL,
  period_key      VARCHAR(32)  NOT NULL,    -- '2024-W11', '2024-03-11'
  rank            INT NOT NULL,
  player_id       VARCHAR(64)  NOT NULL,
  player_name     VARCHAR(128) NOT NULL,    -- denormalized at snapshot time
  score           BIGINT NOT NULL,
  snapshotted_at  TIMESTAMP NOT NULL,
  PRIMARY KEY (game_mode, period_key, rank)
);
```

### 5.4 Player Profile Cache (For Leaderboard Enrichment)

```
Redis Hash (player profile — enrichment for display):
Key: player:{player_id}:profile
Fields: { name, avatar_url, level, country }
TTL: 1 hour (refreshed from DB on miss)
```

Top-K leaderboard display requires player names + avatars alongside rank and score. These are fetched from player profile cache and merged with the ZSET rank data.

---

## 6. High-Level Architecture

```text
+------------------------ Clients --------------------------+
| Mobile App | Web Browser | Game Client (match end screen)|
+-----+-------+-----+------+----------+--------------------+
      |             |                 |
[CDN: Static Assets] [API Gateway]  [Game Servers]
                      (Auth, RL)    (Score Reporters)
                          |                |
      +-------------------+                |
      |           |       |                v
      v           v       v         [Kafka: score_events]
 +--------+  +-------+  +-----+           |
 |Leader- |  |Friends|  |Tour.|  +---------+---------+
 |board   |  |Leader-|  |LB   |  | Score Ingestion   |
 |Service |  |board  |  |Svc  |  | Service           |
 +---+----+  +---+---+  +--+--+  +------+------+-----+
     |            |        |             |      |
     v            v        v             v      v
 +-------+    +-------+  +-----+   +--------+ +----+
 |Redis  |    |Redis  |  |Redis|   |Redis   | | DB |
 |Sorted |    |Cluster|  |ZSET |   |(Anti-  | |(PG)|
 |Sets   |    |(Friend|  |     |   | cheat  | |    |
 |(Main  |    | sets) |  |     |   | state) | |    |
 | LBs)  |    +-------+  +-----+   +--------+ +----+
 +---+---+
     |
 +---+---+
 |WS Push|  ← Score change event → push rank delta to active WebSocket clients
 |Service|
 +-------+
```

---

## 7. Detailed Component Design

### 7.1 Score Ingestion Pipeline

**High write throughput (500K updates/sec) demands careful design.**

#### 7.1.1 Kafka-Buffered Ingestion

```
Game Servers → POST /v1/leaderboard/scores (or direct Kafka produce)
            → Kafka topic: score_events (partitioned by player_id)
            → Score Ingestion Service (Kafka consumers)
            → Redis ZADD + PostgreSQL upsert
```

**Why Kafka between game servers and Redis?**
- **Decoupling**: Game servers don't wait for Redis/DB writes → lower game server latency.
- **Buffering**: Absorbs bursts (tournament end → millions of simultaneous score submissions).
- **Retry**: If Redis is temporarily unavailable → Kafka retains events; replay on recovery.
- **Ordering**: Partition by `player_id` → all score events for one player processed in order → no stale score overwriting a fresher one.

**Kafka configuration:**
```
Topic: score_events
Partitions: 500 (each handles ~1,000 player_ids)
Partition key: player_id (hash-routed)
Retention: 24 hours
Consumers: Score Ingestion Service (consumer group, 500 consumers = 1 per partition)
```

#### 7.1.2 Score Ingestion Service Processing

```
For each event { player_id, game_mode, score, match_id, region, timestamp }:

Step 1: Idempotency check
  → Redis: GET dedup:{match_id}:{player_id}
  → If exists: skip (already processed this match result).
  → If not: SET dedup:{match_id}:{player_id} 1 EX 86400 (24h TTL)

Step 2: Anti-cheat validation (< 1ms, synchronous)
  → Fetch player's last known score from Redis: ZSCORE lb:global:{game_mode}:alltime player_id
  → Validate score jump is within allowed bounds (Section 7.5 — Anti-Cheat)
  → If invalid: drop event, publish to fraud_events Kafka topic.

Step 3: Multi-leaderboard ZADD (pipeline)
  Redis pipeline (single round trip):
    ZADD lb:global:{game_mode}:alltime GT {score} {player_id}
    ZADD lb:global:{game_mode}:weekly  GT {score} {player_id}
    ZADD lb:global:{game_mode}:daily   GT {score} {player_id}
    ZADD lb:regional:{region}:{game_mode}:weekly GT {score} {player_id}
  All in one Redis pipeline → sent atomically, single RTT.

Step 4: Persistent DB upsert (async, lower priority)
  INSERT INTO player_scores (player_id, game_mode, period_key, best_score, ...)
  ON CONFLICT (player_id, game_mode, period_key)
  DO UPDATE SET best_score = GREATEST(best_score, EXCLUDED.best_score),
                last_updated_at = NOW();

Step 5: Emit rank_changed event (for WebSocket push)
  → Publish to Kafka topic: rank_events { player_id, new_score, approximate_rank }
```

#### 7.1.3 Score Update Rate Per Player

**Problem**: A player might finish multiple matches within seconds. Each match end generates a score submission.
- Consecutive ZADD GT for same player: idempotent, only the highest wins.
- No fan-out fan-in problem for single player → Redis handles ZADD GT atomically.
- Throughput: 500K/sec across 10M active players → avg 0.05 updates/player/sec.

### 7.2 Redis Sorted Set — Deep Dive

#### 7.2.1 Internal Data Structure

Redis Sorted Set uses a combination of:
- **Hash table**: `member → score` for O(1) score lookup by member.
- **Skip list**: members sorted by score for O(log N) rank queries and range scans.

```
Skip list conceptually (simplified):

Score: [500] [1000] [2000] [5000] [8500] [9500]
Level 3:  ---------------->[5000]-------->[9500]
Level 2:  ----->[1000]---->[5000]-------->[9500]
Level 1:  [500]->[1000]->[2000]->[5000]->[8500]->[9500]

ZREVRANK query for score 8500:
  → Start from highest level → binary search shortcut → O(log N) steps.
  → For 100M players: log2(100M) ≈ 27 hops → extremely fast.
```

#### 7.2.2 Score Tie-Breaking

When two players have the same score, Redis Sorted Set orders them by lexicographic member name — **arbitrary and unfair**.

**Better approach: encode tie-breaking into the score itself.**

```
Composite score = (points × 10^9) + (10^9 - unix_timestamp_of_achievement)
                              ↑                     ↑
                         Main score        Earlier achievement = higher score
                                           (earlier timestamp → larger bonus)

Example:
  Player A: 8500 points, achieved at t=1000 → 8500 × 10^9 + (10^9 - 1000) = 8500000000999000999
  Player B: 8500 points, achieved at t=2000 → 8500 × 10^9 + (10^9 - 2000) = 8500000000999998000
  Player A has LOWER composite → lower in sorted set → HIGHER rank (ZREVRANK).
  Wait: actually Player A achieved it first (t=1000 < t=2000) → should be ranked higher.
  Adjust: use (10^9 - timestamp) so earlier time = larger bonus → higher composite.
  
Result: Tie broken by whoever achieved the score first. Fair and deterministic.

Alternative: encode as floating point.
  score = points + (1 / unix_timestamp) → "earlier time = tiebreak winner" with < 1.0 difference.
  Risk: floating point precision loss for large timestamps. Use integer composite instead.
```

#### 7.2.3 ZADD Flags Reference

```redis
ZADD key score member            -- standard: always update
ZADD key GT score member         -- only update if new score > current (best score)
ZADD key LT score member         -- only update if new score < current (fewest deaths)
ZADD key NX score member         -- only insert if member doesn't exist (first submission only)
ZADD key XX score member         -- only update existing members (no new inserts)
ZADD key GT CH score member      -- GT + return count of actually changed entries
```

**GT is the right flag for "highest score wins" games.**
**LT is right for "lowest time wins" games (racing, speedrun).**

### 7.3 Top-K Leaderboard — Read Path

```
GET /v1/leaderboard/global?game_mode=battle_royale&limit=100

Step 1: Cache check (L1)
  → Redis key: lb_cache:global:battle_royale:alltime:top100
  → If exists (TTL: 1 second): return cached JSON.
  → If miss: fetch from sorted set.

Step 2: Fetch from Redis Sorted Set
  ZREVRANGE lb:global:battle_royale:alltime 0 99 WITHSCORES
  → Returns: [(PLR_x, 99500), (PLR_y, 98200), ...]

Step 3: Enrich with player profiles (parallel)
  → MGET player:{PLR_x}:profile player:{PLR_y}:profile ...
  → Or: Redis pipeline of HGETALL player:{id}:profile for each player.
  → Cache miss for profiles: fetch from Player DB → populate cache.

Step 4: Merge rank + score + profile → response JSON.

Step 5: Cache top-100 response
  → SET lb_cache:global:battle_royale:alltime:top100 {json} EX 1
  → 1-second TTL → fresh enough for "real-time" perception while serving millions of reads.
```

**Why cache with 1-second TTL?**
At 2M reads/sec, even a 1-second cache absorbs 2M → 1 Redis Sorted Set call. The ZREVRANGE itself takes < 5ms, but at 2M/sec concurrent, Redis would be overwhelmed. The cache converts 2M reads/sec → 1 read/sec on the sorted set.

### 7.4 Player Rank And "Near-Me" Feature

#### 7.4.1 Player's Own Rank

```
GET /v1/leaderboard/rank?player_id=PLR_123&game_mode=battle_royale

Step 1: ZREVRANK lb:global:battle_royale:alltime "PLR_123"
  → Returns: 45209 (0-indexed)
  → Player rank = 45210

Step 2: ZSCORE lb:global:battle_royale:alltime "PLR_123"
  → Returns: 8500

Step 3: Total players = ZCARD lb:global:battle_royale:alltime
  → Returns: 100000000
  → "Rank 45,210 out of 100,000,000 players"

Latency: 3 Redis commands pipelined → < 2ms.
```

#### 7.4.2 Near-Me (Players Around My Rank)

This feature shows players ranked just above and below the user — highly motivating UX.

```
Player rank = 45210 (0-indexed: 45209).
Want: ranks 45200–45220 (10 above and 10 below).

ZREVRANGE lb:global:battle_royale:alltime 45199 45219 WITHSCORES
→ Returns 20 players with scores.

Enrich with profiles → merge → return.

Challenge: What if player is rank #1 or #100M?
  → Clamp range: max(0, rank-10) to min(total_players-1, rank+10).
  → ZREVRANGE handles boundary gracefully (returns fewer results at edges).
```

### 7.5 Friends Leaderboard

**Friends leaderboard is architecturally different — there is no persistent sorted set.**

#### 7.5.1 Why Not A Persistent Sorted Set Per User?

```
100M users × 50 friends each = 5 billion set entries.
Each set would have 50 members (trivially small) but maintaining 100M separate ZSET keys
in Redis → 100M key operations on every score update → impossible fan-out.

Example: Player A has 1,000 friends. On score update, need to update 1,000 ZSETs.
  500K updates/sec × 1,000 friends → 500M ZADD operations/sec. Infeasible.
```

#### 7.5.2 On-Demand Friend Rank Computation (The Right Approach)

```
GET /v1/leaderboard/friends?player_id=PLR_123

Step 1: Fetch PLR_123's friend list.
  → From Friend Service: [PLR_A, PLR_B, PLR_C, ... PLR_50]
  → Cached in Redis: friends:{player_id} → friend_id[] TTL: 5 min.

Step 2: Fetch scores for all friends (zip query).
  ZMSCORE lb:global:battle_royale:alltime PLR_A PLR_B PLR_C ... PLR_50
  → Returns score for each friend in one Redis command.
  → Includes querying user's own score too.

Step 3: Sort friends by score in application memory (50 elements: O(50 log 50) = trivial).

Step 4: Find user's position in sorted friend list.

Step 5: Enrich with friend profiles → return.

Total latency: 2 Redis calls + 1 sort + profile enrichment ≈ 10–20ms.
```

**ZMSCORE** (Redis 6.2+): returns multiple scores for a list of members in O(N log M) where N = count of requested members, M = total set size.

#### 7.5.3 Friend Leaderboard Real-Time Updates

For high-profile friends (e.g., top streamers), periodic push updates:
```
WebSocket subscription to friends leaderboard:
  → Server subscribes to score updates for each friend.
  → On any friend's score update: re-compute friends leaderboard → push delta.
  → Rate-limited: at most 1 update/sec pushed to client.
```

### 7.6 Tournament Leaderboard

Tournaments have a **fixed time window** and a **finalized snapshot** at end.

#### 7.6.1 During Tournament

```
Tournament ZSET: lb:tournament:{tournamentId}
  → Standard ZADD GT on score submissions within tournament window.
  → Only players registered for tournament are included.
  → Separate from global leaderboard.

Tournament entry filter:
  → On score submission: check tournament_participants:{tournamentId} SET in Redis.
  → SISMEMBER tournament_participants:{tournamentId} {player_id}
  → Only participants' scores contribute to tournament ZSET.
```

#### 7.6.2 Tournament Finalization (At End Time)

```
Scheduled job runs at tournament end:

1. Snapshot top-K (K = 1000 or complete if small tournament):
   ZREVRANGE lb:tournament:{tournamentId} 0 K-1 WITHSCORES
   → Write to leaderboard_snapshots table in PostgreSQL (permanent record).

2. Determine winners: top N players receive prizes.

3. Dispatch rewards:
   → Publish prize_events to Kafka → Rewards Service distributes in-game currency/items.

4. Archive Redis ZSET:
   RENAME lb:tournament:{tournamentId} lb:tournament_archive:{tournamentId}
   EXPIRE lb:tournament_archive:{tournamentId} 604800  # keep 7 days for queries, then evict.

5. Future queries for this tournament → served from PostgreSQL snapshot (not Redis).
```

### 7.7 Periodic Leaderboard Reset

Weekly/daily resets must happen **atomically without downtime**.

```
Daily reset (midnight UTC):

Option A: DELETE the ZSET — loses all data immediately.
  Problem: Users see empty leaderboard for a few seconds while re-populating.

Option B: Rename + new key (atomic, zero downtime):
  1. Snapshot (before midnight): ZREVRANGE lb:global:battle_royale:daily 0 999 WITHSCORES
     → Write to leaderboard_snapshots (yesterday's top-1000).
  2. At midnight:
     RENAME lb:global:battle_royale:daily lb:global:battle_royale:daily:yesterday
     # New key lb:global:battle_royale:daily starts fresh (empty).
     EXPIRE lb:global:battle_royale:daily:yesterday 86400
  3. Score submissions immediately populate fresh daily key via ZADD.
  4. "Yesterday" key available for 24 hours for historical queries.

Problem with RENAME for 100M member ZSETs:
  → RENAME is O(1) (just renames the key, doesn't move data).
  → Safe and instantaneous even for large sets.

Weekly reset (Monday midnight): same pattern with weekly key.
```

### 7.8 Real-Time Rank Push via WebSocket

#### 7.8.1 Architecture

```
Score update → Redis ZADD → Emit rank_changed event to Kafka topic: rank_events

Rank events consumer (Push Service):
  → For each rank_changed event { player_id, new_score }:
    1. Check if player_id has active WebSocket connection.
       → Check player_connections:{player_id} → ws_server_id (Redis hash).
    2. If connected: send via internal RPC to that WebSocket server.
       OR: publish to Redis pub/sub channel: push:{player_id} → ws server subscribes.
    3. WS server pushes: { "type": "your_rank_updated", "rank": ..., "score": ... }

For top-3 broadcast (everyone watching leaderboard):
  → Dedicated Redis pub/sub channel: top_updates:{game_mode}
  → Published when top-3 changes.
  → WS servers subscribed → broadcast to all connected clients viewing leaderboard.
```

#### 7.8.2 What To Push (Selective Push)

```
Not every score update warrants a push to all clients. Push strategy:

Case 1: Player's own score changed.
  → Push to that player only: { your_rank, your_score, delta_rank }

Case 2: Top-3 ranking changed.
  → Broadcast to all clients watching the leaderboard (subscribed on WS).
  → Frequency cap: maximum 1 broadcast/second even if top-3 changes 100x/sec.

Case 3: Friend's score improved.
  → If friend is in top-10 for the requesting user → push friend leaderboard update.

Case 4: Near-me range updated (player in ±5 ranks of me changed).
  → High infra cost; computed lazily on next API call, NOT pushed proactively.
```

#### 7.8.3 WebSocket Scale

```
10M concurrent players potentially watching leaderboard:
  → WebSocket servers: 10M / 50K connections per server = 200 WS server pods.
  → Redis pub/sub: channels per game_mode (5–10 channels). All WS pods subscribe.
  → Individual player push: Redis pub/sub channel per player (or connection registry lookup).

Connection registry:
  Key: player_connections:{player_id}
  Value: { ws_server_id, connection_id }
  TTL: refreshed by heartbeat; expires after 60s of no heartbeat (dead connection cleanup).
```

### 7.9 Anti-Cheat Score Validation

#### 7.9.1 Synchronous Pre-Validation (< 1ms)

```
Before ZADD, validate score is plausible:

Rule 1: Max score per match (game-specific constant)
  → battle_royale: max 10,000 points/match.
  → If submitted score > 10,000: reject immediately.

Rule 2: Score jump limit
  → Fetch player's current all-time best: ZSCORE lb:global:battle_royale:alltime player_id
  → Max score jump per match: current_best × 2 (generous but bounded).
  → If new_score > current_best * 3: flag for review; accept but quarantine.

Rule 3: Submission rate
  → Players can't finish more than 1 match every 5 minutes.
  → Redis: INCR rate:{player_id}:submissions EX 300
  → If count > 3 in 5 min window: suspicious; rate-limit submissions.

Rule 4: Match ID validation
  → match_id must reference a real match in the Match Service.
  → Async validation: verify match_id exists and player_id participated.
  → If validation fails retrospectively: rollback score update.
```

#### 7.9.2 Asynchronous Deep Validation

```
Fraud events Kafka consumer (runs after score accepted):

1. Statistical anomaly detection:
   → Is this player's score > 3 standard deviations above their historical average?
   → Flag for human review.

2. Cross-player validation:
   → Did multiple accounts submit the same match_id with same score?
   → Shared device fingerprint → shared account detection.

3. Time-of-submission analysis:
   → Score submitted 100ms after match end → impossible (network + server time).
   → Legitimate: submission within 5s–300s of match end.

4. Game replay validation (if replay data available):
   → Compare submitted score with score derived from actual game events replay.

On confirmed cheat:
  → Ban account.
  → ZREM lb:global:battle_royale:alltime {player_id}  → immediately removes from leaderboard.
  → Update all period leaderboards atomically.
```

---

## 8. End-To-End Critical Flows

### 8.1 Score Submission → Real-Time Leaderboard Update

```
1. Match ends. Game Server:
   POST /v1/leaderboard/scores { player_id: PLR_123, score: 9200, match_id: M_abc }

2. API Gateway: Auth (API key for game server). Route to Score Ingestion Service.

3. Ingestion Service:
   a. Idempotency: GET dedup:M_abc:PLR_123 → not found → SET dedup:M_abc:PLR_123 EX 86400.
   b. Anti-cheat rule check (< 1ms).
   c. Redis pipeline:
        ZADD lb:global:battle_royale:alltime GT 9200 "PLR_123"
        ZADD lb:global:battle_royale:weekly GT 9200 "PLR_123"
        ZADD lb:global:battle_royale:daily GT 9200 "PLR_123"
        ZADD lb:regional:IN:battle_royale:weekly GT 9200 "PLR_123"
   d. DB upsert (async via Kafka).
   e. Publish to rank_events: { player_id: PLR_123, new_score: 9200 }.

4. Push Service consumes rank_events:
   a. ZREVRANK lb:global:battle_royale:alltime "PLR_123" → rank 31200.
   b. Fetch WS connection for PLR_123: player_connections:PLR_123 → ws_server_7.
   c. Route push to ws_server_7: { your_rank: 31201, your_score: 9200, delta: +14009 }.

5. PLR_123's app: rank update notification shown to player.
   Total latency goal: < 1 second from match end to player seeing new rank.
```

### 8.2 Tournament Finalization Flow

```
At tournament end time (scheduled job):

1. Acquire distributed lock: SET tournament:{id}:finalization_lock 1 NX EX 300
   → Only one instance runs finalization.

2. Read final scores:
   ZREVRANGE lb:tournament:{id} 0 999 WITHSCORES → top 1000 players.

3. Write snapshot to PostgreSQL (bulk insert, transaction):
   INSERT INTO leaderboard_snapshots (tournament_id, rank, player_id, score).

4. Determine prize distribution:
   → Rank 1: 10,000 gems. Rank 2-3: 5,000. Rank 4-10: 2,000. etc.
   → Publish prize_events to Kafka for each winner.

5. Notify winners:
   → Push notification + in-app notification to each prize winner.

6. Rename Redis ZSET → archive key with 7-day TTL.

7. Publish tournament_ended event → leaderboard UI shows "Final Results" banner.
```

### 8.3 Daily Leaderboard Reset

```
Cron job at 00:00:00 UTC:

1. Acquire distributed lock: SET daily_reset_lock 1 NX EX 120.

2. Snapshot top-1000 for all game modes (parallel):
   For each game_mode:
     ZREVRANGE lb:global:{game_mode}:daily 0 999 WITHSCORES
     → INSERT INTO leaderboard_snapshots (period_type='daily', period_key='2024-03-11', ...).

3. Rotate keys (for each game_mode):
   RENAME lb:global:{game_mode}:daily lb:global:{game_mode}:daily:2024-03-11
   EXPIRE lb:global:{game_mode}:daily:2024-03-11 86400

4. New lb:global:{game_mode}:daily starts empty → populated by next day's submissions.

5. Broadcast "Daily leaderboard reset!" event → all connected WS clients.
```

---

## 9. Consistency And Idempotency

### 9.1 Idempotent Score Updates

```
Challenge: Network retry causes same match result to be submitted twice → score counted twice.

Solution:
  → Deduplication key: (match_id, player_id) → unique per match per player.
  → Redis SET NX for fast dedup: SET dedup:{match_id}:{player_id} 1 NX EX 86400.
  → DB UNIQUE constraint: (match_id, player_id) on match_results table.
  → ZADD GT: even if same score submitted twice → second ZADD GT is a no-op (score not updated).

Result: Submitting the same match result 10 times → identical leaderboard state as submitting once.
```

### 9.2 Eventual Consistency For Leaderboard Reads

```
Consistency model: strong for score writes; eventually consistent for rank reads.

Reason:
  → Score ZADD to Redis: immediate, strong.
  → Player profile enrichment (name, avatar): cached with 1h TTL → may show stale name.
  → Top-100 cache: 1-second TTL → rank may be 1s stale.
  → Historical snapshots: from DB → accurate at time of snapshot.

Acceptable because:
  → Users tolerate 1-second leaderboard staleness (it's a game, not a financial system).
  → Cached top-100 saves enormous Redis load.
  → Player's own rank is always fetched fresh (no cache).
```

### 9.3 Redis Failure — Durability Risk

```
Risk: Redis is in-memory. Node crash → leaderboard data loss.

Mitigations:
  1. Redis AOF (Append-Only File) persistence with fsync every second.
     → Max 1-second data loss on crash.
  2. Redis Cluster with 3 replicas per shard.
     → Primary crash → automatic failover to replica in < 30 seconds.
  3. Warm-up from PostgreSQL on Redis restart:
     → On Redis startup: batch read top-100K players from player_scores DB.
     → ZADD all into Redis ZSET → warm Redis in < 5 minutes.
     → Lower-rank players re-populated lazily (on first score update or request).
  4. Score updates durable in Kafka (24h retention).
     → If Redis lost during 5-min warm-up window: replay Kafka from last committed offset.
```

---

## 10. Caching Strategy

```
L1 — Client-side (app):
  → Top-100 leaderboard JSON: 5-second TTL.
  → Player's own rank: 1-second TTL; updated via WebSocket.

L2 — CDN:
  → Top-100 leaderboard response: 1-second TTL (appropriate for "near real-time").
  → Historical leaderboards (yesterday, last week): 10-minute TTL (doesn't change).
  → Player profile pages (avatar, bio): 5-minute TTL.

L3 — Redis Application Cache:
  → lb_cache:global:{game_mode}:alltime:top100 → JSON blob  TTL: 1s
  → player:{id}:profile → Hash  TTL: 1h
  → friends:{player_id} → List of friend IDs  TTL: 5 min
  → player_connections:{player_id} → ws_server_id  TTL: 60s (heartbeat-refreshed)
  → dedup:{match_id}:{player_id} → "1"  TTL: 24h

L4 — Redis Sorted Sets (primary leaderboard store):
  → lb:global:{game_mode}:alltime → persistent (no TTL)
  → lb:global:{game_mode}:weekly → expires Sunday midnight (or rotated)
  → lb:global:{game_mode}:daily → expires midnight (rotated)
  → lb:tournament:{id} → expires after tournament + 7 days
```

---

## 11. Data Partitioning And Scaling

### 11.1 Redis Cluster Sharding

```
Redis Cluster hash slots: 16,384 total.
Slot assignment: CRC16(key) % 16,384.

Key design for optimal sharding:
  lb:global:battle_royale:alltime  → hash slot based on full key → single shard.
  lb:regional:IN:battle_royale:weekly → different shard.

For extremely large ZSETs (100M members, 3 GB):
  → Can distribute one ZSET across multiple nodes using hash tags and application-level partitioning.

Partitioning 100M-member ZSET if needed:
  Key: lb:global:{game_mode}:alltime:{shard}  where shard = hash(player_id) % 16

  Writes: ZADD to the correct shard.
  ZREVRANK: query correct shard + adjust rank by sum of sizes of higher shards.
  Global top-K: query top-K from each shard, merge-sort → O(K × 16) in application.

In practice: 100M entries × 32 bytes per entry = 3.2 GB → fits on a single large Redis node
(32–64 GB RAM standard). Sharding only needed if > ~100M entries per leaderboard.
```

### 11.2 Kafka Partition Strategy

```
score_events topic:
  Partition by: player_id (ensures ordering for same player).
  Partitions: 500 → handles 500K/sec at 1K events/sec per partition.
  Consumer group: score_ingestion → 500 consumer instances.

rank_events topic:
  Partition by: player_id (rank push to WS server for that player).
  Consumers: Push Service instances (one per WebSocket server region).
```

### 11.3 Multi-Region Deployment

```
Regions: India (Mumbai), US (Virginia), Europe (Frankfurt), SEA (Singapore).

Architecture per region:
  → Independent Redis cluster with regional leaderboard data.
  → Regional leaderboard: lb:regional:{region}:{game_mode}:*  → local Redis.
  → Global leaderboard: replicated across regions via:
      Option A: Single authoritative Redis (India) + read replicas per region.
               → Writes to India; reads from local replica (< 500ms stale).
      Option B: Event-sourced: Kafka with cross-region replication (Confluent MirrorMaker).
               → Each region maintains own ZSET; eventual consistency across regions.

For a global game: Option B (multi-master per region) is preferred → lower write latency.
Tradeoff: global rank may differ slightly between regions (seconds of lag acceptable for games).
```

---

## 12. Observability And SLOs

### 12.1 SLO Targets

| Metric | SLO |
|---|---|
| Top-K leaderboard latency P99 | < 10ms |
| Player rank lookup P99 | < 20ms |
| Score update to leaderboard freshness | < 1 second |
| Score submission throughput | 500K/sec sustained |
| WebSocket rank push latency P99 | < 500ms from score update |
| Tournament finalization success rate | 100% by end_time + 60s |

### 12.2 Key Metrics

```
Leaderboard health:
  - ZADD latency histogram (should be < 1ms P99).
  - Redis memory utilization per shard (alert > 80%).
  - Kafka score_events consumer lag (alert > 10K messages).

User experience:
  - Top-K API cache hit rate (should be > 99%).
  - Friends leaderboard API latency distribution.
  - WebSocket connection stability (reconnection rate).

Anti-cheat:
  - Score rejection rate by rule type.
  - Suspicious submission rate (velocity violations).
  - Score rollback events per day.

Business:
  - Daily active leaderboard viewers (engagement metric).
  - Tournament participation rate.
  - Player rank improvement rate (retention signal).
```

---

## 13. Major Trade-Offs And Why

### 13.1 Redis ZSET vs Database (PostgreSQL) For Rankings

| Approach | Read Latency | Write Latency | Memory | Durability |
|---|---|---|---|---|
| Redis ZSET | < 2ms (O log N) | < 1ms | In-memory (expensive) | AOF + replica |
| PostgreSQL ranking | 100s ms (ORDER BY on 100M rows) | < 10ms | Disk (cheap) | ACID |
| PostgreSQL + pg_rank window | 50-200ms | < 10ms | Disk | ACID |

**Decision**: Redis ZSET for live rankings (sub-millisecond operations). PostgreSQL for persistent storage and historical queries. Hybrid is non-negotiable at 2M reads/sec and 500K writes/sec.

### 13.2 Friends Leaderboard: Static ZSET vs On-Demand Computation

- Static ZSET per user: O(1) reads, O(friends) writes on score update (fan-out). Infeasible at 500K/sec × 50 friends = 25M ZADD/sec.
- On-demand: O(friends) ZSCORE lookups on read. Perfectly acceptable — 50 ZSCORE ops per friend leaderboard request.
- **Decision**: On-demand computation using ZMSCORE. Read path takes < 5ms for 50 friends.

### 13.3 Score Update Policy: Best Score vs Cumulative Score

- **Best score** (`ZADD GT`): Fairer for skill display; idempotent on retries; simpler.
- **Cumulative score** (`ZADD INCR`): Rewards consistency; not idempotent on retries (need dedup).
- **Decision**: Game-specific. HLD should support both. Use `ZADD GT` (best score) or `ZADD INCR` with dedup. Both valid with explanation.

### 13.4 WebSocket vs Polling For Real-Time Rank Updates

- Polling: simple; high server load at 10M active clients × 1 poll/sec = 10M req/sec on rank API.
- WebSocket: persistent connection; server pushes only on change; much lower server load.
- **Decision**: WebSocket for active clients. Polling (30s interval) as fallback for low-bandwidth clients.

### 13.5 Eager vs Lazy Player Profile Enrichment

- Eager: pre-join profile into leaderboard ZSET value → fast reads but inconsistent on profile change.
- Lazy: fetch profiles separately on read request (cache + pipeline).
- **Decision**: Lazy with Redis Hash cache. Profile changes rare; cache miss < 5ms from DB.

---

## 14. Interview-Ready Deep Dive Talking Points

**"How do you handle 500,000 score updates per second?"**
> Kafka absorbs burst writes from game servers. Score Ingestion consumers (500 instances, one per Kafka partition) process events and execute Redis ZADD GT pipeline. Redis Cluster handles 1M ops/sec per node. Idempotency via dedup key prevents duplicate score inflation. Async DB upsert decoupled from critical path.

**"How do you get a player's rank when they're #50,000,000?"**
> ZREVRANK on Redis Sorted Set — O(log N) = ~27 operations for 100M members. Returns in < 1ms regardless of rank. No pagination or scanning needed. This is Sorted Set's killer feature over any SQL-based approach.

**"How do you implement the 'near-me' feature?"**
> ZREVRANK to get 0-indexed rank → ZREVRANGE from (rank-10) to (rank+10) with WITHSCORES. Two O(log N) operations. Edge cases (rank #1, last rank) handled by clamping range to [0, total-1]. Enrich 20 players' profiles via Redis pipeline. Total < 5ms.

**"How does the leaderboard reset without downtime?"**
> RENAME command is O(1) — atomic key rename. Old key archived as lb:*:yesterday with 24h TTL. New key starts empty and fills as new score submissions arrive. No service interruption; no empty leaderboard period.

**"What if Redis goes down?"**
> Redis Cluster provides automatic failover (< 30s). AOF persistence limits data loss to < 1s. Warm-up job re-populates from PostgreSQL on restart (top-100K loaded in < 5 min). Kafka replay fills the gap during warm-up. Leaderboard may show slight staleness during recovery; graceful degradation via DB fallback.

---

## 15. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Scope: leaderboard types, scale, SLOs, consistency model |
| 5–12 min | Redis Sorted Set deep dive: ZADD GT, ZREVRANK, ZREVRANGE, tie-breaking |
| 12–20 min | Score ingestion: Kafka pipeline, idempotency, anti-cheat |
| 20–28 min | Read path: top-K cache, player rank, near-me, friends on-demand |
| 28–35 min | Real-time push: WebSocket, Redis pub/sub, selective push strategy |
| 35–42 min | Tournament, leaderboard reset (RENAME trick), multi-region |
| 42–45 min | Trade-offs, Redis durability, extensions |

---

## 16. Extensions To Mention If Time Permits

- **Percentile rank**: "You're in the top 3.2% globally" — computed using ZCARD + ZREVRANK.
- **Achievement badges**: "First to reach 10,000 points today" — event-driven badge engine.
- **Season leaderboard**: Long-running (3-month) competitive season with progressive scoring.
- **Clan/Guild leaderboard**: Aggregate member scores; maintain clan ZSET updated on member score change.
- **Multi-metric leaderboard**: Rank by composite: kills × 3 + wins × 10 - deaths × 1. Computed composite score stored in ZSET.
- **Anti-smurf detection**: New accounts with suspiciously high scores → ghost rank (shown to player but not on public leaderboard until verified).
- **Spectator mode**: Top-ranked players' live matches viewable by other players (stream leaderboard updates as game events).
- **Country flags on leaderboard**: Country derived from registration; stored in player profile Redis hash; displayed alongside name.
