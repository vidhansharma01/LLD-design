# 🚗 High-Level Design (HLD) — Uber (Ride-Hailing Platform)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Real-time Location, Driver-Rider Matching, Trip State Machine, Surge Pricing, ETA, Geo-spatial Queries, Consistency

---

## 1. Requirements

### 1.1 Functional Requirements
- Riders can **request a ride** by specifying pickup and drop-off location.
- System **matches rider to the nearest available driver** within seconds.
- **Real-time location tracking** — rider sees driver moving on map; driver gets navigation.
- **ETA prediction** — accurate estimated arrival time shown to rider before and during trip.
- **Fare estimation** before booking; final fare after trip ends.
- **Dynamic surge pricing** — multiplier increases when demand > supply in an area.
- Support **multiple ride types**: UberGo, UberX, Pool, Premier, Auto.
- Driver can **accept or reject** ride requests.
- Support **trip cancellation** (both sides) with applicable charges.
- **Ratings** for driver and rider after each trip.
- **Receipts and invoices** for completed trips.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Driver match latency** | < 5 sec from ride request |
| **Location update frequency** | Driver sends GPS every 5 sec |
| **ETA accuracy** | ±2 min for 90% of trips |
| **Availability** | 99.99% — ride requests must always work |
| **Scale** | 100M trips/day, 5M active drivers globally, 50M active riders |
| **Consistency** | Strong — one driver cannot be assigned to two riders simultaneously |
| **Surge freshness** | Surge multiplier updated every 1 minute |

### 1.3 Out of Scope
- Uber Eats (food delivery — separate system)
- Driver onboarding / KYC
- Financial payouts to drivers
- Autonomous vehicle routing

---

## 2. Capacity Estimation

```
Trips/day               = 100 million → ~1,160/sec avg, ~15K/sec peak
Active drivers peak     = 5 million → GPS update every 5 sec = 1M GPS writes/sec
Active riders peak      = 20 million
Trip duration avg       = 20 min
Concurrent trips        = 1,160/sec × 20 min × 60 = ~1.4M concurrent trips
Location update storage = 1M writes/sec → only LATEST position needed (Redis)
City geo-cells          = Earth divided into S2 cells (~1 km² each) → ~10K relevant cells per city
Surge calculation       = per S2 cell, every 60 sec → ~10K × global_cities computations/min
```

---

## 3. High-Level Architecture

```
 Rider App                               Driver App
      │                                       │
      │  Request Ride                         │  Send GPS location (every 5s)
      │  Track driver                         │  Accept/Reject trip
      └──────────────┬────────────────────────┘
                     ▼
            ┌────────────────┐
            │  API Gateway   │ (Auth, Rate Limit, WebSocket upgrade)
            └───────┬────────┘
                    │
   ┌────────────────┼───────────────────────────────┐
   ▼                ▼                               ▼
┌──────────┐  ┌───────────────┐           ┌──────────────────┐
│  Trip    │  │  Location     │           │  Matching        │
│  Service │  │  Service      │           │  Engine          │
└────┬─────┘  └───────┬───────┘           └────────┬─────────┘
     │                │                            │
     │         ┌──────▼──────────┐           ┌─────▼──────────┐
     │         │  Redis GEO      │           │  Scoring &     │
     │         │  (driver locs)  │           │  Dispatch Svc  │
     │         └─────────────────┘           └────────────────┘
     │
┌────▼──────────────────────────────────────┐
│              Supporting Services           │
│  ┌───────────┐ ┌──────────┐ ┌──────────┐  │
│  │  Pricing  │ │  ETA     │ │  Maps    │  │
│  │  Service  │ │  Service │ │  Service │  │
│  │  (surge)  │ │          │ │ (routing)│  │
│  └───────────┘ └──────────┘ └──────────┘  │
│  ┌───────────┐ ┌──────────┐               │
│  │  Payment  │ │  Notif.  │               │
│  │  Service  │ │  Service │               │
│  └───────────┘ └──────────┘               │
└───────────────────────────────────────────┘
         │
┌────────▼──────────────────────────┐
│  Kafka Event Bus                   │
│  (TripRequested, TripAccepted,     │
│   LocationUpdated, TripCompleted)  │
└───────────────────────────────────┘
```

---

## 4. Trip Lifecycle — State Machine

```
Rider requests ride
        │
        ▼
┌───────────────────┐
│   REQUESTED       │──── no driver found in 2 min ──▶ CANCELLED (auto)
└──────────┬────────┘
           │ driver matched
           ▼
┌───────────────────┐
│   DRIVER_ASSIGNED │──── driver cancels ──▶ REQUESTED (re-match)
└──────────┬────────┘      (back to matching)
           │ driver arrives at pickup
           ▼
┌───────────────────┐
│   DRIVER_ARRIVED  │
└──────────┬────────┘
           │ rider enters vehicle
           ▼
┌───────────────────┐
│   IN_PROGRESS     │
└──────────┬────────┘
           │ driver ends trip at destination
           ▼
┌───────────────────┐
│   COMPLETED       │ → fare calculated, payment charged, ratings prompted
└───────────────────┘

Cancellation:
  Rider cancels before DRIVER_ARRIVED (within free cancel window) → FREE
  Rider cancels after DRIVER_ARRIVED → CANCELLED with fee
  Driver cancels → penalty applied; rider re-matched
```

---

## 5. Core Components

### 5.1 Location Service (The Most Write-Heavy Component)

**1 million GPS writes per second** from 5M active drivers.

```
Driver App → POST /location { driverId, lat, lng, heading, speed, timestamp }
every 5 sec → Location Service
        │
        ▼
Write to Redis GEO:
  GEOADD driver_locations:{cityId} <lng> <lat> <driverId>
  (overwrites previous position — only latest matters)

Additionally:
  HSET driver_meta:{driverId} status AVAILABLE  heading 270  speed 35  city BLR

Kafka: LocationUpdated event → consumed by:
  → Trip Tracking Service (push to rider's WebSocket if trip IN_PROGRESS)
  → ETA Recalculation (update ETA every 30 sec during trip)
  → Surge Engine (update supply density per S2 cell every 60 sec)

Redis GEO commands used by Matching Engine:
  GEORADIUS driver_locations:{cityId} <rider_lng> <rider_lat> 5 km ASC COUNT 20
  Returns nearest 20 drivers, sorted by distance
```

**Redis GEO internals:**
```
Redis GEO uses a sorted set internally.
Coordinates are encoded as 52-bit geohash → stored as ZSET score.
GEORADIUS query = ZRANGEBYSCORE on geohash range → O(log N + M) where M = results
With 5M drivers and Redis Cluster (sharded by cityId), each shard handles ~100K drivers.
```

### 5.2 Matching Engine (Driver-Rider Assignment)

**Critical constraint: one driver assigned to exactly one rider at a time.**

```
TripRequested event received (from Kafka)
        │
        ▼
Step 1: Find candidate drivers
  GEORADIUS driver_locations:{cityId} <riderLng> <riderLat> 5 km ASC COUNT 20
  Filter: only AVAILABLE drivers (check driver_meta:{driverId}.status)
  Result: [driverA: 0.5km, driverB: 1.2km, driverC: 2.1km ...]

Step 2: Score each candidate driver
  score = α × (1 / distance_km)
        + β × driver_rating
        + γ × acceptance_rate (drivers who accept most requests)
        + δ × ETA_to_rider   (real road ETA, not just distance)
        + ε × vehicle_type_match (rider requested UberX, driver has UberX)

Step 3: Dispatch top-scored driver
  Attempt to acquire driver lock:
  SET driver_lock:{driverId} {tripId} NX EX 20  → 1 = acquired, 0 = already locked
  (NX = only if not exists; EX 20 = expires in 20 sec)

Step 4: Send trip request to driver app (push notification)
  Driver has 15 sec to respond
  → Accept → update driver_meta.status = ON_TRIP; create trip record
  → Reject / timeout → release lock → try next candidate
  → If all candidates exhausted → expand radius to 10 km, retry
  → If still no driver in 2 min → TRIP CANCELLED, notify rider
```

**Preventing double assignment using Redis atomics:**
```
SET driver_lock:{driverId} {tripId} NX EX 20
  NX: SET only if key does Not eXist → ensures exactly one trip assigned
  EX 20: auto-expires if driver crashes (prevents permanent lock)

On accept: NX lock converted to permanent status:
  HSET driver_meta:{driverId} status ON_TRIP tripId {tripId}
  DEL driver_lock:{driverId}

On reject/timeout:
  DEL driver_lock:{driverId}  → driver available for other trips
```

### 5.3 Trip Service & Database

```sql
-- PostgreSQL (ACID for financial trip records)
CREATE TABLE trips (
    trip_id         UUID PRIMARY KEY,
    rider_id        UUID NOT NULL,
    driver_id       UUID,
    ride_type       TEXT,         -- UBERGO|UBERX|UBERPOOL|PREMIER
    status          TEXT NOT NULL,
    pickup_lat      DOUBLE,
    pickup_lng      DOUBLE,
    pickup_address  TEXT,
    dropoff_lat     DOUBLE,
    dropoff_lng     DOUBLE,
    dropoff_address TEXT,
    estimated_fare  DECIMAL(10,2),
    final_fare      DECIMAL(10,2),
    surge_multiplier DECIMAL(4,2),
    distance_km     DOUBLE,
    duration_min    INT,
    requested_at    TIMESTAMP,
    accepted_at     TIMESTAMP,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    payment_id      UUID,
    rider_rating    TINYINT,
    driver_rating   TINYINT
);

-- Route breadcrumb trail (for fare calculation, replay, analytics)
CREATE TABLE trip_locations (
    trip_id     UUID,
    recorded_at TIMESTAMP,
    lat         DOUBLE,
    lng         DOUBLE,
    PRIMARY KEY (trip_id, recorded_at)
);
-- Stored in Cassandra (time-series write-heavy); not PostgreSQL
```

### 5.4 ETA Prediction Service

**Two phases: pre-booking ETA and in-trip ETA.**

```
Pre-booking ETA (shown before rider confirms):
  ETA_to_rider = road_travel_time(driverLocation → pickupLocation)
  Using: Google Maps Distance Matrix API / MapmyIndia / HERE Maps
  Factors: real-time traffic, time of day, road speed limits
  Cached: per (origin_cell, destination_cell) pair, TTL = 2 min

In-trip ETA:
  ETA_to_destination = road_travel_time(currentDriverLocation → dropoffLocation)
  Recalculated every 30 sec as driver moves
  Pushed to rider via WebSocket

ML model adjustment:
  Historical trips: actual_time vs maps_estimated_time per route + time_of_day
  Correction factor = mean(actual/estimated) per city-time bucket
  Final ETA = maps_ETA × correction_factor
```

### 5.5 Surge Pricing Engine

**Dynamic pricing based on demand/supply balance.**

```
Surge Calculation (every 60 sec per S2 cell):

  supply = count of AVAILABLE drivers in cell
  demand = count of PENDING trip requests in cell (last 5 min)
  ratio  = demand / supply

  surge_multiplier:
    ratio < 1.0  → 1.0× (normal)
    ratio 1.0–1.5 → 1.2×
    ratio 1.5–2.0 → 1.5×
    ratio 2.0–2.5 → 1.8×
    ratio > 2.5  → 2.0× (max cap)

Storage:
  Redis HASH: surge:{cityId}:{s2CellId} → { multiplier, updated_at }
  TTL: 90 sec

Rider sees:
  Fare estimate at booking time = base_fare × surge_multiplier
  Surge multiplier "locked" for 2 min after rider sees fare (honoring the shown price)

Driver incentive:
  Higher surge areas shown as "heat map" in driver app → nudges drivers towards busy zones
```

### 5.6 Real-time Trip Tracking (WebSocket)

```
IN_PROGRESS trip:
  Driver App: location update every 5 sec → Location Service → Kafka: LocationUpdated
                                                                         │
                                               Trip Tracking Consumer (Kafka)
                                                         │
                                              Is there an active trip for this driver?
                                                         │ yes
                                              Push to rider's WebSocket connection:
                                              { driverLat, driverLng, ETA_to_destination }
                                                         │
                                              Rider app updates driver pin on map

Driver arrival at pickup:
  Driver presses "Arrived" → Trip status: DRIVER_ARRIVED
  Rider gets push notification: "Your driver has arrived"
  Rider sees: "Waiting" timer starts (free wait: 3 min)

WebSocket Connection Management:
  Each rider has a WebSocket connection to Tracking Service
  Connection stored in Redis: ws_session:{riderId} → { nodeId, connectionId }
  If rider reconnects → same nodeId → seamless resume
  Node crash → Redis tells another node to handle via consistent hashing
```

### 5.7 Pricing & Fare Calculation

```
Estimated Fare (pre-trip):
  base_fare + (per_km_rate × estimated_distance) + (per_min_rate × estimated_duration)
  × surge_multiplier
  + booking_fee

Final Fare (post-trip):
  base_fare + (per_km_rate × actual_GPS_distance) + (per_min_rate × actual_duration)
  × locked_surge_multiplier  (locked at booking time)
  + applicable_tolls
  − applied_coupon_discount

GPS distance = sum of Haversine distance between consecutive trip_location breadcrumbs
```

### 5.8 Payment Service

```
Payment flow:
  1. Rider's payment method pre-authorized at trip start
  2. On trip COMPLETED → Payment Service calculates final fare
  3. Charges rider's card / UPI / Uber Cash
  4. Splits: Uber commission (25%) + Driver payout (75%)
  5. Driver payout triggered weekly/instantly (Uber Pro Shield)

Payment gateway: Stripe / Razorpay / Braintree
Idempotency key: tripId (prevents double charge on retry)
Refund: on cancellation with fee → partial charge to rider
```

---

## 6. Geo-Spatial Design — S2 Cell Hierarchy

```
Google S2 Geometry Library:
  Earth divided into hierarchical cells
  Level 13 ≈ 1 km²  (surge pricing granularity)
  Level 15 ≈ 250 m² (driver proximity matching)
  Level 7  ≈ 10 km² (city-level aggregates)

Benefits:
  Consistent cell boundaries regardless of city shape
  Natural hierarchy for multi-level aggregation
  Efficient range queries using cell ID integer ranges

Usage in Uber:
  - Driver search: GEORADIUS on Level 15 cells
  - Surge pricing: demand/supply ratio on Level 13 cells
  - Analytics: trip density on Level 7 cells

Alternatively: Uber's own H3 hexagonal grid system
  Hexagons: no edge-adjacency problem (vs squares with corners)
  H3 resolution 9 ≈ 174 m diameter (driver matching)
  H3 resolution 7 ≈ 5.2 km (surge zones)
```

---

## 7. Pool Rides (UberPool)

```
Rider A requests Pool from Location X
  → Matched to available pool vehicle
  → Ride created with Rider A

Within 2 min:
  Rider B requests Pool from Location Y (nearby X)
  → Algorithm checks: can Rider B be added to Rider A's trip?
    Detour < 5 min for Rider A AND
    B's pickup is on/near route AND
    B's dropoff is before A's dropoff (roughly)
  → Yes → Rider B added to same vehicle
  → Both get notification: "Sharing your ride"

Algorithm: Traveling Salesman heuristic (greedy nearest insertion)
Max 2 riders per pool trip (Uber's practical limit)
```

---

## 8. Handling Edge Cases

| Scenario | Handling |
|---|---|
| **Driver GPS lost** | Last known location used for 30 sec; then marked OFFLINE |
| **Driver app crash during trip** | Trip remains IN_PROGRESS; driver reconnects → state restored; rider can call driver |
| **Payment failure at trip end** | Retry 3×; block rider's account if persistent; trip record marked PAYMENT_FAILED |
| **No drivers available** | Expand search radius; show wait time estimate; suggest trying in X min |
| **Rider and driver both cancel** | Separate cancellation flow; penalty applied based on who cancels and when |
| **GPS spoofing (driver fraud)** | Server-side validation: compare GPS with expected road speed; flag anomalies |
| **Traffic jam — ETA worsens** | Push updated ETA to rider every 30 sec; rider can cancel free within first 2 min |
| **Redis GEO node failure** | Redis Cluster replica promotion; brief matching degradation; fallback to DB geo queries |

---

## 9. Database Summary

| Data | Storage | Reason |
|---|---|---|
| Driver real-time location | Redis GEO | Geo-radius queries; ephemeral (latest only) |
| Driver availability status | Redis HASH | Sub-ms reads during matching |
| Driver assignment lock | Redis SET NX EX | Atomic; auto-expiry |
| Surge multipliers | Redis HASH | Per-cell; 60-sec refresh |
| Trips (active + history) | PostgreSQL | ACID for financial |
| Trip breadcrumb trail | Cassandra | High-write time-series |
| User profiles | PostgreSQL | Relational; stable |
| Ratings aggregates | PostgreSQL | Simple avg update per trip |

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Driver location store** | Redis GEO | O(log N) geo-radius; ephemeral (no history needed here) |
| **Driver assignment lock** | Redis SET NX EX | Atomic; auto-expire on crash; slight risk of duplicate if Redis restarts |
| **Matching algorithm** | Scored greedy (< 100ms) | Sub-optimal vs Hungarian algorithm (optimal but O(N³)) |
| **Surge pricing** | S2 cell demand/supply ratio (1 min refresh) | Coarse granularity; smooth enough for user experience |
| **Trip breadcrumbs** | Cassandra | Write-optimized; partition by trip_id; no complex queries |
| **ETA** | Maps API + ML correction factor | Accurate; maps API cost justified by UX impact |
| **Pool matching** | Greedy heuristic (2-min window) | Fast decision; not globally optimal |

---

## 11. Scalability

| Layer | Strategy |
|---|---|
| Location Service | Stateless; Redis handles 1M writes/sec (Cluster mode) |
| Matching Engine | Kafka consumer group; partitioned by cityId |
| Trip Service | Stateless; PostgreSQL read replicas for history queries |
| WebSocket Tracking | Sticky sessions; horizontal scale; 50M concurrent connections |
| Surge Engine | Scheduled Flink job; Redis for output |
| Maps/ETA API | Cached per (cell_origin, cell_dest) pair; minimize external API calls |

---

## 12. Monitoring & Observability

| Metric | Alert |
|---|---|
| **Driver match time** | P99 > 10 sec |
| **Match success rate** | < 90% (not enough drivers in area) |
| **ETA accuracy (MAPE)** | > 15% mean absolute percentage error |
| **Location update lag** | Driver position > 15 sec stale |
| **Trip cancellation rate** | > 10% (driver shortage / ETA issues) |
| **Surge area coverage** | > 40% of zones in surge → capacity alert |
| **Payment failure rate** | > 1% |

---

## 13. Future Enhancements
- **Autonomous vehicles** — replace driver app with self-driving vehicle telemetry.
- **Scheduled rides** — pre-book ride for tomorrow 6 AM airport pickup.
- **Multi-stop trips** — rider adds intermediate stops (pick-up a friend).
- **Carbon footprint tracking** — per-trip emissions + offset incentives.
- **Route sharing** — share live trip location with family for safety.

---

*Document prepared for SDE 3 system design interviews. Focus areas: real-time geo-tracking (Redis GEO + S2 cells), atomic driver assignment (Redis SET NX EX), trip state machine, surge pricing, ETA prediction, WebSocket tracking, and Pool ride algorithm.*
