# High-Level Design: Stock Exchange / Trading Platform (Groww-style) — SDE3 Interview

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: retail brokerage (Groww) vs exchange (NSE/BSE). Groww is a **broker**, not the exchange.
2. Establish scale, latency, and consistency SLOs.
3. Walk through architecture with focus on the **Order Management System** and **Market Data pipeline**.
4. Deep-dive the **Order Matching Engine** (most critical component).
5. Cover portfolio, P&L, risk checks, regulatory compliance, and failure handling.

---

## 1. Problem Statement And Scope

Design a retail stock trading platform like **Groww** that supports:
- User onboarding with KYC (Aadhaar/PAN-based).
- Demat account and trading account management.
- Equity, Mutual Fund, F&O (Futures & Options) trading.
- Order placement: Market, Limit, Stop-Loss, GTT (Good Till Triggered).
- Real-time order status and execution reports.
- Live market data: quotes, depth (order book), charts, indices.
- Portfolio view: holdings, unrealised/realised P&L, XIRR.
- Fund management: add money, withdraw, margin management.

### 1.1 System Architecture Context: Broker vs Exchange

```
+----------+       +------------------+       +----------+
| Groww    |       | Exchange Broker  |       | NSE/BSE  |
| (Retail  | <---> | (Zerodha/HDFC    | <---> | (Actual  |
|  App)    |       |  Securities)     |       | Exchange)|
+----------+       +------------------+       +----------+
     |                    |                        |
  User places        OMS routes               Matching Engine
  order on app       to exchange              matches orders
                     via FIX protocol         Clearing & settlement
```

**Key distinction for interview**: Groww itself does NOT run the matching engine — NSE/BSE does. Groww's core challenges are:
- **Order Management System (OMS)**: Route orders to the right exchange.
- **Risk Management System (RMS)**: Pre-trade checks before order reaches exchange.
- **Market Data**: Stream real-time prices to millions of users.
- **Portfolio Management**: Real-time P&L, holdings, settlement.

---

## 2. Requirements

### 2.1 Functional Requirements
1. User completes KYC, gets Demat + Trading account.
2. User places equity/MF/F&O orders (market, limit, SL, GTT orders).
3. Orders routed to NSE/BSE via FIX protocol.
4. Real-time order status updates: open → partially filled → filled / rejected / cancelled.
5. Live market data: LTP (Last Traded Price), bid/ask, OHLC, volume, market depth.
6. Portfolio: holdings, quantity, avg buy price, current value, P&L.
7. Fund management: UPI/net banking to trading account, withdrawal to bank.
8. Mutual fund investments: SIP, lumpsum, redemption, NAV-based pricing.

### 2.2 Non-Functional Requirements
- **Latency**: Order placement to exchange acknowledgement: **< 50ms P99** (for algo/retail).
  Market data feed to client: **< 500ms P99**.
- **Throughput**: 10 million active users; peak orders during market open: **100,000 orders/min** (1,667 orders/sec).
- **Availability**: 99.99% during market hours (9:15 AM – 3:30 PM IST). Planned downtime during off-market hours acceptable.
- **Consistency**: Order state must be **exactly-once** — no duplicate orders ever sent to exchange.
- **Durability**: Orders and trades must never be lost (SEBI mandates 5-year retention).
- **Fairness**: Orders must be processed in arrival order (FIFO per security).

---

## 3. Back-Of-The-Envelope Capacity Planning

| Parameter | Value |
|---|---|
| Registered users | 50 million |
| Active traders (market hours) | 5 million DAU |
| Orders/day | 10 million |
| Peak orders/sec (market open) | 2,000 |
| Market data ticks/sec from NSE | 1 million (across all symbols) |
| Concurrent WebSocket connections | 5 million |
| Symbols tracked | 5,000 (NSE) + 4,000 (BSE) |

### 3.1 Market Data Volume
- NSE broadcasts: ~1M price ticks/sec across all symbols.
- Per symbol: ~200 ticks/sec for active stocks (Reliance, TCS).
- Fan-out: 5M users watching live prices → **cannot broadcast every tick individually**.
- Solution: **tick aggregation** — consolidate to 1 update/sec/symbol before pushing to clients.

### 3.2 Order Storage
- 10M orders/day × 500 bytes = 5 GB/day → ~1.8 TB/year (easily manageable).
- Trade records: 5M fills/day (many orders partially fill).

---

## 4. High-Level API Design

### 4.1 Order Management

```http
# Place order
POST /v1/orders
{
  "symbol": "RELIANCE",
  "exchange": "NSE",
  "order_type": "LIMIT",        # MARKET | LIMIT | SL | SL-M | GTT
  "transaction_type": "BUY",    # BUY | SELL
  "quantity": 10,
  "price": 2500.00,             # required for LIMIT/SL orders
  "trigger_price": 2480.00,     # required for SL/GTT
  "product": "CNC",             # CNC (delivery) | MIS (intraday margin) | NRML (F&O)
  "validity": "DAY",            # DAY | IOC (Immediate or Cancel) | GTT
  "idempotency_key": "client-uuid-abc123"
}
→ { "order_id": "ORD_xyz", "status": "open", "timestamp": "..." }

# Modify order (only for open/pending orders)
PUT /v1/orders/{orderId}
{ "quantity": 5, "price": 2490.00 }

# Cancel order
DELETE /v1/orders/{orderId}

# Get order status
GET /v1/orders/{orderId}

# Order book (all orders for user)
GET /v1/orders?status=open|complete|cancelled&date=2024-03-11
```

### 4.2 Market Data

```http
# Get quote (snapshot)
GET /v1/market/quote?symbols=RELIANCE,TCS,INFY&exchange=NSE
→ [{ symbol, ltp, change, change_pct, open, high, low, close, volume, bid, ask }]

# Market depth (order book — Level 2 data)
GET /v1/market/depth?symbol=RELIANCE&exchange=NSE
→ {
    bids: [{ price: 2499, qty: 500, orders: 12 }, ...],  # top 5 bid levels
    asks: [{ price: 2500, qty: 300, orders: 8 }, ...]    # top 5 ask levels
  }

# OHLC chart data
GET /v1/market/chart?symbol=RELIANCE&interval=5m&from=...&to=...

# WebSocket for live quotes
WS /v1/feed
→ Subscribe: { "action": "subscribe", "symbols": ["RELIANCE", "TCS"] }
← Stream: { "symbol": "RELIANCE", "ltp": 2501.50, "timestamp": "..." }
```

### 4.3 Portfolio And Holdings

```http
# Holdings (delivery stocks in Demat)
GET /v1/portfolio/holdings
→ [{ symbol, quantity, avg_price, ltp, current_value, pnl, pnl_pct, day_change }]

# Positions (intraday / F&O open positions)
GET /v1/portfolio/positions
→ [{ symbol, qty, avg_price, ltp, pnl, product_type }]

# P&L report
GET /v1/portfolio/pnl?from=2024-01-01&to=2024-03-31
→ { realised_pnl, unrealised_pnl, xirr }

# Funds (trading account balance)
GET /v1/funds
→ { available_cash, used_margin, total_value, withdrawable_amount }
```

---

## 5. Data Model

### 5.1 Orders

```sql
CREATE TABLE orders (
  order_id          VARCHAR(64)  PRIMARY KEY,
  user_id           VARCHAR(64)  NOT NULL,
  idempotency_key   VARCHAR(128) UNIQUE NOT NULL,
  symbol            VARCHAR(32)  NOT NULL,
  exchange          ENUM('NSE','BSE','NFO','MCX') NOT NULL,
  order_type        ENUM('MARKET','LIMIT','SL','SL-M','GTT') NOT NULL,
  transaction_type  ENUM('BUY','SELL') NOT NULL,
  product           ENUM('CNC','MIS','NRML') NOT NULL,
  quantity          INT NOT NULL,
  pending_quantity  INT NOT NULL,          -- remaining unfilled quantity
  filled_quantity   INT NOT NULL DEFAULT 0,
  price             DECIMAL(10,2),         -- null for MARKET orders
  trigger_price     DECIMAL(10,2),         -- for SL/GTT
  validity          ENUM('DAY','IOC','GTT'),
  status            ENUM('open','pending','complete','cancelled',
                         'rejected','amc_cancelled') NOT NULL,
  exchange_order_id VARCHAR(64),           -- NSE/BSE assigned order ID
  avg_fill_price    DECIMAL(10,2),
  placed_at         TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL,
  rejection_reason  VARCHAR(256),
  INDEX (user_id, placed_at DESC),
  INDEX (exchange_order_id),
  INDEX (idempotency_key),
  INDEX (symbol, status, placed_at)       -- for GTT monitoring
);
```

### 5.2 Trades (Fills)

```sql
-- Each fill event from exchange (one order can have multiple fills)
CREATE TABLE trades (
  trade_id          VARCHAR(64)  PRIMARY KEY,
  order_id          VARCHAR(64)  NOT NULL,
  user_id           VARCHAR(64)  NOT NULL,
  symbol            VARCHAR(32)  NOT NULL,
  exchange          VARCHAR(8)   NOT NULL,
  transaction_type  ENUM('BUY','SELL') NOT NULL,
  quantity          INT NOT NULL,          -- quantity for this specific fill
  price             DECIMAL(10,2) NOT NULL,
  trade_value       DECIMAL(14,2) NOT NULL,
  exchange_trade_id VARCHAR(64),
  traded_at         TIMESTAMP NOT NULL,
  INDEX (order_id),
  INDEX (user_id, traded_at DESC),
  INDEX (symbol, traded_at DESC)
);
```

### 5.3 Holdings (Demat Positions)

```sql
-- Long-term delivery holdings (updated T+1 after settlement)
CREATE TABLE holdings (
  holding_id        VARCHAR(64)  PRIMARY KEY,
  user_id           VARCHAR(64)  NOT NULL,
  symbol            VARCHAR(32)  NOT NULL,
  isin              VARCHAR(16)  NOT NULL,
  quantity          INT NOT NULL,
  avg_price         DECIMAL(10,2) NOT NULL,   -- weighted average cost
  last_updated_at   TIMESTAMP NOT NULL,
  PRIMARY KEY (user_id, symbol),
  INDEX (user_id)
);
```

### 5.4 User Funds

```sql
CREATE TABLE user_funds (
  user_id           VARCHAR(64)  PRIMARY KEY,
  available_cash    DECIMAL(14,2) NOT NULL DEFAULT 0,
  used_margin       DECIMAL(14,2) NOT NULL DEFAULT 0,
  collateral_value  DECIMAL(14,2) NOT NULL DEFAULT 0,  -- pledged holdings margin
  version           BIGINT NOT NULL DEFAULT 0,          -- optimistic locking
  updated_at        TIMESTAMP NOT NULL
);

-- Fund transaction ledger (every add/withdraw/charge)
CREATE TABLE fund_ledger (
  entry_id          VARCHAR(64) PRIMARY KEY,
  user_id           VARCHAR(64) NOT NULL,
  entry_type        ENUM('credit','debit','margin_block','margin_release',
                         'brokerage','stt','stamp_duty','settlement') NOT NULL,
  amount            DECIMAL(14,2) NOT NULL,
  reference_id      VARCHAR(128),           -- order_id or payment_id
  balance_after     DECIMAL(14,2) NOT NULL,
  posted_at         TIMESTAMP NOT NULL,
  INDEX (user_id, posted_at DESC)
);
```

### 5.5 Market Data Tick Store

```sql
-- Time-series store (TimescaleDB or InfluxDB — NOT regular PostgreSQL)
CREATE TABLE price_ticks (
  symbol      VARCHAR(32)   NOT NULL,
  exchange    VARCHAR(8)    NOT NULL,
  timestamp   TIMESTAMPTZ   NOT NULL,
  ltp         DECIMAL(10,2) NOT NULL,
  volume      BIGINT,
  bid         DECIMAL(10,2),
  ask         DECIMAL(10,2),
  PRIMARY KEY (symbol, exchange, timestamp)
);
-- Hypertable with 1-day partitioning (TimescaleDB)
-- Continuous aggregate for 1m, 5m, 15m, 1h OHLCV candles
```

---

## 6. High-Level Architecture

```text
+-------------------------  Clients  -------------------------+
|  Groww Android/iOS  |  Web Browser  |  API (Algo traders)  |
+-------+-------------+-------+-------+-------+--------------+
        |                     |               |
   [CDN: Static]         [API Gateway]    [FIX Gateway]
                          (Auth, RL)      (Algo / Direct)
                               |
    +--------------------------+---------------------------+
    |           |              |            |              |
    v           v              v            v              v
+-------+  +-------+   +-----------+  +--------+   +----------+
|Account|  |Market |   |Order Mgmt |  |Portfolio|  |Funds &   |
|& KYC  |  |Data   |   |System     |  |Service  |  |Margin    |
|Service|  |Service|   |(OMS)      |  |         |  |Service   |
+-------+  +---+---+   +-----+-----+  +----+----+  +----+-----+
               |             |             |             |
               v             v             |             |
        +----------+  +------+------+      |             |
        |Tick Store|  |Risk Mgmt    |      |             |
        |(Timescale|  |System (RMS) |      |             |
        |/InfluxDB)|  +------+------+      |             |
               |             |             |             |
               v             v             v             v
        +------+----+  +-----+----------+------+--------+------+
        |Market Data|  | Exchange Connector              |      |
        |Cache(Redis|  | (FIX Protocol / broker API)     | Main |
        |  pub/sub) |  +----+-------------------+--------+ DB   |
        +-----------+       |                   |        |(PG)  |
                            v                   v        +------+
                         NSE OMS             BSE OMS
                         (FIX 4.4)           (FIX 4.4)
```

---

## 7. Detailed Component Design

### 7.1 Order Management System (OMS) — Core Component

The OMS is the heart of any trading platform. It manages the full lifecycle of an order from user intent to exchange execution.

#### 7.1.1 Order Lifecycle

```
User places order
       │
       ▼
[Order Validation]         ── missing fields, symbol exists, exchange valid?
       │
       ▼
[Idempotency Check]        ── idempotency_key → existing order? Return it.
       │
       ▼
[RMS Pre-Trade Check]      ── funds, margin, risk limits (Section 7.2)
       │
       ├── REJECTED (insufficient funds, circuit breaker, suspended symbol)
       │
       ▼
[Persist Order]            ── INSERT status=open; idempotency anchor
       │
       ▼
[Route to Exchange]        ── FIX NewOrderSingle → NSE/BSE
       │
       ▼
[Exchange ACK]  ←─── exchange_order_id assigned
       │
       ├── REJECTED by exchange → update status=rejected, notify user
       │
       ▼
[Fill Events stream in]    ── ExecutionReport messages from exchange
       │
       ├── Partial fill → update filled_qty, pending_qty
       │
       └── Complete fill → status=complete, update holdings, release/charge margin
```

#### 7.1.2 FIX Protocol Integration

The **Financial Information eXchange (FIX)** protocol is the industry standard for order routing.

```
FIX message types used:

NewOrderSingle (D)     → Place a new order
OrderCancelRequest (F) → Cancel an existing order
OrderCancelReplaceRequest (G) → Modify order (price/qty)
ExecutionReport (8)    ← Exchange sends order status updates (ACK, fill, cancel)
OrderCancelReject (9)  ← Exchange rejects cancel/modify request
```

**FIX Session management:**
```
Session: persistent TCP connection to NSE/BSE (not HTTP).
Heartbeat: every 30s (HEARTBEAT message) to detect dead connections.
Sequence numbers: every FIX message has incrementing SeqNum.
  → Out-of-sequence message → request resend (ResendRequest).
  → This ensures NO message is lost even on TCP reconnect.
Gap fill:
  → If connection drops → reconnect → exchange sends missed ExecutionReports.
  → OMS records last processed SeqNum; requests all missed messages on reconnect.
```

**Why FIX over REST?** FIX is binary-efficiency optimized, low-latency, stateful, with built-in message ordering and gap-fill. REST is stateless and adds HTTP overhead unacceptable at microsecond latency requirements.

#### 7.1.3 Order State Machine

```
open ──→ pending (sent to exchange, awaiting ACK)
     ──→ complete (fully filled)
     ──→ rejected (exchange / RMS rejected)
     ──→ cancelled (user cancelled)
     
pending ──→ complete (all qty filled)
        ──→ open (partial fill; pending_qty > 0, accepting more fills)
        ──→ cancelled (exchange cancelled — e.g., market close)
        ──→ rejected (exchange rejected after initial ACK — e.g., circuit limit hit)
```

#### 7.1.4 GTT (Good Till Triggered) Orders

GTT orders are conditional orders that activate when LTP crosses a trigger price.

```
User places GTT: { symbol: RELIANCE, trigger_price: 2450, limit_price: 2445, qty: 10 }

GTT Monitoring Service (background):
  → Subscribes to market data feed for all symbols with active GTTs.
  → On each tick: check if ltp crosses trigger_price.
  → If triggered: place actual LIMIT order to exchange immediately.
  → TTL per GTT: 1 year (user-defined, up to 1 year per SEBI guidelines).

Storage:
  → Active GTTs: Redis sorted set keyed by (symbol, trigger_price) for fast lookup.
  → On tick for RELIANCE at ₹2450: ZRANGEBYSCORE gtt:BUY:RELIANCE 0 2450 → get triggered GTTs.
  → Atomic: check + trigger + mark-as-triggered (Redis Lua script or DB transaction).
```

### 7.2 Risk Management System (RMS) — Pre-Trade Checks

**Every order must pass RMS before reaching the exchange.** This is both a business requirement (prevent bad debt) and a regulatory requirement (SEBI).

```
Check 1: Fund / Margin Availability
  BUY order:
    Required margin = quantity × price × margin_rate  (margin_rate = 20% for equity delivery;
                                                        can be 100% for CNC — full cash)
    Check: user_funds.available_cash >= required_margin
    If CNC (delivery): block full order value.
    If MIS (intraday): block only margin (5x leverage → block 20% of value).
  SELL order:
    Verify user has sufficient holdings in portfolio (for CNC).
    Or open positions (for MIS/F&O).

Check 2: Symbol-Level Restrictions
  → Symbol circuit limits: if stock has hit 5% upper circuit → no more BUY orders.
  → SEBI-banned securities: certain penny stocks banned from trading.
  → Illiquid warning: if stock has low liquidity → warn user.

Check 3: Order-Level Validations
  → Quantity: minimum lot size (F&O: lot-based; equity: min 1 share).
  → Price: LIMIT price must be within allowed range (±20% of LTP for most stocks).
  → Order size: single order value > ₹5 Cr → additional verification.

Check 4: User-Level Risk Controls
  → Daily loss limit: if user P&L < -₹50,000 today → block further MIS orders.
  → Intraday square-off alert at 3:20 PM; auto square-off at 3:25 PM (broker policy).

Check 5: Regulatory Checks
  → SEBI insider trading: flag orders in restricted securities during quiet period.
  → PAN verification: trades linked to PAN for tax reporting.
```

**Margin blocking (atomic operation):**
```sql
UPDATE user_funds
SET available_cash = available_cash - :required_margin,
    used_margin    = used_margin + :required_margin,
    version        = version + 1
WHERE user_id = :userId
  AND available_cash >= :required_margin
  AND version = :expected_version;

-- If UPDATE affects 0 rows → insufficient funds → reject order.
-- Uses optimistic locking (version field) to prevent race conditions.
```

### 7.3 Market Data Service — The Highest Fan-Out Problem

**NSE broadcasts ~1 million price ticks/second across all symbols. PhonePe/Groww has 5 million concurrent WebSocket connections. Naïve fan-out = 5 × 10^12 operations/sec. Impossible.**

#### 7.3.1 Market Data Pipeline

```
NSE Multicast Feed (UDP multicast)
  ↓
[Market Data Receiver] (co-located at NSE data center for lowest latency)
  ↓ normalized tick (internal format)
[Tick Processor]
  ↓                              ↓
[Redis pub/sub]            [TimescaleDB]
(real-time stream)          (historical storage)
  ↓
[Market Data Gateway]
(aggregation layer)
  ↓                              ↓
[WebSocket Server Pool]    [REST API] (snapshot)
(5M connections)
  ↓
Clients
```

#### 7.3.2 Tick Aggregation (The Key Insight)

```
Problem: 200 ticks/sec for RELIANCE × 5M subscribers = 10^9 pushes/sec. Impossible.

Solution: Throttle to 1 update/second/symbol per subscriber.

Aggregation logic (per symbol, per 1-second window):
  → Collect all ticks in the window.
  → Compute: LTP = last tick, Open = first tick, High = max, Low = min, Close = last.
  → Push ONE consolidated update to all subscribers for that symbol.
  → Result: 5,000 symbols × 5M subscribers × 1/sec = 25 × 10^9 → still too much.

Further optimization: Only push to subscribers of that symbol.
  → RELIANCE might have 500K active subscribers (not 5M).
  → 500K × 5,000 symbols (avg 100K subscribers/symbol) = 500B → scale via:
    - Horizontal WebSocket server sharding.
    - Redis pub/sub for cross-server fan-out.
    - Symbol interest tracking per server.
```

#### 7.3.3 WebSocket Architecture

```
Client connects to [WebSocket Load Balancer] (sticky sessions by user_id hash).
Assigned to one of N WebSocket server pods (e.g., 500 pods × 10K connections each = 5M).

Subscription:
  Client sends: { "subscribe": ["RELIANCE", "TCS", "INFY"] }
  WebSocket server:
    → Records interest: symbol_subscribers[symbol].add(connection_id)
    → Subscribes to Redis pub/sub channel: market:RELIANCE, market:TCS

Market Data Gateway publishes to Redis pub/sub every 1 second:
  PUBLISH market:RELIANCE '{"ltp": 2501.50, "change": +5.5, ...}'

WebSocket server receives from Redis pub/sub:
  → Fans out to all local connections subscribed to RELIANCE.
  → Pushed directly over WebSocket frame (binary protocol, MessagePack encoded).

Cross-pod communication: entirely via Redis pub/sub (no direct pod-to-pod).
Scale: each Redis pub/sub channel handles the broadcast; WebSocket pods handle delivery.
```

#### 7.3.4 Market Depth (Level 2 Data)

Market depth (bid/ask order book) is high-frequency data — changes every millisecond.

```
NSE provides market depth via separate Multicast feed.
Depth data per symbol: top 5 bid + top 5 ask levels.
Update frequency: every 100ms (NSE's broadcast interval).

Storage: NOT stored in DB (too high volume). Stored:
  → In-memory in Market Data Service (hash map: symbol → depth struct).
  → Redis hash for cross-service access.
  → Clients get snapshot via REST; real-time via WebSocket (throttled to 500ms interval).
```

### 7.4 Portfolio Service

**Computes real-time P&L across all holdings and positions.**

#### 7.4.1 Holdings vs Positions

```
Holdings (CNC — Delivery):
  → Settled stocks in Demat account (available after T+1 settlement).
  → Owned long-term; P&L is unrealised until sold.
  → Updated daily after CDSL settlement (not real-time from OMS).

Positions (MIS — Intraday / NRML — F&O):
  → Open intraday positions; settled same day.
  → Must be squared off before market close (MIS) or held till expiry (NRML).
  → Real-time P&L = (ltp - avg_buy_price) × quantity.
```

#### 7.4.2 Real-Time P&L Computation

```
Approach: Event-driven computation, NOT polling.

On each price tick for RELIANCE:
  → Portfolio Service receives tick from Redis pub/sub.
  → Fetches all users holding RELIANCE (from holdings index).
  → For each user: unrealised_pnl = (ltp - avg_price) × quantity.
  → Pushes updated P&L to user's WebSocket connection.

Problem: 1M users hold RELIANCE. On every RELIANCE tick → 1M P&L computations.

Optimizations:
  1. Lazy computation: only compute for users who are actively watching their portfolio.
     → Track active portfolio WebSocket sessions.
     → Only push P&L updates for online users.
  2. Batch computation: compute P&L in micro-batches (50ms windows) not per-tick.
  3. Portfolio snapshot caching: store last portfolio state in Redis; send delta only.
```

#### 7.4.3 Avg Price Calculation

```
Weighted Average (WAP) for holdings:
  New avg = (old_avg × old_qty + fill_price × fill_qty) / (old_qty + fill_qty)

Example:
  Buy 10 @ ₹2500 → avg = ₹2500
  Buy 5 more @ ₹2450 → avg = (2500×10 + 2450×5) / 15 = ₹2483.33

This is computed transactionally on each fill event and stored in holdings table.
FIFO vs WAP: SEBI mandates FIFO for tax purposes; WAP for display.
System stores both: WAP for live display, FIFO for capital gains tax report.
```

### 7.5 Clearing And Settlement

**SEBI mandates T+1 settlement for equity (implemented India-wide since Jan 2023).**

```
T (Trade Day):
  Exchange matches buy and sell orders.
  Trade records sent to NSCCL (NSE Clearing) / BSE Clearing.
  Groww receives trade confirmations via FIX (ExecutionReport).

T+1 (Next Day):
  Clearing: NSCCL determines net obligations (each broker's net buy/sell per symbol).
  Settlement (Funds): Debit from buyer's broker; credit to seller's broker.
  Settlement (Securities): Transfer shares from seller's Demat → NSCCL → buyer's Demat.
  
Groww's settlement tasks on T+1:
  → Receive settlement file from NSCCL/CDSL.
  → Credit shares to buyer's holdings (holdings.quantity += bought_qty).
  → Charge transaction costs (brokerage, STT, exchange charges, stamp duty).
  → Credit proceeds to seller's fund balance.
  → Update fund_ledger with settlement entries.
```

### 7.6 Charges Calculation Engine

Each trade incurs multiple regulatory and broker charges:

```
For a BUY trade of 10 RELIANCE @ ₹2500 (Total value = ₹25,000):

STT (Securities Transaction Tax):        0.1% of trade value   = ₹25.00 (on delivery)
Exchange Transaction Charges (NSE):      0.00322% of turnover  = ₹0.805
SEBI Turnover Fee:                       0.0001% of turnover   = ₹0.025
Stamp Duty:                              0.015% of buy value   = ₹3.75
GST (on brokerage + exchange charges):   18%                   = computed
Brokerage (Groww):                       ₹0 for equity delivery (Groww's USP)
                                         ₹20/order for intraday/F&O

Total charges = ₹25 + ₹0.805 + ₹0.025 + ₹3.75 + GST = ~₹29.60

These are deducted from user's fund balance on settlement.
Computed by Charges Engine (rule-based, updated when SEBI changes rates).
```

---

## 8. End-To-End Critical Flows

### 8.1 Equity Limit Order: Buy 10 Shares of RELIANCE

```
1. User places order in Groww app.
2. API Gateway: Auth check (JWT). Route to OMS.
3. OMS:
   a. Idempotency check: idempotency_key exists in Redis/DB? Return existing order if yes.
   b. Symbol validation: RELIANCE is listed, not halted, not circuit-filtered.
   c. RMS pre-trade check:
      - Required margin = 10 × ₹2500 × 100% (CNC) = ₹25,000.
      - Check available_cash ≥ ₹25,000.
      - Atomic margin block: UPDATE user_funds SET available_cash -= 25000.
   d. INSERT order record (status=open).
   e. Send FIX NewOrderSingle to NSE OMS.
4. NSE OMS: Receives order → validates → assigns exchange_order_id.
   FIX ExecutionReport (ACK): { exchange_order_id, status=OPEN }
5. OMS: UPDATE order SET exchange_order_id=..., status=pending.
   Push status update to user via WebSocket.
6. NSE Matching Engine:
   Order sits in NSE's order book until a matching SELL order arrives.
   On match: FIX ExecutionReport (FILLED) sent to Groww's FIX connection.
7. OMS receives ExecutionReport:
   a. UPDATE order SET filled_qty=10, status=complete, avg_fill_price=2498.50.
   b. Charge actual margin (may differ slightly from estimated for MARKET orders).
   c. INSERT trade record.
   d. Emit order_filled event to Kafka.
8. Kafka consumer — Holdings updater:
   On T+1: UPDATE holdings SET quantity += 10, avg_price = new_weighted_avg.
9. Notification: push + SMS "Order executed: Bought 10 RELIANCE @ ₹2498.50".
```

### 8.2 Market Open Surge (9:15 AM)

```
At 9:15 AM: Market opens. All pre-market and overnight limit orders activate.
Surge: 100,000+ orders placed in first 30 seconds.
Challenge: 3,000+ orders/sec while maintaining < 50ms end-to-end.

Preparation:
  → Pre-scale OMS pods to 5× normal capacity starting 9:00 AM.
  → Warm Redis caches: user fund balances, margin rates, symbol info.
  → Pre-establish FIX sessions to all exchanges (TCP connections already up before 9:15).
  → Circuit breaker armed for exchange API (if exchange is slow, fail fast don't queue).

Order prioritization at 9:15:
  → After-market orders (placed overnight, queue since yesterday close) processed first.
  → Real-time order queue uses FIFO within same millisecond bucket.

RMS at peak:
  → Margin checks: Redis-backed fund balance lookups (< 1ms) + DB atomic update.
  → Symbol validation: fully cached (< 0.5ms).
  → FIX send: < 5ms to reach NSE.
  → Total OMS processing time at target: < 10ms.
```

### 8.3 Intraday Auto Square-Off (3:20 PM)

```
SEBI / Broker policy: All MIS (intraday) positions must be closed before market end.

3:20 PM: Auto square-off begins.
  → Portfolio Service: scan all open MIS positions.
  → For each position: place MARKET SELL order (or BUY for short positions).
  → Priority: submit these orders before regular cancellation at 3:25 PM.

At scale: 100K intraday users × avg 3 positions = 300K square-off orders in 5 minutes.
  → Dedicated square-off worker pool (separate from user order flow).
  → Orders batched and sent in parallel to exchange.

3:25 PM: Cancel all pending limit orders.
  → OMS: send FIX OrderCancelRequest for all open/pending orders.
  → Exchange cancels remaining unmatched orders at market close.
```

---

## 9. Consistency And Idempotency

### 9.1 Exact-Once Order Submission

```
Problem: If connection drops after OMS sends FIX order to exchange but before ACK —
         did the exchange receive it? Re-sending would create a DUPLICATE order.

FIX protocol solution:
  → Each FIX session maintains msg sequence numbers (MsgSeqNum).
  → On reconnect: exchange sends all ExecutionReports since last seen SeqNum (gap fill).
  → OMS NEVER re-sends a NewOrderSingle on reconnect — only checks for fill reports.
  → If no fill arrived after 5 seconds: send OrderStatusRequest to query current state.
  → Only create a new order if exchange confirms original order was NOT received.

This guarantees: At-most-once order submission to exchange.
User impact: Better "order not sent" (recoverable) than "order sent twice" (double buy).
```

### 9.2 Fund Balance Idempotency

```
Problem: OMS crashes after debiting margin but before creating order record.
  → User's funds charged but no order exists.

Solution: Distributed saga with compensating transaction.

Step 1: Block margin (DB UPDATE, optimistic lock) → record fund_ledger entry (type=margin_block, reference_id=pending_order_id).
Step 2: Create order record (status=open).
Step 3: Send to exchange.

On crash between Step 1 and Step 2:
  → Recovery job: scan fund_ledger for margin_block with reference_id pointing to no order.
  → Release margin: fund_ledger entry (type=margin_release).
  → Idempotent: check before releasing (prevent double-release).

On crash between Step 2 and Step 3:
  → Recovery job: scan orders with status=open AND no exchange_order_id AND age > 30s.
  → Retry sending to exchange (FIX NewOrderSingle with SAME order details).
  → Exchange deduplicates by ClOrdID (client order ID = order_id from Groww's DB).
```

---

## 10. Caching Strategy

```
Redis Cluster:

Symbol cache:
  → symbol_info:{symbol} → {name, isin, lot_size, tick_size, circuit_limits} TTL: 1 day
  → halted_symbols → SET of halted symbols (updated real-time from exchange feed)

Market data cache:
  → quote:{symbol} → {ltp, open, high, low, change, volume} TTL: 2s (refreshed by tick processor)
  → depth:{symbol} → {bids[], asks[]} TTL: 500ms

User session cache:
  → user_funds:{userId} → {available_cash, used_margin} TTL: 30s (write-through on update)
  → order_status:{orderId} → status TTL: 24h

RMS cache:
  → margin_rates:{symbol} → float TTL: 1 day (changes rarely)
  → circuit_filters:{symbol} → {upper_limit, lower_limit} TTL: refreshed on exchange event

GTT index:
  → gtt:BUY:{symbol} → Redis sorted set (score=trigger_price, member=gtt_id)
  → gtt:SELL:{symbol} → Redis sorted set (score=trigger_price, member=gtt_id)
  → On tick: ZRANGEBYSCORE to find triggered GTTs in O(log N + K)
```

---

## 11. Data Partitioning And Scaling

### 11.1 Order DB Sharding

```
Shard by user_id (hash-based):
  → All orders for a user on one shard → fast "my orders" queries.
  → No cross-shard joins for user-facing queries.

Cross-shard queries (e.g., all open orders for RELIANCE across all users — for risk monitoring):
  → Maintained in a separate risk_positions table (event-sourced, updated on each fill).
  → Separate read model optimized for symbol-level aggregation.

Order table size: 10M orders/day → 3.65B/year.
  → Archive orders older than 90 days to columnar store (Parquet on S3).
  → Hot table: 90 days, 900M rows → 5 shards × 180M rows each (manageable).
```

### 11.2 Market Data Scaling

```
WebSocket pod sizing:
  → 5M connections / 10K per pod = 500 pods.
  → Each pod: 4 vCPU, 8 GB RAM (mostly holding connection state).

Redis pub/sub sharding:
  → 5,000 symbols → 5,000 channels.
  → Shard channels across Redis cluster nodes (automatic with Redis Cluster).
  → Each channel's messages consumed by all pods subscribed to that symbol.

Tick processor:
  → 1 pod per exchange per segment (NSE equity, NSE F&O, BSE equity).
  → Reads from exchange multicast, normalizes, publishes to Redis.
  → Stateless; restart-safe (picks up latest tick on restart).
```

### 11.3 F&O (Futures & Options) Complexity

F&O adds significant complexity not present in equity:

```
Options Greeks computation (Delta, Gamma, Theta, Vega, Rho):
  → Computed using Black-Scholes model on every options tick.
  → Required for margin computation and risk display.
  → CPU-intensive: run as dedicated computation service.

Expiry handling:
  → Options expire every Thursday (weekly) and last Thursday of month (monthly).
  → On expiry: in-the-money options auto-exercised; out-of-the-money expire worthless.
  → OMS must auto-close positions before expiry if not physically settling.

Span Margin (SEBI requirement for F&O):
  → F&O margin computed using SPAN (Standard Portfolio Analysis of Risk) algorithm.
  → Not just per-position margin → considers portfolio-wide risk offsets.
  → Updated twice daily (pre-market and intraday) by NSE.
  → Groww must apply latest SPAN file for accurate margin computation.
```

---

## 12. Fraud Detection And Risk Controls

### 12.1 Pre-Trade Risk (Synchronous, < 5ms)

Already covered in RMS (Section 7.2). Key checks: funds, limits, symbol status.

### 12.2 Post-Trade Risk Monitoring (Asynchronous)

```
Real-time P&L monitoring:
  → If user's unrealised loss > 80% of margin deposited → margin call alert.
  → If loss > 100% of margin → trigger auto square-off.
  → Prevents broker bad debt from client default.

Unusual order patterns (wash trading detection):
  → User placing matching BUY and SELL at same time → potential wash trade.
  → Flag to compliance team. SEBI prohibits wash trading.

Spoofing detection (F&O):
  → Large orders placed and immediately cancelled at circuit limits → spoofing.
  → Rate: if >80% of orders cancelled within 1 second → flag user account.
```

### 12.3 Account Security

```
Two-factor for large orders:
  → Orders > ₹5 Lakh → TOTP/biometric re-confirmation.
IP and device binding:
  → Login from new device/IP → OTP + email alert.
  → Prevent unauthorized order placement from compromised sessions.
UPI mandate for withdrawals:
  → Withdrawals only to pre-registered bank account (from KYC).
  → Changes to bank account → 24-hour cooling period (prevents fraudulent withdrawal).
```

---

## 13. Regulatory Compliance (SEBI)

```
1. Order audit trail (mandatory):
   Every order with: timestamp (microsecond), user_id, IP, device_id, order details.
   Immutable log retained for 5 years. SEBI can subpoena any order log.

2. KYC (SEBI / CDSL requirement):
   → PAN mandatory for trading.
   → Aadhaar-based in-person verification or video KYC for Demat account.
   → Annual KYC refresh for inactive accounts.

3. Contract Notes (mandatory per SEBI):
   → Daily email to all users who traded: detailed trade contract note.
   → Includes all trades, charges, taxes in prescribed SEBI format.
   → Must be sent before 24 hours of trade day end.

4. Tax Reporting:
   → LTCG (Long Term Capital Gains): shares held > 1 year taxed at 10% above ₹1L.
   → STCG (Short Term): 15% for delivery; normal slab for F&O (F&O = business income).
   → Groww provides annual Tax P&L statements downloadable as Schedule CG/ITR attachment.

5. Investor protection fund:
   → SEBI mandates brokers contribute to IPF.
   → Provides limited compensation to investors in case of broker default.

6. Settlement guarantee:
   → NSCCL (NSE Clearing) guarantees settlement even if counterparty broker defaults.
   → Groww pledges collateral with NSCCL = sum of all client margins held.
```

---

## 14. Observability And SLOs

| Metric | SLO | Alert Threshold |
|---|---|---|
| Order placement API latency P99 | < 100ms | > 200ms for 1 min |
| FIX order to exchange ACK | < 50ms | > 200ms |
| Market data feed freshness | < 500ms behind exchange | > 2s lag |
| Order fill notification latency | < 1s | > 5s |
| WebSocket connection success rate | 99.9% | < 99% for 5 min |
| RMS margin block time P99 | < 5ms | > 20ms |
| Auto square-off completion | 100% by 3:25 PM | Any miss = P0 |

**Business metrics:**
- Orders placed per minute (real-time dashboard — drops = system issue).
- Fill rate (unfilled orders → liquidity issue or RMS problem).
- Margin utilization (risk indicator).

---

## 15. Major Trade-Offs And Why

### 15.1 FIX Protocol vs REST for Exchange Connectivity
- FIX: persistent TCP, binary, stateful, sequence numbers, gap-fill. Low latency.
- REST: HTTP overhead, stateless, no built-in ordering.
- **Decision**: FIX protocol. Non-negotiable for exchange connectivity — industry standard.

### 15.2 In-Memory Market Data vs DB-persisted
- In-memory: ultra-low latency (< 1ms reads), no durability.
- DB-persisted every tick: too slow (200 ticks/sec × 5K symbols = 1M writes/sec to DB).
- **Decision**: In-memory for real-time; TimescaleDB for 1-min+ historical candles.

### 15.3 WebSocket vs SSE vs Polling for Market Data
- Polling: high latency (governed by poll interval), server load scales with client count.
- SSE: server push, simpler than WebSocket, works over HTTP/2. Read-only.
- WebSocket: bidirectional, ideal for subscribe/unsubscribe and push. More complex.
- **Decision**: WebSocket for market data and order updates (supports dynamic subscribe/unsubscribe).

### 15.4 Real-Time Portfolio P&L vs On-Demand
- Real-time: compute on every tick for every position → massive CPU.
- On-demand: compute when user opens portfolio page → low CPU, slight delay.
- **Decision**: Hybrid — compute in 1-second batches for active users; snapshots for inactive.

### 15.5 OMS Sync vs Async Order Processing
- Sync: user waits for exchange ACK (up to 5s). Simple. Good UX for confirmation.
- Async: ACK immediately, update via WebSocket. But user sees "submitted" not "confirmed".
- **Decision**: Return submitted status in < 100ms; WebSocket delivers exchange confirm/fill asynchronously.

---

## 16. Interview-Ready Deep Dive Talking Points

**"How do you handle 3,000 orders/sec at market open?"**
> Pre-scaled OMS pods from 9:00 AM. FIX TCP connections pre-established. RMS checks all Redis-backed (< 1ms). Order DB write is async (write to Kafka WAL first; DB write via consumer). Exchange is the actual bottleneck — NSE handles it natively. Groww's throughput is bounded by FIX session rate limit per PSP (negotiated with exchange).

**"How do you prevent double-order submission?"**
> Client-generated idempotency key with UNIQUE DB constraint. FIX ClOrdID maps one-to-one with order_id — exchange deduplicates. On FIX reconnect: gap-fill receives missed ExecutionReports rather than re-sending orders. OrderStatusRequest confirms exchange state before any retry.

**"How do you serve market data to 5 million concurrent users?"**
> Tick aggregation reduces 200 ticks/sec to 1 push/sec per symbol. Redis pub/sub broadcasts to WebSocket pods. WebSocket pods fan out only to subscribers of each symbol. Binary protocol (MessagePack) minimizes bandwidth. CDN for static; own infra for real-time.

**"What happens if the exchange connection drops mid-trading session?"**
> FIX gap-fill on reconnect: exchange sends all ExecutionReports since last SeqNum. Orders that were filled during outage resolved on reconnect. Status of unconfirmed orders queried via OrderStatusRequest. User sees orders in "pending" until resolved. Market data switches to cached/last-known values with staleness indicator.

---

## 17. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Scope: broker vs exchange, equity + F&O + MF, scale |
| 5–10 min | Capacity planning: TPS, WebSocket connections, market data volume |
| 10–18 min | API design + data model: order lifecycle, trades, holdings, funds |
| 18–28 min | OMS deep dive: order flow, FIX protocol, GTT, auto square-off |
| 28–35 min | Market data pipeline: tick processing, aggregation, WebSocket fan-out |
| 35–42 min | RMS, margin locks, idempotency, settlement, regulatory compliance |
| 42–45 min | Trade-offs, observability, extensions |

---

## 18. Extensions To Mention If Time Permits

- **Algo Trading / API Platform**: REST + WebSocket API for algorithmic traders. Rate-limited FIX access.
- **Mutual Fund Engine**: NAV-based pricing (not exchange-matched), cut-off time, SIP scheduling, AMFI integration.
- **Basket Orders**: Place orders in multiple stocks simultaneously (portfolio rebalancing in one click).
- **Smallcase Integration**: Thematic portfolio → buy/sell all constituent stocks at once.
- **Options Strategy Builder**: Visualize payoff diagram for Iron Condor, Strangle, etc. before placing.
- **Margin Pledge**: Pledge holdings as collateral for F&O margin without selling (CDSL pledge API).
- **ASBA Integration**: For IPO subscriptions — amount blocked in bank account until allotment.
- **Global Investing**: US ETFs and stocks via LRS (Liberalised Remittance Scheme) integration.
