# 🔗 High-Level Design (HLD) — URL Shortener System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Unique ID Generation, Hashing, Read-heavy Caching, Scalability, Analytics, Encoding Schemes

---

## 1. Requirements

### 1.1 Functional Requirements
- Given a **long URL**, generate a unique **short URL** (e.g., `short.ly/aB3xYz`).
- Given a short URL, **redirect** to the original long URL (HTTP 301 / 302).
- Short URLs should be **7 characters** (alphanumeric: a–z, A–Z, 0–9 → 62⁷ ≈ 3.5 trillion combinations).
- Support **custom short URLs** (user-defined alias, e.g., `short.ly/my-blog`).
- Short URLs **expire** after a configurable TTL (default: 2 years; custom on creation).
- Track **analytics** per short URL: total clicks, unique visitors, referrer, geo, device.
- Support **user accounts** — users can manage their short URLs (create, delete, view stats).
- **Duplicate long URLs** — same long URL by same user returns existing short URL.

### 1.2 Non-Functional Requirements
| Property               | Target                                                   |
| ---------------------- | -------------------------------------------------------- |
| **Read Latency**       | < 10 ms P99 (redirect must be instant)                   |
| **Write Latency**      | < 100 ms P99 (URL creation)                              |
| **Availability**       | 99.99% — redirect must always work                       |
| **Scale**              | 100M URLs created/day; 10B redirects/day                 |
| **Read : Write Ratio** | 100 : 1 (extremely read-heavy)                           |
| **Uniqueness**         | Zero collision on short codes                            |
| **Data Retention**     | URLs stored for TTL duration; analytics retained 2 years |

### 1.3 Out of Scope
- Link preview / metadata scraping
- Browser extension
- QR code generation (future enhancement)

---

## 2. Capacity Estimation

```
URL Writes/day           = 100 million  → ~1,200 writes/sec avg, ~10K writes/sec peak
URL Reads (redirects)/day = 10 billion  → ~115K reads/sec avg, ~500K reads/sec peak
URL record size          = ~500 bytes (longURL + shortCode + metadata)
Storage/day              = 100M × 500B = ~50 GB/day
Storage/5 years          = 50 GB/day × 365 × 5 ≈ ~90 TB
Short code space         = 62^7 ≈ 3.5 trillion → sufficient for decades
Cache size (hot URLs)    = 20% of URLs = 80% of traffic → cache 20M URLs
                         = 20M × 500B = ~10 GB (fits easily in Redis)
```

---

## 3. High-Level Architecture

```
 Client (Browser / API)
        │
        ├─── POST /shorten ──────────────────────────────────▶┐
        │                                                      │
        └─── GET  /aB3xYz ──▶ CDN / Load Balancer             │
                                    │                          │
                         ┌──────────▼──────────┐              │
                         │   Redirect Service  │    ┌─────────▼─────────┐
                         │   (read-optimized)  │    │  URL Write Service │
                         └──────────┬──────────┘    │  (create / delete) │
                                    │               └─────────┬──────────┘
                 ┌──────────────────┼─────────────────────────┤
                 ▼                  ▼                          ▼
          ┌──────────┐      ┌──────────────┐         ┌──────────────────┐
          │  Redis   │      │  URL DB      │         │  ID Generator    │
          │  Cache   │      │  (Cassandra) │         │  Service         │
          │ (hot URLs│      └──────────────┘         └──────────────────┘
          │  + bloom │
          │  filter) │               │
          └──────────┘               │
                                     ▼
                              ┌──────────────────┐
                              │  Analytics       │
                              │  Service         │
                              │  (Kafka → Flink  │
                              │   → Cassandra)   │
                              └──────────────────┘
```

---

## 4. Short Code Generation — The Core Problem

### 4.1 Approach 1: MD5 / SHA-256 Hash + Truncation ❌ (Not Recommended)

```
shortCode = MD5(longURL).substring(0, 7)
```
- **Problem:** Collisions — two different URLs can produce the same 7-char prefix.
- Collision probability ≈ 1/62⁷ per pair, but at 100M URLs/day becomes significant.
- **Rejected:** collision resolution makes it complex.

### 4.2 Approach 2: Auto-increment ID + Base62 Encoding ✅ (Recommended)

```
Step 1: Generate unique integer ID (e.g., 1000000001)
Step 2: Encode to Base62
        1000000001 in Base62 = "15FTGg"  (6–7 chars)

Base62 alphabet: 0-9 (10) + a-z (26) + A-Z (26) = 62 chars
```

**Base62 Encoding:**
```java
String encode(long id) {
    char[] chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    StringBuilder sb = new StringBuilder();
    while (id > 0) {
        sb.append(chars[(int)(id % 62)]);
        id /= 62;
    }
    return sb.reverse().toString();
}
// 1000000001 → "15FTGg"
// Padding to 7 chars → "015FTGg"
```

**Properties:**
- ✅ Zero collisions (each ID is unique)
- ✅ Predictable length
- ✅ Fast encoding/decoding
- ⚠️ Sequential → slightly guessable (mitigated by shuffling)

### 4.3 Approach 3: Pre-generated Short Codes (Range-based) ✅ (Best at Scale)

```
ID Generator Service pre-generates ranges of IDs:
  Server 1 → assigned range [1, 1,000,000]
  Server 2 → assigned range [1,000,001, 2,000,000]
  ...

Each Write Server:
  - Keeps a local pool of 1,000 unused IDs in memory
  - Requests new range from ID Generator when pool is exhausted
  - Converts ID → Base62 short code locally (no network call per request)
```

**Benefits:**
- No coordination required per URL creation.
- Horizontally scalable — N write servers independently generate codes.
- No Redis/DB round trip per write for ID generation.

### 4.4 Custom Short Codes (User-defined Alias)

```
POST /shorten { longUrl: "...", customCode: "my-blog" }
  → Check if "my-blog" already exists in DB
  → If free → store with custom code
  → If taken → return 409 Conflict
Reserved words (admin, api, login, etc.) → blocklist checked first
```

---

## 5. Database Design

### 5.1 URL Mapping Table (Cassandra)

**Why Cassandra?** Write-heavy, 90TB+, simple key-value lookups — perfect fit.

```sql
CREATE TABLE url_mappings (
    short_code    TEXT PRIMARY KEY,
    long_url      TEXT,
    user_id       UUID,
    created_at    TIMESTAMP,
    expires_at    TIMESTAMP,
    is_custom     BOOLEAN,
    is_active     BOOLEAN
);

-- Index for duplicate check (same user, same long URL)
CREATE TABLE user_url_index (
    user_id       UUID,
    long_url_hash TEXT,   -- MD5 of long URL (for exact match)
    short_code    TEXT,
    PRIMARY KEY (user_id, long_url_hash)
);
```

### 5.2 Analytics Table (Cassandra — time-series)

```sql
CREATE TABLE url_click_events (
    short_code    TEXT,
    event_time    TIMESTAMP,
    visitor_id    UUID,
    country       TEXT,
    device_type   TEXT,   -- MOBILE | DESKTOP | TABLET
    referrer      TEXT,
    PRIMARY KEY (short_code, event_time)
) WITH CLUSTERING ORDER BY (event_time DESC);

-- Aggregate counts (pre-computed)
CREATE TABLE url_stats (
    short_code    TEXT PRIMARY KEY,
    total_clicks  COUNTER,
    unique_clicks COUNTER
);
```

---

## 6. Core Components

### 6.1 URL Write Service

```
POST /shorten
{ longUrl, userId, customCode (optional), ttlDays (optional) }

1. Validate longUrl (format check, not malicious domain)
2. Check duplicate: lookup user_url_index by (userId, MD5(longUrl))
   → If exists → return existing short code
3. If customCode provided:
   → Check availability in url_mappings
   → If taken → 409 Conflict
4. Generate short code:
   → Custom: use as-is
   → Generated: fetch next ID from local pool → Base62 encode
5. Store in Cassandra: url_mappings + user_url_index
6. Invalidate/update Redis cache (optional)
7. Return: { shortUrl: "short.ly/aB3xYz" }
```

### 6.2 Redirect Service (Hot Path — must be < 10ms)

```
GET /aB3xYz

1. Check Redis cache: GET url:aB3xYz
   → Cache Hit (90%+): return longUrl immediately (< 1ms)
   → Cache Miss: proceed to step 2

2. Bloom Filter check (before hitting DB):
   → If Bloom Filter says DEFINITELY ABSENT → 404 immediately (no DB call)
   → If Bloom Filter says PROBABLY PRESENT → proceed to step 3
   (Protects Cassandra from random/invalid code lookups — enumeration attacks)

3. Query Cassandra:
   → On hit: check is_active = true AND expires_at > now()
             Store result in Redis (TTL = 24hr or until expires_at)
             Publish click event to Kafka (async, non-blocking)
             Return HTTP 302 redirect to longUrl
   → On miss: → 404 Not Found (rare — only on Bloom false positive)

Q>> Where should bloom filter live in redis or local in-memory bloom filters?

 Ans>  Two Scenarios
📍 Scenario A — Bloom Filter in Redis (BF.EXISTS command)
NO — don't put it before Redis.

You'd be making one Redis call to avoid another Redis call. That's redundant — same network hop, same latency, no benefit.

Redis BF.EXISTS url:aB3xYz   ← Redis call #1
Redis GET url:aB3xYz         ← Redis call #2  (pointless if #1 is already a Redis call)
The correct order stays: Redis GET → Bloom Filter (BF.EXISTS) → Cassandra

📍 Scenario B — Bloom Filter is local in-memory (inside the service)
YES — absolutely put it first, before Redis.

A local Bloom Filter runs in nanoseconds with zero network cost. It kills bad requests before any I/O happens at all.

Local BF check (ns)   ← definitely absent? → 404 immediately, zero network
Redis GET (~0.5ms)    ← only reached for codes that might exist
Cassandra (~5-10ms)   ← only reached on Redis miss
What's Standard in Practice?
Setup	Bloom Filter Location	Put Before Redis?
Cassandra (built-in)	Local per SSTable	N/A — Cassandra handles it internally
Custom service BF	Local in-memory	✅ Yes — put it first
Redis BF module	Inside Redis	❌ No — would be a redundant Redis call


4. HTTP Response:
   → 301 (Permanent) — for public URLs (browser caches; reduces repeat hits)
   → 302 (Temporary) — for analytics URLs (browser doesn't cache; every click tracked)
```

**301 vs 302 Trade-off:**
|                    | 301 Permanent                         | 302 Temporary           |
| ------------------ | ------------------------------------- | ----------------------- |
| Browser caching    | Yes (subsequent visits bypass server) | No                      |
| Analytics accuracy | Undercounts repeats                   | Full click tracking     |
| Server load        | Lower                                 | Higher                  |
| **Recommendation** | General shortening                    | Analytics-required URLs |

### 6.3 Caching Strategy

```
Redis Cache:
  Key:   url:{shortCode}
  Value: { longUrl, expiresAt, isActive }
  TTL:   24 hours (or url's expires_at, whichever is sooner)

Bloom Filter (in Redis):
  - Probabilistic check: "does this shortCode definitely NOT exist?"
  - Prevents DB lookups for 100% invalid codes (random guessing attacks)
  - False positive rate: 0.1% (tolerable — just results in a DB hit)
  - Size: ~1.2 GB for 10B URLs (feasible)

Cache Eviction: LRU (20M hot URLs fit in ~10 GB Redis)
```

### 6.4 Analytics Pipeline

```
Redirect Service
  │ (async, non-blocking)
  │ Publish ClickEvent to Kafka
  │ { shortCode, timestamp, visitorId, ip, userAgent, referrer }
  ▼
Kafka Topic: url-clicks
  │
  ▼
Flink Streaming Job
  - GeoIP lookup (ip → country)
  - User-Agent parsing (device type)
  - Deduplication (unique visitors via HyperLogLog)
  │
  ▼
Cassandra (url_click_events) — raw events
Cassandra (url_stats) — COUNTER increments
Redis (real-time counters for dashboard)
```
```java
> total url count:-

stream
  .keyBy(event -> event.shortCode)
  .window(TumblingProcessingTimeWindows.of(Time.minutes(1)))
  .sum("count");

> geo location count:-
  stream
  .keyBy(event -> event.country)
  .window(TumblingProcessingTimeWindows.of(Time.minutes(1)))
  .sum("count");
```

---

## 7. URL Expiry Handling

**Lazy expiry (recommended):**
- On redirect: check `expires_at > now()` → if expired, return 410 Gone.
- No background cleanup needed per request.

**Proactive cleanup (background job):**
- Daily Spark job scans Cassandra for expired URLs → marks `is_active = false`.
- Frees up short codes for potential reuse.

**TTL in Redis:**
- Set Redis TTL = `expires_at - now()` → Redis auto-evicts at expiry.

---

## 8. Data Flow — URL Creation & Redirect

```
── CREATE ──────────────────────────────────────────────

Client POST /shorten {longUrl: "amazon.com/very/long/path?q=abc"}
  → Write Service
  → Duplicate check (user_url_index) → not found
  → Fetch ID 1234567 from local pool
  → Base62(1234567) = "5dxYz"
  → Pad to 7 chars = "05dxYz0"
  → Store in Cassandra + update Redis
  → Return "short.ly/05dxYz0"

── REDIRECT ────────────────────────────────────────────

Browser GET short.ly/05dxYz0
  → CDN (cache miss on first hit)
  → Redirect Service
  → Redis GET url:05dxYz0 → HIT!
  → Publish ClickEvent to Kafka (async)
  → HTTP 302 → "amazon.com/very/long/path?q=abc"
  → Browser follows redirect (< 10ms total)
```

---

## 9. Scalability

| Layer                | Scaling Strategy                                                     |
| -------------------- | -------------------------------------------------------------------- |
| **Write Service**    | Stateless; horizontal scale; each instance has local ID pool         |
| **Redirect Service** | Stateless; horizontal scale; CDN caches popular redirects            |
| **Redis**            | Cluster mode (sharded by shortCode); hot URLs distributed            |
| **Cassandra**        | Partition key = shortCode; scales linearly with nodes                |
| **Kafka**            | Partitioned by shortCode; analytics consumers scale independently    |
| **CDN**              | CloudFront / Cloudflare caches 301 redirects at edge — no origin hit |

---

## 10. Security & Abuse Prevention

| Concern                      | Mitigation                                                                     |
| ---------------------------- | ------------------------------------------------------------------------------ |
| **Malicious URL shortening** | Domain blocklist + Google Safe Browsing API check on creation                  |
| **Short code enumeration**   | Rate limit GET requests per IP; non-sequential codes (shuffle Base62 alphabet) |
| **Spam links**               | Require account creation for high-volume use; CAPTCHA for anonymous            |
| **Phishing via short URL**   | Warning interstitial for suspicious domains                                    |
| **Private URLs**             | Optional password-protected short URLs                                         |

---

## 11. Key Design Decisions & Trade-offs

| Decision              | Choice                              | Trade-off                                                         |
| --------------------- | ----------------------------------- | ----------------------------------------------------------------- |
| **ID generation**     | Range-based pre-allocation + Base62 | Zero collision, no coordination per write                         |
| **DB choice**         | Cassandra                           | Write-optimized, scales to 90TB; no complex queries needed        |
| **Short code length** | 7 chars (62⁷ = 3.5T)                | Enough for decades; decode > encode speed                         |
| **301 vs 302**        | 302 for tracked URLs                | Full analytics; slightly more server load                         |
| **Cache TTL**         | 24 hours (hot URLs)                 | High cache hit; stale risk on deletion (mitigated by active flag) |
| **Redirect latency**  | Redis first, Cassandra fallback     | < 1ms cache hit; 10ms DB fallback                                 |
| **Bloom filter**      | Redis-based Bloom filter            | Eliminates DB lookups for invalid codes                           |
| **Async analytics**   | Kafka + Flink                       | Redirect not blocked by analytics write                           |

---

## 12. Monitoring & Observability

| Signal                     | Metric / Alert                             |
| -------------------------- | ------------------------------------------ |
| **Redirect latency**       | P99 < 10ms; alert if > 20ms                |
| **Cache hit rate**         | Redis hit rate > 95%; alert if < 90%       |
| **URL creation rate**      | Writes/sec; spike = potential abuse        |
| **404 rate**               | High 404 = code enumeration attack         |
| **Kafka consumer lag**     | Analytics lag; alert if > 1M events behind |
| **Cassandra read latency** | Alert if P99 > 5ms                         |

---

## 13. Future Enhancements
- **QR code generation** — auto-generate QR for each short URL.
- **Link-in-bio pages** — landing page with multiple links (like Linktree).
- **Password-protected URLs** — require passphrase before redirect.
- **A/B redirect** — send 50% traffic to URL-A, 50% to URL-B.
- **UTM parameter auto-injection** — append campaign tracking params automatically.

---

*Document prepared for SDE 3 system design interviews. Focus areas: unique ID generation (Base62 encoding), extreme read-heavy caching, 301 vs 302 trade-offs, Bloom filters, and distributed analytics pipeline.*
