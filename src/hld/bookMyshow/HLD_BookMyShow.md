# 🎬 High-Level Design (HLD) — BookMyShow (Ticket Booking System)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Seat Locking (Concurrency), Seat Map, Booking State Machine, Flash Sale Handling, Payment Flow, Read-Heavy Caching

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **search movies / events** by city, date, genre.
- Users can **view shows** for a selected movie — theatre, timings, available screens.
- Users can **select seats** from an interactive seat map and **book tickets**.
- Seat **locking** during the selection window — hold a seat for 10 min while user pays.
- Support **multiple payment modes**: UPI, Card, Net Banking, BookMyShow Wallet.
- Generate **booking confirmation** with QR code for entry.
- Support **seat cancellation** with refund policy.
- **Flash sale / premiere bookings** — handle 10M+ simultaneous users (e.g., Avengers: Endgame).
- Get seat **availability in real-time** — show green/red on seat map.
- Support **food pre-ordering** tied to a booking.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Seat map load latency** | < 200 ms |
| **Seat lock latency** | < 500 ms |
| **Payment latency** | < 2 sec |
| **Consistency** | Strong consistency for seat assignment — no double booking ever |
| **Availability** | 99.99% for browsing; 99.9% for booking |
| **Scale (peak)** | 10M concurrent users during blockbuster releases |
| **Throughput** | 100K bookings/sec during peak |

### 1.3 Out of Scope
- Partner theatre management portal
- Movie content streaming
- Loyalty points / cashback engine

---

## 2. Capacity Estimation

```
Cities                  = 300 (India)
Theatres in system      = 10,000
Screens per theatre     = avg 4 → 40,000 screens
Shows per screen/day    = 5 → 200,000 shows/day
Seats per screen        = avg 250 → 200K shows × 250 = 50M seat-show slots/day
Bookings/day (normal)   = 1M → ~12/sec avg
Bookings/sec (peak)     = 100,000 (Avengers release day)
Seat map reads          = 100× bookings → ~10M reads/day, ~1M/sec peak
Storage (booking)       = 1M/day × 1 KB = ~1 GB/day
Seat state storage      = 50M seat-show slots × 1 byte = ~50 MB (fits entirely in Redis)
```

---

## 3. High-Level Architecture

```
 User (Mobile / Web)
        │
        ├── Browse / Search ──────────────────────────────▶ API Gateway
        │                                                        │
        ├── View Seat Map ────────────────────────────────▶ API Gateway
        │                                                        │
        └── Book / Pay ─────────────────────────────────▶ API Gateway
                                                                 │
                          ┌──────────────────────────────────────┤
                          │                                       │
               ┌──────────▼────────┐                  ┌──────────▼────────┐
               │  Content Service  │                  │  Booking Service   │
               │  (movies, shows,  │                  │  (seat lock, pay,  │
               │  theatres, search)│                  │   confirm, cancel) │
               └──────────┬────────┘                  └──────────┬────────┘
                          │                                       │
               ┌──────────▼────────┐                  ┌──────────▼────────┐
               │  Redis Cache       │                  │  Seat Lock Service │
               │  (seat maps,       │                  │  (Redis + DB)      │
               │   show listings)   │                  └──────────┬────────┘
               └───────────────────┘                             │
                          │                           ┌──────────▼────────┐
               ┌──────────▼────────┐                  │  Payment Service   │
               │  PostgreSQL DB     │                  │  (Razorpay/Stripe) │
               │  (shows, bookings, │                  └──────────┬────────┘
               │   seat inventory)  │                             │
               └───────────────────┘                  ┌──────────▼────────┐
                                                       │  Notification Svc  │
                                                       │  (SMS, Email, Push)│
                                                       └───────────────────┘
```

---

## 4. Booking Flow — State Machine

```
User selects seats
        │
        ▼
┌───────────────────┐
│  SEATS_SELECTED   │
└──────────┬────────┘
           │ lockSeats() — 10-min TTL
           ▼
┌───────────────────┐      timeout / cancel
│   LOCKED          │─────────────────────▶ seats released back to AVAILABLE
└──────────┬────────┘
           │ initiatePayment()
           ▼
┌───────────────────┐      payment fails / timeout
│  PAYMENT_PENDING  │─────────────────────▶ seats released, PAYMENT_FAILED
└──────────┬────────┘
           │ paymentSuccess()
           ▼
┌───────────────────┐
│   CONFIRMED       │ → QR code generated, notification sent
└──────────┬────────┘
           │ user requests cancellation (before show)
           ▼
┌───────────────────┐
│   CANCELLED       │ → refund initiated per policy
└───────────────────┘
```

---

## 5. Core Components

### 5.1 Content Service (Read-Heavy)

Serves movie listings, show timings, theatre details.

```
Data hierarchy:
  Movie → Show → Screen → SeatMap

Read-through cache (Redis):
  Key: shows:{movieId}:{cityId}:{date}
  Value: list of {showId, theatreName, timing, price, availableCount}
  TTL: 5 min (refresh as bookings happen)

Movie metadata:
  Key: movie:{movieId}
  Value: title, genre, duration, rating, poster_url, languages
  TTL: 24 hr (rarely changes)

Elasticsearch:
  Full-text search: movie name, actor, genre, theatre name
  Geo-search: theatres within 10 km of user
```

### 5.2 Seat Map Service

**The most read-intensive component.** Every user opening a show sees the seat map.

**Seat State Model:**
```
Each seat has one of 4 states:
  AVAILABLE   (green)
  LOCKED      (yellow — someone is selecting, 10-min hold)
  BOOKED      (red)
  UNAVAILABLE (grey — broken/blocked by theatre)
```

**Redis as the seat state store (source of truth for real-time):**

```
Key:   seatmap:{showId}
Type:  Redis HASH
Field: seatId (e.g., "A1", "B12")
Value: {status, lockedBy, lockedUntil}

HGETALL seatmap:{showId}  → entire seat map in one call (< 1 ms)
HSET seatmap:{showId} A1 "LOCKED:userId:1741201800"  → lock seat

For 250 seats per show:
  Each entry ~50 bytes → 250 × 50 = ~12.5 KB per showId
  50M show slots × 12.5 KB = ~625 GB → sharded Redis Cluster
```

**Seat map read optimization:**
- Serve seat map from Redis for 99% of reads.
- Redis Cluster sharded by `showId` (all seats for a show on same node).
- Seat map pushed via **WebSocket / SSE** on state change → real-time green/red updates.

### 5.3 Seat Locking Service (Critical Path — Concurrency)

**The core concurrency problem:** 1,000 users trying to book seat A1 simultaneously.

**Solution: Redis atomic operations (Lua script)**

```lua
-- Atomic lock attempt for multiple seats
-- KEYS: seatmap:{showId}
-- ARGV: userId, lockUntil, seat1, seat2, ...

local seatmapKey = KEYS[1]
local userId = ARGV[1]
local lockUntil = ARGV[2]
local seats = {table.unpack(ARGV, 3)}

-- Check all seats are available (atomic read)
for _, seat in ipairs(seats) do
    local val = redis.call('HGET', seatmapKey, seat)
    if val ~= nil and val ~= 'AVAILABLE' then
        return 0  -- LOCK FAILED: seat not available
    end
end

-- All seats available → lock them atomically
for _, seat in ipairs(seats) do
    redis.call('HSET', seatmapKey, seat,
        'LOCKED:' .. userId .. ':' .. lockUntil)
end

return 1  -- LOCK SUCCESS
```

**Lock expiry (TTL-based auto-release):**
```
Problem: Redis HASH values don't have individual TTLs.
Solution: Background TTL job (every 30 sec):
  Scan all locked seats
  If now() > lockedUntil → SET seat back to AVAILABLE
  Publish SeatReleased event → WebSocket push to all viewers
```

Alternative approach: **Separate Redis key per locked seat** with TTL:
```
Key:   lock:{showId}:{seatId}
Value: userId
TTL:   600 sec (10 min)

SET lock:show123:A1 user456 NX EX 600  → 1 = lock acquired, 0 = already locked
```
- NX = only set if Not eXists (atomic).
- TTL = auto-release after 10 min.
- **Simpler and cleaner** — Redis handles expiry automatically.

### 5.4 Booking Service & Database

```sql
-- PostgreSQL (ACID — financial transactions)

CREATE TABLE bookings (
    booking_id      UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    show_id         UUID NOT NULL,
    seats           TEXT[],           -- ['A1', 'A2', 'A3']
    total_amount    DECIMAL(10,2),
    convenience_fee DECIMAL(10,2),
    status          TEXT NOT NULL,    -- LOCKED|PAYMENT_PENDING|CONFIRMED|CANCELLED
    payment_id      UUID,
    qr_code         TEXT,
    booked_at       TIMESTAMP,
    cancelled_at    TIMESTAMP,
    food_orders     JSONB             -- [{item, qty, price}]
);

CREATE TABLE show_seat_inventory (
    show_id         UUID,
    seat_id         TEXT,
    status          TEXT,             -- AVAILABLE|LOCKED|BOOKED|UNAVAILABLE
    booked_by       UUID,
    booking_id      UUID,
    PRIMARY KEY (show_id, seat_id)
);
```

**On successful payment — atomic DB transaction:**
```
BEGIN TRANSACTION;
  UPDATE show_seat_inventory
    SET status='BOOKED', booked_by=userId, booking_id=bookingId
    WHERE show_id=showId AND seat_id IN ('A1','A2')
    AND status='LOCKED' AND booked_by=userId;   -- optimistic lock check

  IF rows_updated != num_seats THEN ROLLBACK; -- someone else grabbed seat
  
  UPDATE bookings SET status='CONFIRMED', payment_id=paymentId
    WHERE booking_id=bookingId;

  -- Remove Redis lock (seat now BOOKED in Redis too)
  DEL lock:show123:A1 lock:show123:A2;
  HSET seatmap:{showId} A1 BOOKED A2 BOOKED;
COMMIT;
```

### 5.5 Payment Service

```
Payment flow:
  1. Booking Service calls Payment Gateway (Razorpay / Stripe)
  2. Gateway returns payment_link / order_id
  3. User completes payment in gateway UI
  4. Gateway webhook → PaymentService (async callback)
  5. PaymentService → Kafka: PaymentSuccess / PaymentFailed
  6. BookingService consumer:
     → On Success: confirm booking, generate QR, notify user
     → On Failure: release locks, update status=PAYMENT_FAILED

Idempotency:
  Payment gateway called with idempotency_key = bookingId
  Prevents duplicate charges on webhook retry

Timeout:
  If no payment webhook in 12 min → auto-release seats → booking cancelled
  (Extra 2 min buffer beyond 10-min seat lock)
```

### 5.6 Flash Sale Handling (10M Concurrent Users)

The hardest part — e.g., Avengers Endgame premiere day.

**Problem:** 10M users refreshing the booking page simultaneously.

**Solutions:**

**1. Virtual Waiting Room (Queue-based access)**
```
User hits "Book Now" for Avengers Endgame premiere
  → Placed in virtual queue (Redis sorted set: score = arrival timestamp)
  → Shown estimated wait time
  → Position 1 → 5,000 gets booking access every 30 sec (controlled drip)
  → Users beyond capacity: "Join waitlist for next batch"

Benefits: Server load becomes predictable; no thundering herd on seat lock.
```

**2. CDN for static content**
```
Seat map HTML/JS/CSS → served from CDN (no origin hits)
Movie posters, theatre images → CDN cached
Only seat availability API hits origin servers
```

**3. Read replicas for inventory reads**
```
Seat map reads → Redis replicas (near-zero load on primary)
Available count display → eventually consistent Redis counter
Only actual seat LOCK → goes to Redis primary (write)
```

**4. Auto-scaling**
```
Pre-scale Booking Service pods 2 hr before Avengers release
  (predictable traffic spike; scheduled auto-scaling event)
Database connection pool pre-warmed
Redis memory pre-allocated
```

**5. Graceful degradation**
```
If Redis primary overloaded:
  → Switch to "hold" mode: no seat selection, only show count
  → Batch book from backend without seat map
  → User gets random seat assignment

If DB overloaded:
  → Reject new lock attempts with "Too Busy, Try Again" message
  → Rate limit per user (1 booking attempt / 5 sec)
```

---

## 6. Seat Map — Real-time Updates

When User A locks seat A1, Users B, C, D viewing the same show must see it turn yellow.

```
Seat Lock Event
      │
      ▼
Booking Service publishes: SeatLocked { showId, seatId, status }
      │
      ▼
Kafka → Seat Update Consumer
      │
      ▼
WebSocket Server:
  All users watching show {showId} seat map → broadcast update:
  { seatId: "A1", status: "LOCKED" }
      │
      ▼
Browser JS updates seat color in real-time (no page reload)

WebSocket server:
  Connection stored: showId → [wsConn1, wsConn2, wsConn3, ...]
  Redis PubSub: when SeatLocked event fires → notify all WS subscribers
  Scale: 1M concurrent viewers → 100 WS nodes, each holding 10K connections
```

---

## 7. QR Code & Validation

```
On booking confirmed:
  QR payload = base64(encrypt(bookingId + showId + seats + timestamp, secretKey))
  QR rendered as image → sent via push notification + email

At theatre gate:
  Scanning device → calls ValidateTicket API:
    POST /validate { qrData }
    → Decrypt → verify bookingId exists + status = CONFIRMED + showId + showTime
    → Return { valid: true, seats: ["A1", "A2"], userName }
    → Mark booking as USED (prevent reuse)
    → Response < 200ms (offline-capable with cached show data)
```

---

## 8. Data Model Summary

| Entity | Storage | Reason |
|---|---|---|
| Movies, Shows, Theatres | PostgreSQL | Relational, ACID |
| Seat inventory | PostgreSQL (source of truth) + Redis (real-time) | Consistency + speed |
| Seat locks | Redis (`SET NX EX`) | Atomic, TTL auto-expiry |
| Bookings, Payments | PostgreSQL | Financial ACID |
| Seat map state | Redis HASH per showId | Sub-millisecond reads |
| Show listings cache | Redis + CDN | Read-heavy, rarely changes |
| Search (movies, theatres) | Elasticsearch | Full-text + geo queries |

---

## 9. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Seat lock mechanism** | Redis `SET NX EX` | Atomic, TTL auto-expiry; eventual DB sync |
| **Concurrency control** | Redis atomic ops + DB optimistic lock | No deadlocks; slight retry complexity |
| **Seat map store** | Redis HASH per showId | Sub-ms reads; 625 GB total — Redis Cluster |
| **Peak traffic** | Virtual waiting room | Controlled load; UX tradeoff (wait time) |
| **Real-time seat updates** | WebSocket + Redis PubSub | Live seat color changes; connection overhead |
| **Payment** | Async webhook model | Non-blocking; handles gateway latency |
| **DB choice** | PostgreSQL | ACID for financial; relational for show inventory |

---

## 10. Monitoring & Observability

| Metric | Alert |
|---|---|
| **Seat lock success rate** | < 80% (high contention on popular shows) |
| **Lock-to-booking conversion** | < 40% (users abandoning after lock — UX issue) |
| **Redis memory utilization** | > 80% — add cluster nodes |
| **Payment gateway success rate** | < 95% |
| **Booking confirmation latency** | P99 > 5 sec |
| **WebSocket connection count** | Per-node > 15K — scale WS pods |
| **Waiting room queue depth** | Display accurate ETAs; alert if queue > 2M |

---

## 11. Future Enhancements
- **Dynamic pricing** — surge pricing for blockbusters; lower prices for off-peak shows.
- **GroupBooking** — share invite link; all friends select seats together in same session.
- **Seat recommendation** — AI recommends best seats based on user preference (aisle, center, back).
- **Augmented seat view** — show view-from-seat preview before selecting.
- **Waitlist** — auto-book if a cancellation opens up for sold-out shows.

---

*Document prepared for SDE 3 system design interviews. Focus areas: seat locking with Redis atomic ops, double-booking prevention, flash sale virtual waiting room, real-time seat map via WebSocket, booking state machine, and ACID payment flow.*
