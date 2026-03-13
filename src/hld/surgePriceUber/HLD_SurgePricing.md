# High-Level Design: Surge Pricing System — Uber / Lyft (SDE3 Interview)

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: what inputs drive surge, how frequently it updates, geographic granularity.
2. Establish scale, latency SLOs, and fairness constraints.
3. Walk through the surge computation pipeline (the core algorithm).
4. Deep-dive geospatial hexagonal grid (H3), demand/supply signals, and the pricing model.
5. Cover the price display flow, surge dampening, regulatory constraints, and failure handling.

---

## 1. Problem Statement And Scope

Design a **real-time, geo-aware pricing engine** that dynamically multiplies base fare by a surge multiplier when demand exceeds supply in a geographic region. The system must:

- Continuously ingest **supply signals** (driver locations, availability) and **demand signals** (ride requests, ETAs, cancellations).
- Divide the city into **geographic cells** and compute a surge multiplier per cell.
- Apply surge multiplier to **fare estimation** when riders request a price quote.
- Update surge multipliers in **near real-time** (< 1 minute lag).
- Ensure **price consistency**: rider shown ₹X must be charged ₹X even if surge changes before trip ends.
- Avoid **extreme volatility**: multiplier should not oscillate wildly within seconds.
- Adhere to **regulatory constraints**: many cities cap surge (e.g., ≤ 2× during declared emergencies in India).

### 1.1 Why Surge Pricing Exists (Economic Justification)

```
Problem without surge:
  During Diwali night / concert end / rainstorm:
    - 10,000 ride requests in 10 minutes.
    - 1,000 available drivers in the city.
    - Supply-demand ratio: 10:1.
    - Result: all riders wait 45+ minutes or get no ride.

With surge pricing:
    - Price rises to 2× → some price-sensitive riders defer their trip.
    - Higher earnings motivate off-duty drivers to come online.
    - Supply increases, demand decreases → equilibrium faster.
    - More riders actually get rides (at higher price) vs no rides (at normal price).

Surge is a market-clearing mechanism, not pure profit extraction.
```

### 1.2 In Scope
- Real-time supply/demand signal ingestion.
- Geospatial cell management (H3 hexagonal grid).
- Surge multiplier computation algorithm.
- Price lock/guarantee for in-flight trips.
- Surge display to rider (upfront pricing).
- Regulatory override and emergency cap.
- Surge dampening and smoothing.

### 1.3 Out Of Scope
- Base fare computation (distance × rate + time × rate + booking fee).
- Driver payment computation (driver earns more during surge).
- Full matching/dispatch engine.
- Fraud detection for artificially manufacturing surge.

---

## 2. Requirements

### 2.1 Functional Requirements
1. Ingest driver location pings every 4 seconds.
2. Ingest ride requests and cancellations in real-time.
3. Divide city into geographic hexagonal cells (H3 resolution 8: ~0.74 km² cells).
4. Compute surge multiplier (1.0× to 5.0×) per H3 cell every 30 seconds.
5. When rider opens app: return surge-adjusted upfront price for their ride.
6. Lock price at quote time: rider pays quoted price regardless of surge changes during trip.
7. Gradually smooth surge transitions: avoid jumping from 1.0× to 3.0× instantly.
8. Apply emergency cap: regulatory override sets max multiplier per city.
9. Driver earnings display: driver sees surge zone map overlaid on driver app.
10. Historical surge: "last 7 days" surge pattern for a city for analysis.

### 2.2 Non-Functional Requirements
- **Scale**: 100 cities; 10 million active drivers globally; 50 million active riders.
- **Write throughput**: 10M drivers × 1 ping/4s = 2.5M location pings/sec.
- **Read throughput**: Surge multiplier reads: 1M/sec (every ride request fetches surge for pickup H3 cell).
- **Surge update latency**: Cell surge updated within 60 seconds of supply-demand shift.
- **Price quote latency**: P99 < 200ms (total, to display surge-adjusted price).
- **Price consistency**: Quoted price must match charged price with 100% accuracy.
- **Availability**: 99.99% for price quote reads; 99.9% for surge computation pipeline.

---

## 3. Back-Of-The-Envelope Capacity Planning

```
Driver pings:
  10M drivers × 1 ping/4s = 2.5M events/sec.
  Per ping: { driver_id, lat, lng, status, timestamp } = ~100 bytes.
  Ingest bandwidth: 2.5M × 100 bytes = 250 MB/s.

Demand events (ride requests):
  Peak: 500K requests/hour → ~140 req/sec.
  Much lower volume than supply pings.

H3 cells per city:
  Typical large city (Mumbai): ~300 km² area at H3 resolution 8.
  300 km² / 0.74 km² per cell = ~405 H3 cells per city.
  100 cities × 405 cells = 40,500 total cells globally.

Surge state memory:
  40,500 cells × 1 KB per cell state = ~40 MB total.
  Fits entirely in a single Redis instance.

Driver-to-cell mapping:
  10M drivers → 40,500 cells → avg 247 drivers/cell.
  Per cell: track driver count, available driver count, request count.
  40,500 cells × (3 counters × 8 bytes) = ~1 MB.

Kafka throughput:
  250 MB/s driver pings → 250 Kafka partitions × 1 MB/s.
```

---

## 4. H3 Hexagonal Grid — Geographic Cell System (Deep Dive)

**H3 is Uber's open-source hierarchical hexagonal geospatial indexing system. Understanding it is mandatory for this HLD.**

### 4.1 Why Hexagons?

```
Option A: Square grid (lat/lng bounding boxes)
  Problem: Neighbors of a square share sides AND corners → distance to neighbor varies.
  Corner neighbors are √2 × farther than edge neighbors.
  → Unequal area coverage when measuring "radius of influence."
  → Weird edge effects at corners (demand leaks diagonally).

Option B: Circle-based regions
  Problem: Circles can't tile a plane without gaps or overlaps.
  → Riders in gaps belong to no region; riders in overlaps belong to two.

Option C: Hexagons (H3)
  Hexagon property: All 6 neighbors are equidistant from center.
  Equal-area tiling: hexagons tile the plane without gaps with uniform area.
  Compact path distances: 6 equidistant neighbors → spreading-wave effects are symmetric.

Hexagons are the optimal trade-off between representational simplicity and geometric uniformity.
```

### 4.2 H3 Resolution Levels

```
H3 uses 16 resolutions (0 = continent, 15 = 1m² cell).

Resolution | Avg Area      | Avg Edge Length | Use Case
---------- | ------------- | --------------- | ---------------------------
0          | 4,357,449 km² | 1,107 km        | Continental
3          | 12,393 km²    | 59 km           | Country-level
6          | 36 km²        | 3.2 km          | District-level
8          | 0.74 km²      | 461 m           | Surge pricing (neighborhood block)
9          | 0.10 km²      | 174 m           | Driver assignment
12         | 3,219 m²      | 29 m            | Building precision

Uber uses:
  Resolution 8 for surge pricing: ~1km² cells capture neighborhood demand patterns.
  Resolution 10 for driver-rider matching: ~15,000 m² for precise pickup.
  Resolution 6 for city-level analytics and heat maps.

H3 cell ID:
  64-bit integer uniquely identifying a hexagon.
  Encodes: resolution level + face (icosahedron face) + hierarchical position.
  Parent-child relationship: one res-8 cell contains exactly 7 res-9 cells.
    → Hierarchical rollup: city-level = union of district cells = union of neighborhood cells.
```

### 4.3 H3 Operations Used In Surge Pricing

```python
import h3

# Convert GPS coordinate to H3 cell at resolution 8
cell = h3.geo_to_h3(lat=19.0760, lng=72.8777, resolution=8)
# Returns: "881f823e1bfffff" (Mumbai Bandra area, res 8)

# Get all neighbors of a cell (for smoothing — Section 9)
neighbors = h3.k_ring(cell, k=1)   # k=1: cell + 6 immediate neighbors
neighbors_2_rings = h3.k_ring(cell, k=2)  # cell + 18 neighbors (2 rings)

# Convert cell to lat/lng centroid (for display)
centroid = h3.h3_to_geo(cell)  # center point of the hexagon

# Get boundary vertices (for rendering surge polygon on map)
boundary = h3.h3_to_geo_boundary(cell)  # list of (lat, lng) pairs

# Cell hierarchy: get parent at lower resolution
parent_res6 = h3.h3_to_parent(cell, resolution=6)  # district-level parent

# Get children at higher resolution
children_res9 = h3.h3_to_children(cell, resolution=9)  # 7 finer sub-cells

# Disk of cells within driving distance
nearby_cells = h3.k_ring(cell, k=3)  # all cells within 3 hexagons (~1.5 km)
```

### 4.4 Why H3 Over Simple Lat/Lng Binning

```
Naive binning: Floor(lat × 100) / 100 → 0.01° grid (~1.1 km × 0.9 km at equator).
Problems:
  - Cells are rectangles: different widths at different latitudes.
  - 8 neighbors with unequal distances (4 edge + 4 corner neighbors).
  - No hierarchical rollup for city/district aggregation.

H3 advantages:
  - Uniform hexagons regardless of latitude (geodesically corrected).
  - 6 equidistant neighbors → predictable smoothing kernels.
  - Hierarchical: automatically roll up res-8 cells to res-6 districts.
  - Cell IDs indexable: B-tree or hash index → O(1) lookup by cell ID.
  - Open source, battle-tested at Uber at city scale.
```

---

## 5. Surge Pricing Algorithm — Deep Dive

### 5.1 Core Supply-Demand Ratio

**The fundamental surge formula:**

```
Supply = number of available drivers within or adjacent to the cell.
Demand = number of unfulfilled ride requests in the cell in last N minutes.

Surge Multiplier = f(Demand / Supply)

Base function (simplified):
  ratio = demand / max(supply, 1)     // avoid division by zero
  multiplier = 1.0 + λ × (ratio - 1) // λ is elasticity constant
  multiplier = clamp(multiplier, 1.0, max_surge)

Example:
  supply = 10 drivers, demand = 30 requests → ratio = 3.0
  multiplier = 1.0 + 0.5 × (3.0 - 1) = 2.0×

  supply = 50 drivers, demand = 30 requests → ratio = 0.6
  multiplier = 1.0 + 0.5 × (0.6 - 1) = 0.8 → clamped to 1.0 (never below 1.0×)
```

### 5.2 Signals Used (Beyond Simple Count)

```
Supply signals:
  - Available drivers in cell: status=IDLE, last_ping < 30s ago.
  - Available drivers in adjacent cells (within 3-ring, ~1.5km):
    weighted by distance: ring-1 = 1.0×, ring-2 = 0.7×, ring-3 = 0.4×.
    → Driver 2 cells away can reach you, just slightly delayed.
  - Driver status: IDLE > ON_TRIP_RETURNING (return-leg drivers count as 0.5×).
  - Acceptance rate: drivers accepting requests in this cell (low acceptance = effective lower supply).

Demand signals:
  - Active ride requests in cell (last 5 minutes, not yet matched).
  - ETA inflation: if matching ETA > 8 min in this cell → demand-pressure signal.
  - Cancellation rate: high cancellations → riders frustrated → same as excess demand.
  - Anticipated demand (ML-predicted): events, weather, time-of-day patterns.
    → "Concert ends in 15 minutes at this venue" → pre-surge before demand spike hits.

Why adjacent cells for supply?
  A driver 1 km away can reach a rider within 3-4 minutes.
  Counting only drivers IN the cell ignores this effective supply.
  H3 k_ring(cell, k=3) captures drivers within ~1.5 km radius — effective supply pool.
```

### 5.3 Surge Multiplier Formula (Production-Grade)

```
Step 1: Compute smoothed supply
  effective_supply = Σ_{ring k=0..3} (drivers_in_ring[k] × ring_weight[k])
  ring_weights = [1.0, 0.8, 0.5, 0.3]

Step 2: Compute smoothed demand
  raw_demand = active_requests_last_5min + α × cancellations_last_5min
               + β × ETA_pressure                        // ETA > threshold adds demand signal
  α = 0.5 (cancellation implies frustrated demand)
  β = 0.3 (ETA inflation penalty)

Step 3: Compute raw multiplier
  ratio = raw_demand / max(effective_supply, min_supply_floor)
  // min_supply_floor prevents infinity when supply = 0

  raw_multiplier = base_multiplier_curve(ratio)
    where base_multiplier_curve is a piecewise function:
      ratio ≤ 0.5:  multiplier = 1.0   (supply adequate)
      0.5–1.0:      linear from 1.0 to 1.2
      1.0–2.0:      linear from 1.2 to 2.0
      2.0–3.0:      linear from 2.0 to 2.8
      3.0–5.0:      linear from 2.8 to 4.0
      > 5.0:        capped at max_surge (4.0× or regulatory cap)

  This piecewise curve is gentler at low ratios and steeper at high ratios.
  Custom per-city based on historical demand elasticity.

Step 4: Apply ML adjustment
  ml_adjustment = ml_model.predict(features: [hour_of_day, day_of_week,
                                                rain_probability, event_nearby,
                                                historical_demand_this_cell_this_hour])
  // ml_adjustment is a multiplicative correction: typically 0.8x to 1.3x
  adjusted_multiplier = raw_multiplier × ml_adjustment

Step 5: Temporal smoothing (exponential moving average)
  prev_multiplier = get_current_multiplier(cell_id)
  new_multiplier = α × adjusted_multiplier + (1-α) × prev_multiplier
  α = 0.3   // 30% weight to new value, 70% to existing → smooth transitions

Step 6: Regulatory cap
  final_multiplier = min(new_multiplier, regulatory_cap[city])
  final_multiplier = round(final_multiplier, 1)  // round to 1 decimal: 1.8, 2.3, etc.
```

### 5.4 Temporal Smoothing — Why It Matters

```
Problem without smoothing:
  Second 0: ratio = 1.2 → multiplier = 1.4
  Second 5: ratio = 3.8 → multiplier = 3.5  (sudden spike)
  Second 10: ratio = 1.5 → multiplier = 1.8 (spike gone)

  User sees: "₹150 → ₹350 → ₹190 → ₹280" in 30 seconds.
  Rider experience: confusing, feels like bait-and-switch.
  Driver experience: surge zone appears/disappears too fast to react to.

With exponential moving average (α = 0.3):
  Second 0: mult = 1.4
  Second 5: mult = 0.3×3.5 + 0.7×1.4 = 1.05 + 0.98 = 2.03  (damped spike)
  Second 10: mult = 0.3×1.8 + 0.7×2.03 = 0.54 + 1.42 = 1.96  (gradual decay)
  
  User sees: 1.4 → 2.0 → 1.96 → ...  (smooth, comprehensible progression)

Additional surge step constraints:
  Max increase per interval: +1.0× per 30-second interval.
  Max decrease per interval: -0.5× per 30-second interval.
  → Surge rises quickly (responsive to demand) but falls slowly (prevents driver exodus).
```

### 5.5 ML-Based Predictive Surge (Pre-Surge)

```
Reactive surge: compute surge AFTER demand spike happens.
  Downside: Riders experience poor availability for 5-10 minutes before surge kicks in.

Predictive surge: compute surge BEFORE demand spike, based on leading indicators.

Feature engineering:
  - Time: hour, day_of_week, day_of_month (month-end high demand).
  - Events: concert at venue X ends at 10 PM (scraped from Ticketmaster/BookMyShow API).
  - Weather: rain probability in next 30 min (Dark Sky / IMD API).
  - Historical pattern: 7-day avg demand for this cell at this hour.
  - Current trajectory: rate of request growth in last 3 minutes (leading indicator).
  - Nearby cell surge: if adjacent cells already high → likely to spread.

ML model: Gradient Boosted Tree (LightGBM).
  Training: 2 years of historical demand × surge correlation data.
  Inference: runs every 5 minutes per cell.
  Output: demand_multiplier_adjustment ∈ [0.8, 1.4] applied to current raw multiplier.

Example:
  Cell: Mumbai Bandra-Kurla Complex, 6 PM Friday.
  Feature: "BKC Happy Hour ending" → high confidence of 7 PM surge.
  Predictive surge activates at 6:45 PM to 1.5× before actual demand hits.
  Drivers pre-positioned in BKC → supply increases before demand peaks.
  Result: smaller spike, shorter wait times, better experience.
```

---

## 6. Data Model

### 6.1 Cell Surge State

```sql
-- Real-time surge state per cell (primary store: Redis; backed by PostgreSQL)
CREATE TABLE cell_surge_state (
  cell_id           VARCHAR(16)    PRIMARY KEY,    -- H3 cell ID e.g. "881f823e1bfffff"
  city_id           VARCHAR(32)    NOT NULL,
  current_multiplier DECIMAL(3,1)  NOT NULL DEFAULT 1.0,
  raw_multiplier    DECIMAL(4,2),                 -- before smoothing/cap
  effective_supply  DECIMAL(8,2),                 -- weighted driver count
  raw_demand        DECIMAL(8,2),                 -- demand signal
  demand_supply_ratio DECIMAL(6,3),
  last_computed_at  TIMESTAMP      NOT NULL,
  next_recompute_at TIMESTAMP,
  regulatory_cap    DECIMAL(3,1),                 -- NULL = no cap; set during emergencies
  updated_at        TIMESTAMP      NOT NULL
);

-- Redis representation (primary, fast access):
-- Key: surge:cell:{cell_id}
-- Type: Redis Hash
-- Fields: { multiplier, supply, demand, ratio, computed_at }
-- TTL: 120 seconds (auto-expire: if compute pipeline dies, surge falls to 1.0× safely)
```

### 6.2 Driver Location State

```sql
-- Real-time driver location (Redis primary; PostgreSQL for audit)
-- Redis: GEOADD drivers:city:{city_id} lng lat driver_id

CREATE TABLE driver_locations (
  driver_id         VARCHAR(64)    PRIMARY KEY,
  city_id           VARCHAR(32)    NOT NULL,
  lat               DECIMAL(10,7)  NOT NULL,
  lng               DECIMAL(11,7)  NOT NULL,
  h3_cell_8         VARCHAR(16)    NOT NULL,     -- precomputed H3 cell for fast lookup
  status            ENUM('IDLE','ON_TRIP','OFFLINE','RETURNING') NOT NULL,
  last_ping_at      TIMESTAMP      NOT NULL,
  accuracy_meters   INT,
  INDEX (city_id, h3_cell_8, status)              -- fast count per cell per status
);
```

### 6.3 Price Lock (Upfront Pricing Guarantee)

```sql
-- Locks in the quoted price for a rider at request time
CREATE TABLE price_locks (
  lock_id           VARCHAR(64)    PRIMARY KEY,
  rider_id          VARCHAR(64)    NOT NULL,
  pickup_cell_id    VARCHAR(16)    NOT NULL,
  dropoff_cell_id   VARCHAR(16)    NOT NULL,
  surge_multiplier  DECIMAL(3,1)   NOT NULL,    -- multiplier AT time of quote
  base_fare         DECIMAL(8,2)   NOT NULL,
  total_fare        DECIMAL(8,2)   NOT NULL,    -- base × surge (quoted to rider)
  currency          CHAR(3)        NOT NULL DEFAULT 'INR',
  quoted_at         TIMESTAMP      NOT NULL,
  expires_at        TIMESTAMP      NOT NULL,    -- lock valid for 5 minutes
  used_at           TIMESTAMP,                  -- populated when ride actually starts
  status            ENUM('active','used','expired') NOT NULL,
  INDEX (rider_id, quoted_at DESC),
  INDEX (expires_at)                           -- for cleanup job
);
```

### 6.4 Surge History (For Analytics)

```sql
-- Periodic snapshot of all cell surges (one row per cell per interval)
CREATE TABLE surge_history (
  cell_id           VARCHAR(16)    NOT NULL,
  snapshot_at       TIMESTAMP      NOT NULL,
  multiplier        DECIMAL(3,1)   NOT NULL,
  supply            DECIMAL(8,2)   NOT NULL,
  demand            DECIMAL(8,2)   NOT NULL,
  PRIMARY KEY (cell_id, snapshot_at)
);
-- Partitioned by month. Archived to S3 Parquet after 90 days.
-- Used for: historical surge heatmaps, ML training data, regulatory reporting.
```

---

## 7. High-Level Architecture

```text
+-------------------- Data Sources --------------------+
| Driver Apps (GPS pings) | Rider App (requests) | Events API |
+----+-------------------+-----------+---------+---+----------+
     |                               |             |
     v                               v             v
+----+-------------------------------+--------+----+--------+
|                  Ingest Gateway (Auth + Rate Limit)       |
+----+--------------------+----------+---------+-----------+
     |                    |          |
     v                    v          v
+----+-------+  +---------+-+  +----+--------+
|Driver Ping |  |Demand    |  |External      |
|Kafka Topic |  |Kafka     |  |Events Kafka  |
|(250 MB/s)  |  |Topic     |  |(weather,     |
|250 partns  |  |          |  | events)      |
+----+-------+  +-----+----+  +----+---------+
     |                |             |
     v                v             v
+----+----------------+-------------+----------+
|  Location Processor  |   Demand Aggregator   |
|  (Flink)             |   (Flink)             |
|  - H3 cell compute   |   - Requests per cell |
|  - Driver state      |   - ETA signals       |
|  - GEOADD to Redis   |   - Cancellation rate |
+----------+-----------+   +-------------------+
           |                       |
           v                       v
    +------+------------------------+-------+
    |           Surge Computation Engine    |
    |           (runs every 30s per cell)   |
    |                                       |
    |  For each cell:                       |
    |  1. Read supply from Redis GEODATA   |
    |  2. Read demand from Demand Aggreg.  |
    |  3. Apply surge formula + smoothing  |
    |  4. ML adjustment                    |
    |  5. Regulatory cap                   |
    |  6. Update Redis surge:{cell_id}     |
    +-------------------+-------------------+
                        |
          +-------------+-------------+
          |             |             |
          v             v             v
    +---------+   +---------+   +---------+
    |Surge    |   |Price    |   |Surge    |
    |API Svc  |   |Estimate |   |History  |
    |(read    |   |Service  |   |Writer   |
    | Redis)  |   |(fare ×  |   |(PG +   |
    |         |   | surge)  |   | S3)    |
    +----+----+   +----+----+   +---------+
         |             |
    +----+----+   +----+----+
    |Client   |   |Price    |
    |Apps     |   |Lock     |
    |(surge   |   |Store    |
    | display)|   |(Redis + |
    |         |   | PG)    |
    +---------+   +---------+
```

---

## 8. Detailed Component Design

### 8.1 Location Processor (Flink) — High-Throughput Driver Signal

**Processes 2.5M driver pings/sec and maintains real-time geo-state.**

```
Input: Kafka driver_pings topic (250 MB/s).
Partition key: driver_id → ensures all pings for a driver go to same Flink task.

For each ping { driver_id, lat, lng, status, timestamp }:

Step 1: Validate ping
  - Timestamp: within 30 seconds of now (reject stale pings).
  - GPS accuracy: accuracy_meters < 50 (reject noisy GPS readings).
  - Speed sanity: distance from last location / time_elapsed < 150 km/h.
    → Impossible speed → GPS glitch → use last known location.

Step 2: Compute H3 cell
  cell_id = h3.geo_to_h3(lat, lng, resolution=8)
  // This is O(1) math — no database lookup needed.

Step 3: Update Redis
  Multi-command pipeline:
    // Geo-index for spatial queries
    GEOADD drivers:active:{city_id} lng lat driver_id
    // Per-cell count (for fast supply computation WITHOUT geo query)
    HSET driver:{driver_id} cell cell_id status status last_ping now
    // EX 60: driver considered offline if no ping in 60s
    SET driver_alive:{driver_id} 1 EX 60
    // Cell occupancy counters
    HINCRBY cell_counts:{cell_id} total_drivers 1
    HINCRBY cell_counts:{cell_id} idle_drivers (1 if status=IDLE else 0)

Step 4: On cell change (driver moved to new cell)
  → HINCRBY cell_counts:{old_cell} total_drivers -1
  → HINCRBY cell_counts:{new_cell} total_drivers +1
  → (Requires Flink state to remember driver's previous cell)

Step 5: Stale driver cleanup
  → Periodic job (every 30s): scan expiring driver_alive keys.
  → On expiry: HINCRBY cell_counts:{driver_cell} total_drivers -1 (decrement cell counter).
  
Throughput: 2.5M pings/sec → Flink tasks (250 tasks, one per Kafka partition) → Redis pipeline.
Redis pipeline: 4 commands per ping, batched → ~10M Redis ops/sec handled by Redis Cluster.
```

### 8.2 Demand Aggregator (Flink)

```
Input: Kafka demand_events topic (ride requests, cancellations, ETA events).

Tumbling window (5-minute windows):
  For each demand event:
    cell_id = h3.geo_to_h3(event.pickup_lat, event.pickup_lng, resolution=8)
    → Route event to per-cell accumulator.
  
  At window close (every 5 minutes):
    For each cell: emit { cell_id, requests_count, cancellations_count, avg_eta, avg_wait_time }
    Write to Redis: HSET demand:{cell_id} requests {count} cancellations {count} ...
    TTL: 10 minutes (stale demand auto-expires).

ETA monitoring (real-time, not windowed):
  → When driver assignment results in ETA > 8 min for a cell:
    → HINCRBY demand:{cell_id} eta_pressure_score 1
    → This feeds into surge computation as leading demand indicator.
```

### 8.3 Surge Computation Engine — Scheduled Orchestrator

**The brain — runs every 30 seconds, one computation job per active H3 cell.**

```
Scheduler: Kubernetes CronJob or Flink ProcessingTimeWindow(30s).

For each active cell in city (cells with recent driver/rider activity):

Step 1: Read signals (all from Redis, sub-millisecond)
  supply_data  = HGETALL cell_counts:{cell_id}
  demand_data  = HGETALL demand:{cell_id}
  prev_mult    = HGET surge:cell:{cell_id} multiplier

Step 2: Compute effective supply (H3 k_ring weighted)
  ring_cells = h3.k_ring(cell_id, k=3)  // 37 cells in 3 rings
  effective_supply = 0
  for each ring_cell, ring_level in ring_cells:
    supply = HGET cell_counts:{ring_cell} idle_drivers  // from Redis
    effective_supply += supply × ring_weight[ring_level]

Step 3: Compute raw demand
  raw_demand = demand_data.requests + 0.5×demand_data.cancellations
               + 0.3×demand_data.eta_pressure_score

Step 4: Apply surge formula (Section 5.3)
  ratio = raw_demand / max(effective_supply, 1)
  raw_mult = base_curve(ratio)

Step 5: ML adjustment
  features = { cell_id, hour, day_of_week, rain_prob, events_nearby, ... }
  ml_factor = ml_inference_service.predict(features)  // gRPC call, < 10ms
  adjusted = raw_mult × ml_factor

Step 6: Temporal smoothing
  new_mult = 0.3 × adjusted + 0.7 × float(prev_mult or 1.0)
  // Apply max change constraints
  delta = new_mult - float(prev_mult or 1.0)
  if delta > 1.0: new_mult = float(prev_mult) + 1.0   // max +1.0 per interval
  if delta < -0.5: new_mult = float(prev_mult) - 0.5  // max -0.5 per interval

Step 7: Regulatory cap
  cap = get_regulatory_cap(city_id)  // Redis GET regulatory_cap:{city_id}
  final_mult = min(new_mult, cap)
  final_mult = round(final_mult, 1)  // 1.8, 2.3, etc.

Step 8: Write to Redis
  HSET surge:cell:{cell_id} multiplier {final_mult} supply {eff_supply}
                             demand {raw_demand} ratio {ratio} computed_at {now}
  EXPIRE surge:cell:{cell_id} 120    // auto-expire to 1.0 if compute engine dies

Step 9: Emit to history
  Kafka topic: surge_snapshots { cell_id, final_mult, eff_supply, raw_demand, timestamp }
  → Consumed by: surge_history_writer (PostgreSQL) + ML training pipeline.

Step 10: Push to websocket subscribers
  If |final_mult - prev_mult| >= 0.2:  // only push if meaningful change
    Publish to Redis pub/sub: surge_updates:{city_id}
    { cell_id: final_mult }  // driver app subscribed for surge map updates
```

### 8.4 Price Estimate Service — The Read Hot Path

**Called 1M times/sec when riders request ride price quotes.**

```
GET /v1/fare/estimate?pickup_lat=19.076&pickup_lng=72.877&drop_lat=...&drop_lng=...

Step 1: Compute H3 cell for pickup location
  cell_id = h3.geo_to_h3(pickup_lat, pickup_lng, RESOLUTION_8)
  // O(1) math, < 0.1ms.

Step 2: Fetch surge multiplier
  multiplier = HGET surge:cell:{cell_id} multiplier
  // Redis read, < 1ms.
  // If key missing (cell not active): multiplier = 1.0 (no surge).

Step 3: Compute base fare
  base_fare = compute_base_fare(route_distance_km, route_duration_min,
                                 time_of_day, vehicle_type)
  // External call to Pricing service: < 20ms (cache route for common origin/dest pairs).

Step 4: Apply surge
  surge_fare = base_fare × multiplier

Step 5: Create price lock
  lock_id = UUID()
  INSERT INTO price_locks (lock_id, rider_id, surge_multiplier, base_fare, total_fare, ...)
  // Also write to Redis for fast lookup: SET price_lock:{lock_id} {json} EX 300  (5 min)

Step 6: Return to rider
  {
    "estimate_id": lock_id,
    "base_fare": 150.00,
    "surge_multiplier": 1.8,
    "total_fare": 270.00,
    "surge_message": "High demand. Fare is 1.8× normal.",
    "price_locked_until": "2024-03-11T22:05:00Z",
    "currency": "INR"
  }

Total latency: cell_id (0.1ms) + Redis (1ms) + base_fare (20ms) + lock_write (5ms) ≈ 26ms.
Caching: for same pickup cell and vehicle type, base_fare is cached in Redis TTL 60s.
```

### 8.5 Price Lock — Fare Guarantee

**The most important correctness guarantee: the price shown must equal the price charged.**

```
Why price locking is critical:
  Rider sees ₹270 at 10:00:05 PM.
  Driver accepts at 10:00:35 PM. Surge changes to 2.3× at 10:00:30 PM.
  Without lock: rider charged 2.3× → ₹345. User fury, charge-backs, app deletion.
  With lock: rider pays ₹270 regardless of surge change. Trust maintained.

Lock lifecycle:
  Created at: quote time (Step 5 above).
  Valid for: 5 minutes (enough time for driver to accept).
  Used when: driver accepts → lock_id passed to trip creation → charges = lock.total_fare.
  Expires if: no driver accepts within 5 min → lock expires → new estimate required.
  
  New estimate after expiry may show different surge → rider must re-confirm.
  This is shown in app: "Price changed. Confirm ₹330?" (with breakdown).

Lock storage:
  Redis (primary): SET price_lock:{lock_id} {json} EX 300  // 5-minute TTL
  PostgreSQL (durable): INSERT price_locks (for audit, billing, disputes).

On trip completion — charging:
  Trip.fare = price_locks.total_fare WHERE lock_id = trip.price_lock_id
  // Always use lock's stored fare, NEVER recompute from current surge.
  // Lock_id is on the Trip record → irrefutable audit trail.

What if rider holds lock past 5 minutes?
  Lock EXPIRES in Redis. PHP client retries → new quote = new lock.
  App shows: "Your estimated price has expired. Current price: ₹340."
```

### 8.6 Surge Display On Apps

**How surge is shown to riders and drivers.**

```
Rider App:
  1. Map overlay: colored hexagons showing surge zones.
     Color coding: Green (1.0×), Yellow (1.3×), Orange (1.8×), Red (2.5×+).
  2. Fare breakdown: shows "1.8× surge multiplier applies."
  3. Surge explanation: "High demand due to heavy rain. Prices will stabilize soon."
  4. "Surge-free at drop price" timer: if surge < 1.5×, show estimated time for surge to drop.
     (Computed from historical surge patterns for this cell/time.)

Driver App:
  1. Surge heat map on driver navigation screen.
  2. Surge zones highlighted with multiplier: "2.0× here" with glowing animation.
  3. Notification: "High demand in Andheri East (2.1× surge). Drive there now."
     Notification triggers when:
       - A nearby cell (within 5 km) goes from 1.0→1.5+.
       - Driver is idle.
       - Only 1 alert per 5 minutes per driver (prevents alert fatigue).

Surge real-time update (driver app):
  Driver app maintains WebSocket to surge-feed server.
  Redis pub/sub: surge_updates:{city_id} → surge-feed pods → WebSocket push.
  Push frequency: max 1 update/30s per cell (not every computation — only on significant change).
  Delta threshold: only push if multiplier changes by ≥ 0.2 (avoid constant small fluctuations).
```

---

## 9. Surge Smoothing Across Adjacent Cells

**A key UX concern: sharp boundary between 3.0× cell and adjacent 1.0× cell creates gaming.**

```
Problem: Riders open app, see 3.0× for their pickup, walk 500m to cell boundary,
         request at 1.0× → smart but frustrating system design ("surge dodging").

Also: If cell A = 3.0× and adjacent cell B = 1.0×, ALL drivers rush to cell B
      (lower surge = driver earnings are equal regardless, but riders prefer A).
      Result: Asymmetric driver distribution exacerbates the very imbalance surge tried to fix.

Solution: Spatial smoothing using H3 neighbor averaging.

Spatial smoothing algorithm:
  For each cell C:
    neighbors = h3.k_ring(C, k=2)  // C + 18 neighbors (2 rings)
    spatial_smoothed_mult = Σ (neighbor_mult × spatial_weight) / Σ weights
    
    where spatial_weight[ring_0] = 1.0 (self)
          spatial_weight[ring_1] = 0.4 (first ring neighbors)
          spatial_weight[ring_2] = 0.1 (second ring neighbors)
    
    final_mult = 0.7 × raw_computed_mult + 0.3 × spatial_smoothed_mult

Effect:
  Cell A = 3.0×, all neighbors = 1.0× (isolated spike):
    spatial_smoothed = (3.0×1.0 + 6×1.0×0.4 + 12×1.0×0.1) / (1+6×0.4+12×0.1)
                     = (3.0 + 2.4 + 1.2) / (1 + 2.4 + 1.2) = 6.6/4.6 = 1.43
    final_mult = 0.7×3.0 + 0.3×1.43 = 2.1 + 0.43 = 2.53 (cell A reduced from 3.0→2.53)

  Adjacent cell B = 1.0× (next to cell A = 3.0×):
    spatial_smoothed ≈ (1.0×1.0 + 1×3.0×0.4 + other_neighbors×1.0×0.4...) / denom
                     ≈ 1.3 (slightly elevated)
    final_mult = 0.7×1.0 + 0.3×1.3 = 0.7 + 0.39 = 1.09 (slightly above 1.0)

  Result: Gradient between cells (2.53× vs 1.09×) not cliff (3.0× vs 1.0×).
  Surge dodging still works but is less dramatic → less gaming incentive.
```

---

## 10. Multi-City And Regulatory Management

### 10.1 City Configuration

```
Each city has independent configuration:
  {
    "city_id": "mumbai",
    "h3_resolution": 8,
    "max_surge": 4.0,
    "regulatory_cap": 2.0,        // India ONDC cap during festivals (example)
    "emergency_cap": 1.5,         // Set during floods, declared disasters
    "surge_update_interval_sec": 30,
    "smoothing_alpha": 0.3,
    "ring_weights": [1.0, 0.8, 0.5, 0.3],
    "min_supply_floor": 1,
    "elasticity_lambda": 0.5
  }

Stored in: Redis hash city_config:{city_id} + PostgreSQL (source of truth).
Override: admin panel can set emergency_cap via HSET city_config:{city_id} emergency_cap 1.5.

Regulatory cap enforcement is ALWAYS the final step in surge computation →
No code path can produce a final multiplier exceeding the regulatory cap.
Audit log written on every cap application for regulatory compliance.
```

### 10.2 "Emergency Mode" Override

```
Trigger: Natural disaster, government directive, safety event.
Admin action: POST /admin/v1/cities/{city_id}/emergency_mode { cap: 1.5 }

Effect:
  - Redis: SET regulatory_cap:{city_id} 1.5 (immediate, all compute tasks pick up on next cycle).
  - All running surge computations re-cap final_multiplier to 1.5.
  - Active price locks that exceed 1.5× are VOIDED → must re-quote at capped price.
    (Riders notified: "Government directive: price reduced to ₹180 (from ₹280)").
  - Riders get refund if trip already started at higher surge: credits added to account.
  - Audit log: who set the cap, timestamp, duration, regulatory reference.

This is a special case where price locks CAN be overridden (for regulatory compliance).
The system treats emergency mode as a "force discount" event, not a normal surge update.
```

---

## 11. Failure Handling

### 11.1 Surge Computation Engine Failure

```
The surge TTL (120 seconds on Redis keys) is the safety mechanism.

If surge computation engine crashes:
  → No one updates surge:cell:{cell_id} keys.
  → After 120 seconds: all keys expire.
  → Price Estimate Service: HGET surge:cell:{cell_id} → key not found → returns 1.0×.
  → Riders get normal (non-surge) pricing. Uber loses surge revenue, NOT riders overcharged.

Fail-safe design principle: Always fail toward customer safety (1.0× default), not business revenue.

Recovery:
  → Kubernetes restarts crashed pod automatically.
  → New instance reads current cell states from Redis (driver counts still fresh).
  → Recomputes surge from scratch for all active cells within 60 seconds.

Active-active: Run 2 surge computation instances.
  → Both compute surge independently.
  → Redis HSET is idempotent: second write of same value is harmless.
  → Split-brain: both write slightly different values (race condition) → last writer wins.
    → Maximum error: 0.1× difference (both reading similar input data).
    → Acceptable: riders see 1.8× instead of 1.9×. Not a correctness issue.
```

### 11.2 Redis Failure

```
Redis is the bottleneck single point of failure for real-time surge reads.

Mitigations:
  1. Redis Cluster: 6 primary + 6 replica shards.
     - Hash slot for surge:cell:{cell_id}: consistent hash.
     - Shard failure → automatic failover to replica (< 30s).
  
  2. Price Estimate Service in-process cache:
     - AsyncLoadingCache: each service instance caches surge per cell ID.
     - TTL: 60 seconds.
     - On Redis failure: serve 60s-stale cached surge values.
     - Most reads hit local cache (high temporal locality — same cell queried repeatedly).
     - On Redis recovery: cache TTL expires → fresh values loaded.
  
  3. Last resort: static surge fallback.
     - Config-driven default surge schedule: "Friday 10 PM → 1.5× in downtown."
     - Loaded from: Config file (cold path) → S3 (durable) → served during extended Redis outage.
```

### 11.3 Stale Driver Pings

```
Driver app loses connectivity (underground parking, building shadow):
  - Last good ping: 10:00 PM.
  - Driver is IDLE but app is offline.
  - Driver appears "available" in cell but can't receive trip requests.

Detection:
  - driver_alive:{driver_id} key expires after 60 seconds of no ping.
  - Expiry event (Redis keyspace notification) → decrements cell_counts:{cell_id} idle_drivers.

Effective supply correction:
  - Supply formula uses idle_drivers from cell_counts.
  - On driver expiry: cell_counts updated → next surge computation cycle sees lower supply.
  - Maximum error window: 60s driver expiry + 30s surge recompute = 90s lag.
  - Acceptable: 90-second lag before detecting a driver going offline.
```

---

## 12. Observability And SLOs

### 12.1 SLO Targets

| Metric | SLO | Alert |
|---|---|---|
| Price estimate API P99 latency | < 200ms | > 500ms |
| Surge recompute freshness | < 60s per cell | > 90s lag |
| Price lock correctness | 100% (charge = quoted price) | Any mismatch = P0 |
| Regulatory cap violations | 0 | Any violation = P0 |
| Driver ping processing lag | < 5s P99 | > 30s |
| Surge feed WebSocket push lag | < 1s P95 | > 5s |

### 12.2 Key Metrics

```
Business:
  - Surge multiplier distribution (histogram): % of trips at 1.0, 1.5, 2.0, 2.5, 3.0+.
  - Demand conversion rate at surge (ride requests completed / opened): lower at higher surge.
  - Driver hour increase during surge: more drivers go online when surge appears?

Technical:
  - cell_counts accuracy: compare Redis counts with GPS-verified count (periodic audit).
  - Surge computation latency per cell (should be < 500ms in P99).
  - Price lock expiry rate: % of locks that expire before driver accepts (demand-side signal).
  - Kafka consumer lag for driver pings (alert if > 10K messages behind).

Fairness:
  - Average surge multiplier by neighborhood (equity monitoring).
    Rich neighborhoods vs low-income neighborhoods should not consistently have different surges.
  - Response to rain events: does surge activate within 5 minutes of rain start?
```

---

## 13. Major Trade-Offs And Why

### 13.1 Reactive vs Predictive Surge

- **Reactive**: Responds only to observed demand/supply. Simple, accurate, 5-10 min lag on demand spikes.
- **Predictive (ML)**: Pre-surges before demand hits. Better driver pre-positioning. Risk of false positives (surge for event that didn't cause demand).
- **Decision**: Reactive as base; ML adjustment factor (multiplicative, bounded 0.8-1.4). Best of both.

### 13.2 Cell Granularity (H3 Resolution)

- **Coarser (res 6, 36 km²)**: Less cells to compute, smoother surge map, loses neighborhood precision.
- **Finer (res 10, 0.1 km²)**: 7× more cells, precise, but sparse data per cell → noisy surge.
- **Resolution 8 (0.74 km²)**: ~1km blocks — captures neighborhood demand without data sparsity. Uber's production choice.

### 13.3 Price Lock Duration

- **Longer lock (15 min)**: Better rider experience, rider can wait without re-quoting. Surge might drop → Uber loses revenue at high surge.
- **Shorter lock (2 min)**: More re-quotes, more friction. More accurate to current surge.
- **Decision**: 5 minutes. Long enough for driver acceptance; short enough to limit Uber's exposure to surge-change mismatch.

### 13.4 Per-Cell vs Global Surge

- **Global city surge** (1 single multiplier per city): Simple, can't target hot spots, drivers don't know where to go.
- **Per-cell surge**: Precise demand signal, drivers navigate to high-surge zones, better supply redistribution.
- **Decision**: Per H3 cell at resolution 8. Aggregated to res-6 for city-wide overview display.

### 13.5 Hard Cap vs Smooth Curve

- **Hard cap** (e.g., max 4×): Simple, transparent. Demand-supply problem unresolved above cap.
- **Smooth asymptotic curve**: No hard ceiling; multiplier grows unboundedly with ratio; complex to reason about.
- **Decision**: Piecewise linear with capped maximum. Transparent to regulators; configurable per city. Regulatory override always triumphs.

---

## 14. Interview-Ready Deep Dive Talking Points

**"How does H3 work and why hexagons?"**
> H3 divides Earth into hierarchical hexagons at 16 resolution levels. At resolution 8: ~0.74 km² cells. Hexagons have 6 equidistant neighbors (vs squares with 4+4 unequal neighbors), enabling symmetric smoothing kernels. H3 cell IDs are 64-bit integers — O(1) GPS → cell conversion via `geo_to_h3(lat, lng, resolution)`. Parent-child hierarchy allows rollup from neighborhood → district → city.

**"How do you compute surge without querying millions of driver rows?"**
> Pre-aggregated cell counters in Redis Hash. On each driver ping, Location Processor increments the driver's H3 cell counter (HINCRBY cell_counts:{cell_id} idle_drivers). Surge computation reads HGET cell_counts for the cell and its 3-ring H3 neighbors — 37 Redis reads total. No SQL involved. Stale drivers auto-expire via driver_alive:{driver_id} key TTL (60s), which triggers cell counter decrement.

**"How do you guarantee the price shown equals the price charged?"**
> At quote time, a price_lock record is created storing the surge multiplier and total fare as of that moment. The lock_id accompanies the entire trip lifecycle. At billing, the system reads price_locks.total_fare — never recomputes from current surge. Lock TTL = 5 minutes; expired locks require re-quote. Regulatory emergency mode is the only exception: locks voided, riders credited, audit log written.

**"How do you prevent surge oscillation (1.0 → 3.0 → 1.0 → 3.0 every 30s)?"**
> Three dampening mechanisms: (1) Exponential moving average: 30% new value, 70% previous — filters rapid swings. (2) Max delta constraints: +1.0× max increase and -0.5× max decrease per 30s interval — surge rises fast but falls slowly. (3) Spatial smoothing: final multiplier blended with H3 neighbor averages — prevents isolated spikes and cliff-edge boundaries.

**"What happens if the surge engine crashes?"**
> Redis keys for surge have a 120-second TTL. If the engine stops updating them, they expire. Price Estimate Service reads missing key → defaults to 1.0× multiplier. System fails safe (no surge) rather than charging riders for unavailable service. Surge computation is stateless (reads current driver counts from Redis on restart) — recovers within 60 seconds, even if restarting from scratch.

---

## 15. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Why surge pricing exists (economics), in-scope vs out-of-scope |
| 5–13 min | H3 hexagonal grid: why hexagons, resolution choices, key operations |
| 13–22 min | Surge algorithm: supply signals, demand signals, formula, smoothing, ML adjustment |
| 22–30 min | Architecture: Location Processor (Flink), Demand Aggregator, Surge Computation Engine |
| 30–37 min | Price Estimate Service read hot path, price lock, correctness guarantee |
| 37–43 min | Failure handling (TTL safety net), regulatory cap, surge display on apps |
| 43–45 min | Trade-offs and extensions |

---

## 16. Extensions To Mention If Time Permits

- **Upfront pricing for entire trip** (not just surge): lock route distance + surge at booking → no fare surprises on toll roads or traffic.
- **Carpooling surge**: lower surge for shared rides to attract price-sensitive riders and maximize driver utilization.
- **Driver surge guarantee**: "Earn at least 2.0× for the next 30 minutes if you drive to this zone." Reduces driver gaming (cherry-pick only peak-surge trips).
- **Surge subscription / surge-free pass**: Premium riders pay monthly fee for surge-free rides up to a cap per month (Netflix model).
- **Demand forecasting dashboard**: City ops team sees predicted demand heatmaps 2 hours ahead. Used for driver incentive programs ("drive here at this time, guaranteed bonus").
- **A/B testing surge curves**: Serve different elasticity curves to rider cohorts, measure demand response → optimize curve to maximize both ride completion and revenue.
- **Competitor surge parity**: Monitor competitor app (Ola/Rapido) pricing → adjust own surge to remain competitive (anti-churn).
