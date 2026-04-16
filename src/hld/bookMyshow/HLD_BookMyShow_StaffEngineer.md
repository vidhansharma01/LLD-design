# 🎬 High-Level Design — BookMyShow (Staff Engineer Level)
> **Level:** Staff / Principal Engineer
> **Focus:** Seat locking concurrency, distributed consistency, flash sale architecture,
> real-time seat map, payment atomicity, database schema, caching strategy, fault tolerance

---

## Table of Contents
1. [Requirements](#1-requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Booking State Machine](#4-booking-state-machine)
5. [Core Components Deep Dive](#5-core-components-deep-dive)
   - 5.1 API Gateway & Load Balancing
   - 5.2 Content & Discovery Service
   - 5.3 Seat Map Service
   - 5.4 Seat Locking Service ← **most critical**
   - 5.5 Booking Service
   - 5.6 Payment Service
   - 5.7 Notification Service
   - 5.8 Flash Sale & Virtual Queue
6. [Database Design](#6-database-design)
7. [Caching Strategy](#7-caching-strategy)
8. [Real-Time Seat Map (WebSocket)](#8-real-time-seat-map)
9. [Concurrency Deep Dive](#9-concurrency-deep-dive)
10. [Scalability](#10-scalability)
11. [Reliability & Fault Tolerance](#11-reliability--fault-tolerance)
12. [Observability](#12-observability)
13. [Security](#13-security)
14. [Trade-offs & Alternatives](#14-trade-offs--alternatives)

---

## 1. Requirements

### 1.1 Functional Requirements

**Core user flows:**
- Search movies/events by city, date, genre, language, format (2D/3D/IMAX).
- View shows for a selected movie — theatre, screen, timing, pricing by category.
- View an **interactive seat map** — real-time seat availability (green/yellow/red).
- **Select and hold seats** for 10 minutes while completing payment.
- Support multiple payment modes: UPI, Card, Net Banking, Wallet, Pay Later.
- Generate **booking confirmation** with encrypted QR code for physical entry.
- **Cancel booking** with tiered refund policy (time-based).
- View booking history, download tickets.
- **Flash sale support** — 10M+ concurrent users for blockbuster premieres.
- **Food pre-ordering** tied to a booking (F&B add-ons at checkout).

**Theatre/Admin flows (out of scope for this design):**
- Theatre partner onboarding, screen/show management.
- Show schedule CRUD, pricing configuration.

### 1.2 Non-Functional Requirements

| Property | Target | Reason |
|---|---|---|
| **Seat map load** | < 200 ms P99 | Users expect instant seat visualization |
| **Seat lock latency** | < 500 ms P99 | Any lag = user anxiety, abandonment |
| **Payment end-to-end** | < 3 sec P99 | Industry standard; longer = drop-off |
| **Zero double bookings** | Absolute guarantee | Business correctness — non-negotiable |
| **Availability (browse)** | 99.99% | Browsing should never go down |
| **Availability (booking)** | 99.9% | Brief degradation acceptable |
| **Peak concurrent users** | 10M (flash sale) | Avengers/IPL premiere scale |
| **Peak booking TPS** | 100K bookings/sec | ~10× above normal peak |
| **Seat count freshness** | Real-time (< 1 sec staleness) | "50 seats available" must be accurate |
| **Geo redundancy** | 2 active datacenters | RPO < 1 min, RTO < 5 min |

### 1.3 Out of Scope
- Movie content streaming / trailers
- Partner theatre management portal
- Loyalty & rewards engine
- Advertisement serving

---

## 2. Capacity Estimation

```
── SCALE ──────────────────────────────────────────────────────────────
Cities            = 300 (India-wide)
Theatres          = 10,000
Screens/theatre   = avg 4  →  40,000 screens total
Shows/screen/day  = 5      →  200,000 shows/day
Seats/screen      = avg 250 →  50M seat-show slots/day

── TRAFFIC ────────────────────────────────────────────────────────────
Normal bookings/day     = 1 million       →  ~12 bookings/sec avg
Peak bookings/sec       = 100,000         →  Avengers day
Seat map reads          = 100× bookings   →  100M reads/day
                                          →  ~1,150 reads/sec avg, ~1M/sec peak

── STORAGE ────────────────────────────────────────────────────────────
Booking record size     = ~1 KB (all fields)
Bookings/day            = 1M × 1 KB = ~1 GB/day
5-year retention        = 1 GB × 365 × 5 = ~1.8 TB (cold archival)

Active seat maps in Redis:
  Active shows at any moment = 200K shows/day × (avg 2hr window / 24hr) ≈ ~16,000 shows
  16,000 × 250 seats × 50 bytes/seat = ~200 MB in Redis (trivially small)

── BANDWIDTH ──────────────────────────────────────────────────────────
Seat map payload per user = ~5 KB (250 seats × 20 bytes JSON)
Peak: 1M req/sec × 5 KB = ~5 GB/sec → must be served from Redis/CDN, NOT DB
```

---

## 3. High-Level Architecture

```
                          ┌──────────────────────────────────────┐
                          │         CLIENT LAYER                 │
                          │  Mobile App / Web Browser / PWA      │
                          └───────────────┬──────────────────────┘
                                          │ HTTPS
                          ┌───────────────▼──────────────────────┐
                          │    CDN (CloudFront / Cloudflare)      │
                          │  Static assets, movie posters, JS/CSS │
                          └───────────────┬──────────────────────┘
                                          │ Cache miss only
                          ┌───────────────▼──────────────────────┐
                          │           API GATEWAY                 │
                          │  Rate limiting, Auth (JWT), Routing   │
                          │  SSL termination, Request coalescing  │
                          └──────┬──────────────┬────────────────┘
                                 │              │
               ┌─────────────────▼──┐    ┌──────▼──────────────────┐
               │  Content Service   │    │    Booking Service       │
               │  (browse, search,  │    │  (lock, pay, confirm,    │
               │   seat map serve)  │    │   cancel, QR generate)   │
               └────────┬───────────┘    └──────┬──────────────────┘
                        │                       │
          ┌─────────────▼──────────┐   ┌────────▼───────────────────┐
          │  Redis Cluster          │   │   Seat Lock Service        │
          │  (seat maps, avail      │   │   (Redis atomic ops        │
          │   counters, show meta)  │   │    + Lua scripts)          │
          └─────────────┬──────────┘   └────────┬───────────────────┘
                        │                       │
          ┌─────────────▼──────────────────────▼────────────────────┐
          │               PostgreSQL (Primary + Read Replicas)        │
          │   shows, bookings, seat_inventory, payments, users        │
          └────────────────────────┬────────────────────────────────┘
                                   │
      ┌─────────────────┬──────────▼──────────┬────────────────────┐
      ▼                 ▼                      ▼                    ▼
Payment Service   Notification Svc      Virtual Queue         Analytics
(Razorpay/Stripe) (SMS/Email/Push)   (Flash Sale handler)  (Kafka→Flink)
```

---

## 4. Booking State Machine

Understanding the state machine is essential before designing any component.
Every transition must be **atomic** — partial transitions cause double bookings or ghost locks.

```
                        ┌───────────────┐
                        │  SEAT_VIEWED  │  User opens seat map
                        └──────┬────────┘
                               │ User selects seats & clicks "Proceed"
                        ┌──────▼────────┐
                        │  SEAT_SELECTED│  Client-side only, no server state yet
                        └──────┬────────┘
                               │ POST /lock-seats (server call)
                        ┌──────▼────────┐       timeout (10 min) OR
                        │    LOCKED     │──────────────────────────────▶ AVAILABLE
                        └──────┬────────┘       user clicks "Cancel"
                               │ POST /initiate-payment
                        ┌──────▼────────┐       payment fails /
                        │ PAYMENT_INIT  │──────────────────────────────▶ PAYMENT_FAILED
                        └──────┬────────┘       no webhook in 10 min
                               │ Gateway webhook: PaymentSuccess
                        ┌──────▼────────┐
                        │   CONFIRMED   │──▶ QR generated, notification sent
                        └──────┬────────┘
                               │ User cancels (before show time)
                        ┌──────▼────────┐
                        │   CANCELLED   │──▶ Refund initiated per policy
                        └───────────────┘
                               │ Ticket scanned at gate
                        ┌──────▼────────┐
                        │     USED      │  Prevent QR reuse
                        └───────────────┘

Key invariants:
  - LOCKED → AVAILABLE must release Redis lock AND update seat_inventory
  - CONFIRMED is only written AFTER payment gateway confirms AND DB transaction commits
  - CANCELLED only allowed if show start time - now() > cancellation_cutoff (e.g., 2 hr)
  - USED is a terminal state — no transitions out
```

---

## 5. Core Components Deep Dive

### 5.1 API Gateway & Load Balancing

The API Gateway is the single entry point for all clients. At staff level, you must design it deliberately.

**Responsibilities:**
```
1. SSL Termination   — decrypt HTTPS once at edge; internal services use HTTP
2. Authentication    — validate JWT token on every request (public-key verification, sub-ms)
3. Authorization     — route /admin/* only for theatre partners
4. Rate Limiting     — per user, per IP, per endpoint:
     /search         → 100 req/min per userId
     /lock-seats     → 5 req/min per userId (prevent seat hoarders)
     /pay            → 3 req/min per userId
5. Request Routing   → Content Service, Booking Service, Payment Service
6. Request Coalescing→ deduplicate identical concurrent requests (seat map)
7. Observability     → inject trace-id, log request/response metadata
```

**Why request coalescing matters for seat maps:**
During a flash sale, 50,000 users may simultaneously request the seat map for `showId=X`. Without coalescing, 50,000 identical Redis reads fire. With coalescing, the gateway batches them and sends a single Redis `HGETALL seatmap:{showId}` — the response fans out to all 50K pending connections. This reduces Redis load by 99.99%.

---

### 5.2 Content & Discovery Service

Serves movie listings, show timings, theatre details. This is the **most read-heavy service** (~1M req/sec peak) — it must be nearly entirely cache-served.

**Critical design principle: Separate static from dynamic data.**

> Never cache `availableCount` inside the same key as static show metadata.
> `availableCount` changes every few milliseconds during peak. Static metadata
> (theatre name, timing, price) never changes once a show is created.

**Cache architecture:**

```
Layer 1: CDN (CloudFront)
  What: Movie poster images, trailer thumbnails, static HTML/JS/CSS
  TTL:  24 hr (content-addressed URLs with hash in filename → infinite TTL)
  Hit rate target: 99%

Layer 2: Redis Cluster (show listings)

  Key: shows:{movieId}:{cityId}:{date}
  Type: Redis LIST of showIds only
  TTL:  2 hours
  Why:  List of shows for a given movie/city/date is stable.
        showIds are immutable once created.
        Never embed availableCount here.

  Key: show:meta:{showId}
  Type: Redis HASH
  Fields: { theatreId, screenId, theatreName, screenName,
            startTime, endTime, priceMap (JSON), language, format }
  TTL:  2 hours
  Why:  Show metadata is immutable after creation.
        Safe to cache aggressively.

  Key: avail:{showId}
  Type: Redis STRING (integer counter)
  Value: current available seat slots (not locked, not booked)
  TTL:  None (counter is live; explicitly DEL'd when show ends)
  Updated by:
    DECRBY avail:{showId} {n}  on seat LOCK   (seats removed from pool)
    INCRBY avail:{showId} {n}  on lock EXPIRE (seats returned to pool)
    INCRBY avail:{showId} {n}  on CANCELLATION
  Why:  Redis integer increment is atomic — zero staleness, zero contention.
        avail:X = 0 means sold out → stop accepting lock requests immediately.

Layer 3: PostgreSQL (source of truth — only on cache miss)
```

**Response assembly flow:**
```
GET /shows?movieId=X&cityId=Y&date=Z

Step 1: LRANGE shows:{movieId}:{cityId}:{date} 0 -1
        → [showId_1, showId_2, showId_3, showId_4]

Step 2: Redis PIPELINE (single round trip):
          HGETALL show:meta:{showId_1}
          HGETALL show:meta:{showId_2}
          HGETALL show:meta:{showId_3}
          HGETALL show:meta:{showId_4}
          GET avail:{showId_1}
          GET avail:{showId_2}
          GET avail:{showId_3}
          GET avail:{showId_4}

Step 3: Merge metadata + live count per show.
        Mark shows where avail:{showId} == 0 as "Sold Out".
        Mark shows where avail:{showId} < 10 as "Filling Fast".

Step 4: Return enriched show list. Entire response: < 5 ms.
```

**Elasticsearch for search:**
```
Index: movies
  Fields: title, director, cast, genre, language, rating, releaseDate
  Settings: hindi analyzer for regional titles

Index: theatres
  Fields: name, city, address, amenities, geo_location (lat/lon)
  Query: geo_distance + full-text match

Use case examples:
  "Avengers Mumbai"         → match title + city filter
  "IMAX shows near me"      → geo_distance(5km) + format=IMAX filter
  "Hindi movies this weekend"→ language=hindi + date=weekend range
```

---

### 5.3 Seat Map Service

The seat map is the most **read-intensive** UI element — every user who opens a show page hits it.

**Data model for a seat:**
```
A seat has:
  seatId      = "A1" (row A, column 1)
  category    = PREMIUM | GOLD | SILVER
  price       = ₹350
  status      = AVAILABLE | LOCKED | BOOKED | UNAVAILABLE
  lockedBy    = userId (if LOCKED)
  lockedUntil = epoch ms (if LOCKED)
```

**Redis HASH per show — entire seat map in one call:**
```
Key:   seatmap:{showId}
Type:  Redis HASH
Field: seatId (e.g., "A1")
Value: encoded status string

Encoding:
  AVAILABLE   → "A"
  LOCKED      → "L:{userId}:{lockUntilEpoch}"
  BOOKED      → "B"
  UNAVAILABLE → "U"

Commands:
  HGETALL seatmap:{showId}              → full seat map, < 1ms (< 15 KB for 250 seats)
  HGET    seatmap:{showId} A1           → single seat status
  HSET    seatmap:{showId} A1 "L:u1:..." → lock a seat
  HMSET   seatmap:{showId} A1 B A2 B    → mark multiple seats as BOOKED post-payment
```

**Initialization:**
When a show is created, the Seat Map Service initializes Redis:
```
HSET seatmap:{showId}
  A1 A  A2 A  A3 A  ...  A20 A
  B1 A  B2 A  ...
  ... (250 seats total, all set to "A")
EXPIRE seatmap:{showId} 86400   → expire 24hr after show start (data cleanup)
SET avail:{showId} 250          → availability counter
```

**Serving the seat map to clients:**
```
GET /seatmap/{showId}

Step 1: HGETALL seatmap:{showId}
        → Returns all 250 seat entries as a flat map { seatId → status }
Step 2: Parse status strings → enrich with seat metadata (price, category)
        Seat metadata is static → cached in show:meta:{showId}.priceMap
Step 3: Return {
          seats: [
            { seatId: "A1", row: "A", col: 1, status: "AVAILABLE",
              category: "PREMIUM", price: 350 },
            { seatId: "A2", ..., status: "LOCKED" },
            ...
          ]
        }

Latency: < 5 ms (single Redis HGETALL + local enrichment)
```

**What about DB for seat map?**
The DB (`show_seat_inventory`) is the **source of truth** but NOT the read path.
- Redis is authoritative for real-time seat state.
- DB is authoritative for post-show audit, cancellation verification, reconciliation.
- On Redis node failure → rebuild seatmap:{showId} from `show_seat_inventory` DB (reconciliation job).

---

### 5.4 Seat Locking Service ← Most Critical Component

This is where the hardest distributed systems problem lives: **how do you prevent two users from booking the same seat simultaneously?**

#### 5.4.1 The Concurrency Problem

```
T=0:  User A selects seat A1, clicks "Proceed"
T=0:  User B selects seat A1, clicks "Proceed" (simultaneously)

Both send POST /lock-seats { showId: X, seats: ["A1"] }

Without proper concurrency control:
  Thread 1: reads A1 → AVAILABLE ✓
  Thread 2: reads A1 → AVAILABLE ✓   (reads before Thread 1 writes)
  Thread 1: writes A1 → LOCKED by UserA ✓
  Thread 2: writes A1 → LOCKED by UserB ✓   ← DOUBLE LOCK! → double booking!
```

**Solution:** **Redis Lua script** — Lua scripts execute atomically in Redis. No other command can interleave during Lua execution. This gives us a distributed mutex without any distributed lock manager.

#### 5.4.2 Lua Script — Atomic Multi-Seat Lock

```lua
-- KEYS[1]  = seatmap:{showId}
-- KEYS[2]  = avail:{showId}
-- ARGV[1]  = userId
-- ARGV[2]  = lockUntilEpoch (ms)
-- ARGV[3+] = seatIds to lock (e.g., "A1", "A2")

local seatmapKey  = KEYS[1]
local availKey    = KEYS[2]
local userId      = ARGV[1]
local lockUntil   = ARGV[2]
local numSeats    = #ARGV - 2

-- STEP 1: Check all requested seats are AVAILABLE
-- In Redis Lua: HGET returns false (not nil) for missing fields.
-- A missing field = seat not initialized = treat as unavailable.
for i = 3, #ARGV do
    local seatId = ARGV[i]
    local val = redis.call('HGET', seatmapKey, seatId)
    if not val or val ~= 'A' then
        -- Return which seat failed and why
        return {'FAIL', seatId, val or 'NOT_INITIALIZED'}
    end
end

-- STEP 2: Check sufficient inventory in availability counter
local avail = tonumber(redis.call('GET', availKey))
if not avail or avail < numSeats then
    return {'FAIL', 'avail', tostring(avail or 0)}
end

-- STEP 3: All clear — lock all seats atomically
for i = 3, #ARGV do
    local seatId = ARGV[i]
    redis.call('HSET', seatmapKey, seatId,
        'L:' .. userId .. ':' .. lockUntil)
end

-- STEP 4: Decrement availability counter
redis.call('DECRBY', availKey, numSeats)

return {'OK', numSeats}
```

**Why this is correct:**
- Lua scripts run single-threaded in Redis — no interleaving possible.
- Steps 1→4 are indivisible: read-check → write is atomic.
- Returns failure reason for observability (which seat was taken, by whom).

#### 5.4.3 Lock Expiry — Auto-Release After 10 Minutes

Redis HASH fields don't support individual TTLs. Two approaches:

**Approach A — Separate lock keys with TTL (simpler, recommended):**
```
Key:   lock:{showId}:{seatId}
Value: userId
TTL:   600 sec (10 minutes)

On lock:
  SET lock:{showId}:A1 {userId} NX EX 600
  SET lock:{showId}:A2 {userId} NX EX 600

On Redis key expiry:
  → Redis key-space notification fires (config: 'Ex' events)
  → Seat Expiry Consumer receives __keyevent@0__:expired for lock:{showId}:A1
  → Consumer: HSET seatmap:{showId} A1 "A"   (mark AVAILABLE in seat map)
              INCRBY avail:{showId} 1          (restore count)
              Publish SeatReleased event → WebSocket push to seat map viewers
```

**Approach B — Background TTL job (fallback / belt-and-suspenders):**
```
Every 30 seconds, a background job:
  1. Scans all LOCKED entries in Redis seatmaps
  2. Checks: if now() > lockUntilEpoch → seat has expired
  3. Releases expired seats: HSET → "A", INCRBY avail, WebSocket notify
  4. Also sweeps DB: locks older than 15 min → AVAILABILITY restored in show_seat_inventory
```

**Production decision:** Use **both** — Approach A for fast auto-expiry, Approach B as a reconciliation fallback for missed events.

#### 5.4.4 Lock Extension

If payment is taking longer than expected (slow gateway, user double-checks), extend the lock before it expires:

```
At T=9 min (1 min before expiry):
  Booking Service checks: is payment still in progress?
  If yes:
    SET lock:{showId}:A1 {userId} XX KEEPTTL
    → XX = only update if key exists (i.e., lock is still ours — not yet stolen)
    → KEEPTTL = preserve remaining TTL (Redis 6.0+)
    → If returns nil: lock already expired + re-acquired by someone else → abort payment
  
  After payment succeeds:
    DEL lock:{showId}:A1 (explicit delete)
    HSET seatmap:{showId} A1 "B" (mark BOOKED)
    No INCRBY needed — counter was decremented at lock time, stays decremented
```

---

### 5.5 Booking Service

Orchestrates the full booking lifecycle — the most **state-heavy** service.

#### 5.5.1 Lock Seats API

```
POST /v1/bookings/lock
{
  "showId": "uuid",
  "userId": "uuid",
  "seats": ["A1", "A2"],
  "foodOrders": [{ "itemId": "burger", "qty": 2, "price": 199 }]
}

Flow:
1. Validate request (auth, rate limit, show exists, show hasn't started)
2. Check avail:{showId} > 0 (fast pre-check before Lua — avoid Lua invocation on sold-out)
3. Execute Lua lock script (atomic)
   → On FAIL: return 409 { "error": "SEAT_UNAVAILABLE", "seatId": "A1" }
   → On OK: proceed
4. Write to PostgreSQL (bookings table):
   INSERT INTO bookings (booking_id, user_id, show_id, seats, status, food_orders, locked_at)
   VALUES (gen_random_uuid(), ?, ?, ?, 'LOCKED', ?, now())
5. Publish BookingLocked event to Kafka
   → Consumed by: WebSocket Service (notify other seat map viewers)
6. Return 201 {
     "bookingId": "uuid",
     "seats": ["A1", "A2"],
     "lockExpiresAt": "2024-01-01T10:10:00Z",
     "totalAmount": 750,
     "convenienceFee": 50
   }
```

#### 5.5.2 Confirm Booking (Post Payment)

This is where atomicity matters most — ensuring DB and Redis don't diverge.

```
POST /v1/bookings/{bookingId}/confirm
{ "paymentId": "pay_xxx", "gatewayOrderId": "order_xxx" }

Flow (two-phase: DB first, Redis after):

─── PHASE 1: PostgreSQL ACID Transaction ──────────────────────────────
BEGIN;

  -- Step 1: Optimistic check — seats must still be locked by this user in DB
  UPDATE show_seat_inventory
    SET status = 'BOOKED',
        booked_by = {userId},
        booking_id = {bookingId},
        booked_at = now()
  WHERE show_id = {showId}
    AND seat_id IN ('A1', 'A2')
    AND status = 'LOCKED'
    AND locked_by = {userId};

  -- Step 2: If not all rows updated → concurrent theft detected → ROLLBACK
  GET ROW COUNT;
  IF row_count != 2 THEN
    ROLLBACK;
    RAISE EXCEPTION 'CONCURRENT_SEAT_THEFT';
  END IF;

  -- Step 3: Confirm booking record
  UPDATE bookings
    SET status = 'CONFIRMED',
        payment_id = {paymentId},
        confirmed_at = now(),
        qr_code = {encryptedQR}
  WHERE booking_id = {bookingId}
    AND status = 'LOCKED';  -- idempotency guard

COMMIT;

─── PHASE 2: Redis Sync (after DB commit, outside transaction) ─────────
DEL lock:{showId}:A1
DEL lock:{showId}:A2
HMSET seatmap:{showId} A1 "B" A2 "B"
PUBLISH booking-events:{showId} '{"seatId":"A1","status":"BOOKED","userId":"..."}'
PUBLISH booking-events:{showId} '{"seatId":"A2","status":"BOOKED","userId":"..."}'

─── PHASE 3: Side Effects (async via Kafka) ────────────────────────────
Publish BookingConfirmed → {
  Notification Service: send SMS + email + push
  Analytics Service: record booking event
  Food Service: relay F&B order to theatre kitchen system
}
```

**Crash-recovery for Redis divergence:**
If service crashes between Phase 1 and Phase 2, Redis still shows seats as LOCKED but DB shows BOOKED.
A background reconciliation job (every 60 sec):
```
SELECT * FROM show_seat_inventory WHERE status = 'BOOKED' AND show_id IN (active_shows);
For each BOOKED seat:
  val = HGET seatmap:{showId} {seatId}
  IF val starts with 'L:' (still showing as locked in Redis):
    HSET seatmap:{showId} {seatId} "B"   → fix Redis to match DB truth
```

---

### 5.6 Payment Service

Payment is the most **failure-prone** external integration. A staff engineer must design for every failure mode.

#### 5.6.1 Payment Flow

```
User clicks "Pay Now"
      │
      ▼
Booking Service: POST to Payment Gateway (Razorpay / Stripe)
  Body: {
    amount: 80000,          // in paise (₹800.00)
    currency: "INR",
    order_id: bookingId,    // idempotency key
    customer: { name, email, phone },
    notes: { showId, bookingId }
  }
  → Gateway returns: { gatewayOrderId, paymentLink }
      │
      ▼
Client redirected to payment UI (gateway-hosted page or SDK)
User enters UPI/Card details
      │
      ▼
Gateway processes payment
      │ async webhook (POST /webhooks/payment)
      ▼
PaymentService receives webhook:
  1. Verify HMAC signature (tamper-proof webhook validation)
  2. Idempotency check: have we processed this payment_id before?
     (SELECT FROM payments WHERE payment_id = ?)
  3. If SUCCESS:
       Publish PaymentSuccess event to Kafka → BookingService confirms booking
  4. If FAILURE:
       Publish PaymentFailed event → BookingService releases seat locks
```

#### 5.6.2 Failure Modes & Mitigations

| Failure Scenario | Risk | Mitigation |
|---|---|---|
| **Webhook never arrives** | Booking stuck in PAYMENT_INIT | Polling job: check payment status from gateway API every 2 min |
| **Duplicate webhook** | Double-confirm (charge twice?) | Idempotency key = bookingId; `INSERT ... ON CONFLICT DO NOTHING` |
| **Webhook arrives after lock expiry** | Seat released, then payment succeeds | Reject confirmation: booking_id.status must be LOCKED at confirm time |
| **Payment gateway timeout** | Unknown payment state | Async poll gateway; do NOT release seats immediately |
| **Partial payment (wallet split)** | Complex reconciliation | Store each payment instrument separately; confirm only when total = amount |
| **Refund failure** | User charged, booking cancelled | Dead letter queue; manual reconciliation with finance team |

#### 5.6.3 Lock TTL vs Payment Timeout Alignment

```
Seat lock TTL           = 10 minutes  (Redis EX 600)
Payment window          = 10 minutes  (must complete payment within lock window)
Webhook wait timeout    = 10 minutes  (if no webhook, cancel)

At T=9 min (if payment still pending):
  Booking Service extends lock: SET lock:{showId}:{seatId} {userId} XX KEEPTTL
  This refreshes TTL to another 60 sec → gives gateway 1 more minute

At T=11 min (if still no webhook):
  Hard cancel: release locks, mark booking PAYMENT_TIMEOUT
  INCRBY avail:{showId} {numSeats}  → restore availability
  Publish SeatReleased event → WebSocket notifies other viewers

⚠️ Non-obvious bug: If you set payment timeout > lock TTL without extension:
  At T=10: Redis auto-releases seats (another user can grab them)
  At T=11: gateway webhook arrives with SUCCESS
  → You confirm a booking for seats now owned by someone else → DOUBLE BOOKING
```

---

### 5.7 Notification Service

Async, fire-and-forget — never on the booking critical path.

```
Kafka topic: booking-events
Consumer group: notification-service

On BookingConfirmed event:
  1. Generate QR Code:
       payload = base64(AES256_encrypt(
         bookingId + showId + seats + userId + timestamp,
         secretKey
       ))
       QR = renderQRCode(payload)
       Store QR in S3: qrs/{bookingId}.png

  2. Send SMS (via Twilio / MSG91):
       "Booking confirmed! Show: Avatar at PVR Andheri, 7:30 PM
        Seats: A1, A2. [Download ticket: short.ly/bms/abc123]"

  3. Send Email (via SendGrid):
       HTML template with seat map visual, theatre details, QR image

  4. Push notification (via FCM / APNs):
       "Your booking is confirmed! Tap to view tickets"

On BookingCancelled event:
  1. Initiate refund via Payment Gateway API
  2. Send cancellation confirmation with refund timeline
  3. Update booking status = CANCELLED in DB
  4. INCRBY avail:{showId} {numSeats}
  5. HMSET seatmap:{showId} {seats} → "A" (make available again)
  6. Publish SeatReleased for WebSocket

Idempotency:
  notification_log table tracks sent notifications by bookingId + channel
  On retry, skip if already sent (idempotent consumer pattern)
```

---

### 5.8 Flash Sale & Virtual Queue

The hardest operational challenge — 10M users for Avengers premiere.

#### 5.8.1 The Problem

```
Normal traffic:         12 bookings/sec
Flash sale peak:    100,000 bookings/sec     → 8,333× amplification

Without mitigation:
  - API Gateway overwhelmed → 503 for everyone
  - Redis: 10M simultaneous Lua script invocations → lock contention
  - PostgreSQL: 100K write TPS → connection pool exhausted
  → Everyone fails → terrible UX + revenue loss
```

#### 5.8.2 Virtual Waiting Room

```
User opens booking page for Avengers premiere
      │
      ▼
Are we in flash sale mode? (feature flag check — instant)
  Yes → Virtual Queue Entry
      │
      ▼
POST /queue/join { showId, userId }
  → ZADD queue:{showId} {timestamp} {userId}
    → Score = join timestamp → FIFO ordering
  → Return { position: 5832, estimatedWait: "4 min" }
      │
      ▼
Client polls or WebSocket: GET /queue/position/{userId}
  ZRANK queue:{showId} {userId} → current position
  Position < 5000 → grant booking access, issue booking_token (JWT, TTL 5 min)
      │
      ▼
Every 30 seconds, Queue Processor:
  ZPOPMIN queue:{showId} 5000   → dequeue next 5000 users
  Issue booking tokens to each → SSE/WebSocket push: "You're in!"
  → These users can now hit the booking API (token required)
      │
      ▼
Booking API validates booking_token before processing
  Users without token → 429 "Please wait in queue"
```

**Why Redis ZSET for queue?**
- ZADD: O(log N) — instant enqueue
- ZRANK: O(log N) — instant position lookup
- ZPOPMIN: O(log N + M) — dequeue N users atomically
- ZCARD: O(1) — total queue depth for ETA estimate

#### 5.8.3 Pre-scaling Strategy

```
Flash sale is predictable (announces 48hr in advance):

T-24hr: Alert on-call, activate flash sale runbook
T-4hr:  Scale Booking Service pods: 10 → 200 (Kubernetes HPA override)
T-2hr:  Pre-warm DB connection pools (PgBouncer)
T-1hr:  Activate virtual queue feature flag
T-30min: Load test final state with synthetic traffic
T=0:    Launch — queue absorbs 10M users; 5K/30sec enters booking
T+2hr:  Tickets sold out; disable queue; scale down
```

#### 5.8.4 Read Replica Strategy During Flash Sale

```
Architecture during peak:
  PostgreSQL Primary: handles WRITES only (booking confirms, seat updates)
  PostgreSQL Replicas ×5: handle all READ traffic
    - Show listings: replica
    - Booking history: replica
    - Seat inventory audit: replica
  Redis Cluster: handles ALL real-time seat state (not DB)
  
Routing:
  Write queries  → primary (via PgBouncer primary pool)
  Read queries   → random replica (via PgBouncer replica pool)
  Replica lag:   < 100 ms (acceptable for show listing / history reads)
```

---

## 6. Database Design

### 6.1 Why PostgreSQL?

| Factor | PostgreSQL | Cassandra | MongoDB |
|---|---|---|---|
| **ACID transactions** | ✅ Full ACID | ❌ Eventual | ⚠️ Document-level only |
| **Complex joins** | ✅ Native SQL | ❌ No joins | ❌ $lookup is expensive |
| **Financial correctness** | ✅ Serializable isolation | ❌ | ⚠️ |
| **Relational integrity** | ✅ Foreign keys, constraints | ❌ | ❌ |
| **Operational maturity** | ✅ Excellent | ✅ Good | ✅ Good |

**Verdict:** Booking is a financial transaction. Double booking = legal and regulatory liability. PostgreSQL's serializable transaction isolation is the correct choice. At 100K TPS, horizontal sharding (by `show_id`) is needed at scale.

### 6.2 Core Schema

```sql
-- ─────────────────────────────────────────────────────────────────────
-- SHOWS & CONTENT
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE theatres (
    theatre_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL,
    city          TEXT NOT NULL,
    address       TEXT,
    geo_lat       DECIMAL(9,6),
    geo_lon       DECIMAL(9,6),
    created_at    TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE screens (
    screen_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    theatre_id    UUID REFERENCES theatres(theatre_id),
    name          TEXT NOT NULL,         -- "Screen 1", "IMAX Hall"
    total_seats   INT  NOT NULL,
    seat_layout   JSONB NOT NULL         -- { "rows": [{"row":"A","seats":20},...] }
);

CREATE TABLE movies (
    movie_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title         TEXT NOT NULL,
    genre         TEXT[],
    language      TEXT,
    duration_mins INT,
    rating        TEXT,                  -- "U/A", "A"
    release_date  DATE,
    poster_url    TEXT
);

CREATE TABLE shows (
    show_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    movie_id      UUID REFERENCES movies(movie_id),
    screen_id     UUID REFERENCES screens(screen_id),
    start_time    TIMESTAMPTZ NOT NULL,
    end_time      TIMESTAMPTZ NOT NULL,
    format        TEXT NOT NULL,         -- "2D" | "3D" | "IMAX" | "4DX"
    language      TEXT NOT NULL,
    price_map     JSONB NOT NULL,        -- {"PREMIUM": 350, "GOLD": 250, "SILVER": 180}
    status        TEXT DEFAULT 'ACTIVE', -- ACTIVE | CANCELLED | COMPLETED
    created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_shows_movie_city_date ON shows(movie_id, start_time);

-- ─────────────────────────────────────────────────────────────────────
-- SEAT INVENTORY (Source of Truth)
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE show_seat_inventory (
    show_id       UUID NOT NULL REFERENCES shows(show_id),
    seat_id       TEXT NOT NULL,         -- "A1", "B12"
    category      TEXT NOT NULL,         -- PREMIUM | GOLD | SILVER
    price         DECIMAL(8,2) NOT NULL,
    status        TEXT NOT NULL DEFAULT 'AVAILABLE',
                  -- AVAILABLE | LOCKED | BOOKED | UNAVAILABLE
    locked_by     UUID REFERENCES users(user_id),
    locked_until  TIMESTAMPTZ,
    booked_by     UUID REFERENCES users(user_id),
    booking_id    UUID,
    booked_at     TIMESTAMPTZ,
    PRIMARY KEY   (show_id, seat_id)
);
-- Partial index for fast available seat count queries
CREATE INDEX idx_seat_available ON show_seat_inventory(show_id)
  WHERE status = 'AVAILABLE';

-- ─────────────────────────────────────────────────────────────────────
-- BOOKINGS & PAYMENTS
-- ─────────────────────────────────────────────────────────────────────

CREATE TYPE booking_status AS ENUM
  ('LOCKED','PAYMENT_INIT','CONFIRMED','CANCELLED','PAYMENT_TIMEOUT','USED');

CREATE TABLE bookings (
    booking_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    show_id         UUID NOT NULL REFERENCES shows(show_id),
    seats           TEXT[] NOT NULL,            -- ['A1','A2']
    num_seats       INT GENERATED ALWAYS AS (array_length(seats,1)) STORED,
    subtotal        DECIMAL(10,2) NOT NULL,
    convenience_fee DECIMAL(10,2) NOT NULL DEFAULT 0,
    total_amount    DECIMAL(10,2) NOT NULL,
    status          booking_status NOT NULL DEFAULT 'LOCKED',
    food_orders     JSONB,                      -- [{itemId, qty, price}]
    payment_id      TEXT,                       -- gateway payment ID
    gateway_order   TEXT,                       -- gateway order ID (idempotency)
    qr_code_url     TEXT,                       -- S3 URL
    locked_at       TIMESTAMPTZ DEFAULT now(),
    confirmed_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    refund_id       TEXT,
    cancellation_reason TEXT
);
CREATE INDEX idx_bookings_user ON bookings(user_id, locked_at DESC);
CREATE INDEX idx_bookings_show ON bookings(show_id, status);

CREATE TABLE payments (
    payment_id      TEXT PRIMARY KEY,           -- gateway payment_id
    booking_id      UUID REFERENCES bookings(booking_id),
    amount          DECIMAL(10,2) NOT NULL,
    currency        TEXT DEFAULT 'INR',
    gateway         TEXT NOT NULL,              -- 'razorpay' | 'stripe'
    status          TEXT NOT NULL,              -- SUCCESS | FAILED | REFUNDED
    gateway_data    JSONB,                      -- full gateway response (audit)
    processed_at    TIMESTAMPTZ DEFAULT now()
);
-- Idempotency: ensure we don't process the same payment_id twice
CREATE UNIQUE INDEX idx_payments_idempotency ON payments(payment_id);
```

### 6.3 Sharding Strategy at Scale

At 100K bookings/sec, a single PostgreSQL instance maxes out (~10–20K write TPS).

**Shard by `show_id` (hash-based):**
```
shard = hash(show_id) % num_shards

Shard 0: shows 0–999
Shard 1: shows 1000–1999
...

Why show_id?
  - Booking queries always filter by show_id (seat map, inventory check)
  - All data for one show (inventory, bookings) co-located on same shard
  - Cross-shard queries are rare (user booking history: scatter-gather across shards)
  
Cross-shard user query:
  SELECT * FROM bookings WHERE user_id = ?
  → Fan-out to all shards + merge in application layer
  → Or: maintain a separate user_bookings lookup table (bookingId → shardId)
```

---

## 7. Caching Strategy

```
                    ┌──────────────────────────────────────────────┐
                    │           CACHE HIERARCHY                    │
                    │                                              │
                    │  L1: CDN (CloudFront)                        │
                    │      → Static assets: images, JS, CSS        │
                    │      → TTL: 24hr–∞ (content-addressed URLs)  │
                    │      → Hit rate target: 99.5%                │
                    │                                              │
                    │  L2: Redis Cluster                           │
                    │      → Show metadata, seat maps, counts      │
                    │      → Hit rate target: 98%                  │
                    │                                              │
                    │  L3: PostgreSQL Read Replicas                │
                    │      → Cache miss fallback                   │
                    │      → Source of truth for cold data         │
                    └──────────────────────────────────────────────┘
```

### Full Redis Namespace Map

| Key | Type | TTL | Written By | Read By |
|---|---|---|---|---|
| `shows:{movieId}:{cityId}:{date}` | LIST of showIds | 2 hr | Content Svc | Content Svc |
| `show:meta:{showId}` | HASH (static fields) | 2 hr | Content Svc | Content Svc |
| `seatmap:{showId}` | HASH (seatId → status) | 24 hr | Seat Lock Svc | Seat Map Svc |
| `avail:{showId}` | STRING (int counter) | None | Seat Lock Svc | Content Svc |
| `lock:{showId}:{seatId}` | STRING (userId) | 600 sec | Seat Lock Svc | Seat Lock Svc |
| `movie:{movieId}` | HASH (metadata) | 24 hr | Content Svc | Content Svc |
| `queue:{showId}` | ZSET (userId → ts) | None | Queue Svc | Queue Svc |
| `booking_token:{token}` | STRING (userId+showId) | 300 sec | Queue Svc | Booking Svc |
| `rate:{userId}:{endpoint}` | STRING (INCR counter) | 60 sec | API Gateway | API Gateway |

### Cache Invalidation Rules

```
On show created/updated:
  DEL shows:{movieId}:{cityId}:{date}   → list regenerated on next request
  DEL show:meta:{showId}                → metadata refreshed

On seat LOCKED (Lua script handles atomically):
  HSET seatmap:{showId} {seatId} "L:..."
  DECRBY avail:{showId} {n}
  SET lock:{showId}:{seatId} {userId} NX EX 600

On payment confirmed (Phase 2, post DB commit):
  HMSET seatmap:{showId} A1 "B" A2 "B"
  DEL lock:{showId}:A1  lock:{showId}:A2
  (avail counter unchanged — was decremented at lock time)

On booking cancelled:
  HMSET seatmap:{showId} A1 "A" A2 "A"
  INCRBY avail:{showId} {n}
  (lock keys already expired or were deleted at confirm time)
```

---

## 8. Real-Time Seat Map

Every second, other users must see seats turn yellow (locked) or red (booked).

### Architecture

```
Seat Lock Svc ──▶ Kafka topic: seat-events (partitioned by showId)
                         │
                         ▼
              WebSocket Gateway (Horizontally Scaled)
                  Node 1: 10K persistent connections
                  Node 2: 10K persistent connections
                  ...
                  Node N: 10K persistent connections
                         │
                         │ Redis PubSub channel per show:
                         │   seat-updates:{showId}
                         ▼
              All WebSocket connections for show X
              receive: { seatId: "A1", status: "LOCKED" }
                         │
                         ▼
              Browser JS updates seat color instantly
              (no page reload — DOM patch)
```

### WebSocket Connection Management

```
Client connects:
  WS: wss://api.bms.com/ws/seatmap/{showId}
  Server: authenticate JWT, register connection
  Server: SUBSCRIBE to Redis channel seat-updates:{showId}
  Server: immediately send HGETALL seatmap:{showId} → initial state

Client receives updates:
  {"type":"SEAT_LOCKED","seatId":"A1","by":"otherUser","until":1700001800}
  {"type":"SEAT_BOOKED","seatId":"A1"}
  {"type":"SEAT_RELEASED","seatId":"A1"}

Connection registry (per WS node):
  showId → [conn1, conn2, conn3, ...]   (in-process HashMap)
  On Redis PubSub message → fan out to all local connections for that showId

Scale:
  1M concurrent seat map viewers (flash sale)
  → 100 WS nodes × 10K connections = 1M capacity
  → Each node subscribes to relevant showId channels in Redis PubSub
  → Redis PubSub: single channel per show; all WS nodes subscribed
```

### Why WebSocket over SSE?

| Criterion | WebSocket | SSE |
|---|---|---|
| **Direction** | Bidirectional | Server → Client only |
| **Use case fit** | ✅ Client sends seat selection; server pushes state | ❌ Client can't send data |
| **Load balancing** | ❌ Sticky sessions needed | ✅ Stateless (HTTP) |
| **Proxy support** | ⚠️ Some proxies block WS | ✅ Works everywhere |
| **Browser support** | ✅ All modern browsers | ✅ All modern browsers |

**Decision:** WebSocket. Seat map interaction is bidirectional — clients signal seat highlights ("I'm looking at A1"), server pushes lock/release updates.

---

## 9. Concurrency Deep Dive

### 9.1 Race Conditions Catalog

| Race | Scenario | Impact | Solution |
|---|---|---|---|
| **Concurrent lock** | Two users try to lock A1 simultaneously | Double lock → double booking | Redis Lua atomic script |
| **Lock expiry + payment** | Lock expires at T=10, payment arrives T=10.5 | Confirm on released seat | DB optimistic check: `status='LOCKED' AND locked_by=userId` |
| **Concurrent cancellation** | User cancels while payment webhook arrives | Status conflict | DB: `UPDATE ... WHERE status='CONFIRMED'` is idempotent |
| **Webhook duplicate** | Gateway sends webhook twice | Double-confirm | Idempotency table: `INSERT payments ON CONFLICT DO NOTHING` |
| **Seat availability undercount** | INCR and DECR race on avail:{showId} | Wrong count shown | Redis INCR/DECR are atomic; no race possible |
| **Split brain Redis** | Network partition creates two Redis primaries | Dual writes to different primaries | Redis Cluster with leader election; reads from primary only for seat lock |

### 9.2 Idempotency by Design

Every write endpoint has an idempotency key:

```
Lock seats:    idempotencyKey = bookingId (client-generated UUID)
               DB: INSERT ... ON CONFLICT(booking_id) DO NOTHING
               Re-submitting same lock request → returns same result

Confirm:       idempotencyKey = paymentId (gateway-provided)
               DB: INSERT payments ON CONFLICT(payment_id) DO NOTHING
               Duplicate webhook → no-op

Cancel:        idempotencyKey = bookingId
               DB: UPDATE WHERE status='CONFIRMED' (not already CANCELLED)
               Duplicate cancel → no-op (booking already cancelled)
```

---

## 10. Scalability

### 10.1 Scaling Each Layer

| Layer | Normal | Peak | Strategy |
|---|---|---|---|
| **API Gateway** | 5 nodes | 50 nodes | Stateless; HPA on CPU |
| **Content Service** | 3 nodes | 30 nodes | Stateless; mostly Redis-served |
| **Booking Service** | 5 nodes | 100 nodes | Stateless; Kafka async |
| **Seat Lock Service** | 3 nodes | 20 nodes | Stateless; Redis handles concurrency |
| **WebSocket Gateway** | 10 nodes (10K conn each) | 100 nodes | Sticky session LB + Redis PubSub |
| **Redis Cluster** | 6 nodes (3 primary + 3 replica) | 30 nodes | Add shards; auto-rebalance |
| **PostgreSQL Primary** | 1 instance (r6g.4xlarge) | 1 + 5 read replicas | Writes to primary only; reads to replicas |
| **Kafka** | 6 brokers | 30 brokers | Add brokers; increase partitions |

### 10.2 Database Scaling Bottleneck

PostgreSQL saturates at ~15K write TPS. At 100K bookings/sec:
```
Option A: Shard PostgreSQL by show_id (mod N shards)
  Pros: Each shard handles 100K/N TPS; proven pattern
  Cons: Cross-shard queries need scatter-gather; migration complexity

Option B: Move seat inventory writes to Redis (primary truth)
  Pros: Redis handles millions of ops/sec
  Cons: Durability risk without WAL replication
  Design: Redis with AOF persistence + async flush to DB for audit trail

Option C: CQRS — write path to Redis + Kafka, read path from Redis
  Kafka consumers write to DB asynchronously (eventual consistency for DB)
  Redis is the read-write store for active shows
  DB is audit + historical store
  
Production recommendation: Option A (sharding) + Option C (CQRS for analytics writes)
```

---

## 11. Reliability & Fault Tolerance

### 11.1 Failure Modes & Mitigations

| Component | Failure | Impact | Mitigation |
|---|---|---|---|
| **Redis primary** | Node crash | Seat locks lost; seatmap unavailable | Redis Cluster auto-failover to replica (< 30 sec) |
| **Redis cluster** | Full cluster down | Cannot lock/confirm any seats | Fallback: pessimistic DB locks (`SELECT FOR UPDATE`); 10× slower but functional |
| **PostgreSQL primary** | Crash | Cannot confirm bookings | Auto-failover to standby (Patroni); RPO < 10 sec |
| **Payment gateway** | Outage | Cannot process payments | Queue payment requests; retry with exponential backoff; show "service temporarily unavailable" |
| **WebSocket gateway** | Node crash | Active seat map sessions disconnected | Client auto-reconnects (browser WS auto-retry); re-fetches full seatmap on reconnect |
| **Kafka** | Broker down | Event pipeline delayed | Kafka replication factor 3; producer retries with idempotent producers |

### 11.2 Circuit Breaker Pattern

```
Payment Gateway Circuit Breaker:

CLOSED  → Normal operation; calls gateway
         If 5 failures in 10 sec → OPEN

OPEN    → Reject all payment calls immediately (fast fail)
          Return: "Payment service temporarily unavailable, try in 30 sec"
          After 30 sec → HALF-OPEN

HALF-OPEN → Allow 1 test request
            If success → CLOSED (resume normal)
            If fail    → OPEN again (another 30 sec)
```

### 11.3 Data Durability

```
Redis persistence config (for seat maps):
  appendonly yes                 → AOF: every write logged to disk
  appendfsync everysec           → flush to disk every 1 sec
  aof-rewrite-increment-percentage 100  → compact AOF when it doubles

  Result: max 1 sec data loss on Redis crash

PostgreSQL:
  synchronous_commit = on        → WAL flushed to disk before ACK
  wal_level = replica            → enable streaming replication
  max_wal_senders = 5            → up to 5 replicas

Kafka:
  acks = all                     → producer waits for all ISR replicas
  min.insync.replicas = 2        → at least 2 replicas confirm write
  replication.factor = 3         → 3 copies of every message
```

---

## 12. Observability

### 12.1 Metrics to Monitor

| Signal | Metric | Alert Threshold |
|---|---|---|
| **Seat lock success rate** | `lock_attempts - lock_successes / lock_attempts` | < 70% → high contention |
| **Lock-to-booking conversion** | `confirmed / locked` per hour | < 40% → users abandoning |
| **Redis avail counter** | `avail:{showId}` < 10 | "Filling Fast" UI trigger |
| **Payment success rate** | `payment_success / payment_init` | < 90% → gateway issue |
| **DB write latency** | `booking_confirm_p99` | > 500 ms → replication lag |
| **WebSocket connections** | per node | > 12K → scale up |
| **Queue depth** | `ZCARD queue:{showId}` | > 2M → add queue capacity |
| **Redis memory** | `used_memory / maxmemory` | > 80% → add nodes |
| **Kafka consumer lag** | notification-service group | > 100K → scale consumers |

### 12.2 Distributed Tracing

Every booking request carries a `X-Trace-ID` header:
```
Client → API GW → Booking Svc → Seat Lock Svc → DB → Kafka → ...

Trace spans:
  [api_gateway] authenticate: 2ms
  [booking_svc] validate_request: 1ms
  [booking_svc] check_avail: 0.5ms (Redis GET)
  [seat_lock]   lua_script: 3ms (Redis EVAL)
  [booking_svc] db_write: 8ms (PostgreSQL INSERT)
  [booking_svc] kafka_publish: 2ms

Trace stored in Jaeger; alert if total booking trace > 500ms P99
```

---

## 13. Security

| Area | Threat | Mitigation |
|---|---|---|
| **Seat hoarding** | Bot locks 100 seats, never pays → blocks real users | Rate limit: 5 lock attempts/min per user; max 10 seats per booking; CAPTCHA on flash sale |
| **Payment tampering** | Client sends `amount=1` for ₹800 ticket | Amount computed server-side, never trusted from client; validate before gateway call |
| **QR code forgery** | Attacker creates fake QR | AES-256 encrypted payload + server-side signature; validate decrypt on scan |
| **QR reuse** | Same QR used twice | Mark booking as `USED` on first scan; `UPDATE ... WHERE status='CONFIRMED'` → `USED` |
| **Webhook spoofing** | Attacker sends fake payment success | HMAC-SHA256 signature validation on every webhook; reject if signature invalid |
| **SQL injection** | Malicious show_id / seat_id | Parameterized queries everywhere; ORM-enforced |
| **Brute force booking** | Bypass seat lock with timing attacks | Redis rate limiting per userId + IP; exponential backoff after failures |

---

## 14. Trade-offs & Alternatives

| Decision | Choice Made | Alternative | Why This Choice |
|---|---|---|---|
| **Concurrency control** | Redis Lua atomic script | DB `SELECT FOR UPDATE` | Lua: microseconds + no DB connection per lock; `SELECT FOR UPDATE` holds connection, causes pool exhaustion at 100K TPS |
| **Seat state store** | Redis HASH (primary) + PostgreSQL (truth) | PostgreSQL only | PostgreSQL alone: 10–15ms/query; Redis: < 1ms. At 1M seat map reads/sec, DB cannot serve this |
| **Lock expiry** | Redis TTL + background reconciler | Explicit TTL field in DB only | Redis TTL auto-expires without polling; background job is belt-and-suspenders |
| **Queue for flash sale** | Redis ZSET (sorted by timestamp) | SQS / Kafka | ZSET: O(log N) insert; instant position query (`ZRANK`); SQS has no position visibility |
| **Real-time updates** | WebSocket + Redis PubSub | Polling (5 sec intervals) / SSE | Polling: 1M clients × 1 req/5sec = 200K RPS just for status updates; WS is 0 RPS until change |
| **Payment model** | Async webhook (Kafka) | Synchronous polling | Webhook: booking API returns immediately, payment confirmed async; polling: holds connection open for 3–30 sec under gateway load |
| **DB choice** | PostgreSQL | Cassandra | Financial ACID required; Cassandra's eventual consistency risks ghost bookings |
| **Seat map size** | All active shows in Redis (~200 MB) | DB + fetch per request | Redis: < 1ms HGETALL; DB: 5–15ms per query × 1M req/sec = impossible |

---

*Prepared for Staff / Principal Engineer level system design interviews.
Key differentiators: Redis Lua atomic locking, split static/dynamic cache, two-phase DB+Redis commit,
flash sale virtual queue, seat expiry via Redis keyspace notifications, and full concurrency race catalog.*
