# HLD — Flash Sale System (IPL Ticket Booking)

> **Context:** Design a system capable of handling a flash sale for IPL tickets where 50,000 seats go on sale at exactly 10:00 AM for 10 million waiting users. Covers the thundering herd problem, seat inventory management, fairness, and oversell prevention.

---

## 1. Requirements Clarification

### Functional Requirements
- Users can **queue up** before the sale opens
- At sale start, users are allowed to **browse available seats**
- User can **select seats, hold them temporarily**, and **complete payment**
- Seat hold expires if payment not completed in N minutes (e.g., 10 mins)
- Only one booking per user per match (anti-hoarding)
- Booking confirmation sent via email/SMS
- Admin can configure: sale start time, seat inventory, hold TTL

### Non-Functional Requirements
| Property | Target |
|---|---|
| Scale | 10M concurrent users at T=0 |
| Seat Inventory | 50,000 seats per match |
| Availability | 99.99% during sale window |
| Consistency | **Strong** — zero oversell tolerated |
| Latency | p99 < 500ms for seat hold API |
| Fairness | Randomized or FIFO queue; no preference for fast networks |
| Durability | Every confirmed booking must survive failures |

### Out of Scope
- Payment gateway internals
- Seat map rendering (frontend concern)
- Refund / cancellation flows (post-sale)

---

## 2. Capacity Estimation

### Traffic
| Metric | Estimate |
|---|---|
| Concurrent users at T=0 | 10 million |
| Seat inventory | 50,000 |
| Expected sale duration | ~2–5 minutes (seats go fast) |
| Peak RPS (queue/hold/pay APIs) | ~500K–1M RPS |
| Read RPS (seat map polling) | ~5M RPS (every user refreshes) |

### Storage
- Booking record: ~500 bytes → 50K bookings = **25 MB** (negligible)
- User queue state: ~100 bytes × 10M = **~1 GB** in Redis
- Seat state in Redis: 50K keys × ~100 bytes = **5 MB**

### Bandwidth
- At 1M RPS × 1 KB avg payload = **~1 GB/s** inbound at peak
- CDN and edge must absorb seat map reads

---

## 3. API Design

```
# Pre-sale: join virtual queue
POST /v1/sale/{sale_id}/queue
Body: { user_id }
Response: { queue_token, estimated_position, estimated_wait_ms }

# Poll queue status (client polls every 2–5s)
GET /v1/sale/{sale_id}/queue/status
Headers: Authorization: Bearer <queue_token>
Response: { status: WAITING | ADMITTED | EXPIRED, position, admitted_at }

# Browse seat map (only after admission)
GET /v1/sale/{sale_id}/seats
Headers: Authorization: Bearer <queue_token>
Response: { seats: [{ seat_id, row, section, status: AVAILABLE|HELD|SOLD }] }

# Hold a seat (atomic)
POST /v1/sale/{sale_id}/seats/{seat_id}/hold
Headers: Authorization: Bearer <queue_token>
Body: { user_id }
Response: { hold_id, expires_at }

# Confirm booking (post payment)
POST /v1/sale/{sale_id}/bookings
Body: { hold_id, user_id, payment_token }
Response: { booking_id, confirmation_code }
```

---

## 4. High-Level Architecture

```
                         ┌──────────────────────────────┐
                         │         Users (10M)           │
                         └──────────────┬───────────────┘
                                        │ HTTPS
                              ┌─────────▼──────────┐
                              │    CDN (CloudFront) │  ← Static assets, seat map cache
                              └─────────┬──────────┘
                                        │
                              ┌─────────▼──────────┐
                              │    WAF + DDoS Shield│  ← Block bots, rate limit by IP
                              └─────────┬──────────┘
                                        │
                              ┌─────────▼──────────┐
                              │    API Gateway       │  ← Auth, rate limiting, routing
                              └──┬──────┬──────┬───┘
                                 │      │      │
               ┌─────────────────┘      │      └──────────────────┐
               ▼                        ▼                         ▼
    ┌──────────────────┐   ┌────────────────────┐   ┌────────────────────┐
    │  Queue Service   │   │  Seat/Hold Service  │   │  Booking Service   │
    │  (Virtual Line)  │   │  (Inventory Core)   │   │  (Payment + Confirm)│
    └────────┬─────────┘   └────────┬───────────┘   └────────┬───────────┘
             │                      │                          │
             ▼                      ▼                          ▼
    ┌──────────────┐      ┌──────────────────┐       ┌──────────────────┐
    │  Redis Cluster│      │  Redis Cluster   │       │  PostgreSQL       │
    │  (Queue State)│      │  (Seat Inventory)│       │  (Bookings DB)    │
    └──────────────┘      └──────────────────┘       └──────────────────┘
                                    │
                           ┌────────▼────────┐
                           │  Kafka           │
                           │  (Booking Events)│
                           └────────┬────────┘
                                    │
                    ┌───────────────┴──────────────┐
                    ▼                               ▼
         ┌──────────────────┐           ┌──────────────────┐
         │  Notification Svc│           │  Analytics Svc    │
         │  (Email/SMS/Push)│           │  (Real-time stats)│
         └──────────────────┘           └──────────────────┘
```

---

## 5. Deep Dive — Critical Components

### 5.1 Virtual Queue (Thundering Herd Prevention)

**Problem:** 10M users hit the system simultaneously at T=0. No backend can absorb this.

**Solution: Metered Admission via Virtual Queue**

```
Phase 1 — Pre-sale (T-30 min to T=0):
  Users join the queue → receive a signed JWT queue_token with position
  Queue entries stored in Redis ZADD (score = join_timestamp for fairness)

Phase 2 — Sale Opens (T=0):
  Admission Controller releases users in batches (e.g., 5,000/min)
  Batch size = capacity system can safely handle
  Users polled or notified via SSE/WebSocket when admitted

Phase 3 — Admitted users:
  Get a short-lived admission token (TTL = 15 min)
  Only admitted users can call the hold/book APIs
```

**Queue Implementation (Redis ZADD):**
```
ZADD queue:{sale_id} <join_timestamp_ms> <user_id>
ZRANK queue:{sale_id} <user_id>   → returns position
ZRANGE queue:{sale_id} 0 4999     → get next batch of 5000
```

**Admission Token:**
- Signed JWT: `{ user_id, sale_id, admitted_at, exp: admitted_at + 15min }`
- Verified by Seat/Hold Service before any operation
- Prevents non-queued users from bypassing

**Fairness Strategy Options:**
| Strategy | Description | Tradeoff |
|---|---|---|
| FIFO by join time | First come, first served | Rewards fast networks |
| Randomized lottery | Random shuffle at T=0 | Fairest; less incentive to queue early |
| Tiered (loyalty first) | Premium members admitted first | Business upsell; potential backlash |

> **Recommended:** Randomized lottery among all users who joined before T=0. Pure FIFO rewards CDN proximity, not user intent.

---

### 5.2 Seat Inventory & Hold Service (Zero Oversell)

**This is the most critical component.** Any race condition here causes oversell.

#### Seat State Machine
```
AVAILABLE → HELD (hold API, 10-min TTL)
HELD → SOLD (booking confirmed)
HELD → AVAILABLE (TTL expired, no payment)
SOLD → (terminal)
```

#### Implementation: Redis + Lua Script (Atomic Hold)

Each seat is a Redis key:
```
seat:{sale_id}:{seat_id} → { status: AVAILABLE|HELD|SOLD, user_id, hold_id, expires_at }
```

**Atomic seat hold via Lua (runs as a single Redis command, no race conditions):**
```lua
-- KEYS[1] = seat key, ARGV[1] = user_id, ARGV[2] = hold_id, ARGV[3] = TTL seconds
local seat = redis.call('HGETALL', KEYS[1])
local status = seat['status']
if status ~= 'AVAILABLE' then
  return redis.error_reply('SEAT_NOT_AVAILABLE')
end
redis.call('HMSET', KEYS[1],
  'status', 'HELD',
  'user_id', ARGV[1],
  'hold_id', ARGV[2],
  'expires_at', tostring(tonumber(redis.call('TIME')[1]) + tonumber(ARGV[3]))
)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return redis.status_reply('OK')
```

**Why Lua?** Redis executes Lua scripts atomically — no other command runs between the check and the set. This is the **compare-and-set** pattern in Redis.

#### Hold Expiry & Auto-Release
- Redis key TTL naturally expires the hold
- A background **Hold Expiry Worker** (Redis keyspace notifications or scheduled scan) detects expired holds and:
  1. Resets seat state to `AVAILABLE` in Redis
  2. Publishes `hold.expired` event to Kafka
  3. Notifies user (optional)

#### One-Seat-Per-User Enforcement
```
user_hold:{sale_id}:{user_id} → hold_id (with same TTL)
```
Before granting a hold, check this key exists. If yes, reject.

#### Seat Map Reads (High Frequency)
- CDN caches seat map responses with a **5-second TTL**
- Slightly stale reads are acceptable (showing a seat as available when it's just been held is OK — hold API will reject)
- Do not cache at the hold/confirm level — those must be strongly consistent

---

### 5.3 Booking Confirmation (Exactly-Once Semantics)

**Flow:**
```
1. Client calls POST /bookings with hold_id + payment_token
2. Booking Service validates:
   a. hold_id exists in Redis (not expired)
   b. hold belongs to this user_id
   c. user has not already booked (idempotency check in DB)
3. Call Payment Gateway (synchronous, with timeout + circuit breaker)
4. On payment success:
   a. Update seat state: HELD → SOLD (atomic Redis SET)
   b. Write booking record to PostgreSQL (with hold_id as idempotency key)
   c. Publish booking.confirmed event to Kafka
5. Return booking_id + confirmation_code
```

**Idempotency:** If client retries, re-use `hold_id` as idempotency key. DB unique constraint on `hold_id` prevents double-booking.

**Payment Failure Handling:**
- Release the hold back to `AVAILABLE`
- User can retry with a different seat (hold was released)
- Do **not** auto-retry payment — user must re-initiate

---

### 5.4 Rate Limiting & Bot Protection

| Layer | Mechanism |
|---|---|
| WAF | Block known bot IPs, TOR exit nodes |
| CDN Edge | Rate limit: 100 req/s per IP on all endpoints |
| API Gateway | Per-user rate limit: 10 req/s via Redis sliding window |
| Queue join | 1 queue entry per authenticated user_id |
| Hold API | 1 hold per user per sale (enforced in Redis) |
| CAPTCHA | Trigger on suspicious behavior (rapid polling) |

---

## 6. Scale & Resilience

### Horizontal Scaling
| Service | Scaling Strategy |
|---|---|
| Queue Service | Stateless; scale behind ALB; Redis holds all state |
| Seat/Hold Service | Stateless; Redis is source of truth |
| Booking Service | Stateless; idempotency handled in DB |
| Redis | Redis Cluster (sharded by seat_id or sale_id); 6 nodes (3 primary + 3 replica) |
| PostgreSQL | Primary + 2 read replicas; write to primary only |
| Kafka | 12 partitions, 3 replicas per partition |

### Pre-Sale Warm-Up
- **Pre-load** all seat states into Redis 30 min before sale
- **Cache warmup** of seat map on CDN edge nodes
- **Load test** at 2× expected peak 24 hrs before sale

### Failure Modes & Mitigations
| Failure | Impact | Mitigation |
|---|---|---|
| Redis primary down | Seat holds fail | Redis Cluster auto-failover to replica (<30s); holds in-flight may be lost (idempotent retry) |
| Payment gateway slow | Bookings timeout | Circuit breaker (Hystrix); fail-open by rejecting new attempts; release hold |
| Kafka down | Notifications delayed | Booking committed to DB first; Kafka is async; retry with exponential backoff |
| DB primary down | Bookings fail | Sync replica promotion (RDS Multi-AZ); ~60s failover |
| Single hold service pod OOM | Partial outage | Multiple pods; ALB health check removes failing pod in 10s |
| Thundering herd at T=0 | System overload | Virtual queue absorbs; admission rate controlled |

### Multi-Region Consideration
- Primary region handles all writes (seat inventory is global state, cannot shard geo)
- Secondary region is **read-only** mirror — serves queue status polling, seat map reads
- In case of primary region failure: activate backup region (manual or automated failover)
- RPO: ~30s (replication lag); RTO: ~2 min

---

## 7. Trade-Offs & Alternatives

| Decision | Chosen | Alternative | Rationale |
|---|---|---|---|
| Seat hold atomicity | Redis Lua script | Optimistic locking in DB (CAS) | Redis is in-memory; Lua is atomic without round-trips. DB CAS works but slower at 50K concurrent holds |
| Queue implementation | Redis ZADD | SQS FIFO / Kafka | Redis ZADD gives O(log N) rank queries; SQS has throughput limits at this scale |
| Fairness model | Randomized lottery | Strict FIFO | FIFO incentivizes CDN proximity; lottery is fairer across geographies |
| Seat map consistency | CDN cache (5s stale) | Strong consistent reads | Strong reads at 5M RPS would crush the DB; 5s staleness is UX-acceptable |
| Payment integration | Sync, within hold TTL | Async (post-booking confirm) | Sync gives immediate feedback; async risks user not knowing if booking succeeded |
| DB for bookings | PostgreSQL | Cassandra | Bookings need ACID; idempotency key unique constraint is easier in relational DB |
| Notification | Kafka + async workers | Sync in booking flow | Kafka decouples; notification failure should not fail the booking |

---

## 8. Observability

### Key Metrics
| Metric | Alert Threshold |
|---|---|
| Queue depth (users waiting) | Alert if drain rate < 1K/min |
| Seat hold success rate | Alert if < 95% (indicates inventory exhausted) |
| Booking success rate | Alert if < 90% |
| Hold expiry rate | Alert if > 30% (users not completing payment) |
| Redis memory usage | Alert if > 80% |
| Payment gateway p99 latency | Alert if > 3s |
| Error rate (5xx) | Alert if > 0.1% |

### Dashboards
- **Real-time sale dashboard:** Seats sold vs. held vs. available (updated every 5s)
- **Queue drain dashboard:** Users admitted, waiting, expired
- **Revenue dashboard:** Total confirmed bookings × price

### Tracing
- OpenTelemetry trace spans: `queue.join → admission → seat.hold → payment → booking.confirm`
- Trace ID propagated across all services for end-to-end debugging

---

## 9. Security

| Concern | Approach |
|---|---|
| Bot traffic | WAF rules, rate limiting, CAPTCHA on queue join |
| Queue token forgery | Signed JWT (RS256); short TTL |
| Seat hold replay attacks | hold_id is UUID; one-time use enforced in DB |
| Payment data | PCI-DSS compliant; payment token from gateway (never store raw card data) |
| API abuse | Per-user rate limiting via Redis sliding window |
| DDoS at sale time | CloudFront + AWS Shield Advanced |

---

## 10. Flash Sale — Pre-Sale Checklist

```
T-7 days  : Load test at 3× expected peak
T-1 day   : Disable non-critical features (recommendations, search) to free capacity
T-2 hours : Pre-load seat inventory into Redis; warm CDN cache
T-30 min  : Open queue join (users can start queuing)
T-5 min   : Freeze queue — no new joins
T=0       : Admission controller starts releasing users; sale is LIVE
T+5 min   : Monitor hold expiry rate; release expired seats immediately
T+N       : Sale ends (all seats SOLD or inventory exhausted)
```

---

## Summary

```
┌─────────────────────────────────────────────────────────────┐
│                   Flash Sale Key Principles                  │
├─────────────────┬───────────────────────────────────────────┤
│ Thundering Herd │ Virtual queue with metered admission       │
│ Zero Oversell   │ Redis Lua atomic compare-and-set           │
│ Fairness        │ Randomized lottery before admission        │
│ Read Scalability│ CDN-cached seat map (5s TTL)               │
│ Consistency     │ Redis for holds (strong), DB for bookings  │
│ Resilience      │ Redis Cluster + DB Multi-AZ + Kafka retry  │
│ Idempotency     │ hold_id as unique idempotency key in DB    │
└─────────────────┴───────────────────────────────────────────┘
```
