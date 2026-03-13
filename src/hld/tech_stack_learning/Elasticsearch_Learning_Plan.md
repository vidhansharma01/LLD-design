# Elasticsearch: 7-Day Learning Plan — Beginner to Advanced

> **What is Elasticsearch?**
> Elasticsearch is a distributed, RESTful search and analytics engine built on Apache Lucene.
> It stores data as JSON documents, indexes every field by default, and enables full-text search,
> aggregations, and real-time analytics at massive scale.
>
> **Goal**: By Day 7 you can design, query, tune, and operate production Elasticsearch clusters
> and explain every concept in an SDE3 system design interview.
>
> **Time per day**: 2–3 hours study + hands-on practice.
> **Tools**: Docker (local ES), Kibana Dev Tools, curl / REST client, Spring Boot + Spring Data ES.

---

## Setup (Do This Before Day 1)

```bash
# Start Elasticsearch + Kibana via Docker Compose
# docker-compose.yml:
# version: '3'
# services:
#   elasticsearch:
#     image: docker.elastic.co/elasticsearch/elasticsearch:8.12.0
#     environment:
#       - discovery.type=single-node
#       - xpack.security.enabled=false   # disable auth for local learning
#       - ES_JAVA_OPTS=-Xms512m -Xmx512m
#     ports: ["9200:9200"]
#   kibana:
#     image: docker.elastic.co/kibana/kibana:8.12.0
#     ports: ["5601:5601"]
#     environment:
#       - ELASTICSEARCH_HOSTS=http://elasticsearch:9200

docker-compose up -d

# Verify
curl http://localhost:9200
# Returns cluster info JSON

# Open Kibana Dev Tools (UI for running queries)
# http://localhost:5601 → Hamburger menu → Dev Tools
```

---

## Day 1 — Core Concepts And Data Model

### 1.1 What Is Elasticsearch?

```
Typical use cases:
  → Full-text search: search bar on e-commerce, Google-like search.
  → Log analytics: ELK stack (Elasticsearch + Logstash + Kibana).
  → Application performance monitoring (APM).
  → Geospatial search: "find restaurants within 2 km".
  → Business analytics: sales dashboards, time-series aggregations.

What Elasticsearch is NOT:
  → Primary relational database (no ACID, no JOINs between indices).
  → Source of truth (treated as search/analytics layer; primary DB is PostgreSQL etc.).
  → Perfect for write-heavy OLTP (each write triggers index updates on all fields).
```

### 1.2 Core Terminology

```
┌─────────────────┬───────────────────────────────────────────────────────────┐
│ Term            │ Meaning (with SQL analogy)                                │
├─────────────────┼───────────────────────────────────────────────────────────┤
│ Index           │ = Table. Logical collection of documents with same schema.│
│ Document        │ = Row. A JSON object stored in an index.                  │
│ Field           │ = Column. A key-value pair in a document.                 │
│ Mapping         │ = Schema. Defines field names and data types.             │
│ Shard           │ = Partition. Index split across multiple shards.          │
│ Replica         │ = Read replica. Copy of shard for HA and read scaling.    │
│ Node            │ = One Elasticsearch process/server.                       │
│ Cluster         │ = Group of nodes working together.                        │
│ Alias           │ = View. Named pointer to one or more indices.             │
└─────────────────┴───────────────────────────────────────────────────────────┘
```

### 1.3 Document Structure

```json
// Each document is a JSON object with an auto-generated or provided _id.
{
  "_index": "products",
  "_id": "ASIN_B09X123",
  "_source": {
    "name": "Echo Dot 5th Gen",
    "category": "Electronics",
    "price": 4999,
    "rating": 4.5,
    "stock": 1200,
    "tags": ["alexa", "smart-home", "speaker"],
    "description": "Smart speaker with Alexa. Compact design...",
    "launched_at": "2023-10-15T00:00:00Z",
    "location": { "lat": 19.0760, "lon": 72.8777 }
  }
}
```

### 1.4 CRUD Operations (REST API)

```bash
# CREATE index with settings
PUT /products
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  }
}

# INDEX (create/replace) a document
PUT /products/_doc/ASIN_B09X123
{
  "name": "Echo Dot 5th Gen",
  "category": "Electronics",
  "price": 4999,
  "rating": 4.5,
  "tags": ["alexa", "speaker"]
}

# Auto-generate ID (POST)
POST /products/_doc
{ "name": "Fire TV Stick", "category": "Electronics", "price": 3999 }

# GET a document by ID
GET /products/_doc/ASIN_B09X123

# UPDATE (partial update — only specified fields)
POST /products/_update/ASIN_B09X123
{
  "doc": { "price": 3999, "stock": 800 }
}

# DELETE a document
DELETE /products/_doc/ASIN_B09X123

# DELETE an entire index
DELETE /products

# Check cluster health
GET /_cluster/health

# List all indices
GET /_cat/indices?v

# Index stats
GET /products/_stats
```

### 1.5 Day 1 Practice

```
1. Start Elasticsearch via Docker.
2. Create an index called "movies" with 1 shard, 0 replicas (single-node = no replica needed).
3. Index 5 movies: { title, director, year, genre, imdb_rating }.
4. GET each movie by ID.
5. UPDATE the rating of one movie.
6. DELETE one movie.
7. GET /_cat/indices?v — observe index size and document count.
```

---

## Day 2 — Mappings, Data Types, And Analyzers

### 2.1 Mappings (Schema Definition)

```bash
# Explicit mapping (always define mappings in production — don't rely on dynamic)
PUT /products
{
  "mappings": {
    "properties": {
      "name":        { "type": "text", "analyzer": "english" },
      "category":    { "type": "keyword" },
      "price":       { "type": "float" },
      "rating":      { "type": "half_float" },
      "stock":       { "type": "integer" },
      "tags":        { "type": "keyword" },
      "description": { "type": "text", "analyzer": "english" },
      "launched_at": { "type": "date" },
      "location":    { "type": "geo_point" },
      "attributes":  { "type": "object" },
      "reviews":     { "type": "nested" }
    }
  }
}

# View current mapping
GET /products/_mapping
```

### 2.2 Data Types Deep Dive

```
text vs keyword — critical distinction:

  "text":
    → Full-text analyzed. Tokenized, lowercased, stemmed.
    → "Echo Dot Speaker" → tokens: ["echo", "dot", "speaker"].
    → Used for: product descriptions, article bodies, any searchable prose.
    → Cannot be used for: sorting, aggregations (too many unique tokens).

  "keyword":
    → Stored as-is. Not analyzed.
    → "Electronics" stays "Electronics".
    → Used for: exact match filtering, sorting, aggregations (facets).
    → Never use keyword for long text (stored verbatim → memory explosion).

  Best practice — multi-field mapping (both!):
    "name": {
      "type": "text",
      "analyzer": "english",
      "fields": {
        "keyword": { "type": "keyword", "ignore_above": 256 }
      }
    }
    → Search: query name (text field).
    → Sort/aggregate: use name.keyword (keyword sub-field).

Numeric types:
  "integer":    32-bit signed integer (-2B to 2B).
  "long":       64-bit signed integer. Use for IDs, timestamps.
  "float":      32-bit IEEE 754 float.
  "double":     64-bit. Avoid — use scaled_float instead.
  "half_float": 16-bit. Good for ratings (0.0–5.0). Saves memory.
  "scaled_float": float × scale_factor stored as long. E.g., price × 100 → no float errors.

Special types:
  "date":       ISO 8601 string or epoch millis. "2024-03-14" or 1710374400000.
  "boolean":    true/false.
  "geo_point":  { "lat": 19.076, "lon": 72.877 } for location searches.
  "ip":         IPv4/IPv6 addresses with CIDR range queries.
  "nested":     Array of objects where each object is independently queryable.
  "join":       Parent-child relationship within one index.

object vs nested:
  object: fields of all array elements merged into one flat structure.
    → Cannot query "find products where review.user=Alice AND review.rating=5".
    → Alice's rating and Bob's user name might match together (cross-object confusion).
  nested: each element stored as a hidden separate document.
    → Queries match within each element independently. ✓
    → More expensive: separate Lucene document per nested element.
```

### 2.3 Analyzers — How Text Is Processed

```
Analyzer pipeline: Character Filter → Tokenizer → Token Filters.

Standard analyzer (default):
  Input:  "Quick BROWN Fox!"
  Tokens: ["quick", "brown", "fox"]
  Steps: lowercase + tokenize on whitespace/punctuation.

English analyzer:
  Input:  "Running quickly through forests"
  Tokens: ["run", "quick", "through", "forest"]
  Steps: lowercase + stopword removal ("through") + stemming ("running→run").

Custom analyzer:
  PUT /products
  {
    "settings": {
      "analysis": {
        "char_filter": {
          "html_strip": { "type": "html_strip" }
        },
        "tokenizer": {
          "my_tokenizer": { "type": "standard" }
        },
        "filter": {
          "my_stop": { "type": "stop", "stopwords": "_english_" },
          "my_stem":  { "type": "stemmer", "language": "english" },
          "my_lower": { "type": "lowercase" },
          "edge_ngram": { "type": "edge_ngram", "min_gram": 2, "max_gram": 10 }
        },
        "analyzer": {
          "my_analyzer": {
            "type": "custom",
            "char_filter": ["html_strip"],
            "tokenizer": "standard",
            "filter": ["my_lower", "my_stop", "my_stem"]
          },
          "autocomplete_analyzer": {
            "type": "custom",
            "tokenizer": "standard",
            "filter": ["lowercase", "edge_ngram"]
          }
        }
      }
    }
  }

Test analyzer:
  GET /products/_analyze
  {
    "analyzer": "english",
    "text": "Running quickly through forests"
  }
```

### 2.4 Day 2 Practice

```
1. Create "articles" index with explicit mapping:
   title (text + keyword), author (keyword), body (text, english analyzer),
   published_at (date), tags (keyword), view_count (integer).
2. Test the "english" analyzer on: "The cats are running quickly".
3. Create a custom "autocomplete_analyzer" using edge_ngram (min=2, max=10).
4. Index in test: WHY does "name.keyword" matter for sorting? Index 5 products and
   try sorting by "name" (fails on text) vs "name.keyword" (works).
```

---

## Day 3 — Search Queries (The Core Of Elasticsearch)

### 3.1 Query DSL Structure

```json
GET /products/_search
{
  "query": {  ... },          // what to match
  "sort":  [ ... ],           // how to sort results
  "from":  0,                 // pagination offset
  "size":  10,                // results per page
  "_source": ["name", "price"], // fields to return (projection)
  "highlight": { ... },       // highlight matching terms
  "aggs":  { ... }            // aggregations (Day 4)
}
```

### 3.2 Leaf Queries (Match Single Field)

```json
// match — full-text search (analyzed)
GET /products/_search
{ "query": { "match": { "name": "echo dot speaker" } } }
// Finds documents containing ANY of: echo, dot, speaker (OR by default)

// match with AND operator (all terms must appear)
{ "query": { "match": { "name": { "query": "echo dot", "operator": "and" } } } }

// match_phrase — words must appear in exact order
{ "query": { "match_phrase": { "description": "compact smart speaker" } } }

// match_phrase_prefix — autocomplete (last word is prefix)
{ "query": { "match_phrase_prefix": { "name": "echo d" } } }
// Matches: "echo dot", "echo device" etc.

// term — exact match on keyword/numeric (NOT analyzed)
{ "query": { "term": { "category": "Electronics" } } }
// WRONG for text fields — use match for text, term only for keyword/numeric.

// terms — match any value in list (SQL: WHERE category IN (...))
{ "query": { "terms": { "category": ["Electronics", "Books"] } } }

// range — numeric or date range
{ "query": { "range": { "price": { "gte": 1000, "lte": 5000 } } } }
{ "query": { "range": { "launched_at": { "gte": "2023-01-01", "lte": "now" } } } }

// prefix — keyword starts with prefix
{ "query": { "prefix": { "category": "Elect" } } }

// wildcard — glob patterns (slow — avoid in production on large indices)
{ "query": { "wildcard": { "name": "echo*" } } }

// exists — field is present and not null
{ "query": { "exists": { "field": "rating" } } }

// fuzzy — typo-tolerant match ("echoo" → "echo")
{ "query": { "fuzzy": { "name": { "value": "echoo", "fuzziness": "AUTO" } } } }
// fuzziness AUTO: 0-2 chars → exact, 3-5 → 1 edit, >5 → 2 edits.
```

### 3.3 Compound Queries (Combine Multiple Conditions)

```json
// bool query — the most important query type
{
  "query": {
    "bool": {
      "must":     [ ... ],   // AND — affects score (relevance)
      "filter":   [ ... ],   // AND — does NOT affect score (faster — cached)
      "should":   [ ... ],   // OR  — boosts score if matches (optional)
      "must_not": [ ... ]    // NOT — excludes matches, not scored
    }
  }
}

// Example: "Electronics products, price 1000-5000, name matches 'speaker', in-stock preferred"
GET /products/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "name": "speaker" } }
      ],
      "filter": [
        { "term":  { "category": "Electronics" } },
        { "range": { "price": { "gte": 1000, "lte": 5000 } } }
      ],
      "should": [
        { "range": { "stock": { "gt": 0 } } }
      ]
    }
  }
}

// dis_max — best field query (returns max score across fields)
{
  "query": {
    "dis_max": {
      "queries": [
        { "match": { "name": "echo speaker" } },
        { "match": { "description": "echo speaker" } }
      ],
      "tie_breaker": 0.3
    }
  }
}
// Prevents rewarding a doc that weakly matches in many fields over one that strongly matches one.

// multi_match — search across multiple fields
{
  "query": {
    "multi_match": {
      "query": "echo speaker",
      "fields": ["name^3", "description", "tags^2"],
      "type": "best_fields"
    }
  }
}
// ^3 = boost name field 3× more than others.

// constant_score — apply filter without scoring overhead
{
  "query": {
    "constant_score": {
      "filter": { "term": { "category": "Electronics" } },
      "boost": 1.0
    }
  }
}
```

### 3.4 Relevance Scoring (BM25)

```
Elasticsearch uses BM25 (Best Match 25) for relevance scoring.

BM25 factors:
  1. Term Frequency (TF): how often does the search term appear in the document?
     More occurrences = higher score. But with diminishing returns (not linear).

  2. Inverse Document Frequency (IDF): how rare is the term across all documents?
     Rare terms = more informative = higher weight.
     "the", "is" → DF=100% → IDF≈0 (no information gain).
     "Elasticsearch" → DF=0.1% → high IDF → boosts score significantly.

  3. Field length normalization: shorter fields score higher for same term.
     "echo" in title="echo" vs title="echo dot speaker smart home device":
     → shorter title scores higher (term is more prominent).

Check score explanation:
  GET /products/_search
  {
    "explain": true,
    "query": { "match": { "name": "echo" } }
  }
  // Returns detailed score breakdown: tf, idf, field norm per shard.

Score manipulation:
  "boost": 2.0          → multiply score by 2 for this clause.
  "function_score"      → custom score: combine ES score with business rules.
  "script_score"        → score = ES_score * log(1 + doc['rating'].value)
```

### 3.5 Pagination (Deep Pagination Problem)

```json
// Standard pagination (from + size): OK for first 10,000 results
{ "from": 0, "size": 10 }    // page 1
{ "from": 10, "size": 10 }   // page 2
{ "from": 9990, "size": 10 } // page 1000 ← EXPENSIVE: ES collects 10000 docs to sort

// Deep pagination problem:
//   from=100000, size=10 → ES on EACH SHARD collects top 100010 docs,
//   coordinator merges (3 shards × 100010) = 300030 docs just to return 10.
//   Memory + CPU scales with from value.

// Solution 1: search_after (stateless cursor — for sequential pagination)
GET /products/_search
{
  "size": 10,
  "sort": [{ "price": "asc" }, { "_id": "asc" }],   // must include tiebreaker
  "search_after": [4999, "ASIN_B09X123"]              // values from last hit's sort fields
}
// O(size) memory per request regardless of depth. ✓

// Solution 2: scroll API (for exporting all documents — NOT user-facing pagination)
POST /products/_search?scroll=1m
{ "size": 100, "query": { "match_all": {} } }
// Returns scroll_id. Use: GET /_search/scroll { "scroll": "1m", "scroll_id": "..." }
// Scroll creates a point-in-time snapshot. Delete when done.

// Solution 3: Point In Time (PIT) + search_after (recommended for modern ES 7.10+)
POST /products/_pit?keep_alive=5m          // creates PIT ID
GET /_search
{
  "pit": { "id": "<pit_id>", "keep_alive": "5m" },
  "size": 10,
  "sort": [{ "_shard_doc": "asc" }],
  "search_after": [100]
}
```

### 3.6 Day 3 Practice

```
1. Index 20 products with varied categories, prices, ratings.
2. Write a bool query: Electronics, price 1000-5000, match "smart" in description.
3. Add "should": boost products with rating > 4.0.
4. Try "fuzzy" query with a typo ("smrat speaker") → see if it finds results.
5. Use "explain": true to understand why one product scored higher than another.
6. Implement search_after pagination: get page 1, use last sort values for page 2.
```

---

## Day 4 — Aggregations And Analytics

### 4.1 Aggregation Types

```
Three families:
  1. Bucket aggregations: GROUP BY equivalent. Create buckets of documents.
  2. Metric aggregations: compute numbers (sum, avg, min, max, percentiles).
  3. Pipeline aggregations: compute on output of other aggregations.
```

### 4.2 Bucket Aggregations

```json
// terms — group by field value (like GROUP BY + COUNT)
GET /products/_search
{
  "size": 0,                        // don't return documents, just aggregations
  "aggs": {
    "by_category": {
      "terms": {
        "field": "category",        // must be keyword type
        "size": 10                  // top-10 categories by doc count
      }
    }
  }
}
// Result: { "buckets": [{ "key": "Electronics", "doc_count": 4521 }, ...] }

// range — custom buckets by numeric range
{
  "aggs": {
    "price_ranges": {
      "range": {
        "field": "price",
        "ranges": [
          { "to": 1000, "key": "budget" },
          { "from": 1000, "to": 5000, "key": "mid" },
          { "from": 5000, "key": "premium" }
        ]
      }
    }
  }
}

// date_histogram — bucket by time (for time-series charts)
{
  "aggs": {
    "sales_over_time": {
      "date_histogram": {
        "field": "launched_at",
        "calendar_interval": "month"   // or "day", "week", "year", "1h"
      }
    }
  }
}

// Nested aggregations (subaggregations inside buckets)
{
  "aggs": {
    "by_category": {
      "terms": { "field": "category", "size": 5 },
      "aggs": {
        "avg_price": { "avg": { "field": "price" } },
        "max_rating": { "max": { "field": "rating" } }
      }
    }
  }
}
// Result: each category bucket now has avg_price and max_rating computed inside it.
```

### 4.3 Metric Aggregations

```json
{
  "size": 0,
  "aggs": {
    "avg_price":    { "avg":            { "field": "price" } },
    "total_stock":  { "sum":            { "field": "stock" } },
    "min_price":    { "min":            { "field": "price" } },
    "max_price":    { "max":            { "field": "price" } },
    "price_stats":  { "stats":          { "field": "price" } },
    "price_ext":    { "extended_stats": { "field": "price" } },  // + variance, std_dev
    "unique_cats":  { "cardinality":    { "field": "category" } }, // approx COUNT DISTINCT
    "percentiles":  {
      "percentiles": {
        "field": "price",
        "percents": [25, 50, 75, 95, 99]   // price at p25, median, p75, p95, p99
      }
    }
  }
}
```

### 4.4 Filter Aggregation + Global Aggregation

```json
// filter — run subagg only on filtered subset
{
  "aggs": {
    "electronics_only": {
      "filter": { "term": { "category": "Electronics" } },
      "aggs": {
        "avg_price": { "avg": { "field": "price" } }
      }
    }
  }
}

// global — escape query scope, aggregate entire index
{
  "query": { "term": { "category": "Electronics" } },
  "aggs": {
    "all_products_avg": {
      "global": {},
      "aggs": { "avg_price": { "avg": { "field": "price" } } }
    },
    "electronics_avg": { "avg": { "field": "price" } }
  }
}
// Returns: avg price of Electronics (query-scoped) AND avg price of ALL products (global).
```

### 4.5 Pipeline Aggregations

```json
// avg_bucket — average of bucket values (second-level computation)
{
  "aggs": {
    "monthly_sales": {
      "date_histogram": { "field": "launched_at", "calendar_interval": "month" },
      "aggs": { "total_sales": { "sum": { "field": "stock" } } }
    },
    "avg_monthly_sales": {
      "avg_bucket": { "buckets_path": "monthly_sales>total_sales" }
    }
  }
}

// moving_avg — smoothed trend line for time-series dashboards
{
  "aggs": {
    "weekly": {
      "date_histogram": { "field": "date", "calendar_interval": "week" },
      "aggs": {
        "sales": { "sum": { "field": "amount" } },
        "smoothed": {
          "moving_fn": {
            "buckets_path": "sales",
            "window": 4,
            "script": "MovingFunctions.unweightedAvg(values)"
          }
        }
      }
    }
  }
}
```

### 4.6 Day 4 Practice

```
1. Write a "faceted search" query like e-commerce:
   - Search: "smart home"
   - Aggregations: count by category, price ranges (budget/mid/premium), avg rating per category.
   - Use size:0 to get only aggregation results.
2. Build a time-series: index 12 documents with dates one per month. Date_histogram monthly.
3. Nested agg: top-5 categories, each showing avg price and total stock.
4. Use "cardinality" to count distinct tags across all products.
```

---

## Day 5 — Index Management, Sharding, And Cluster Operations

### 5.1 Sharding Deep Dive

```
An index is divided into primary shards. Each shard is a full Lucene index.
Replicas are copies of primary shards (HA + read scaling).

Routing: which shard does document go to?
  shard = hash(document._id) % number_of_primary_shards
  → Consistent: same _id always goes to same shard.
  → Custom routing: PUT /products/_doc/1?routing=category_electronics
    → All Electronics go to shard 2 → queries can target just that shard.

Primary shard count is FIXED at index creation. Cannot change after.
  → Changing requires reindex (expensive).
  → Plan ahead: over-shard slightly rather than under-shard.

Replica count can change anytime:
  PUT /products/_settings { "index": { "number_of_replicas": 2 } }

Sizing rule:
  Recommended shard size: 10–50 GB per shard.
  For 100 GB index: 3–5 primary shards, each 20-33 GB. ✓
  For 10 GB index: 1 primary shard. Don't over-shard (overhead > benefit below 10 GB).

Shard allocation:
  ES spreads shards across nodes automatically.
  Primary and replica of the same shard never on same node (HA).
  3 nodes, index with 3 primaries + 1 replica = 6 shards across 3 nodes = 2 shards/node. ✓
```

### 5.2 Index Lifecycle Management (ILM)

```
ILM automatically manages index age and size transitions for log-style data.
Phases: HOT → WARM → COLD → DELETE.

PUT /_ilm/policy/logs_policy
{
  "policy": {
    "phases": {
      "hot": {
        "min_age": "0ms",
        "actions": {
          "rollover": {
            "max_size": "50gb",     // rollover when index hits 50 GB
            "max_age": "7d",        // or when it's 7 days old
            "max_docs": 10000000    // or when it has 10M documents
          }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink":      { "number_of_shards": 1 },  // merge shards to 1 (read-only)
          "forcemerge":  { "max_num_segments": 1 }   // compact Lucene segments
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "freeze": {}    // unload index from memory (searchable from disk, slow)
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": { "delete": {} }   // auto-delete after 90 days
      }
    }
  }
}

Index templates (create index with ILM policy automatically):
  PUT /_index_template/logs_template
  {
    "index_patterns": ["logs-*"],
    "template": {
      "settings": {
        "number_of_shards": 2,
        "index.lifecycle.name": "logs_policy",
        "index.lifecycle.rollover_alias": "logs"
      }
    }
  }
```

### 5.3 Aliases

```
Alias = named pointer to one or more indices.
Zero-downtime reindex pattern:
  1. Old index: products_v1. Alias: products → products_v1.
  2. Create new index: products_v2 (with new mapping).
  3. Reindex data: POST /_reindex { "source": {"index":"products_v1"}, "dest": {"index":"products_v2"} }
  4. Swap alias (atomic!):
     POST /_aliases
     {
       "actions": [
         { "remove": { "index": "products_v1", "alias": "products" } },
         { "add":    { "index": "products_v2", "alias": "products" } }
       ]
     }
  5. Application code always uses alias "products" → no downtime, no code change.

Write alias (for data streams / ILM):
  { "add": { "index": "products_v2", "alias": "products", "is_write_index": true } }
  → Writes go to write index. Reads served from all indices in alias.

Filter alias (create scoped virtual index):
  { "add": { "index": "products", "alias": "electronics", "filter": { "term": { "category": "Electronics" } } } }
  → GET /electronics/_search → only returns Electronics documents.
```

### 5.4 Reindex And Update By Query

```bash
# Reindex (copy from one index to another, optionally with transform)
POST /_reindex
{
  "source": {
    "index": "products_v1",
    "query": { "term": { "category": "Electronics" } }   // reindex subset only
  },
  "dest": { "index": "products_v2" },
  "script": {
    "source": "ctx._source.price_cents = ctx._source.price * 100",   // transform
    "lang": "painless"
  }
}

# Update by query (update all matching documents in place)
POST /products/_update_by_query
{
  "query": { "term": { "category": "Electronics" } },
  "script": {
    "source": "ctx._source.discount = 0.10",
    "lang": "painless"
  }
}

# Delete by query
POST /products/_delete_by_query
{ "query": { "range": { "stock": { "lte": 0 } } } }
```

### 5.5 Day 5 Practice

```
1. Create index "logs-2024.03" with ILM policy (rollover at 10 docs for testing).
2. Index 15 documents. Observe rollover creating "logs-2024.03-000002".
3. Create alias "products" → "products_v1". Verify reads work through alias.
4. Create "products_v2" with extra field. Reindex v1 → v2. Swap alias atomically.
5. Run "_update_by_query" to add a "discount": 5 field to all Electronics.
```

---

## Day 6 — Advanced Search Features

### 6.1 Geo Queries

```json
// geo_distance — find products within 5km of a location
GET /products/_search
{
  "query": {
    "bool": {
      "filter": {
        "geo_distance": {
          "distance": "5km",
          "location": { "lat": 19.0760, "lon": 72.8777 }
        }
      }
    }
  },
  "sort": [{
    "_geo_distance": {
      "location": { "lat": 19.0760, "lon": 72.8777 },
      "order": "asc",
      "unit": "km"
    }
  }]
}

// geo_bounding_box — find within a rectangular area
{
  "filter": {
    "geo_bounding_box": {
      "location": {
        "top_left":     { "lat": 20.0, "lon": 72.0 },
        "bottom_right": { "lat": 18.0, "lon": 74.0 }
      }
    }
  }
}
```

### 6.2 Autocomplete / Suggest

```json
// Completion suggester (fast prefix suggest — uses FST in memory)
// Mapping:
"suggest_field": {
  "type": "completion",
  "analyzer": "simple"
}

// Index document with suggest data:
POST /products/_doc
{
  "name": "Echo Dot",
  "suggest_field": {
    "input": ["Echo Dot", "Smart Speaker", "Alexa Device"],
    "weight": 10    // higher weight = higher in suggestions
  }
}

// Query:
POST /products/_search
{
  "suggest": {
    "product-suggest": {
      "prefix": "echo",
      "completion": {
        "field": "suggest_field",
        "size": 5,
        "fuzzy": { "fuzziness": 1 }   // typo tolerance
      }
    }
  }
}

// Edge NGram approach (more flexible, searchable in normal queries too):
// See analyzer setup in Day 2. Index with autocomplete_analyzer.
// Query with match_phrase_prefix or match on the edge_ngram field.
```

### 6.3 Highlighting

```json
GET /products/_search
{
  "query": { "match": { "description": "smart speaker" } },
  "highlight": {
    "fields": {
      "description": {
        "pre_tags": ["<strong>"],
        "post_tags": ["</strong>"],
        "fragment_size": 150,     // chars per highlighted snippet
        "number_of_fragments": 3  // max snippets returned
      }
    }
  }
}
// Returns: "description_highlight": ["...Built as a <strong>smart</strong> <strong>speaker</strong>..."]
```

### 6.4 Nested And Join Queries

```json
// Nested query (query on nested object array — see Day 2 mapping)
GET /products/_search
{
  "query": {
    "nested": {
      "path": "reviews",
      "query": {
        "bool": {
          "must": [
            { "match":  { "reviews.user": "Alice" } },
            { "range":  { "reviews.rating": { "gte": 5 } } }
          ]
        }
      },
      "inner_hits": {}    // return the matching nested docs that caused the hit
    }
  }
}

// function_score — custom ranking with business rules
GET /products/_search
{
  "query": {
    "function_score": {
      "query": { "match": { "name": "speaker" } },
      "functions": [
        { "field_value_factor": { "field": "rating", "factor": 1.2, "modifier": "sqrt" } },
        { "gauss": { "launched_at": { "origin": "now", "scale": "30d", "decay": 0.5 } } }
      ],
      "score_mode": "multiply",
      "boost_mode": "multiply"
    }
  }
}
// Score = original_relevance × sqrt(rating × 1.2) × time_decay_factor
// Boosts newer, highly-rated products in search results.
```

### 6.5 Percolate (Reverse Search — Save Queries, Match Incoming Documents)

```json
// Normal search: you have documents, find matching queries.
// Percolate: you have queries stored, find which queries match an incoming document.

// Use case: "Alert me whenever a product matching 'Sony headphones under 5000' is added."

// 1. Store query in a percolator index:
PUT /alerts
{ "mappings": { "properties": {
    "query":    { "type": "percolator" },
    "category": { "type": "keyword" }
}}}

POST /alerts/_doc/alert1
{
  "query": {
    "bool": {
      "must":   [ { "match": { "name": "headphones" } } ],
      "filter": [ { "term": { "category": "Electronics" } },
                  { "range": { "price": { "lte": 5000 } } } ]
    }
  }
}

// 2. When new product added, check which alerts match:
GET /alerts/_search
{
  "query": {
    "percolate": {
      "field": "query",
      "document": { "name": "Sony WH1000 Headphones", "category": "Electronics", "price": 4500 }
    }
  }
}
// Returns: alert1 matched → trigger notification.
```

### 6.6 Day 6 Practice

```
1. Add geo_point "location" to 5 products with Mumbai coordinates (+/- 10km).
2. Query: "Find products within 3km of Bandra (lat:19.0544, lon:72.8404), sorted by distance."
3. Set up completion suggester for product names. Test with "ech" prefix.
4. Implement function_score: boost by rating × recency decay.
5. Set up percolater: store a price alert query, then add a product and check which alerts fire.
```

---

## Day 7 — Production Operations And Interview Mastery

### 7.1 Performance Tuning

```
Write performance:
  → Increase refresh_interval: default 1s (index becomes searchable every 1s).
    During bulk import: PUT /products/_settings { "refresh_interval": "-1" }
    After import: reset to "1s".
  → Bulk API: always use bulk instead of individual inserts:
    POST /_bulk
    { "index": { "_index": "products", "_id": "1" } }
    { "name": "Product 1", "price": 1000 }
    { "index": { "_index": "products", "_id": "2" } }
    { "name": "Product 2", "price": 2000 }
    Optimal batch size: 5–15 MB per bulk request. Not too large (memory), not too small (overhead).
  → number_of_replicas: 0 during bulk load → 1 after. Replicas slow down writes.

Read (search) performance:
  → Filter context over query context: filters are cached, don't affect score.
    Use "filter" inside bool for all non-scoring criteria (category, price range, date).
    Use "must" only for full-text that needs scoring.
  → Request cache: aggregation results on same query cached across shards.
  → Field data: avoid aggregating on text fields (loads entire field into memory).
    Use keyword subfields or doc values instead.
  → Avoid wildcard and script queries in hot path (linear scan, no inverted index).
  → Avoid deep pagination (use search_after instead of from > 10000).

Segment merging:
  → Lucene accumulates small segments on write. Periodically merges to fewer large segments.
  → More segments = slower search (must check each). Fewer large segments = faster search.
  → force_merge after index is finalized (read-only): POST /products/_forcemerge?max_num_segments=1
  → In production: let ES auto-merge. Force only on frozen/historical indices.

JVM heap sizing:
  → Rule: 50% of node RAM, max 31 GB.
  → 31 GB limit: JVM compressed object pointers (COoPs) work below 32 GB → huge performance boost.
  → ES_JAVA_OPTS="-Xms16g -Xmx16g" (always equal min and max to prevent heap resizing pauses).
  → Remaining 50% RAM: Lucene OS file system cache (just as important as JVM heap).
```

### 7.2 Monitoring And Cluster Health

```bash
# Cluster health
GET /_cluster/health
# green = all primary + replica shards assigned.
# yellow = all primaries assigned but some replicas unassigned (single node: normal).
# red = some primaries unassigned (data loss risk).

# Node stats
GET /_nodes/stats

# Hot threads (what is each node doing right now?)
GET /_nodes/hot_threads

# Pending tasks
GET /_cluster/pending_tasks

# Slow log — log searches taking > threshold
PUT /products/_settings
{
  "index.search.slowlog.threshold.query.warn": "5s",
  "index.search.slowlog.threshold.query.info": "1s",
  "index.search.slowlog.threshold.fetch.warn": "1s"
}

# Node disk usage alert: if disk > 85% → ES stops routing new shards (flood stage).
# Disk watermarks:
PUT /_cluster/settings
{
  "transient": {
    "cluster.routing.allocation.disk.watermark.low":   "80%",  // warn at 80%
    "cluster.routing.allocation.disk.watermark.high":  "85%",  // stop new shards at 85%
    "cluster.routing.allocation.disk.watermark.flood_stage": "90%"  // make indices read-only at 90%
  }
}
```

### 7.3 Spring Boot Integration

```java
// Maven: spring-boot-starter-data-elasticsearch

// application.yml
// spring:
//   elasticsearch:
//     uris: http://localhost:9200
//     username: elastic   (if security enabled)
//     password: password

// Entity mapping
@Document(indexName = "products", shards = 3, replicas = 1)
public class Product {
    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "english")
    private String name;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Float)
    private Float price;

    @Field(type = FieldType.Date)
    private LocalDateTime launchedAt;

    // getters/setters
}

// Repository
public interface ProductRepository extends ElasticsearchRepository<Product, String> {
    // Spring Data generates queries from method names:
    List<Product> findByCategory(String category);
    List<Product> findByPriceBetween(Float min, Float max);
    List<Product> findByNameContaining(String name);
    Page<Product> findByCategoryAndRatingGreaterThan(String cat, Float rating, Pageable pageable);
}

// Custom query with ElasticsearchOperations
@Service
public class ProductSearchService {

    @Autowired ElasticsearchOperations ops;

    public SearchHits<Product> search(String keyword, String category, Float minPrice, Float maxPrice) {
        BoolQuery.Builder boolQuery = QueryBuilders.bool()
            .must(QueryBuilders.match().field("name").query(keyword).build()._toQuery())
            .filter(QueryBuilders.term().field("category").value(category).build()._toQuery())
            .filter(QueryBuilders.range().field("price")
                .gte(JsonData.of(minPrice)).lte(JsonData.of(maxPrice)).build()._toQuery());

        NativeQuery query = NativeQuery.builder()
            .withQuery(boolQuery.build()._toQuery())
            .withSort(Sort.by(Sort.Direction.DESC, "rating"))
            .withPageable(PageRequest.of(0, 10))
            .withHighlightQuery(new HighlightQuery(
                new Highlight(List.of(new HighlightField("name"), new HighlightField("description"))),
                Product.class))
            .build();

        return ops.search(query, Product.class);
    }
}
```

### 7.4 Interview Cheat Sheet

```
"How does Elasticsearch achieve fast full-text search?"
  → Inverted index: at index time, each document's text is analyzed into tokens.
    A mapping is built: token → [list of document IDs containing that token].
    At query time: look up token in inverted index → O(1) → get doc IDs → score them.
    Compare to DB: LIKE '%speaker%' → linear scan. ES: inverted index → O(log N) lookup.

"What is the difference between text and keyword fields?"
  → text: analyzed (tokenized, lowercased, stemmed). For full-text search. Cannot sort/aggregate.
  → keyword: stored as-is. For exact match, sorting, faceted aggregations.
  → Best practice: multi-field with both (name as text + name.keyword).

"Explain the difference between must and filter in bool query."
  → must: adds to relevance score. Input to BM25 scoring. Slightly slower.
  → filter: binary include/exclude. Does NOT affect score. Results are CACHED.
    Always use filter for: dates, categories, price ranges, boolean flags.
    Only use must for: full-text fields where relevance score matters.

"How many shards should I use?"
  → Target shard size: 10-50 GB. For a 100 GB index: 3-5 shards.
  → Avoid too many small shards (overhead per shard: memory, file handles, CPU).
  → Rule: heap supports ~20 shards per GB. A node with 16 GB heap handles ~320 shards.

"How do you handle near real-time search requirements?"
  → ES writes go to an in-memory buffer → flushed to a new Lucene segment every 1s (refresh).
  → Documents searchable after refresh_interval (default 1s) = "near real-time."
  → For truly immediate search: POST /products/_refresh manually after write.

"Elasticsearch vs Solr vs OpenSearch?"
  → ES: managed by Elastic, richer features (ML, ELSER), best ecosystem.
  → Solr: older, Zookeeper-based, strong in enterprise, XML config.
  → OpenSearch: AWS fork of ES 7.10 (open source, no SSPL), used in AWS OpenSearch Service.
  → For AWS: use OpenSearch Service. For full control + ML features: Elasticsearch.

"How do you keep Elasticsearch in sync with your primary database?"
  → Change Data Capture (CDC): Debezium reads DB binlog → Kafka → Logstash/custom consumer → ES.
  → Dual write: app writes to DB + ES simultaneously (risk of partial failure).
  → Batch sync: periodic job queries DB changes since last run → bulk upsert to ES.
  → Event-driven: on DB write → publish event → consumer updates ES (eventually consistent).
  → Best: CDC (Debezium) for real-time strong consistency without app-layer coupling.
```

### 7.5 Production Architecture Blueprint

```
Production Best Practices Checklist:

Cluster:
  ✅ Min 3 nodes (for quorum-based master election — prevents split-brain).
  ✅ Dedicated master-eligible nodes (3 small nodes: 4 GB RAM each) separate from data nodes.
  ✅ Data nodes: memory optimized (32–64 GB RAM). Heap = 31 GB, rest for Lucene cache.
  ✅ Cross-zone: nodes spread across 3 AZs.

Indices:
  ✅ Explicit mappings (never rely on dynamic mapping in production).
  ✅ number_of_shards sized for expected data volume (10-50 GB per shard).
  ✅ ILM policies for time-series data (logs, events, metrics).
  ✅ Aliases in code (zero-downtime reindex possible anytime).
  ✅ Index templates for auto-creation of recurring index patterns.

Performance:
  ✅ Use filter context for all non-scoring criteria.
  ✅ Avoid deep pagination (use search_after or scroll).
  ✅ Force merge archived/frozen indices (max_num_segments=1).
  ✅ Set refresh_interval=-1 during bulk loads.

Security:
  ✅ TLS for inter-node and client communication.
  ✅ Role-based access control (read-only roles for reporting users).
  ✅ Never expose ES port 9200 to internet.
  ✅ Audit logging enabled.

Monitoring:
  ✅ Stack monitoring or Prometheus + Grafana.
  ✅ Alert on: cluster health yellow/red, high JVM heap (> 85%), disk > 75%.
  ✅ Slow query log enabled.
```

---

## Summary: 7-Day Roadmap

| Day | Topics | Key Concepts |
|---|---|---|
| **1** | Fundamentals, CRUD | Index, Document, Node, Shard, REST API |
| **2** | Mappings, Data Types, Analyzers | text vs keyword, BM25, edge_ngram autocomplete |
| **3** | Search Queries | match, bool/must/filter/should, fuzzy, search_after |
| **4** | Aggregations | terms, range, date_histogram, nested aggs, pipeline aggs |
| **5** | Index Management | Sharding, ILM, Aliases, Reindex, zero-downtime schema change |
| **6** | Advanced Features | Geo queries, completion suggester, function_score, percolate |
| **7** | Production + Interviews | JVM tuning, slow logs, Spring Boot, CDC sync, interview questions |

---

## Resources

- **Official Docs**: https://www.elastic.co/guide/en/elasticsearch/reference/current/
- **Kibana Dev Tools**: http://localhost:5601 → the best way to explore queries interactively.
- **Book**: "Elasticsearch: The Definitive Guide" (free online).
- **Spring Data Elasticsearch**: https://docs.spring.io/spring-data/elasticsearch/docs/current/reference/html/
- **AWS OpenSearch**: https://docs.aws.amazon.com/opensearch-service/
- **Elasticsearch Labs (ML features)**: https://www.elastic.co/search-labs
