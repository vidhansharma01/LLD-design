# 🕷️ High-Level Design (HLD) — Web Crawler
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Distributed Crawling, URL Frontier, Politeness, Deduplication, HTML Parsing, Scalability, robots.txt

---

## 1. Requirements

### 1.1 Functional Requirements
- Crawl the **entire web** (or a specific domain subset) starting from a set of **seed URLs**.
- **Download HTML content** of web pages and extract all hyperlinks.
- Store raw HTML and extracted metadata (title, description, outlinks).
- **Discover new URLs** from extracted links and add them to the crawl queue.
- Respect **robots.txt** — honor crawl-disallow rules per domain.
- Handle **duplicate URLs** — don't crawl the same page twice.
- Handle **near-duplicate content** (mirrors, URL variations of same content).
- Support **recrawling** — periodically re-visit pages to detect updates.
- Support **priority crawling** — crawl high-value pages (news, .gov, .edu) first.
- Handle **different content types**: HTML, PDF, images (configurable).

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Scale** | Crawl 10 billion pages; ~1B pages/day in steady state |
| **Throughput** | 10,000 pages/sec (parallelized) |
| **Politeness** | Max 1 request / 2 sec per domain |
| **Freshness** | High-value pages re-crawled every 24 hr; low-value every 30 days |
| **Availability** | Crawl should survive individual node failures |
| **Storage** | ~50 KB avg page → 10B pages = ~500 TB raw HTML |
| **Dedup accuracy** | < 0.01% false positive rate for URL dedup |

### 1.3 Out of Scope
- Search indexing (downstream consumer of crawl output)
- Page ranking (PageRank — separate pipeline)
- JavaScript rendering (basic crawler; headless browser is separate)

---

## 2. Capacity Estimation

```
Target pages              = 10 billion
Avg HTML page size        = 50 KB
Total raw storage         = 10B × 50KB = 500 TB
Pages/day (steady state)  = 1 billion → ~11,600 pages/sec avg
Crawl workers needed      = 11,600 pages/sec ÷ ~5 pages/sec/worker = ~2,300 workers
                           (each worker bottlenecked by network I/O, ~100–200ms/page)
URLs discovered/page      = avg ~50 links → 10B pages × 50 = 500B URLs to dedup
Bloom filter size (500B URLs, 0.01% FPR) ≈ ~1.2 TB (distributed across nodes)
DNS cache entries         = Millions of unique domains; TTL varies
```

---

## 3. High-Level Architecture

```
                   ┌──────────────┐
   Seed URLs ─────▶│  URL Frontier│◀──────────────────────────────┐
                   │  (Priority   │                                │
                   │   Queue)     │                                │
                   └──────┬───────┘                                │
                          │ (next URL batch)                       │
                   ┌──────▼──────────────────────────────┐         │
                   │        Crawler Workers               │         │
                   │  (Distributed — 2,000+ nodes)        │         │
                   │                                     │         │
                   │  1. DNS Resolve (cached)            │         │
                   │  2. Fetch HTML (HTTP/2)             │         │
                   │  3. Parse HTML → extract links      │         │
                   │  4. Dedup check new URLs            │         │
                   │  5. Enqueue new URLs → Frontier     │─────────┘
                   └──────┬──────────────────────────────┘
                          │ (downloaded content)
             ┌────────────┼─────────────────────────┐
             ▼            ▼                         ▼
      ┌─────────────┐ ┌──────────────┐   ┌──────────────────┐
      │  Content    │ │  Link        │   │  robots.txt      │
      │  Store (S3) │ │  Extractor   │   │  Cache           │
      │  Raw HTML   │ │  + Metadata  │   │  (Redis)         │
      └─────────────┘ └──────┬───────┘   └──────────────────┘
                             │
                    ┌────────▼──────────┐
                    │  URL Seen Store   │  (Bloom Filter + URL DB)
                    │  Deduplication    │
                    └───────────────────┘
                             │
                    ┌────────▼──────────┐
                    │  Downstream Index │  (Elasticsearch / Search Engine)
                    └───────────────────┘
```

---

## 4. Core Components

### 4.1 URL Frontier (Priority Queue)

The **heart of the crawler** — decides what to crawl next and in what order.

**Two-level priority queue design:**

```
Level 1: Priority Queues (by importance)
  High Priority Queue   → News, .gov, .edu, seed domains
  Medium Priority Queue → General web pages
  Low Priority Queue    → Forums, comment sections, spam-likely

Level 2: Politeness Queues (one queue per domain)
  q_nytimes.com  → [url1, url2, url3, ...]
  q_bbc.com      → [url4, url5, ...]
  q_reddit.com   → [url6, url7, ...]

Selector:
  - Pick from a Priority Queue based on priority weights
  - For the selected URL, check: lastCrawlTime[domain] + 2 sec < now?
  - If yes → dispatch to worker
  - If no → skip for now, pick another domain (politeness enforcement)
```

**URL scoring (priority assignment):**
```
score = α × page_rank_estimate
      + β × domain_authority
      + γ × content_freshness_decay (how long since last crawl)
      + δ × inlink_count (how many pages link here)
      + ε × domain_type_bonus (.gov=1.5, .edu=1.3, news=1.2)
```

**Implementation:** Apache Kafka + Redis Sorted Set combination:
- Kafka topics: `frontier-high`, `frontier-medium`, `frontier-low`.
- Redis ZSET per domain for politeness: `domain:nytimes.com` → {url: score}.

### 4.2 Crawler Workers (Distributed Fetch Layer)

**Stateless, horizontally scalable.** Each worker:

```java
void crawlLoop() {
    while (true) {
        URL url = frontier.getNextUrl();          // respects politeness
        if (url == null) { sleep(100ms); continue; }

        // Step 1: Check robots.txt
        RobotsTxt robots = robotsCache.get(url.domain);
        if (robots.isDisallowed(url.path)) {
            frontier.markSkipped(url);
            continue;
        }

        // Step 2: DNS resolve (cache aggressively)
        InetAddress ip = dnsCache.resolve(url.host);  // TTL: 30 min

        // Step 3: Fetch page
        HttpResponse response = httpClient.get(url, timeout=5sec);

        // Step 4: Handle response
        switch (response.statusCode) {
            case 200 -> processPage(url, response);
            case 301/302 -> frontier.enqueue(response.redirectUrl, priority);
            case 404 -> seen.markDead(url);
            case 429 -> frontier.backoff(url.domain, 60 sec);
            case 5xx -> frontier.requeue(url, retryDelay=exponential);
        }

        // Step 5: Update last-crawl time for domain
        politeness.update(url.domain, now());
    }
}
```

**HTTP best practices:**
- HTTP/2 connection pooling (one TCP connection, many requests per domain).
- Connection timeout: 5 sec; read timeout: 10 sec.
- User-Agent header: `MyBot/1.0 (+http://mycompany.com/bot)`.
- Respect `Crawl-Delay` in robots.txt.

### 4.3 HTML Parser & Link Extractor

```java
PageResult parse(URL sourceUrl, String html) {
    Document doc = Jsoup.parse(html);

    // Extract metadata
    String title   = doc.select("title").text();
    String desc    = doc.select("meta[name=description]").attr("content");
    String lang    = doc.select("html").attr("lang");
    List<String> outlinks = new ArrayList<>();

    // Extract all hyperlinks
    for (Element a : doc.select("a[href]")) {
        String href = a.absUrl("href");          // resolve relative URLs
        href = normalizeUrl(href);               // strip fragments, lowercase
        if (isValidUrl(href)) outlinks.add(href);
    }

    return new PageResult(title, desc, outlinks, extractedText(doc));
}

String normalizeUrl(String url) {
    // Remove fragment: http://example.com/page#section → http://example.com/page
    // Lowercase scheme + host
    // Remove default ports (http:80, https:443)
    // Sort query params alphabetically
    // Strip tracking params (utm_source, fbclid, etc.)
}
```

**URL normalization is critical** — these are all the same page:
```
http://Example.com/Page
http://example.com/page
http://example.com/page?
http://example.com/page#anchor
→ normalize to: http://example.com/page
```

### 4.4 URL Deduplication (Seen URL Store)

**The biggest scale challenge.** 500B unique URLs needed.

**Approach: Bloom Filter + Persistent URL DB**

```
Bloom Filter (fast probabilistic check):
  - In-memory (distributed Redis Cluster or dedicated service)
  - Size: ~1.2 TB for 500B URLs at 0.01% FPR using 4 hash functions
  - Check: "Has this URL definitely NOT been crawled?" → O(1)
  - False positive: 0.01% (URL looks seen but isn't → miss a few pages, acceptable)
  - False negative: ZERO (never misidentifies a new URL as seen)

Persistent URL DB (exact match store, fallback):
  - Cassandra table: primary key = MD5/SHA1(normalizedUrl)
  - Stores: url, first_crawl_time, last_crawl_time, http_status, content_hash

Dedup flow per discovered URL:
  1. Normalize URL
  2. Bloom filter check → if "definitely not seen" → proceed
  3. Cassandra lookup (double-check false positives) → if truly new → enqueue
  4. Add to Bloom filter + Cassandra
```

**Content deduplication (near-duplicates):**
```
SimHash (Locality Sensitive Hashing):
  - Compute 64-bit SimHash of page content
  - If Hamming distance(hash_A, hash_B) < 3 → near-duplicate → skip

Stored in: HBase / Cassandra (simhash → urlId)
Used to: avoid re-indexing mirrored content (e.g., printer-friendly pages)
```

### 4.5 robots.txt Handling

```
On first visit to a domain (example.com):
  Fetch: https://example.com/robots.txt

Parse rules:
  User-agent: *         → applies to all bots
  Disallow: /admin/     → skip all /admin/* URLs
  Disallow: /private    → skip /private
  Allow: /public/       → override disallow for /public/
  Crawl-delay: 5        → wait 5 sec between requests

Cache in Redis:
  Key:  robots:{domain}
  Value: parsed RobotsTxt object
  TTL:  24 hours

If robots.txt fetch fails (404) → assume no restrictions
If robots.txt fetch timeout → retry once; if fails → assume no restrictions
```

### 4.6 Content Store (S3 + Metadata DB)

```
Raw HTML:
  S3 path: s3://crawl-data/{year}/{month}/{day}/{urlHash}.html.gz
  Compressed with gzip → avg 10 KB (from 50 KB raw)
  Total compressed: ~100 TB for 10B pages

Metadata (Cassandra):
CREATE TABLE crawled_pages (
    url_hash      TEXT PRIMARY KEY,   -- MD5 of normalized URL
    url           TEXT,
    domain        TEXT,
    crawl_time    TIMESTAMP,
    http_status   INT,
    content_type  TEXT,
    content_hash  TEXT,               -- SHA-256 of HTML body (exact dedup)
    simhash       BIGINT,             -- 64-bit SimHash (near-dedup)
    s3_path       TEXT,
    title         TEXT,
    lang          TEXT,
    outlink_count INT,
    page_rank     DOUBLE
);
```

### 4.7 DNS Cache

DNS resolution is a major bottleneck — every URL requires a DNS lookup.

```
Local DNS cache per worker (in-process):
  Map<String, InetAddress> → TTL: 30 min
  Handles 90%+ of lookups (most URLs from same domains)

Shared DNS cache (Redis Cluster):
  Key: dns:{hostname}
  Value: IP address
  TTL: min(actual DNS TTL, 30 min)

Negative cache:
  If DNS fails → cache NXDOMAIN for 5 min (don't hammer failed domains)

External DNS resolver:
  Use anycast DNS (8.8.8.8, 1.1.1.1) only on cache miss
```

---

## 5. Politeness & Crawl Ethics

**Politeness is non-negotiable** — an impolite crawler can DDoS websites.

```
Per-domain politeness rules:
  1. Crawl-Delay from robots.txt (if specified): honor exactly
  2. Default delay: 2 sec between requests to same domain
  3. If server returns 429 (rate limit): exponential backoff
     → 1 min → 5 min → 30 min → 2 hr → give up for 24 hr
  4. Max concurrent connections per domain: 2

Real-time politeness enforcement (Redis):
  Key:   polite:{domain}
  Value: last_request_timestamp
  Cmd:   SET polite:nytimes.com <now> EX 2
         → Worker checks: can it proceed? NX succeeds → yes, proceed
```

---

## 6. Recrawl Scheduling

Not all pages need to be recrawled equally:

| Page Type | Recrawl Frequency |
|---|---|
| News sites, trending pages | Every 24 hours |
| Major websites (.gov, .edu, top 1M) | Every 7 days |
| General web (medium traffic sites) | Every 30 days |
| Low-value / rarely updated | Every 90+ days |
| Dead pages (404) | Mark dead; stop crawling |

```
Recrawl decision:
  factor = change_rate × importance_score
  change_rate = (# of times content changed) / (# of recrawls)

  If change_rate is high (news) → recrawl frequently
  If change_rate is low (static pages) → recrawl less often

Scheduled via a Recrawl Priority Queue (back-fill into main Frontier):
  URLs sorted by: last_crawl_time + recommended_recrawl_interval
```

---

## 7. Data Flow — End-to-End

```
Bootstrap: seed 1M high-value URLs into Frontier
        │
        ▼
Frontier dispatches URLs to workers (respecting politeness)
        │
Worker picks URL: https://nytimes.com/article/xyz
        │
  1. robots.txt cache: /article/* → ALLOWED
  2. DNS cache: nytimes.com → 151.101.x.x (cached)
  3. HTTP GET https://nytimes.com/article/xyz
     → Bloom filter check on URL: not seen → proceed
  4. Response 200 OK, Content-Type: text/html
  5. Parse HTML → extract 42 outlinks
  6. For each outlink:
       Normalize → Bloom filter check → if new → enqueue to Frontier
  7. Store compressed HTML → S3
  8. Write metadata → Cassandra
  9. Emit PageCrawled event → Kafka → downstream (search indexer)
  10. Update domain last-crawl time in Redis
```

---

## 8. Handling Special Cases

| Scenario | Handling |
|---|---|
| **Redirect chains** | Follow up to 5 hops; beyond that → mark as bad URL |
| **Infinite URL spaces** | URL normalization + Bloom filter; detect patterns (?page=1, ?page=2, ...) → cap at 10 |
| **Spider traps (loops)** | Track URL depth; max depth = 10 hops from seed |
| **Slow servers** | Per-request timeout 5s; abandon + requeue with low priority |
| **Non-HTML content** | Check Content-Type before parsing; skip binary unless configured |
| **HTTPS cert errors** | Skip domain; log for review |
| **JavaScript-heavy (SPA)** | Basic crawler returns empty page; trigger headless browser queue separately |
| **Robots.txt not found** | 404 → assume no restrictions; 5xx → skip domain temporarily |
| **Duplicate content (mirrors)** | SimHash comparison → skip re-indexing, still mark URL as crawled |

---

## 9. Distributed Coordination

**Partitioning the crawl across 2000+ workers:**

```
URL space partitioned by domain hash:
  hash(domain) % N_workers → worker responsible for that domain

Benefits:
  - Politeness naturally enforced per worker (each worker owns its domains)
  - Local DNS cache stays warm for that domain
  - No coordination needed between workers

Frontier partitioned into N Kafka partitions:
  Partition key = hash(domain) % N_partitions
  Each worker consumes its assigned partitions

Failure handling:
  - Kafka consumer group: if worker dies → partition reassigned to another worker
  - Checkpointing: Kafka offset committed after each successful crawl
  - At-least-once crawling: possible duplicate crawl on failure — URL dedup handles it
```

---

## 10. Storage Architecture

| Data | Storage | Size | Retention |
|---|---|---|---|
| Raw HTML (compressed) | S3 | ~100 TB | 90 days (rolling) |
| URL metadata | Cassandra | ~2 TB | Permanent |
| Bloom filter (URL dedup) | Redis Cluster | ~1.2 TB | Permanent |
| SimHash (content dedup) | HBase / Cassandra | ~500 GB | Permanent |
| DNS cache | Redis | ~10 GB | TTL-based |
| robots.txt cache | Redis | ~5 GB | 24hr TTL |
| Crawl metrics | InfluxDB / Prometheus | 100 GB | 30 days |

---

## 11. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **URL dedup** | Bloom filter + Cassandra | Fast (O(1)) + exact; Bloom filter ~0.01% FPR accepted |
| **Content dedup** | SimHash | Catches near-duplicates; small computation cost per page |
| **URL normalization** | Aggressive (strip fragments, params) | May miss genuinely different pages (acceptable) |
| **Politeness enforcement** | Redis SET NX per domain | Distributed, low-latency; slight clock skew possible |
| **Frontier design** | Kafka + Redis ZSET | Durable (Kafka) + fast priority access (ZSET) |
| **Storage** | S3 + Cassandra | S3 for cheap bulk storage; Cassandra for fast URL lookups |
| **Recrawl** | Change-rate-based | Smart freshness vs uniform schedule |
| **JS rendering** | Out of scope (basic crawler) | Simpler ops; misses SPA content (separate headless crawler) |

---

## 12. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Crawl throughput** | Pages/sec; alert if < 8K/sec (target 10K) |
| **Frontier depth** | Queue size per priority level; alert if high-priority queue growing |
| **Politeness violations** | 429 rate per domain; indicates crawl-delay too short |
| **DNS failure rate** | % of DNS lookups failing → may indicate bad URL batches |
| **Bloom filter false positive rate** | Estimate via spot-check of "seen" URLs |
| **Worker failure rate** | Dead workers detected via Kafka consumer lag |
| **Robots.txt violation rate** | Should always be 0 |

---

## 13. Future Enhancements
- **JavaScript rendering layer** — Headless Chrome cluster for SPA-heavy sites.
- **Focused crawling** — crawl only specific domains / topic areas.
- **International crawling** — handle charset encoding diversity, hreflang tags.
- **Link graph extraction** — export full web link graph for PageRank computation.
- **Change detection** — diff crawled HTML vs stored version; emit only diffs to downstream.

---

*Document prepared for SDE 3 system design interviews. Focus areas: URL frontier design, politeness enforcement, Bloom filter deduplication, SimHash near-duplicate detection, distributed crawl coordination via Kafka, and robots.txt compliance.*
