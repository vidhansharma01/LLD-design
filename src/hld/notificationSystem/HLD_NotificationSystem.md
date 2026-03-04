# 🔔 High-Level Design (HLD) — Notification System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** High throughput fan-out, Multi-channel delivery, Reliability, User preferences, Deduplication

---

## 1. Requirements

### 1.1 Functional Requirements
- Send notifications via multiple channels: **Push (iOS/Android), Email, SMS, In-App**.
- Support **transactional notifications** (order confirmation, OTP, payment alert) and **marketing notifications** (promotions, offers).
- Users can manage **notification preferences** — opt-in/out per channel per category.
- Support **templating** — dynamic content injected into predefined templates.
- **Scheduling** — send notifications at a future time or user's local timezone.
- **Priority levels** — critical (OTP, fraud alert) vs normal vs low priority (marketing).
- Track **delivery status** — sent, delivered, opened, clicked, failed.
- Support **bulk/broadcast** notifications (e.g., to 100M users during flash sale).
- **Deduplication** — avoid sending the same notification twice.
- **Rate limiting per user** — don't spam users.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Throughput** | 1M notifications/sec (peak sale events) |
| **Latency** | Critical: < 5 sec E2E; Normal: < 30 sec; Marketing: best-effort |
| **Availability** | 99.99% for critical; 99.9% for marketing |
| **Scalability** | 500M registered users; 10M concurrent push subscriptions |
| **Durability** | No notification lost; at-least-once delivery |
| **Idempotency** | Same event must not trigger duplicate notifications |

### 1.3 Out of Scope
- Notification content creation / campaign management UI
- A/B testing framework for notification copy
- Billing per notification (third-party provider costs)

---

## 2. Capacity Estimation

```
Users                      = 500 million
Peak notification rate     = 1M/sec (flash sale broadcast)
Avg notification/user/day  = 5 → 500M × 5 = 2.5B/day → ~29K/sec average
Notification payload size  = ~500 bytes
Storage (90-day retention) = 2.5B/day × 500B × 90 days ≈ ~115 TB
Push token storage         = 500M users × 2 tokens (iOS + Android) × 200B ≈ ~200 GB
Delivery log writes        = 2.5B events/day → ~30K writes/sec
```

---

## 3. High-Level Architecture

```
  Event Sources (Producers)
  ┌──────────────────────────────────────────────────────┐
  │  Order Svc │ Payment Svc │ Promo Svc │ Auth Svc │ ...│
  └─────────────────────┬────────────────────────────────┘
                        │
                        ▼
              ┌─────────────────┐
              │   Event Bus     │  (Kafka — partitioned by userId)
              └────────┬────────┘
                       │
              ┌────────▼────────────┐
              │  Notification       │
              │  Orchestrator       │  (Fan-out, preference check,
              │  (Consumer)         │   dedup, priority routing)
              └────────┬────────────┘
                       │
         ┌─────────────┼──────────────────┐
         ▼             ▼                  ▼
  ┌──────────┐  ┌──────────┐      ┌──────────────┐
  │  Push Q  │  │  Email Q │      │    SMS Q     │
  │ (Kafka)  │  │ (Kafka)  │      │  (Kafka)     │
  └─────┬────┘  └────┬─────┘      └──────┬───────┘
        │             │                   │
  ┌─────▼────┐  ┌────▼──────┐     ┌──────▼───────┐
  │  Push    │  │  Email    │     │   SMS        │
  │  Worker  │  │  Worker   │     │   Worker     │
  └─────┬────┘  └────┬──────┘     └──────┬───────┘
        │             │                   │
  ┌─────▼────┐  ┌────▼──────┐     ┌──────▼───────┐
  │ APNs/FCM │  │ SendGrid/ │     │  Twilio/     │
  │ (3rd pty)│  │  SES      │     │  AWS SNS     │
  └──────────┘  └───────────┘     └──────────────┘
        │             │                   │
        └─────────────┼───────────────────┘
                      ▼
           ┌──────────────────────┐
           │  Delivery Tracker    │  (Status: sent/delivered/opened)
           │  (Cassandra + Redis) │
           └──────────────────────┘
```

---

## 4. Core Components

### 4.1 Event Sources & Kafka Ingestion

- All upstream services publish domain events to Kafka topics (e.g., `order.placed`, `payment.failed`, `promo.flash-sale`).
- **Topic partition key = userId** → all events for a user go to the same partition → preserves ordering.
- **Separate Kafka topics per priority:**
  - `notifications.critical` (OTP, fraud, payment failure)
  - `notifications.transactional` (order updates, shipping)
  - `notifications.marketing` (flash sale, offers)

### 4.2 Notification Orchestrator

**The brain of the system.** Stateless, horizontally scalable consumer group.

**Responsibilities:**
1. **Event → Notification Mapping** — looks up which notification(s) to send for a given event (config-driven rules in DB).
2. **User Preference Check** — query Preference Service: is user opted in for this channel + category?
3. **Deduplication** — check Redis for idempotency key `notif:{userId}:{eventId}` (TTL: 24h).
4. **Rate Limiting** — enforce per-user per-channel limits (e.g., max 5 marketing push/day).
5. **Template Rendering** — merge event payload with Handlebars/Mustache templates.
6. **Priority Routing** — enqueue to the correct channel queue at the right priority.
7. **Scheduling** — if `send_after` is set, push to Scheduler instead of immediate queue.

### 4.3 User Preference Service

```
GET /v1/users/{userId}/notification-preferences

Response:
{
  "push":  { "order_updates": true, "marketing": false },
  "email": { "order_updates": true, "marketing": true  },
  "sms":   { "order_updates": true, "marketing": false }
}
```

- Stored in **MySQL** (user-editable, low write volume, needs strong consistency).
- Cached in **Redis** (TTL: 1 hour) — 90%+ of orchestrator calls served from cache.
- Cache invalidated immediately on preference update.

### 4.4 Channel Workers

Each channel has a dedicated Kafka consumer worker pool.

| Channel | 3rd Party Provider | Fallback Provider |
|---|---|---|
| Push (iOS) | Apple APNs | — |
| Push (Android) | Firebase FCM | — |
| Email | AWS SES | SendGrid |
| SMS | Twilio | AWS SNS |
| In-App | Internal WebSocket / SSE | Polling |

**Worker responsibilities:**
- Read from channel-specific Kafka queue.
- Call 3rd-party provider API.
- Handle retries with **exponential backoff** (max 3 retries).
- On final failure → publish to `notifications.dead-letter` topic.
- Write delivery event to **Delivery Tracker** (async).

### 4.5 Push Token Registry

Stores device tokens for push notifications.

```
Table: push_tokens
Columns:
  user_id     (PK)
  device_id   (SK)
  token       (APNs / FCM token)
  platform    (iOS / Android / Web)
  updated_at
  is_active
```

- Stored in **DynamoDB** (partition key: `userId`).
- Tokens expire/rotate → APNs/FCM returns `InvalidToken` → worker marks `is_active = false`.
- **Multi-device fan-out:** one user may have 3 devices → all receive push.

### 4.6 Template Engine

- Templates stored in DB (versioned): `{ templateId, channel, subject, body, variables[] }`.
- **Handlebars** templating — supports `{{user.name}}`, `{{order.id}}`, conditionals.
- Templates cached in-process (Caffeine) — TTL: 10 min.
- Template rendering is done in the Orchestrator before enqueuing.

### 4.7 Scheduler Service

Handles future-dated and timezone-aware notifications.

```
                Orchestrator
                     │
                     │ (send_after = tomorrow 10am IST)
                     ▼
           ┌──────────────────┐
           │  Scheduler DB    │  (sorted by send_at timestamp)
           │  (Redis ZSET or  │
           │  DynamoDB TTL)   │
           └─────────┬────────┘
                     │
           Polling job runs every 10 sec
                     │
           Due notifications → enqueue to channel queues
```

- **Redis Sorted Set** (`ZSET`): key = `scheduled_notifications`, score = `send_at` unix timestamp.
- Poller uses `ZRANGEBYSCORE` to fetch due notifications.
- At scale: Redis Cluster partitioned by time buckets.

### 4.8 Delivery Tracker

Records the full lifecycle of every notification.

**Cassandra Table:**
```sql
CREATE TABLE notification_events (
    user_id       UUID,
    notification_id UUID,
    channel       TEXT,
    status        TEXT,   -- QUEUED | SENT | DELIVERED | OPENED | CLICKED | FAILED
    event_time    TIMESTAMP,
    provider_msg_id TEXT,
    failure_reason TEXT,
    PRIMARY KEY (user_id, notification_id, event_time)
) WITH CLUSTERING ORDER BY (event_time DESC);
```

- **Delivery receipts** from APNs/FCM/Twilio → worker updates status.
- **Open/Click tracking** via pixel tracking (email) and deep link callbacks (push).
- Retention: 90 days, then archived to S3 cold storage.

---

## 5. Broadcast Notifications (100M Users)

Marketing campaign to all 500M users (e.g., Diwali sale):

```
Campaign Trigger (Admin)
        │
        ▼
  Campaign Service
        │
  Batch job: reads user IDs from User DB
  in chunks of 10,000
        │
  Fan-out Workers (parallel)
        │ (for each chunk)
        ▼
  For each user:
    → preference check (batch Redis lookup)
    → eligibility filter (segment criteria)
    → enqueue to Kafka (notifications.marketing)
        │
  Rate: ~1M enqueues/sec → Kafka absorbs spike
        │
  Push/Email/SMS workers drain queue
  at their own pace
```

**Key insight:** The bottleneck is the **3rd-party provider rate limits** (FCM: ~500K push/sec), not our internal system. We throttle our workers to match provider capacity.

---

## 6. Deduplication Strategy

```
Event arrives at Orchestrator
        │
  Generate idempotency key:
  key = hash(userId + eventId + channel + templateId)
        │
  Redis SET NX (set if not exists):
  SET dedup:{key} 1 EX 86400
        │
  ┌─────┴─────┐
  │ Exists?   │
  Yes         No
  │           │
  DROP     PROCEED to enqueue
```

- Handles both upstream duplicate events and Kafka at-least-once redelivery.
- TTL = 24 hours (configurable per notification type).

---

## 7. Rate Limiting Per User

Enforced in Orchestrator using **Redis sliding window counters**:

```
Key:   ratelimit:{userId}:{channel}:{category}:{date}
Type:  Redis INCR + EXPIRE
```

| Category | Channel | Limit |
|---|---|---|
| Marketing | Push | 3/day |
| Marketing | Email | 1/day |
| Marketing | SMS | 1/week |
| Transactional | All | 50/day |
| Critical | All | Unlimited |

- If limit exceeded → notification is **dropped** (marketing) or **delayed to next window** (transactional).

---

## 8. In-App Notifications

- Delivered via **WebSocket** (persistent connection) for real-time.
- Fallback: **SSE (Server-Sent Events)** for clients that don't support WebSocket.
- Stored in `in_app_notifications` table (Cassandra) — inbox shown in app.
- **Unread count** maintained as Redis counter per user.
- On reconnect: client fetches missed notifications via REST API (since last `seen_at` timestamp).

---

## 9. Failure Handling & Retries

```
Worker calls 3rd-party provider
        │
  ┌─────┴──────────────────────┐
  │ Success                 Failure
  │                            │
Update delivery             Retry with
status = SENT           exponential backoff
                        (1s, 2s, 4s — max 3 retries)
                                │
                         Still failing?
                                │
                   Publish to Dead Letter Queue (DLQ)
                                │
                    Alert on-call + DLQ monitor
                    (manual review / re-drive)
```

**Provider Failover:**
- Email: SES → SendGrid (automatic failover if SES error rate > 5%).
- SMS: Twilio → AWS SNS.
- Push: No failover (APNs/FCM are the definitive providers).

---

## 10. Scalability

| Layer | Scaling Strategy |
|---|---|
| Orchestrator | Stateless — scale out horizontally; Kafka consumer group auto-rebalances |
| Kafka | Partition by `userId`; scale partitions to match peak throughput |
| Push Workers | Scale out; throttle to FCM/APNs rate limits |
| Email Workers | Scale out; SES has high throughput (millions/sec with dedicated IPs) |
| Redis | Cluster mode; separate clusters for dedup, rate-limit, preferences |
| Cassandra | Partition by `userId`; scales linearly |
| Scheduler | Redis Cluster (partitioned ZSET by time bucket) |

---

## 11. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Kafka lag** | Per topic-partition consumer lag; alert if lag > 1M messages |
| **Delivery success rate** | Push/Email/SMS success %; alert if < 95% |
| **End-to-end latency** | Time from event publish to provider delivery |
| **DLQ depth** | Alert if DLQ > 10K unprocessed |
| **Provider error rate** | Per-provider 4xx/5xx; triggers failover |
| **Dedup hit rate** | Track % of duplicates caught |
| **Unsubscribe rate** | Spike = over-notification; business alert |

**Tooling:** Prometheus + Grafana, Jaeger tracing (event → delivery), PagerDuty for on-call alerts.

---

## 12. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Kafka as backbone** | Partitioned by userId | Ordering + durability; slight latency vs direct HTTP calls |
| **Priority separation** | Separate Kafka topics per priority | Critical notifications never delayed by marketing backlog |
| **Async delivery** | Fire-and-forget with status tracking | High throughput; no blocking upstream services |
| **At-least-once delivery** | Kafka consumer + retry | Deduplication required to handle re-delivery |
| **Redis for preferences** | Cache with DB fallback | Near-zero latency preference check; eventual consistency acceptable |
| **Template rendering in Orchestrator** | Not at the worker | Workers are simple; rendering failures caught early |
| **Broadcast via batch fan-out** | Chunk-based user iteration | Avoids memory explosion; back-pressure via Kafka |

---

## 13. Future Enhancements
- **Intelligent Send Time Optimization** — ML model predicts best time to send per user (maximizes open rates).
- **Notification Center** — Cross-platform unified inbox (web + mobile).
- **A/B Testing** — Send variant A to 10%, variant B to 10%, measure engagement.
- **Do Not Disturb (DND)** — Respect user's quiet hours / local timezone.
- **Rich Push Notifications** — Action buttons, images, deep links directly in push payload.

---

*Document prepared for SDE 3 system design interviews. Focus areas: high-throughput fan-out, multi-channel delivery, deduplication, at-least-once semantics, and graceful degradation under provider failures.*
