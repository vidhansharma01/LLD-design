# High-Level Design: Flipkart E-Commerce Platform (SDE3 Interview)

## 0. How To Use This Document In Interview
This write-up is intentionally deep. In a real SDE3 interview, present in this order:
1. Clarify scope: which flows to design (catalog, cart, checkout, orders, search, recommendations).
2. Show API surface and data model.
3. Draw architecture and walk through each component.
4. Explain critical flows end-to-end (product search → add to cart → checkout → order fulfillment).
5. Cover scale, consistency trade-offs, reliability, and security.

If the interviewer interrupts for deep-dive, jump to the relevant section directly.

---

## 1. Problem Statement And Scope

Design India's largest e-commerce platform (Flipkart) that supports:
- Browse and search a catalog of hundreds of millions of SKUs.
- Product detail pages with ratings, reviews, variants (size, color), and seller offers.
- Cart, checkout, and payment (COD, UPI, cards, wallets, EMI).
- Order placement, tracking, and returns/refunds.
- Inventory management per seller per warehouse.
- Flash sales (Big Billion Days) with massive traffic spikes.
- Personalized recommendations and sponsored listings.
- Seller portal for listing management, pricing, and order fulfillment.

### 1.1 Explicitly In Scope
- Product Catalog, Search, and Browse.
- Cart and Checkout.
- Order lifecycle management.
- Inventory and fulfillment pipeline.
- Pricing engine and offers.
- Recommendations and ranking.
- Reliability, scalability, observability, and security.

### 1.2 Out Of Scope
- Payment gateway internal implementation.
- Logistics partner (Ekart) internal routing algorithms in depth.
- Ads platform auction mechanics in depth.
- Machine learning model training internals.

---

## 2. Requirements

### 2.1 Functional Requirements
1. User can browse categories and view product listing pages (PLP) and product detail pages (PDP).
2. User can search products with filters (brand, price, rating, delivery speed, discount).
3. User can add products to cart, update quantities, and remove items.
4. User can checkout: select address, delivery slot, payment method, and place order.
5. User can track order status end-to-end (packed → shipped → out for delivery → delivered).
6. User can cancel or return an order and request a refund.
7. Sellers can list new products, manage inventory, set prices, and view orders.
8. System supports flash sales: pre-registration, countdown timer, limited stock.
9. System applies coupons, bank offers, cashback, and dynamic pricing.
10. Personalized home feed, recommendations (frequently bought together, similar products).
11. User can write ratings and reviews; system surfaces aggregate ratings.

### 2.2 Non-Functional Requirements
- **Availability**: 99.99% for product browsing and order placement.
- **Latency**:
  - Search: P99 < 300 ms.
  - Product Detail Page load: P99 < 500 ms.
  - Cart operations: P99 < 200 ms.
  - Order placement: P99 < 2 s.
- **Scale**:
  - 500 million registered users; 50 million DAU during Big Billion Days.
  - 1.5 billion page views per day during peak.
  - Catalog: 500 million+ SKUs across 80+ categories.
  - Orders: 10 million+ per day peak.
- **Consistency**:
  - Strong consistency for inventory deduction and payment.
  - Eventual consistency for search index, recommendations, review counts.
- **Durability**: No order or payment record can ever be lost.
- **Security**: PCI-DSS compliance for payments; GDPR/IT Act for user data.

---

## 3. Back-Of-The-Envelope Capacity Planning

### Assumptions
| Parameter | Normal Day | Peak Day (BBD) |
|---|---|---|
| DAU | 10 million | 50 million |
| Page views/user/day | 30 | 50 |
| Search queries/user/day | 10 | 15 |
| Add-to-cart/user/day | 3 | 8 |
| Orders/user converting | 0.5 | 1.5 |

### 3.1 Request Volume
- **Page views**: 10M * 30 = 300M/day → **3,500 reads/sec average; 15K peak** (during BBD 50M * 5 min concentrated spike).
- **Search QPS**: 10M * 10 / 86400 = **1,160/sec average; 8,000/sec peak**.
- **Cart writes**: 10M * 3 / 86400 = **350/sec average; 5,000/sec peak**.
- **Order writes**: 10M * 0.5 / 86400 = **58/sec average; 5,000/sec peak during flash sale**.

### 3.2 Storage Estimates
- **Product catalog**: 500M SKUs * 5 KB metadata = **2.5 TB** metadata; **~500 TB** for images.
- **Orders**: 10M/day * 365 * 2KB = **~7 TB/year** order records.
- **User data**: 500M users * 2 KB profile = **1 TB** user records.
- **Inventory**: 500M SKUs * 10 bytes = **5 GB** in-memory hot inventory snapshot.
- **Search index**: 500M products * 2 KB index doc = **~1 TB** Elasticsearch index.

### 3.3 Flash Sale (Big Billion Days) Concurrency
- 10 million users trying to buy same item in 1 second → **10 million concurrent requests/sec at peak**.
- This requires pre-caching stock, queue-based order acceptance, and rate limiting — critical SDE3 topic.

---

## 4. High-Level API Design

### 4.1 Product Catalog APIs

```http
# List products in a category (PLP)
GET /v1/catalog/products?categoryId=mobiles&page=1&size=40
    &filters=brand:samsung,price:10000-30000,rating:4+
    &sort=popularity

# Search products
GET /v1/search?q=iphone+14&filters=...&sort=relevance&page=1

# Get product details (PDP)
GET /v1/products/{productId}
Response includes: title, description, images, variants, offers, seller listings,
                   specifications, ratings_summary, delivery_info

# Get product offers (multiple sellers + price)
GET /v1/products/{productId}/offers?pincode=560001
```

### 4.2 Cart APIs

```http
# Get user cart
GET /v1/cart/{userId}

# Add item to cart (idempotent: sending same item again updates quantity)
PUT /v1/cart/{userId}/items
{
  "productId": "PROD_123",
  "variantId": "VAR_456",
  "sellerId": "SELL_789",
  "quantity": 2
}

# Remove item
DELETE /v1/cart/{userId}/items/{cartItemId}

# Apply coupon
POST /v1/cart/{userId}/coupons
{
  "couponCode": "FLAT200"
}

# Get cart price summary (with all offers applied)
GET /v1/cart/{userId}/pricing
```

### 4.3 Checkout APIs

```http
# Initiate checkout session
POST /v1/checkout/sessions
{
  "userId": "USR_123",
  "cartId": "CART_456",
  "addressId": "ADDR_789",
  "deliverySlot": "standard" | "express" | "scheduled"
}
→ Returns: checkoutSessionId, order_summary, payment_options, price_breakdown

# Place order
POST /v1/orders
{
  "checkoutSessionId": "sess_abc",
  "paymentMethod": "UPI",
  "upiId": "user@okaxis"
}
→ Returns: orderId, paymentUrl OR paymentStatus for COD
```

### 4.4 Order Management APIs

```http
# Get order details
GET /v1/orders/{orderId}

# List user orders
GET /v1/users/{userId}/orders?status=delivered&page=1

# Cancel order
POST /v1/orders/{orderId}/cancel
{
  "reason": "Changed my mind"
}

# Initiate return
POST /v1/orders/{orderId}/items/{orderItemId}/return
{
  "reason": "Defective product",
  "returnType": "refund" | "replacement"
}

# Track order
GET /v1/orders/{orderId}/tracking
→ Returns: status timeline, current location, estimated delivery
```

### 4.5 Inventory APIs (Internal / Seller Facing)

```http
# Reserve inventory (internal, called during checkout)
POST /v1/inventory/reserve
{
  "skuId": "SKU_ABC",
  "warehouseId": "WH_BLR1",
  "quantity": 1,
  "reservationTtlSeconds": 600
}

# Confirm deduction (internal, called on payment success)
POST /v1/inventory/confirm/{reservationId}

# Release reservation (on payment failure or cancellation)
POST /v1/inventory/release/{reservationId}

# Seller updates stock
PUT /v1/seller/{sellerId}/inventory/{skuId}
{
  "warehouseId": "WH_BLR1",
  "quantityDelta": +100
}
```

### 4.6 Notification APIs

```http
# Webhook / Push on order status change
POST /v1/notifications/order-update
{
  "orderId": "ORD_XYZ",
  "userId": "USR_123",
  "status": "shipped",
  "message": "Your order is on its way!"
}
```

---

## 5. Data Model (Core Entities)

### 5.1 Product Catalog

```sql
-- Core product (shared attributes for a product listing)
CREATE TABLE products (
  product_id      VARCHAR(64)  PRIMARY KEY,
  title           VARCHAR(512) NOT NULL,
  brand           VARCHAR(128),
  category_id     VARCHAR(64) NOT NULL,
  description     TEXT,
  specifications  JSONB,           -- flexible key-value attributes
  avg_rating      DECIMAL(2,1),    -- denormalized for fast PLP rendering
  review_count    INT,
  status          ENUM('active','inactive','deleted'),
  created_at      TIMESTAMP NOT NULL,
  updated_at      TIMESTAMP NOT NULL,
  INDEX (category_id, avg_rating),
  INDEX (brand, category_id)
);

-- Product variants (size/color/storage combinations)
CREATE TABLE product_variants (
  variant_id      VARCHAR(64) PRIMARY KEY,
  product_id      VARCHAR(64) NOT NULL REFERENCES products(product_id),
  sku             VARCHAR(128) UNIQUE NOT NULL,
  attributes      JSONB,           -- {color: Red, size: XL, storage: 128GB}
  images          JSONB,           -- ordered list of image URLs
  weight_grams    INT,
  dimensions      JSONB,
  INDEX (product_id)
);

-- Seller offers on a variant (price, quantity, seller)
CREATE TABLE product_listings (
  listing_id      VARCHAR(64) PRIMARY KEY,
  variant_id      VARCHAR(64) NOT NULL,
  seller_id       VARCHAR(64) NOT NULL,
  mrp             DECIMAL(10,2) NOT NULL,
  selling_price   DECIMAL(10,2) NOT NULL,
  available_qty   INT NOT NULL DEFAULT 0,    -- denormalized for fast read
  fulfillment_type ENUM('flipkart_assured', 'seller_fulfilled'),
  warehouse_id    VARCHAR(64),
  is_featured     BOOLEAN DEFAULT FALSE,    -- highlighted/buy-box position
  updated_at      TIMESTAMP NOT NULL,
  INDEX (variant_id, selling_price),
  INDEX (seller_id)
);
```

**Why JSONB for specifications?**  
Product attributes are highly heterogeneous across categories (mobiles have RAM/storage; shoes have size/material). JSONB avoids hundreds of nullable columns while supporting indexed queries on specific attributes.

### 5.2 User Cart

```sql
-- Cart header per user (one active cart per user)
CREATE TABLE carts (
  cart_id         VARCHAR(64) PRIMARY KEY,
  user_id         VARCHAR(64) UNIQUE NOT NULL,   -- one active cart per user
  coupon_code     VARCHAR(64),
  created_at      TIMESTAMP NOT NULL,
  updated_at      TIMESTAMP NOT NULL
);

-- Individual items in cart
CREATE TABLE cart_items (
  cart_item_id    VARCHAR(64) PRIMARY KEY,
  cart_id         VARCHAR(64) NOT NULL,
  listing_id      VARCHAR(64) NOT NULL,          -- seller specific listing
  variant_id      VARCHAR(64) NOT NULL,
  quantity        INT NOT NULL DEFAULT 1,
  unit_price      DECIMAL(10,2) NOT NULL,        -- price snapshot at add time
  added_at        TIMESTAMP NOT NULL,
  INDEX (cart_id)
);
```

**Why snapshot price at add-to-cart?**  
Price snapshotting prevents sticker shock. The actual order placement re-validates and may differ — a UX decision — but most platforms (including Flipkart) re-check at checkout and warn if price has changed.

### 5.3 Order Management

```sql
-- Master order record
CREATE TABLE orders (
  order_id        VARCHAR(64) PRIMARY KEY,
  user_id         VARCHAR(64) NOT NULL,
  status          ENUM('pending_payment','confirmed','processing',
                       'packed','shipped','out_for_delivery',
                       'delivered','cancelled','return_initiated',
                       'refunded') NOT NULL,
  total_mrp       DECIMAL(10,2) NOT NULL,
  total_discount  DECIMAL(10,2) NOT NULL DEFAULT 0,
  delivery_charge DECIMAL(10,2) NOT NULL DEFAULT 0,
  total_payable   DECIMAL(10,2) NOT NULL,
  address_id      VARCHAR(64) NOT NULL,
  address_snapshot JSONB NOT NULL,    -- denormalized at order time
  payment_method  VARCHAR(64),
  payment_id      VARCHAR(128),       -- external payment gateway reference
  estimated_delivery DATE,
  created_at      TIMESTAMP NOT NULL,
  updated_at      TIMESTAMP NOT NULL,
  INDEX (user_id, created_at DESC),
  INDEX (status, updated_at)
);

-- Individual items within an order (each item tracked independently for returns)
CREATE TABLE order_items (
  order_item_id   VARCHAR(64) PRIMARY KEY,
  order_id        VARCHAR(64) NOT NULL,
  listing_id      VARCHAR(64) NOT NULL,
  variant_id      VARCHAR(64) NOT NULL,
  seller_id       VARCHAR(64) NOT NULL,
  product_title   VARCHAR(512) NOT NULL,   -- snapshot
  sku             VARCHAR(128) NOT NULL,
  quantity        INT NOT NULL,
  unit_price      DECIMAL(10,2) NOT NULL,
  total_price     DECIMAL(10,2) NOT NULL,
  item_status     ENUM('confirmed','packed','shipped','delivered',
                       'cancelled','return_initiated','refunded'),
  warehouse_id    VARCHAR(64),
  tracking_id     VARCHAR(128),
  INDEX (order_id),
  INDEX (seller_id, item_status)
);

-- Order status timeline (immutable audit trail)
CREATE TABLE order_events (
  event_id        VARCHAR(64) PRIMARY KEY,
  order_id        VARCHAR(64) NOT NULL,
  order_item_id   VARCHAR(64),
  event_type      VARCHAR(64) NOT NULL,    -- PLACED, CONFIRMED, SHIPPED, etc.
  event_data      JSONB,
  actor           VARCHAR(128),           -- system/seller/logistics
  occurred_at     TIMESTAMP NOT NULL,
  INDEX (order_id, occurred_at)
);
```

**Why snapshot `address_snapshot` and `product_title` at order time?**  
The user might later update their address or the seller might rename the product. Order records must be immutable representations of what was ordered — a legal and UX requirement.

### 5.4 Inventory

```sql
-- Real-time inventory per SKU per warehouse
CREATE TABLE inventory (
  sku             VARCHAR(128) NOT NULL,
  warehouse_id    VARCHAR(64) NOT NULL,
  total_qty       INT NOT NULL DEFAULT 0,
  reserved_qty    INT NOT NULL DEFAULT 0,        -- held by in-flight orders
  available_qty   INT GENERATED ALWAYS AS (total_qty - reserved_qty) STORED,
  version         BIGINT NOT NULL DEFAULT 0,     -- optimistic locking
  updated_at      TIMESTAMP NOT NULL,
  PRIMARY KEY (sku, warehouse_id)
);

-- Reservation record (created during checkout, confirmed or released)
CREATE TABLE inventory_reservations (
  reservation_id  VARCHAR(64) PRIMARY KEY,
  sku             VARCHAR(128) NOT NULL,
  warehouse_id    VARCHAR(64) NOT NULL,
  order_id        VARCHAR(64),
  quantity        INT NOT NULL,
  status          ENUM('pending','confirmed','released','expired'),
  expires_at      TIMESTAMP NOT NULL,             -- TTL for pre-payment reservations
  created_at      TIMESTAMP NOT NULL,
  INDEX (sku, warehouse_id, status),
  INDEX (expires_at, status)
);
```

### 5.5 Pricing And Offers

```sql
-- Coupon definitions
CREATE TABLE coupons (
  coupon_code     VARCHAR(64) PRIMARY KEY,
  description     VARCHAR(256),
  discount_type   ENUM('flat','percent','free_delivery'),
  discount_value  DECIMAL(10,2),
  max_discount    DECIMAL(10,2),
  min_order_value DECIMAL(10,2),
  usage_limit     INT,
  used_count      INT DEFAULT 0,
  valid_from      TIMESTAMP NOT NULL,
  valid_until     TIMESTAMP NOT NULL,
  applicable_to   JSONB        -- {categories: [...], brands: [...], users: [...]}
);

-- Flash sale configuration
CREATE TABLE flash_sales (
  sale_id         VARCHAR(64) PRIMARY KEY,
  product_id      VARCHAR(64) NOT NULL,
  variant_id      VARCHAR(64),
  sale_price      DECIMAL(10,2) NOT NULL,
  total_inventory INT NOT NULL,
  sold_count      INT NOT NULL DEFAULT 0,
  start_time      TIMESTAMP NOT NULL,
  end_time        TIMESTAMP NOT NULL,
  status          ENUM('upcoming','active','ended')
);
```

---

## 6. High-Level Architecture

```text
+------------------------- Clients --------------------------+
| Mobile App (Android/iOS) | Web Browser | Seller Dashboard |
+----------+---------+-----+-------------------------------+--+
           |         |                     |
   [Global CDN]  [API Gateway]        [Seller API GW]
   (Static assets, (Auth, Rate         (Seller portals,
    Images, PDFs)  Limiting, Routing)   Inventory updates)
           |         |                     |
           v         v                     v
   +-----------------+-------------------+-------+
   |                 |                   |       |
   v                 v                   v       v
+--------+    +-----------+   +----------+  +---------+
|Product |    |Search     |   |Cart      |  |Seller   |
|Catalog |    |Service    |   |Service   |  |Service  |
|Service |    |(Elastic-  |   |          |  |         |
|        |    | search)   |   +----+-----+  +----+----+
+---+----+    +-----+-----+        |             |
    |               |              |             |
    v               v              v             v
+-------+      +--------+    +----------+   +----------+
|Product|      |Index   |    |Checkout  |   |Inventory |
|DB     |      |Sync    |    |Service   |   |Service   |
|(MySQL |      |Service |    |          |   |          |
| /PG)  |      |        |    +----+-----+   +----+-----+
+---+---+      +--------+         |              |
    |                             |              |
    v                             v              v
+--------+                  +----------+    +---------+
|Redis   |                  |Order     |    |Inventory|
|Cache   |                  |Service   |    |DB       |
|        |                  |          |    |         |
+--------+                  +----+-----+    +---------+
                                 |
              +------------------+------------------+
              |                  |                  |
              v                  v                  v
       +-----------+     +-------------+    +-------------+
       |Payment    |     |Fulfillment  |    |Notification |
       |Service    |     |Service      |    |Service      |
       | (Gateway  |     |(Warehouse / |    |(Email/SMS/  |
       |  Adapter) |     | Logistics)  |    | Push)       |
       +-----------+     +------+------+    +------+------+
                                |                  |
                                v                  v
                         [Kafka Event Bus]   [Kafka Event Bus]
```

### 6.1 Key Architectural Decisions
- **Microservices**: Each domain (Catalog, Cart, Order, Inventory, Payment, Fulfillment) is an independent service — allows independent scaling.
- **Event-driven**: Kafka decouples critical services. Order placement emits an event; payment, inventory, fulfillment, and notifications all react asynchronously.
- **CDN for catalog**: Product images, static category pages, and search suggestions cached at CDN edges (especially critical during BBD).
- **Read replicas + cache**: Heavy read traffic on product catalog served from Redis cache and read replicas, shielding primary DB.

---

## 7. Detailed Component Design

### 7.1 API Gateway

**Responsibilities:**
- **Authentication**: Validates JWT tokens (or session tokens from auth service).
- **Authorization pre-check**: Coarse-grained check (is user active? is token valid?).
- **Rate limiting**: Per-user, per-IP, and global rate limits. Critical during flash sales.
- **Request routing**: Routes to appropriate microservice.
- **SSL termination**, request tracing, and logging.

**Rate limiting strategy during flash sales:**
- Global rate limiter using Redis + token bucket or sliding window algorithm.
- Flash sale endpoints have stricter per-user limits (e.g., max 5 requests/sec/user).
- Pre-register interested users → only registered users allowed past gateway during sale window.

**SDE3 note**: During the 2021 Big Billion Days, Flipkart processed peak traffic of millions of concurrent users. The API Gateway's rate limiter is the first line of defense against DDOS and traffic spikes.

### 7.2 Product Catalog Service

**Responsibilities:** Manages the product taxonomy, product metadata, variant data, and seller listing data.

**Read path optimization (critical for PLP/PDP latency):**
```
Request: GET /products/{productId}
  L1: Redis cache (TTL: 10 min)
    → Cache hit: return immediately (< 5ms)
    → Cache miss: fall through
  L2: Read replica MySQL/PostgreSQL
    → Fetch product + variants + active listings
    → Enrich with pricing engine call (async)
    → Populate cache
    → Return (< 50ms)
```

**Write path (seller updates):**
```
Seller updates price/stock via Seller API
  → Write to primary DB (strong consistency for source of truth)
  → Invalidate Redis cache entry
  → Emit product_updated event to Kafka
    → Search Indexer: update Elasticsearch
    → CDN Invalidation: purge cached PDP pages
```

**Category tree management:**
- 80+ categories with 4-5 levels of nesting.
- Stored as adjacency list in DB + materialized full path for efficient breadcrumb navigation.
- Category tree is small (~10K nodes) → fits entirely in Redis.

**Variant explosion problem:**
- A single t-shirt with 5 colors × 5 sizes = 25 variants.
- Product-level metadata shared; only variant-specific attributes differ.
- Images stored per-variant with inheritance from parent product.

### 7.3 Search Service (Deep Dive)

Search is one of the highest-traffic and most complexity-dense components.

**Technology**: Elasticsearch (or OpenSearch) cluster.

**Index structure per document:**
```json
{
  "product_id": "PROD_123",
  "title": "Samsung Galaxy S24 Ultra 256GB Black",
  "brand": "Samsung",
  "category_path": ["Electronics", "Mobiles", "Smartphones"],
  "category_id": "CAT_MOBILES",
  "min_price": 89999,
  "max_price": 89999,
  "avg_rating": 4.5,
  "review_count": 12000,
  "discount_percent": 10,
  "delivery_estimate_days": 2,
  "is_flipkart_assured": true,
  "specifications": {
    "ram": "12GB",
    "storage": "256GB",
    "camera_mp": "200",
    "battery_mah": "5000"
  },
  "seller_count": 3,
  "catalog_score": 0.92,
  "popularity_score": 0.88,
  "image_url": "https://..."
}
```

**Query pipeline:**
```
1. Query parsing:
   - Tokenize and normalize (lowercase, stemming, transliteration for Hindi queries).
   - Expand abbreviations (s24 → Samsung Galaxy S24).
   - Intent classification (navigational vs exploratory vs transactional).

2. Candidate retrieval:
   - Full-text search on title, brand, category.
   - Filtered by category, brand, price range, rating from user-applied filters.
   - Elasticsearch `bool` query combining `must`, `filter`, `should` clauses.

3. Ranking (scoring):
   Score = w1 * text_relevance + w2 * popularity_score
         + w3 * personalization_score + w4 * catalog_quality_score
         - w5 * (1 / availability_flag)

4. Post-processing:
   - Facet aggregation (brand counts, price range histogram, rating distribution).
   - Sponsored product injection (ads auction result merged in).
   - Diversity constraint (not all results from one seller/brand).
```

**Autocomplete/typeahead:**
- Prefix index using Elasticsearch `completion` suggester.
- Top queries maintained in Redis sorted set (score = query frequency in last 7 days).
- Serving latency < 50ms.

**Search index update pipeline:**
```
Product change event (Kafka)
  → Index Sync Service (Kafka consumer)
  → Elasticsearch partial document update
  → CDN cache purge for cached search result pages
```
Update lag target: < 60 seconds.

**Sharding strategy:**
- Shard by `category_id` bucket (e.g., Electronics, Fashion, Home) for hot partition isolation.
- Each shard has 2 replicas for read throughput.
- Total index size ~1 TB → 10 shards × 100 GB each.

### 7.4 Cart Service

**Design principles:**
- Cart state must be **highly available** — a user loses trust if their cart disappears.
- Cart reads are far more frequent than writes.
- Consistency requirement: eventual (last write wins per item).

**Storage:**
- Primary store: Redis hash per user (`cart:{userId}` → hash of `itemId → item JSON`).
- Persistence backup: MySQL for cart recovery after Redis eviction.
- On Redis cache miss → load from MySQL → repopulate Redis.

**Cart operations:**
```
AddItem(userId, listingId, qty):
  1. Validate listing exists and is active (call Catalog, cached).
  2. Check per-user item limit (max 50 items/cart).
  3. HSET cart:{userId} {itemId} {listing snapshot + qty} in Redis.
  4. Async write-back to MySQL.

GetCart(userId):
  1. HGETALL cart:{userId} from Redis.
  2. Enrich with live prices from Catalog (price may have changed since add-to-cart).
  3. Show price-change banner if current price ≠ snapshot price.
  4. Run pricing engine: apply coupons, bank offers, delivery calc.

Merge cart on login:
  - Guest users have anonymous cart keyed by device_id.
  - On login, merge anonymous cart into user's saved cart.
  - Conflict resolution: keep higher quantity; alert user of duplicates.
```

**Wishlist vs saved-for-later:**
- Wishlist: NoSQL (DynamoDB or Cassandra), user-keyed, high write volume.
- Move-to-cart: atomic HSET to Redis + remove from wishlist.

### 7.5 Pricing Engine

**One of the most complex services in e-commerce.**

**Price components per order item:**
```
Final Price = MRP
            - Product Discount (seller-defined)
            - Coupon Discount (validated by coupon service)
            - Bank Offer Discount (e.g., 10% off on HDFC cards)
            - Cashback (credited post-delivery, not upfront)
            + Delivery Charge (0 if Flipkart Plus or order > ₹500)
            - Flipkart SuperCoins redemption
```

**Pricing rules engine:**
- Rules stored in a central DB, cached in Redis.
- Rules evaluated in priority order (seller discount < coupon < bank offer).
- Constraints: max discount cap per user per day; one coupon per order.
- Stackability: Flipkart-funded coupons can stack with bank offers; seller-funded coupons cannot.

**Dynamic pricing:**
- Base price set by seller.
- Platform can algorithmically adjust prices (similar to Amazon's repricing) based on:
  - competitor prices (price intelligence feed)
  - demand signal (search volume, page views)
  - inventory level
- Changes are logged as pricing events for auditability.

**Flash sale pricing:**
- Flash sale price overrides all other prices during sale window.
- Checked as highest priority rule in pricing engine.
- Flash sale price cached in Redis with TTL matching sale window.

### 7.6 Checkout Service (The Heart Of The Ordering System)

**Checkout is the most critical path — correctness must be guaranteed.**

**Checkout flow (step by step):**
```
Step 1: InitiateCheckout
  - Validate cart is non-empty.
  - Validate all items still available (availability check via Inventory Service).
  - Validate cart price (re-price with latest prices).
  - Validate coupon if applied.
  - Create checkout session (stored in Redis, TTL: 15 min).
  - Return price breakdown + available payment methods.

Step 2: SelectDelivery
  - User picks address from saved addresses.
  - Checkout service calls Fulfillment Service:
    → Determines nearest warehouse with stock.
    → Computes estimated delivery date (via logistic partner APIs).
    → Determines delivery eligibility for express/same-day.
  - Delivery charge computed (₹0 for Flipkart Plus; otherwise weight/distance based).

Step 3: ReserveInventory
  - For each cart item, call Inventory Service.
  - Create inventory reservation (TTL: 10 minutes, extended if user takes time).
  - If any item reservation fails → notify user, remove item from checkout.

Step 4: PlaceOrder
  - Validate checkout session is still valid.
  - Confirm all reservations are still active.
  - Initiate payment (call Payment Service).
  - On payment success (synchronous for UPI/card → redirect; async for netbanking):
    → Write order record to Order DB (atomic transaction).
    → Confirm inventory reservations (convert from reserved to deducted).
    → Emit order_placed event to Kafka.
    → Clear cart from Redis.
  - On payment failure:
    → Release inventory reservations.
    → Return payment failure to user.
```

**Idempotency is critical:**
- Each `PlaceOrder` call carries an `idempotencyKey` (client-generated UUID).
- Server stores `(idempotencyKey → orderId)` in Redis.
- Duplicate request (retry after network timeout) → returns existing orderId, does NOT create duplicate order.
- This prevents "double order" on the most common failure mode: client retries due to network drop.

**Checkout Session State Machine:**
```
INITIATED → DELIVERY_SELECTED → INVENTORY_RESERVED → PAYMENT_INITIATED → ORDER_PLACED
                                       ↓ (timeout)
                               INVENTORY_RELEASED → ABANDONED
```

### 7.7 Order Service

**Order service is the source of truth for all order state transitions.**

**Order lifecycle FSM:**
```
pending_payment
    ↓ (payment confirmed)
confirmed
    ↓ (warehouse picks items)
processing
    ↓ (items packed)
packed
    ↓ (handed to logistics)
shipped
    ↓ (last-mile delivery)
out_for_delivery
    ↓ (delivered to customer)
delivered
    
Any state before shipped → can be cancelled
delivered → return_initiated → refunded
```

**Event sourcing for order state:**
- Every state transition appended as an immutable event to `order_events` table.
- Current state derived by replaying events (or stored as snapshot for performance).
- Benefits: complete audit trail, ability to reconstruct any past state, debugging.

**Seller notification:**
- On `confirmed` event: Seller notified via email/app/API to prepare order.
- Seller marks `processing` → `packed` via Seller Portal / API.
- Ekart Logistics API called to create shipment and obtain tracking ID.

**Cancel & Refund flow:**
```
User cancels order (before shipped)
  → Order Service validates state (must be pre-shipped)
  → Update order status to "cancelled"
  → Emit order_cancelled event
  → Inventory Service: release or restore deducted stock
  → Payment Service: initiate refund to original payment method
  → Refund to: UPI/card in 5-7 days; wallet instant
```

### 7.8 Inventory Service (Deep Dive)

**Inventory correctness is sacred.** Overselling (selling more than in stock) is catastrophic for an e-commerce platform.

**Inventory state per (SKU, Warehouse):**
```
total_qty = physical stock in warehouse
reserved_qty = quantity held by in-flight checkouts (not yet paid)
available_qty = total_qty - reserved_qty    (what can be sold next)
```

**Reserve → Confirm → Release pattern:**
```
Checkout initiates:
  UPDATE inventory
  SET reserved_qty = reserved_qty + 1
  WHERE sku = ? AND warehouse_id = ?
    AND (total_qty - reserved_qty) >= 1   ← atomicity check
  RETURNING version                        ← optimistic lock version

If UPDATE affects 0 rows → out of stock, fail immediately.
Insert reservation record.

On payment success:
  UPDATE inventory SET total_qty = total_qty - 1, reserved_qty = reserved_qty - 1
  DELETE FROM inventory_reservations WHERE reservation_id = ?

On payment failure / timeout:
  UPDATE inventory SET reserved_qty = reserved_qty - 1
  UPDATE inventory_reservations SET status = 'released'
```

**Expiry sweeper:**  
A background job runs every minute scanning `inventory_reservations WHERE status = 'pending' AND expires_at < NOW()`.
Expired reservations release stock back to available pool. Critical to prevent "ghost reservations" locking stock forever.

**Flash sale specific inventory design:**
- Pre-load flash sale stock into Redis (`INCRBY flash_sale:{saleId}:remaining -1`).
- Redis atomic decrement is lock-free and handles 1M+ ops/sec.
- Successful Redis decrement → proceed to actual DB reservation.
- Failed decrement (stock = 0) → immediately reject without touching DB.
- Periodic sync from DB to Redis for freshness.

**Multi-warehouse routing:**
- When multiple warehouses have stock, Fulfillment Service picks closest to delivery pincode.
- Decision based on: delivery pincode → zone → nearest fulfillment center with stock.
- Reserved from the chosen warehouse for the session duration.

### 7.9 Payment Service

**Acts as an adapter** between Flipkart's order system and external payment gateways (Razorpay, PayU, Juspay, bank-direct UPI).

**Payment modes and flows:**

| Mode | Flow Type | Confirmation Mechanism |
|---|---|---|
| UPI (collect) | Synchronous (30s timeout) | Real-time NPCI callback |
| UPI (intent) | User switches to UPI app | Deeplink + callback webhook |
| Credit/Debit card | Redirect to PG / 3DS | Webhook + polling |
| Net banking | Redirect → bank portal | Webhook on return URL |
| Cash on Delivery | No payment now | Delivery agent collects |
| Flipkart Pay Later | Internal credit check | Instant |
| EMI | Sync with bank EMI API | Webhook |

**Payment state machine:**
```
INITIATED → PENDING_GATEWAY → SUCCESS / FAILURE / TIMEOUT
```

**Webhook handling (critical):**
- Payment gateways POST to `/v1/payments/webhook/{gatewayName}`.
- Webhook verified using HMAC signature from gateway.
- Idempotent processing: payment_id stored; duplicate webhooks are no-ops.
- Trigger: emit `payment_succeeded` or `payment_failed` to Kafka.

**Reconciliation:**
- Daily reconciliation job compares Flipkart DB with gateway settlement reports.
- Discrepancies trigger alerts and manual investigation.
- Handles: gateway credited but our webhook missed; our DB shows success but gateway failed.

### 7.10 Fulfillment Service

**Maps orders to warehouses, coordinates with logistics.**

**Warehouse selection algorithm:**
```
Given: order items (SKU, qty) + delivery pincode
For each item:
  1. Query inventory for warehouses with available stock.
  2. Filter by delivery pincode serviceability (city/zone mapping).
  3. Score warehouses: distance score + SLA score + stock level.
  4. Pick highest-scoring warehouse for reservation.

Split shipment:
  - If no single warehouse has all items, split into multiple shipments.
  - Notify user about split delivery and separate ETAs.
```

**Last-mile logistics integration:**
- Ekart (Flipkart's own) or third-party courier: Delhivery, BlueDart.
- API call to logistics partner: create shipment → receive AWB (Air Waybill) number.
- Tracking updates pushed from logistics partner webhook → Order Service event → Notification.

**Seller-fulfilled orders (Flipkart Marketplace):**
- Seller ships directly from their own stock.
- Flipkart sends order notification to seller.
- Seller updates tracking via Seller Portal or API.
- SLA breach (seller doesn't act in 48h) → auto-cancel with penalty.

### 7.11 Recommendation And Personalization Service

**Four major surfaces:**
1. **Home feed**: Personalized product tiles, category shortcuts, sale banners.
2. **"You may also like"**: Collaborative filtering on PDP.
3. **"Frequently bought together"**: Association rules mining.
4. **"Trending in your city"**: Geo-aware trending items.

**Architecture:**
```
Offline (batch, daily):
  User purchase + browse events → Spark job
  → User embedding vectors (collaborative filtering, matrix factorization)
  → Item embedding vectors (from product attributes + buy patterns)
  → Similarity indexes (ANN: FAISS or ScaNN)
  → Written to Feature Store

Nearline (stream, minutes):
  Clickstream events → Kafka → Flink stream processor
  → Update session-level user feature vector
  → Update trending item scores

Online (milliseconds):
  API call for recommendations
  → Fetch user embedding from Feature Store
  → ANN search for similar items
  → Apply business rules (in-stock, max price range, not already purchased)
  → Re-rank with ML model (LightGBM/XGBoost)
  → Return ranked list
```

**Cold start handling:**
- New user: show trending items in city + selected categories from onboarding quiz.
- New item: use content-based features (category, brand, description embedding) only.

### 7.12 Notification Service

**Channels**: SMS (Exotel / MSG91), Push (FCM / APNS), Email (Amazon SES), WhatsApp Business API.

**Event-driven fan-out:**
```
Kafka event (e.g., order_shipped)
  → Notification Service consumer
  → Template selection (order_shipped → "Your order is on the way!")
  → User preference check (has user opted out of SMS?)
  → Channel routing
  → Delivery to SMS/push/email provider
  → Delivery ACK tracking (sent, delivered, read)
```

**Delivery preferences table:**
- Users can opt out of marketing; never opt-out for transactional (order status = mandatory).
- Marketing notifications throttled (max 3/day/user) to prevent opt-out churn.

---

## 8. End-To-End Critical Flows

### 8.1 Big Billion Days Flash Sale Flow (Most Important For SDE3)

**Pre-sale (days before):**
```
1. Admin configures flash_sales record (product, price, inventory, start_time).
2. Flash sale inventory pre-loaded to Redis: SET flash:{saleId}:stock 10000
3. Users pre-register interest: SET flash:{saleId}:registered:{userId} 1 (Redis SET with TTL)
4. CDN pre-warms sale landing page and product images.
5. Capacity auto-scaling triggered for order + inventory services.
```

**During sale (T+0 spike):**
```
1. Countdown timer expires → "Buy Now" button unlocks (controlled by feature flag, not time-on-client).
2. 10M users click Buy Now simultaneously.
3. API Gateway rate-limiter:
   - Rejects non-registered users immediately (HTTP 429).
   - Throttles registered users to max 2 req/s/user.
   - Virtual queue: users beyond capacity threshold get "You're in queue, position 5,432" response.
4. Accepted users hit Inventory Service:
   - DECR flash:{saleId}:stock in Redis (atomic, lock-free, ~1M ops/s possible).
   - If DECR < 0: INCR back + return "OUT OF STOCK" (sold out handling).
   - On positive DECR: proceed to checkout session creation.
5. Order placement follows normal checkout flow.
```

**Virtual queue design:**
```
Redis sorted set: flash_queue:{saleId}
  ZADD flash_queue:{saleId} {timestamp} {userId}

Queue processor:
  - Every 100ms: ZPOPMIN flash_queue:{saleId} 100  (dequeue 100 users)
  - Issue checkout permission token to each dequeued user (JWT with TTL: 5 min)
  - User polls polling endpoint to receive their token
```

### 8.2 Normal Order Placement Flow

```
1. User adds item to cart (Redis HSET, < 5ms).
2. User navigates to checkout → GET /cart/{userId} (Redis HGETALL + price enrichment, < 50ms).
3. InitiateCheckout:
   a. Validate items (Catalog Service, cached).
   b. Create checkout session in Redis.
   c. Call Fulfillment for delivery options and ETA.
   Response: price breakdown + estimated delivery.
4. User selects address + delivery slot.
5. User selects payment method.
6. PlaceOrder:
   a. Inventory Service: reserve stock (DB UPDATE with availability check).
   b. Payment Service: initiate payment (redirect/collect).
7. Payment Gateway callback (webhook, within 30-120s):
   a. Verify HMAC signature.
   b. Order Service: BEGIN TRANSACTION
      - Write order record.
      - Write order_items.
      - Write PLACED event.
      - Emit order_placed to Kafka.
      COMMIT
   c. Inventory Service: confirm stock deduction.
   d. Cart Service: clear cart from Redis.
8. Kafka consumers react:
   a. Fulfillment Service: assign warehouse, notify seller or Ekart.
   b. Notification Service: send order confirmation SMS/email.
   c. Analytics: record order event.
9. Subsequent fulfillment events update order status and trigger notifications at each step.
```

### 8.3 Return And Refund Flow

```
1. User initiates return: POST /orders/{orderId}/items/{itemId}/return
2. Order Service:
   a. Validate return eligibility:
      - Within return window (10-30 days depending on category).
      - Item status is 'delivered'.
      - Category is returnable (certain categories like inner wear/software = no return).
   b. Create return record, update item_status to 'return_initiated'.
   c. Emit return_initiated event.
3. Return logistics scheduled:
   a. Ekart pickup assigned (or drop-off at store).
   b. Pickup date/time communicated to user.
4. Item received at warehouse:
   a. QC check: original packaging, item condition.
5. QC Approved:
   a. Refund initiated: Payment Service calls gateway refund API.
   b. Refund amount: full price of item + delivery charge (if applicable).
   c. Timeline: 5-7 days to bank; instant to Flipkart wallet.
6. QC Failed (damaged item returned):
   a. No refund. User notified with reason.
   b. Item returned to user.
```

---

## 9. Consistency And Transaction Model

### 9.1 Consistency Requirements By Domain

| Domain | Consistency Level | Why |
|---|---|---|
| Inventory deduction | Strong (ACID transaction) | Overselling is catastrophic |
| Payment status | Strong | Financial correctness required |
| Order placement | Strong (DB transaction) | Cannot have ghost orders |
| Cart state | Eventual (Redis, async persist) | Cart loss is frustrating but recoverable |
| Search index | Eventual (60s lag acceptable) | Freshness not critical for search |
| Review counts | Eventual | Approximate count fine |
| Recommendations | Eventual | Stale recs fine |

### 9.2 Distributed Transaction Challenge: Order + Inventory + Payment

**The classic distributed transaction problem:**  
Order record, inventory deduction, and payment all live in different DBs/services. How to ensure atomicity?

**Solution: SAGA pattern (Choreography-based)**

```
1. CheckoutService → ReserveInventory (local DB tx)
   On success → emit InventoryReserved event

2. PaymentService consumes InventoryReserved
   → Initiates payment
   On success → emit PaymentSucceeded event
   On failure → emit PaymentFailed event (triggers compensating transaction)

3. OrderService consumes PaymentSucceeded
   → Creates order record (local DB tx)
   → emit OrderCreated event

4. InventoryService consumes OrderCreated
   → Confirms reservation (deducts from total)

Compensating transactions:
  PaymentFailed → ReleaseInventory
  OrderCreationFailed → RefundPayment → ReleaseInventory
```

**Why SAGA over 2PC (Two-Phase Commit)?**
- 2PC requires a coordinator; if coordinator crashes → all participants blocked.
- SAGA: each service handles its own local transaction + compensating action.
- More resilient; failures are handled via events, not locks.

---

## 10. Caching Strategy

### 10.1 Cache Tiers

```
L1 — CDN (Akamai / CloudFront):
  → Product images, category landing pages, static assets.
  → TTL: 24 hours for images; 5 min for catalog pages (to allow price updates).
  → Cache key: URL + region.

L2 — Application cache (Redis Cluster):
  → Product metadata: {productId} → {title, price, rating, images}  TTL: 10 min
  → Category tree: full tree JSON  TTL: 1 hour (rarely changes)
  → Flash sale stock: Redis INCRBY/DECRBY (no TTL, managed explicitly)
  → Cart: {userId} → {item hash}  TTL: 30 days
  → Session: {sessionToken} → {userId, permissions}  TTL: 1 hour
  → Typeahead suggestions: {prefix} → [suggestions]  TTL: 5 min
  → Inventory availability (approximate): {skuId + warehouseId} → qty  TTL: 30s

L3 — DB read replicas:
  → Heavy analytical reads (seller dashboards, admin consoles) directed here.
  → Lag: < 5s acceptable.
```

### 10.2 Cache Invalidation

**Product price change (most frequent):**
```
Seller updates price
  → Write primary DB
  → DEL Redis key: product:{productId}
  → CDN purge: /v1/products/{productId} and /search cached responses
  → Elasticsearch partial update (async, < 60s)
```

**Flash sale cache coherence:**
```
Flash sale starts:
  SET flash:{saleId}:active 1 EX {saleWindowSeconds}
  SET flash:{saleId}:stock {totalStock}
Flash sale ends (TTL expires or stock = 0):
  All reads return stock=0 without touching DB
```

### 10.3 Thundering Herd During BBD

**Problem**: At 12:00 AM BBD start, 10M users load the home page simultaneously. Redis cache entries may not exist (cold start after deployment). 10M requests hit the DB simultaneously.

**Solutions:**
1. **Pre-warm cache**: Before BBD start, background job loads all featured products, banners, and sale items into Redis.
2. **probabilistic early recompute**: Cache entries are refreshed slightly before TTL expiry (using β-distribution stochastic approach).
3. **Mutex lock on cache miss**: Only one thread fetches from DB per cache key (Redis `SET NX` with short TTL lock). Others wait.
4. **Jittered TTL**: `TTL = baseTTL + random(0, baseTTL * 0.2)` to spread expiry across time.

---

## 11. Data Partitioning And Scaling

### 11.1 Product Catalog Sharding

- **By category**: Fashion, Electronics, Home → separate logical shards. Enables category-specific tuning.
- **By product_id range**: UUID-based random distribution across DB shards.
- Category tree + product metadata: ~2.5 TB → fits on 5 shards, 500 GB each.

### 11.2 Order DB Sharding

- Shard by `user_id` (range or hash).
- Ensures all orders for a user are in same shard → `LIST /users/{userId}/orders` is a single-shard query.
- Hash sharding avoids hotspots (sequential user IDs would concentrate new registrations on one shard).
- Cross-shard queries (e.g., seller sees all orders for their products) → fan out to all shards OR maintain a seller-keyed secondary index in separate table.

### 11.3 Inventory Scaling

- Inventory table sharded by `(sku, warehouse_id)` → single-shard atomicity per stock deduction.
- Flash sale hot item: one SKU on one shard → millions of requests → use Redis in front of DB.
- Redis handles atomic DECR; DB updated in async batch.

### 11.4 Kafka Partitioning

| Topic | Partition Key | Consumer |
|---|---|---|
| order_events | order_id | Fulfillment, Notification, Analytics |
| payment_events | payment_id | Order Service, Reconciliation |
| inventory_events | sku+warehouse | Inventory sync, Elasticsearch |
| user_activity | user_id | Recommendation, Analytics |

- Ordering guaranteed within a partition (same key → same partition).
- Scale consumers by adding consumer group members up to partition count.

---

## 12. Reliability And Disaster Recovery

### 12.1 Multi-Region Architecture

```
Region: Mumbai (Primary)     Region: Hyderabad (DR)       Region: Chennai (Read Replica)
+---------------------+      +---------------------+       +----------------------+
| Full stack active   |      | Full stack standby  |       | Read-only replica    |
| Primary DB          |      | DB replica (sync    |       | Product catalog reads|
| Kafka leader        |      |  or semi-sync)      |       | Recommendations      |
| All write APIs      |      | Kafka mirrors       |       |                      |
+---------------------+      +---------------------+       +----------------------+
```

- **Active-passive** for Order/Payment DBs (strong consistency requirement).
- **Active-active** for Product Catalog (read-heavy, eventual consistency acceptable).
- RTO (Recovery Time Objective): < 15 minutes for failure to serving.
- RPO (Recovery Point Objective): < 30 seconds for order data (replicated nearly synchronously).

### 12.2 Circuit Breaker Pattern

Every microservice call wrapped in a circuit breaker (Hystrix/Resilience4j):

```
State: CLOSED (normal) → on 50% error rate in 10s window → OPEN (fail fast)
OPEN → after 30s → HALF_OPEN (let 5% of requests through)
HALF_OPEN → on success → CLOSED; on failure → back to OPEN

Example: Pricing Service down
  → Circuit breaker opens
  → Checkout returns prices without bank-offer discount
  → User sees "Offers temporarily unavailable" banner
  → Degraded but functional checkout (does not block order placement)
```

### 12.3 Failure Mode Analysis

| Component Fails | Impact | Mitigation |
|---|---|---|
| Redis (Cart) | Cart data unavailable | Load from MySQL backup; show "Cart loading" |
| Elasticsearch | Search down | Fallback to DB category browse; cached trending results |
| Payment Gateway | Orders can't be placed | Failover to secondary gateway; offer COD |
| Kafka | Events queued | Local DB journal; async retry when Kafka recovers |
| Inventory Service | Can't check stock | Soft fail: allow order, validate post-payment; risk of oversell |
| Order DB shard failure | Orders for affected users fail | Reroute to hot-standby shard |
| Notification Service | No SMS/email | Order placed; notifications sent when service recovers |

### 12.4 Deployment Safety

- **Blue-green deployments**: Stand up new version in parallel, switch traffic atomically.
- **Canary releases**: 1% → 5% → 20% → 100% traffic migration with SLO monitoring.
- **Feature flags**: New checkout flow hidden behind flag; rolled out per user cohort.
- **Dark launch**: New Recommendation Service runs in parallel, results logged but not shown to user.

---

## 13. Security And Compliance

### 13.1 Authentication And Authorization

- **OAuth 2.0 + JWT**: All user-facing APIs. Tokens scoped to user_id with expiry.
- **Seller API**: OAuth 2.0 client credentials + API keys. Scoped to seller_id.
- **Internal services**: mTLS (mutual TLS) between microservices. No implicit trust inside VPC.
- **Admin access**: RBAC with just-in-time provisioning and mandatory MFA.

### 13.2 Payment Security (PCI-DSS)

- Card numbers NEVER stored by Flipkart — tokenized at payment gateway.
- Flipkart stores only: last 4 digits, card type, gateway token.
- PCI-DSS Level 1 certified infrastructure for payment flows.
- CVV never stored (regulation).
- 3D Secure (3DS 2.0) enforced for all card transactions > ₹5,000.

### 13.3 Fraud Prevention

**Order fraud signals:**
- Multiple orders to new addresses with same card.
- Spike in high-value orders from new accounts.
- Repeated failed payment attempts (card probing).
- Returns abuse (high return rate users).

**ML fraud signals pipeline:**
```
Order placement event → Real-time fraud scorer (< 100ms)
  → Feature vector: user_age, address_history, device_fingerprint,
                    order_value, payment_method, velocity_score
  → Fraud model (LightGBM) → risk_score (0.0 - 1.0)
  → Risk score > 0.8 → HOLD order for manual review
  → Risk score > 0.95 → AUTO_CANCEL and block account
```

### 13.4 Data Privacy (IT Act 2000, DPDP 2023)

- User PII (name, phone, address) encrypted at rest (AES-256).
- Masked in logs: phone → +91-98****9876.
- Right to deletion: user account delete → anonymize order history, delete PII.
- Data retention: orders retained for 7 years (tax compliance); PII stripped after 2 years.

---

## 14. Observability And SLOs

### 14.1 SLO Targets

| API | Availability SLO | Latency SLO (P99) |
|---|---|---|
| Product search | 99.9% | < 300ms |
| Product detail page | 99.99% | < 500ms |
| Add to cart | 99.9% | < 200ms |
| Checkout initiation | 99.99% | < 1s |
| Order placement | 99.99% | < 2s |
| Payment webhook handling | 99.999% | < 500ms |

### 14.2 Business Metrics (SDE3 Bonus)

Beyond golden signals (latency, traffic, errors, saturation), track:
- **Cart abandonment rate**: % of carts that don't convert to order → signals checkout friction.
- **Order cancellation rate**: High rate → inventory mismatch or payment issues.
- **Inventory accuracy**: `available_qty_DB / actual_physical_count` deviation.
- **Payment success rate**: Any drop → immediate PagerDuty to payments team.
- **Search zero-result rate**: % of queries with no results → signals catalog gap or query understanding issue.

### 14.3 Distributed Tracing

Every request carries a `X-Trace-Id` header. Propagated across all microservice calls.
Stored in Jaeger/Zipkin for root-cause analysis.

Example trace for a slow checkout:
```
checkout_service [200ms total]
  → catalog_service [45ms] (cache hit)
  → fulfillment_service [130ms] ← SLOW
      → logistics_api [120ms] ← external API bottleneck
  → pricing_service [20ms]
```

---

## 15. Major Trade-Offs And Why

### 15.1 Microservices vs Monolith
- Flipkart started as a monolith, migrated to microservices circa 2013-2015.
- Microservices: independent scaling, independent deployment, team ownership.
- Downsides: network latency, distributed debugging complexity, data consistency challenges.
- For a system at Flipkart scale (500M users, 50M DAU peak): microservices are necessary.

### 15.2 Redis DECR for Flash Sale vs DB-Only
- DB-only: 10M concurrent `UPDATE inventory WHERE available > 0` → DB deadlocks, queue buildup, milliseconds per op.
- Redis DECR: atomic, single-threaded per key, 1M+ ops/sec. Trades durability for speed.
- Mitigation: Redis persistence (AOF) + periodic async sync to DB.
- Decision: Redis as hot cache for flash sale stock; DB as source of truth for final deduction.

### 15.3 Strong Consistency for Inventory vs Availability
- Strong consistency (ACID in single DB): prevents overselling but becomes bottleneck for hot items.
- Optimistic concurrency (version field): allows parallel reads, detects conflicts at commit time. Preferred at scale.
- At extreme flash sale scale: Redis layer accepts orders, DB validates async → small risk of oversell → handled with compensation (cancel + refund excess orders).

### 15.4 SAGA vs 2PC for Distributed Transactions
- 2PC is blocking, coordinator is a single point of failure, poor resilience.
- SAGA with choreography (event-driven): resilient, no single coordinator, each service manages its own state.
- Downside: harder to reason about, compensating transactions must be idempotent.
- Decision: SAGA for order + payment + inventory flow.

### 15.5 Elasticsearch vs DB for Product Search
- DB LIKE query: full table scan, no ranking, no typo tolerance → unacceptable.
- Elasticsearch: inverted index, BM25 ranking, faceted search, multilingual → correct choice.
- Downside: eventual consistency (60s lag), operational complexity, memory intensive.
- Decision: Elasticsearch for search; DB is source of truth.

---

## 16. Interview-Ready Deep Dive Talking Points

**If interviewer asks "how do you handle 10 million users buying the same item in 1 second?":**
> Redis atomic DECR on flash sale stock. Users pre-registered (filtered at gateway). Virtual queue for overflow. Redis handles ~1M ops/sec per key. Accepted users proceed to checkout normally. DB only sees committed orders, not all attempts.

**If interviewer asks "how do you prevent double orders on payment timeout?":**
> Idempotency key (client-generated UUID) stored in Redis. Server checks key before processing. Duplicate request (retry) returns previously created order, no new order created.

**If interviewer asks "how do you ensure inventory is never oversold?":**
> Reserve-confirm-release pattern with DB-level availability check (`WHERE available_qty >= qty_requested`). DB UPDATE returns 0 rows if no stock → fail immediately. Reservations have TTL; expiry sweeper releases timed-out reservations. Flash sale uses Redis DECR as optimistic fast check before DB reservation.

**If interviewer asks "how does search stay fast?":**
> Elasticsearch with pre-built inverted index. Scoring blends relevance + personalization + popularity. Typeahead uses prefix completion suggester in ES + Redis for top prefixes. CDN caches common search result pages.

**If interviewer asks "what breaks first during BBD?":**
> 1. Inventory DB hot partition → mitigate with Redis. 2. Payment gateway rate limits → failover to secondary PG. 3. Elasticsearch query volume → pre-compute + cache common category searches. 4. Notification service fan-out lag → acceptable (transactional guaranteed, marketing degraded).

---

## 17. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Scope clarification: catalog scale, flash sale, payment modes, fulfillment |
| 5–10 min | Capacity planning: users, orders/day, QPS, storage estimates |
| 10–18 min | API design: search, cart, checkout, order placement |
| 18–28 min | Architecture diagram + component walkthrough |
| 28–36 min | Deep dive: flash sale flow OR checkout SAGA OR inventory reserve-confirm-release |
| 36–42 min | Caching strategy, consistency model, reliability |
| 42–45 min | Trade-offs, fraud prevention, extensions |

---

## 18. Extensions You Can Mention If Time Permits

- **Flipkart Plus loyalty program**: Points earning/redemption integrated with pricing engine.
- **Flipkart Pay Later**: Credit scoring microservice, credit limit management, EMI computation.
- **Supermart (grocery)**: 10-minute delivery requires dark store network, hyper-local inventory.
- **Live commerce**: Live stream shopping with in-stream "Buy Now" linked to flash inventory.
- **Seller analytics dashboard**: Real-time GMV, returns, ratings, impressions — built on Druid for OLAP.
- **Supply chain forecasting**: Demand prediction for pre-stocking warehouses before BBD.
- **Multi-language support**: Product descriptions, search in Hindi/Tamil/Telugu using multilingual NLP.
- **Voice search**: Integrating with Alexa/Google Assistant for voice-driven product search.
- **B2B marketplace (Flipkart Wholesale)**: Bulk ordering, credit terms, GST invoicing at scale.
