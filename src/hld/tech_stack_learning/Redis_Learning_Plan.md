# Redis: 7-Day Learning Plan — Beginner to Advanced

> **Goal**: By Day 7 you should be able to design Redis-backed production systems,
> explain internals in SDE3 interviews, and write real application code using Redis.
>
> **Time per day**: 2–3 hours of study + hands-on practice.
> **Tools needed**: Redis CLI, Redis Insight (GUI), Jedis / Lettuce (Java), Docker.

---

## Setup (Do This Before Day 1)

```bash
# Install Redis via Docker (easiest cross-platform)
docker run -d --name redis-learn -p 6379:6379 redis:7.2

# Connect via CLI
docker exec -it redis-learn redis-cli

# Verify
127.0.0.1:6379> PING
PONG
```

Install **RedisInsight** (free GUI): https://redis.com/redis-enterprise/redis-insight/

---

## Day 1 — What Redis Is And Why It Exists

### 1.1 What Is Redis?

Redis = **RE**mote **DI**ctionary **S**erver.

- An **in-memory** data structure server.
- Data lives in RAM → operations in **microseconds** (not milliseconds like a DB).
- Optionally persists to disk (AOF / RDB snapshots).
- Single-threaded command execution → no race conditions inside Redis.
- Used as: Cache, Session store, Rate limiter, Message broker, Leaderboard, Pub/Sub bus.

### 1.2 Redis vs Memcached vs Database

```
┌─────────────────┬───────────────┬──────────────┬─────────────────┐
│ Feature         │ Redis         │ Memcached    │ PostgreSQL      │
├─────────────────┼───────────────┼──────────────┼─────────────────┤
│ Data structures │ 10+ types     │ String only  │ Tables (SQL)    │
│ Persistence     │ Yes (opt)     │ No           │ Yes (ACID)      │
│ Replication     │ Yes           │ No (3rd lib) │ Yes             │
│ Speed           │ ~100K ops/ms  │ ~100K ops/ms │ ~1K ops/ms      │
│ Pub/Sub         │ Yes           │ No           │ LISTEN/NOTIFY   │
│ Transactions    │ MULTI/EXEC    │ No           │ Full ACID       │
│ Cluster         │ Yes (native)  │ Client-side  │ Yes (PG)        │
└─────────────────┴───────────────┴──────────────┴─────────────────┘
Use Redis when: Latency < 5ms, data fits in RAM, auxiliary role (not source of truth).
Use DB when: Durability, complex joins, ACID transactions required.
```

### 1.3 Redis Data Model

```
Redis is a KEY → VALUE store.
Every key is a string. Values can be any of 10+ data types.
Keys are case-sensitive: "User:1" ≠ "user:1".

Key naming convention (use colons as namespace separator):
  user:1001:profile
  session:abc123
  rate:192.168.1.1:minute:202403131430
  leaderboard:game:battle_royale:weekly
```

### 1.4 Day 1 Practice Commands

```redis
# Basic SET and GET
SET name "Vidhan"
GET name              # → "Vidhan"

# SET with expiry (TTL = Time To Live)
SET otp "482910" EX 300    # expires in 300 seconds (5 min)
TTL otp                     # → 298 (seconds remaining)
PTTL otp                    # → milliseconds remaining
PERSIST otp                 # remove TTL → key lives forever

# Check existence
EXISTS name               # → 1 (exists), 0 (not found)

# Delete
DEL name                  # → 1 (deleted count)
UNLINK name               # async delete (non-blocking for large values)

# Key inspection
TYPE name                 # → string
OBJECT ENCODING name      # → embstr (small string) or raw (long string)

# Pattern matching (avoid in production on large DBs — use SCAN instead)
KEYS user:*               # lists all keys matching pattern — BLOCKS server!
SCAN 0 MATCH user:* COUNT 100   # non-blocking cursor-based iteration

# Rename
RENAME old_key new_key

# Key expiry management
EXPIREAT session:abc 1710172800   # expire at specific unix timestamp
```

### 1.5 Day 1 Homework

1. Start Redis via Docker.
2. Store your name, age, city as separate keys with `SET`.
3. Set an OTP key that expires in 2 minutes. Watch it disappear with `TTL`.
4. Use `SCAN` to list all your keys.

---

## Day 2 — The 5 Core Data Types

### 2.1 String

```
The most fundamental type. Can hold text, integers, or binary (up to 512 MB).

SET counter 0
INCR counter          # → 1 (atomic increment — safe for concurrent use)
INCRBY counter 5      # → 6
DECR counter          # → 5
DECRBY counter 2      # → 3
INCRBYFLOAT price 2.5 # floats supported too

APPEND greeting "Hello"
APPEND greeting " World"
GET greeting          # → "Hello World"
STRLEN greeting       # → 11

GETSET name "Bob"     # returns old value, sets new value (atomic)
SETNX name "Alice"    # Set if Not eXists (returns 1 if set, 0 if key already existed)
MSET k1 v1 k2 v2      # multi-set (atomic)
MGET k1 k2            # multi-get

Use cases:
  → Cache HTML fragments:  SET page:home:html "<html>..." EX 3600
  → Session tokens:        SET session:xyz "user_id=123" EX 1800
  → Counters:              INCR page_views:homepage
  → Distributed locks:     SET lock:resource unique_val NX EX 30
```

### 2.2 List (Doubly Linked List)

```
Ordered sequence. Insert at head (LPUSH) or tail (RPUSH). Fast at ends, slow in middle.

RPUSH queue "task1" "task2" "task3"    # append to right
LPUSH queue "urgent_task"              # prepend to left
LRANGE queue 0 -1                      # get all: ["urgent_task","task1","task2","task3"]
LLEN queue                             # → 4
LPOP queue                             # → "urgent_task" (remove from left)
RPOP queue                             # → "task3" (remove from right)
LINDEX queue 0                         # → "task1" (0-indexed peek)
LSET queue 0 "new_task1"              # update element at index

# Blocking pop (used in job queues — waits if list is empty)
BLPOP queue 10                         # blocks up to 10 seconds for an element

# Trim list to bounded size
RPUSH log "entry1" "entry2" "entry3" "entry4" "entry5"
LTRIM log 0 2                          # keep only first 3 elements

Use cases:
  → Job/Task queue:      RPUSH jobs task1 | BLPOP jobs 0
  → Activity feed:       LPUSH feed:user123 event | LTRIM feed:user123 0 99 (last 100 events)
  → Recent search history: LPUSH recent:user1 "shoes" | LTRIM recent:user1 0 9
```

### 2.3 Hash (Field → Value Map)

```
A map of string fields to string values. Efficient for representing objects.
One Hash = one Redis key with many fields. Much more memory-efficient
than using separate string keys for each field.

HSET user:1001 name "Vidhan" age "25" city "Mumbai"
HGET user:1001 name                    # → "Vidhan"
HMGET user:1001 name city              # → ["Vidhan", "Mumbai"]
HGETALL user:1001                      # → { name: Vidhan, age: 25, city: Mumbai }
HKEYS user:1001                        # → [name, age, city]
HVALS user:1001                        # → [Vidhan, 25, Mumbai]
HLEN user:1001                         # → 3
HDEL user:1001 city                    # remove one field
HEXISTS user:1001 age                  # → 1
HINCRBY user:1001 age 1               # increment numeric field
HSETNX user:1001 email "v@x.com"      # set field only if it doesn't exist

Memory comparison:
  100 fields as separate STRING keys:  100 keys × ~60 bytes overhead = ~6 KB overhead.
  100 fields in ONE Hash:              ~1 Hash overhead + 100 × ~10 bytes = ~1 KB overhead.
  → Hash is 6× more memory efficient for objects.

Use cases:
  → User profile:         HSET user:1001 name age email last_login
  → Shopping cart:        HSET cart:user1001 ASIN_123 "2" ASIN_456 "1"
  → Config per entity:    HSET city_config:mumbai max_surge "4.0" emergency_cap "2.0"
  → Driver location:      HSET driver:D1 cell "881f823e1bfffff" status "IDLE"
```

### 2.4 Set (Unordered Unique Collection)

```
Collection of unique, unordered strings. Fast membership test O(1).

SADD fruits "apple" "banana" "mango" "apple"   # duplicate ignored
SMEMBERS fruits                                 # → {apple, banana, mango} (any order)
SCARD fruits                                    # → 3 (cardinality)
SISMEMBER fruits "mango"                        # → 1 (exists)
SMISMEMBER fruits "mango" "grape"               # → [1, 0] (bulk check)
SREM fruits "banana"                            # remove member
SPOP fruits                                     # remove and return random member
SRANDMEMBER fruits 2                            # peek 2 random members (no remove)

# Set operations (very powerful)
SADD setA "a" "b" "c"
SADD setB "b" "c" "d"

SINTER setA setB        # intersection → {b, c}
SUNION setA setB        # union        → {a, b, c, d}
SDIFF  setA setB        # A - B        → {a}
SDIFF  setB setA        # B - A        → {d}

# Store result into new key
SINTERSTORE result setA setB      # stores {b, c} in "result"

Use cases:
  → Online users:         SADD online_users user:1 user:2
  → Unique visitors:      SADD visited:page:home:20240314 user:1 user:2 user:3
  → Tag system:           SADD tags:post:101 "java" "redis" "backend"
  → Friends list:         SADD friends:user1 user2 user3
  → Common friends:       SINTER friends:user1 friends:user2
  → Blocklist:            SADD blocked_items ASIN_123 ASIN_456
```

### 2.5 Sorted Set / ZSet (Score-Ranked Set)

```
Unique members, each with a floating-point score. Sorted by score ascending.
ZREVRANGE gives descending order (most used for rankings).

ZADD leaderboard 9500 "Alice"
ZADD leaderboard 8200 "Bob"
ZADD leaderboard 7100 "Charlie"

ZREVRANGE leaderboard 0 -1 WITHSCORES    # all, descending: Alice 9500, Bob 8200, Charlie 7100
ZREVRANK leaderboard "Bob"               # → 1 (0-indexed rank, 0=highest score)
ZSCORE leaderboard "Alice"               # → "9500"
ZCARD leaderboard                        # → 3

ZINCRBY leaderboard 300 "Charlie"        # Charlie's score becomes 7400

# Range by score
ZRANGEBYSCORE leaderboard 7000 9000 WITHSCORES    # items with score 7000-9000

# Remove members
ZREM leaderboard "Bob"
ZPOPMAX leaderboard         # remove and return highest scorer
ZPOPMIN leaderboard         # remove and return lowest scorer

# ZADD flags (critical for interview)
ZADD leaderboard GT 9600 "Alice"    # only update if new score > existing (best-score-wins)
ZADD leaderboard LT 7000 "Alice"    # only update if new score < existing
ZADD leaderboard NX 5000 "Dave"     # only insert if member doesn't exist
ZADD leaderboard XX 5000 "Dave"     # only update existing members (no new inserts)
ZADD leaderboard CH 5000 "Dave"     # return count of changed entries

# Multiple score queries (Redis 6.2+)
ZMSCORE leaderboard "Alice" "Bob" "Charlie"    # returns all three scores in one call

# Set operations on sorted sets
ZUNIONSTORE merged 2 board1 board2           # union; sum scores of common members
ZINTERSTORE common 2 board1 board2 WEIGHTS 1 1

Use cases:
  → Leaderboard:      ZADD | ZREVRANGE | ZREVRANK
  → Top-K:            ZADD | ZREVRANGE 0 K-1
  → Rate limiting:    ZADD with timestamp as score → ZREMRANGEBYSCORE to remove old
  → Priority queue:   ZADD | ZPOPMIN (lowest score = highest priority)
  → Autocomplete:     Store prefixes as members with 0 score → range by lex
```

### 2.6 Day 2 Practice

```
Build a mini leaderboard:
1. Add 5 players with ZADD.
2. Get the top-3 with ZREVRANGE 0 2 WITHSCORES.
3. Increment a player's score with ZINCRBY.
4. Find rank of a specific player with ZREVRANK.
5. Build a shopping cart using Hash (HSET, HINCRBY for quantity, HGETALL).
6. Track unique page visitors using Set (SADD, SCARD).
```

---

## Day 3 — Advanced Data Types + Expiry Patterns

### 3.1 Bitmap (Bit-Level Operations)

```
A String used as an array of bits. Extremely memory-efficient for boolean per-user data.

SETBIT active_users:20240314 1001 1   # user 1001 was active today
SETBIT active_users:20240314 1002 1   # user 1002 was active today
SETBIT active_users:20240314 1003 0   # user 1003 was NOT active

GETBIT active_users:20240314 1001     # → 1
GETBIT active_users:20240314 9999     # → 0 (default)

BITCOUNT active_users:20240314        # count of set bits = active users today
BITCOUNT active_users:20240314 0 0    # count bits in byte range 0–0

# Bit operations between multiple bitmaps
BITOP AND result active:day1 active:day2   # users active on BOTH days (retention)
BITOP OR result active:day1 active:day2    # users active on either day

Memory efficiency:
  1 million users → 1M bits = 125 KB. Compare to: 1M strings = ~50 MB.
  1000× more efficient than a Set for boolean per-user flags.

Use cases:
  → Daily active users (DAU): SETBIT dau:20240314 user_id 1
  → Feature flags per user:   SETBIT feature:dark_mode user_id 1
  → Read receipts:            SETBIT read:message:123 user_id 1
  → Attendance tracking:      SETBIT attendance:class:101 student_id 1
```

### 3.2 HyperLogLog (Approximate Cardinality)

```
Probabilistic structure. Estimates COUNT DISTINCT with ~0.81% error.
Fixed memory: 12 KB per HyperLogLog regardless of cardinality.

PFADD visitors:20240314 "user:1" "user:2" "user:3" "user:1"  # duplicates ignored
PFCOUNT visitors:20240314      # → 3 (approximate unique count)

# Merge multiple HyperLogLogs
PFADD visitors:day1 user:1 user:2 user:3
PFADD visitors:day2 user:3 user:4 user:5
PFMERGE visitors:week visitors:day1 visitors:day2
PFCOUNT visitors:week          # → 5 (unique users across both days)

Use cases:
  → Unique page views:        PFADD pageviews:homepage user_id
  → Unique search queries:    PFADD search_queries:20240314 query_str
  → Monthly uniq users:       PFADD mau:202403 user_id
  
Why HyperLogLog over Set?
  Set for 100M unique users: 100M × 16 bytes = 1.6 GB.
  HyperLogLog for 100M users: 12 KB fixed. Same 0.81% error always.
  Trade-off: cannot enumerate members (only count them).
```

### 3.3 Streams (Append-Only Log)

```
Redis Stream is an append-only, ordered message log (like Kafka, but in Redis).
Messages have auto-generated IDs: "timestamp-sequence" e.g. "1710172800000-0".

# Producer: add message to stream
XADD events * user_id 1001 action "purchase" item_id "ASIN_123"
# * means auto-generate ID. Returns: "1710172800000-0"

# Read messages from beginning
XREAD COUNT 10 STREAMS events 0          # read 10 msgs starting from ID 0

# Read new messages only (block for 5 seconds if empty)
XREAD COUNT 5 BLOCK 5000 STREAMS events $

# Consumer Groups (multiple consumers, each gets different messages)
XGROUP CREATE events orders_group $ MKSTREAM   # create consumer group
XREADGROUP GROUP orders_group consumer1 COUNT 5 STREAMS events >
  # > means "undelivered to this group"

# Acknowledge processed message
XACK events orders_group 1710172800000-0

# View pending (unacknowledged) messages
XPENDING events orders_group - + 10

XLEN events              # number of messages in stream
XRANGE events - +        # all messages (- = min, + = max)
XRANGE events 1710172800000-0 +  # messages after specific ID

Use cases:
  → Real-time activity feed: XADD user_feed user_id event_type
  → Event sourcing:          XADD domain_events * entity_id 123 event "OrderPlaced"
  → Lightweight Kafka:       When Kafka is too heavy but pubsub is needed
```

### 3.4 Expiry Strategies

```
Redis offers fine-grained TTL control. Critical for cache design.

# Command-level TTL options
SET key value EX 60           # expires in 60 SECONDS
SET key value PX 60000        # expires in 60000 MILLISECONDS
SET key value EXAT 1710172800 # expires at UNIX TIMESTAMP (seconds)
SET key value PXAT 1710172800000  # expires at UNIX TIMESTAMP (milliseconds)
SET key value KEEPTTL         # preserve existing TTL when updating value

EXPIRE key 60                 # set TTL in seconds (after key already exists)
PEXPIRE key 60000             # set TTL in milliseconds
EXPIREAT key 1710172800       # expire at unix timestamp
PERSIST key                   # remove TTL → key lives forever

TTL key                       # returns seconds until expiry (-1 = no expiry, -2 = key gone)
PTTL key                      # milliseconds precision

Common TTL patterns:
  Cache-aside (15 min):    SET cache:user:1001 {json} EX 900
  Session (30 min, sliding): SET session:token {data} EX 1800
                              + EXPIRE session:token 1800 on every access
  OTP (5 min):             SET otp:user:1001 "482910" EX 300
  Rate limit (1 min window): SET rate:ip:192.168.1.1 0 EX 60 NX
  Daily stats (expire next day): SET stats:20240314 0 EXPIREAT {next_midnight}
```

### 3.5 Day 3 Practice

```
1. Create a daily active user tracker using Bitmap for 10 user IDs.
2. Use BITCOUNT to get number of active users.
3. Use PFADD to track unique page visitors for "day1" and "day2".
4. PFMERGE them and PFCOUNT the weekly uniques.
5. Add 3 events to a Stream with XADD.
6. Create a consumer group and XREADGROUP to consume messages.
```

---

## Day 4 — Redis In Application Code (Java + Spring Boot)

### 4.1 Jedis (Simple Java Client)

```java
// Maven dependency
// <dependency>
//   <groupId>redis.clients</groupId>
//   <artifactId>jedis</artifactId>
//   <version>5.1.0</version>
// </dependency>

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisExample {

    // Connection Pool (use this in production, not single Jedis instance)
    private static JedisPool createPool() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);          // max connections in pool
        config.setMaxIdle(10);           // max idle connections
        config.setMinIdle(5);            // min idle connections
        config.setTestOnBorrow(true);    // test connection before use
        return new JedisPool(config, "localhost", 6379);
    }

    public static void main(String[] args) {
        JedisPool pool = createPool();

        // Always use try-with-resources to return connection to pool
        try (Jedis jedis = pool.getResource()) {

            // String operations
            jedis.set("name", "Vidhan");
            jedis.setex("otp", 300, "482910");    // setex = SET + EX
            String name = jedis.get("name");       // "Vidhan"
            long ttl = jedis.ttl("otp");           // 299

            // Hash
            jedis.hset("user:1001", "name", "Vidhan");
            jedis.hset("user:1001", "city", "Mumbai");
            String city = jedis.hget("user:1001", "city");
            Map<String, String> user = jedis.hgetAll("user:1001");

            // List
            jedis.rpush("tasks", "task1", "task2");
            String task = jedis.lpop("tasks");     // "task1"

            // Set
            jedis.sadd("tags", "java", "redis", "backend");
            boolean hasMember = jedis.sismember("tags", "java");  // true

            // Sorted Set
            jedis.zadd("leaderboard", 9500, "Alice");
            jedis.zadd("leaderboard", 8200, "Bob");
            // Get top-2
            List<String> top2 = jedis.zrevrange("leaderboard", 0, 1);
            Long rank = jedis.zrevrank("leaderboard", "Bob");      // 1

            // Increment counter
            Long count = jedis.incr("page_views");
            Long hits = jedis.incrBy("page_views", 5);

            System.out.println("Name: " + name + ", Top: " + top2 + ", Rank of Bob: " + rank);
        }

        pool.close();
    }
}
```

### 4.2 Lettuce (Async/Reactive Java Client — Production Preferred)

```java
// Maven: io.lettuce:lettuce-core:6.3.0
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.async.RedisAsyncCommands;

public class LettuceExample {
    public static void main(String[] args) throws Exception {
        RedisClient client = RedisClient.create("redis://localhost:6379");
        StatefulRedisConnection<String, String> connection = client.connect();

        // Synchronous API
        RedisCommands<String, String> sync = connection.sync();
        sync.set("key", "value");
        String val = sync.get("key");

        // Asynchronous API (non-blocking)
        RedisAsyncCommands<String, String> async = connection.async();
        async.set("async_key", "async_val")
             .thenAccept(result -> System.out.println("Set result: " + result));

        // Sorted Set
        async.zadd("lb", 9500.0, "Alice")
              .thenCompose(r -> async.zrevrange("lb", 0, 9))
              .thenAccept(top10 -> System.out.println("Top 10: " + top10))
              .toCompletableFuture().get();  // wait for async chain

        connection.close();
        client.shutdown();
    }
}
```

### 4.3 Spring Boot Redis (Most Common In Production)

```java
// application.yml
// spring:
//   data:
//     redis:
//       host: localhost
//       port: 6379
//       timeout: 2000ms
//       lettuce:
//         pool:
//           max-active: 50
//           max-idle: 10

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class CacheService {

    @Autowired
    private StringRedisTemplate redisTemplate;                 // for String → String
    // @Autowired RedisTemplate<String, Object> redisTemplate; // for Object values

    // Cache-aside pattern
    public String getUserProfile(String userId) {
        String cacheKey = "user:" + userId + ":profile";

        // 1. Check cache
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return cached;

        // 2. Cache miss → fetch from DB
        String profile = fetchFromDatabase(userId);

        // 3. Store in cache with 15-minute TTL
        redisTemplate.opsForValue().set(cacheKey, profile, Duration.ofMinutes(15));
        return profile;
    }

    // Rate limiting with Redis
    public boolean isRateLimited(String ip) {
        String key = "rate:" + ip + ":minute:" + (System.currentTimeMillis() / 60000);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(60));  // set TTL on first use
        }
        return count > 100;  // 100 requests per minute limit
    }

    // Sorted Set operations for leaderboard
    public void updateScore(String player, double score) {
        redisTemplate.opsForZSet().add("leaderboard", player, score);
    }

    public Set<String> getTopK(int k) {
        return redisTemplate.opsForZSet().reverseRange("leaderboard", 0, k - 1);
    }

    // Hash operations for shopping cart
    public void addToCart(String userId, String itemId, int qty) {
        redisTemplate.opsForHash().put("cart:" + userId, itemId, String.valueOf(qty));
    }

    private String fetchFromDatabase(String userId) {
        return "{\"id\":\"" + userId + "\",\"name\":\"Vidhan\"}"; // stub
    }
}
```

### 4.4 Pipeline (Batch Commands — Critical For Performance)

```java
// WRONG: 1000 separate round trips = 1000 × network latency
for (int i = 0; i < 1000; i++) {
    jedis.set("key:" + i, "value:" + i);  // 1000 round trips!
}

// RIGHT: Pipeline = 1 round trip for 1000 commands
try (Jedis jedis = pool.getResource()) {
    Pipeline pipeline = jedis.pipelined();
    for (int i = 0; i < 1000; i++) {
        pipeline.set("key:" + i, "value:" + i);
    }
    pipeline.sync();  // single network flush
}
// Result: ~1000× faster for bulk operations.

// Pipeline with responses
try (Jedis jedis = pool.getResource()) {
    Pipeline p = jedis.pipelined();
    Response<String> r1 = p.get("key:1");
    Response<String> r2 = p.get("key:2");
    p.sync();
    System.out.println(r1.get() + " " + r2.get());  // access after sync
}
```

### 4.5 Day 4 Practice

```
Build a simple Java class that:
1. Implements a rate limiter: allow 10 requests/minute per IP using INCR + EXPIRE.
2. Implements a session store: store session_token → user_id with 30min TTL.
3. Implements a leaderboard: ZADD, ZREVRANGE top-5, ZREVRANK for a given player.
```

---

## Day 5 — Transactions, Pub/Sub, and Lua Scripts

### 5.1 Transactions (MULTI / EXEC)

```
Redis transactions: group commands → execute atomically.
No other client's commands can interleave between MULTI and EXEC.
But: Redis does NOT rollback on error (not ACID transactions).

MULTI                        # begin transaction
SET balance 1000
DECRBY balance 200
SET last_txn "transfer_abc"
EXEC                         # execute all atomically → [OK, 800, OK]

# DISCARD cancels the queued commands
MULTI
SET key1 value1
DISCARD                      # cancels — nothing executed

# Error handling:
MULTI
SET valid_key "ok"
NOTACOMMAND                  # syntax error — queued but will fail
SET another_key "ok"
EXEC
# → [OK, error, OK] — Redis executes what it can, partial success.
# This is NOT like SQL ROLLBACK — other commands still execute.

# WATCH (Optimistic Locking):
# Implements check-and-set (CAS) pattern.
WATCH balance
balance_val = GET balance    # read current value
# If balance changes between WATCH and EXEC → EXEC returns nil (abort)
MULTI
DECRBY balance 100
EXEC   # → nil if balance was modified → retry logic needed in application

Java example:
  jedis.watch("balance");
  String val = jedis.get("balance");
  int balance = Integer.parseInt(val);
  if (balance >= 100) {
      Transaction tx = jedis.multi();
      tx.decrBy("balance", 100);
      List<Object> results = tx.exec();
      if (results == null) {
          // conflict detected → retry
      }
  } else {
      jedis.unwatch();
  }
```

### 5.2 Pub/Sub (Publish / Subscribe)

```
Redis Pub/Sub: fire-and-forget messaging.
Publisher sends message to a channel. Subscribers receive it.
No message persistence — if subscriber is offline, message is lost.
Use Redis Streams instead if persistence is needed.

# Terminal 1 (Subscriber)
SUBSCRIBE news:sports news:tech      # subscribe to channels
# → Waiting for messages...

# Terminal 2 (Publisher)
PUBLISH news:sports "India wins!"
PUBLISH news:tech "Redis 8 released"

# Terminal 1 receives:
# 1) "message"
# 2) "news:sports"
# 3) "India wins!"

# Pattern subscribe (wildcard)
PSUBSCRIBE news:*        # subscribes to all channels matching pattern
PSUBSCRIBE user:*:events # user:1001:events, user:2002:events, etc.

Java example (Jedis):
  // Subscriber (runs in separate thread)
  class MySubscriber extends JedisPubSub {
      @Override
      public void onMessage(String channel, String message) {
          System.out.println("Channel: " + channel + " Message: " + message);
      }
  }
  new Thread(() -> {
      try (Jedis jedis = pool.getResource()) {
          jedis.subscribe(new MySubscriber(), "news:sports", "news:tech");
      }
  }).start();

  // Publisher
  try (Jedis jedis = pool.getResource()) {
      jedis.publish("news:sports", "India wins!");
  }

Use cases:
  → Real-time notifications (not critical — fire and forget)
  → Chat rooms (basic, no history)
  → Cache invalidation signal: PUBLISH cache_invalidate "user:1001"
  → Presence updates: "user X came online"
```

### 5.3 Lua Scripting (Atomic Complex Operations)

```
Lua scripts run atomically inside Redis — no other command executes while Lua runs.
Use when: you need multi-step logic with no race conditions.

# EVAL syntax:
# EVAL script numkeys key1 key2... arg1 arg2...

# Example: Atomic rate limiter
# Script: if count < limit → increment and return 1 (allowed); else return 0 (blocked)
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local count = redis.call('INCR', key)
if count == 1 then
    redis.call('EXPIRE', key, window)
end
if count <= limit then
    return 1   -- allowed
else
    return 0   -- blocked
end

# Run in CLI:
EVAL "local count = redis.call('INCR', KEYS[1])\nif count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\nreturn count" 1 rate:user:123 60

# Store script for reuse (SCRIPT LOAD returns SHA hash)
SHA=$(redis-cli SCRIPT LOAD "return redis.call('GET', KEYS[1])")
redis-cli EVALSHA $SHA 1 mykey    # run by SHA (no resend of script text)

Java example:
  String script = "local c = redis.call('INCR', KEYS[1])\n" +
                  "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
                  "return c";
  Object result = jedis.eval(script, 1, "rate:user:456", "60");
  long count = Long.parseLong(result.toString());
  boolean allowed = count <= 100;
```

### 5.4 Day 5 Practice

```
1. Implement distributed lock using SET NX EX (atomic).
   - Acquire: SET lock:resource uuid NX EX 30
   - Release (Lua): if GET lock == uuid → DEL lock (atomic check-and-delete)
2. Implement optimistic counter with WATCH + MULTI + EXEC.
3. Create a simple chat: PUBLISH messages, SUBSCRIBE in another thread.
```

---

## Day 6 — Persistence, Replication, And Cluster

### 6.1 Persistence — How Redis Saves Data To Disk

**Without persistence: Redis is a pure cache. All data lost on restart.**

#### RDB (Redis Database Snapshot)

```
Saves a binary snapshot of ALL data at configured intervals.

redis.conf configuration:
  save 3600 1     # save if at least 1 key changed in last 60 min
  save 300 100    # save if at least 100 keys changed in last 5 min
  save 60 10000   # save if at least 10000 keys changed in last 1 min

How it works:
  1. Redis calls fork() → child process inherits parent's memory.
  2. Child writes full snapshot to dump.rdb file.
  3. Parent continues serving requests (copy-on-write → minimal overhead).
  4. On next restart: Redis loads dump.rdb → restores all data.

Manual trigger:
  BGSAVE    # background save (non-blocking, uses fork)
  SAVE      # foreground save (BLOCKS all commands — use only in maintenance)

Pros: Compact file, fast restart (binary format), minimal runtime overhead.
Cons: Data loss = everything since last snapshot (minutes to hours of data).
Use when: Cache data (loss tolerable), data can be rebuilt from source.
```

#### AOF (Append-Only File)

```
Logs every write command to a file. On restart: replay all commands → rebuild state.

redis.conf:
  appendonly yes
  appendfilename "appendonly.aof"
  appendfsync everysec    # fsync every second (recommended: 1s max data loss)
  # appendfsync always    # fsync every write (0 data loss, very slow)
  # appendfsync no        # OS decides when to flush (fastest, up to 30s loss)

AOF rewrite (compaction):
  Over time AOF grows. Rewrite replaces history with minimal current state.
  BGREWRITEAOF    # trigger AOF rewrite (forks child to compact)
  auto-aof-rewrite-percentage 100   # rewrite when AOF is 100% bigger than last rewrite
  auto-aof-rewrite-min-size 64mb    # only rewrite if AOF > 64MB

Pros: Durable (max 1s data loss with everysec), human-readable commands, appendable.
Cons: Larger file than RDB, slower restart (replay all commands).
Use when: Financial data, session state, anything where < 1s loss matters.

Best practice: Use BOTH RDB + AOF together.
  RDB for fast restarts (load binary snapshot first).
  AOF for durability (replay only commands since last RDB).
```

### 6.2 Replication (Leader-Follower)

```
One primary (master) + N replicas (slaves/replicas).
All writes go to primary. Replicas copy from primary asynchronously.

redis.conf on replica:
  replicaof 192.168.1.10 6379   # point to primary

Or via command:
  REPLICAOF 192.168.1.10 6379

How sync works:
  1. Replica connects to primary.
  2. Primary runs BGSAVE → sends RDB snapshot (full sync).
  3. Replica loads RDB.
  4. Primary sends accumulated write commands since sync started.
  5. Thereafter: streaming replication (primary sends every write command to replica).

Inspect replication status:
  INFO replication
  # On primary: role:master, connected_slaves:2, slave0:ip=...,offset=123456
  # On replica: role:slave, master_host:192.168.1.10, master_repl_offset:123456

Read scaling:
  → Route read-heavy traffic to replicas.
  → Primary handles all writes.
  → Replicas are eventually consistent (replication lag typically < 50ms).

Failover (manual):
  If primary dies:
    REPLICAOF NO ONE    # on replica → promote it to primary
  (Automatic failover requires Redis Sentinel or Redis Cluster.)
```

### 6.3 Redis Sentinel (High Availability)

```
3+ Sentinel processes monitor primary + replicas.
If primary fails: Sentinels elect new primary (quorum vote).
Client connects to Sentinel → Sentinel returns current primary IP.

Sentinel config (sentinel.conf):
  sentinel monitor mymaster 192.168.1.10 6379 2   # "2" = quorum
  sentinel down-after-milliseconds mymaster 5000   # failover after 5s no response
  sentinel failover-timeout mymaster 60000         # 60s to complete failover

Sentinel ports: 26379 by default.

Java connection with Sentinel:
  Set<String> sentinels = new HashSet<>();
  sentinels.add("192.168.1.11:26379");
  sentinels.add("192.168.1.12:26379");
  sentinels.add("192.168.1.13:26379");
  JedisSentinelPool pool = new JedisSentinelPool("mymaster", sentinels);
  try (Jedis jedis = pool.getResource()) {
      jedis.set("key", "value");
  }
```

### 6.4 Redis Cluster

```
Sharding + High Availability built-in.
Data automatically distributed across multiple primary nodes.

Cluster uses 16,384 hash slots:
  hash_slot = CRC16(key) % 16384
  
Slot assignment example (3 primary nodes):
  Node A: slots 0–5460
  Node B: slots 5461–10922
  Node C: slots 10923–16383

Each primary has 1+ replicas for HA.
Minimum viable cluster: 3 primaries + 3 replicas = 6 nodes.

Create cluster:
  redis-cli --cluster create \
    192.168.1.1:6379 192.168.1.2:6379 192.168.1.3:6379 \
    192.168.1.4:6379 192.168.1.5:6379 192.168.1.6:6379 \
    --cluster-replicas 1

Hash tags (keep related keys on same shard):
  user:{1001}:profile     → same slot as user:{1001}:cart
  topk:{global}:24h       → same slot as topk:{global}:7d
  { } braces define the part used for slot calculation.
  Both keys above use {1001} → same slot → MULTI/EXEC works across them.

Cluster commands:
  CLUSTER INFO                   # cluster status
  CLUSTER NODES                  # all nodes and their slots
  CLUSTER KEYSLOT mykey          # which slot does this key belong to?

Java (Jedis Cluster):
  Set<HostAndPort> nodes = new HashSet<>();
  nodes.add(new HostAndPort("192.168.1.1", 6379));
  nodes.add(new HostAndPort("192.168.1.2", 6379));
  // ... add all nodes
  JedisCluster cluster = new JedisCluster(nodes);
  cluster.set("user:{1001}:profile", "data");
  cluster.get("user:{1001}:profile");
```

### 6.5 Day 6 Practice

```
1. Configure AOF persistence and restart Redis. Verify data survives restart.
2. Set up primary → replica replication locally (two Docker containers).
   docker run -d --name redis-primary -p 6379:6379 redis:7.2
   docker run -d --name redis-replica -p 6380:6379 redis:7.2 redis-server --replicaof host.docker.internal 6379
3. Write to primary, read from replica.
4. INFO replication on both.
```

---

## Day 7 — Production Patterns + Interview Preparation

### 7.1 Common Production Design Patterns

#### Pattern 1: Cache-Aside (Lazy Loading)

```
Read: Check Redis → found? return. Not found? Read DB → write to Redis → return.
Write: Update DB → DELETE Redis key (invalidate; don't update cache — avoids race).

Java:
  public User getUser(String id) {
      String cached = redis.get("user:" + id);
      if (cached != null) return deserialize(cached);  // Cache HIT
      User user = userDB.findById(id);                  // Cache MISS → DB
      redis.setex("user:" + id, 3600, serialize(user)); // populate cache
      return user;
  }
  
  public void updateUser(String id, User user) {
      userDB.save(user);              // update DB first (source of truth)
      redis.del("user:" + id);        // invalidate cache (not update!)
  }

Why delete instead of update on write?
  Race condition risk: if two writers update simultaneously and update cache,
  the slower writer overwrites the faster writer's cache value.
  Deletion is safe: next read will refresh from DB.
```

#### Pattern 2: Write-Through

```
Write to Redis AND DB synchronously before returning to client.
Cache always has fresh data; no stale reads.

update(key, value):
  DB.update(key, value)               // write to DB
  redis.set(key, value, TTL)          // write to Redis simultaneously

Downside: Every write is slower (both DB + Redis write on critical path).
Use when: Read-heavy, low write volume, freshness critical.
```

#### Pattern 3: Distributed Lock (Redlock)

```
Atomic acquire lock:
  SET lock:resource unique_token NX PX 30000  (NX=only if not exists, PX=30s TTL)
  → Returns OK (acquired) or nil (already locked)

Atomic release lock (MUST use Lua):
  if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
  else
    return 0
  end
  // Prerequisite: only the lock owner (with its unique_token) can release.
  // Without this: Thread A acquires; lock expires; Thread B acquires; Thread A releases B's lock!

TTL purpose:
  Lock holder dies without releasing → lock auto-expires → no deadlock.
  TTL must be >> expected critical section duration.

Redlock (multi-node distributed lock):
  Acquire from majority of N Redis instances (N=5, need 3 successful).
  Prevents split-brain (single Redis node goes down).
```

#### Pattern 4: Rate Limiting

```
Strategy 1: Fixed Window Counter
  INCR rate:user:1001:minute:1234   # key = user + minute bucket
  EXPIRE rate:user:1001:minute:1234 60
  if count > 100: reject

Strategy 2: Sliding Window Log (accurate but expensive)
  ZADD rate:user:1001 timestamp timestamp   # score = timestamp, member = timestamp
  ZREMRANGEBYSCORE rate:user:1001 0 (now-60000)  # remove older than 60s
  count = ZCARD rate:user:1001
  EXPIRE rate:user:1001 60
  if count > 100: reject

Strategy 3: Token Bucket (best for burst tolerance — Lua script)
  Script runs atomically:
    capacity = 100, refill_rate = 10 tokens/sec
    On each request:
      tokens = GET bucket:user:1001 (or capacity if first time)
      time_since_refill = now - last_refill_time
      tokens = min(capacity, tokens + time_since_refill × refill_rate)
      if tokens >= 1: tokens -= 1; return ALLOWED
      else: return BLOCKED
```

#### Pattern 5: Session Store

```
User logs in → generate session_id → store in Redis:
  HSET session:abc123 user_id 1001 role admin device iPhone TTL...
  EXPIRE session:abc123 1800   # 30 min

On each request:
  session = HGETALL session:token_from_cookie
  if session empty: redirect to login
  EXPIRE session:token_from_cookie 1800  # sliding expiry (reset on activity)

Sticky sessions NOT needed: any app server reads the same session from Redis.
→ Enables truly stateless app servers → horizontal scaling without session affinity.
```

### 7.2 Redis Memory Optimization

```
1. Use appropriate encoding:
   Small hashes (≤128 fields, ≤64 bytes/field) → ziplist (compact).
   Large hashes → hashtable (fast).
   Redis chooses automatically via:
     hash-max-ziplist-entries 128
     hash-max-ziplist-value 64

2. Key naming: shorter keys = less memory.
   "user:1001:session:token" (26 chars) vs "u:1001:s:t" (10 chars)
   For millions of keys: 16 bytes × 1M = 16 MB savings.

3. Expire aggressively: every orphaned key wastes RAM.
   Set TTL on EVERY cache key. No exceptions.

4. Key count analysis:
   INFO keyspace          # see DB count per logical DB
   DBSIZE                 # total key count
   MEMORY USAGE mykey     # bytes consumed by specific key (including metadata)
   MEMORY DOCTOR          # Redis self-diagnosis of memory issues

5. Compression at application layer:
   store: redis.set(key, gzip(json_string))
   retrieve: gunzip(redis.get(key))
   For large values (> 1 KB): typically 70% size reduction.
```

### 7.3 Redis Anti-Patterns (What NOT To Do)

```
1. KEYS in production — NEVER
   KEYS user:* scans all keys → blocks Redis until complete.
   At 10M keys: takes seconds → entire system freezes.
   Use SCAN 0 MATCH user:* COUNT 100 instead (cursor-based, non-blocking).

2. SELECT (multiple databases) — Avoid
   Redis has 16 logical DBs (SELECT 0 through SELECT 15).
   They share the same memory/CPU. False separation — use different Redis instances
   or key namespace prefixes instead.

3. FLUSHDB / FLUSHALL in production — Never without ASYNC
   FLUSHALL → BLOCKING delete of all keys → system freezes.
   FLUSHALL ASYNC → non-blocking (background delete). Always use ASYNC.

4. Storing huge values
   SET blob:1 {100MB_json} → single key blocks Redis during read/write.
   Split into chunks: blob:1:chunk:0, blob:1:chunk:1, etc.
   Or: store big data in S3/CDN, store only URL/metadata in Redis.

5. Using Redis as primary database (without persistence)
   Redis is a cache. Source of truth must be in PostgreSQL/MySQL etc.
   Exception: use Redis as primary only with AOF+RDB and careful backup strategy.

6. Not setting TTL on cache keys
   Memory fills up. LRU eviction kicks in. Unpredictable key removal.
   Always SET with EX or call EXPIRE on every cache key.
```

### 7.4 Interview Cheat Sheet

```
"How does Redis achieve O(1) for GET/SET?"
  → Hash table for key lookup. Key → pointer to value → O(1) average.

"How does Sorted Set ZRANK work? What's its complexity?"
  → Skip list internally. ZRANK = O(log N). Skip list allows
    ordered traversal with O(log N) rank queries (like a balanced BST but simpler).

"Why is Redis single-threaded? Is it slow?"
  → Single thread for command execution → no lock contention → predictable latency.
    Network I/O is multi-threaded (Redis 6+). Single thread processes 1M+ ops/sec
    because commands are in-memory — CPU is rarely the bottleneck, network is.

"How do you prevent cache stampede (thundering herd)?"
  → Option A: Probabilistic early expiry (recompute while still fresh, small % chance).
  → Option B: Lock: first request acquires lock, computes, sets cache; others wait.
  → Option C: Background refresh: async thread refreshes cache before expiry.

"Explain Redlock."
  → Acquire lock on majority (N/2+1) of independent Redis nodes.
    Prevents single-node SPOF. Lock valid only if acquired within < TTL/2 time.
    Release by deleting from all nodes.

"When would you use Redis Streams vs Pub/Sub vs Kafka?"
  → Pub/Sub: fire-and-forget, no persistence, < 1000 msgs/sec, same datacenter.
  → Redis Streams: persistent, consumer groups, replay, within Redis ecosystem.
  → Kafka: 1M+ msgs/sec, durable, multi-datacenter, days of retention.

"How do you implement exactly-once in Redis?"
  → Lua script: check if event_id exists → skip if yes → process → SET event_id EX 3600.
    All in one atomic Lua script: no race between check and set.
```

### 7.5 Day 7 Final Project: Build A URL Shortener

```java
// Implements: URL shortening service backed by Redis
// Features: create short URL, redirect, track click count, expiry

@Service
public class UrlShortenerService {

    @Autowired StringRedisTemplate redis;

    // POST /shorten { "url": "https://example.com/very/long/path", "ttl_days": 7 }
    public String shorten(String originalUrl, int ttlDays) {
        String shortCode = generateShortCode();         // 6-char Base62 random code
        String key = "url:" + shortCode;

        redis.opsForHash().put(key, "original_url", originalUrl);
        redis.opsForHash().put(key, "clicks", "0");
        redis.opsForHash().put(key, "created_at", String.valueOf(System.currentTimeMillis()));
        redis.expire(key, Duration.ofDays(ttlDays));    // auto-expire after N days

        return "https://short.ly/" + shortCode;
    }

    // GET /{shortCode} → redirect to original URL
    public String redirect(String shortCode) {
        String key = "url:" + shortCode;
        String originalUrl = (String) redis.opsForHash().get(key, "original_url");
        if (originalUrl == null) throw new NotFoundException("Short code expired or invalid");

        // Increment click count atomically
        redis.opsForHash().increment(key, "clicks", 1);
        return originalUrl;
    }

    // GET /stats/{shortCode}
    public Map<Object, Object> getStats(String shortCode) {
        return redis.opsForHash().entries("url:" + shortCode);
    }

    private String generateShortCode() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(chars.charAt((int)(Math.random() * chars.length())));
        return sb.toString();
    }
}
```

---

## Summary: 7-Day Roadmap

| Day | Topics | Key Commands |
|---|---|---|
| 1 | What Redis is, String type, TTL basics | SET, GET, INCR, TTL, EXPIRE, SCAN |
| 2 | List, Hash, Set, Sorted Set | LPUSH/RPUSH, HSET/HGETALL, SADD/SINTER, ZADD/ZREVRANGE |
| 3 | Bitmap, HyperLogLog, Streams, Expiry patterns | SETBIT, PFADD, XADD, XREADGROUP |
| 4 | Java integration, Jedis, Lettuce, Spring Boot, Pipeline | JedisPool, RedisTemplate, pipelining |
| 5 | Transactions, Pub/Sub, Lua scripting | MULTI/EXEC, WATCH, PUBLISH/SUBSCRIBE, EVAL |
| 6 | Persistence (RDB/AOF), Replication, Sentinel, Cluster | BGSAVE, BGREWRITEAOF, REPLICAOF, CLUSTER |
| 7 | Design patterns, Anti-patterns, Interview prep | Distributed lock, Rate limiter, Cache-aside, URL shortener project |

---

## Resources

- **Official Docs**: https://redis.io/docs/
- **Interactive Tutorial**: https://try.redis.io/ (browser-based CLI)
- **Redis University (Free)**: https://university.redis.com/
- **Book**: "Redis in Action" by Josiah Carlson
- **Java Client Docs**: Jedis (https://github.com/redis/jedis), Lettuce (https://lettuce.io/)
- **GUI Tool**: RedisInsight (https://redis.com/redis-enterprise/redis-insight/)
