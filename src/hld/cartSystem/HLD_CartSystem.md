# 🛒 High-Level Design (HLD) — Cart System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Scalability, Reliability, Consistency, Distributed Systems Trade-offs

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **add**, **update quantity**, and **remove** items from their cart.
- Users can **view** the current state of their cart.
- Cart is **user-specific** (authenticated) and supports **guest carts** (session-based).
- Guest cart is **merged** into user cart upon login/registration.
- Cart enforces **inventory checks** (item availability, max quantity).
- Cart is **persisted** — survives page refresh and browser close.
- Support **promo codes / coupons** applied to the cart.
- Cart reflects **real-time price changes** from the catalog.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Read Latency** | < 50 ms (P99) |
| **Write Latency** | < 100 ms (P99) |
| **Availability** | 99.99% (cart reads/writes must not block checkout) |
| **Consistency** | Eventual for price, Strong for quantity/inventory |
| **Scale** | 100M+ DAU, 10M concurrent carts |
| **Durability** | Cart data must not be lost |

### 1.3 Out of Scope
- Payment processing
- Order placement / checkout flow (separate service)
- Wishlist management

---

## 2. Capacity Estimation

```
DAU                   = 100 million
Peak Cart Ops/sec     ≈ 100M × 10 ops/day / 86400 ≈ ~12,000 RPS (peak ~50,000 RPS)
Cart Size (avg)       = 5 items × ~200 bytes = ~1 KB per cart
Total Cart Storage    = 100M × 1 KB = ~100 GB (hot data, last 30 days)
Cache Hit Rate Target = > 90%
```

---

## 3. High-Level Architecture

```
                         ┌──────────────┐
   Mobile / Web  ──────▶ │  API Gateway │ (Auth, Rate Limiting, Routing)
                         └──────┬───────┘
                                │
                    ┌───────────▼───────────┐
                    │    Cart Service        │
                    │  (Stateless, Auth)     │
                    └──┬────────┬───────────┘
                       │        │
           ┌───────────▼──┐  ┌──▼──────────────┐
           │  Cart Cache  │  │   Cart DB         │
           │  (Redis)     │  │  (DynamoDB/Cassandra)│
           └──────────────┘  └──────────────────┘
                       │
          ┌────────────┼────────────────┐
          │            │                │
    ┌─────▼────┐ ┌─────▼──────┐ ┌──────▼──────┐
    │ Inventory│ │  Catalog   │ │   Promo /   │
    │ Service  │ │  Service   │ │  Coupon Svc │
    └──────────┘ └────────────┘ └─────────────┘
                       │
               ┌───────▼──────┐
               │  Event Bus   │ (Kafka)
               │  (Cart Events│
               │  → Analytics,│
               │  Realtime,   │
               │  Checkout)   │
               └──────────────┘
```

---

## 4. Core Components

### 4.1 API Gateway
- **Authentication & Authorization** — validates JWT tokens; generates guest session tokens.
- **Rate Limiting** — per-user, per-IP (prevent cart abuse / scraping).
- **Routing** — routes to Cart Service cluster.

### 4.2 Cart Service (Stateless Microservice)
Owns all cart business logic.

**Key APIs:**

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/v1/cart/{userId}` | Fetch cart with enriched product details |
| `POST` | `/v1/cart/{userId}/items` | Add item to cart |
| `PATCH` | `/v1/cart/{userId}/items/{itemId}` | Update quantity |
| `DELETE` | `/v1/cart/{userId}/items/{itemId}` | Remove item |
| `POST` | `/v1/cart/merge` | Merge guest cart into user cart on login |
| `POST` | `/v1/cart/{userId}/coupon` | Apply/remove coupon |

**Responsibilities:**
- Read-through / write-back to **Redis cache**.
- Validate item availability with **Inventory Service** (async for reads, sync for writes).
- Fetch latest prices from **Catalog Service** at read time (price not stored, always fetched live).
- Publish `CartItemAdded`, `CartItemRemoved`, `CartViewed` events to **Kafka**.

### 4.3 Cart Storage — Redis (Primary Cache)

- **Data Model:** `HASH` per cart keyed by `cart:{userId}`
  ```
  Key:   cart:user123
  Field: itemId → { productId, qty, addedAt, ... }
  TTL:   30 days (refreshed on access)
  ```
- **Write Strategy:** Write-through (write to Redis + async write to DB).
- **Eviction:** LRU + TTL (inactive carts evicted after 30 days).
- **Cluster:** Redis Cluster (sharded by userId) with **read replicas** for high throughput.

### 4.4 Cart Storage — Persistent DB (DynamoDB / Cassandra)

Used as the **source of truth** for cart data (durable, survives Redis failures).

**DynamoDB Table Design:**
```
Table: Carts
PK: userId  (partition key)
SK: itemId  (sort key)

Attributes: productId, qty, addedAt, updatedAt, guestSessionId (nullable)

GSI: guestSessionId-index  (for guest cart lookup/merge)
```
- **On cache miss:** Cart Service fetches from DB and **warm-ups** Redis.
- **Consistency:** DynamoDB strong reads for checkout; eventually consistent for cart views.

### 4.5 Inventory Service Integration

| Operation | Strategy | Reason |
|---|---|---|
| View Cart | Async (best-effort check) | Low latency — show cart fast, warn if OOS |
| Add to Cart | Synchronous reserve/soft check | Prevent adding clearly unavailable items |
| Checkout | Hard inventory lock (two-phase) | Correctness critical |

- Uses **soft reservation** pattern for cart additions (not a hard inventory lock).
- Inventory status pushed via **Kafka** events → cart service subscribes and marks items as `OUT_OF_STOCK` in Redis.

### 4.6 Catalog Service Integration

- **Price is NOT stored in cart.** Always fetched live from Catalog Service at cart read time.
- Prices are cached in a **local in-memory cache** in Cart Service (TTL: 5 min) to reduce Catalog calls.
- Price freshness vs latency trade-off: acceptable for cart views; exact price recalculated at checkout.

### 4.7 Promo / Coupon Service

- Validates coupon code applicability (user eligibility, expiry, min cart value).
- Returns discount details; Cart Service stores `{ couponCode, discountType, discountValue }` in cart.
- Coupon is **re-validated at checkout** to prevent stale coupons.

### 4.8 Event Bus (Kafka)

| Event | Producer | Consumers |
|---|---|---|
| `CartItemAdded` | Cart Service | Analytics, Recommendations |
| `CartItemRemoved` | Cart Service | Analytics |
| `CartAbandoned` | Cart Service (scheduled) | Notification Service (retargeting) |
| `InventoryUpdated` | Inventory Service | Cart Service (mark OOS) |

---

## 5. Guest Cart & Merge Flow

```
Guest User  ──adds items──▶  cart:guest:{sessionId}  (Redis, 7-day TTL)
                                        │
                               User logs in / signs up
                                        │
                               Cart Merge API called
                                        │
                       Merge Strategy (configurable):
                       ┌─────────────────────────────┐
                       │ If item in BOTH carts:       │
                       │   → Take MAX(qty) (default)  │
                       │ If item in GUEST only:       │
                       │   → Add to user cart         │
                       │ If item in USER only:        │
                       │   → Keep unchanged           │
                       └─────────────────────────────┘
                                        │
                               Delete guest cart
```

---

## 6. Data Flow — Add to Cart

```
Client
  │── POST /v1/cart/{userId}/items ──▶ API Gateway
                                             │ (Auth + Rate limit)
                                             ▼
                                       Cart Service
                                             │
                              ┌──────────────┼──────────────┐
                              ▼              ▼              ▼
                       Inventory Svc    Catalog Svc    Coupon Svc
                       (soft check)     (verify item    (if coupon
                              │          exists)         applied)
                              └──────────────┬────────────┘
                                             ▼
                                Write to Redis (HASH → item)
                                             │
                                    Async write to DynamoDB
                                             │
                                Publish CartItemAdded to Kafka
                                             │
                                     Return 200 OK to Client
```

---

## 7. Scalability & Reliability

### 7.1 Horizontal Scaling
- Cart Service is **stateless** — scale out with load balancer (consistent hash on userId ensures Redis locality).
- Redis Cluster: **sharded** across nodes; rehashing handled by cluster slots.
- DynamoDB: Auto-scaling; partition key = `userId` distributes load well.

### 7.2 Caching Strategy

| Layer | Cache | TTL | Strategy |
|---|---|---|---|
| Cart data | Redis | 30 days | Write-through |
| Product prices | In-process (Guava/Caffeine) | 5 min | Read-through |
| Inventory status | Redis | 2 min | Event-invalidated |

### 7.3 Failure Handling

| Failure | Handling |
|---|---|
| Redis node down | Fallback to DynamoDB read; cart ops degrade gracefully |
| Inventory Svc down | Add to cart allowed with warning banner (soft degradation) |
| Catalog Svc down | Serve cart with last cached price + staleness indicator |
| DB write failure | Retry with exponential backoff; dead-letter queue |

### 7.4 Idempotency
- All write APIs accept an **idempotency key** (client-generated UUID) to prevent duplicate operations on retry.
- Cart Service checks Redis for previously processed idempotency keys (TTL: 24 hours).

---

## 8. Consistency Considerations

| Concern | Approach |
|---|---|
| Cart price drift | Prices fetched live at read-time; re-validated at checkout |
| Inventory oversell | Soft check at "add-to-cart"; hard lock only at checkout |
| Concurrent cart updates | Redis `HSET` + Lua scripts for atomic updates; optimistic locking in DB |
| Guest → User merge conflicts | Deterministic merge strategy (max-qty wins) |

---

## 9. Security

- **Authentication:** JWT / OAuth 2.0 for logged-in users; signed session tokens for guests.
- **Authorization:** Cart service validates `userId` in JWT matches cart being accessed (no IDOR).
- **Rate Limiting:** Per-user limits (e.g., 100 cart writes/min) to prevent abuse.
- **Input Validation:** Strict schema validation on all cart payloads (quantity limits, allowed product IDs).
- **PII:** No sensitive PII stored in cart; user identity resolved via userId only.

---

## 10. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Latency** | P50/P99 for GET and POST cart APIs |
| **Error Rate** | 4xx/5xx rate per endpoint |
| **Cache Hit Rate** | Redis hit rate > 90%; alert if < 80% |
| **Cart Abandonment** | % of carts not converted to orders (business KPI) |
| **Queue Lag** | Kafka consumer lag for cart event processors |
| **DB Replication Lag** | DynamoDB global table replication lag |

**Tooling:** Prometheus + Grafana dashboards, distributed tracing via Jaeger/OpenTelemetry, structured logging (ELK stack).

---

## 11. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Price storage** | Not stored; fetched live | Always fresh price vs. extra Catalog call per read |
| **Inventory check** | Soft at add-to-cart, hard at checkout | Better UX vs. tiny risk of oversell (handled at checkout) |
| **DB choice** | DynamoDB (NoSQL) | Scales to 100M+ carts easily; no complex joins needed |
| **Cache-aside vs write-through** | Write-through | Consistency over performance (avoids cache miss on write) |
| **Guest cart storage** | Redis only (short TTL) | Lightweight; merged into user cart on login |
| **Event-driven OOS update** | Kafka consumer | Decoupled; eventual consistency acceptable for cart views |

---

## 12. Future Enhancements
- **Shared Carts** — collaborative shopping lists (e.g., family members share a cart).
- **Save For Later / Wishlist** — move items out of active cart without removing.
- **Dynamic Pricing** — personalized pricing reflected in cart (requires tighter Catalog integration).
- **ML-powered Cart Recommendations** — "Frequently bought together" items shown in cart.
- **Multi-region Active-Active** — geo-distributed cart writes for global users.

---

*Document prepared for SDE 3 system design interviews. Focus areas: distributed caching, eventual consistency, service decomposition, and graceful degradation.*
