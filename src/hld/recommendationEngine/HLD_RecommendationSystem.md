# 🎯 High-Level Design (HLD) — Recommendation System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** ML Pipeline, Collaborative Filtering, Real-time vs Batch, Feature Store, Cold Start, A/B Testing

---

## 1. Requirements

### 1.1 Functional Requirements
- Recommend **personalized products** to users on the homepage, product detail page (PDP), and cart page.
- Show **"Customers also bought"** (item-to-item recommendations).
- Show **"Recommended for you"** (user-personalized recommendations).
- Show **"Trending / Popular"** items (non-personalized fallback).
- Recommendations must reflect **real-time user session behavior** (browsed X → recommend related to X).
- Handle **cold start** — new users and new items with no history.
- Support **A/B testing** of recommendation algorithms.
- Recommendations are **context-aware** — homepage vs PDP vs cart surface different items.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Read Latency** | < 50 ms P99 (recommendations served inline with page load) |
| **Freshness** | Session behavior reflected within 30 sec; model retrained daily |
| **Scale** | 500M users, 50M products, 100M page views/day |
| **Availability** | 99.99% — always show something (fallback to trending) |
| **Relevance** | CTR, add-to-cart rate as business KPIs |

### 1.3 Out of Scope
- Ad targeting / sponsored recommendations
- Search ranking (separate system)
- Real-time model training (near-real-time batch is acceptable)

---

## 2. Capacity Estimation

```
Users               = 500 million
Products            = 50 million
Page views/day      = 100 million → ~1200 req/sec avg, ~50K/sec peak
User interactions   = 1B events/day (clicks, views, purchases, add-to-cart)
Recommendation set  = Top 20 items per user per context
Storage per user    = 20 items × 4 bytes (productId) × 5 contexts = ~400 bytes
Total user vectors  = 500M users × 256-dim float vector × 4 bytes = ~512 GB
Item vectors        = 50M items × 256-dim × 4 bytes = ~51 GB
```

---

## 3. High-Level Architecture

```
 User Interaction Events (clicks, views, purchases, ratings)
          │
          ▼
 ┌─────────────────┐
 │   Event Bus     │  (Kafka — partitioned by userId)
 └────────┬────────┘
          │
 ┌────────┴──────────────────────────────────────────────┐
 │                                                        │
 ▼                                                        ▼
 Real-Time Pipeline                          Batch Pipeline
 (Apache Flink / Spark Streaming)            (Apache Spark — daily)
          │                                        │
          ▼                                        ▼
 ┌────────────────┐                     ┌─────────────────────────┐
 │ Session-based  │                     │  Model Training         │
 │ Recommendations│                     │  (Collaborative Filter  │
 │ (in-session    │                     │  + Content-based +      │
 │  signals)      │                     │  Deep Learning)         │
 └───────┬────────┘                     └──────────┬──────────────┘
         │                                         │
         ▼                                         ▼
 ┌──────────────────┐                   ┌─────────────────────────┐
 │  Real-time       │                   │  Feature Store          │
 │  Feature Store   │                   │  (user embeddings,      │
 │  (Redis)         │                   │   item embeddings)      │
 └───────┬──────────┘                   │  (Redis + S3 offline)   │
         │                              └──────────┬──────────────┘
         └──────────────────┬───────────────────────┘
                            ▼
                  ┌──────────────────────┐
                  │  Recommendation      │
                  │  Service             │
                  │  (Serving Layer)     │
                  └──────────┬───────────┘
                             │
              ┌──────────────┼───────────────┐
              ▼              ▼               ▼
        ┌──────────┐  ┌───────────┐  ┌───────────────┐
        │ Pre-comp │  │ ANN Index │  │  Fallback     │
        │ Recs     │  │ (Faiss /  │  │  (Trending /  │
        │ (Redis)  │  │  Pinecone)│  │   Popular)    │
        └──────────┘  └───────────┘  └───────────────┘
```

---

## 4. Recommendation Algorithms

### 4.1 Collaborative Filtering (CF) — "Users like you also liked"

**User-User CF:**
- Find users with similar purchase/view history.
- Recommend items liked by similar users.
- Problem: Doesn't scale to 500M users (O(N²) similarity computation).

**Item-Item CF (Preferred at scale):**
- Pre-compute item similarity offline: items co-purchased/co-viewed together.
- At query time: look up items similar to what the user recently viewed.
- Scales well: similarity computed offline; lookup is O(1).

```
Co-occurrence matrix:
  [ItemA, ItemB] co-viewed 10,000 times → similarity score = 0.87
  Stored as: similar_items:{itemId} → [(itemB, 0.87), (itemC, 0.82), ...]
```

**Matrix Factorization (ALS / SVD):**
- Decompose user-item interaction matrix into latent factor vectors.
- User vector (256-dim): represents user's taste
- Item vector (256-dim): represents item's characteristics
- Recommendation = items with highest dot-product with user vector.

```
User U's vector:     [0.2, 0.8, 0.1, ..., 0.6]   (256-dim)
Item A's vector:     [0.3, 0.7, 0.2, ..., 0.5]   (256-dim)
Relevance score  =   dot_product(U, A) = 0.91  ← recommend
```

### 4.2 Content-Based Filtering — "Because you viewed X"

- Item features: category, brand, price range, tags, description embeddings.
- User profile: weighted average of features of items the user interacted with.
- Recommend items whose feature vector is closest to the user profile vector.
- **Good for:** Cold-start for new users (uses item features, not history).

### 4.3 Deep Learning — Two-Tower Model (Production Standard)

```
     User Tower                    Item Tower
  ┌─────────────────┐          ┌─────────────────┐
  │ user_id         │          │ item_id         │
  │ age, location   │          │ category, price │
  │ past purchases  │──▶ 256D  │ brand, tags     │──▶ 256D
  │ session history │  vector  │ description emb │  vector
  └─────────────────┘          └─────────────────┘
           │                            │
           └────────── dot product ─────┘
                            │
                      Relevance Score
```

- Trained offline on billions of interaction events.
- At serving time: user vector looked up from Feature Store → ANN search over item vectors.
- **Industry standard:** Used by YouTube, Twitter, Pinterest, Airbnb.

### 4.4 Session-Based Recommendations (Real-Time)

For users in the current session (especially new/anonymous users):
- Track last **K items** viewed in session (K = 5–10).
- Use item-item similarity to recommend related items.
- Served from Redis (real-time session signals).

```
Session: User viewed [Phone A, Phone B, Phone Case X]
→ Recommend: similar phones, phone accessories
Real-time lookup: similar_items:{PhoneB} from Redis
```

---

## 5. Core Components

### 5.1 Event Ingestion Pipeline

All user interactions flow into Kafka:

| Event | Topic | Used For |
|---|---|---|
| `item.viewed` | `user-events` | Session recs, co-view matrix |
| `item.clicked` | `user-events` | Implicit feedback signal |
| `item.purchased` | `purchase-events` | Strong positive signal |
| `item.rated` | `rating-events` | Explicit feedback |
| `item.added_to_cart` | `user-events` | Positive signal |
| `item.returned` | `return-events` | Negative signal |

### 5.2 Batch Training Pipeline (Daily — Apache Spark)

```
S3 (raw interaction logs)
        │
        ▼
Spark Job: Data preprocessing
  - Filter bots / invalid events
  - Normalize ratings (implicit → explicit feedback)
  - Build interaction matrix (userId × itemId)
        │
        ▼
Model Training:
  - ALS (Matrix Factorization) for CF
  - Two-Tower model training (TensorFlow / PyTorch)
  - Item-Item co-occurrence matrix
        │
        ▼
Generate:
  - User embedding vectors (500M × 256-dim) → Feature Store
  - Item embedding vectors (50M × 256-dim) → Feature Store + ANN Index
  - Pre-computed top-K recs per user → Redis
  - Item-Item similarity index → Redis
        │
        ▼
Deploy outputs to:
  - Feature Store (Redis + S3)
  - FAISS / Pinecone ANN Index (item vectors)
  - Pre-computed recs cache (Redis)
```

### 5.3 Feature Store

Stores user & item representations for fast serving:

| Feature | Storage | Latency |
|---|---|---|
| User embedding (256-dim) | Redis HASH | < 1 ms |
| Item embedding (256-dim) | Redis HASH | < 1 ms |
| User recent views (session) | Redis LIST | < 1 ms |
| User purchase history (long-term) | DynamoDB | < 5 ms |
| Item metadata (category, price) | Redis HASH | < 1 ms |
| Pre-computed top-K recs | Redis LIST | < 1 ms |

### 5.4 Recommendation Serving Layer

**Fast path (< 10ms): Pre-computed recommendations**
```
GET /v1/recommendations?userId=U123&context=homepage&limit=20

1. Fetch pre-computed recs from Redis:
   Key: rec:{userId}:homepage → [itemA, itemB, ..., itemT]

2. If found → return (< 5ms)
3. If not found → fallback to ANN path
```

**Medium path (< 50ms): Real-time ANN lookup**
```
1. Fetch user embedding from Feature Store (Redis)
2. Fetch session signals (last 5 viewed items)
3. Combine: user_vector = 0.7 × user_embedding + 0.3 × avg(session_item_vectors)
4. ANN query: find top-K nearest item vectors in FAISS index
5. Filter: remove already-purchased items, out-of-stock items
6. Re-rank: apply business rules (boost high-margin items, exclude competitors)
7. Return top 20
```

**Fallback path: Trending / Popular**
```
If user has no history (new user / cold start):
1. Return globally trending items (last 24h)
2. Or category-trending (if we know user's location/category interest)
3. Stored in Redis, refreshed every 15 min
```

### 5.5 Approximate Nearest Neighbor (ANN) Index

- **FAISS** (Facebook AI Similarity Search) — open source, in-memory ANN.
- **Pinecone** — managed vector DB (production alternative).
- Index: 50M item vectors (256-dim), ~51GB in memory.
- Query: find top-100 nearest items in < 5ms.
- Rebuilt daily after batch training.

### 5.6 Re-ranking Layer

After ANN retrieval of top-100 candidates, apply re-ranking:

```
Score = base_relevance_score
      + diversity_bonus     (avoid 20 identical items)
      + freshness_boost     (boost newer items)
      + margin_boost        (business objective: high-margin items)
      - recently_viewed_penalty  (don't show items just viewed)
      - out_of_stock_penalty
```

---

## 6. Cold Start Problem

| Scenario | Strategy |
|---|---|
| **New user (no history)** | Show trending + category-popular; use location/demographics if available |
| **New user (session signals)** | First few clicks → item-item similarity for instant personalization |
| **New item (no interactions)** | Content-based: embed item description/category → place in ANN index |
| **New item (cold → warm)** | After 10+ interactions, include in CF model at next daily batch |

---

## 7. A/B Testing Framework

```
User request arrives
        │
        ▼
Experiment Service:
  - Hash(userId) → assigns user to experiment bucket
  - Bucket A (50%): Algorithm v1 (ALS CF)
  - Bucket B (50%): Algorithm v2 (Two-Tower DL)
        │
        ▼
Recommendation served per bucket
        │
        ▼
Metrics tracked per bucket:
  - CTR (Click-Through Rate)
  - Add-to-Cart Rate
  - Conversion Rate
  - Revenue per session
        │
        ▼
After statistical significance → winner promoted to 100%
```

---

## 8. Data Flow — Homepage Recommendations

```
User U123 opens homepage

Client → GET /v1/recommendations?userId=U123&context=homepage
                  │
                  ▼
        Recommendation Service
                  │
         Check Redis pre-computed:
         Key: rec:U123:homepage
                  │
          ┌───────┴───────────────┐
     Cache Hit                Cache Miss
          │                        │
    Return 20 recs           Fetch user_embedding from Redis
    (< 5ms)                       │
                            ANN search in FAISS
                            top-100 candidates
                                   │
                            Re-rank (diversity, freshness)
                                   │
                            Filter (OOS, viewed, purchased)
                                   │
                            Store in Redis (TTL: 1hr)
                                   │
                            Return top-20
```

---

## 9. Scalability

| Layer | Scaling Strategy |
|---|---|
| **Kafka** | Partitioned by userId; scale partitions for peak event ingestion |
| **Batch Training** | Spark on EMR auto-scales; daily job runs in < 4 hours |
| **Feature Store (Redis)** | Cluster mode; sharded by userId; read replicas for high throughput |
| **ANN Index (FAISS)** | Replicated across Recommendation Service nodes; each node hosts full index (51GB) |
| **Recommendation Service** | Stateless; horizontal scale behind load balancer |
| **Pre-computed cache** | Redis; covers 90%+ of requests; only < 10% need ANN path |

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Batch vs Real-time training** | Daily batch (Spark) | Simpler ops; recs up to 1 day stale |
| **Real-time signals** | Session events via Kafka → Redis | Fresh session context without model retraining |
| **Algorithm** | Two-Tower + Item-Item CF | DL quality + fast item similarity for session recs |
| **ANN index** | FAISS (in-memory per node) | Ultra-fast queries; 51GB memory per node |
| **Pre-computation** | Top-K recs per user daily | 90% cache hit; staleness acceptable for most users |
| **Fallback** | Trending items | Always something to show; never blank page |
| **Re-ranking** | Business rules layer | Balances relevance with business objectives |

---

## 11. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **CTR** | Click-through rate per surface (homepage/PDP/cart) |
| **Add-to-cart rate** | % of recommended items added to cart |
| **Cache hit rate** | Pre-computed recs Redis hit rate; target > 90% |
| **ANN query latency** | P99 FAISS query time; alert if > 20ms |
| **Model staleness** | Alert if daily batch job fails / recs > 25h old |
| **Cold start ratio** | % of users served trending fallback (new user growth indicator) |

---

## 12. Future Enhancements
- **Online Learning** — continuously update user vectors with streaming gradient descent (no daily batch needed).
- **Contextual Bandits** — balance explore (new items) vs exploit (known preferences) for each user.
- **Multi-objective ranking** — jointly optimize CTR, revenue, diversity, and serendipity.
- **Cross-domain recommendations** — recommend based on behavior across categories (bought running shoes → recommend fitness tracker).
- **Explainable recommendations** — "Because you bought X" labels for trust and transparency.

---

*Document prepared for SDE 3 system design interviews. Focus areas: ML pipeline architecture, Two-Tower models, ANN indexing, real-time vs batch trade-offs, cold start handling, and A/B testing.*
