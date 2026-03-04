# 🚦 High-Level Design (HLD) — Rate Limiter System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Algorithms (Token Bucket, Sliding Window), Distributed Rate Limiting, Redis, Consistency, Failure Handling

---

## 1. Requirements

### 1.1 Functional Requirements
- Limit the number of requests a **client (user / IP / API key)** can make to an API within a time window.
- Support **multiple rate limit rules** per client (e.g., 100 req/min AND 1000 req/hour).
- Support **different limits per API endpoint** (e.g., `/login` → 5/min, `/search` → 200/min).
- Support **global limits** (across all clients) and **per-client limits**.
- Return `429 Too Many Requests` with `Retry-After` header when limit is breached.
- Rules are **configurable dynamically** without service restart.
- Support **whitelisting** (internal services bypass rate limits).
- Rate limiter must work in a **distributed** environment (multiple servers behind a load balancer).

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Latency overhead** | < 5 ms per request (rate limiter check must be fast) |
| **Availability** | 99.99% — rate limiter failure should fail open (not block all traffic) |
| **Accuracy** | Allow at most X% burst above limit (< 1% overage acceptable) |
| **Throughput** | Handle 1M+ requests/sec across all clients |
| **Scalability** | Horizontally scalable; no single point of failure |
| **Consistency** | Near-real-time across distributed nodes (eventual consistency acceptable) |

### 1.3 Out of Scope
- Circuit breaker / retry logic (client responsibility)
- Billing based on API usage
- DDoS protection (separate layer — WAF/CDN)

---

## 2. Capacity Estimation

```
Total API requests        = 1 billion/day → ~12,000 req/sec avg, ~1M req/sec peak
Clients (users/API keys)  = 100 million
Rate limit rules          = ~500 distinct rules (endpoint × client tier combinations)
Storage per client        = ~100 bytes (counters + timestamps)
Total counter storage     = 100M × 100B = ~10 GB (fits comfortably in Redis)
Redis ops/sec             = 1M req/sec → 1M Redis calls/sec (1 INCR per request)
```

---

## 3. High-Level Architecture

```
 Client Request
      │
      ▼
 ┌──────────────┐
 │  API Gateway │  (or middleware in each service)
 │              │
 │  Rate Limiter│◀── Rule Config Service (dynamic rules)
 │  Middleware  │
 └──────┬───────┘
        │
  ┌─────▼──────────────────────┐
  │   Rate Limiter Service      │  (stateless, horizontally scaled)
  │                             │
  │  1. Identify client key     │
  │  2. Fetch applicable rules  │
  │  3. Check counter in Redis  │
  │  4. Allow / Deny            │
  └─────┬───────────────────────┘
        │
  ┌─────▼──────────────────┐
  │   Redis Cluster         │  (distributed counters — source of truth)
  │   (rate limit counters) │
  └─────────────────────────┘
        │
  ┌─────▼──────────────────┐
  │   Rule Config Store     │  (MySQL + local cache)
  │   (rules per endpoint   │
  │    per client tier)     │
  └─────────────────────────┘
        │
  ┌─────▼──────────────────┐
  │   Monitoring / Alerts   │  (Prometheus, throttle dashboards)
  └─────────────────────────┘
```

---

## 4. Rate Limiting Algorithms

This is the **core of the interview discussion**. Know all 4 algorithms, their trade-offs, and when to use each.

---

### 4.1 Token Bucket ⭐ (Most Common / Recommended)

```
Bucket holds max N tokens.
Tokens refill at rate R tokens/sec.
Each request consumes 1 token.
If bucket is empty → reject request (429).

         Refill: R tokens/sec
              │
              ▼
  ┌─────────────────────────┐
  │  ● ● ● ● ●  (5 tokens) │  ← bucket (capacity = 10)
  └─────────────────────────┘
       │
  Request arrives → remove 1 token → allow
  No tokens → reject
```

**Properties:**
- Allows **bursting** up to N requests at once (bucket fills up during idle time).
- Smooth average rate enforced.
- **Best for:** APIs where short bursts are acceptable (most common use case).

**Redis Implementation:**
```
Key:   ratelimit:{clientId}:{endpoint}
Fields: tokens (current count), last_refill (timestamp)

Algorithm (Lua script — atomic):
  now = current_time_ms
  elapsed = now - last_refill
  tokens = min(capacity, tokens + elapsed × refill_rate)
  last_refill = now
  if tokens >= 1:
      tokens -= 1
      return ALLOW
  else:
      return DENY
```

---

### 4.2 Leaky Bucket

```
Requests enter a queue (bucket).
Queue drains at a fixed rate (leak rate).
If queue is full → reject (overflow).

  Requests ──▶ [ ● ● ● ● ● ] ──▶ process at fixed rate R
                  (queue)
```

**Properties:**
- Output rate is **always constant** — requests are smoothed.
- No burst allowed.
- **Best for:** Downstream systems that can't handle bursts (e.g., legacy APIs, payment gateways).
- **Downside:** Requests pile up in queue; added latency.

---

### 4.3 Fixed Window Counter

```
Divide time into fixed windows (e.g., each minute).
Count requests per client per window.
If count > limit → reject.

  Window 1 [00:00–01:00]: count = 98/100 → allow
  Window 2 [01:00–02:00]: count = 0/100 → allow
  
  Problem: Boundary burst:
  99 requests at 00:59 + 99 requests at 01:01 = 198 in 2 sec!
```

**Properties:**
- Simple Redis INCR + EXPIRE.
- **Boundary burst problem** — doubles effective rate at window boundaries.
- **Best for:** Simple use cases where boundary bursting is acceptable.

**Redis:**
```
Key:   ratelimit:{clientId}:{window_start}
cmd:   INCR key → count
       EXPIRE key 60 (if first request in window)
       if count > limit → DENY
```

---

### 4.4 Sliding Window Log

```
Store timestamp of every request in a sorted set.
On each request:
  1. Remove entries older than windowSize from ZSET
  2. Count remaining entries
  3. If count < limit → add current timestamp → ALLOW
  4. Else → DENY

Redis ZSET:
  Key:   ratelimit:{clientId}
  Score: timestamp (ms)
  Value: requestId
```

**Properties:**
- Most **accurate** — no boundary burst problem.
- High **memory usage** — stores every request timestamp.
- **Best for:** Low-volume, high-accuracy requirements (e.g., financial APIs).

---

### 4.5 Sliding Window Counter ⭐ (Best Balance)

Hybrid of Fixed Window + Sliding window. Approximates sliding window using two fixed windows.

```
Rate = prev_window_count × overlap_ratio + curr_window_count

Example (limit = 100/min):
  prev window [00:00–01:00]: 80 requests
  curr window [01:00–02:00]: 30 requests
  Current time: 01:15 (25% into curr window → 75% overlap with prev)

  Rate ≈ 80 × 0.75 + 30 = 90 → ALLOW (< 100)
```

**Properties:**
- Memory efficient (only 2 counters per client).
- Very accurate (< 1% error rate in practice).
- **Best for:** Most production distributed rate limiters.

---

## 5. Identifying the Client Key

```java
String buildRateLimitKey(HttpRequest request) {
    // Priority order:
    if (request.hasApiKey())    return "api:" + request.getApiKey();
    if (request.isAuthorized()) return "user:" + request.getUserId();
    return "ip:" + request.getClientIP();
}
```

**Key dimensions for rate limiting:**

| Dimension | Example Key | Use Case |
|---|---|---|
| Per User | `user:{userId}` | Authenticated API limits |
| Per API Key | `api:{apiKey}` | Developer/business tier limits |
| Per IP | `ip:{ipAddr}` | Unauthenticated / public endpoints |
| Per Endpoint | `user:{userId}:endpoint:{path}` | Endpoint-specific limits |
| Global | `global:{endpoint}` | Total traffic cap on an endpoint |

---

## 6. Rule Configuration Service

```sql
CREATE TABLE rate_limit_rules (
    rule_id       UUID PRIMARY KEY,
    client_tier   ENUM('FREE','PRO','ENTERPRISE','INTERNAL'),
    endpoint      VARCHAR(200),      -- NULL means applies to all
    limit_count   INT,
    window_sec    INT,
    algorithm     ENUM('TOKEN_BUCKET','SLIDING_WINDOW_COUNTER','FIXED_WINDOW'),
    burst_size    INT,               -- for token bucket
    is_active     BOOLEAN
);
```

**Rules cached locally** in each Rate Limiter Service instance (TTL: 60 sec). Changes propagate within 1 minute — acceptable for rule updates.

**Priority resolution (most specific wins):**
```
1. User + Endpoint specific rule
2. API Key + Endpoint rule
3. IP + Endpoint rule
4. Tier-level rule (FREE / PRO / ENTERPRISE)
5. Global default rule
```

---

## 7. Distributed Rate Limiting Design

**The challenge:** 10 Rate Limiter nodes each have their own memory. A client hitting different nodes could exceed the limit N× times.

**Solution: Redis as centralized counter store**

```
Node 1 ──▶ Redis INCR ──▶ global counter
Node 2 ──▶ Redis INCR ──▶ same counter
Node 3 ──▶ Redis INCR ──▶ same counter

All nodes share the same Redis counter → consistent global rate limiting
```

**Lua Script for atomicity (Sliding Window Counter):**
```lua
-- atomic: compare + increment in one Redis round trip
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- Remove old window entries
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- Count current window
local count = redis.call('ZCARD', key)

if count < limit then
    redis.call('ZADD', key, now, now)
    redis.call('EXPIRE', key, window / 1000)
    return 1  -- ALLOW
else
    return 0  -- DENY
end
```

**Why Lua script?** Atomic execution on Redis — no race condition between COUNT and INCR.

---

## 8. Data Flow — Request Processing

```
Client ──▶ GET /api/search

API Gateway / Middleware
        │
        ▼
  Build key: "user:U123:search"
        │
        ▼
  Fetch rule: 200 req/min (Sliding Window Counter)
  (from local cache → Redis config cache → MySQL)
        │
        ▼
  Execute Lua script on Redis:
    prev_count = HGET ratelimit:U123:search:prev_window
    curr_count = HGET ratelimit:U123:search:curr_window
    rate = prev × overlap + curr
        │
  ┌─────┴──────────────────────────┐
  │ rate < 200           rate ≥ 200│
  ▼                                ▼
ALLOW                        DENY (429)
  │                          Set Retry-After header:
INCR curr_count              retry_after = window_end - now
  │
Forward to upstream service
```

---

## 9. Response Headers

Always return informative rate limit headers:

```
HTTP/1.1 200 OK
X-RateLimit-Limit:     200
X-RateLimit-Remaining: 143
X-RateLimit-Reset:     1741201800   (unix timestamp of window reset)

HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit:     200
X-RateLimit-Remaining: 0
X-RateLimit-Reset:     1741201860
Retry-After:           47           (seconds until retry)
```

---

## 10. Failure Handling

| Failure | Strategy |
|---|---|
| **Redis unreachable** | **Fail open** — allow all requests (rate limiter unavailable; don't block traffic) |
| **Redis timeout** | Short timeout (< 2ms); fail open if exceeded |
| **Redis partial failure (one node)** | Redis Cluster auto-routes to replica |
| **Rate Limiter Service crash** | Load balancer routes to healthy nodes |
| **Rule config DB down** | Serve stale rules from local cache |

> **Fail open vs Fail closed:**  
> For rate limiters — **fail open** is preferred. It's better to let some excess traffic through than to block all legitimate traffic when the rate limiter has issues.

---

## 11. Optimizations

### 11.1 Local Cache (Reduce Redis Calls)
For very high-traffic clients, maintain a **local approximate counter** in the service node memory:
- Sync with Redis every 100ms (batched INCR).
- Allows 10M+ req/sec without Redis bottleneck.
- Trade-off: slight over-allowance during sync interval.

### 11.2 Redis Pipeline / Batch
- Batch multiple client checks into a single Redis pipeline round trip.
- Reduces network overhead from N round trips to 1.

### 11.3 Sliding Window Counter vs Log
- Use **Sliding Window Counter** for most endpoints (2 counters, O(1) memory).
- Reserve **Sliding Window Log** only for low-volume, high-accuracy endpoints.

---

## 12. Redis Data Structure Summary

| Algorithm | Redis Structure | Memory Per Client |
|---|---|---|
| Token Bucket | HASH (tokens, last_refill) | ~50 bytes |
| Fixed Window | STRING (counter) | ~20 bytes |
| Sliding Window Log | ZSET (timestamps) | ~100B × req count |
| Sliding Window Counter | 2× STRING (prev + curr count) | ~40 bytes |

---

## 13. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Algorithm** | Sliding Window Counter | Best accuracy/memory balance; < 1% error |
| **Storage** | Redis Cluster | Fast atomic ops; single source of truth |
| **Atomicity** | Lua scripts | No race conditions; single round trip |
| **Failure mode** | Fail open | Traffic not blocked; risk of slight over-limit |
| **Rule config** | Local cache (60s TTL) | Low latency; rules update within 1 min |
| **Client key** | API key > userId > IP | Most granular identification first |
| **Distributed sync** | Centralized Redis | Consistent limits; Redis is the bottleneck |

---

## 14. Where to Place the Rate Limiter

```
Option A: API Gateway (Centralized)
  ✅ Single enforcement point
  ✅ Language-agnostic
  ❌ Gateway becomes bottleneck; all services coupled to it

Option B: Middleware in each service (Distributed)
  ✅ No single bottleneck
  ✅ Service-specific rules
  ❌ Code duplication; harder to update consistently

Option C: Sidecar Proxy (Best for microservices)
  ✅ Decoupled from service code
  ✅ Independently deployable (like Envoy sidecar)
  ❌ Infrastructure complexity

Recommended: API Gateway for external traffic + Sidecar for inter-service limits.
```

---

## 15. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Throttle rate** | % of requests returning 429; spike = abuse or misconfigured limit |
| **Redis latency** | P99 response time for INCR ops; alert if > 3ms |
| **Rule cache miss rate** | Frequency of fallback to DB for rule lookup |
| **Client blacklist requests** | Clients consistently at 100% throttle → candidate for blocking |
| **False positive rate** | Legitimate requests throttled — monitor via client complaints |

---

## 16. Future Enhancements
- **Adaptive Rate Limiting** — dynamically adjust limits based on server load (auto-throttle during high CPU).
- **Client Reputation Score** — penalize clients with history of abuse with lower limits.
- **Geolocation-based limits** — different limits per region (GDPR, regional traffic control).
- **Cost-based limiting** — limit by compute cost of requests, not just count (expensive ML endpoints throttled differently).

---

*Document prepared for SDE 3 system design interviews. Focus areas: rate limiting algorithms (Token Bucket vs Sliding Window), distributed counters with Redis, atomic Lua scripts, failure modes (fail open), and placement strategy.*
