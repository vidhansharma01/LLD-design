# 📺 High-Level Design (HLD) — YouTube (Video Streaming Platform)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Video Ingestion Pipeline, Adaptive Streaming (HLS/DASH), CDN Architecture, Recommendation Engine, Search, Scalability

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **upload videos** (up to 12 hours / 256 GB raw).
- Videos are **processed and available** for streaming within minutes of upload.
- Users can **stream videos** in multiple resolutions (144p – 4K) with adaptive bitrate.
- Users can **search** for videos by title, description, channel, tags.
- **Personalized homepage** — ranked video recommendations.
- Users can **like, dislike, comment, subscribe** to channels.
- **Watch history** and **resume playback** across devices.
- Support **live streaming** with real-time chat.
- **Creator analytics** — views, watch time, revenue, audience retention.
- Support **captions / subtitles** (auto-generated + manual).

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Video startup latency** | < 2 sec to first frame |
| **Upload availability** | Video playable within 5 min of upload completion |
| **Streaming quality** | Adaptive bitrate; < 1% buffering events |
| **Availability** | 99.99% for streaming; 99.9% for uploads |
| **Scale** | 2B DAU, 500 hours of video uploaded/min, 1B hours watched/day |
| **Search latency** | < 200 ms |
| **Storage** | ~1 exabyte total video storage |

### 1.3 Out of Scope
- Ad serving / monetization pipeline
- Creator payment / revenue calculation
- YouTube Kids content filtering

---

## 2. Capacity Estimation

```
DAU                         = 2 billion
Uploads/min                 = 500 hours of video = 30,000 min of video/min
                            → ~3,600 videos/min uploaded (avg 8-min video)
Video size raw (8 min HD)   ≈ 1.5 GB
Uploads storage/day         = 3,600/min × 1,440 min × 1.5 GB = ~7.8 PB/day (raw)
After transcoding (multi-res) ≈ 5× compression → ~40 PB stored/day
Watch hours/day             = 1 billion hours = 60B min/day → ~700M GB/day of video served
CDN must serve              ≈ ~700 million GB/day ≈ ~8 TB/sec peak throughput
Comments/day                = 500 million
Search queries/day          = 3 billion → ~35K/sec
```

---

## 3. High-Level Architecture

```
 Creator (Upload)                    Viewer (Stream)
      │                                     │
      ▼                                     ▼
 ┌──────────────┐                  ┌────────────────────┐
 │  Upload      │                  │   API Gateway      │
 │  Service     │                  │   (Auth, RL)       │
 └──────┬───────┘                  └─────────┬──────────┘
        │                                    │
        ▼                           ┌────────┴────────────────────────┐
 ┌──────────────────────┐           ▼                                 ▼
 │  Raw Video Storage   │   ┌──────────────┐                ┌──────────────────┐
 │  (S3)                │   │  Video       │                │  Recommendation  │
 └──────┬───────────────┘   │  Service     │                │  Engine          │
        │                   │  (metadata,  │                └──────────────────┘
        ▼                   │  streaming   │
 ┌──────────────────────┐   │  URLs)       │                ┌──────────────────┐
 │  Video Processing    │   └──────┬───────┘                │  Search Service  │
 │  Pipeline (async)    │          │                         │  (Elasticsearch) │
 │  - Transcoding       │          ▼                         └──────────────────┘
 │  - Thumbnail gen     │   ┌──────────────┐
 │  - Subtitle gen      │   │  CDN         │  (served globally, ABR streaming)
 │  - Moderation        │   │  (CloudFront │
 └──────┬───────────────┘   │  / Akamai)   │
        │                   └──────────────┘
        ▼
 ┌──────────────────────┐
 │  Processed Video     │
 │  Storage (S3)        │
 │  Multi-res HLS/DASH  │
 └──────────────────────┘
        │
        ▼
 ┌──────────────────────┐
 │  Kafka Event Bus     │
 │  (VideoPublished,    │
 │   ViewEvent, Likes)  │
 └──────────────────────┘
```

---

## 4. Video Upload & Processing Pipeline

### 4.1 Upload Flow

```
Creator (YouTube Studio app)
        │
        ▼
Upload Service:
  1. Auth: validate creator account active
  2. Validate: file format (MP4, MOV, AVI, MKV...), size < 256 GB
  3. Issue pre-signed S3 URL (multipart upload for large files)
  4. Client uploads directly to S3 (bypasses YouTube servers)
     → Chunked upload: 8 MB chunks, resumable if interrupted
  5. S3 triggers SNS → SQS → VideoProcessingWorker
  6. Return videoId = Snowflake ID to creator
  7. Video status = "PROCESSING" — shown on YouTube Studio
```

**Resumable upload (critical for large files):**
```
Client: POST /upload/init → gets uploadSessionId + S3 multipart uploadId
Client: PUT /upload/{uploadSessionId}?partNumber=1 (8 MB chunk)
Client: PUT /upload/{uploadSessionId}?partNumber=2 ...
Client: POST /upload/{uploadSessionId}/complete → triggers S3 multipart complete
→ Survives network interruption; client resumes from last uploaded chunk
```

### 4.2 Video Processing Pipeline

```
Raw video arrives in S3
        │
        ▼
Kafka: VideoUploaded event
  → Processing Orchestrator (Temporal / AWS Step Functions)
        │
   ┌────┴──────────────────────────────────┐ (parallel workers)
   ▼             ▼              ▼          ▼
Transcoding  Thumbnail     Auto-Caption  Content
(FFmpeg)     Generation    (Speech-to-   Moderation
             (3 frames)    text AI)      (NSFW/Copyright)
   │
   ▼
Target resolutions (HLS + DASH output):
  4K (2160p) → ~20 Mbps
  1080p Full HD → ~8 Mbps
  720p HD → ~5 Mbps
  480p → ~2.5 Mbps
  360p → ~1 Mbps
  240p → ~0.5 Mbps
  144p → ~0.15 Mbps
        │
Each resolution → split into 10-second HLS chunks → stored in S3
        │
        ▼
S3 path: s3://yt-videos/{videoId}/{resolution}/{segment_N}.ts
         s3://yt-videos/{videoId}/manifest.m3u8  (master playlist)
        │
        ▼
CDN origin updated → video publicly streamable
Kafka: VideoProcessed → Search indexing, recommendation eligibility
Video status = "PUBLISHED"
Total Pipeline Time: ~3–5 min for 8-min HD video
```

**Why parallel workers?**
- Transcoding is CPU-bound; different resolutions processed simultaneously.
- 4K takes ~10 min per video; 360p takes 30 sec — don't let 4K block 360p.

---

## 5. Adaptive Bitrate Streaming (ABR)

**The key to smooth playback on any network.**

### 5.1 HLS (HTTP Live Streaming) — Apple/Mobile

```
Master Playlist (manifest.m3u8):
  #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080
  1080p/playlist.m3u8

  #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1280x720
  720p/playlist.m3u8

  #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360
  360p/playlist.m3u8

Individual resolution playlist (720p/playlist.m3u8):
  #EXTINF:10.0,
  segment_0.ts
  #EXTINF:10.0,
  segment_1.ts
  ...
```

### 5.2 Client ABR Logic

```
Client (video player) monitors buffer health every 2 sec:

Buffer Level    → Action
> 30 sec        → upgrade to next higher resolution
10–30 sec       → maintain current resolution
5–10 sec        → downgrade one resolution
< 5 sec         → immediately drop to lowest available
< 2 sec         → buffering event (user sees spinner)

Network estimation:
  Measure bandwidth of each segment download → rolling average
  Estimated bandwidth < 5 Mbps → cap at 720p
  Estimated bandwidth < 1.5 Mbps → cap at 360p
```

### 5.3 Video Serving via CDN

```
Client → CDN Edge PoP (nearest geographic node)
          │
          Cache HIT (>90% for popular videos) → serve immediately
          │
          Cache MISS → fetch from S3 origin, cache at edge (TTL: 24hr)

CDN strategy:
  - Top 1M most-watched videos: pre-pushed to all PoPs globally (~200 PoPs)
  - Long-tail: fetched from origin on first request per region, cached locally
  - Segments served with Range header support (partial content for seeking)

Seek optimization:
  User skips to 5:30 → client fetches segment containing 5:30
  Pre-buffers next 3 segments from that point
  No need to re-download earlier segments
```

---

## 6. Core Services

### 6.1 Video Metadata Service

```sql
-- PostgreSQL (relational — video has many relationships)
CREATE TABLE videos (
    video_id        BIGINT PRIMARY KEY,    -- Snowflake ID
    channel_id      UUID NOT NULL,
    title           TEXT,
    description     TEXT,
    tags            TEXT[],
    duration_sec    INT,
    status          TEXT,   -- PROCESSING|PUBLISHED|DELETED|PRIVATE
    visibility      TEXT,   -- PUBLIC|UNLISTED|PRIVATE
    category        TEXT,
    default_lang    TEXT,
    view_count      BIGINT,
    like_count      INT,
    dislike_count   INT,
    thumbnail_url   TEXT,
    published_at    TIMESTAMP
);

CREATE TABLE video_streams (
    video_id        BIGINT REFERENCES videos,
    resolution      TEXT,   -- '1080p','720p','360p'...
    bitrate_kbps    INT,
    hls_url         TEXT,   -- CDN URL for m3u8 playlist
    file_size_bytes BIGINT,
    PRIMARY KEY (video_id, resolution)
);

CREATE TABLE video_captions (
    video_id  BIGINT,
    lang_code TEXT,
    vtt_url   TEXT,         -- CDN URL for WebVTT caption file
    PRIMARY KEY (video_id, lang_code)
);
```

**Hot metadata cached in Redis:**
```
Key:   video:{videoId}
Type:  HASH
Fields: title, channelId, duration, thumbnailUrl, viewCount, likeCount, hlsUrl
TTL:   1 hr (refreshed on view events)
```

### 6.2 View Count & Engagement Tracking

```
Problem: 1B views/day = ~11,500 view events/sec
         Direct DB writes → kills PostgreSQL

Solution: Approximate counting with eventual consistency

Client → POST /view { videoId, watchDurationSec, percentWatched }
        │
        ▼
Kafka topic: view-events  (partitioned by videoId)
        │
        ▼
Flink streaming job (1-min tumbling window):
  Aggregate view counts → batch INCRBY to Redis:
  INCRBY viewcount:{videoId} N
        │
        ▼
Hourly DB sync job:
  Flush Redis counters → UPDATE videos SET view_count = view_count + N

Likes / Dislikes:
  Redis SET per user (dedup): liked:{userId}:{videoId}
  INCR/DECR like_count:{videoId}
  Synced to DB every 5 min
```

### 6.3 Search Service

```
Elasticsearch index: videos
  Indexed fields: title (boost=3), description (boost=1), tags (boost=2),
                  channel_name (boost=2), transcript (boost=0.5)

Query: "machine learning tutorial python 2024"
  → Multi-match across indexed fields
  → Score = text_relevance × popularity_score × recency_factor

Ranking signals:
  - View count velocity (views in last 7 days)
  - Watch time (avg % watched — quality signal)
  - Like/dislike ratio
  - Channel authority (subscriber count)
  - Upload recency

Indexing pipeline:
  Kafka (VideoProcessed) → ES consumer → indexed in < 60 sec

Autocomplete:
  Redis ZSET per prefix (see Search Autocomplete HLD)
```

### 6.4 Recommendation Engine

**YouTube's recommendation is the most sophisticated in the world — drives 70% of watch time.**

**Two-stage pipeline (same as described in our other HLDs):**

```
Stage 1: Candidate Generation (~1,000 videos)
  Sources:
  a) Collaborative filtering: users with similar watch history liked these
  b) Content-based: similar to recently watched (title, tags, category embedding)
  c) Subscriptions: latest videos from subscribed channels
  d) Trending (globally + per category + per location)
  e) Previously interrupted videos (resume watching)

Stage 2: Ranking (1,000 → top 20 homepage cards)
  Deep Neural Network (YouTube's DNN Ranking paper, 2016):
  Features:
    User: watch history embeddings, search history, demographics, location
    Video: view count, like ratio, avg watch %, upload recency, category
    Context: device, time of day, previous video in session
  Target: Maximize: P(watch > 50%) × watch_duration + P(like) + P(subscribe)

Stage 3: Post-processing
  - Diversity: no 3 consecutive videos from same channel
  - Freshness boost: at least 2 videos < 24 hr old on homepage
  - Filter: already-watched videos (optional, configurable)
  - Safe mode filtering for restricted users
```

**Watch history → embedding:**
```
Each video encoded as 256-dim embedding via Word2Vec-style training
User embedding = avg(embeddings of last 50 watched videos)
→ Enables fast ANN lookup for candidate generation
```

### 6.5 Watch History & Resume Playback

```
On every 10 sec of playback:
  Client sends: PATCH /history { videoId, watchPosition: 324, percentWatched: 45 }
    → Kafka: WatchProgress event
    → Flink: upsert to watch_history (last position per user per video)

Resume playback:
  GET /video/{videoId}/context?userId=U123
  → Returns: { lastPosition: 324, percentWatched: 45 }
  → Player seeks to 324 sec on load

Watch history stored in:
  Redis: history:{userId} → ZSET of {videoId: last_watch_timestamp} (last 200 videos)
  DynamoDB: long-term history (userId, videoId) → full record
  TTL: Redis 30 days; DynamoDB permanent (user-controlled clear)
```

### 6.6 Live Streaming

```
Creator starts live stream:
  → OBS / YouTube Studio → RTMP stream to Ingest Server
  → Ingest server → transcodes to HLS (near real-time, ~2-4 sec latency)
  → HLS chunks (.ts) → S3 → CDN
  → Viewers fetch latest HLS chunks with short TTL (2 sec freshness)

Live chat:
  → WebSocket connections to Chat Service
  → Each message → Kafka → fan-out to all chat subscribers in room
  → Redis: chat:{liveVideoId} → active WebSocket sessions
  → Rate limit: 1 message / 3 sec per user (prevent spam)

Super Chats (paid messages):
  → Payment flow → prioritized pinned display in chat
```

---

## 7. Content Moderation Pipeline

```
On video upload:
  1. Copyright detection:
     Content ID: audio/video fingerprinting against rights-holder library
     If match → auto-mute / block / revenue share per policy

  2. NSFW / violence detection:
     ML classifier on video frames (sampled every 2 sec)
     Score > 0.9 safe → PUBLISHED
     Score 0.5–0.9 → human review queue (SLA: 24 hr)
     Score < 0.5 → AUTO_REMOVED

  3. Spam/misleading metadata:
     NLP classifier on title + description + tags

  4. Community guidelines violations:
     Post-publish: user reports → human review → strike / removal
```

---

## 8. CDN & Storage Architecture

```
Storage tiers:
  HOT   (< 30 days, frequent access) → S3 Standard
  WARM  (30–180 days, occasional)    → S3 Standard-IA
  COLD  (> 180 days, rare)           → S3 Glacier
  ARCHIVE (> 5 years)                → S3 Deep Archive

Cost optimization:
  Top 1% of videos = 95% of watch time → keep in HOT tier
  Long-tail: auto-tiered based on access frequency
  CDN caches top videos at edge → reduces S3 egress costs significantly

CDN PoP strategy:
  200+ global PoPs (CloudFront / Akamai)
  US: 40 PoPs, Europe: 30 PoPs, India: 15 PoPs, etc.
  Popular videos pre-warmed to all PoPs on publish
  Live streams: special low-latency edge nodes
```

---

## 9. Scalability

| Layer | Strategy |
|---|---|
| **Upload** | S3 multipart; pre-signed URLs; bypass app servers |
| **Processing** | Auto-scaling transcoding workers (GPU instances for 4K) |
| **CDN** | 90%+ of video bytes served from edge; transparent to origin |
| **Metadata reads** | Redis cache (1hr TTL); < 5% DB reads |
| **View counts** | Kafka + Flink batching; avoid per-view DB write |
| **Search** | Elasticsearch sharded by content category |
| **Recommendations** | Pre-computed per user; daily Spark batch + real-time session update |
| **Live chat** | Redis PubSub + horizontal WebSocket servers |

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Upload** | Pre-signed S3 + resumable multipart | No YouTube server bandwidth used; complex client retry logic |
| **Processing** | Async pipeline (Kafka + workflow engine) | Video not instant; ~5 min delay acceptable |
| **Streaming format** | HLS + DASH | Maximum device compatibility; playlist overhead |
| **CDN** | Multi-PoP global edge | 90%+ cache hit; cache invalidation complexity on deletion |
| **View counts** | Eventually consistent (Kafka → Redis → DB sync) | Slight undercounting; ~minutes to hours delay is fine |
| **Recommendations** | Two-stage DNN (candidate gen + ranking) | Best quality; requires significant ML infrastructure |
| **Storage tiering** | S3 Standard → IA → Glacier by age | 10× cost reduction for long-tail old videos |
| **ABR algorithm** | Buffer-based (BOLA) | Minimizes rebuffering; slight quality oscillation |

---

## 11. Monitoring & Observability

| Metric | Alert |
|---|---|
| **Video startup time** | P99 > 3 sec |
| **Buffering ratio** | > 1% of playback sessions |
| **Processing pipeline lag** | Video > 10 min in PROCESSING state |
| **CDN cache hit rate** | < 85% |
| **Upload failure rate** | > 2% |
| **Copyright false positive rate** | Content ID blocking legitimate content |
| **Live stream ingest lag** | > 6 sec (viewer latency) |

---

## 12. Future Enhancements
- **YouTube Shorts** — separate vertical video feed optimized for mobile (< 60 sec, TikTok-style FYP).
- **8K / 360° / VR streaming** — requires specialized encoding and player.
- **AI video chapters** — auto-detect scene changes → generate chapter titles from transcript.
- **Offline download** (Premium) — DRM-encrypted download similar to Spotify's model.
- **Real-time translation** — live dubbing of videos into user's language via AI.

---

*Document prepared for SDE 3 system design interviews. Focus areas: video processing pipeline (FFmpeg + HLS), adaptive bitrate streaming, CDN architecture, two-stage recommendation (DNN), view count batching, live streaming, and S3 storage tiering.*
