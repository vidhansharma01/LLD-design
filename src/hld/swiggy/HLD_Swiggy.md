# 🍔 High-Level Design (HLD) — Swiggy (Food Delivery Platform)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Real-time Location Tracking, Order Lifecycle State Machine, Matching Engine, ETA Prediction, Geo-spatial Queries

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **browse restaurants** by location, cuisine, rating, delivery time.
- Users can **place orders** with multiple items from a restaurant.
- **Real-time delivery partner (DP) assignment** to orders.
- **Live order tracking** — map showing DP location and ETA.
- Support **multiple payment modes**: UPI, Card, COD, Swiggy Money.
- Restaurants receive **order notifications** and manage preparation status.
- **Dynamic delivery fee** based on distance, surge demand.
- **Order lifecycle management**: Placed → Accepted → Preparing → Picked Up → Delivered/Cancelled.
- **Ratings** for restaurant and delivery partner after delivery.
- **Swiggy One** (subscription) — free delivery, priority support.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Order placement latency** | < 2 sec |
| **DP assignment latency** | < 30 sec from order placement |
| **Location update frequency** | DP sends GPS update every 5 sec |
| **ETA accuracy** | Within ±2 min for 90% of orders |
| **Availability** | 99.99% for order placement; real-time tracking 99.9% |
| **Scale** | 10M orders/day, 300K active DPs, 200K restaurants |

---

## 2. Capacity Estimation

```
Orders/day              = 10 million → ~115/sec avg, ~1000/sec peak (meal times)
Active DPs (peak)       = 300,000
Location updates/sec    = 300K DPs × 1 update/5sec = 60,000 GPS writes/sec
Restaurant listings     = 200,000
Avg order size          = 3 items → ~500 bytes
Order storage/day       = 10M × 500B = ~5 GB/day
Location storage        = In-memory (Redis); only latest position matters
```

---

## 3. High-Level Architecture

```
 Customer App         Restaurant App        Delivery Partner App
      │                     │                       │
      └──────────────────────┼───────────────────────┘
                             ▼
                    ┌────────────────┐
                    │  API Gateway   │ (Auth, Rate Limit, Routing)
                    └───────┬────────┘
                            │
         ┌──────────────────┼─────────────────────────┐
         ▼                  ▼                          ▼
  ┌──────────────┐  ┌──────────────┐        ┌──────────────────┐
  │  Restaurant  │  │  Order       │        │  Delivery Partner │
  │  Service     │  │  Service     │        │  Service          │
  └──────┬───────┘  └──────┬───────┘        └──────────┬────────┘
         │                 │                            │
         │          ┌──────▼──────┐          ┌──────────▼────────┐
         │          │  Payment    │          │  Location Service  │
         │          │  Service    │          │  (Redis Geo)       │
         │          └─────────────┘          └──────────┬────────┘
         │                 │                            │
         └─────────────────┼────────────────────────────┘
                           ▼
                   ┌────────────────┐
                   │   Kafka        │
                   │  (Order Events)│
                   └───────┬────────┘
                           │
         ┌─────────────────┼────────────────┐
         ▼                 ▼                ▼
  ┌──────────────┐  ┌───────────┐  ┌──────────────────┐
  │  Matching    │  │Notification│ │   ETA / Routing   │
  │  Engine      │  │ Service   │  │   Service         │
  │  (DP assign) │  │ (SMS/Push)│  │   (Maps API)      │
  └──────────────┘  └───────────┘  └──────────────────┘
```

---

## 4. Order Lifecycle — State Machine

```
          Customer places order
                  │
                  ▼
           ┌─────────────┐
           │   PLACED    │──── payment fails ──▶ PAYMENT_FAILED
           └──────┬──────┘
                  │ payment success
                  ▼
           ┌─────────────┐
           │  CONFIRMED  │──── restaurant rejects ──▶ CANCELLED (auto-refund)
           └──────┬──────┘
                  │ restaurant accepts
                  ▼
           ┌─────────────┐
           │  PREPARING  │◀── restaurant updates prep time
           └──────┬──────┘
                  │ DP assigned + arrives at restaurant
                  ▼
           ┌─────────────┐
           │  PICKED_UP  │
           └──────┬──────┘
                  │ DP marks delivered
                  ▼
           ┌─────────────┐
           │  DELIVERED  │
           └─────────────┘

Cancellable by customer: PLACED or CONFIRMED (before PREPARING)
Auto-cancel: if no DP found in 10 min → CANCELLED + full refund
```

### Order Table (PostgreSQL — ACID for financial transactions)

```sql
CREATE TABLE orders (
    order_id        UUID PRIMARY KEY,
    customer_id     UUID NOT NULL,
    restaurant_id   UUID NOT NULL,
    dp_id           UUID,
    status          TEXT NOT NULL,
    total_amount    DECIMAL(10,2),
    delivery_fee    DECIMAL(10,2),
    payment_status  TEXT,
    payment_mode    TEXT,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    delivery_address JSONB,
    items           JSONB    -- [{itemId, name, qty, price}, ...]
);
```

---

## 5. Core Components

### 5.1 Restaurant Service

- **Restaurant listing**: Cassandra (read-heavy, no complex joins).
- **Menu items** cached in Redis (TTL: 15 min).
- **Restaurant discovery**: Elasticsearch — filter by location (geo), cuisine, rating, delivery time.
- **Availability**: restaurants mark open/closed; cached in Redis.

**Geo-based restaurant search:**
```
GET /restaurants?lat=12.97&lng=77.59&radius=5km&cuisine=Indian

Elasticsearch geo_distance query:
{
  "query": { "geo_distance": { "distance": "5km", "location": [77.59, 12.97] } }
  "sort": [{ "rating": "desc" }, { "estimated_delivery_time": "asc" }]
}
```

### 5.2 Delivery Partner Location Service

**The most write-intensive component.** 60,000 GPS writes/sec.

```
DP App sends location every 5 sec:
  POST /location { dpId, lat, lng, timestamp }
         │
         ▼
  Location Service
         │
  Redis GEO:
    GEOADD dp_locations <lng> <lat> <dpId>
    (TTL on each key: 30 sec — after 30 sec without update, DP considered offline)
         │
  Kafka: LocationUpdated event
    → Order Tracking Service (push update to customer via WebSocket)
    → ETA recalculation
```

**Redis GEO commands for DP lookup:**
```
GEORADIUS dp_locations <restaurant_lng> <restaurant_lat> 5 km
  → returns [dpId1, dpId2, dpId3 ...] sorted by distance
  → Used by Matching Engine to find nearby available DPs
```

### 5.3 Matching Engine (DP Assignment)

Triggered on `OrderConfirmed` event from Kafka.

```
OrderConfirmed event received
        │
        ▼
Find available DPs within 5km of restaurant:
  GEORADIUS dp_locations <restaurant> 5 km ASC COUNT 20

For each candidate DP, compute score:
  score = α × (1/distance)
        + β × DP_acceptance_rate
        + γ × DP_rating
        + δ × (current_order_count == 0 ? 1 : 0)  // prefer idle DPs

Select top-scoring DP
        │
        ▼
Send assignment request to DP app (push notification)
  → DP has 15 sec to accept
  → If decline / timeout → try next candidate
  → If no DP in 10 min → auto-cancel order + refund
        │
        ▼
On acceptance: update order.dp_id, status = ASSIGNED
Notify customer: "Your delivery partner has been assigned"
```

### 5.4 ETA Prediction Service

```
ETA = preparation_time + pickup_travel_time + delivery_travel_time

preparation_time:
  → Restaurant sets estimate; ML model adjusts based on:
    - Current kitchen load (# active orders)
    - Historical avg for similar orders / time of day

pickup_travel_time & delivery_travel_time:
  → Google Maps / MapmyIndia Distance Matrix API
  → Factors: real-time traffic, road conditions
  → Recalculated every 60 sec as DP moves

ETA stored in Redis (TTL: 60 sec; refreshed on each location update)
ETA pushed to customer via WebSocket
```

### 5.5 Real-time Order Tracking

```
DP App → Location Update → Location Service → Kafka
Kafka → Order Tracking Service → WebSocket Server → Customer App

WebSocket:
  - Persistent connection per active order
  - Pushes: { dpLat, dpLng, eta, status }
  - On status change: push status event
  - Fallback: SSE (Server-Sent Events) if WebSocket not supported

Map rendering:
  - Customer app renders DP pin on map in real-time
  - Route polyline from DP to customer rendered via Maps SDK
```

### 5.6 Payment Service

```
Customer checkout:
  → Payment Service calls payment gateway (Razorpay / PhonePe / Stripe)
  → On success → Kafka: PaymentSuccess → Order Service updates status
  → On failure → 3 retries → PaymentFailed → show error to customer

COD flow:
  → Order placed without upfront payment
  → DP collects cash on delivery
  → DP marks "Cash Collected" in app → triggers payout logic

Refund flow (cancellation):
  → Automatic refund initiated via payment gateway
  → SLA: 5–7 business days to customer's account
```

### 5.7 Surge Pricing & Delivery Fee

```
Delivery Fee = base_fee
             + distance_fee (per km after threshold)
             + surge_multiplier × base_fee (if demand > supply in area)

Surge Detection:
  - Monitor: open_orders / available_DPs ratio per geo-cluster (Redis)
  - Ratio > 2.0 → level 1 surge (1.2×)
  - Ratio > 3.0 → level 2 surge (1.5×)
  - Updated every 5 min; displayed to customer on checkout
```

---

## 6. Notification Service Integration

| Trigger | Channel | Message |
|---|---|---|
| Order confirmed | Push + SMS | "Your order is confirmed!" |
| DP assigned | Push | "Ravi is picking up your order" |
| Order picked up | Push | "Order is on the way! ETA: 15 min" |
| Order delivered | Push + email | "Delivered! Rate your experience" |
| Order cancelled | Push + SMS | "Order cancelled. Refund in 5–7 days" |
| Restaurant delay | Push | "Kitchen is busy. New ETA: 35 min" |

---

## 7. Geo-spatial Design

```
Geo-clustering for surge / restaurant discovery:
  - Earth divided into S2 cells (Google S2 library) or H3 hexagons (Uber H3)
  - Each cell ~1 km² at level 13
  - Demand/supply tracked per cell → surge pricing, DP dispatch

Restaurant search radius:
  - Default 5 km from customer location
  - Elasticsearch geo_distance queries → sub-50ms p99

DP proximity:
  - Redis GEORADIUS: O(N+log M) where N = nearby DPs
  - Updated every 5 sec; stale data TTL = 30 sec
```

---

## 8. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Order DB** | PostgreSQL | ACID for financial data; not web-scale NoSQL |
| **Location store** | Redis GEO | O(log N) radius queries; ephemeral (no history needed) |
| **DP matching** | Score-based greedy | Fast (~100ms); optimal requires Hungarian algorithm (too slow) |
| **ETA** | Maps API + ML | Accurate but latency; cached per-route for 60 sec |
| **Surge pricing** | S2 cell demand/supply ratio | Coarse-grained; fine-grained = too noisy |
| **Real-time tracking** | WebSocket | Sub-second latency; connection management at scale |
| **Fan-out** | Status via Kafka → push | Decoupled; push service scales independently |

---

## 9. Scalability

| Layer | Scaling Strategy |
|---|---|
| Location Service | Stateless; Redis horizontal scale; 60K writes/sec trivial for Redis |
| Matching Engine | Kafka consumer group; partitioned by restaurant geo-cluster |
| WebSocket servers | Sticky sessions per order; scale out nodes |
| Order Service | Stateless; connection pool to PostgreSQL; read replicas for menu |
| Restaurant search | Elasticsearch cluster; sharded by geo-region |

---

## 10. Monitoring

| Metric | Alert |
|---|---|
| DP assignment time | > 30 sec average |
| Order cancellation rate | > 5% (kitchen/DP issues) |
| ETA accuracy | > 20% of deliveries off by > 5 min |
| Location update lag | DP location > 15 sec stale |
| Payment failure rate | > 2% |

---

*Document prepared for SDE 3 system design interviews. Focus areas: order state machine, real-time geo-tracking (Redis GEO), DP matching algorithm, ETA prediction, WebSocket-based live tracking, and surge pricing.*
