# HLD — Google Calendar (Full 45-Min SDE 3 Interview Walkthrough)

> **Interview Difficulty:** SDE 3 | **Time Budget:** 45 min
> **Category:** Scheduling / Distributed Consistency / Notification at Scale
> **Real-world Analogue:** Google Calendar, Apple Calendar, Microsoft Outlook Calendar

---

## How to Navigate This Document in an Interview

```
[0:00 -  3:00]  Step 1: Clarify Requirements (ask questions, define boundaries)
[3:00 -  6:00]  Step 2: Capacity Estimation (back-of-envelope math)
[6:00 -  9:00]  Step 3: API Design (core endpoints)
[9:00 - 20:00]  Step 4: High-Level Architecture (draw + explain each box)
[20:00 - 38:00] Step 5: Deep Dives (pick 3 of the 5 below based on interviewer interest)
[38:00 - 42:00] Step 6: Scale & Resilience
[42:00 - 45:00] Step 7: Trade-offs & Alternatives
```

---

## Table of Contents
1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [Recurring Events — The Hardest Part](#51-recurring-events--the-hardest-part)
   - 5.2 [Event Storage & Sharding Strategy](#52-event-storage--sharding-strategy)
   - 5.3 [Invitation System & Fan-Out](#53-invitation-system--fan-out)
   - 5.4 [Reminder & Notification Pipeline](#54-reminder--notification-pipeline)
   - 5.5 [Free/Busy Lookup & Conflict Detection](#55-freebusy-lookup--conflict-detection)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

> **Interview tip:** Spend exactly 3 minutes here. Ask 4–5 targeted questions that reveal hidden complexity. Don't ask obvious things.

### Functional Requirements (State these first, then ask follow-ups)

| # | Requirement | Hidden Complexity |
|---|---|---|
| FR-1 | Create / Read / Update / Delete **events** | CRUD for recurring events is non-trivial |
| FR-2 | **Recurring events** (daily, weekly, monthly, custom) | "Edit this / this+future / all" semantics |
| FR-3 | **Invite attendees**; attendees RSVP (Accept/Decline/Maybe) | Fan-out to millions of shared calendars |
| FR-4 | **Multiple calendars** per user (Personal, Work, Holidays) | Permission model per calendar |
| FR-5 | **Reminders & Notifications** (email, push, in-app) | Must fire at exact time; billions of pending reminders |
| FR-6 | **Free/Busy lookup** — show others' availability | Cross-user query; privacy permissions |
| FR-7 | **Conflict detection** — warn on overlapping events | Index-based overlap query |
| FR-8 | **Time zone handling** — store in UTC, render locally | DST transitions on recurring events |
| FR-9 | **Calendar sharing** — read / write / free-busy-only ACLs | Permission propagation |
| FR-10 | **Search** — find events by title, attendee, date range | Full-text + temporal search |

### Clarifying Questions to Ask in Interview

```
Q1: "When a user edits a recurring event, what are the edit semantics?
     Just this occurrence / this and all future / all occurrences?"
     → This unlocks the exceptions table design.

Q2: "For invitations — if Bob invites 10,000 people to a company event,
     do all 10,000 need to see it immediately in their calendars?"
     → This decides push fan-out vs pull-on-view.

Q3: "Is the free/busy query real-time, or is slight staleness acceptable?
     (e.g., cached for 5 minutes?)"
     → Unlocks bitmap cache vs real-time DB approach.

Q4: "Do reminders need to fire within seconds, or within a minute is fine?"
     → Determines whether we need a sub-second scheduler or a 30s poll.

Q5: "Do we need mobile offline support with local-first and sync-on-reconnect?"
     → Significantly changes the sync protocol complexity.
```

### Non-Functional Requirements

| Attribute | Target | Justification |
|---|---|---|
| **Scale** | 2B users, 10B events | Google-scale assumption |
| **Calendar view latency** | p99 < 200 ms | User interaction must feel instant |
| **Write latency** | p99 < 500 ms | Event create/edit |
| **Availability** | 99.99% | Calendar must always be readable |
| **Consistency** | Strong within own calendar | User MUST see their own edits immediately |
| **Notification accuracy** | Fire within ±60 seconds of target | Acceptable for calendar reminders |
| **Search latency** | p99 < 1 s | Full-text search |

### Out of Scope (State explicitly)
- Video conferencing internals (Google Meet is a separate system)
- Room/resource booking inventory management
- Third-party OAuth calendar sync internals (Exchange, iCal)
- Billing and enterprise SSO

---

## 2. Capacity Estimation

> **Interview tip:** Do the math out loud. Show your reasoning, not just the answer.

### Users & Events

```
Users:       2 billion total; 500M DAU
Events/user: avg 50 events stored, 5 active events viewed/day
Total events: 2B users x 50 events = 100B events stored

But: recurring events are stored as 1 rule, not expanded.
  Realistic stored records: ~10B (many series count as 1)
```

### Read Traffic

```
Calendar view load (most common operation):
  500M DAU x 5 calendar views/day = 2.5B views/day
  Sustained: 2.5B / 86400 = ~29K reads/sec
  Peak (9 AM Monday morning): 5x sustained = ~145K reads/sec

Event detail load: ~10K reads/sec additional
Free/busy queries (scheduling assistant): ~5K reads/sec

Total read RPS: ~160K reads/sec peak
```

### Write Traffic

```
Event create/edit:
  500M DAU x 0.5 writes/day = 250M writes/day
  Sustained: 250M / 86400 = ~2,900 writes/sec
  Peak: 5x = ~15K writes/sec

RSVP updates: ~3K writes/sec (attendees accepting/declining)
Reminder creation: each event write -> avg 2 reminders -> 30K/sec reminder jobs

Total write RPS: ~20K writes/sec
```

### Storage

```
Per event record:
  event_id (16B) + user_id (16B) + title (100B) + timestamps (16B)
  + timezone (32B) + rrule (128B) + description (500B) + tags (50B)
  = ~860 bytes avg per event

10B events x 860 bytes = ~8.6 TB raw (before replication)
With 3x replication: ~26 TB

Attendee records: 10B events x avg 5 attendees x 64 bytes = 3.2 TB
Reminder jobs: 10B active reminders x 80 bytes = 800 GB

Total storage: ~30 TB across cluster
```

### Notification Burst

```
Peak reminder fire time: Monday 9:00 AM (everyone's standup)
  Estimate: 5% of 500M DAU have a 9 AM reminder = 25M reminders at exactly 9:00
  Must fire within 60 seconds -> 25M / 60 = ~416K notifications/sec burst
  Channels: FCM (mobile push), APNS (iOS), email (SES)
```

---

## 3. API Design

> **Interview tip:** Define 5-6 critical endpoints. Show awareness of tricky parameters.

### Core Event APIs

```http
# ============================================================
# CREATE EVENT
# ============================================================
POST /v1/calendars/{calendar_id}/events
Authorization: Bearer {jwt_token}
Content-Type: application/json

{
  "summary":     "Team Standup",
  "description": "Daily sync call",
  "location":    "Google Meet: meet.google.com/abc-xyz",

  # Time with explicit timezone (wall clock, not UTC offset)
  "start": { "dateTime": "2026-04-07T10:00:00", "timeZone": "Asia/Kolkata" },
  "end":   { "dateTime": "2026-04-07T10:30:00", "timeZone": "Asia/Kolkata" },

  # RFC 5545 recurrence rules (can have multiple, e.g., RRULE + EXDATE)
  "recurrence": [
    "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T235959Z"
  ],

  # Attendees (triggers invite emails)
  "attendees": [
    { "email": "alice@example.com", "optional": false },
    { "email": "bob@example.com",   "optional": true  }
  ],

  # Reminders override defaults
  "reminders": {
    "useDefault": false,
    "overrides": [
      { "method": "email",  "minutes": 1440 }, # 24h before
      { "method": "popup",  "minutes": 10   }, # 10min before
      { "method": "sms",    "minutes": 60   }  # 1h before
    ]
  },

  # Visibility & color
  "visibility": "default",      # default | public | private | confidential
  "colorId": "5",
  "guestsCanModify": false,
  "guestsCanSeeOtherGuests": true
}

Response 201 Created:
{
  "id": "evt_7f3a9b2c1d4e",
  "htmlLink": "https://calendar.google.com/event?eid=...",
  "status": "confirmed",
  "creator":   { "email": "vidhan@example.com" },
  "organizer": { "email": "vidhan@example.com" },
  ...full event object...
}
```

```http
# ============================================================
# LIST EVENTS FOR A DATE RANGE (calendar view)
# ============================================================
GET /v1/calendars/{calendar_id}/events
    ?timeMin=2026-04-07T00:00:00Z      # inclusive lower bound
    &timeMax=2026-04-13T23:59:59Z      # exclusive upper bound
    &singleEvents=true                 # expand recurring -> individual instances
    &orderBy=startTime                 # or "updated"
    &q=standup                         # optional full-text search
    &pageToken={cursor}                # pagination

Response 200:
{
  "kind": "calendar#events",
  "summary": "Vidhan's Calendar",
  "timeZone": "Asia/Kolkata",
  "nextPageToken": "CiAKGjBpNDdVbWxkUGhPaVp...",
  "items": [
    {
      "id": "evt_7f3a9b2c1d4e_20260407",  # recurring instance ID
      "recurringEventId": "evt_7f3a9b2c1d4e",
      "originalStartTime": { "dateTime": "2026-04-07T10:00:00", "timeZone": "Asia/Kolkata" },
      "summary": "Team Standup",
      "start": { "dateTime": "2026-04-07T10:00:00+05:30" },
      "end":   { "dateTime": "2026-04-07T10:30:00+05:30" },
      "attendees": [
        { "email": "alice@example.com", "responseStatus": "accepted" },
        { "email": "bob@example.com",   "responseStatus": "needsAction" }
      ]
    }
  ]
}
```

```http
# ============================================================
# UPDATE EVENT — with editScope for recurring events
# ============================================================
PATCH /v1/calendars/{calendar_id}/events/{event_id}
      ?editScope=thisAndFollowing      # thisOnly | thisAndFollowing | allEvents

# For thisOnly: event_id = base_event_id_YYYYMMDD (specific occurrence)
# For thisAndFollowing: splits series at this date
# For allEvents: updates master RRULE (affects all future instances)

{
  "summary": "Team Standup (Updated)",
  "start": { "dateTime": "2026-04-07T10:30:00", "timeZone": "Asia/Kolkata" }
}
```

```http
# ============================================================
# RSVP to an event invitation
# ============================================================
POST /v1/calendars/primary/events/{event_id}/rsvp
{ "responseStatus": "accepted" }  # accepted | declined | tentative | needsAction

# ============================================================
# FREE/BUSY QUERY (scheduling assistant)
# ============================================================
POST /v1/freeBusy
{
  "timeMin": "2026-04-07T09:00:00Z",
  "timeMax": "2026-04-07T17:00:00Z",
  "timeZone": "Asia/Kolkata",
  "items": [
    { "id": "alice@example.com" },
    { "id": "bob@example.com"   },
    { "id": "team-calendar@group.example.com" }   # group calendar
  ]
}
Response 200:
{
  "kind": "calendar#freeBusy",
  "calendars": {
    "alice@example.com": {
      "busy": [
        { "start": "2026-04-07T10:00:00Z", "end": "2026-04-07T10:30:00Z" },
        { "start": "2026-04-07T14:00:00Z", "end": "2026-04-07T15:00:00Z" }
      ]
    },
    "bob@example.com": { "busy": [] }
  }
}
```

```http
# ============================================================
# CALENDAR SHARING / PERMISSIONS
# ============================================================
PUT /v1/calendars/{calendar_id}/acl
{
  "role": "writer",      # reader | writer | owner | freeBusyReader
  "scope": { "type": "user", "value": "carol@example.com" }
}
```

---

## 4. High-Level Architecture

> **Interview tip:** Draw while explaining. Walk through each component's responsibility and WHY you chose it.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT LAYER                                         │
│   Web Browser (React SPA)    iOS App    Android App    3rd-party Clients     │
└────────────────────────┬─────────────────────────────────────────────────────┘
                         │  HTTPS / REST / gRPC
                         v
┌────────────────────────────────────────────────────────────────────────────┐
│                    API GATEWAY (Stateless)                                  │
│  - JWT validation / OAuth 2.0 token verification                            │
│  - Rate limiting: per-user (1K req/min), per-IP burst protection            │
│  - Request routing to downstream microservices                              │
│  - SSL termination, request logging, distributed tracing header injection   │
└─────┬────────────┬──────────────┬───────────────┬──────────────────────────┘
      │            │              │               │
      v            v              v               v
┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────────┐
│ CALENDAR │ │  INVITE  │ │ NOTIFICATION │ │    SEARCH    │
│ SERVICE  │ │ SERVICE  │ │   SERVICE    │ │   SERVICE    │
│          │ │          │ │              │ │              │
│ - CRUD   │ │ - Send   │ │ - Schedule   │ │ - Full-text  │
│   events │ │   invites│ │   reminders  │ │   event      │
│ - RRULE  │ │ - RSVP   │ │ - Fire via   │ │   search     │
│   expand │ │   state  │ │   FCM/APNS/  │ │ - Attendee   │
│ - Conflict│ │ - Fan-out│ │   SES/SMS    │ │   search     │
│   detect │ │   worker │ │              │ │              │
└────┬─────┘ └────┬─────┘ └──────┬───────┘ └─────┬────────┘
     │            │              │               │
     v            v              v               v
┌────────────────────────────────────────────────────┐
│                MESSAGE BUS (Kafka)                  │
│  Topics:                                            │
│    event-writes     (event CRUD -> fan-out + index) │
│    invite-actions   (RSVP changes)                  │
│    reminder-jobs    (reminder scheduling events)    │
└──────────────────────┬─────────────────────────────┘
                       │
        +--------------+-----------------+
        |              |                 |
        v              v                 v
┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐
│  EVENT STORE │ │  INBOX STORE │ │  REMINDER STORE       │
│              │ │              │ │                        │
│ Cloud Spanner│ │  Cassandra   │ │  Redis Sorted Set      │
│ (ACID for    │ │  (per-user   │ │  OR                    │
│  event +     │ │   attendee   │ │  Time-partitioned      │
│  attendee    │ │   inbox;     │ │  PostgreSQL table       │
│  atomicity)  │ │   fast reads)│ │  (fire_at buckets)     │
│              │ │              │ │                        │
│ Sharded by   │ │ Sharded by   │ │  Scheduler polls       │
│ calendar_id  │ │ user_id      │ │  every 30 seconds      │
└──────────────┘ └──────────────┘ └──────────────────────┘
        |
        v
┌──────────────────────┐         ┌─────────────────────┐
│  READ CACHE (Redis)  │         │  SEARCH INDEX        │
│  - Calendar views    │         │  (Elasticsearch)     │
│  - Free/Busy bitmaps │         │  - event titles      │
│  - Event metadata    │         │  - attendee emails   │
│  TTL: 10-60 min      │         │  - date range filter │
└──────────────────────┘         └─────────────────────┘
```

### Component Responsibilities (walk through each in interview)

| Component | Why This Technology |
|---|---|
| **API Gateway** | Stateless; Kong or Envoy; handles auth, rate limiting without touching application logic |
| **Calendar Service** | CRUD + RRULE expansion; reads from Event Store; writes go through Kafka |
| **Invite Service** | Async fan-out; handles broadcasting event to 100s of attendees without blocking Calendar Service |
| **Notification Service** | Stateless; picks up Kafka reminder events; dispatches to FCM/APNS/SES |
| **Event Store (Spanner)** | ACID needed: creating event + inserting attendees must be atomic |
| **Inbox Store (Cassandra)** | Fast per-user lookup: "give me alice's calendar events for this week"; eventual consistency OK |
| **Reminder Store (Redis ZSET)** | O(log N) schedule/cancel; ZRANGEBYSCORE for due reminders |
| **Search (Elasticsearch)** | Full-text + date range hybrid queries; async populated from Kafka |

---

## 5. Deep Dives

### 5.1 Recurring Events — The Hardest Part

> **Interview tip:** This is the #1 differentiator at SDE 3. Nail the exceptions table design.

#### Why Naive Expansion Fails

```
Naive: expand all instances and store each as a row.

Daily standup for 2 years:
  2 x 365 = 730 rows for ONE event series.

10M recurring event series x 730 instances = 7.3 BILLION rows just for recurring events.
Query "get this week's events" = filter through billions of rows.

This is completely infeasible at Google scale.
```

#### Correct Approach: Store the Rule, Expand on Read

```
Database: store exactly 1 row per series (the RRULE)
Application: expand instances at query time using rrule library

struct RecurringEvent {
  event_id:     UUID  -- master series identifier
  calendar_id:  UUID
  title:        String
  start_utc:    Timestamp   -- first occurrence start (UTC)
  end_utc:      Timestamp   -- first occurrence end (UTC)
  timezone:     String      -- "Asia/Kolkata" (for wall-clock expansion)
  rrule:        String      -- "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
  duration_sec: Int         -- end-start; apply to every instance
  created_at:   Timestamp
  updated_at:   Timestamp
}
```

#### RFC 5545 RRULE Specification

```
RRULE Anatomy:
  RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261231T235959Z

Components:
  FREQ     = SECONDLY | MINUTELY | HOURLY | DAILY | WEEKLY | MONTHLY | YEARLY
  INTERVAL = N (every N FREQs; default=1)
  BYDAY    = MO,TU,WE,TH,FR (weekdays); -1FR (last Friday of month)
  BYMONTHDAY = 15 (15th of month)
  BYMONTH  = 3 (March)
  UNTIL    = 20261231T235959Z (end date)
  COUNT    = 52 (number of occurrences)
  EXDATE   = 20260401T040000Z (exclusion date — this instance is deleted)

Complex examples:
  "Every last Friday of each month":
    RRULE:FREQ=MONTHLY;BYDAY=-1FR

  "Every other week on Mon/Wed/Fri":
    RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR

  "1st and 15th of every month":
    RRULE:FREQ=MONTHLY;BYMONTHDAY=1,15
```

#### Expansion Algorithm at Read Time

```python
def get_events_in_range(calendar_id, time_min, time_max):
    # Step 1: Fetch all event series that could have instances in range
    series = db.query("""
        SELECT * FROM events WHERE calendar_id = :cid
        AND (rrule IS NULL  -- one-time events entirely in range
             OR start_utc <= :time_max)  -- recurring series started before end of range
        AND (rrule IS NULL AND end_utc >= :time_min  -- one-time overlaps
             OR rrule IS NOT NULL)  -- recurring: we'll filter after expansion
    """, cid=calendar_id, time_min=time_min, time_max=time_max)

    all_instances = []

    for event in series:
        if event.rrule is None:
            # One-time event: include as-is
            all_instances.append(event)
        else:
            # Step 2: Expand RRULE within requested range
            rule = rrulestr(event.rrule, dtstart=event.start_utc)
            occurrences = rule.between(time_min, time_max, inc=True)

            for occ_start in occurrences:
                instance = EventInstance(
                    id=f"{event.event_id}_{occ_start.strftime('%Y%m%d')}",
                    recurring_event_id=event.event_id,
                    start=occ_start,
                    end=occ_start + timedelta(seconds=event.duration_sec),
                    title=event.title,  # will be overridden if exception exists
                )
                all_instances.append(instance)

    # Step 3: Apply exceptions (edits/cancellations of specific instances)
    exceptions = db.query("""
        SELECT * FROM event_exceptions
        WHERE event_id IN :series_ids
        AND original_date BETWEEN :start AND :end
    """, series_ids=series_ids_from_above, ...)

    for exc in exceptions:
        if exc.is_deleted:
            # Remove this occurrence from results
            all_instances = [i for i in all_instances
                             if i.recurring_event_id != exc.event_id
                             or i.original_date != exc.original_date]
        else:
            # Override fields for this occurrence
            instance = find_instance(all_instances, exc.event_id, exc.original_date)
            instance.apply_overrides(exc.overrides)

    # Step 4: Sort by start time and return
    return sorted(all_instances, key=lambda e: e.start)
```

#### Exceptions Table (Edit Semantics)

```sql
-- Stores overrides for specific occurrences of a recurring event series
CREATE TABLE event_exceptions (
  exception_id    UUID NOT NULL DEFAULT gen_random_uuid(),
  event_id        UUID NOT NULL,          -- parent recurring series
  original_date   DATE NOT NULL,          -- which occurrence (date of original start)
  start_utc       TIMESTAMP,              -- new start if changed (NULL = unchanged)
  end_utc         TIMESTAMP,              -- new end if changed
  is_deleted      BOOLEAN DEFAULT FALSE,  -- TRUE = this occurrence is cancelled
  overrides       JSONB,                  -- { title, location, description, ... }
  attendee_changes JSONB,                 -- { add: [...], remove: [...] }
  created_at      TIMESTAMP DEFAULT NOW(),
  PRIMARY KEY (event_id, original_date)   -- one exception per occurrence
);
```

#### Three Edit Semantics (Critical — Always Explain These)

```
Given: Weekly standup, occurring every Monday (M1, M2, M3, M4, M5...)
User edits M3 and chooses:

1. "Only this event" (thisOnly):
   → INSERT INTO event_exceptions (event_id, original_date='M3', overrides={new_title})
   → Original RRULE unchanged; M1, M2, M4, M5... unaffected
   → On read: expand RRULE → apply exception at M3 → replace title only

2. "This and following events" (thisAndFollowing):
   → This terminates the original series: UPDATE events SET rrule = "...UNTIL=M2"
   → Creates NEW series starting from M3 with new properties
   → New RRULE: "RRULE:FREQ=WEEKLY;BYDAY=MO" starting at M3
   → Two series in DB: original (M1-M2) and new (M3 onwards)

3. "All events" (allEvents):
   → UPDATE events SET title='New Title' WHERE event_id = :parent_id
   → Ripples through all past and future occurrences
   → Exceptions that override the same field may conflict → last-write-wins

DST (Daylight Saving Time) Edge Case:
   Event: every Sunday at 2:00 AM (US/Eastern)
   DST spring-forward: 2:00 AM doesn't exist on that Sunday!
   → Correct behavior: skip that occurrence (RFC 5545 compliance)
   
   Event: every fall-back Sunday at 1:30 AM
   → 1:30 AM happens TWICE on fall-back night
   → RFC 5545: use the first occurrence (pre-transition)
   
   Implementation: ALWAYS expand in wall-clock time using local timezone,
   then convert each instance to UTC separately.
   DO NOT expand in UTC and convert (produces wrong results around DST).
```

---

### 5.2 Event Storage & Sharding Strategy

> **Interview tip:** Justify EVERY choice. Don't just say "I'll use Spanner" — explain WHY it beats alternatives.

#### Primary Database: Cloud Spanner

```
Why Spanner over other options?

Option A: PostgreSQL (single node)
  - Max writes: ~10K/sec; storage: ~10 TB
  - Can't handle 2B users' data
  - ✗ Rejected: doesn't scale

Option B: Cassandra (leaderless, tunable consistency)
  - Scales horizontally, handles petabytes
  - Problem 1: No ACID transactions
    When Bob creates event + inserts 50 attendee records:
    Partial failure -> attendees partially inserted
    Some attendees get invite, some don't -> data inconsistency
  - Problem 2: No secondary indexes (attends_events_for_user requires careful modeling)
  - ✗ Rejected: no ACID; poor for relational data

Option C: Cloud Spanner
  + Globally distributed + ACID transactions + SQL interface
  + External consistency (stronger than serializable; linearizable globally)
  + Auto-sharding with no hot-spot risk (uses FARSITE distributed B-tree)
  + Handles 2B users at petabyte scale
  + TrueTime API: bounded clock uncertainty for commit timestamps
  ✓ Chosen: only DB that gives both ACID and horizontal scale
```

#### Schema Design

```sql
-- Primary calendar and events tables (Spanner syntax)

CREATE TABLE Calendars (
  calendar_id    STRING(36) NOT NULL,
  user_id        STRING(36) NOT NULL,
  name           STRING(128) NOT NULL,
  description    STRING(1024),
  color          STRING(16),
  is_primary     BOOL NOT NULL DEFAULT FALSE,
  timezone       STRING(64) NOT NULL DEFAULT 'UTC',
  acl            JSON,         -- access control list
  created_at     TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
) PRIMARY KEY (user_id, calendar_id);  -- Shards by user_id

-- Interleaved: Events stored physically alongside parent Calendar
CREATE TABLE Events (
  calendar_id    STRING(36) NOT NULL,
  event_id       STRING(36) NOT NULL,
  user_id        STRING(36) NOT NULL,  -- denormalized for fan-out queries
  title          STRING(1024) NOT NULL,
  description    STRING(MAX),
  start_utc      TIMESTAMP NOT NULL,
  end_utc        TIMESTAMP NOT NULL,
  timezone       STRING(64) NOT NULL,
  rrule          STRING(1024),          -- NULL for one-time events
  duration_sec   INT64,
  locus          STRING(1024),          -- location/meet link
  visibility     STRING(32) NOT NULL DEFAULT 'default',
  color_id       STRING(8),
  status         STRING(32) NOT NULL DEFAULT 'confirmed',
  created_at     TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  updated_at     TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
) PRIMARY KEY (calendar_id, event_id),
  INTERLEAVE IN PARENT Calendars ON DELETE CASCADE;
  -- Interleaving: Spanner co-locates Events rows with their Calendar row
  -- Benefit: single-node read for all events of a calendar = NO cross-shard joins

-- Also interleaved under Events
CREATE TABLE EventAttendees (
  calendar_id    STRING(36) NOT NULL,
  event_id       STRING(36) NOT NULL,
  attendee_email STRING(256) NOT NULL,
  display_name   STRING(128),
  rsvp_status    STRING(32) NOT NULL DEFAULT 'needsAction',
  is_organizer   BOOL NOT NULL DEFAULT FALSE,
  is_optional    BOOL NOT NULL DEFAULT FALSE,
  responded_at   TIMESTAMP,
) PRIMARY KEY (calendar_id, event_id, attendee_email),
  INTERLEAVE IN PARENT Events ON DELETE CASCADE;

-- Separate table (NOT interleaved) for reverse lookup: "events I'm invited to"
CREATE TABLE UserInvites (
  attendee_email STRING(256) NOT NULL,
  organizer_calendar_id STRING(36) NOT NULL,
  event_id       STRING(36) NOT NULL,
  rsvp_status    STRING(32) NOT NULL DEFAULT 'needsAction',
  event_start_utc TIMESTAMP NOT NULL,    -- for date-range query without JOIN
  event_end_utc   TIMESTAMP NOT NULL,
  event_title     STRING(1024),          -- denormalized for fast calendar list view
) PRIMARY KEY (attendee_email, event_start_utc DESC, event_id);
-- Note: sort by event_start_utc DESC for efficient "get upcoming invited events"

CREATE TABLE EventExceptions (
  event_id       STRING(36) NOT NULL,
  original_date  DATE NOT NULL,
  start_utc      TIMESTAMP,
  end_utc        TIMESTAMP,
  is_deleted     BOOL NOT NULL DEFAULT FALSE,
  overrides      JSON,
  created_at     TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
) PRIMARY KEY (event_id, original_date);
```

#### Why Interleaved Tables in Spanner

```
Regular tables: Calendar[cal_id] and Events[cal_id, evt_id] on different shards
  → Query "get all events for calendar C in date range":
    1. Lookup Calendar row on shard A
    2. Lookup all Event rows on shard B, C (different shards)
    → Cross-shard distributed join = multiple network hops = slow

Interleaved tables: Events rows physically co-located with Calendar row
  → Same query:
    1. Single shard lookup (Calendar + all its Events on the same node)
    → No cross-shard join = 10-100x faster for calendar view queries

Tradeoff: All events for a calendar must fit on one shard
  Max shard size in Spanner: ~1TB per split
  Spanner auto-splits based on load, not just key range
  Fine for even heavy users (1M events x 2KB = 2GB per user — well within limit)
```

---

### 5.3 Invitation System & Fan-Out

> **Interview tip:** The challenge is fan-out. 1 write triggers N updates. Design for N = 10,000.

#### Invitation Flow

```
Step 1: Bob creates event, invites Alice, Carol, and Team-X@groups (500 members)

Calendar Service receives POST /events:
  → BEGIN TRANSACTION (Spanner):
       INSERT INTO Events (calendar_id=bob_cal, event_id='evt_123', ...)
       INSERT INTO EventAttendees (alice@, carol@, team-x@group)
     COMMIT TRANSACTION
  → Publish to Kafka: topic=event-writes
       {
         event_id: 'evt_123',
         action: 'CREATED',
         organizer: 'bob@',
         attendees: ['alice@', 'carol@', 'team-x@group'],
         event_data: { ...full event... }
       }
  → Return 201 to Bob immediately (async fan-out happens after)

Step 2: Fan-Out Worker (Kafka consumer, scales horizontally):
  For each attendee:
    → Expand groups: team-x@group resolves to [member1@, member2@, ..., member500@]
    → For each individual:
         INSERT INTO UserInvites (attendee_email=member1@, event_id='evt_123', ...)
         Queue invite email (send via SES)
         Queue push notification (send via FCM/APNS)

Step 3: Attendee Alice opens her calendar:
  → Query: SELECT * FROM UserInvites WHERE attendee_email='alice@'
               AND event_start_utc BETWEEN :start AND :end
  → Get event details (either from UserInvites denormalized columns or JOIN to Events)
  → Alice sees event with RSVP buttons (Accept / Decline / Maybe)
```

#### Large Guest List Problem (> 1000 attendees)

```
Company-wide event: CEO announces all-hands, invites 50,000 employees.

Problem with push fan-out:
  50,000 writes to UserInvites = 50,000 DB writes
  At 1ms/write = 50 seconds just for one event!
  At scale (100 such events/day): write amplification = 5M UserInvite rows/day

Solution: Threshold-based pull model for large events

  If attendee count > THRESHOLD (e.g., 500):
    → Mark event as: large_event = true
    → Store event ONCE in a special LargeEvents table
    → Create ONE SubscriptionRecord: { group_id, event_id }
    → Do NOT write to UserInvites

  When Alice loads her calendar:
    → Check UserInvites (small events she's individually invited to)
    → ALSO check: is alice in any groups that have LargeEvent subscriptions?
        SELECT event_id FROM LargeEventSubscriptions
        WHERE group_id IN (alice's group memberships)
        AND event_start_utc BETWEEN :start AND :end
    → Merge: personal events + group events

  Threshold decision:
    < 500 attendees: push (write fan-out per attendee)
    ≥ 500 attendees: pull (subscription lookup at read time)

  Trade-off:
    Push: faster reads (all events in one UserInvites query)
    Pull: slower reads (extra join at read time) but avoids write amplification
```

#### RSVP Handling

```
Alice accepts event:
  POST /v1/calendars/primary/events/evt_123/rsvp
  { "responseStatus": "accepted" }

Server:
  BEGIN TRANSACTION:
    UPDATE EventAttendees SET rsvp_status='accepted', responded_at=NOW()
    WHERE calendar_id=bob_cal AND event_id='evt_123' AND attendee_email='alice@'

    UPDATE UserInvites SET rsvp_status='accepted'
    WHERE attendee_email='alice@' AND event_id='evt_123'
  COMMIT TRANSACTION

  Publish to Kafka: topic=rsvp-updates
  { event_id: 'evt_123', attendee: 'alice@', status: 'accepted' }

Organizer notification (async):
  Bob's notification: "Alice accepted Team Standup"
  Notification Worker reads Kafka -> sends push to Bob's device

Real-time RSVP count update on Bob's event detail page:
  WebSocket subscription on event_id
  → RSVP changes pushed to Bob's open browser tab
  → No polling needed for real-time attendee status
```

---

### 5.4 Reminder & Notification Pipeline

> **Interview tip:** The key insight is the 9 AM burst problem (25M reminders firing in 60 seconds).

#### Reminder Storage & Scheduling

```
Each event create/edit generates N reminder jobs:
  Event: Team Standup on Mon April 7, 10:00 AM IST
  Reminders: [10 min before, 1 day before]
  
  Generated reminder jobs:
    { job_id, event_id, user_id, fire_at='2026-04-07T04:20:00Z', method='popup', status='pending' }
    { job_id, event_id, user_id, fire_at='2026-04-06T04:30:00Z', method='email', status='pending' }

Where to store these reminders?

Option A: PostgreSQL with fire_at index
  Problem: 10B active reminders x polling every 30s = huge index scan
  Even with index on fire_at, scanning 10B rows is slow
  ✗ Too slow at scale

Option B: Redis Sorted Set (Recommended for small-to-medium scale)
  ZADD reminders {fire_at_unix_ts} {job_id}
  Scheduler: ZRANGEBYSCORE reminders 0 {now+30s} LIMIT 1000
  After processing: ZREM reminders {job_id} (or mark in Redis Hash)
  
  Memory: 10B reminders x 50 bytes = 500 GB
  → Need Redis Cluster with 50+ nodes (10 GB data per node)
  
  Pro: O(log N) per operation; fast range queries
  Con: Memory expensive at 10B scale

Option C: Time-bucketed partitioned table (Recommended for Google scale)
  Partition reminder_jobs by fire_at_hour:
  
  CREATE TABLE reminder_jobs (
    fire_at_hour   TIMESTAMP NOT NULL,  -- hour of fire_at (truncated to hour)
    fire_at        TIMESTAMP NOT NULL,  -- exact fire time
    job_id         UUID NOT NULL,
    event_id       UUID NOT NULL,
    user_id        UUID NOT NULL,
    method         ENUM('popup','email','sms'),
    status         ENUM('pending','claimed','fired','cancelled'),
    PRIMARY KEY (fire_at_hour, fire_at, job_id)
  ) PARTITION BY RANGE (fire_at_hour);
  -- Partition for "2026-04-07T10:00:00" (one partition per hour)
  
  Scheduler only reads the CURRENT and NEXT hour's partition:
    SELECT * FROM reminder_jobs
    WHERE fire_at_hour IN (current_hour, next_hour)
    AND fire_at <= NOW() + INTERVAL '30 seconds'
    AND status = 'pending'
    LIMIT 5000;
  
  Each partition = at most a few million rows (one hour of reminders)
  = manageable scan
  
  Partition GC: delete/archive partitions older than 2 hours automatically
```

#### Scheduler Architecture

```
Reminder Scheduler Service:
  Runs on 10 pods (for parallelism)
  Each pod polls every 30 seconds for its assigned fire_at_minute range
  (Pod 1: minutes 0-5; Pod 2: minutes 6-11; etc.)

  Poll query:
    SELECT job_id, user_id, method, event_id FROM reminder_jobs
    WHERE fire_at BETWEEN NOW() AND NOW() + INTERVAL '35 seconds'
    AND status = 'pending'
    AND pod_assignment = :my_pod_id  -- range-based pod assignment
    LIMIT 1000;

  Claim jobs atomically (prevent double-firing):
    UPDATE reminder_jobs SET status = 'claimed', claimed_at = NOW(), claimed_by = :pod_id
    WHERE job_id IN (:job_ids) AND status = 'pending'

  Publish to Kafka: topic=reminder-fire-events
    { user_id, event_id, method, event_title, event_start_time, calendar_id }

  Notification Dispatcher (Kafka consumer, scales to 100 pods):
    Reads from reminder-fire-events
    For method='popup': send via WebSocket / Server-Sent Events to user's open tabs
    For method='email': enqueue to SES (batch, rate-limited to avoid spam filters)
    For method='sms':   enqueue to Twilio / SNS
    For method='push':  send via FCM (Android) or APNS (iOS)

Handling the 9 AM burst:
  Problem: 25M users have 9 AM standups -> 25M reminders at 08:50 AM (10min before)

  Solution 1: Horizontal scaling of Notification Dispatcher
    Auto-scale pods during predicted peak: scale to 500 pods at 8:45 AM
    25M / 60s / 500 pods = ~833 per pod per second -- manageable

  Solution 2: Spread reminder delivery
    Instead of EXACTLY 10 min before, fire within [10min - 30s, 10min + 30s]
    Jitter by random(0, 60) seconds -> spreads burst by 60x
    Users don't notice ±30 second jitter on a calendar reminder

  Solution 3: Pre-warm messaging infrastructure
    Daily ML model predicts peak times -> pre-allocate FCM/APNS connection pools ahead of time
```

---

### 5.5 Free/Busy Lookup & Conflict Detection

#### Free/Busy Query (Scheduling Assistant)

```
Use case: Alice wants to schedule a meeting with Bob and Carol.
  Show a time slot selector with their availability highlighted.

Approach 1 — Real-time DB query per attendee:
  For each attendee, run:
    SELECT start_utc, end_utc FROM events
    WHERE calendar_id = {attendee_primary_calendar}
    AND start_utc < {timeMax}
    AND end_utc > {timeMin}
    AND status = 'confirmed'
    AND visibility != 'private'  -- respect privacy settings
  
  Result: list of busy intervals per attendee
  
  Latency: 3 attendees x 5ms/query (parallel) = 5ms total (good for small groups)
  For 50 attendees: 50 parallel queries -> still ~5-10ms with connection pooling
  ✓ Acceptable for < 100 attendees

Approach 2 — Cached Free/Busy Bitmap (for large org scheduling tools):
  Pre-compute per user per day as a bitmap in Redis:
  
  Key: freeBusy:{user_id}:{date_string}
  Value: 96-bit bitmap (one bit per 15-minute slot in a 24-hour day)
    bit 0  = 00:00 - 00:15
    bit 1  = 00:15 - 00:30
    ...
    bit 40 = 10:00 - 10:15   <- if bob has standup at 10AM, bit 40 = 1
    ...
    bit 95 = 23:45 - 24:00
  
  Stored as 12 bytes (96 bits) per user per day!
  
  Group availability check:
    GET freeBusy:alice:2026-04-07  -> 12 bytes
    GET freeBusy:bob:2026-04-07    -> 12 bytes
    GET freeBusy:carol:2026-04-07  -> 12 bytes
    
    combined = alice_bits AND bob_bits AND carol_bits  -> bits where ALL are free
    Return: free time slots = positions where combined has 0 bits
    
    Total: 3 Redis GET operations (< 1ms total)
  
  Cache invalidation:
    On any event write for user X on date D:
      DEL freeBusy:{user_id}:{date_string}
      (Lazily recomputed on next freeBusy query)
  
  TTL: 24 hours (max staleness = 24h; acceptable for scheduling purposes)
```

#### Conflict Detection on Event Create

```
When Alice creates a new event:
  New event: April 7, 10:00 AM - 11:00 AM

Check: does this overlap any existing event in her calendar?

Overlap condition: A and B overlap iff A.start < B.end AND A.end > B.start
  (Interval intersection logic -- memorize this!)

Query:
  SELECT event_id, title, start_utc, end_utc
  FROM Events
  WHERE calendar_id = alice_primary_calendar
  AND status = 'confirmed'
  AND start_utc < '10:00:00 + duration = 11:00:00'  -- new event end
  AND end_utc > '10:00:00'                           -- new event start
  LIMIT 10;

Index needed:
  CREATE INDEX idx_events_timerange
  ON Events (calendar_id, start_utc, end_utc)
  WHERE status = 'confirmed';
  
  With this index: O(log N + K) where N=total events, K=overlapping events
  Typically K=0 or 1 (no conflicts) -> very fast

Response to user:
  If conflicts found: return 200 with conflicts list + warning
  Do NOT return 409 Conflict -- calendar UX convention is to warn, not block
  User decides whether to proceed
  
  {
    "created": true,
    "conflicts": [
      { "event_id": "...", "title": "1:1 with Manager", "start": "10:00", "end": "11:00" }
    ]
  }
```

---

## 6. Scale & Resilience

### Caching Strategy

```
Level 1: Calendar View Cache (Redis)
  Key:     calView:{user_id}:{week_start_date_utc}
  Value:   JSON serialized list of event summaries for that week
  TTL:     10 minutes
  Size:    ~50 KB per user per week (100 events x 500 bytes)
  
  Cache-aside pattern:
    Read: check Redis -> hit? return. Miss? query DB -> write to Redis -> return
    Write: on any event write for user_id -> DELETE calView:{user_id}:* (pattern delete)
    
  Hit rate estimate: user views same week 5 times -> 80% cache hit (4 of 5)

Level 2: Free/Busy Bitmap Cache (Redis above)
  Already described in 5.4

Level 3: Event Detail Cache (Redis)
  Key:   event:{event_id}
  TTL:   30 minutes
  Value: full event JSON (title, attendees, description, rrule, reminders...)
  
  Invalidation: on event update -> DEL event:{event_id}

Level 4: CDN Cache (Cloudflare / CloudFront)
  Public holiday calendars (Christmas, etc.) are perfectly cacheable
  Cache at edge for 24 hours -- these never change
  Serve to 2B users without hitting origin
```

### Failure Scenarios

| Failure | Detection | Impact | Recovery |
|---|---|---|---|
| **Spanner shard failure** | Health check + auto-retry | Events on that shard unreadable | Spanner auto-failover to replica in < 30s; transparent to clients |
| **Redis cache crash** | Connection timeout | Cache miss storm; DB spike | DB handles load (sized for 2× normal); Redis auto-restart; warm-up takes ~10 min |
| **Kafka lag** | consumer lag metric > 10K | Fan-out delayed; invites slow | Scale out fan-out worker pods; message retained 48h in Kafka |
| **Notification burst** | queue depth > 1M | Reminders delayed | Auto-scale FCM/APNS dispatcher pods; alert on > 60s delivery delay |
| **RRULE expansion timeout** | CPU time limit hit | Complex recurrence returns partial results | Max expansion = 3650 instances; return partial with `truncated: true` flag |
| **FreeBusy cache miss** | Cache cold start | Real-time DB queries | Acceptable; DB handles 160K reads/sec; cache warms up within minutes |

### Multi-Region Active-Active

```
Regions: US-EAST, EU-WEST, AP-SOUTHEAST

User data homed to closest region (based on account creation):
  Users in India: primary data in AP-SOUTHEAST
  Users in Germany: primary data in EU-WEST

Cross-region invites (Bob in US invites Alice in India):
  Event primary: stored in US-EAST (Bob's home region)
  Alice's UserInvites: written to AP-SOUTHEAST (Alice's home region)
    → Fan-out worker in US-EAST writes to AP-SOUTHEAST Spanner asynchronously
    → Cross-region write latency: ~100-200ms
    → Alice's calendar shows event within ~500ms of Bob creating it

Inter-region Kafka: MirrorMaker 2 replicates event-writes topic across regions
  → Each region's fan-out worker reads from local Kafka (regional mirror)
  → Async replication lag: < 500ms under normal conditions

Failover:
  If AP-SOUTHEAST goes down:
    → DNS fails over to EU-WEST (secondary replica)
    → Users get slightly stale data (last cross-region sync)
    → RPO: ~30 seconds (replication lag)
    → RTO: ~2 minutes (DNS TTL = 60s + health check delay)

Read-your-own-writes consistency:
  After Alice writes (RSVP), she must immediately see her own response.
  Solution: Route Alice's reads to her home region primary, not replicas.
  (Slightly higher latency for Alice's reads, but perfect consistency)
```

---

## 7. Trade-offs & Alternatives

### Decision Matrix

| Decision | Choice Made | Alternative Considered | Why This Choice |
|---|---|---|---|
| **Primary DB** | Cloud Spanner | Cassandra | Spanner: ACID for atomic event+attendee writes; Cassandra: no transactions |
| **Secondary DB (invites)** | Cassandra for UserInvites | Spanner globally | Cassandra: fast per-user reads; OK with eventual consistency for invites |
| **Recurring events** | Store RRULE, expand on read | Pre-expand all instances | 1 row vs potentially 1000s per series; DST handling is correct in wall-clock |
| **Fan-out method** | Async Kafka (for < 500 attendees) | Synchronous inline | Sync blocks API response; 50-attendee fan-out = 50ms added latency |
| **Large event fan-out** | Pull model (subscription) for > 500 | Always push | Write amplification: 50K attendees × every edit = unbounded writes |
| **Reminder storage** | Time-bucketed partitioned table | Redis sorted set | Partitioned table: unlimited scale, cheap; Redis: 500GB for 10B reminders |
| **Free/Busy** | Bitmap cache | Real-time DB per attendee | Bitmap: 12 bytes per user, O(1) group check; DB: O(N attendees × query) |
| **Conflict check** | Soft warning (not hard block) | HTTP 409 error | UX convention: calendar advises, human decides; double-booking is valid |
| **Auth** | OAuth 2.0 + per-calendar ACL | Simple API key | Google Workspace: need granular per-calendar sharing with 4 permission levels |

### What Interviewers Look For at SDE 3

```
1. Recognizing RRULE expansion complexity (not naive row-per-instance)
2. Knowing the three edit semantics (thisOnly/thisAndFollowing/allEvents)
3. Push vs pull fan-out based on attendee count threshold
4. Choosing Spanner for ACID across event + attendee tables
5. Bitmap cache for free/busy (not naive N-query approach)
6. Reminder burst problem at hour boundaries (25M/sec burst)
7. DST handling in RRULE expansion (wall-clock, not UTC)
8. Interleaved tables in Spanner for co-location of events with calendars
9. Multi-region: home region + cross-region async fan-out
10. Conflict detection SQL with overlap condition (A.start < B.end AND A.end > B.start)
```

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS (3 min)
  [ ] Asked about recurring event edit semantics (thisOnly/thisAndFollowing/all)
  [ ] Asked about free/busy privacy (who can see whose busy times)
  [ ] Clarified large event fan-out (company-wide vs personal invite)
  [ ] Asked about reminder precision (± seconds vs ± minutes)
  [ ] Stated offline mobile support is out of scope (unless prompted)

CAPACITY (3 min)
  [ ] Read RPS: 145K peak; Write RPS: 15K peak
  [ ] Storage: 10B events × 860B = 8.6 TB; with 3× replication: 26 TB
  [ ] Reminder burst: 25M reminders at 9 AM -> 416K notifications/sec
  [ ] Did NOT count recurring instances as separate rows in storage estimate

API (3 min)
  [ ] singleEvents=true parameter (expand recurring into instances)
  [ ] editScope parameter (thisOnly/thisAndFollowing/allEvents)
  [ ] freeBusy endpoint returns intervals, not full events
  [ ] ACL endpoint with role=reader|writer|owner|freeBusyReader

ARCHITECTURE (10 min)
  [ ] Kafka as async backbone between Calendar Service and fan-out
  [ ] Separate Event Store (Spanner ACID) from Inbox Store (Cassandra fast reads)
  [ ] Redis cache for calendar views and free/busy bitmaps
  [ ] Notification Service is stateless; scales independently

DEEP DIVES (18 min — pick 3)
  RRULE:
    [ ] Store RRULE not instances (1 row vs 730 rows for daily 2-year event)
    [ ] event_exceptions table: original_date + is_deleted + overrides JSONB
    [ ] Three edit semantics: RRULE UNTIL truncation + new series creation
    [ ] DST expansion in wall-clock time, NOT UTC
    [ ] RRULE edge cases: -1FR (last Friday), BYMONTHDAY=1,15, COUNT vs UNTIL
  
  Storage:
    [ ] Spanner over Cassandra: justified by ACID requirement
    [ ] Interleaved tables: Calendar -> Events -> EventAttendees co-located
    [ ] UserInvites table: reverse index, sorted by event_start_utc DESC
    [ ] Overlap index: (calendar_id, start_utc, end_utc) for conflict detection
  
  Fan-out:
    [ ] Async via Kafka (not synchronous in-request fan-out)
    [ ] Group expansion at fan-out time (not at invite time)
    [ ] Threshold: < 500 attendees = push to UserInvites; ≥ 500 = subscription pull
    [ ] RSVP: atomic update of EventAttendees + UserInvites in same Spanner txn
  
  Reminders:
    [ ] Time-bucketed partition table: only the current+next hour scanned
    [ ] Atomic CLAIM via status='pending'->'claimed' update (prevent double-fire)
    [ ] Horizontal scale of Notification Dispatcher for 9 AM burst
    [ ] Jitter: ±30s on reminder fire time to spread burst
  
  Free/Busy:
    [ ] Real-time multi-query for < 100 attendees (parallel, < 5ms)
    [ ] Bitmap cache (12 bytes per user per day) for large group scheduling
    [ ] Overlap SQL: A.start < B.end AND A.end > B.start (memorize this!)
    [ ] Soft conflict warning (not hard 409 block)

SCALE & RESILIENCE (4 min)
  [ ] Spanner auto-failover < 30s on shard failure
  [ ] Cache miss storm mitigation (DB sized for 2× normal load without cache)
  [ ] Multi-region: home region + async cross-region fan-out via Kafka MirrorMaker
  [ ] RRULE expansion: max instance limit (3650) to prevent CPU exhaustion

TRADE-OFFS (2 min)
  [ ] Spanner vs Cassandra
  [ ] Store rule vs expand instances
  [ ] Push vs pull fan-out for large events
  [ ] Bitmap cache vs real-time free/busy query
  [ ] Soft conflict warning vs hard block
```
