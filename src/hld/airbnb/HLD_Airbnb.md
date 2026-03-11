# High-Level Design: Airbnb (SDE3 Interview)

## 0. How To Present This In Interview
This document is intentionally detailed. In a real SDE3 interview, lead with:
1. Scope and assumptions.
2. Core data model and APIs.
3. Architecture and critical flows.
4. Consistency and double-booking prevention.
5. Scale, reliability, and trade-offs.

If interrupted, deep dive into the section the interviewer asks for.

---

## 1. Problem Statement
Design an Airbnb-like platform where:
- Guests can search listings by location/date/filter.
- Guests can book stays and pay online.
- Hosts can create listings, manage calendars, and receive payouts.
- Users can message each other.
- Reviews are supported after completed trips.
- Platform handles cancellations, refunds, and disputes.
- System supports very high read traffic (search) and correctness-critical writes (booking/payment).

---

## 2. Scope

## 2.1 In Scope
- Listing lifecycle: create/update/publish/unpublish.
- Search and ranking with availability and pricing filters.
- Booking with strong concurrency control.
- Payment authorization/capture and host payouts.
- Calendar sync and availability management.
- Messaging and notifications.
- Reviews and ratings.
- Trust and safety checkpoints (identity/risk/fraud hooks).

## 2.2 Out Of Scope
- Full legal policy engine by country/state.
- Tax filing/report generation internals.
- Advanced ML internals for pricing and ranking models.
- Customer support tool internals.

---

## 3. Requirements

## 3.1 Functional Requirements
1. Guest can search listings by city, map region, date range, guest count, and amenities.
2. Guest can see listing detail page with photos, amenities, house rules, rating, and total price.
3. Guest can reserve a listing for date range.
4. System must prevent double booking for the same listing/date.
5. Guest can cancel reservation; host can cancel with policy implications.
6. Platform supports different cancellation policies (flexible, moderate, strict).
7. Host receives payouts after check-in/check-out rules.
8. Guest and host can send messages before/during stay.
9. After trip completion, both parties can leave reviews.
10. Search and booking support multiple currencies and locales.

## 3.2 Non-Functional Requirements
- Availability:
  - Search/discovery: 99.95%
  - Booking and payments: 99.99%
- Latency:
  - Search API P99 < 300 ms
  - Listing detail P99 < 200 ms
  - Booking commit P99 < 1.5 s
- Consistency:
  - No double-booking (strong correctness for inventory writes).
  - Eventual consistency acceptable for search index freshness.
- Durability:
  - Reservation/payment events must be durable and auditable.
- Security and compliance:
  - PCI boundaries for card data.
  - PII protection and access auditing.

---

## 4. Assumptions And Capacity Planning

Use interview-friendly assumptions:
- MAU: 220 million
- DAU: 55 million
- Active listings: 12 million
- Daily searches: 450 million
- Daily booking attempts: 18 million
- Booking success rate: 12% (2.16 million confirmed bookings/day)
- Peak to average traffic multiplier: 3x

## 4.1 QPS Estimates
- Search average QPS: 450M / 86400 ~= 5208 QPS
- Search peak QPS: ~15.6K QPS
- Booking attempt average QPS: 18M / 86400 ~= 208 QPS
- Booking attempt peak QPS: ~624 QPS
- Confirmed bookings average QPS: 2.16M / 86400 ~= 25 QPS

Even though booking QPS is much lower than search, booking writes are strict and expensive due to locks, payment coordination, and idempotency.

## 4.2 Availability Data Volume
If day-level calendar rows are stored for 12M listings and 365 days:
- 12M * 365 = 4.38B listing-day records/year window
- Need partitioning + compression + archival strategy

## 4.3 Messaging Volume
Assume 70 million messages/day across active conversations.
- Average: ~810 msgs/sec
- Peak: 2K-3K msgs/sec

## 4.4 Storage (Order Of Magnitude)
- Listing metadata + profile + amenities: TB scale
- Photos/media: PB scale in object storage
- Reservation + payment ledgers: TB to 10s TB/year
- Event stream + analytics warehouse: 10s to 100s TB/day ingestion depending on telemetry richness

---

## 5. Core Domain Model

Primary entities:
- `User`: guest or host (or both).
- `Listing`: property with attributes, location, rules.
- `ListingCalendar`: per-date availability and constraints.
- `Reservation`: booking lifecycle record.
- `PaymentIntent`: authorization/capture state.
- `Payout`: host settlement record.
- `Conversation` and `Message`.
- `Review`: post-stay rating and text.

Important invariants:
1. A listing-day can be booked by at most one active reservation (for single-unit listings).
2. Reservation status transitions must be valid state-machine transitions.
3. Payment and reservation state must be reconcilable through immutable event logs.

---

## 6. API Design (Representative)

## 6.1 Search APIs
```http
GET /v1/search?lat=..&lng=..&checkIn=2026-07-10&checkOut=2026-07-14&guests=3&minPrice=100&maxPrice=350&amenities=wifi,kitchen&pageToken=...
```

```http
GET /v1/listings/{listingId}
```

```http
POST /v1/listings/{listingId}/quote
{
  "checkIn": "2026-07-10",
  "checkOut": "2026-07-14",
  "guests": 3,
  "currency": "USD"
}
```

## 6.2 Booking APIs
```http
POST /v1/reservations/hold
Idempotency-Key: a4e3...
{
  "listingId": "lst_123",
  "checkIn": "2026-07-10",
  "checkOut": "2026-07-14",
  "guestId": "usr_9",
  "quoteId": "q_abc"
}
```

```http
POST /v1/reservations/{holdId}/confirm
Idempotency-Key: b9c2...
{
  "paymentMethodId": "pm_22",
  "billingAddressId": "addr_7"
}
```

```http
POST /v1/reservations/{reservationId}/cancel
{
  "initiator": "GUEST",
  "reasonCode": "CHANGE_OF_PLANS"
}
```

## 6.3 Messaging APIs
```http
POST /v1/conversations/{conversationId}/messages
{
  "senderId": "usr_9",
  "body": "Can I check in late at night?"
}
```

## 6.4 Review APIs
```http
POST /v1/reservations/{reservationId}/reviews
{
  "overallRating": 5,
  "cleanlinessRating": 5,
  "comment": "Great location and very responsive host."
}
```

---

## 7. High-Level Architecture

```text
Clients (Web/iOS/Android)
        |
        v
   API Gateway
        |
        +------------------------------+
        |                              |
        v                              v
Identity/Auth                    Search Orchestrator
Service                          (query + rank + pricing preview)
        |                              |
        |                              +--> Search Index (OpenSearch/ES)
        |                              +--> Availability Cache
        |                              +--> Pricing Service
        |                              +--> Personalization Service
        |
        +--> Listing Service ----> Listing DB (RDBMS)
        |
        +--> Calendar Service ---> Calendar Store (NoSQL/RDBMS hybrid)
        |
        +--> Booking Service ----> Reservation DB
        |             |                |
        |             |                +--> Outbox/Event Bus
        |             |
        |             +--> Payment Service --> PSP/Card Processor
        |             +--> Risk/Fraud Service
        |
        +--> Payout Service -----> Ledger DB + Banking rails
        |
        +--> Messaging Service --> Conversation DB + WebSocket Gateway
        |
        +--> Review Service ----> Review DB
        |
        +--> Notification Service (Email/SMS/Push)
        |
        +--> Data Platform (Kafka/Pulsar -> Flink/Spark -> Warehouse/Feature Store)
```

Design principle:
- Separate read-heavy discovery path from correctness-critical booking path.
- Maintain a clear source of truth (reservation/calendar DB) independent of search index.

---

## 8. Detailed Component Design

## 8.1 Listing Service
Responsibilities:
- Host onboarding, listing creation, updates, publication.
- Validation: mandatory attributes, safety rules, geo normalization.
- Emits `ListingUpdated` events to indexing and analytics.

Data:
- Strongly consistent relational store for listing source-of-truth.
- Media metadata in DB, photos in object storage + CDN.

## 8.2 Search Orchestrator
Responsibilities:
- Parse query and filters.
- Fetch candidate listings from geo-text index.
- Filter by coarse availability.
- Enrich with pricing and ranking signals.
- Return paginated results.

Ranking factors (example):
- textual relevance
- map distance/proximity
- historical CTR and booking conversion
- rating and review volume
- host quality score
- price competitiveness
- personalization score

## 8.3 Availability/Calendar Service
Responsibilities:
- Manage nightly availability, min-stay, blocked dates, price overrides.
- Handle iCal sync and external channel updates.
- Provide booking-path strong checks.

Key idea:
- Search uses denormalized availability projection for speed.
- Booking uses source-of-truth calendar/reservation check with locking.

## 8.4 Quote And Pricing Service
Responsibilities:
- Compute final payable amount for date range.
- Applies:
  - nightly base price
  - seasonal/weekend multipliers
  - cleaning fee
  - guest service fee
  - host service fee
  - taxes
  - coupon/credits
  - currency conversion

Returns a signed `quoteId` with short TTL (for example 15 minutes).

## 8.5 Booking Service
Responsibilities:
- Create temporary hold.
- Confirm reservation after payment authorization.
- Manage status lifecycle and cancellation transitions.

Reservation state machine:
- `HOLD_CREATED`
- `PAYMENT_AUTHORIZED`
- `CONFIRMED`
- `CANCELLED_BY_GUEST`
- `CANCELLED_BY_HOST`
- `CHECKED_IN`
- `COMPLETED`
- `REFUND_PENDING`
- `REFUNDED`

Must be idempotent across retries.

## 8.6 Payment Service
Responsibilities:
- Create payment intent with PSP.
- Authorize at confirmation, capture based on policy.
- Refund full/partial based on cancellation policy.
- Emit immutable payment ledger events.

PCI strategy:
- Card vault/tokenization handled by PSP.
- Internal systems store payment tokens, never raw card PAN/CVV.

## 8.7 Payout Service
Responsibilities:
- Calculate host payout after platform fee and taxes.
- Delay/hold payouts for risk/dispute windows.
- Reconcile payout state with bank settlement feedback.

## 8.8 Messaging Service
Responsibilities:
- Create conversation thread per reservation or pre-booking inquiry.
- Persist messages and deliver realtime via WebSocket/push.
- Apply safety filtering and anti-spam controls.

## 8.9 Review Service
Responsibilities:
- Collect reviews only after eligible trip completion.
- Enforce review window.
- Support double-blind reveal (optional policy).

Double-blind option:
- Reveal reviews only when both parties submit or after review deadline expires.

## 8.10 Trust, Safety, And Fraud
Checks at:
- account creation
- listing publication
- booking attempt
- payment/payout

Signals:
- device fingerprint
- IP/geo anomalies
- card risk
- unusual booking velocity
- repetitive cancellation abuse

Risk output can:
- approve
- challenge (step-up verification)
- deny
- hold payout

---

## 9. Critical Flow: Search

1. Client sends search query with map/date/filter params.
2. Gateway authenticates optional user context and routes request.
3. Search Orchestrator queries geo index for candidate listing IDs.
4. Availability projection filters out obviously unavailable listings.
5. Pricing Service computes fast estimate (or pulls cached quote fragments).
6. Ranking model scores candidates.
7. Response returns listing cards + total price estimate + pagination token.

Optimization:
- Two-pass retrieval:
  - first pass cheap candidate generation (top 2000)
  - second pass expensive ranking for top 100-200

---

## 10. Critical Flow: Book Listing (Double-Booking Safe)

Flow uses hold-then-confirm pattern.

1. Guest clicks reserve.
2. Booking Service validates quote TTL, listing policy, user eligibility.
3. Create reservation hold with expiry (for example 10-15 minutes).
4. Acquire inventory for all nights in stay.
5. Call Risk Service.
6. Authorize payment.
7. If success, mark reservation `CONFIRMED` and commit calendar.
8. Emit events (`ReservationConfirmed`, `PaymentAuthorized`, `CalendarCommitted`).
9. Trigger notifications (guest confirmation, host alert).

If any step fails:
- release hold/inventory
- mark reservation failed/cancelled
- return deterministic error code

Idempotency:
- Same `Idempotency-Key` should return same reservation outcome even if client retries due to timeout.

---

## 11. Inventory Concurrency And Locking Strategy

Preventing double booking is the most important correctness problem.

## 11.1 Data Structure Option A: Listing-Day Rows
`inventory_nights` table:
- key: `(listing_id, date)`
- fields: status (`AVAILABLE`, `HELD`, `BOOKED`), hold_id, reservation_id, version

Booking acquires all nights in [checkIn, checkOut).
Use transaction + conditional updates (compare-and-swap by version/status).

Pros:
- Simple to reason.
- Strong per-night correctness.

Cons:
- Many rows for long stays and large inventory.

## 11.2 Data Structure Option B: Interval Trees
Store booked intervals and query overlap.

Pros:
- Less row explosion.

Cons:
- Overlap checks become complex at high concurrency.
- Harder to guarantee contention safety across distributed shards.

For interview clarity, Option A is preferred unless asked for hotel-style multi-unit optimization.

## 11.3 Hold Expiry
`reservation_holds` table with TTL expiration.
- Background sweeper releases stale holds.
- Release operation is idempotent.

## 11.4 Isolation And Transactions
- Keep lock scope minimal: only listing-night rows being reserved.
- Use serializable or strict conditional writes for those rows.
- Avoid distributed transaction across many services.
- Use saga pattern for cross-service steps (payment, notification).

---

## 12. Cancellation And Refund Flow

1. User/host initiates cancellation.
2. Booking Service validates actor permissions and current state.
3. Cancellation Policy Engine computes refund amount and penalties.
4. Payment Service triggers refund (full/partial/none).
5. Calendar nights released if applicable.
6. Payout adjustments generated if payout already created.
7. Notifications and receipts sent.

Cancellation policy evaluation factors:
- policy type
- cancellation timestamp relative to check-in
- local regulations
- force majeure/dispute status

---

## 13. Data Schema (Representative)

## 13.1 Listings
```sql
CREATE TABLE listings (
  listing_id            BIGINT PRIMARY KEY,
  host_id               BIGINT NOT NULL,
  title                 VARCHAR(256) NOT NULL,
  description           TEXT,
  country_code          CHAR(2) NOT NULL,
  city                  VARCHAR(128) NOT NULL,
  lat                   DECIMAL(9,6) NOT NULL,
  lng                   DECIMAL(9,6) NOT NULL,
  property_type         VARCHAR(64) NOT NULL,
  max_guests            INT NOT NULL,
  bedrooms              INT,
  bathrooms             DECIMAL(3,1),
  status                VARCHAR(32) NOT NULL,
  created_at            TIMESTAMP NOT NULL,
  updated_at            TIMESTAMP NOT NULL
);
```

## 13.2 Calendar
```sql
CREATE TABLE listing_calendar (
  listing_id            BIGINT NOT NULL,
  stay_date             DATE NOT NULL,
  status                VARCHAR(16) NOT NULL, -- AVAILABLE/HELD/BOOKED/BLOCKED
  hold_id               BIGINT,
  reservation_id        BIGINT,
  nightly_price_minor   BIGINT NOT NULL,
  min_nights            INT DEFAULT 1,
  version               BIGINT NOT NULL,
  updated_at            TIMESTAMP NOT NULL,
  PRIMARY KEY (listing_id, stay_date)
);
```

## 13.3 Reservations
```sql
CREATE TABLE reservations (
  reservation_id        BIGINT PRIMARY KEY,
  hold_id               BIGINT UNIQUE,
  listing_id            BIGINT NOT NULL,
  guest_id              BIGINT NOT NULL,
  host_id               BIGINT NOT NULL,
  check_in              DATE NOT NULL,
  check_out             DATE NOT NULL,
  status                VARCHAR(32) NOT NULL,
  total_minor           BIGINT NOT NULL,
  currency              CHAR(3) NOT NULL,
  policy_snapshot_json  JSONB NOT NULL,
  idempotency_key       VARCHAR(128) NOT NULL,
  created_at            TIMESTAMP NOT NULL,
  updated_at            TIMESTAMP NOT NULL
);
```

## 13.4 Payment Intents
```sql
CREATE TABLE payment_intents (
  payment_intent_id     BIGINT PRIMARY KEY,
  reservation_id        BIGINT NOT NULL,
  guest_id              BIGINT NOT NULL,
  amount_minor          BIGINT NOT NULL,
  currency              CHAR(3) NOT NULL,
  psp_reference         VARCHAR(128),
  status                VARCHAR(32) NOT NULL, -- CREATED/AUTHORIZED/CAPTURED/FAILED/REFUNDED
  created_at            TIMESTAMP NOT NULL,
  updated_at            TIMESTAMP NOT NULL
);
```

## 13.5 Reviews
```sql
CREATE TABLE reviews (
  review_id             BIGINT PRIMARY KEY,
  reservation_id        BIGINT NOT NULL UNIQUE,
  reviewer_id           BIGINT NOT NULL,
  reviewee_id           BIGINT NOT NULL,
  overall_rating        INT NOT NULL,
  cleanliness_rating    INT,
  communication_rating  INT,
  value_rating          INT,
  comment               TEXT,
  visibility_status     VARCHAR(16) NOT NULL, -- HIDDEN/REVEALED
  created_at            TIMESTAMP NOT NULL
);
```

---

## 14. Search Indexing Strategy

Source of truth:
- Listing DB + Calendar DB.

Search index document includes:
- listing attributes
- geospatial point
- popularity metrics
- rating aggregates
- coarse availability bitmap
- price summary buckets

Update pipeline:
1. Listing/Calendar/Pricing changes emit events.
2. Indexer consumes events and updates denormalized index document.
3. Typical lag target: less than 30-60 seconds.

Important:
- Search index can be stale.
- Booking path always re-checks source-of-truth before confirmation.

---

## 15. Caching Strategy

Cache layers:
1. CDN:
   - listing images and static content.
2. Edge/API cache:
   - common search responses for anonymous traffic.
3. Service cache (Redis):
   - hot listing metadata
   - host profile snippets
   - pricing fragments
   - availability summaries

Techniques:
- read-through cache for metadata
- write-through or event-based invalidation for hot entities
- jittered TTLs to avoid thundering herd
- request coalescing for expensive quote calls

---

## 16. Event-Driven Architecture And Outbox

Important events:
- `ListingCreated`, `ListingUpdated`, `ListingPublished`
- `CalendarUpdated`
- `ReservationHoldCreated`
- `ReservationConfirmed`
- `ReservationCancelled`
- `PaymentAuthorized`, `PaymentCaptured`, `PaymentRefunded`
- `PayoutScheduled`, `PayoutSettled`
- `ReviewSubmitted`

Outbox pattern:
- Service writes domain change + outbox event in same DB transaction.
- Async publisher reads outbox and sends to event bus.
- Prevents lost events when service crashes between DB write and bus publish.

Consumer design:
- At-least-once consumption.
- Deduplicate using event id and version.

---

## 17. Consistency Model

Strong consistency required for:
- inventory reservation on booking path
- reservation status transition
- payment state transition snapshots

Eventual consistency acceptable for:
- search index freshness
- recommendation features
- analytics dashboards
- non-critical counters

Conflict resolution:
- versioned writes for listing updates and calendar edits.
- last-writer-wins only where business safe (for example non-critical host profile text), not for inventory.

---

## 18. Multi-Region And Disaster Recovery

## 18.1 Topology
- Active-active for read-heavy services (search/listing content).
- Booking and payment control plane can be:
  - active-passive per region pair, or
  - active-active with strict partition ownership to reduce split-brain risk.

## 18.2 Data
- Reservations and payments replicated cross-region with low RPO.
- Event bus with geo-replication.
- Object storage multi-region for media.

## 18.3 Failover
1. Regional health failure detected by control plane.
2. Global traffic manager shifts new traffic.
3. Booking region ownership transferred with guarded runbook.
4. Replay lagging events from replicated log.

RTO and RPO example targets:
- RTO: 15-30 minutes for booking path.
- RPO: < 1 minute for critical ledgers.

---

## 19. Security, Privacy, And Compliance

Security controls:
- TLS everywhere.
- OAuth/JWT for user sessions.
- mTLS between internal services (optional but recommended).
- Field-level encryption for sensitive PII.
- Secret rotation via KMS/HSM.

Compliance:
- PCI DSS boundary around payment services and PSP integration.
- GDPR/CCPA workflows for data export/deletion.
- Access audit logs for all admin tools.

Abuse prevention:
- account takeover detection
- bot booking defense
- fake listing detection via identity and image similarity checks
- payout fraud checks before settlement

---

## 20. Observability And SLOs

Golden signals for every service:
- latency
- traffic
- errors
- saturation

Critical booking metrics:
- hold creation success rate
- hold-to-confirm conversion
- double-booking incidents (should be zero)
- payment auth failure rates by provider
- cancellation and refund processing latency

Search metrics:
- query latency P50/P95/P99
- zero-result rate
- CTR and conversion by query cohort
- index freshness lag

Data pipeline metrics:
- consumer lag
- event drop and duplicate rates
- outbox publish delay

SLO examples:
- Booking confirm success >= 99.95%
- Double-booking defect rate = 0 (hard invariant)
- Search P99 <= 300 ms

---

## 21. Cost Optimization

1. Image/media heavy cost:
   - aggressive CDN caching
   - adaptive image formats and sizes
   - background compression pipelines

2. Search cost:
   - two-stage retrieval/ranking
   - region-aware shard routing
   - query cache for hot destinations/dates

3. Event data cost:
   - compact event schemas
   - sampling for high-volume, low-value telemetry
   - tiered storage lifecycle policies

4. DB cost:
   - archive stale calendars and historical reservations
   - partition pruning
   - read replicas for heavy analytics reads

---

## 22. Trade-Offs And Design Choices

1. Strong consistency on booking vs eventual:
- Strong consistency increases write latency but avoids catastrophic business errors.
- Correct choice: strong consistency for inventory and reservation commit.

2. RDBMS vs NoSQL for calendar:
- RDBMS gives transactional safety and rich constraints.
- NoSQL gives easier horizontal scaling.
- Practical approach: transactional store for booking-critical records plus denormalized cache/projections for search.

3. Synchronous vs async quote computation:
- Fully synchronous can increase user latency.
- Precompute fragments and cache to reduce recomputation while preserving correctness with quote TTL.

4. Monolith vs microservices:
- Monolith simpler early stage.
- At Airbnb scale, domain isolation (booking/payments/search) reduces blast radius and supports independent scaling.

5. Active-active booking globally:
- Better availability but high complexity for conflict control.
- Many teams choose controlled regional ownership for booking writes to reduce split-brain risk.

---

## 23. Interview Deep-Dive Questions And Strong Answers

Q1: How do you guarantee no double booking?
- Source-of-truth listing-date locks with conditional writes in a transaction.
- Hold-then-confirm flow with expiry.
- Revalidation before final confirm.
- Idempotency keys and deterministic retry behavior.

Q2: Search shows available, but booking fails. Why?
- Search uses denormalized near-real-time projections.
- Small staleness window is acceptable.
- Booking path performs strict final check against source-of-truth.

Q3: How do you handle payment success but reservation failure?
- Saga with compensating action.
- If payment auth succeeded but reservation commit fails, void/cancel authorization.
- Reconciliation jobs detect and fix inconsistent states.

Q4: How do you handle host calendar updates from external channels?
- Ingest external calendar changes as events.
- Apply versioned calendar updates.
- Conflict policy favors already-confirmed reservations.

Q5: How do you prevent abuse?
- Risk scoring at booking and payout.
- Device/IP anomaly detection.
- Velocity and graph-based fraud heuristics.

---

## 24. 45-Minute SDE3 Interview Narrative

1. Minutes 0-5:
   - clarify requirements, hard constraints, and scale assumptions.
2. Minutes 5-12:
   - APIs and core entities.
3. Minutes 12-22:
   - architecture and search vs booking path split.
4. Minutes 22-32:
   - booking consistency, locking, payment saga.
5. Minutes 32-40:
   - reliability, observability, DR, and security.
6. Minutes 40-45:
   - trade-offs, extensions, and cost discussion.

---

## 25. Extensions If Interviewer Asks

- Experiences and long-term stays as separate verticals.
- Smart pricing recommender for hosts.
- Real-time map updates and map clustering.
- Multi-unit inventory for hotels/property managers.
- AI-powered support assistant and dispute triage.
- Carbon footprint and sustainability scoring.

---

## 26. Final Summary
At Airbnb scale, the architecture must optimize for two very different workloads:
- discovery: massive read-heavy, low-latency, eventually consistent
- booking/payments: lower volume but correctness-critical, strongly consistent

The key SDE3 signal is demonstrating how you preserve booking correctness (no double-booking, auditable payments, reliable cancellations) while still serving fast, personalized search at global scale.
