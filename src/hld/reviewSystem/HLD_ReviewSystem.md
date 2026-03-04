# ⭐ High-Level Design (HLD) — E-Commerce Review System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Write-heavy workloads, Content Moderation, Ranking/Aggregation, Abuse Prevention

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **submit a review** (rating 1–5 + text + media) for a purchased product.
- Users can **edit** or **delete** their own review (one review per user per product).
- Reviews are **displayed on product pages** with pagination & sorting (newest, most helpful, highest-rated).
- Users can **vote reviews as helpful / not helpful**.
- Reviews go through **moderation** before being publicly visible.
- Product **aggregate rating** (avg + count) is displayed prominently in real time.
- Users can **upload images/videos** with reviews.
- Support **review filtering** (by rating, verified purchase, with media).
- Sellers can **respond** to reviews.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Read Latency** | < 50 ms (P99) — product page loads |
| **Write Latency** | < 500 ms (P99) — review submission accepted |
| **Availability** | 99.99% for reads; 99.9% for writes |
| **Scale** | 500M products, 100M DAU, ~50K review writes/sec (peak sale events) |
| **Consistency** | Eventual for review lists; near-real-time for aggregate rating |
| **Durability** | Reviews must never be lost |
| **Moderation** | Auto-moderation < 2 sec; human review < 24 hours |

### 1.3 Out of Scope
- Payment or order verification (consumed via events)
- Seller reputation scoring
- Legal takedown / DMCA handling

---

## 2. Capacity Estimation

```
Products              = 500 million
DAU                   = 100 million
Review Writes/day     = 5M  → ~60 writes/sec avg, ~50K/sec peak (sale events)
Review Reads/day      = 1B  → ~12K reads/sec avg, ~500K/sec peak
Avg Review Size       = 500 bytes (text) + metadata
Media per review      = ~20% carry images/video (stored in object storage)
Total review storage  = 5M/day × 500B = ~2.5 GB/day → ~900 GB/year (text only)
Aggregate rating      = 500M products × 20 bytes = ~10 GB (fits in cache)
```

---

## 3. High-Level Architecture

```
          Mobile / Web
               │
       ┌───────▼────────┐
       │   API Gateway  │ (Auth, Rate Limit, Routing)
       └───────┬────────┘
               │
    ┌──────────▼──────────┐
    │    Review Service   │  (Write Path + Read Path)
    └──┬──────────────┬───┘
       │              │
 ┌─────▼──────┐  ┌────▼──────────┐
 │ Write Path │  │   Read Path   │
 └─────┬──────┘  └────┬──────────┘
       │               │
  ┌────▼────┐     ┌────▼──────────────┐
  │  Kafka  │     │  Review Cache     │
  │ (Events)│     │  (Redis Cluster)  │
  └────┬────┘     └────┬──────────────┘
       │               │ (cache miss)
  ┌────▼──────────┐  ┌─▼───────────────────┐
  │ Moderation    │  │   Review DB          │
  │ Service       │  │ (Cassandra / DynamoDB)│
  │ (ML + Human)  │  └─────────────────────┘
  └────┬──────────┘
       │ (approved)
  ┌────▼──────────────────────────┐
  │  Indexing / Search Service    │
  │  (Elasticsearch)              │
  └───────────────────────────────┘
       │
  ┌────▼──────────────────┐
  │  Aggregate Rating Svc │
  │  (Redis + DB rollup)  │
  └───────────────────────┘
       │
  ┌────▼──────────┐
  │  Media Store  │
  │  (S3 + CDN)   │
  └───────────────┘
```

---

## 4. Core Components

### 4.1 API Gateway
- **Auth:** Validates JWT (must be a verified purchase to write a review — checked via Order Service event).
- **Rate Limiting:** 1 review per user per product; max 10 reviews/day per user.
- **Routing:** Read vs write path separation.

### 4.2 Review Service

**Key APIs:**

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/v1/reviews` | Submit a new review |
| `PATCH` | `/v1/reviews/{reviewId}` | Edit own review |
| `DELETE` | `/v1/reviews/{reviewId}` | Soft-delete own review |
| `GET` | `/v1/products/{productId}/reviews` | Paginated review list |
| `POST` | `/v1/reviews/{reviewId}/vote` | Vote helpful/unhelpful |
| `POST` | `/v1/reviews/{reviewId}/reply` | Seller reply |
| `GET` | `/v1/products/{productId}/rating-summary` | Aggregate rating |

**Write Path Flow:**
1. Validate user has a verified purchase (via Order Service event pre-cached in Review DB).
2. Store review with status = `PENDING_MODERATION`.
3. Upload media metadata (actual bytes go directly to S3 via pre-signed URL).
4. Publish `ReviewSubmitted` event to Kafka.
5. Return `202 Accepted` immediately (async moderation).

**Read Path Flow:**
1. Check Redis cache for paginated review list.
2. On cache miss → query Review DB (Cassandra).
3. Enrich with real-time helpfulness vote counts from Redis counters.
4. Return sorted, paginated results.

### 4.3 Review Database — Cassandra / DynamoDB

Reviews are a **write-heavy, partition-by-product** workload — perfect for Cassandra.

**Cassandra Table Design:**

```sql
-- Primary review store
CREATE TABLE reviews (
    product_id    UUID,
    created_at    TIMESTAMP,
    review_id     UUID,
    user_id       UUID,
    rating        TINYINT,
    title         TEXT,
    body          TEXT,
    status        TEXT,   -- PENDING | APPROVED | REJECTED | DELETED
    helpful_votes INT,
    media_urls    LIST<TEXT>,
    is_verified   BOOLEAN,
    PRIMARY KEY (product_id, created_at, review_id)
) WITH CLUSTERING ORDER BY (created_at DESC);

-- One review per user per product enforcement
CREATE TABLE user_product_review (
    user_id       UUID,
    product_id    UUID,
    review_id     UUID,
    PRIMARY KEY (user_id, product_id)
);
```

- Partition by `product_id` → all reviews for a product co-located.
- Supports efficient sorted reads (newest first) using clustering key `created_at DESC`.
- `user_product_review` table enforces one-review-per-user-per-product at DB level.

### 4.4 Moderation Service

Two-tier moderation pipeline:

```
Review Submitted
      │
      ▼
  ML Auto-Moderation (< 2 sec)
  ┌────────────────────────────────────────┐
  │  • Toxicity / hate speech detection   │
  │  • Spam / fake review classifier      │
  │  • PII detection (email, phone)       │
  │  • Image/video content safety (NSFW)  │
  └────────────────────────────────────────┘
      │
  ┌───▼──────────────────────────────────────┐
  │  Score ≥ 0.9 safe  →  AUTO-APPROVE       │
  │  Score 0.5–0.9     →  HUMAN REVIEW QUEUE │
  │  Score < 0.5       →  AUTO-REJECT        │
  └──────────────────────────────────────────┘
      │ (human review queue)
  ┌───▼─────────────────────────┐
  │  Moderation Dashboard       │
  │  (Internal tool for agents) │
  │  SLA: < 24 hours            │
  └─────────────────────────────┘
      │ (decision made)
  Kafka: ReviewApproved / ReviewRejected
      │
  Review DB status updated
  Cache invalidated
  Elasticsearch indexed
```

### 4.5 Aggregate Rating Service

Product-level rating aggregates (`avg_rating`, `total_count`, `rating_distribution`) are **read extremely frequently** (every product page load) and must be fast.

**Strategy:**
- **Redis** stores aggregate per product: `rating:productId → { avg, count, dist[1..5] }`
- On `ReviewApproved` Kafka event → update Redis counter atomically (Lua script).
- **Periodic DB rollup** (every 15 min) persists aggregates to `ProductRating` table for durability.
- On cache miss (cold product) → read from DB and warm up Redis.

```
Redis Key:  rating:productId
Type:       HASH
Fields:     avg_rating, total_count, count_1, count_2, count_3, count_4, count_5
TTL:        None (always kept hot for active products), LRU eviction for long-tail
```

### 4.6 Search & Filter — Elasticsearch

- Indexed after review is approved.
- Supports full-text search within reviews for a product.
- Enables **filters:** by star rating, verified purchase, with media, date range.
- Enables **sort:** by helpfulness score, newest, highest rating.
- Updated via Kafka consumer (`ReviewApproved` → index document).

### 4.7 Helpful Votes

- Stored as **Redis counters** for real-time updates: `votes:reviewId:helpful` / `votes:reviewId:unhelpful`.
- User vote deduplication: `voted:{userId}:{reviewId}` → Boolean (Redis SET, TTL = 1 year).
- Periodically flushed to Review DB (background job every 5 min).
- **Helpfulness score** = `helpful / (helpful + unhelpful)` — used for "Most Helpful" sort.

### 4.8 Media Storage (Images / Videos)

```
Client  ──▶  Review Service  ──▶  Pre-signed S3 URL
  │
  └──▶  Direct upload to S3
              │
         S3 Event → Lambda → Media Processing Service
                                    │
                              Image resizing (thumbnails)
                              Video transcoding
                              NSFW scan (Vision AI)
                                    │
                              CDN (CloudFront) invalidation
                                    │
                              media_urls stored in Review DB
```

---

## 5. Data Flow — Review Submission

```
Client
  │─── POST /v1/reviews ──▶ API Gateway
                                 │ (Auth: JWT + verified purchase check)
                                 ▼
                          Review Service
                                 │
                    ┌────────────┼─────────────────┐
                    ▼            ▼                  ▼
             User-product    Persist to DB      Get pre-signed
             review check    (status=PENDING)   S3 URL for media
             (Cassandra)
                    │
                    ▼
             Publish ReviewSubmitted → Kafka
                    │
             Return 202 Accepted to Client
                    │
         ┌──────────▼───────────┐
         │  Moderation Consumer │
         │  (ML auto-mod)       │
         └──────────┬───────────┘
                    │
          ┌─────────▼──────────┐
          │ ReviewApproved /   │
          │ ReviewRejected     │
          │ event → Kafka      │
          └─────────┬──────────┘
                    │
       ┌────────────┼──────────────────┐
       ▼            ▼                  ▼
  Update DB     Update Redis       Index in
  status        aggregates         Elasticsearch
                + cache invalidate
```

---

## 6. Read Path — Product Reviews

```
GET /v1/products/{productId}/reviews?sort=helpful&rating=4&page=2

API Gateway → Review Service (Read)
                      │
               Check Redis cache
               key: reviews:{productId}:helpful:4:p2
                      │
          ┌───────────┴──────────────────────┐
          │ Cache Hit                  Cache Miss
          ▼                                  ▼
   Return cached JSON          Query Cassandra (by productId)
                               + ES for filter/sort
                                       │
                               Enrich with vote counts (Redis)
                                       │
                               Store in Redis (TTL: 5 min)
                                       │
                               Return paginated results
```

---

## 7. Fake Review Detection

Core concern for any review system at scale:

| Signal | Technique |
|---|---|
| **Account age** | New accounts posting reviews flagged |
| **Review velocity** | > N reviews/hour from same IP / device |
| **Text similarity** | Near-duplicate reviews across users (MinHash / LSH) |
| **Graph analysis** | Coordinated review rings (graph anomaly detection) |
| **Verified purchase** | Only allow reviews from buyers (order event linkage) |
| **Device fingerprint** | Multiple accounts from same device flagged |
| **ML model** | Trained classifier on labeled fake/real review dataset |

---

## 8. Caching Strategy

| Data | Cache | TTL | Invalidation |
|---|---|---|---|
| Review list (paginated) | Redis | 5 min | On new review approved |
| Aggregate rating | Redis | No TTL (LRU) | On every review approval |
| Helpfulness votes | Redis counters | No TTL | Periodic flush to DB |
| User vote deduplication | Redis SET | 1 year | — |
| Product metadata | Local in-process | 5 min | — |

---

## 9. Scalability & Reliability

### 9.1 Write Scalability
- **Async write path:** `202 Accepted` → Kafka → async moderation → DB write.
- Kafka partitioned by `productId` → preserves per-product ordering of review events.
- Cassandra horizontally scales with product partitions; no hot partitions (products distribute naturally).

### 9.2 Read Scalability
- > 90% reads served from **Redis** (review lists + aggregates).
- Elasticsearch for complex filter/sort queries; Redis for simple paginated reads.
- CDN for media (images/videos) — zero origin hits for popular review images.

### 9.3 Failure Handling

| Failure | Degradation Strategy |
|---|---|
| Redis down | Fallback to Cassandra reads; aggregate computed on the fly |
| Kafka lag spike | Reviews accumulate as PENDING; moderation eventually consistent |
| Elasticsearch down | Disable text search; basic sort/filter from Cassandra |
| Moderation ML down | Route all to human review queue; writes still accepted |
| S3 upload failure | Review accepted without media; retry media upload separately |

### 9.4 Hot Product Problem
Very popular products (top-selling on Prime Day) → millions of reads/sec for same product reviews.

- **Solution:** Local in-process cache in Review Service pods (Caffeine, TTL: 30 sec).
- Prevents thundering herd on Redis for viral products.
- Accepted trade-off: 30-sec stale data window.

---

## 10. Consistency Considerations

| Concern | Approach |
|---|---|
| One review per user per product | `user_product_review` table + idempotency key |
| Aggregate rating accuracy | Kafka consumer, atomic Lua script on Redis |
| Duplicate helpful votes | Redis SET deduplication before incrementing counter |
| Moderation race condition | Review status is a state machine; only `PENDING → APPROVED/REJECTED` transitions allowed |
| Cache staleness | Short TTL (5 min); event-driven invalidation on approval |

---

## 11. Security & Abuse Prevention

- **Verified Purchase Only:** Review write API validates purchase via event-driven pre-cache.
- **IDOR Prevention:** `userId` in JWT must match review author for edit/delete.
- **Rate Limiting:** 1 review/product/user; 10 reviews/user/day; 100 votes/user/hour.
- **Media Safety:** NSFW scan before media URLs are marked public.
- **PII Scrubbing:** Auto-detect and redact phone numbers, emails in review text (ML + regex).

---

## 12. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Review submission rate** | Writes/sec; spike detection (bot activity) |
| **Moderation queue depth** | Alert if > 10K pending > 1 hour |
| **Auto-rejection rate** | Alert if sudden spike (may indicate ML model drift) |
| **Cache hit rate** | Redis hit rate for review lists; target > 90% |
| **Aggregate rating drift** | Actual DB aggregate vs Redis aggregate diff > 0.1 |
| **ES indexing lag** | Alert if Kafka consumer lag for ES indexer > 5 min |
| **Fake review detection rate** | Track flagged/confirmed fake review % |

**Tooling:** Prometheus + Grafana, distributed tracing (Jaeger), ELK for logs.

---

## 13. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Async moderation** | 202 Accepted, async pipeline | Better write latency; review not instantly visible |
| **DB choice** | Cassandra (partition by productId) | Write-optimized, scales well; no complex joins |
| **Aggregate storage** | Redis (always hot) | Sub-ms reads; small risk of stale data (mitigated by Lua scripts) |
| **Elasticsearch** | Separate search index | Flexible queries; added ops complexity + eventual consistency |
| **Media via S3 pre-signed URLs** | Client uploads directly to S3 | Offloads bandwidth from Review Service entirely |
| **One-review-per-product** | Enforced at DB level | Prevents abuse; DB-level constraint is safer than app-level |
| **Verified purchase requirement** | Order event linkage | Dramatically reduces fake reviews; excludes non-buyers |

---

## 14. Future Enhancements
- **Sentiment Analysis** — Auto-tag reviews as positive/negative/neutral; show sentiment summary on product page.
- **Review Highlights** — ML-generated summary of top themes across hundreds of reviews (like Amazon's AI summaries).
- **Q&A Section** — Product Q&A as an extension of the review system.
- **Multi-language Support** — Auto-translate reviews via NLP pipeline.
- **Seller Analytics Dashboard** — Real-time review trends, sentiment breakdown per product for sellers.

---

*Document prepared for SDE 3 system design interviews. Focus areas: write-heavy systems, content moderation pipelines, caching strategies, abuse prevention, and eventual consistency.*
