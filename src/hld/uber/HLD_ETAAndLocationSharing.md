# HLD — ETA Service & Real-Time Location Sharing (Driver ↔ Rider)

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** Geospatial Systems / Real-Time Streaming / Graph Algorithms
> **Real-world Analogue:** Uber, Lyft, Ola, Grab — live driver tracking + ETA

---

## How to Navigate This in 45 Minutes

```
[0:00 -  3:00]  Step 1: Clarify Requirements
[3:00 -  6:00]  Step 2: Capacity Estimation
[6:00 -  9:00]  Step 3: API Design
[9:00 - 20:00]  Step 4: High-Level Architecture
[20:00 - 38:00] Step 5: Deep Dives (pick 3 from 5)
[38:00 - 42:00] Step 6: Scale & Resilience
[42:00 - 45:00] Step 7: Trade-offs
```

---

## Table of Contents
1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [Driver Location Ingestion Pipeline](#51-driver-location-ingestion-pipeline)
   - 5.2 [ETA Computation Engine](#52-eta-computation-engine)
   - 5.3 [Real-Time Location Sharing (WebSocket)](#53-real-time-location-sharing-websocket)
   - 5.4 [Geospatial Indexing with Geohash / S2](#54-geospatial-indexing-with-geohash--s2)
   - 5.5 [Route Graph & Traffic-Aware Routing](#55-route-graph--traffic-aware-routing)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Clarifying Questions to Ask First

```
Q1: "Is this ETA before or after driver is matched to rider?
     Pre-match ETA (show price estimate) vs on-trip ETA (show live countdown)?"
     → Unlocks two distinct use cases with different accuracy requirements.

Q2: "How often does the driver's GPS update? We need to decide
     push frequency vs battery drain trade-off."
     → Standard: every 4–5 seconds.

Q3: "Does the rider see the driver's real route or just a dot moving?"
     → Full polyline rendering vs simple lat/lng marker.

Q4: "Is ETA re-computed continuously or only on certain events
     (route deviation, traffic change, stop)?"
     → Continuous re-computation adds significant compute cost.

Q5: "Any privacy requirements? Can driver location be stored long-term?"
     → Affects data retention design.
```

### Functional Requirements

| # | Requirement | Notes |
|---|---|---|
| FR-1 | Driver app **pushes GPS location** every 5 seconds | Even when phone screen is off |
| FR-2 | Rider app **sees driver's live location** on map in real time | < 10s lag from driver to rider |
| FR-3 | System computes and displays **ETA to pickup** | Shown to rider and driver, updated continuously |
| FR-4 | System computes **ETA to destination** once trip starts | Updates if traffic changes significantly |
| FR-5 | Route shown as **polyline** on rider and driver maps | Snapped to road network |
| FR-6 | ETA updates when driver **deviates from route** (turn-by-turn) | Re-routing on deviation |
| FR-7 | Driver **location history** stored per trip | For dispute resolution, analytics |

### Non-Functional Requirements

| Attribute | Target | Reason |
|---|---|---|
| **Location update latency** | Driver → Rider: p99 < 5 seconds | User expects nearly real-time movement |
| **ETA accuracy** | ±1 minute for < 20 min ETA; ±5 min for longer | Uber's published SLA |
| **GPS ingestion throughput** | 500K updates/sec (peak) | 2.5M concurrent drivers × 1 update/5s |
| **Availability** | 99.99% | Active trip tracking must never go down |
| **Location data retention** | Trip duration + 30 days | Disputes, fraud, analytics |
| **ETA computation latency** | < 2 seconds end-to-end | Must feel real-time to rider |

### Out of Scope
- Driver matching algorithm (surge pricing, rider-driver matching)
- Turn-by-turn voice navigation
- Map tile rendering (use Google Maps / Mapbox SDK on client)
- Payment processing

---

## 2. Capacity Estimation

```
Active drivers globally: 5M at peak
Active trips: 2.5M concurrent trips (50% of drivers in active trips)
GPS update frequency: 1 update per 5 seconds per driver

Location updates per second:
  5M drivers / 5 seconds = 1M location updates/sec sustained
  Peak (rush hour): 1.5× = 1.5M updates/sec

Per location update payload:
  { driver_id: 16B, lat: 8B, lng: 8B, heading: 4B, speed: 4B,
    accuracy: 4B, timestamp: 8B, trip_id: 16B }
  = ~70 bytes per update

Ingestion bandwidth:
  1M updates/sec × 70 bytes = 70 MB/s raw
  With Kafka replication (3×): 210 MB/s disk writes

Location storage (hot: last known location per driver):
  5M drivers × 70 bytes = 350 MB → fits entirely in Redis

Trip location history storage:
  Average trip: 20 minutes = 240 GPS points × 70 bytes = 16.8 KB per trip
  2.5M concurrent trips × 16.8 KB = 42 GB in-flight (Cassandra / S3)
  Per day: ~3M trips completed × 16.8 KB = 50 GB/day
  30-day retention: 1.5 TB total trip history

ETA computation requests:
  2.5M active trips × re-compute every 30s = 83K ETA computations/sec
  Each requires: map graph lookup + shortest path computation
  = heavy compute → dedicated ETA cluster needed

WebSocket connections (riders watching driver):
  2.5M active riders with open WebSocket connections to location service
  Average: 2.5M concurrent connections (stateful — must be handled carefully)
```

---

## 3. API Design

### Driver App → Backend (Location Ingestion)

```http
# Driver sends GPS update (called every 5 seconds by driver app)
POST /v1/drivers/{driver_id}/location
Authorization: Bearer {driver_jwt}
Content-Type: application/json

{
  "latitude":  12.9716,
  "longitude": 77.5946,
  "heading":   270.5,          # degrees (0=North, 90=East, 180=South, 270=West)
  "speed":     45.2,           # km/h
  "accuracy":  5.0,            # meters (GPS accuracy radius)
  "timestamp": 1711963200123,  # client-side Unix ms (used for dedup / ordering)
  "trip_id":   "trip_abc123",  # null if not on active trip
  "bearing":   268.3           # compass bearing
}

Response 200:
{
  "acknowledged": true,
  "server_timestamp": 1711963200145
}

# For high-frequency updates, prefer UDP or QUIC to reduce TCP handshake overhead
# Binary protocol (protobuf) preferred over JSON for 70 bytes/update at 1M/sec
```

### Rider App → Backend (Subscribe to Driver Location)

```http
# Get current driver location (polling fallback)
GET /v1/trips/{trip_id}/driver-location
Response 200:
{
  "driver_id": "drv_xyz",
  "latitude":  12.9716,
  "longitude": 77.5946,
  "heading":   270.5,
  "speed":     45.2,
  "updated_at": "2026-04-01T10:00:05Z",
  "eta_seconds": 240,            # ETA to next waypoint (pickup or dropoff)
  "route_polyline": "encoded_polyline_string",  # Google Encoded Polyline
  "distance_remaining_meters": 3200
}

# WebSocket (preferred for real-time)
WSS /v1/trips/{trip_id}/track
# Server pushes LocationUpdate every 5 seconds:
{
  "type": "LOCATION_UPDATE",
  "latitude": 12.9720,
  "longitude": 77.5940,
  "heading": 265.0,
  "eta_seconds": 210,
  "distance_remaining_meters": 2950
}
# Server pushes ETA_UPDATE when ETA changes by > 30 seconds:
{
  "type": "ETA_UPDATE",
  "eta_seconds": 180,
  "reason": "traffic_cleared"   # traffic_cleared | route_changed | detour
}
```

### ETA Query API

```http
# Point-to-point ETA (pre-match, for price estimate)
POST /v1/eta/estimate
{
  "origin":      { "latitude": 12.9716, "longitude": 77.5946 },
  "destination": { "latitude": 12.9352, "longitude": 77.6245 },
  "departure_time": "2026-04-01T10:00:00Z",   # null = now
  "mode": "driving"                            # driving | walking
}
Response 200:
{
  "eta_seconds": 1380,          # 23 minutes
  "distance_meters": 12400,
  "route_polyline": "...",
  "confidence": 0.88,           # ML model confidence score
  "traffic_condition": "moderate"
}

# Driver ETA to specific rider (for matching page)
GET /v1/drivers/{driver_id}/eta-to
    ?pickup_lat=12.9716&pickup_lng=77.5946

# Admin: force ETA recalculation for trip
POST /v1/internal/trips/{trip_id}/recalculate-eta
```

---

## 4. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                                      │
│  Driver App (iOS/Android)   Rider App (iOS/Android)   Rider Web          │
│     GPS every 5s                 WebSocket subscriber                    │
└───────────┬──────────────────────────────┬───────────────────────────────┘
            │ HTTPS/gRPC (location push)   │ WSS (location subscribe)
            ▼                              ▼
┌───────────────────────┐    ┌─────────────────────────────────────────┐
│  LOCATION INGESTION   │    │       LOCATION DELIVERY SERVICE         │
│  SERVICE              │    │       (WebSocket Gateway)               │
│                       │    │                                         │
│  • Validate token     │    │  • Maintains 2.5M WebSocket connections │
│  • Dedup (seq check)  │    │  • Subscribes to Kafka for driver       │
│  • Enrich with snap   │    │    location updates                     │
│    (road-snapping)    │    │  • Pushes to rider's open connection    │
│  • Publish to Kafka   │    │  • Sticky load balancing (conn affinity)│
└───────────┬───────────┘    └─────────────────┬───────────────────────┘
            │                                   │
            ▼                                   │
┌───────────────────────────────────────────────────────────────────────┐
│                      KAFKA (Event Bus)                                │
│  Topic: driver-locations  (partition key = driver_id % 200)           │
│  Topic: eta-updates       (partition key = trip_id % 100)             │
│  Retention: 48 hours (for replay on consumer failure)                 │
└────────────┬──────────────────────────────────────────────────────────┘
             │ Fan-out to multiple consumers
    ┌─────────┼──────────────────────┬────────────────────┐
    ▼         ▼                      ▼                    ▼
┌────────┐ ┌──────────────┐  ┌───────────────┐  ┌────────────────┐
│ REDIS  │ │  LOCATION    │  │  ETA SERVICE  │  │  TRIP HISTORY  │
│ CACHE  │ │  HISTORY DB  │  │               │  │  (Cassandra)   │
│        │ │  (Cassandra) │  │  • Map graph  │  │                │
│• Last  │ │              │  │  • Dijkstra / │  │  • Append GPS  │
│  known │ │  • Append GPS│  │    A* routing │  │    per trip    │
│  loc   │ │  per trip_id │  │  • ML ETA     │  │  • Trip replay │
│• Geohash│ │  • S3 archive│  │    model      │  │  • Audit trail │
│  index │ │  after 7d    │  │  • Traffic    │  │                │
│        │ │              │  │    layer      │  │                │
└────────┘ └──────────────┘  └───────────────┘  └────────────────┘
                                     │
                                     ▼
                            ┌────────────────┐
                            │  MAP / ROUTING │
                            │  ENGINE        │
                            │                │
                            │ • OSRM / Valhalla│
                            │ • Road graph   │
                            │ • Traffic feed │
                            │   (HERE, TomTom│
                            │    or own)     │
                            └────────────────┘
```

### Data Flow — Location Update (End-to-End)

```
t=0ms:    Driver app sends GPS  → Location Ingestion Service
t=5ms:    Dedup + validate      → Kafka publish (driver-locations)
t=10ms:   Redis updated         LAST_LOCATION:{driver_id} = {lat, lng, ts}
t=12ms:   Cassandra append      trip_locations:{trip_id} += {lat, lng, ts}
t=15ms:   Kafka → ETA Service   → Recalculate ETA (async)
t=1500ms: ETA result ready      → Publish to Kafka (eta-updates)
t=1510ms: WebSocket Gateway     reads eta-updates → pushes to rider's socket
t=1520ms: Rider sees update on app (< 2s total end-to-end)
```

---

## 5. Deep Dives

### 5.1 Driver Location Ingestion Pipeline

#### Deduplication & Out-of-Order Handling

```
Problem: Mobile apps can send duplicate or out-of-order GPS updates
  Network retry: driver app sends update, timeout, retries → broker gets it twice
  GPS jitter: old buffered location arrives after newer one

Solution: Sequence-based deduplication in Location Ingestion Service

  Driver app includes:
    { seq_number: 1042, timestamp: 1711963200123, driver_id: "drv_xyz", ... }

  Service maintains per-driver last seen state in Redis:
    Key:   drv_seq:{driver_id}
    Value: { last_seq: 1041, last_ts: 1711963200118 }
    TTL:   30 seconds (driver considered offline if no update in 30s)

  On receiving update:
    IF update.seq <= last_seq:         → discard (duplicate or old)
    IF update.timestamp < last_ts - 30s: → discard (too old, satellite artifact)
    ELSE:                              → accept, update last_seq + last_ts

  Redis command: GETSET drv_seq:{driver_id} {seq_number}
    → Atomic compare: get old seq, set new in one operation
```

#### Road Snapping

```
Problem: Raw GPS coordinates are not on roads (GPS noise ±5-50m)
  Driver is on a road, GPS says they're 30m off (in a building)
  Route calculation fails / looks wrong on map

Solution: Map Matching (road snapping) before storage

  Map matching algorithm (Hidden Markov Model - HMM):
    Input: sequence of GPS points with timestamps
    Output: sequence of road segments that best explains the GPS trace

  Implementation options:
    1. OSRM match API: open-source, self-hosted
       POST /match/v1/driving/{coordinates}
       → Returns snapped polyline on road network

    2. Google Roads API: managed, accurate, costly at scale
    
    3. Custom HMM: for ultra-low latency (< 2ms per point)
       Emit candidates (nearby road segments within 50m radius of GPS point)
       Use Viterbi algorithm to find most likely road sequence
       Transition probability: P(road B after road A) from map topology
       Emission probability: P(GPS point | road segment) = gaussian(distance)

  At 1M updates/sec: road snapping must be sub-millisecond
  → Pre-build spatial index of road segments in memory
  → Use geohash to find candidate road segments in O(1)
  → Viterbi on 3-5 candidates per point is fast (not full graph search)
```

#### Location Protocol: HTTP vs UDP vs QUIC

```
HTTP/1.1 (current mobile default):
  70 bytes payload + ~400 bytes HTTP headers = 470 bytes per update
  TCP handshake overhead for new connections
  At 1M/sec: 470 MB/s just for headers → wasteful

UDP (custom binary protocol):
  70 bytes payload, no headers
  No connection state, no handshake
  Concern: packet loss (UDP is unreliable)
  Mitigation: driver app seq number; missing seq triggers re-send
  At 1M/sec: 70 MB/s → 6.7x more efficient

QUIC (recommended):
  Benefits of UDP (low latency, no head-of-line blocking)
  + TLS 1.3 built-in (security)
  + Connection IDs survive IP change (critical for mobile roaming)
  + 0-RTT reconnect (driver switches from WiFi to 4G = instant reconnect)
  → Uber uses QUIC for driver location updates since 2021

Choose: QUIC for production; HTTP/2 as fallback
```

---

### 5.2 ETA Computation Engine

#### Three ETA Components

```
Total ETA = Route Distance + Traffic Impact + Historical Correction

Component 1: Route Distance ETA (base)
  Given: current driver location, destination, road graph
  Compute: shortest path by time (not distance)
  Algorithm: Dijkstra / A* / Contraction Hierarchies on road graph
  Result: theoretical ETA with free-flow speed limits

Component 2: Real-Time Traffic Layer
  Input: live traffic speed data per road segment
    - GPS floating car data (millions of drivers on road → crowd-sourced speeds)
    - Infrastructure sensors (toll booths, traffic cameras)
    - Third-party: HERE Maps, TomTom Traffic, Google Maps API
  Apply: replace free-flow speeds with actual observed speeds
  Result: traffic-adjusted ETA

Component 3: ML Correction Layer
  Historic pattern: "this route on Monday 9 AM takes 15% longer than computed"
  Features: time_of_day, day_of_week, weather, nearby events (cricket match!)
  Model: LightGBM or XGBoost regressor
    Input: route_features + traffic_features + context_features
    Output: ETA_multiplier (e.g., 1.12 → add 12% to computed ETA)
  Trained on: historical trips with actual durations vs computed ETAs
  Inference: < 5ms per prediction (served from feature store + model file in memory)
```

#### Road Graph Representation

```
Road graph G = (V, E)
  V = intersections (nodes): { node_id, lat, lng }
  E = road segments (edges): { from_node, to_node, distance_m, speed_limit_kmh, road_class }

Storage:
  Node count: 500M nodes globally (OpenStreetMap scale)
  Edge count: 1.2B edges
  Raw size: ~20 GB for just topology (compressed)
  With traffic weights: ~80 GB total

  Sharding by geographic region:
    tile_id = geohash(lat, lng, precision=4) → 32x32km cells
    Each routing engine instance holds 1 geographic tile (in RAM)
    On cross-tile route: request straddles multiple tiles → multi-tile routing

Contraction Hierarchies (CH) — Why, Not Just Dijkstra:
  Raw Dijkstra on 1B nodes: O(V log V + E) = ~minutes
  CH preprocessing contracts nodes (add shortcut edges):
    Remove low-importance nodes; add direct shortcuts between remaining nodes
    Precompute in ~30 min once; update when map changes
    Query time: < 5ms even for cross-country routes
    Trade-off: preprocessing cost; not suitable for very frequent graph updates
```

#### ETA Computation Request Flow

```
ETA Request (every 30s per active trip, 83K/sec):

  1. Get driver's current location from Redis:
       HGET last_location:{driver_id} -> { lat, lng, snapped_node_id }

  2. Get destination node:
       From trip metadata (already computed at trip start; stored in trip DB)

  3. Run Contraction Hierarchies shortest path:
       Source: snapped_node_id
       Target: destination_node_id
       Time weight: f(edge) = distance / current_traffic_speed[edge]
       Result: { eta_seconds_base, route_polyline_encoded, distance_meters }

  4. Apply ML correction:
       features = {eta_base, time_of_day, day_of_week, weather, ...}
       eta_final = model.predict(features) × eta_base

  5. Publish to Kafka:
       { trip_id, eta_seconds: eta_final, route_polyline, ts: now() }

  6. WebSocket Gateway reads eta-updates topic:
       Push to rider and driver apps

ETA Service scaling:
  83K ETA computations/sec
  Each CH query: ~2ms → 1 CH server handles 500 queries/sec
  Need: 83K / 500 = 166 CH routing servers
  Each holds full road graph for its region in RAM
  Total: ~100 routing servers (geographic sharding reduces per-server graph size)
```

---

### 5.3 Real-Time Location Sharing (WebSocket)

#### WebSocket Gateway Architecture

```
Problem: 2.5M concurrent WebSocket connections
  Each connection = 1 TCP connection + memory for buffers
  A single server can handle ~50K concurrent WebSocket connections
  (limited by: file descriptors, memory ~10 KB/conn, CPU for TLS)

  2.5M / 50K = 50 WebSocket Gateway nodes needed

Connection stickiness (critical):
  Each rider's WebSocket connection must stay on the SAME gateway node
  for the duration of the trip
  → If rider switches nodes, we lose the connection state

  Solution: L4 load balancer with consistent hash sticky routing
    hash(trip_id) % N_gateway_nodes → always routes same trip to same node
    On gateway failure: re-hash (riders reconnect, small disruption)

Each gateway node subscribes to Kafka topic (driver-locations):
  Subscription: ALL partitions (gateway needs all updates for its riders)
  Filter: only forward updates for riders connected to THIS gateway

  Problem: 50 gateway nodes × 1M events/sec = 50M fan-out events/sec!
           Each gateway reads all 1M updates, filters to its ~50K riders.

  Optimization — Per-gateway Kafka subscription filtering:
    Partition by driver_id; each gateway subscribes to subset of partitions
    Trip routing: ensure trip's Kafka partition aligns with trip's gateway shard
    Implementation: trip_id % 200 = Kafka partition; gateway_id = partition / 4
    → Each gateway reads only 1/50th of events = 20K events/sec per gateway ✓
```

#### WebSocket Message Protocol

```
Server → Rider (every 5 seconds OR on significant change):

Location update message (binary, ~50 bytes):
  {
    type:       1            # 1=LOCATION, 2=ETA, 3=TRIP_STATUS, 4=HEARTBEAT
    lat:        float32      # 4 bytes
    lng:        float32      # 4 bytes
    heading:    uint16       # degrees × 10 (0-3599), 2 bytes
    speed_kmh:  uint16       # 2 bytes
    eta_sec:    uint32       # 4 bytes
    dist_m:     uint32       # 4 bytes
    timestamp:  uint64       # 8 bytes
  }
  Total binary: ~32 bytes vs ~200 bytes JSON (6× bandwidth savings)

Connection lifecycle:
  CONNECT:   Rider opens WSS /v1/trips/{trip_id}/track
  AUTH:      Gateway validates trip_id + rider_jwt (first message)
  STREAMING: Gateway pushes location updates (5s interval)
  HEARTBEAT: Gateway pings every 30s; rider ACKs (detect dead connections)
  DISCONNECT: On trip completion or rider closes app
              Gateway unregisters connection; stops forwarding to this rider
```

#### Handling App Backgrounding (Mobile-Specific)

```
Problem: iPhone puts apps in background → WebSocket connection drops
         But rider still needs to see driver arrival notification!

Solution: Push Notifications as fallback

  When WS connection is detected as closed (heartbeat timeout):
  → Location Gateway marks rider as "offline"
  → System falls back to push notification for critical events:
       "Driver is 2 minutes away" → APNs/FCM push
       "Driver has arrived. Please come to pickup point" → push

  Client-side reconnect logic:
    Rider app comes to foreground → re-establish WebSocket
    Resume from last known state (driver location from REST API call)
    Then switch back to real-time WebSocket streaming

For Android (doesn't kill background apps as aggressively):
  Foreground service maintains WebSocket even when app backgrounded
  Trades battery for real-time experience
```

---

### 5.4 Geospatial Indexing with Geohash / S2

#### Why Spatial Index Is Needed

```
Problem: Finding nearest drivers to a rider at pickup time.
  Rider at (12.9716, 77.5946) — show nearest 5 available drivers.
  Naive: scan all 5M drivers, compute distance for each → O(5M) per query.
  At 50K rider requests/sec: 5M × 50K = 250 BILLION distance computations/sec.
  → Not feasible.

Solution: Spatial index to reduce candidate set to nearby drivers only.
```

#### Geohash Encoding

```
Geohash encodes (lat, lng) into a short alphanumeric string:
  Geohash precision levels:
    Length 1: ~5,000 km × 5,000 km (continent)
    Length 4: ~40 km × 20 km (city)
    Length 6: ~1.2 km × 0.6 km (neighborhood)
    Length 7: ~150m × 75m (street block)
    Length 9: ~5m × 5m (building-level)

  (12.9716, 77.5946) → Geohash-6 = "tdnu2w"

Driver location in Redis:
  Standard Geospatial commands:
    GEOADD drivers-geo {lng} {lat} {driver_id}
    GEORADIUS drivers-geo {lng} {lat} 2 km WITHCOORD WITHDIST COUNT 20 ASC
    → Returns 20 nearest drivers within 2km radius; O(N+log M) where N=results

  Redis GEO internals: uses sorted set with 52-bit geohash as score
  → ZRANGEBYSCORE on geohash range = fast spatial lookup
  → O(log M + N) for M total drivers, N results

Geohash neighbor lookup for edge cases:
  Driver at boundary of geohash cell → needs to check adjacent cells too
  Geohash has 8 neighbors (N, NE, E, SE, S, SW, W, NW)
  Always search: current cell + 8 neighbors = 9 cells total
  → Guarantees no nearby driver is missed at cell boundaries
```

#### S2 Geometry (Google's Approach — Used by Uber Since 2015)

```
S2 maps sphere surface to a unit cube, then subdivides recursively:
  Level 0:  6 faces of cube (~85M km²)
  Level 14: ~600m × 600m (neighborhood)
  Level 20: ~1m × 1m (sub-meter precision)

Advantages over Geohash:
  1. Uniform cell sizes: geohash cells are NOT equally sized (distortion at poles)
     S2 cells are nearly uniform → equal search radius accuracy
  2. Hierarchical: S2 level 14 cell ⊂ level 13 cell (proper containment)
  3. Range queries: a circular region maps to a small set of S2 cell ranges
     → Efficient Redis ZRANGEBYSCORE on multiple ranges

Implementation:
  ZADD drivers-s2 {s2_level_14_cell_id} {driver_id}
  
  For "find drivers within 1km of (lat, lng)":
    1. Compute S2 cell covering (lat, lng) at level 14
    2. Compute all S2 cells within 1km radius → list of cell ID ranges
    3. ZRANGEBYSCORE for each range → union of results → distance filter

Uber S2 usage:
  Drivers updated in S2 cells at multiple levels
  Matching: search outward from rider's cell until K drivers found
```

---

### 5.5 Route Graph & Traffic-Aware Routing

#### Live Traffic Speed Updates

```
Floating Car Data (FCD) — crowd-sourced traffic:
  Every driver with Uber/Ola app moving on roads is a traffic probe.
  5M GPS updates/sec → aggregate speeds per road segment.

  Processing pipeline:
  1. GPS update → identify which road segment driver is on (map matching)
  2. Compute instantaneous speed: distance_traveled / time_difference
  3. Accumulate: ZADD segment_speeds:{segment_id} {timestamp} {speed_kmh}
  4. Per-segment rolling average (last 5 min): 
       ZRANGEBYSCORE segment_speeds:{segment_id} (now-300s) now
       → average = sum(speeds) / count(speeds)

  Speed data stored in:
  - Redis: real-time segment speeds (TTL 10 minutes)
  - Cassandra: hourly aggregated speeds per segment (historical)
  - Updated every 30 seconds for ETA routing engine

Traffic data enrichment from 3rd parties:
  HERE Maps Traffic API or TomTom Real-Time Traffic:
    → Incident reports (accidents, road closures)
    → Congestion levels per road segment
    → Estimated clearance times
  Subscribed via webhook → update routing graph edge weights
```

#### Route Deviation Detection & Re-Routing

```
Problem: Driver takes wrong turn or road is blocked → need to re-route.

Detection:
  1. Driver's GPS updates compared to planned route polyline
  2. If driver's position is > 50m from nearest route segment:
     → Compute: "off_route_distance" = meters from intended path
  3. If off_route_distance > 100m for > 15 seconds:
     → Trigger re-routing event

Re-routing logic:
  1. Cancel current route in ETA Service
  2. Compute new route from driver's CURRENT position to destination
  3. Publish new ETA + route to Kafka (eta-updates)
  4. WebSocket Gateway pushes new route polyline to rider and driver apps

Edge case: Driver intentionally deviates (road closed, accident ahead)
  → Same re-routing applies; system adapts
  → Driver app shows "recalculating..." (same as Google Maps)

Implementation:
  Route stored as ordered list of (lat, lng) waypoints in Redis:
    Key: route:{trip_id}
    Value: LRANGE returns [point0, point1, ..., pointN] (polyline)

  Deviation check runs on EACH driver location update:
    closest_segment = find_nearest_segment(driver_location, route)
    if perpendicular_distance(driver_location, closest_segment) > 100m:
      AND not_recently_rerouted(trip_id, last_5_seconds):
        trigger_reroute(trip_id, driver_location)
```

---

## 6. Scale & Resilience

### Location Pipeline Scaling

```
Ingestion Service (stateless, auto-scaled):
  1M updates/sec ÷ 10K updates/sec per pod = 100 pods
  K8s HPA based on Kafka consumer lag metric

Kafka:
  Topic: driver-locations, 200 partitions, RF=3
  Partition key: hash(driver_id) % 200
  Each partition: 5K updates/sec = manageable per consumer

Redis (Last-Known Location):
  Single shard: 5M drivers × 70 bytes = 350 MB → fits in one node
  But replication needed for HA: Redis Sentinel (1 primary + 2 replicas)
  If geo queries needed: Redis Cluster with 6 nodes (geo commands scale well)

Cassandra (Trip History):
  Write: 1M updates/sec × 70 bytes = 70 MB/s
  With RF=3: 210 MB/s write bandwidth → 10 Cassandra nodes
  Partition key: trip_id (all points for one trip on same node)
  Clustering key: timestamp (time-series within partition)
  TTL: 30 days (auto-expire old trip data)
```

### Failure Scenarios

| Failure | Detection | Impact | Recovery |
|---|---|---|---|
| **Location Ingestion pod crash** | K8s liveness probe | GPS updates stopped | K8s restarts pod in <30s; Kafka retains 48h; driver app retries |
| **Redis cache failure** | Connection timeout | Last-known location unavailable | Fall back to Cassandra for last location (slight latency increase) |
| **ETA Service crash** | Health check | No ETA updates | Rider sees stale ETA; K8s restarts; last ETA cached in WebSocket gateway |
| **WebSocket Gateway crash** | Load balancer health check | Riders lose real-time updates | Clients reconnect to new gateway; REST polling fallback for 30s |
| **Kafka broker failure** | ISR election | Brief ingestion delay | Kafka ISR election heals in <30s; buffered in driver app |
| **Map routing engine crash** | Health check | ETA computation paused | Pre-computed ETAs served from cache; routing restarts in <60s |
| **GPS signal lost on driver** | No updates for 15s | Rider sees stale marker | Mark driver as "last seen" with timestamp; show stale indicator on rider app |

### Rate Limiting & Abuse Prevention

```
Driver location API:
  Expected: 1 update per 5 seconds per driver
  Abuse: malicious client sending 100 updates/sec

  Rate limit per driver_id:
    → Max 2 updates/sec accepted (allow burst for initial connection)
    → Excess: HTTP 429 with Retry-After: 3
    → Implementation: Redis sliding window counter:
        INCR rate:{driver_id}:{second} EX 2
        If > 2 → reject

Rider WebSocket:
  Max 1 WebSocket connection per trip per user
  If same trip_id tries to open 2nd connection → close older one
```

---

## 7. Trade-offs & Alternatives

### Key Design Decisions

| Decision | Choice Made | Alternative | Reason |
|---|---|---|---|
| **Location protocol** | QUIC (UDP-based) | HTTP/2 | QUIC: 0-RTT reconnect on mobile IP change; reduces overhead 6× |
| **Location delivery** | WebSocket push | Polling (GET every 5s) | WS: server-initiated; no empty polls; lower latency |
| **Spatial index** | Redis GEO + S2 cells | PostGIS (PostgreSQL) | Redis GEO: in-memory, O(log N); PostGIS: accurate but SQL overhead |
| **ETA algorithm** | Contraction Hierarchies + ML | Pure Dijkstra | CH: < 5ms for any route; Dijkstra: minutes on 1B-node graph |
| **Traffic source** | FCD (crowd-sourced) + 3rd party | Own infrastructure sensors | FCD: free (already have driver data); sensor infrastructure is CapEx-heavy |
| **Road snapping** | HMM map matching | No snapping (raw GPS) | Raw GPS off-road ruins ETA accuracy; HMM is standard for navigation |
| **Trip history storage** | Cassandra | PostgreSQL time-series | Cassandra: write-optimized; TTL natively supported; horizontal scale |
| **ETA update frequency** | Every 30s + on deviation | Continuous (every 5s) | 30s: 83K ETA/sec; continuous: 1M ETA/sec (10× compute cost) |

### ETA Approach Comparison

| Approach | Accuracy | Latency | Cost |
|---|---|---|---|
| **Static speed limits** | Low (±30%) | < 1ms | Minimal |
| **CH + historical speeds** | Medium (±15%) | < 5ms | Map data storage |
| **CH + real-time traffic** | High (±5%) | 5-20ms | Traffic data feed |
| **CH + traffic + ML** | Highest (±3%) | 5-50ms | ML inference cost |
| **Google Maps API (external)** | Highest | 100-500ms | Per-query pricing ($$ at scale) |

**Choose:** CH + real-time traffic + ML for owned ETA at Uber-scale.  
**Use Google Maps API** for startup / low volume (< 10M queries/day before self-hosting makes financial sense).

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Separated pre-match ETA (estimate) from on-trip ETA (live)
  [ ] Asked update frequency and battery/accuracy trade-off
  [ ] Clarified if full route polyline or just ETA number is needed
  [ ] Asked about privacy / location data retention

CAPACITY
  [ ] 5M drivers × 1/5s = 1M updates/sec sustained
  [ ] WebSocket: 2.5M concurrent connections → 50 gateway nodes
  [ ] ETA computations: 83K/sec → 100+ routing server nodes
  [ ] Redis: 5M × 70B = 350 MB → single node (highlighted it's small!)

ARCHITECTURE
  [ ] Driver location → Kafka → fan-out (Redis + Cassandra + ETA + WebSocket)
  [ ] WebSocket Gateway as separate stateful tier (connection affinity)
  [ ] ETA Service as separate compute-heavy tier (CH + ML)
  [ ] Geospatial index (Redis GEO / S2) for driver discovery

DEEP DIVES (pick 3)

  Location Ingestion:
    [ ] Sequence-based dedup: Redis GETSET for atomic compare
    [ ] QUIC vs HTTP vs UDP: QUIC wins on mobile (0-RTT reconnect)
    [ ] Road snapping: HMM with Viterbi on 3-5 candidate segments

  ETA Computation:
    [ ] Three-component ETA: base routing + traffic + ML correction
    [ ] Contraction Hierarchies: why not Dijkstra (minutes vs 5ms)
    [ ] ML ETA: LightGBM with features (time_of_day, weather, events)
    [ ] 83K ETA/sec → 100 CH routing servers (geographic sharding)

  WebSocket Location Sharing:
    [ ] 2.5M WS connections → 50 nodes × 50K each
    [ ] Sticky routing: consistent hash(trip_id) → same gateway node
    [ ] Kafka partition alignment: avoid all-to-all fan-out
    [ ] Binary protocol (32 bytes) vs JSON (200 bytes) = 6× bandwidth
    [ ] Mobile backgrounding: push notification fallback

  Geospatial Indexing:
    [ ] Redis GEO (GEORADIUS): O(log M + N)
    [ ] Geohash neighbor problem: check 8 surrounding cells
    [ ] S2 cells: uniform size, hierarchical, better than geohash
    [ ] Application: driver discovery at match time

  Route Graph & Traffic:
    [ ] FCD pipeline: GPS → segment → speed → roll-up → edge weight
    [ ] Deviation detection: > 100m from route for > 15s → re-route
    [ ] Re-routing: CH from current position; publish new ETA + polyline

SCALE & RESILIENCE
  [ ] Location ingestion: 100 stateless pods, K8s HPA
  [ ] Redis failure: fall back to Cassandra for last location
  [ ] WebSocket crash: clients reconnect; REST polling fallback for 30s
  [ ] Rate limiting: 2 updates/sec per driver (Redis sliding window)

TRADE-OFFS
  [ ] QUIC vs HTTP (connection resilience on mobile)
  [ ] WS push vs polling (latency vs simplicity)
  [ ] Self-hosted CH vs Google Maps API (cost crossover at ~10M queries/day)
  [ ] 30s ETA refresh vs continuous (10× cost difference)
  [ ] CH vs Dijkstra (5ms vs minutes on 1B node graph)
```
