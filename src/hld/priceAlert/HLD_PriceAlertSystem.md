# HLD — Price Alert System (Amazon / Stock Prices)

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** Event-Driven Systems / Rule Engine / Notification at Scale
> **Real-world Analogues:** Amazon Price Drop Alert, Google Stocks Alert, Robinhood Price Alert, Camelcamelcamel

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
   - 5.1 [Alert Storage & Inverted Index](#51-alert-storage--inverted-index)
   - 5.2 [Price Change Event Pipeline](#52-price-change-event-pipeline)
   - 5.3 [Alert Evaluation Engine](#53-alert-evaluation-engine)
   - 5.4 [Notification Delivery & Deduplication](#54-notification-delivery--deduplication)
   - 5.5 [Stock Price Alerts — Tick-Level Precision](#55-stock-price-alerts--tick-level-precision)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Clarifying Questions to Ask First

```
Q1: "Are we designing for e-commerce price drops (Amazon) or financial
     instruments (stocks/crypto)?"
     → Both have same architecture; stocks have stricter latency SLAs.
     → Ask: "Can I design a unified system covering both?"

Q2: "What alert conditions do we support?
     Just 'price drops below X' or also 'price drops by Y%', 'back in stock',
     'price crosses X in either direction' (stock alerts)?"
     → Determines the rule evaluation complexity.

Q3: "What notification channels? Email, SMS, push, in-app?"
     → Affects notification service design.

Q4: "How quickly must the alert fire after a price change?
     Within seconds (stocks) or within minutes (e-commerce)?"
     → Determines if we need real-time Kafka-based evaluation or periodic batch.

Q5: "Can the same user get multiple alerts for the same item?
     E.g., price drops from $100 → $80 → $60, alert fires twice?"
     → Deduplication and re-arm logic.

Q6: "How long is an alert active before it auto-expires?"
     → Storage sizing and cleanup strategy.

Q7: "Do we need a price history? Can users see historical price charts?"
     → Separate time-series storage concern.
```

### Functional Requirements

| # | Use Case | Amazon | Stocks |
|---|---|---|---|
| FR-1 | User creates an alert: "Notify me when price drops **below** $X" | ✅ | ✅ |
| FR-2 | User creates: "Notify when price drops **by Y%** from current" | ✅ | ✅ |
| FR-3 | User creates: "Notify when price crosses **above** $X" | ❌ common | ✅ |
| FR-4 | User creates: "Notify when item is **back in stock**" | ✅ | N/A |
| FR-5 | User views, edits, pauses, deletes their alerts | ✅ | ✅ |
| FR-6 | Alert fires notification within SLA after price change | ✅ | ✅ |
| FR-7 | Alert **auto-disarms** after firing (or remains active) | Configurable | Configurable |
| FR-8 | User can set alert for **multiple items/stocks** | ✅ | ✅ |
| FR-9 | View **price history** chart for an item | ✅ | ✅ |
| FR-10 | Alert **expires** after N days if not triggered | ✅ | ✅ |

### Non-Functional Requirements

| Attribute | E-Commerce (Amazon) | Stock Alerts |
|---|---|---|
| **Price update frequency** | Minutes to hours | Milliseconds (tick data) |
| **Alert fire latency** | < 5 minutes | < 5 seconds |
| **Scale: active alerts** | 5 billion (200M users × 25 alerts) | 500M (50M traders × 10 alerts) |
| **Scale: price updates/sec** | 100K/sec (100M products × 1 update/hour ÷ 3600) | 10M ticks/sec (global markets) |
| **Notification throughput** | 1M alerts fired/day | 10M alerts fired/day (volatile markets) |
| **Availability** | 99.9% (missing one alert is forgivable) | 99.99% (financial — regulatory requirement) |

---

## 2. Capacity Estimation

### E-Commerce Alert System

```
Users:             200M active users
Alerts per user:   avg 25 alerts (some have hundreds)
Total alerts:      200M × 25 = 5 BILLION active alerts

Products:          500M unique products on Amazon
Price updates:     Avg price changes once per hour per product
                   500M / 3600 = ~140K price updates/sec sustained
                   Peak (flash sales, Black Friday): 10× = 1.4M/sec

Alert storage:
  Per alert record: ~150 bytes
  { alert_id(16B) + user_id(16B) + product_id(16B) + condition(4B)
    + target_price(8B) + current_price(8B) + status(4B) + channels(8B)
    + created_at(8B) + expires_at(8B) + last_fired_at(8B) + ... }
  5B alerts × 150 bytes = 750 GB total alert storage

Inverted index (product_id → list of alert_ids):
  Each product has avg 50 alerts watching it (long-tail distribution)
  Hot products (iPhone): 100K+ alerts
  Index size: 500M products × 50 alerts × 8 bytes (alert_id) = 200 GB
  → Must be in-memory (Redis) for fast evaluation

Alert evaluation:
  On each price update: fetch all alerts for that product, evaluate each
  140K updates/sec × 50 alerts/product = 7M evaluations/sec

Alert firing rate:
  Avg 1 alert fires per product per day (conservative)
  500M products × 1/86400 = ~5,800 alerts fire/sec → manageable

Notification:
  Email: 1M/day (12 per sec) via SES
  Push: 5M/day (58 per sec) via FCM/APNs
```

### Stock Alert System (More Demanding)

```
Stocks/crypto tracked: 100K instruments (NYSE + NASDAQ + crypto + forex)
Tick rate: avg 100 ticks/sec per instrument
Total ticks: 100K × 100 = 10M ticks/sec

Active alerts: 500M
Alert evaluation: 10M ticks × 10 alerts/instrument = 100M evaluations/sec
  → Requires heavily optimized evaluation engine

Alert fire rate: Volatile market → 0.1% of alerts fire per day
  500M × 0.001 = 500K alerts fired/day = 6K/sec notifications
```

---

## 3. API Design

### Alert Management APIs

```http
# ============================================================
# CREATE PRICE ALERT
# ============================================================
POST /v1/alerts
Authorization: Bearer {jwt}

{
  "item_type":  "product",          # product | stock | crypto
  "item_id":    "B08F7N7DQ3",       # ASIN (Amazon) or ticker (AAPL, BTC-USD)
  "conditions": [
    {
      "type":         "PRICE_BELOW",  # PRICE_BELOW | PRICE_ABOVE | PRICE_DROP_PERCENT
                                      # | BACK_IN_STOCK | PRICE_CROSSES (stock)
      "target_value": 799.99,         # target price in USD
      "currency":     "USD"
    }
  ],
  "notify_via": ["push", "email"],  # push | email | sms | webhook
  "auto_rearm": false,              # false = fires once and disarms; true = fires every N days
  "rearm_cooldown_hours": 24,       # if auto_rearm=true, minimum hours between fires
  "expires_at": "2026-12-31T23:59:59Z"   # auto-delete if not triggered
}

Response 201:
{
  "alert_id":      "alrt_7f3a9b2c",
  "item_id":       "B08F7N7DQ3",
  "item_name":     "Apple AirPods Pro (2nd Gen)",
  "current_price": 899.00,
  "target_price":  799.99,
  "status":        "ACTIVE",          # ACTIVE | PAUSED | FIRED | EXPIRED
  "created_at":    "2026-04-01T10:00:00Z"
}


# ============================================================
# LIST USER'S ALERTS
# ============================================================
GET /v1/alerts?status=ACTIVE&item_type=product&cursor={token}&limit=50
Response 200:
{
  "alerts": [ { ...alert objects... } ],
  "next_cursor": "eyJ..."
}


# ============================================================
# UPDATE / PAUSE / DELETE ALERT
# ============================================================
PATCH /v1/alerts/{alert_id}
{ "status": "PAUSED" }           # or "ACTIVE" to re-enable

DELETE /v1/alerts/{alert_id}
Response 204 No Content


# ============================================================
# PRICE HISTORY (for chart)
# ============================================================
GET /v1/items/{item_id}/price-history
    ?from=2026-01-01T00:00:00Z
    &to=2026-04-01T00:00:00Z
    &granularity=1d               # 1h | 6h | 1d | 1w

Response 200:
{
  "item_id": "B08F7N7DQ3",
  "currency": "USD",
  "data": [
    { "timestamp": "2026-01-01T00:00:00Z", "price": 949.00 },
    { "timestamp": "2026-01-02T00:00:00Z", "price": 929.00 },
    ...
  ],
  "min_price_30d": 789.00,
  "max_price_30d": 999.00
}


# ============================================================
# INTERNAL: PRICE UPDATE (from product catalog / market feed)
# ============================================================
POST /v1/internal/price-updates          # called by Pricing Service
{
  "updates": [
    {
      "item_id":    "B08F7N7DQ3",
      "old_price":  899.00,
      "new_price":  799.99,
      "currency":   "USD",
      "timestamp":  1711963200123,
      "source":     "SELLER_UPDATE"    # SELLER_UPDATE | FLASH_SALE | DYNAMIC_PRICE
    }
  ]
}
```

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         PRICE SOURCES                                   │
│                                                                         │
│  Amazon Catalog   Stock Exchange   Crypto Exchange   Third-party feeds  │
│  (seller updates, (NYSE, NASDAQ,   (Binance, Coinbase) (CamelCamelCamel)│
│   dynamic pricing) tick data feed)                                      │
└───────┬──────────────────┬───────────────────┬──────────────────────────┘
        │                  │                   │
        ▼                  ▼                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                    PRICE INGESTION SERVICE                                │
│  • Normalize formats (all sources → internal PriceUpdateEvent protobuf)  │
│  • Validate: price > 0, symbol exists, not stale (timestamp check)       │
│  • Dedup: skip if price unchanged from last known value                  │
│  • Publish to Kafka: topic = price-updates                               │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        KAFKA (price-updates)                            │
│  Partition key = item_id / ticker (ensures ordering per item)           │
│  Partitions: 1000 (for 10M ticks/sec stock scenario)                    │
│  Retention: 7 days                                                       │
└──────────┬────────────────────────┬────────────────────────────────────┘
           │                        │
           ▼                        ▼
┌──────────────────────┐  ┌──────────────────────────────────────────────┐
│  PRICE HISTORY       │  │         ALERT EVALUATION ENGINE              │
│  SERVICE             │  │                                              │
│                      │  │  • Reads price updates from Kafka            │
│  • Appends to TSDB   │  │  • For each update: lookup alerts for item   │
│  • ClickHouse /      │  │    from Redis Inverted Index                 │
│    TimescaleDB       │  │  • Evaluate each alert condition             │
│  • Serves price-     │  │  • If condition met: publish to             │
│    history API       │  │    Kafka: alert-fired-events                 │
│  • Powers charts     │  │                                              │
└──────────────────────┘  └────────────────────┬─────────────────────────┘
                                               │
                          ┌────────────────────┼────────────────────────┐
                          ▼                    ▼                        ▼
               ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
               │  ALERT DB      │  │  NOTIFICATION  │  │  DEDUP STORE  │
               │  (PostgreSQL   │  │  DISPATCHER    │  │  (Redis)      │
               │   + Cassandra) │  │                │  │               │
               │               │  │  • Email (SES) │  │  • Prevent    │
               │  • Alert CRUD  │  │  • Push (FCM)  │  │    duplicate  │
               │  • Inverted    │  │  • SMS (Twilio)│  │    alerts in  │
               │    index sync  │  │  • Webhook     │  │    24h window │
               │  • Alert state │  │  • Rate limit  │  │               │
               │    management  │  │    per user    │  │               │
               └────────────────┘  └────────────────┘  └────────────────┘
```

### Data Flow (End-to-End for Amazon Price Drop)

```
t=0ms:    Seller lowers AirPods price $899 → $799 in Seller Portal
t=50ms:   Pricing Service emits PriceUpdateEvent → Kafka (price-updates)
t=100ms:  Alert Evaluation Engine reads from Kafka
t=110ms:  Redis lookup: "which alerts watch B08F7N7DQ3?"
           → Returns 87,423 alert IDs for this product
t=120ms:  Parallel batch evaluation of 87K alerts in-memory
           → Find 3,200 alerts with target_price >= 799
t=130ms:  Publish 3,200 AlertFiredEvent to Kafka (alert-fired-events)
t=200ms:  Notification Dispatcher reads fired events
t=500ms:  FCM push notification sent to 3,200 users' phones
t=1000ms: Email queued for SES batch delivery

Total: price change → user notification in ~1 second ✓ (well under 5-min SLA)
```

---

## 5. Deep Dives

### 5.1 Alert Storage & Inverted Index

> **Interview tip:** This is the core design challenge. The lookup direction is reversed — we need "given a product, find all alerts watching it" — not the other way around.

#### Two Storage Layers

```
Layer 1: Alert Master Store (PostgreSQL + Cassandra)
  Purpose: Source of truth; CRUD operations; user views their alerts

  PostgreSQL (for user-facing CRUD, pagination, filtering):
    CREATE TABLE alerts (
      alert_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id         UUID NOT NULL,
      item_id         VARCHAR(128) NOT NULL,      -- ASIN or ticker
      item_type       VARCHAR(32) NOT NULL,        -- product | stock | crypto
      condition_type  VARCHAR(64) NOT NULL,        -- PRICE_BELOW | PRICE_ABOVE | etc.
      target_value    DECIMAL(18,4) NOT NULL,
      baseline_price  DECIMAL(18,4),              -- for PRICE_DROP_PERCENT
      currency        CHAR(3) NOT NULL DEFAULT 'USD',
      status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
      notify_via      JSONB NOT NULL,             -- ["push", "email"]
      auto_rearm      BOOLEAN DEFAULT FALSE,
      rearm_cooldown  INT DEFAULT 86400,          -- seconds
      last_fired_at   TIMESTAMP WITH TIME ZONE,
      expires_at      TIMESTAMP WITH TIME ZONE,
      created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      -- Indexes:
      -- (user_id, status, created_at DESC) for listing user's alerts
      -- (item_id, status)                  for evaluation lookup
    );

    Sharding: user_id % N shards (user CRUD is dominant access pattern)

  Cassandra (for high-write evaluation state updates):
    Table: alert_evaluation_log
    Partition by: item_id
    → On each evaluation: update last_evaluated_at, last_price_seen
    → Cassandra handles high write throughput per item without contention

Layer 2: Inverted Index (Redis — in-memory for sub-millisecond lookup)
  Purpose: "Which alerts are watching this item?" — critical hot path

  Data structure: Redis Hash of Sorted Sets
    Key:   alerts:{item_id}
    Type:  ZSET (sorted set)
    Score: target_price (float)
    Member: alert_id

  Example: AirPods (B08F7N7DQ3) with alerts at $799, $750, $700:
    ZADD alerts:B08F7N7DQ3 799.99 "alrt_001"
    ZADD alerts:B08F7N7DQ3 750.00 "alrt_002"
    ZADD alerts:B08F7N7DQ3 700.00 "alrt_003"
    ZADD alerts:B08F7N7DQ3 800.00 "alrt_004"   # alert wants < $800

  When price drops to $799:
    "Find all PRICE_BELOW alerts where target_price >= new_price":
    ZRANGEBYSCORE alerts:B08F7N7DQ3 799 +inf
    → Returns [alrt_001, alrt_004]  (score >= 799.00)
    → These users get notified ✓

    "Find PRICE_ABOVE alerts where target_price <= new_price":
    ZRANGEBYSCORE alerts:B08F7N7DQ3 -inf 799
    → For stock "goes above $X" alerts

  Time complexity: O(log N + K) where N = alerts per item, K = triggered alerts
  For hot item with 100K alerts: log(100K) + K = ~17 + K steps → extremely fast

  Memory estimation:
    Redis ZSET per alert: ~128 bytes (sorted set overhead + score + member)
    5B alerts × 128 bytes = 640 GB → Redis Cluster with 32 nodes (20 GB each)
```

#### Keeping PostgreSQL and Redis in Sync

```
Problem: Alert created in PostgreSQL → must appear in Redis too.
         Alert deleted in PostgreSQL → must be removed from Redis.
         Two separate writes = potential inconsistency.

Solution: Change Data Capture (CDC) via Debezium

  Debezium reads PostgreSQL WAL (write-ahead log):
    INSERT into alerts → publishes AltertCreatedEvent
    UPDATE alerts SET status='DELETED' → publishes AlertDeletedEvent
    UPDATE alerts SET status='FIRED' → publishes AlertFiredEvent

  Alert Sync Service (Kafka consumer):
    On AlrtCreatedEvent:
      ZADD alerts:{item_id} {target_price} {alert_id}   (Redis)
    On AlertDeletedEvent:
      ZREM alerts:{item_id} {alert_id}                   (Redis)
    On AlertFiredEvent + auto_rearm=false:
      ZREM alerts:{item_id} {alert_id}                   (Redis)
    On AlertFiredEvent + auto_rearm=true:
      (keep in Redis; will re-fire after cooldown)

  Benefits:
    → PostgreSQL is write-ahead log source of truth
    → Redis is eventually consistent (within < 1s of DB write)
    → No dual-write failure scenarios
    → Redis can be rebuilt from PostgreSQL at any time (cold start)
```

---

### 5.2 Price Change Event Pipeline

#### Deduplication of Price Updates

```
Problem: Same price update arrives multiple times from different sources.
  Amazon Pricing Service → update $899 → $799
  Flash Sale System also emits → update $899 → $799
  Kafka retry on offset commit failure → same event delivered twice

  Without dedup: alert fires twice → user gets 2 pushes for same price drop.

Solution: Price-change dedup using Redis last-seen cache

  Key:   last_price:{item_id}
  Value: { price: 899.00, currency: "USD", ts: 1711963100000 }
  TTL:   24 hours

  On each incoming price update:
    current = HGET last_price:{item_id}
    IF current.price == new_price: SKIP (no change)
    IF current.ts > event.timestamp: SKIP (stale update — older than what we have)
    ELSE:
      HSET last_price:{item_id} price:{new_price} ts:{now}
      Forward to evaluation engine

  Memory: 500M products × 32 bytes = 16 GB → fits in Redis Cluster

Handling flash sales (rapid price oscillations):
  Amazon Flash Sale: price bounces $899 → $699 → $899 → $699 → $899 (within minutes)
  
  Without handling: users get 3 notifications (prices keep crossing their threshold)
  
  Solution: Alert cooldown (see §5.4 Deduplication)
```

#### Price Update Ingestion Sources

```
Source 1: Amazon Seller Portal
  Sellers update prices → triggers internal PriceChangeEvent
  Volume: 100K updates/sec normally; 500K during Black Friday sales
  Format: { asin, old_price, new_price, seller_id, timestamp }

Source 2: Amazon Dynamic Pricing Engine
  Algorithmic pricing (repricing to beat competitors)
  Updates can be very frequent: some high-demand products → 1,000+ changes/day
  Throttle: limit Kafka publish to max 1 price-update per item per 5 minutes
  (Avoid evaluation storm for highly dynamic products)
  Implementation: Redis rate limiter on Kafka producer side

Source 3: Stock Exchange Tick Data
  NYSE/NASDAQ: FIX protocol feed → internal adapter → PriceUpdateEvent
  Volume: 10M ticks/sec during market hours
  Requires separate high-throughput Kafka cluster (stock-ticks topic)
  Binary encoding (not JSON): Protobuf for 10M/sec efficient serialization

Source 4: Third-party Scrapers (for products not on Amazon)
  CamelCamelCamel-style price tracking: scrape competitor prices
  Push via webhook → Price Ingestion Service
  Rate: 10K updates/sec (lower volume)
```

---

### 5.3 Alert Evaluation Engine

> **The heart of the system. This is what separates a senior design from a junior one.**

#### Evaluation Logic for All Condition Types

```python
def evaluate_alert(alert: Alert, old_price: float, new_price: float) -> bool:
    """
    Returns True if alert condition is met and notification should fire.
    """

    # Condition 1: PRICE_BELOW — price dropped below target
    if alert.condition_type == "PRICE_BELOW":
        # Fire if: new price is at or below target AND old price was above target
        # (Only fire on the crossing, not every subsequent update)
        return new_price <= alert.target_value and old_price > alert.target_value

    # Condition 2: PRICE_ABOVE — price rose above target (stock alert)
    elif alert.condition_type == "PRICE_ABOVE":
        return new_price >= alert.target_value and old_price < alert.target_value

    # Condition 3: PRICE_DROP_PERCENT — price dropped by X% from baseline
    elif alert.condition_type == "PRICE_DROP_PERCENT":
        # baseline_price = price at time of alert creation
        drop_pct = (alert.baseline_price - new_price) / alert.baseline_price * 100
        was_above = (alert.baseline_price - old_price) / alert.baseline_price * 100
        return drop_pct >= alert.target_value and was_above < alert.target_value

    # Condition 4: BACK_IN_STOCK
    elif alert.condition_type == "BACK_IN_STOCK":
        return old_price is None and new_price is not None
        # old_price=None means was out of stock; new_price != None = back in stock

    # Condition 5: PRICE_CROSSES (bidirectional, for stock volatility alerts)
    elif alert.condition_type == "PRICE_CROSSES":
        lower = min(alert.target_value, alert.secondary_target)
        upper = max(alert.target_value, alert.secondary_target)
        was_inside = lower <= old_price <= upper
        is_outside = new_price < lower or new_price > upper
        return was_inside and is_outside  # price exited the band

    # Unknown condition type: log and skip
    else:
        log.error(f"Unknown condition type: {alert.condition_type}")
        return False
```

#### Evaluation Engine Architecture (Scale: 7M evaluations/sec)

```
Challenge: 140K price updates/sec × avg 50 alerts each = 7M evaluations/sec

Option A: Simple DB Query Approach
  On price update: SELECT * FROM alerts WHERE item_id = ? AND status='ACTIVE'
  Evaluate each in application code
  Problem: 140K queries/sec against PostgreSQL → DB overwhelmed
  ✗ Rejected: DB can't handle this write + read mix

Option B: Redis Inverted Index + In-Memory Evaluation (Chosen)
  On price update:
  a. ZRANGEBYSCORE alerts:{item_id} {new_price} +inf → get PRICE_BELOW alert IDs
     (target_price >= new_price → these users want price BELOW their target)
  b. For each returned alert_id: evaluate full condition in-memory
     (fetch alert details from Redis HASH or local LRU cache)

  Optimization — Store full alert in Redis too:
    Key: alert_meta:{alert_id}
    Value: { condition_type, target_value, user_id, notify_via, auto_rearm, rearm_cooldown }
    → Avoids DB lookup during evaluation hot path
    → Populated via CDC from PostgreSQL (same Debezium stream)

  Evaluation pipeline pseudocode:
    def on_price_update(event):
        item_id  = event.item_id
        new_price = event.new_price
        old_price = get_last_price(item_id)   # Redis GET last_price:{item_id}

        # PRICE_BELOW alerts: target >= new_price AND target < old_price (crossing)
        triggered_ids = ZRANGEBYSCORE alerts_below:{item_id}
                                       new_price    (old_price - epsilon)

        # PRICE_ABOVE alerts (separate ZSET, sorted by target ascending):
        triggered_ids += ZRANGEBYSCORE alerts_above:{item_id}
                                       (old_price + epsilon)    new_price

        for alert_id in triggered_ids:
            meta = HGETALL alert_meta:{alert_id}  # O(1) Redis hash lookup
            if evaluate_alert(meta, old_price, new_price):
                if not is_in_cooldown(alert_id):  # dedup check
                    publish_to_kafka(alert_id, item_id, new_price)

  Two ZSET design (PRICE_BELOW and PRICE_ABOVE in separate sets):
    alerts_below:{item_id}: ZSET scored by target_price (check: score >= new_p)
    alerts_above:{item_id}: ZSET scored by target_price (check: score <= new_p)

  Time complexity per item update: O(log N + K + K) = O(log N + K)
  where K = number of triggered alerts
  At 50 alerts/item, K << N typically → very fast
```

---

### 5.4 Notification Delivery & Deduplication

#### Alert Deduplication (Most Critical Correctness Concern)

```
Problem: Without deduplication, users get spammed.

Scenario 1: Price bounces around threshold
  AirPods: $900 → $799 → $810 → $795 → $820 → $798...
  Alert target: $800
  Without dedup: fires 3 times in 10 minutes (at $799, $795, $798)
  User experience: terrible — inbox flooded

Scenario 2: Kafka consumer retries
  Alert fires, notification published to Kafka
  Consumer crashes before ACK → Kafka redelivers → duplicate notification

Scenario 3: Evaluation engine horizontal scaling
  2 pods read same partition (during rebalance) → both evaluate same update
  Both fire the same alert → user gets 2 pushes

Solution: Redis-based deduplication with cooldown window

  Key:   dedup:{alert_id}
  Value: { fired_at, price_seen }
  TTL:   rearm_cooldown_seconds (default: 86400 = 24 hours)

  Before publishing AlertFired event:
    result = SET dedup:{alert_id} {fired_at} NX EX {cooldown_seconds}
      NX = "only set if NOT exists" (atomic compare-and-set)
      EX = TTL in seconds

    IF result == "OK" (key was set):
      → First fire in cooldown window → proceed with notification
    IF result == None (key already existed):
      → Already fired recently → skip (silent dedup)

  This atomic Redis SET NX EX prevents race conditions:
    Even if 2 pods try to fire same alert simultaneously:
    → Only one gets "OK"; other gets None → no duplicate

Re-arm logic:
  auto_rearm = false: Alert permanently disarmed after first fire
    → After firing: SET alert status='FIRED' in PostgreSQL (via CDC → ZREM from Redis)
  
  auto_rearm = true: Alert re-activates after cooldown
    → dedup key TTL expires → Redis key gone → next crossing fires again
    → No explicit "re-arm" action needed (TTL handles it)
    → Use case: stock trader who wants daily alerts if stock stays above $200
```

#### Notification Dispatcher

```
Notification Dispatcher reads from Kafka (alert-fired-events):
  Topic: alert-fired-events
  Partition by: user_id (ensures ordering per user; rate limiting per user)

For each AlertFiredEvent:
  1. Fetch user preferences: notification channels, language, unsubscribe status
  2. Per-user rate limiting:
       Key: notify_rate:{user_id}:{hour}
       INCR → if > 10: skip (user rate-limited — had 10+ alerts this hour)
       Prevents notification bombing users with many alerts
  3. Render notification content:
       Template: "[New Low Price!] AirPods Pro dropped to $799 (was $899)"
       Localize: price formatting, currency symbol, language
  4. Dispatch by channel:
       Push: FCM (Android) / APNs (iOS) — fire and forget, async
       Email: SES batch API (batch records, delivered within 5 min)
       SMS: Twilio/SNS (for price drops > 20%, or user preference)
       Webhook: POST to user-configured URL (for developers)
  5. Record delivery:
       INSERT notification_log(alert_id, user_id, channel, status, ts)
       (For "why didn't I get notified?" customer support queries)

Channel priority for alert-critical scenarios:
  Stock crossing alert (time-sensitive): Push first (< 1s), then email
  Product price drop (less urgent): Email first (batch), push if user opted in
  Out-of-stock alert: Push only (immediate action usually needed)
```

---

### 5.5 Stock Price Alerts — Tick-Level Precision

#### Why Stock Alerts Are Harder

```
E-commerce: 100K price updates/sec; alerts OK within 5 minutes
Stocks:     10M ticks/sec; alerts MUST fire within 5 seconds
             → 100× volume AND 60× stricter latency

New constraints:
  1. Market hours: NYSE 9:30 AM - 4:00 PM ET; evaluation pauses at close
  2. After-hours trading: separate tick feed, different volumes
  3. Alert evaluation backpressure: burst on market open (millions of stale alerts)
  4. Pre-market alerts: high demand for early morning price movements
  5. Regulatory: MiFID II / SEC requires audit trail of all alerts fired
```

#### Tick-Level Evaluation Architecture

```
Volume: 10M ticks/sec requires different architecture than e-commerce.

Kafka cluster (stock-ticks):
  10M ticks/sec × 100 bytes/tick = 1 GB/s ingest
  1000 partitions (partition key = ticker hash % 1000)
  Each partition: 10K ticks/sec = 1 MB/s → manageable

Evaluation Engine for stocks:
  Per-partition consumer (1000 consumers for 1000 partitions)
  Each consumer handles avg 100 tickers × 100 ticks/sec = 10K evaluations/sec

  In-memory alert index per consumer (local to each pod):
    ticker → sorted list of (target_price, alert_id) pairs
    → No Redis hop needed on the evaluation hot path!
    → Consumer has its own in-memory copy for its assigned tickers

  How to maintain local index?
    On startup: load all alerts for my tickers from Redis (cold start)
    On-going: subscribe to Kafka alert-changes topic
      → Alert created → add to local index
      → Alert deleted → remove from local index
    Memory/pod: if pod owns 100 tickers × avg 1K alerts × 64 bytes = 6.4 MB
    → Trivially small — entire working set in L2/L3 cache = fast!

  Evaluation with NO Redis hop:
    on_tick(ticker, old_price, new_price):
      alerts = local_index[ticker]
      # Binary search (sorted list): find alerts where target >= new_price
      idx = bisect_left(alerts_by_price, new_price)
      triggered = alerts_by_price[idx:]
      for alert in triggered:
          if old_price < alert.target_price:   # was above, now crossed below
              fire(alert)

  Throughput per pod: 10K evaluations/sec × ~1 μs/evaluation = 1% CPU utilization
  → 100× headroom; scales easily

Pre-market alert storm (9:30 AM market open):
  Problem: All stocks gapped up/down overnight
  Many alerts become stale (price already crossed threshold) when market opens
  
  Solution: Alert snapshot evaluation at 9:29 AM
    Before market open: load all alerts for each ticker
    Compare against pre-market price (if available) or last close price
    Pre-compute which alerts WILL likely fire at open
    → Pre-stage in Kafka; dispatch in controlled bursts (not all at 9:30:00 AM)
```

#### Audit Trail for Stocks (Regulatory Requirement)

```
SEC / FINRA requirements:
  All alerts fired must be logged with:
  - Exact timestamp (milliseconds) when condition was met
  - Exact price that triggered the alert
  - User ID, alert ID
  - Which tick data feed sourced the price

Implementation:
  All AlertFiredEvents appended to Cassandra audit table:
    CREATE TABLE alert_audit (
      dt          DATE,                    -- partition by day
      fired_at    TIMESTAMP NOT NULL,
      alert_id    UUID NOT NULL,
      user_id     UUID NOT NULL,
      ticker      VARCHAR(16),
      trigger_price DECIMAL(18,4) NOT NULL,
      feed_source VARCHAR(64),             -- NYSE_CONSOLIDATED | NASDAQ_QMF
      PRIMARY KEY (dt, fired_at, alert_id)
    );
  
  Retention: 7 years (SEC requirement for broker-dealers)
  Storage: 500K fires/day × 150 bytes × 365 × 7 = ~200 GB (manageable)
```

---

## 6. Scale & Resilience

### Scaling the Inverted Index

```
5B alerts across 500M products
Redis Cluster: 32 nodes × 20 GB each = 640 GB total
Partition by: consistent hash of item_id → Redis shard

Hot products (top 0.1% of items: 500K products):
  Each hot product: avg 100K+ alerts watching it
  ZRANGEBYSCORE on 100K element ZSET: O(log 100K + K) = ~17 + K steps
  Fast even for hot products ✓

Hot ZSET size bound:
  100K alerts × 128 bytes/entry = 12.8 MB per ZSET
  → Single Redis key can hold this (Redis handles up to 4 GB per value)
  → Not a problem; single-command lookup for any product
```

### Failure Scenarios

| Failure | Detection | Recovery |
|---|---|---|
| **Evaluation Engine pod crash** | Kafka consumer lag alert | Pod restarts; reprocesses from last committed offset; alertdedup prevents double-fire |
| **Redis inverted index crash** | Connection timeout | Rebuild from PostgreSQL + CDC stream on restart (~10 min for 5B alerts); accept higher latency |
| **Kafka lag spike** | Consumer lag metric | Scale out evaluation pods; K8s HPA; Kafka retains 7 days |
| **Notification dispatcher failure** | DLQ depth growing | Alert-fired events in Kafka; re-process after restart; dedup prevents duplicate sends |
| **PostgreSQL primary failure** | Replica lag + health check | Failover to replica (< 30s); writes buffered in Kafka |
| **Alert dedup Redis failure** | Connection timeout | Fail open: allow notification even without dedup → user may get 1 duplicate (acceptable) |

### Black Friday / Market Crash Stress Events

```
Black Friday: 10× normal price update volume
  Normal: 140K updates/sec
  Black Friday: 1.4M updates/sec

  Mitigation:
  1. Pre-scale evaluation cluster 2 hours before event (K8s cluster autoscaling)
  2. Throttle low-priority products: limit evaluation to 1 per product per 5 min
  3. Priority queue: items with most active alerts evaluated first
  4. Shed load: if lag > 30s, skip evaluation for lower-tier products

Market crash (2020-style circuit breaker):
  All stocks move sharply → 99% of all stock alerts fire simultaneously
  500M alerts × 1% = 5M fires in 60 seconds = 83K fires/sec

  Mitigation:
  1. Per-user rate limit: max 5 notifications in 5 minutes (notification throttle)
  2. Notification aggregation: "7 of your 10 stock alerts triggered. View details →"
     Instead of 7 separate push notifications → 1 summary push
  3. Email digest: batch all triggered alerts into 1 email per user (vs 7 emails)
  4. FCM/APNs rate limits: mobile platforms have per-app rate limits
     → Build in retry with exponential backoff for failed pushes
```

---

## 7. Trade-offs & Alternatives

| Decision | Choice | Alternative | Reason |
|---|---|---|---|
| **Alert index** | Redis ZSET (inverted, scored by target_price) | DB query on evaluation | ZRANGEBYSCORE is O(log N + K) vs DB: 140K queries/sec would crush DB |
| **Evaluation trigger** | Event-driven (price change → evaluate) | Periodic batch (every 5 min, compare all prices vs all alerts) | Event-driven: fires within 1s; batch: up to 5 min lag; batch can't meet stock SLA |
| **Data sync** | CDC (Debezium) to keep Redis in sync | Dual-write (write PostgreSQL + Redis) | CDC: DB is truth; Redis rebuilt on failure; dual-write: partial failure = corruption |
| **Stock evaluation** | In-memory local index per consumer pod | Redis lookup per tick | Local: 1μs/eval, no network; Redis: 1ms/eval × 10M ticks/sec = 10K req/sec per tick (too slow) |
| **Dedup mechanism** | Redis SET NX EX (atomic) | DB upsert with unique constraint | Redis: < 1ms, atomic, auto-TTL; DB: ~5ms, needs cleanup job for expired dedup records |
| **Notification** | Async Kafka → Dispatcher | Inline in Evaluation Engine | Decoupled: evaluation and notification scale independently; dispatcher can retry without re-evaluation |
| **Hot product strategy** | ZSET handles naturally | Shard by alert_id | ZSET: one command fetches all alerts for product; no coordination needed |
| **Auto-rearm** | TTL-based (dedup key expires) | Explicit re-arm state machine | TTL: simpler; auto-rearm = dedup key expires; no separate state machine needed |

### Architecture Evolution

```
MVP (startup, < 1M users, < 10M alerts):
  PostgreSQL for everything (alerts + price history)
  Polling: cron job every 5 minutes evaluates recent price changes
  Email only (SES)
  Works fine up to ~1M alerts evaluated in a batch job

Growth (1M-10M users, up to 1B alerts):
  Add Redis inverted index
  Kafka for price updates
  Event-driven evaluation engine (Node.js workers)
  Add FCM/APNs push notifications

Scale (100M+ users, 5B+ alerts):
  Redis Cluster for inverted index
  Separate stock evaluation track (in-memory per consumer)
  Notification aggregation to prevent alert bombs
  Multi-region deployment (alerts stored in user's home region)
```

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Asked E-commerce vs Stock vs both (different SLA requirements)
  [ ] Identified 5 condition types (BELOW/ABOVE/PCT/BACK_IN_STOCK/CROSSES)
  [ ] Asked about auto-rearm vs single-fire semantics
  [ ] Discussed price bouncing problem immediately (shows foresight)

CAPACITY
  [ ] 5B total alerts × 150 bytes = 750 GB alert storage
  [ ] Redis inverted index: 640 GB (32 nodes × 20 GB) for 5B alert entries
  [ ] 140K price updates/sec × 50 alerts each = 7M evaluations/sec
  [ ] 83K notification fires/sec on Black Friday / market crash

ARCHITECTURE
  [ ] Inverted index (product → alerts) as core insight — not alerts → products
  [ ] CDC (Debezium) to keep Redis in sync with PostgreSQL (not dual-write)
  [ ] Separate evaluation from notification (Kafka between them)
  [ ] Two Kafka topics: price-updates and alert-fired-events

DEEP DIVES

  Alert Storage & Index:
    [ ] Redis ZSET scored by target_price → ZRANGEBYSCORE O(log N + K)
    [ ] Two ZSETs per item: alerts_below and alerts_above
    [ ] Dedup key: Redis SET NX EX (atomic, TTL-based)
    [ ] CDC via Debezium to sync PostgreSQL → Redis (not dual-write)

  Price Change Pipeline:
    [ ] Dedup using Redis last_price: skip if price unchanged
    [ ] Rate limiting dynamic pricing products (max 1 update per 5 min per item)
    [ ] Separate Kafka clusters for e-commerce vs stock ticks

  Evaluation Engine:
    [ ] "Crossing" semantics: fire only when price CROSSES threshold (not every update below it)
    [ ] Python pseudocode for each condition type evaluation
    [ ] 7M evaluations/sec requires Redis inverted index, not DB queries
    [ ] PRICE_DROP_PERCENT uses baseline_price stored at alert creation time

  Notification & Dedup:
    [ ] Redis SET NX EX: atomic dedup, self-expiring
    [ ] Per-user rate limit (10 notifications/hour max)
    [ ] Notification aggregation (1 "7 alerts triggered" push vs 7 pushes)
    [ ] Audit log for stocks (Cassandra, 7-year retention, SEC requirement)

  Stock Alerts:
    [ ] In-memory local alert index per consumer pod (no Redis hop per tick)
    [ ] 10M ticks/sec vs 140K product updates/sec (100× harder)
    [ ] Pre-market storm handling: pre-evaluate before 9:30 AM open
    [ ] Market crash: notification aggregation + per-user rate limit

SCALE & RESILIENCE
  [ ] Evaluation pods: Kafka consumer → ZRANGEBYSCORE → evaluate → fire → Kafka
  [ ] Redis crash: rebuild from PostgreSQL → CDC stream (10 min rebuild for 5B alerts)
  [ ] Black Friday 10× load: pre-scale + product evaluation rate limiting
  [ ] Alert dedup Redis failure: fail open (user gets at most 1 duplicate) — acceptable

TRADE-OFFS
  [ ] Event-driven vs batch evaluation (latency: 1s vs 5 min)
  [ ] CDC vs dual-write (correctness vs simplicity)
  [ ] In-memory local index for stocks vs Redis (throughput: 1μs vs 1ms)
  [ ] Single-fire vs auto-rearm alerts (cooldown TTL elegance)
```
