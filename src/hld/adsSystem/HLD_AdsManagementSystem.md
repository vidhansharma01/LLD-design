# HLD — Ads Management & Display System for Social Feed

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** Ad Tech / Real-Time Auction / ML Ranking / Distributed Counters
> **Real-world Analogues:** Facebook Ads, Instagram Ads, Twitter/X Promoted Posts, LinkedIn Ads
> **The Core Problem:** In < 100ms, run a real-time auction across millions of eligible ads, rank them by predicted click probability × bid price, enforce budget constraints, and insert the winning ad into the user's feed — at 1 million feed loads per second.

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
   - 5.1 [Ad Auction — Second-Price Auction & Real-Time Bidding](#51-ad-auction--second-price-auction--real-time-bidding)
   - 5.2 [Ad Targeting — Candidate Retrieval](#52-ad-targeting--candidate-retrieval)
   - 5.3 [Ad Ranking — Relevance Score × Bid](#53-ad-ranking--relevance-score--bid)
   - 5.4 [Budget Pacing & Spend Management](#54-budget-pacing--spend-management)
   - 5.5 [Impression & Click Tracking at Scale](#55-impression--click-tracking-at-scale)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Ad Tech Terminology (Know Before Interview)

```
ADVERTISER:      Company buying ads (Nike, Amazon, Zomato)
PUBLISHER:       Platform showing ads (Instagram, Twitter — us)
CAMPAIGN:        Advertiser's top-level container (Q4 Sports Campaign)
AD SET:          Sub-group with budget, schedule, targeting (18-35 yr, Mumbai)
AD / CREATIVE:   Actual content shown (image, video, carousel, text)
IMPRESSION:      Ad is shown to a user (view, not necessarily clicked)
CLICK:           User taps the ad
CTR:             Click-Through Rate = Clicks / Impressions
CPM:             Cost Per Mille = cost per 1,000 impressions
CPC:             Cost Per Click  
CPE:             Cost Per Engagement (comment, share, save)
eCPM:            Effective CPM = (Clicks × CPC / Impressions) × 1000
AUCTION:         Competition among ads for a single slot
SECOND-PRICE:    Winner pays 2nd-highest bid price + ε (Vickrey auction)
pCTR:            Predicted Click-Through Rate (ML model output)
RELEVANCE SCORE: Platform quality score (pCTR × other engagement signals)
AD RANK:         Final score = bid × pCTR × ad quality factors
BUDGET PACING:   Distributing ad spend over campaign duration smoothly
FREQUENCY CAP:   Max times same user sees same ad (e.g., 3 times/day)
```

### Clarifying Questions

```
Q1: "Are we building the full ad stack (campaign management + auction +
     serving + billing + reporting) or focusing on the critical path:
     ad selection and insertion into the feed?"
     → Full stack; go deep on auction + serving + budget pacing.

Q2: "Is this first-party auction (only our advertisers) or open RTB
     (Real-Time Bidding — external ad networks like Google, OpenX compete)?"
     → Start with first-party; mention RTB as extension.

Q3: "What ad formats do we support? Image, video, carousel, story?"
     → Feed ads: image + video (most common). Carousel as stretch.

Q4: "What billing model? CPM (pay per impression), CPC (pay per click),
     or CPA (pay per action/conversion)?"
     → Support all three; auction normalizes to eCPM for comparison.

Q5: "How many ad slots per feed page load?"
     → Typically 1 ad per N organic posts (e.g., 1 ad per 5 posts = 20%)

Q6: "Do we need real-time reporting or is T+1 batch reporting OK?"
     → Real-time dashboards matter for advertisers managing budgets.
```

### Functional Requirements

| # | Requirement | Key Challenge |
|---|---|---|
| FR-1 | Advertiser creates **campaign → ad set → ad** (with targeting, budget, schedule) | Campaign management UI + backend |
| FR-2 | System **selects 1-3 winner ads** for every feed load (< 100ms) | Real-time auction at millions/sec |
| FR-3 | Ads targeted by **demographics**, interests, behavior, location, device | Candidate retrieval from 10M+ active ads |
| FR-4 | **Budget enforcement**: never exceed daily/lifetime budget | Distributed rate-limiting under 1M+ RPS |
| FR-5 | **Frequency cap**: user sees same ad max N times per day | Per-user, per-ad state tracking |
| FR-6 | **Impression & click tracking**: count accurately; support billing | Exactly-once counting at massive scale |
| FR-7 | **Advertiser dashboard**: real-time spend, impressions, CTR, CPC | Near-real-time aggregation pipeline |
| FR-8 | **Ad quality / review**: human + automated content moderation | Pre-serving; not on critical path |
| FR-9 | **A/B testing**: different ad creatives compete; winner scales | Multi-armed bandit or fixed split |
| FR-10 | **Billing**: charge advertiser at end of day or on click | Invoicing + payment integration |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Ad selection latency** | p99 < 100ms (within feed load < 500ms total) |
| **Feed load RPS** | 1M feed loads/sec (1B users × 10 loads/day / 86400) |
| **Active ads** | 10M active ads across all campaigns |
| **Impression tracking** | 1B impressions/day; exactly-once; idempotent |
| **Budget accuracy** | Overspend < 10% of daily budget (pacing tolerance) |
| **Availability** | 99.99% — ad system revenue-critical |
| **Reporting freshness** | Dashboard lag < 5 minutes |

---

## 2. Capacity Estimation

```
Platform scale:
  Users: 1B total; 500M DAU
  Feed loads: 500M DAU × 10 loads/day = 5B feed loads/day
  Sustained: 5B / 86400 = 58K feed loads/sec
  Peak (prime time): 3× = 174K feed loads/sec

Ad impressions:
  1 ad shown per 5 organic posts; avg feed = 20 posts → 4 ads per load
  5B loads/day × 4 ads = 20B impressions/day = 231K impressions/sec
  Peak: 3× = 693K impressions/sec

Auction computation:
  Each feed load triggers 4 mini-auctions (4 ad slots)
  58K loads/sec × 4 slots = 232K auctions/sec
  Each auction: retrieve candidates + score + rank + enforce budget
  p99 latency target: 100ms total

Click tracking:
  Industry avg CTR: 0.5-1%
  231K impressions/sec × 0.8% = 1,848 clicks/sec (manageable)
  But impression tracking at 231K/sec is the scale challenge

Active campaigns:
  100K advertisers × avg 3 campaigns × 5 ad sets × 10 ads = 15M ads
  Active (not paused, not budget exhausted): ~10M active ads

Budget management:
  $50M/day ad revenue (mid-size social platform)
  Avg $500/day budget per campaign
  100K active campaigns; budget state checked 231K times/sec

Storage:
  Campaign metadata: 100K campaigns × 10 KB = ~1 GB
  Active ad index (targeting params): 10M ads × 200 bytes = 2 GB → in Redis
  Impression log: 20B/day × 50 bytes = 1 TB/day (cold storage S3)
  Budget state (spend per campaign): 100K × 8 bytes = 800 KB → easily in Redis
  Frequency cap state: 500M users × 100 seen ads × 8 bytes = 400 GB
    → Too large for single Redis → sharded by user_id
```

---

## 3. API Design

### Advertiser Campaign Management APIs

```http
# ============================================================
# CREATE CAMPAIGN
# ============================================================
POST /v1/advertisers/{advertiser_id}/campaigns
Authorization: Bearer {advertiser_token}

{
  "name":        "Summer Sale 2026",
  "objective":   "TRAFFIC",               # TRAFFIC | AWARENESS | CONVERSIONS | APP_INSTALL
  "status":      "ACTIVE",
  "lifetime_budget": 50000,               # in cents ($500 USD)
  "start_date":  "2026-05-01",
  "end_date":    "2026-08-31"
}
Response 201: { "campaign_id": "camp_xyz", ... }


# ============================================================
# CREATE AD SET (targeting + daily budget)
# ============================================================
POST /v1/campaigns/{campaign_id}/ad-sets

{
  "name":            "18-30 Urban Women",
  "daily_budget":    10000,               # cents ($100/day)
  "billing_event":   "IMPRESSIONS",       # IMPRESSIONS | CLICKS | CONVERSIONS
  "bid_amount":      50,                  # cents (max bid per 1000 impressions for CPM)
  "bid_strategy":    "LOWEST_COST",       # LOWEST_COST | TARGET_CPA | MANUAL_CPC
  "targeting": {
    "age_min": 18, "age_max": 30,
    "genders":    ["female"],
    "locations":  [{ "type": "city", "id": "mumbai" }, { "type": "city", "id": "delhi" }],
    "interests":  ["fashion", "beauty", "fitness"],
    "behaviors":  ["online_shoppers", "luxury_buyers"],
    "device_types": ["mobile"],
    "os":         ["ios", "android"],
    "languages":  ["en", "hi"],
    "excluded_audiences": ["existing_customers_lookalike"],
    "custom_audience": "aud_lookalike_top20pct"   # uploaded customer list
  },
  "frequency_cap": { "impressions": 3, "interval_days": 1 },
  "schedule": {
    "timezone": "Asia/Kolkata",
    "days":     ["MON","TUE","WED","THU","FRI"],
    "hours":    [8, 9, 10, 17, 18, 19, 20, 21]  # show only these hours
  }
}


# ============================================================
# CREATE AD CREATIVE
# ============================================================
POST /v1/ad-sets/{ad_set_id}/ads

{
  "name":       "Summer Sale Banner v2",
  "format":     "image",                 # image | video | carousel | stories
  "creative": {
    "headline":  "Up to 50% OFF This Summer!",
    "body":      "Shop the hottest deals before they're gone.",
    "image_url": "https://cdn.example.com/ads/summer_sale_v2.jpg",
    "cta":       "SHOP_NOW",             # button text
    "destination_url": "https://brand.com/summer-sale?utm_source=social",
    "video_url": null
  },
  "status": "PENDING_REVIEW"             # newly created; must pass content review
}


# ============================================================
# REPORTING API (near real-time)
# ============================================================
GET /v1/campaigns/{campaign_id}/insights
    ?date_from=2026-05-01
    &date_to=2026-05-07
    &granularity=day                     # hour | day | week
    &breakdown=age,gender                # optional demographic breakdown

Response 200:
{
  "data": [
    {
      "date":        "2026-05-01",
      "impressions": 124500,
      "clicks":      1120,
      "ctr":         0.009,              # 0.9%
      "spend":       5623,               # cents
      "cpm":         45.14,              # effective CPM
      "cpc":         5.02,               # average CPC
      "reach":       98200,              # unique users who saw ad
      "frequency":   1.27               # avg times each user saw it
    }
  ],
  "total": { "spend": 39361, "impressions": 871500, ... }
}


# ============================================================
# INTERNAL: AD SELECTION API (called by Feed Service per load)
# ============================================================
POST /v1/internal/ad-auction
{
  "user_id":       "usr_abc123",
  "session_id":    "sess_xyz789",
  "slots":         2,                   # how many ads to fill in this feed
  "user_context": {
    "age":          25,
    "gender":       "F",
    "location":     { "city": "mumbai", "country": "IN" },
    "interests":    ["fashion", "beauty", "travel"],
    "device":       "mobile",
    "os":           "ios",
    "language":     "en",
    "feed_position": [3, 8]             # insert ad after post #3 and #8
  },
  "exclusions": {
    "recently_seen_ad_ids": ["ad_001", "ad_002"],   # from frequency cap
    "advertiser_exclusions": ["adv_blocked"]
  }
}

Response 200:
{
  "winning_ads": [
    {
      "ad_id":           "ad_456",
      "ad_set_id":       "adsset_789",
      "campaign_id":     "camp_xyz",
      "creative":        { ...ad content... },
      "impression_token":"imp_token_abc",   # signed token; used to track impression later
      "clearing_price":  42,               # cents per 1000 impressions (2nd price)
      "slot":            1,
      "reason":          "AUCTION_WINNER"
    }
  ]
}
```

---

## 4. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                   ADVERTISER PLANE (Campaign Management)                     │
│   Advertiser UI/API → Campaign CRUD → Ad Review → Budget Setup               │
└────────────────────────────────┬─────────────────────────────────────────────┘
                                 │
                                 ▼
            ┌────────────────────────────────────────┐
            │         CAMPAIGN MANAGEMENT SERVICE    │
            │  • Campaign / AdSet / Ad CRUD          │
            │  • Content moderation (human + ML)     │
            │  • Audience builder & sync             │
            │  • Budget allocation across ad sets    │
            └───────────────┬────────────────────────┘
                            │ Write active ads to index
                            ▼
            ┌───────────────────────────────────────────┐
            │           AD INDEX / STORE                │
            │   PostgreSQL (source of truth)             │
            │   + Redis (active targeting index in RAM)  │
            │   + Elasticsearch (audience search)        │
            └───────────────┬───────────────────────────┘
                            │
═══════════════════════════ SERVING PLANE (Critical Path < 100ms) ═══════════
                            │
┌───────────────────────────┼──────────────────────────────────────────────────┐
│  FEED SERVICE              │                                                 │
│  (User opens Instagram     │                                                 │
│   feed → needs ads)        │                                                 │
└───────────────────────────┘                                                 │
               │ POST /internal/ad-auction                                    │
               ▼                                                              │
┌──────────────────────────────────────────────────────────────────────────┐  │
│                      AD SELECTION SERVICE                                │  │
│                                                                          │  │
│  Step 1: CANDIDATE RETRIEVAL (< 10ms)                                    │  │
│    • Read user context (age, gender, city, interests, device)            │  │
│    • Query Redis ad index: find eligible ads matching targeting           │  │
│    • Apply frequency cap filter (Redis SET: seen_ads:{user_id}:{date})   │  │
│    • Apply budget filter (Redis: is ad's campaign still spending?)       │  │
│    • Result: ~1,000 candidate ads (from 10M total)                       │  │
│                                                                          │  │
│  Step 2: SCORING / RANKING (< 50ms)                                      │  │
│    • ML model: predict pCTR for each candidate (lightweight model)       │  │
│    • Compute ad rank = bid × pCTR × quality_score                        │  │
│    • Normalize bids to eCPM for cross-billing-model comparison           │  │
│                                                                          │  │
│  Step 3: AUCTION (< 5ms)                                                 │  │
│    • Sort by ad_rank (DESC)                                               │  │
│    • Winner = highest ad_rank                                            │  │
│    • Clearing price = second-price + ε (Vickrey auction)                 │  │
│    • Pacing check: confirm winner campaign has remaining budget           │  │
│    • Generate signed impression_token                                    │  │
│                                                                          │  │
│  Step 4: RESPONSE (< 5ms)                                                │  │
│    • Return winning ad creative + impression_token to Feed Service        │  │
└──────────────────────────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  IMPRESSION & CLICK TRACKING (Async — after feed rendered to user)       │
│                                                                          │
│  User's device fires:                                                    │
│    POST /v1/track/impression (with impression_token)                     │
│    POST /v1/track/click (with click_token)                               │
│                                                                          │
│  Tracking Service:                                                       │
│    • Validate token (signed; prevent fake impressions)                   │
│    • Dedup (bloom filter — prevent count inflation from retries)         │
│    • Publish to Kafka: impression-events / click-events                  │
└─────────────────────────┬────────────────────────────────────────────────┘
                          │
           ┌──────────────┼──────────────────────┐
           ▼              ▼                      ▼
  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐
  │  BUDGET      │  │  REPORTING   │  │  BILLING SERVICE     │
  │  CONTROLLER  │  │  PIPELINE    │  │  (daily invoicing;   │
  │              │  │              │  │   charge advertisers)│
  │  Deducts     │  │  Kafka →     │  │                      │
  │  spend from  │  │  Flink/Spark │  │                      │
  │  Redis       │  │  → ClickHouse│  │                      │
  │  counter     │  │  → Dashboard │  │                      │
  └──────────────┘  └──────────────┘  └──────────────────────┘
```

### Data Flow Summary

```
OFFLINE (pre-computation, runs continuously):
  Ad metadata updated → Rebuild Redis ad index → pCTR model retrained nightly

ONLINE (real-time critical path, < 100ms):
  Feed load → ad-auction → candidate retrieval (Redis) → scoring (ML)
             → auction (second-price) → return winner

ASYNC (after ad shown):
  Impression token fired → Kafka → budget deduction + reporting + billing
```

---

## 5. Deep Dives

### 5.1 Ad Auction — Second-Price Auction & Real-Time Bidding

> **Interview tip:** The auction design is the most intellectually interesting part. Lead with WHY second-price, not just WHAT.

#### Why Second-Price (Vickrey) Auction?

```
First-Price Auction (pay what you bid):
  Nike bids $5.00; Zomato bids $3.00
  Nike wins; pays $5.00
  Problem: Nash equilibrium → advertisers strategize, shade bids below true value
           Bidding ≠ true willingness-to-pay → market inefficiency
           Result: complex bidding agencies; arms race of bid shading

Second-Price Auction (winner pays 2nd highest bid + ε):
  Nike bids $5.00; Zomato bids $3.00
  Nike wins; pays $3.01 (Zomato's bid + $0.01)
  Key theorem: Dominant strategy is to bid your TRUE value
  Why? If you bid less than true value and lose → you lose more than the clearing price
       If you bid more than true value and win → you overpay
       Bidding true value → always optimal regardless of what others do
  Result: simpler bidding; market efficiency; honest signals of ad value

Facebook/Google both use variations of second-price auctions.
```

#### Ad Rank Formula (Not Pure Bid)

```
Pure bid-based auction = rich advertisers always win, irrelevant ads shown.
Result: poor user experience → fewer clicks → less long-term revenue.

Solution: Ad Rank = bid × predicted_click_rate × quality_score

  bid:              Advertiser's max bid (CPC or CPM, normalized to eCPM)
  pCTR:             ML-predicted probability this user clicks this ad (0-1)
  quality_score:    Historical performance + ad relevance + landing page quality

Example:
  Nike:  bid=$5.00, pCTR=0.01, quality=0.8 → rank = 5.00 × 0.01 × 0.8 = 0.040
  Zomato: bid=$2.00, pCTR=0.04, quality=0.9 → rank = 2.00 × 0.04 × 0.9 = 0.072

  Zomato WINS despite lower bid! Because their ad is much more likely to be clicked.

Why? Revenue = bid × pCTR; platform earns more from relevant ads even at lower bids.
  Nike would need to pay: Zomato_rank / Nike_pCTR / Nike_quality
    = 0.072 / 0.01 / 0.8 = $9.00/click to win (vs Zomato paying $5.01/click to secure win)
  Platform maximizes EXPECTED REVENUE, not highest bid.

Clearing price (what winner actually pays):
  clearing_price = (runner_up_rank / winner_pCTR / winner_quality) + ε
  Guarantees winner pays as little as necessary to win.
```

#### Full Auction Flow

```python
def run_auction(candidates: List[Ad], user_context: UserContext, slots: int):
    """
    Run second-price auction for N ad slots.
    Returns winning ads with clearing prices.
    """
    scored = []

    for ad in candidates:
        # Step 1: Get bid (normalize all to eCPM)
        bid_ecpm = normalize_to_ecpm(ad.bid_amount, ad.billing_event,
                                      ad.pCTR_estimate)

        # Step 2: Compute ML-predicted CTR for this (ad, user) pair
        pctr = ml_model.predict(
            ad_features=ad.features,        # ad creative features
            user_features=user_context.features,  # user profile features
            context_features={              # contextual signals
                'time_of_day': now().hour,
                'day_of_week': now().weekday(),
                'feed_position': slot_position,
                'device_type': user_context.device
            }
        )

        # Step 3: Compute quality score
        quality = compute_quality_score(
            historical_ctr=ad.historical_ctr,       # past performance
            ctr_to_bid_ratio=pctr / (bid_ecpm / 1000),  # relevance
            landing_page_score=ad.landing_page_quality,  # post-click quality
            ad_freshness=days_since_creation(ad)    # penalize stale ads
        )

        # Step 4: Compute ad rank
        ad_rank = bid_ecpm * pctr * quality

        scored.append((ad, bid_ecpm, pctr, quality, ad_rank))

    # Step 5: Sort by ad_rank descending
    scored.sort(key=lambda x: x[4], reverse=True)

    winners = []
    for i in range(min(slots, len(scored))):
        winner     = scored[i]
        runner_up  = scored[i + 1] if i + 1 < len(scored) else None

        if runner_up:
            # Clearing price: minimum bid winner needs to pay to beat runner-up
            clearing_price = (runner_up[4] / winner[2] / winner[3]) + EPSILON
        else:
            # Only one bidder: pay floor price
            clearing_price = FLOOR_PRICE_CPM

        clearing_price = max(clearing_price, FLOOR_PRICE_CPM)
        clearing_price = min(clearing_price, winner[1])  # cap at winner's max bid

        winners.append({
            'ad': winner[0],
            'clearing_price': clearing_price,
            'ad_rank': winner[4],
            'pCTR': winner[2]
        })

    return winners


def normalize_to_ecpm(bid: float, billing_event: str, pctr: float) -> float:
    """Normalize different billing models to eCPM for fair comparison."""
    if billing_event == "IMPRESSIONS":  # CPM bid
        return bid  # already in per-1000-impression units

    elif billing_event == "CLICKS":     # CPC bid
        # eCPM = CPC × expected clicks per 1000 impressions
        return bid * pctr * 1000

    elif billing_event == "CONVERSIONS":  # CPA bid
        # eCPM = CPA × P(conversion) per 1000 impressions
        conversion_rate = pctr * ad.post_click_conversion_rate
        return bid * conversion_rate * 1000
```

---

### 5.2 Ad Targeting — Candidate Retrieval

#### The Scale Challenge

```
10M active ads × each with targeting criteria
1 user arrives with: age=25, gender=F, city=Mumbai, interests=[fashion,beauty]

Naive: check every ad's targeting against this user:
  10M ads × 1ms per check = 10,000 seconds
  → Completely infeasible for < 100ms target

Solution: Inverted Index on targeting dimensions in Redis
  Build an inverted index: targeting_value → list of matching ad_set_ids

  Index structure:
    age:25       → [adsset_001, adsset_005, adsset_019, ...]  (ad sets targeting 25-year-olds)
    gender:F     → [adsset_001, adsset_003, adsset_021, ...]
    city:mumbai  → [adsset_002, adsset_006, adsset_011, ...]
    interest:fashion → [adsset_001, adsset_004, adsset_019, ...]
    device:mobile  → [adsset_001, adsset_003, adsset_019, ...]

  Retrieval:
    candidates = INTERSECT(
      age:25, gender:F, city:mumbai, interest:fashion, device:mobile
    )
    → Returns only ad sets that match ALL targeting criteria
    → From 10M → ~10,000 candidates (very hot user profile)
    → From 10,000 → apply frequency cap filter → ~1,000 ad candidates
```

#### Redis Targeting Index Design

```
Data structure: Redis SORTED SET (ZSET) per targeting dimension
  Score:  ad budget remaining (higher budget = higher priority for candidacy)
  Member: ad_id

ZADD index:age:25 {budget_score} {ad_id}
ZADD index:gender:F {budget_score} {ad_id}
ZADD index:city:mumbai {budget_score} {ad_id}
ZADD index:interest:fashion {budget_score} {ad_id}

Retrieval strategy (INTERSECT on top-K):
  # Get top-N from each dimension (by budget score) → intersect → candidates
  top_age     = ZREVRANGE index:age:25      0 10000  # top 10K by budget
  top_gender  = ZREVRANGE index:gender:F    0 10000
  top_city    = ZREVRANGE index:city:mumbai 0 10000
  top_interest = ZREVRANGE index:interest:fashion 0 10000

  candidates = top_age ∩ top_gender ∩ top_city ∩ top_interest
  → Set intersection in application layer (sort, merge: O(N log N))

Alternative: Redis ZINTERSTORE (native intersection)
  ZINTERSTORE candidates 4 index:age:25 index:gender:F index:city:mumbai index:interest:fashion
  AGGREGATE MIN  (take minimum score = most constrained dimension)
  → Single Redis command; returns intersection natively
  → Efficient for < 10K elements per dimension

For complex boolean targeting ("fashion" OR "beauty"):
  ZUNIONSTORE interest_set 2 index:interest:fashion index:interest:beauty
  Then intersect interest_set with other dimension indexes

Memory estimation:
  10M active ads × 5 targeting dimensions × 32 bytes per ZSET entry
  = 10M × 5 × 32 = 1.6 GB per dimension
  Total across 20 dimension types = 32 GB → Redis Cluster (4 nodes × 10 GB each)
```

#### Lookalike Audiences & Custom Audiences

```
Custom Audiences:
  Advertiser uploads: customer email list, phone numbers, device IDs
  We hash and match against our user database
  Create audience_segment: "Nielsen Existing Customers" = {user_id_set}
  Store as: user_segment:{segment_id} → Redis SET of user_ids (bitmap for large sets)
  
  On targeting check: SISMEMBER user_segment:{segment_id} {user_id}
  → O(1) membership check → include/exclude this user

Lookalike Audiences:
  Advertiser: "find users similar to my best 10% customers"
  System: take seed users → extract feature vectors (interests, behavior)
           → run KNN search to find most similar users in our population
           → Create lookalike audience segment
  Offline job (nightly): rebuild lookalike segments using embedding similarity
  HNSW (Hierarchical Navigable Small World) index for ANN search
    → Find top-N similar users to seed set in milliseconds
```

---

### 5.3 Ad Ranking — Relevance Score × Bid

#### pCTR Model (Predicted Click-Through Rate)

```
This ML model is THE core intelligence of the ad system.
Google went from $1B to $10B in ad revenue largely by improving their pCTR model.

Training data:
  Historical impression-click pairs:
  { user_features, ad_features, context_features, clicked: 0/1 }
  Billions of training examples (every impression = one data point)
  
  Label: did user click this ad? (1=yes, 0=no)
  Problem type: binary classification

Feature categories:

User features (dense, from user profile):
  - Demographic: age_bucket, gender, location_tier, income_estimate
  - Behavioral: interests_embedding, recent_categories, purchase_history
  - Platform: account_age, daily_active_mins, content_type_preference
  - Historical: past CTR for this ad category, past conversion rate

Ad features (dense, from ad creative):
  - Creative: image_embedding (from CNN), text_embedding (from BERT)
  - Campaign: objective, bid_amount, category, brand_affinity_score
  - Historical: same_ad CTR last 7d, same_campaign CTR, same_creative CTR

Context features:
  - Time: hour_of_day, day_of_week, is_weekend, days_until_payday
  - Device: mobile/desktop, os, app_version, connection_type
  - Feed: position_in_feed (earlier = higher CTR), organic_content_topic

Models in production (typically):
  Offline (training): Deep learning (DLRM — Deep Learning Recommendation Model)
    Two-tower architecture:
      User tower: MLP on user features → 128-dim user embedding
      Ad tower:   MLP on ad features  → 128-dim ad embedding
      Interaction: dot product + MLP → pCTR score
    Trained: nightly on previous day's impression data
    
  Online (inference): Lightweight gradient boosted trees (GBDT/XGBoost)
    Reason: 1000 candidates × 100ms budget → ~100μs per scoring → GBT is fastest
    Input: precomputed user embedding + per-ad features
    Output: pCTR in [0, 1]
    
  Two-stage approach:
    Stage 1 (retrieval): Use embedding dot product (fast; ANN search)
                         Reduce 10M ads → 1000 candidates (< 10ms)
    Stage 2 (ranking): Full GBDT on 1000 candidates (50ms)
    → Analogous to RAG pipeline but for ads
```

#### Quality Score Components

```
Quality Score = f(relevance, landing_experience, historical_performance)

1. Ad Relevance Score (static, per ad creation):
   Does ad creative match the targeting audience?
   ML classifier: "fashion ad shown to fashion-interested users" → high
   "insurance ad shown to teenagers" → low
   Updated: on ad creation + 24-hour re-evaluation

2. Landing Page Quality (static, per ad):
   After click, does landing page deliver on ad promise?
   Signals: page load speed, mobile-friendliness, bounce rate, conversion rate
   Estimated using: Lighthouse score + historical post-click engagement

3. Expected Impact (dynamic, per auction):
   How does this ad perform for users like the current user?
   Signals: CTR for this ad × this demographic × this time-of-day
   Updated: every 4 hours using aggregated Kafka events

Quality score range: [0, 10] (like Google's quality score)
Below 5: penalized significantly in auction (reduces ad rank)
Above 7: bonus multiplier (rewards consistently well-performing ads)
```

---

### 5.4 Budget Pacing & Spend Management

> **Interview tip:** Budget pacing is the most underrated deep dive. It's a rate-limiting problem at massive scale with financial implications.

#### The Pacing Challenge

```
Problem:
  Advertiser sets budget: $100/day
  Without pacing: system spends $100 in first 2 hours (early morning traffic).
  Afternoon/evening users (maybe more valuable) see NO ads from this advertiser.
  Advertiser unhappy: all budget gone before prime time.

Desired behavior:
  Spend $100 evenly across 24 hours → $4.17/hour
  OR intelligently: spend more during high-traffic / high-value hours (dayparting)
  HARD CONSTRAINT: NEVER exceed $100 total (overspend = revenue nightmare for platform)

Three pacing strategies:
  1. Even pacing: $4.17/hour regardless of traffic volume
  2. Accelerated: spend as fast as possible until budget runs out  
  3. Smart pacing: ML model predicts optimal spend rate by hour
```

#### Token Bucket Pacing Algorithm

```
Each campaign has a token bucket:
  capacity:     budget per time unit (e.g., $4.17/hour = tokens)
  refill_rate:  tokens added per second ($4.17 / 3600 = $0.00116/sec)
  current_tokens: current remaining budget for this time period

On each ad win:
  1. Check: current_tokens >= clearing_price?
     YES → consume tokens; ad shown
     NO  → ad SKIPPED (campaign pacing out); next ad in rank considered

  Implementation in Redis (atomic):
    EVAL {lua script} 1 budget:{campaign_id} {clearing_price_cents} {refill_amount} {max_tokens}

    Lua script (atomic in Redis — no race conditions):
      -- Refill tokens since last check
      local now = tonumber(redis.call('time')[1])
      local last_refill = tonumber(redis.call('hget', KEYS[1], 'last_refill') or '0')
      local current = tonumber(redis.call('hget', KEYS[1], 'tokens') or '0')
      local max_tokens = tonumber(ARGV[3])
      local refill_rate_per_sec = tonumber(ARGV[2])     -- tokens per second
      
      -- Add tokens for elapsed time
      local elapsed = now - last_refill
      local new_tokens = math.min(current + elapsed * refill_rate_per_sec, max_tokens)
      
      -- Check if we can spend
      local cost = tonumber(ARGV[1])
      if new_tokens >= cost then
          redis.call('hset', KEYS[1], 'tokens', new_tokens - cost)
          redis.call('hset', KEYS[1], 'last_refill', now)
          return 1   -- proceed (tokens consumed)
      else
          redis.call('hset', KEYS[1], 'tokens', new_tokens)
          redis.call('hset', KEYS[1], 'last_refill', now)
          return 0   -- skip (insufficient tokens)
      end
```

#### Budget Enforcement Hierarchy

```
Three-level budget enforcement:

Level 1: Lifetime Budget (campaign level)
  Hard cap: campaign NEVER spends more than lifetime_budget
  Implementation: Cassandra persisted counter (accurate, not in-memory only)
  Updated: asynchronously via Kafka impression events

Level 2: Daily Budget (ad set level)
  Soft cap: spend approximately daily_budget per day
  Implementation: Redis token bucket (fast, in-memory)
  Resets: midnight in advertiser's timezone
  Variance tolerance: allow 10-15% overspend to avoid under-delivery

Level 3: Real-Time Throttle (ad selection level)
  Sampling rate: if token bucket is running low, probabilistically skip
  Rate = remaining_tokens / (remaining_time × expected_spend_rate)
  If rate = 0.3: show this ad in only 30% of eligible auctions
  → Spreads remaining budget evenly over remaining time

Preventing Overspend on Impression Lag:
  Problem: We approve ad in auction, but impression event arrives 10s later
           In those 10s, 1000 more auctions may have also approved this ad
           → Overspend by 10-20% before tracking catches up

  Solution: Optimistic concurrency with shadow budget
    Redis "pending_spend" counter: INCRBY pending_spend:{campaign_id} {clearing_price}
    before returning auction winner
    Kafka impression event arrives: deduct from actual_spend, release from pending_spend
    If pending_spend + actual_spend > 95% of budget: stop entering auctions
    → Builds in 5% buffer for spend-before-track lag
```

---

### 5.5 Impression & Click Tracking at Scale

#### Scale Challenge

```
231K impressions/sec (sustained) + 1,848 clicks/sec

Requirements:
  1. Every impression counted exactly once (for billing accuracy)
  2. Idempotent: client retries should not inflate count
  3. Fraud prevention: fake impressions inflate advertiser spend
  4. Near-real-time: advertisers see dashboard within 5 minutes

Common attack vectors:
  Bot click farms: 1000 clicks/sec from same IP/device
  Impression stuffing: hidden iframe loads ad 1000 times
  Inventory fraud: publisher reports more impressions than served
```

#### Impression Token Design

```
Impression token = signed JWT with:
  { ad_id, user_id, ad_set_id, campaign_id, clearing_price,
    auction_timestamp, feed_position, ttl: 30min }
Signed with: HMAC-SHA256 using server-side secret

Benefits:
  1. Contains all billing data (no DB lookup needed on impression receipt)
  2. Tamper-proof: attacker can't forge tokens without secret
  3. Time-bounded (TTL): old tokens rejected → prevents replay attacks
  4. Stateless validation: any tracker node validates without coordination

Impression tracking flow:
  1. Feed renders → JS fires POST /track/impression with impression_token
  2. Tracker validates:
     a. HMAC signature valid?
     b. TTL not expired (auction_timestamp + 30min > now)?
     c. Dedup check (not seen before)?
  3. Dedup via Bloom Filter:
     BF.EXISTS impressions_dedup {impression_id} in Redis
     If EXISTS: drop (duplicate from retry) → return 200 (idempotent)
     If NOT EXISTS: BF.ADD → continue processing
  4. Publish to Kafka (impression-events) with raw impression data
  5. 200 OK to client (fire-and-forget; don't wait for Kafka)
```

#### Tracking Pipeline

```
Kafka: impression-events (1000 partitions; keyed by campaign_id)
  → Multiple consumers fan out:

Consumer 1: Budget Deductor
  Reads impression event
  Deducts clearing_price from Redis campaign spend counter:
    INCRBY campaign_spend:{campaign_id} {clearing_price_cents}
  Checks: if campaign_spend >= daily_budget → pause campaign in index

Consumer 2: Reporting Aggregator (Apache Flink or Spark Streaming)
  Micro-batch (every 30 seconds):
    GROUP BY (campaign_id, ad_id, hour) aggregate: impressions, clicks, spend
  Write to ClickHouse:
    INSERT INTO ad_metrics (campaign_id, ad_id, hour, impressions, clicks, spend)
    → Advertiser dashboard reads from ClickHouse (p99 < 1s for report queries)

Consumer 3: ML Feature Updater
  Update Redis feature store for fraud detection:
    INCR ip_impressions:{ip}:{hour}
    INCR device_impressions:{device_fingerprint}:{hour}
  Detect anomalies: > 100 impressions from same IP in 1 hour → flag for fraud review

Consumer 4: Cold Storage Writer
  Batch write to S3/GCS Parquet:
    Partitioned by date/campaign_id
    Used for: long-term analytics, billing disputes, model training data

Click tracking:
  Same flow but with click_token (issued only to impressions that were shown)
  Click token = impression_token + click_nonce + click_timestamp
  Attacker who never saw ad cannot forge a valid click_token
  
  Click → Kafka (click-events) → 
    Budget deduction (CPC billing: deduct CPC amount)
    CTR update for quality score
    Conversion attribution (was this click → purchase? tracked via pixel)
```

---

## 6. Scale & Resilience

### Ad Index Refresh Strategy

```
Problem: Ad campaigns start, pause, exhaust budget, change targeting.
Redis index must stay synchronized with PostgreSQL source of truth.

Change event types:
  - New ad created → add to index
  - Ad paused → remove from index
  - Budget exhausted → remove from index (until next day)
  - Targeting updated → update index entries
  - Campaign daily reset (midnight) → re-add all paused-for-budget ads

Sync strategy: CDC via Debezium
  PostgreSQL WAL → Debezium → Kafka (ad-index-change-events)
  Ad Index Updater service consumes:
    On AD_CREATED/RESUMED: ZADD all targeting dimension keys
    On AD_PAUSED/BUDGET_EXHAUSTED: ZREM all targeting dimension keys
    On TARGETING_CHANGED: ZREM old entries; ZADD new entries

  Latency: < 5s from DB change to Redis index update
  Critical: budget exhaustion must propagate in < 30s
  (If it takes longer, campaign overspends by 30s × clearing_price × auction_win_rate)

Daily midnight reset:
  Budget Cron Job (midnight per timezone):
    1. RESET campaign_spend:{campaign_id} → 0 (Redis FLUSHDB for spend keys)
    2. Reload all campaigns with budget remaining: re-add to Redis index
    3. Rebuild token bucket: HSET budget:{campaign_id} tokens {daily_budget_in_cents}
```

### Failure Scenarios

| Failure | Detection | Impact | Recovery |
|---|---|---|---|
| **Redis targeting index crash** | Connection timeout | Can't retrieve ad candidates → no ads shown | Rebuild from PostgreSQL (~10 min); serve fallback ads from cache |
| **ML scoring service crash** | Health check | Can't rank candidates | Fall back to bid-only ranking (no pCTR); accept lower quality but keep serving |
| **Budget Redis crash** | Connection timeout | Can't enforce budgets → overspend risk | Circuit breaker: stop all auctions for 5min; alert; rebuild Redis budget state from Kafka |
| **Kafka lag spike** | Consumer lag metric | Budget deduction delayed → overspend | Reduce impression rate (throttle 20%); alert finance team |
| **Impression tracker crash** | Health check | Impressions not counted → advertisers underbilled | Client retries (impression_token TTL = 30min allows retries); Kafka retry |
| **pCTR model staleness** | Model freshness metric | Lower relevance → lower CTR → lower revenue | Use cached model; alert ML team; auto-retrain triggered |

### Advertiser Budget Overspend Protection

```
Multi-layer protection against overspending:
  Layer 1: Redis token bucket (primary — catches most)
  Layer 2: 10% soft cap — when spend reaches 90% of budget: pause ad (pre-emptive)
  Layer 3: Kafka lag check — if billing lag > 60s: halt new auctions for that campaign
  Layer 4: Daily spend reconciliation: compare Redis vs Cassandra spend
           If divergence > 5%: alert + auto-correct Redis from Cassandra

Advertiser SLA commitment:
  "We guarantee not to overspend your daily budget by more than 25%"
  (Facebook commits to 25%; Google to 20%)
  → This allows for asynchronous budget tracking without hard-blocking every auction
```

---

## 7. Trade-offs & Alternatives

| Decision | Choice | Alternative | Reason |
|---|---|---|---|
| **Auction type** | Second-price Vickrey | First-price | Second-price: dominant strategy = bid true value; simpler for advertisers; proven at Google/FB |
| **Ad rank** | bid × pCTR × quality | Pure bid | Pure bid: rich wins, irrelevant ads; rank × pCTR: maximizes expected revenue + user experience |
| **Candidate retrieval** | Redis inverted index (ZINTERSTORE) | DB query + filtering | DB: 10M query too slow; Redis: in-memory intersection in < 10ms |
| **Budget tracking** | Redis token bucket + async Kafka deduction | Synchronous DB decrement per auction | DB: 232K TPS = impossible; Redis: atomic Lua, in-memory, fast; async: slight overspend risk |
| **pCTR model** | Two-stage: embedding retrieval → GBDT ranking | Single deep model everywhere | DL everywhere: too slow per candidate (10ms × 10M ads); two-stage: fast retrieval + accurate ranking |
| **Impression dedup** | Bloom filter + signed token | Session-level dedup | Bloom: O(1), 18 MB memory, no per-impression DB write; signed token prevents forgery |
| **Reporting** | Kafka → Flink → ClickHouse | Real-time query on raw logs | ClickHouse: pre-aggregated, < 1s query on billions of rows; raw logs: too slow for dashboards |
| **Frequency cap** | Redis per-user LRU set | DB lookup per impression | Redis: O(1) SISMEMBER; DB: ad selection adds 1ms per ad lookup × 1000 candidates = 1000ms |

### Ad Auction Model Comparison

| Approach | Revenue | User Experience | Advertiser Complexity |
|---|---|---|---|
| **First-price** | High short-term | Poor (irrelevant ads) | High (bid shading) |
| **Second-price bid only** | Medium | Medium (rich wins) | Low |
| **Second-price + pCTR** | Highest (proven at FB/Google) | Best (relevant ads = more clicks) | Low (bid true value) |
| **Open RTB (external DSPs)** | Potentially higher | Variable | Very high |

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Knew ad tech vocabulary (eCPM, pCTR, ad rank, clearing price, pacing)
  [ ] Distinguished campaign management (offline) from ad serving (online critical path)
  [ ] Asked billing model (CPM vs CPC vs CPA) → all normalized to eCPM for auction
  [ ] Mentioned frequency cap as constraint on candidate retrieval

CAPACITY
  [ ] 1M feed loads/sec → 232K auctions/sec
  [ ] 10M active ads → 1000 candidates via inverted index (not full scan)
  [ ] 231K impressions/sec sustained; 1B impressions/day
  [ ] Redis ad index: 32 GB across 4 cluster nodes

ARCHITECTURE
  [ ] Separated offline (index build, model training) from online (< 100ms serving)
  [ ] Four-step auction: retrieval → scoring → auction → budget check
  [ ] Async post-auction: impression token fired → Kafka → budget + reporting
  [ ] CDP via Debezium to keep Redis index in sync with PostgreSQL

DEEP DIVES

  Auction:
    [ ] WHY second-price: equilibrium bid = true value (not a gaming exercise)
    [ ] Ad rank = bid × pCTR × quality_score (not pure bid — critical insight)
    [ ] eCPM normalization: CPC bid × pCTR × 1000 = eCPM for CPM comparison
    [ ] Clearing price formula: runner_up_rank / winner_pCTR / winner_quality

  Candidate Retrieval:
    [ ] Redis ZSET inverted index per targeting dimension
    [ ] ZINTERSTORE across dimensions → O(K log K) intersection where K ≪ 10M
    [ ] Custom audiences: SISMEMBER on user_id set
    [ ] Lookalike audiences: ANN search with HNSW on user embeddings

  Ad Ranking / pCTR:
    [ ] Two-stage: embedding dot-product retrieval → GBDT ranking
    [ ] Features: user, ad creative, context (time, device, position)
    [ ] Quality score components: relevance + landing page + historical CTR
    [ ] Model training: nightly on billions of impression-click pairs

  Budget Pacing:
    [ ] Token bucket algorithm: refill_rate = daily_budget / 86400
    [ ] Atomic Redis Lua script for token check + consume
    [ ] Three budget levels: lifetime (Cassandra) → daily (Redis) → real-time (sampling)
    [ ] Pending spend buffer: INCRBY pending before auction wins confirmed

  Tracking:
    [ ] Signed impression token (JWT + HMAC): tamper-proof, TTL-bounded
    [ ] Bloom filter dedup: O(1), prevents retry inflation, 0.1% FP acceptable
    [ ] Kafka → 4 consumers: budget deductor, reporting, fraud detection, cold storage
    [ ] Click token derived from impression token → attacker must have seen ad to click

SCALE & RESILIENCE
  [ ] CDC (Debezium): ad pause/budget-exhaust propagates to Redis in < 5s
  [ ] Redis crash: rebuild index from PostgreSQL; fallback to bid-only ranking
  [ ] Budget overspend protection: 4 layers including 10% soft cap
  [ ] Multi-region: ad index replicated; single-region auction decision (consistency)

TRADE-OFFS
  [ ] Second-price vs first-price (market efficiency vs revenue short-term)
  [ ] bid × pCTR vs pure bid (relevance is key to long-term revenue)
  [ ] Async budget tracking vs synchronous (throughput vs overspend risk)
  [ ] Two-stage ranking vs single model (latency constraint drives this decision)
  [ ] Bloom filter dedup vs session dedup (scale vs false positive rate)
```
