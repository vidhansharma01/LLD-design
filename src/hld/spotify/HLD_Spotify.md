# 🎧 High-Level Design (HLD) — Spotify (Music Streaming Platform)
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Audio Streaming, Licensing/DRM, Recommendation Engine, Offline Mode, CDN, Search

---

## 1. Requirements

### 1.1 Functional Requirements
- Users can **search and play music** (songs, albums, podcasts, playlists).
- **Personalized recommendations**: Discover Weekly, Daily Mixes, Radio.
- **Offline mode** — premium users can download songs for offline playback.
- Support **collaborative playlists** (multiple users edit one playlist).
- **Social features** — follow artists/friends, see what friends are listening to.
- **Cross-device playback** — start on mobile, continue on desktop seamlessly.
- **Lyrics** displayed in sync with song playback.
- **Podcasts** — streaming + download support.
- **Spotify Connect** — use phone as remote control for desktop/speaker.
- Audio quality tiers: Normal (96kbps) → High (160kbps) → Very High (320kbps).

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Startup latency** | First audio chunk playing < 250 ms |
| **Buffering** | < 0.5% buffering events during playback |
| **Availability** | 99.99% for playback; 99.9% for search/discovery |
| **Scale** | 600M MAU, 100M tracks, 50M daily listening hours |
| **Offline support** | Encrypted downloads; expire after 30 days without internet |
| **DRM** | Protected content — prevent unauthorized redistribution |

---

## 2. Capacity Estimation

```
MAU                    = 600 million; DAU = 250 million
Songs in catalog       = 100 million tracks
Avg song size (320kbps) = ~10 MB
Total catalog size     = 100M × 10MB = 1 PB (stored in multiple bitrates: ~3 PB)
Daily listens          = 250M users × 25 songs = 6.25 billion play events/day
Audio data served/day  = 6.25B × 3 min × 320kbps / 8 ≈ ~90 PB/day via CDN
Search queries/day     = 500 million → ~6K req/sec
Metadata reads         = Extremely high; 90%+ served from cache
```

---

## 3. High-Level Architecture

```
 Client (iOS / Android / Web / Desktop)
        │
        ├── Search / Browse / Playlist ──▶ API Gateway
        │                                       │
        └── Audio stream ──────────────▶ CDN Edge Node
                                              (returns audio chunks directly)
                                    ┌─────────────────┐
                    API Gateway ──▶ │  Backend Services│
                                    └────────┬────────┘
                                             │
              ┌──────────────────────────────┼───────────────────────────┐
              ▼                              ▼                           ▼
     ┌────────────────┐           ┌────────────────────┐      ┌──────────────────┐
     │  Music Catalog │           │  User Service       │      │  Recommendation  │
     │  Service       │           │  (Library, Follows) │      │  Engine          │
     └───────┬────────┘           └────────────────────┘      └──────────────────┘
             │
      ┌──────┴───────┐
      ▼              ▼
  ┌──────────┐  ┌──────────┐
  │  Search  │  │  Stream  │
  │  Service │  │  Service │
  │  (ES)    │  │          │
  └──────────┘  └────┬─────┘
                     │
              ┌──────▼──────────────────────┐
              │  Object Storage (S3)         │
              │  Audio files (multi-bitrate) │
              │  + DRM encrypted             │
              └──────────────────────────────┘
                     │
              ┌──────▼──────────────────────┐
              │  CDN (CloudFront / Akamai)   │
              │  Globally distributed audio  │
              └─────────────────────────────┘
```

---

## 4. Core Components

### 4.1 Audio Ingestion & Storage

**Ingest pipeline (when label uploads track):**
```
Raw audio file (WAV/FLAC) uploaded by label
        │
        ▼
Transcoding Service (FFmpeg cluster)
  → 320kbps MP3 / OGG Vorbis (Very High)
  → 160kbps OGG (High)
  →  96kbps OGG (Normal)
  →  24kbps OGG (Low — for slow connections)
        │
        ▼
DRM Encryption (Widevine / FairPlay per platform)
  → Each file encrypted with unique content key
  → Key stored in Key Management Service (KMS)
        │
        ▼
Upload to S3 (all 4 bitrates)
CDN pre-warms popular tracks to edge nodes
        │
        ▼
Metadata stored: title, artist, album, genre, duration, lyrics, ISRC code
```

### 4.2 Audio Streaming Service

**Startup latency target: < 250ms to first audio byte.**

```
Client requests track:
  GET /stream/{trackId}?quality=high&position=0

1. Auth check (premium vs free tier quality limits)
2. License check: does user have rights to play in their country?
3. Fetch CDN URL for track segment:
   → Pre-signed CDN URL (10-min TTL)
4. Return URL to client
5. Client fetches audio segments directly from CDN (bypasses Spotify servers)
6. DRM license fetch (separate call to KMS)
   → Client decrypts audio in memory — never touches disk unencrypted

Streaming format: chunked (30-sec segments) for progressive loading
Pre-buffering: client pre-fetches next 3 segments (next song already loading)
```

**Adaptive streaming:**
```
Client monitors buffer health every 5 sec:
  Buffer > 30 sec  → request higher quality next segment
  Buffer < 10 sec  → downgrade to lower quality
  Buffer < 5 sec   → switch to lowest quality immediately

Network-aware starting: detect bandwidth on connect → choose initial bitrate
```

### 4.3 Music Catalog & Metadata Service

```
Metadata DB: PostgreSQL (normalized: tracks, albums, artists, genres, labels)
Read cache:  Redis (track metadata TTL: 24hr; artist profile TTL: 1hr)

Track metadata (cached in Redis):
{
  trackId, title, artistId, albumId, duration,
  coverArtUrl, previewUrl, isrc, popularity_score,
  available_markets: [IN, US, UK, ...]  -- licensing per country
}

Popularity score:
  → Computed daily: f(play_count_24h, play_count_7d, saves, shares)
  → Used for search ranking and radio seeding
```

### 4.4 Search Service (Elasticsearch)

```
Indexed entities: tracks, albums, artists, playlists, podcasts, users

Query: "Shape of You"
Elasticsearch full-text search:
  {
    "multi_match": {
      "query": "Shape of You",
      "fields": ["title^3", "artist_name^2", "album_name", "lyrics"]
    }
  }

Result ranking:
  score = relevance_score × popularity_score × availability_in_user_country

Auto-complete:
  → Redis ZRANGEBYLEX for prefix matching (sub-5ms)
  → Backed by Elasticsearch for full query expansion

Indexing pipeline:
  → Kafka (TrackPublished) → Elasticsearch consumer → indexed in 30 sec
```

### 4.5 Recommendation Engine — Discover Weekly & Daily Mix

**Data signals used:**
- Explicit: likes, adds to playlist, saves, skips (negative signal).
- Implicit: listen completion rate, repeat listens, playlist adds.
- Context: time of day, device, activity (workout playlist behavior).

**Algorithms:**

```
1. Collaborative Filtering (primary):
   - Matrix factorization (ALS) on user-song interaction matrix
   - 600M users × 100M songs → factorized into 128-dim latent vectors
   - "Users with similar taste to you also loved…"

2. Content-based Audio Analysis:
   - Audio fingerprinting: BPM, key, energy, danceability, acousticness
     (Spotify's Audio Features API)
   - "This song has similar tempo/mood to your liked songs"

3. Natural Language Processing:
   - Scrape music blogs, tweets → understand cultural associations
   - "Indie folk artists similar to Bon Iver"

Discover Weekly (generated every Monday):
  - Offline batch job (Spark) runs Sunday night
  - 30-song playlist generated per user
  - Stored in playlist DB; delivered on app open Monday

Daily Mix (6 mixes, updated daily):
  - Clustered by genre/mood from user's library
  - Pre-computed per user; stored in personalized playlist table
```

### 4.6 Offline Mode (Downloads)

**Premium feature — encrypted local storage:**

```
User taps "Download" on playlist/album
        │
        ▼
Download Manager (client-side)
  → Fetch download token from server (validates premium status)
  → Download encrypted audio segments from CDN
  → Store encrypted file on device + local SQLite metadata DB

DRM enforcement:
  → License key expires after 30 days → must connect to internet to renew
  → If premium lapses → license not renewed → downloads unplayable
  → Files remain encrypted on device (useless without key)

Client-side DB (SQLite):
  → tracks downloaded, expiry timestamps, playlist mappings
  → Synced to server on reconnect (deleted downloads reflected)
```

### 4.7 Spotify Connect (Cross-Device Playback)

```
User has Spotify open on phone + laptop
  → Both register with Connect Service (WebSocket)
  → Phone sees laptop as "available device"
  → User taps "Play on Laptop"
  → Connect Service sends control command to laptop client
  → Laptop starts streaming; phone becomes remote control

Commands: Play, Pause, Seek, Skip, Volume, Queue
State sync: Track position, queue synced every 1 sec via WebSocket
```

### 4.8 Lyrics Service

```
Lyrics stored in DB with millisecond-level timestamps per line
Served via REST API: GET /lyrics/{trackId}
Client renders line highlighting synced to audio playback position
```

---

## 5. Data Models

### 5.1 User Library (Cassandra)
```sql
-- Liked songs
CREATE TABLE user_liked_songs (
    user_id    UUID,
    liked_at   TIMESTAMP,
    track_id   UUID,
    PRIMARY KEY (user_id, liked_at)
) WITH CLUSTERING ORDER BY (liked_at DESC);

-- User playlists
CREATE TABLE playlists (
    playlist_id   UUID PRIMARY KEY,
    owner_id      UUID,
    name          TEXT,
    is_public     BOOLEAN,
    collaborators LIST<UUID>,
    created_at    TIMESTAMP
);
```

### 5.2 Play Event Stream (Kafka → Data Warehouse)
```
PlayEvent {
  userId, trackId, startedAt, endDAt,
  percentCompleted, skippedAt (nullable),
  deviceType, country, qualityLevel
}
→ Kafka topic: play-events
→ Flink: aggregates → Recommendation Engine
→ Redshift: analytics, label royalty calculations
```

---

## 6. Licensing & Royalties

- Each play event recorded in data warehouse.
- **Royalties** calculated monthly: `(user_streams / total_streams) × royalty_pool`.
- **Geo-licensing**: track availability per country enforced at stream-request time by checking `available_markets` from metadata cache.
- **License expiry** for limited availability tracks handled by soft-delete + CDN cache purge.

---

## 7. Free vs Premium Tier

| Feature | Free | Premium |
|---|---|---|
| Audio quality | 96kbps | Up to 320kbps |
| Ads | Yes (every 3–4 songs) | No |
| Skip limits | 6 skips/hour | Unlimited |
| Offline downloads | No | Yes |
| On-demand playback | Limited | Full |

**Ad insertion:**
- Ad decision service called between tracks (free users).
- Returns ad audio URL → client plays before next song.
- Ad impression tracked → billing to advertiser.

---

## 8. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Audio format** | OGG Vorbis (+ MP3 for compatibility) | Better compression than MP3; not universally supported |
| **CDN strategy** | Pre-warm top 5M tracks globally | 99% cache hit; cold tracks add ~50ms origin latency |
| **DRM** | Widevine (Android/Web) + FairPlay (iOS) | Mandatory for label deals; adds ~20ms license fetch |
| **Rec engine** | Collaborative filtering + audio features | Handles cold start better than CF alone |
| **Metadata DB** | PostgreSQL (normalized) | Complex joins (track→album→artist); ACID important |
| **User library** | Cassandra | Write-heavy (likes, history); no joins needed |
| **Offline DRM** | Time-limited license key | Balance user convenience vs piracy prevention |
| **Adaptive bitrate** | Client-side buffer-based | No server coordination needed; works on all networks |

---

## 9. Scalability

| Layer | Scaling Strategy |
|---|---|
| CDN | 99%+ of audio served from edge; ~1PB/day at peak |
| Metadata cache | Redis cluster; 90%+ hit rate for popular tracks |
| Search | Elasticsearch sharded by entity type |
| Recommendation | Weekly Spark batch; 600M user jobs parallelized |
| Stream Service | Stateless; only issues CDN URLs; horizontally scaled |
| Play events | Kafka → Flink → horizontal consumer scaling |

---

## 10. Monitoring

| Metric | Alert |
|---|---|
| First audio byte latency | > 300ms P99 |
| Buffering rate | > 1% of play sessions |
| CDN cache hit rate | < 90% |
| DRM license fetch time | > 100ms |
| Search latency | > 100ms P99 |
| Recommendation job completion | > Monday 6AM (Discover Weekly miss) |

---

*Document prepared for SDE 3 system design interviews. Focus areas: adaptive audio streaming, DRM & encryption, CDN pre-warming, collaborative filtering for music recommendations, offline mode design, and cross-device Spotify Connect.*
