# 🎵 High-Level Design (HLD) — TikTok (Short-Video Platform)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Video Ingestion Pipeline, Feed Ranking (ML), CDN, Creator Economy, Real-time Interactions

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **upload short videos** (15 sec – 10 min).
- Users see a **personalized "For You" feed** — infinite scroll of recommended videos.
- Users can **like, comment, share, and follow** creators.
- Videos support **sounds/music tracks**, **effects**, and **text overlays**.
- **Live streaming** capability with real-time comments.
- **Search** for videos, sounds, hashtags, users.
- **Creator analytics** — views, likes, shares, follower growth per video.
- **Duet / Stitch** — record a video side-by-side or using a clip of another video.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Feed Latency** | < 200 ms to load first video; pre-buffered next 3 |
| **Upload Latency** | Video available for viewing within 30 sec of upload |
| **Availability** | 99.99% for feed; 99.9% for uploads |
| **Scale** | 1B+ DAU, 500M videos uploaded/day, 100B feed requests/day |
| **Video Quality** | Adaptive bitrate (ABR): 4K → 1080p → 720p → 360p based on bandwidth |

---

## 2. Capacity Estimation

```
DAU                    = 1 billion
Videos uploaded/day    = 500 million → ~5,800/sec avg, ~50K/sec peak
Avg video size (raw)   = 100 MB → 500M × 100MB = 50 PB/day (raw input)
After compression      ≈ 10 PB/day stored in multiple resolutions
Feed requests/day      = 100 billion → ~1.2M req/sec avg, ~5M/sec peak
Avg video watch time   = 30 sec → CDN must serve 100B × 30s worth of video/day
Comments + likes/day   = 5 billion interactions
```

---

## 3. High-Level Architecture

```
 Creator Side                            Viewer Side
 ┌────────────────┐                     ┌──────────────────────┐
 │ Mobile App     │                     │  Mobile App          │
 │ (record video) │                     │  (scroll For You feed│
 └───────┬────────┘                     └──────────┬───────────┘
         │                                         │
         ▼                                         ▼
  ┌──────────────┐                      ┌──────────────────────┐
  │  API Gateway │                      │   Feed Service        │
  │  (Auth, RL)  │                      │   (ranked video feed) │
  └──────┬───────┘                      └──────────┬───────────┘
         │                                         │
  ┌──────▼───────────────────┐         ┌───────────▼──────────┐
  │   Video Upload Service   │         │  Recommendation       │
  │   (pre-signed S3 upload) │         │  Engine (ML ranking)  │
  └──────┬───────────────────┘         └───────────┬──────────┘
         │                                         │
  ┌──────▼───────────────────┐         ┌───────────▼──────────┐
  │   Video Processing        │         │  Video Metadata DB    │
  │   Pipeline (async)        │         │  (Cassandra)          │
  │   Transcoding, Thumbnail  │         └──────────────────────┘
  │   Moderation, Indexing    │
  └──────┬───────────────────┘         ┌──────────────────────┐
         │                             │   CDN (CloudFront /   │
  ┌──────▼───────────────────┐         │   Akamai)             │
  │   Object Storage          │────────▶│   Video served globally│
  │   (S3 — multi-resolution) │         │   with ABR            │
  └───────────────────────────┘         └──────────────────────┘
         │
  ┌──────▼───────────────────┐
  │   Kafka Event Bus         │
  │   (interactions, uploads) │
  └──────────────────────────┘
```

---

## 4. Core Components

### 4.1 Video Upload & Processing Pipeline

```
Creator uploads video (mobile app)
        │
        ▼
Upload Service → issues pre-signed S3 URL
        │
Client uploads directly to S3 (bypasses servers)
        │
S3 Event → Kafka → Video Processing Workers
        │
┌───────┼─────────────────────────────────────┐
▼       ▼               ▼                     ▼
Transcode          Thumbnail           Content Moderation
(FFmpeg cluster)   Generation          (ML: violence, NSFW,
4K,1080p,720p,     (3 frames auto)      copyright detection)
360p, HLS chunks
        │
        ▼
Store in S3 (multi-resolution HLS segments)
        │
CDN origin updated → video publicly available (~30 sec total)
        │
Metadata stored in Cassandra
Kafka: VideoPublished event → search indexing, feed eligibility
```

**HLS (HTTP Live Streaming):**
- Video split into **10-second segments** per resolution.
- Client downloads manifest file (`.m3u8`) → fetches segments on demand.
- Enables **adaptive bitrate** (ABR) — client switches resolution based on bandwidth.

### 4.2 For You Page (FYP) — Recommendation Engine

**The defining feature of TikTok.** Two-stage retrieval + ranking:

```
Stage 1: Candidate Generation (retrieve ~1,000 candidates)
  ├── Collaborative filtering (users with similar history)
  ├── Content-based (similar to recently liked videos)
  ├── Trending / viral videos
  ├── Followed creators' recent posts
  └── Location-based trending

Stage 2: Ranking (score and sort 1,000 → top 20)
  ML Ranking Model (Two-Tower / Transformer-based):
  Features:
    - User: watch history, likes, shares, follows, location, device, time-of-day
    - Video: caption, hashtags, sound, like/share/comment rate, completion rate
    - Context: session length, previous watch duration
  Target metric: P(watch > 80%) × completion_rate + P(like) + P(share)

Stage 3: Diversification & Post-filtering
  - Avoid 5 consecutive videos from same creator
  - Diversity across hashtags
  - Filter videos user already watched
```

**Key insight:** TikTok's FYP gives **new creators** a chance — every video starts with a small test audience (500 views). High engagement → promoted to larger audience (10K → 1M). This "lottery" model is core to TikTok's growth.

### 4.3 Video Serving via CDN

```
Client requests video segment
  → Hit CDN edge node (90%+ cache hit for viral videos)
  → ABR player selects appropriate resolution
  → Pre-buffers next 2–3 segments

CDN Strategy:
  - Popular videos: replicated to all 200+ edge PoPs globally
  - Long-tail videos: fetched from S3 origin on demand + cached at edge
  - HLS segments TTL: 24 hours at edge
```

### 4.4 Social Graph & Interactions

```
Interactions DB:
  - Follows: Graph DB (Neo4j) or adjacency table (Cassandra)
  - Likes, Comments: Cassandra (partition by videoId)
  - Like count: Redis counter (real-time) + periodic DB sync

Follow Feed:
  - On follow, new videos from creator pushed to follower feed (fan-out on write)
  - For mega creators (>10M followers): fan-out on read (pull model)
  - Hybrid: push to active followers, pull for rest
```

### 4.5 Live Streaming

```
Creator starts live stream
  → RTMP stream pushed to ingest servers
  → HLS conversion in near real-time (~2-5 sec latency)
  → CDN distributes HLS to viewers

Real-time Comments:
  → WebSocket connections to comment servers
  → Kafka fan-out to all connected viewers

Gifts / virtual currency:
  → In-app purchase → payment service → creator analytics
```

### 4.6 Search

- **Elasticsearch** index: video captions, hashtags, sounds, usernames.
- Trending hashtags: Redis sorted set (score = interaction velocity in last 1h).
- Auto-complete: Trie stored in Redis.

---

## 5. Data Models

### 5.1 Video Metadata (Cassandra)
```sql
CREATE TABLE videos (
    video_id      UUID PRIMARY KEY,
    creator_id    UUID,
    caption       TEXT,
    hashtags      LIST<TEXT>,
    sound_id      UUID,
    duration_sec  INT,
    status        TEXT,  -- PROCESSING | ACTIVE | DELETED
    view_count    COUNTER,
    like_count    COUNTER,
    share_count   COUNTER,
    comment_count COUNTER,
    created_at    TIMESTAMP,
    s3_urls       MAP<TEXT, TEXT>  -- resolution → S3 HLS manifest URL
);
```

### 5.2 User Feed Cache (Redis)
```
Key:   feed:{userId}
Type:  LIST of videoIds (pre-ranked)
TTL:   1 hour
Ops:   LPUSH new videos, RPOP as user scrolls
```

---

## 6. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Upload path** | Pre-signed S3 (client direct) | Offloads bandwidth from servers; no progress control |
| **Feed ranking** | Two-stage: candidate gen + ML rank | High quality; 200ms latency acceptable |
| **Fan-out** | Hybrid push/pull by creator size | Balances write load vs read latency |
| **Video format** | HLS (chunked segments) | ABR support; slight startup overhead |
| **CDN** | Edge caching + origin S3 | 90%+ cache hit for trending; cost-effective |
| **New creator boost** | Small test cohort (500 users) | Fairness; enables viral discovery |
| **Live comments** | WebSocket | Real-time; connection overhead at scale |

---

## 7. Scalability & Failure Handling

| Layer | Strategy |
|---|---|
| Upload Service | Stateless; horizontal scale; S3 absorbs burst |
| Processing Workers | Auto-scaling consumer group on Kafka |
| Recommendation | Pre-computed feed in Redis; ANN index sharded |
| CDN | Multi-PoP global edge network |
| Cassandra | Partitioned by videoId; geo-replicated |
| Live streaming | Ingest servers horizontally scaled; WebSocket load balanced |

---

## 8. Monitoring

| Metric | Alert |
|---|---|
| Video processing time | > 60 sec end-to-end |
| Feed latency (P99) | > 300ms |
| CDN cache hit rate | < 85% |
| Content moderation backlog | > 10K unreviewed |
| Live stream ingest lag | > 5 sec |

---

*Document prepared for SDE 3 system design interviews. Focus areas: video processing pipeline, FYP ML ranking (two-stage retrieval), HLS adaptive streaming, CDN architecture, and fan-out strategies.*
