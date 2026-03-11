# High-Level Design: LeetCode (SDE3 Interview)

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: problem catalog, code submission, judge, contests, leaderboards.
2. Show API surface and data model.
3. Walk through architecture with focus on the **Online Judge** (the hardest part).
4. Explain end-to-end submission flow, sandboxing, scaling, and consistency.
5. Cover reliability, security, and trade-offs.

---

## 1. Problem Statement And Scope

Design a competitive programming platform like LeetCode supporting:
- Browse, search, and filter a catalog of coding problems.
- Write and submit code in multiple languages (Python, Java, C++, Go, JS, etc.).
- Secure code execution with time/memory limits per language.
- Real-time submission feedback (Accepted / Wrong Answer / TLE / MLE / Runtime Error).
- Contests with real-time leaderboards and ranking.
- Discussion forums, editorial hints, and community solutions.
- Subscription (free vs premium) with locked problems and company-specific sets.
- User profile with streak, acceptance stats, and progress tracking.

### 1.1 In Scope
- Problem catalog and metadata management.
- Code submission and Online Judge (sandbox execution).
- Contest management and real-time leaderboard.
- User profile, progress, and subscription.
- Notification and discussion system.

### 1.2 Out Of Scope
- Full payment gateway internals.
- Deep ML/NLP for problem recommendations at model depth.
- Native mobile app internals.

---

## 2. Requirements

### 2.1 Functional Requirements
1. User browses/searches problems by difficulty, topic, company tag.
2. User writes code in an in-browser editor with syntax highlighting.
3. User runs code against sample test cases (Run button — no full judge).
4. User submits code — judged against all hidden test cases.
5. System returns verdict: Accepted, Wrong Answer, TLE, MLE, Runtime Error, Compile Error.
6. User views submission history and accepted solutions (after solve or subscription).
7. Contests: fixed-duration timed events, score based on problems solved + penalty time.
8. Real-time leaderboard updates during contest.
9. User profile tracks streaks, acceptance rate, topic-wise progress.
10. Discussion: forum threads per problem, comment nesting, upvote.

### 2.2 Non-Functional Requirements
- **Availability**: 99.99% for problem browsing; 99.9% for judge (execution infra can have brief outages).
- **Latency**: Submission verdict delivered in < 5 seconds (P95) for typical problems.
- **Scale**: 5 million MAU; 500K DAU; peak contest concurrency: 100K simultaneous submissions.
- **Security**: Sandboxed execution — user code cannot access host filesystem, network, or other users' data.
- **Isolation**: One user's infinite loop / fork bomb cannot affect other users' executions.
- **Fairness**: Same problem, same language → deterministic time/memory measurement.

---

## 3. Back-Of-The-Envelope Capacity Planning

| Parameter | Value |
|---|---|
| MAU | 5 million |
| DAU | 500K |
| Submissions/user/day | 8 |
| Run (sample test) vs Submit ratio | 60% / 40% |
| Peak contest: submissions/sec | 2,000 |
| Problems in catalog | 3,000+ |
| Test cases per problem | 100–500 |
| Avg submission size | 2 KB |
| Avg execution time per test case | 0.5s |

### 3.1 Submission Throughput
- Normal day: 500K * 8 * 0.4 = **1.6M submissions/day** → **18 submissions/sec avg**.
- Peak contest: **2,000 submissions/sec**.
- Each submission runs up to 500 test cases × 0.5s → up to **250s CPU time/submission** at max.
- Need massive parallelism in judge workers.

### 3.2 Judge Worker Estimate
- At 2,000 submissions/sec peak, avg 5s per submission → **10,000 concurrent judge workers** needed at peak.
- Use auto-scaling container pool; scale down after contest.

### 3.3 Storage
- Code submissions: 1.6M/day * 2 KB = **3.2 GB/day** → ~1.2 TB/year.
- Test case input/output: 3K problems * 500 cases * 10 KB avg = **15 GB** (read-heavy, cacheable).
- Problem metadata + editorial: **~500 MB** (trivially small).

---

## 4. High-Level API Design

### 4.1 Problem Catalog

```http
# List problems with filters
GET /v1/problems?difficulty=medium&tags=dp,graph&company=google
                &status=unsolved&page=1&size=50

# Get problem detail
GET /v1/problems/{problemSlug}
Response: {
  id, title, slug, difficulty, description_html, examples,
  constraints, starter_code_by_language, topics, acceptance_rate,
  is_premium, hints (partial for free users)
}

# Search problems
GET /v1/problems/search?q=two+sum
```

### 4.2 Code Execution

```http
# Run against sample test cases (fast, no judge queue)
POST /v1/problems/{problemId}/run
{
  "language": "python3",
  "code": "class Solution:\n    def twoSum(...):\n        ...",
  "custom_input": "nums=[2,7,11,15]\ntarget=9"   # optional override
}
Response: { "run_id": "run_abc", "status": "queued" }

# Poll run result
GET /v1/run/{runId}
Response: {
  "status": "finished",
  "verdict": "Correct" | "Wrong Answer" | "Runtime Error" | ...,
  "stdout": "...", "stderr": "...", "runtime_ms": 45, "memory_kb": 12800
}

# Submit solution (judged against all test cases)
POST /v1/problems/{problemId}/submit
{
  "language": "java",
  "code": "class Solution { ... }"
}
Response: { "submission_id": "sub_xyz", "status": "queued" }

# Poll submission result
GET /v1/submissions/{submissionId}
Response: {
  "status": "accepted" | "wrong_answer" | "time_limit_exceeded" |
            "memory_limit_exceeded" | "runtime_error" | "compile_error",
  "runtime_ms": 120, "memory_kb": 34560,
  "runtime_percentile": 84.2,   # beats 84.2% of submissions
  "memory_percentile": 71.5,
  "total_test_cases": 63,
  "passed_test_cases": 63,
  "failed_test_case": null | { input, expected, actual }
}
```

### 4.3 Contests

```http
# List upcoming/active contests
GET /v1/contests?status=upcoming|active|past

# Register for contest
POST /v1/contests/{contestId}/register

# Submit during contest (proxied to judge with contest context)
POST /v1/contests/{contestId}/problems/{problemId}/submit
{
  "language": "cpp",
  "code": "..."
}

# Get contest leaderboard (paginated)
GET /v1/contests/{contestId}/leaderboard?page=1&size=50
Response: ranked list with { rank, user, score, penalty_minutes, problems_solved[] }
```

### 4.4 User Profile

```http
GET /v1/users/{username}/profile
Response: {
  username, avatar, streak, total_solved, easy/medium/hard_counts,
  acceptance_rate, ranking_global, topic_progress[], recent_submissions[]
}

GET /v1/users/{username}/submissions?problemId=...&page=1
```

### 4.5 Discussion

```http
GET  /v1/problems/{problemId}/discussions?sort=most_votes|newest
POST /v1/problems/{problemId}/discussions  { "title": "...", "content_md": "..." }
POST /v1/discussions/{discussionId}/comments { "content_md": "..." }
POST /v1/discussions/{discussionId}/upvote
```

---

## 5. Data Model

### 5.1 Problem Catalog

```sql
CREATE TABLE problems (
  problem_id      INT          PRIMARY KEY,
  slug            VARCHAR(128) UNIQUE NOT NULL,
  title           VARCHAR(256) NOT NULL,
  difficulty      ENUM('easy','medium','hard') NOT NULL,
  description_md  TEXT,
  acceptance_rate DECIMAL(5,2),
  is_premium      BOOLEAN DEFAULT FALSE,
  time_limit_ms   INT NOT NULL DEFAULT 2000,   -- per test case, per language
  memory_limit_kb INT NOT NULL DEFAULT 262144, -- 256 MB
  created_at      TIMESTAMP NOT NULL,
  updated_at      TIMESTAMP NOT NULL
);

CREATE TABLE problem_tags (
  problem_id  INT NOT NULL,
  tag         VARCHAR(64) NOT NULL,
  PRIMARY KEY (problem_id, tag),
  INDEX (tag)
);

CREATE TABLE problem_company_tags (
  problem_id  INT NOT NULL,
  company     VARCHAR(64) NOT NULL,
  frequency   ENUM('high','medium','low'),
  PRIMARY KEY (problem_id, company)
);

-- Language-specific starter code
CREATE TABLE problem_starter_code (
  problem_id  INT NOT NULL,
  language    VARCHAR(32) NOT NULL,
  code        TEXT NOT NULL,
  PRIMARY KEY (problem_id, language)
);

-- Time/memory limits can differ per language (C++ faster than Python)
CREATE TABLE problem_language_config (
  problem_id      INT NOT NULL,
  language        VARCHAR(32) NOT NULL,
  time_limit_ms   INT NOT NULL,
  memory_limit_kb INT NOT NULL,
  PRIMARY KEY (problem_id, language)
);
```

### 5.2 Test Cases (Stored Securely — Never Exposed To Users)

```sql
-- Metadata only in DB; actual input/output stored in object storage
CREATE TABLE test_cases (
  test_case_id  VARCHAR(64) PRIMARY KEY,
  problem_id    INT NOT NULL,
  sequence      INT NOT NULL,             -- ordering
  is_sample     BOOLEAN DEFAULT FALSE,   -- shown on problem page
  input_key     VARCHAR(256) NOT NULL,   -- object store key for input file
  output_key    VARCHAR(256) NOT NULL,   -- object store key for expected output
  time_weight   DECIMAL(3,2) DEFAULT 1.0,
  INDEX (problem_id, sequence)
);
```

**Security**: Test case inputs/outputs stored in a **private** object store bucket. Judge workers access via IAM role — user-submitted code can NEVER reach this bucket (isolated network).

### 5.3 Submissions

```sql
CREATE TABLE submissions (
  submission_id   VARCHAR(64) PRIMARY KEY,
  user_id         VARCHAR(64) NOT NULL,
  problem_id      INT NOT NULL,
  contest_id      VARCHAR(64),           -- null for practice submissions
  language        VARCHAR(32) NOT NULL,
  code            TEXT NOT NULL,         -- or object store key if large
  status          ENUM('queued','running','accepted','wrong_answer',
                       'time_limit_exceeded','memory_limit_exceeded',
                       'runtime_error','compile_error','system_error') NOT NULL,
  runtime_ms      INT,
  memory_kb       INT,
  runtime_percentile DECIMAL(5,2),
  memory_percentile  DECIMAL(5,2),
  passed_tests    INT,
  total_tests     INT,
  error_message   TEXT,
  failed_input    TEXT,                 -- shown only on non-AC
  submitted_at    TIMESTAMP NOT NULL,
  judged_at       TIMESTAMP,
  worker_id       VARCHAR(64),          -- which judge worker handled it
  INDEX (user_id, submitted_at DESC),
  INDEX (problem_id, status, submitted_at DESC),
  INDEX (contest_id, user_id)
);
```

### 5.4 Users And Progress

```sql
CREATE TABLE users (
  user_id         VARCHAR(64) PRIMARY KEY,
  username        VARCHAR(64) UNIQUE NOT NULL,
  email           VARCHAR(256) UNIQUE NOT NULL,
  password_hash   VARCHAR(256) NOT NULL,
  subscription    ENUM('free','premium','premium_plus') DEFAULT 'free',
  subscription_until TIMESTAMP,
  global_rank     INT,
  created_at      TIMESTAMP NOT NULL
);

-- Solved problems (denormalized for fast profile load)
CREATE TABLE user_problem_status (
  user_id     VARCHAR(64) NOT NULL,
  problem_id  INT NOT NULL,
  status      ENUM('attempted','solved') NOT NULL,
  solved_at   TIMESTAMP,
  best_runtime_ms   INT,
  best_memory_kb    INT,
  PRIMARY KEY (user_id, problem_id),
  INDEX (user_id, status)
);

-- Daily streak tracking
CREATE TABLE user_streaks (
  user_id         VARCHAR(64) PRIMARY KEY,
  current_streak  INT NOT NULL DEFAULT 0,
  longest_streak  INT NOT NULL DEFAULT 0,
  last_active_date DATE
);
```

### 5.5 Contest Data

```sql
CREATE TABLE contests (
  contest_id    VARCHAR(64) PRIMARY KEY,
  title         VARCHAR(256) NOT NULL,
  start_time    TIMESTAMP NOT NULL,
  end_time      TIMESTAMP NOT NULL,
  duration_min  INT NOT NULL,
  status        ENUM('upcoming','active','finished'),
  type          ENUM('weekly','biweekly','special')
);

CREATE TABLE contest_problems (
  contest_id    VARCHAR(64) NOT NULL,
  problem_id    INT NOT NULL,
  position      INT NOT NULL,    -- Q1, Q2, Q3, Q4
  score         INT NOT NULL,    -- 3, 4, 5, 6 points typically
  PRIMARY KEY (contest_id, problem_id)
);

CREATE TABLE contest_registrations (
  contest_id  VARCHAR(64) NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  registered_at TIMESTAMP NOT NULL,
  PRIMARY KEY (contest_id, user_id)
);

-- Real-time scoring (updated on each accepted submission)
CREATE TABLE contest_scores (
  contest_id      VARCHAR(64) NOT NULL,
  user_id         VARCHAR(64) NOT NULL,
  score           INT NOT NULL DEFAULT 0,
  penalty_minutes INT NOT NULL DEFAULT 0,  -- total wrong ans penalty
  problems_solved JSONB,   -- {problem_id: {accepted_at, attempts}}
  finish_time     TIMESTAMP,
  rank            INT,
  PRIMARY KEY (contest_id, user_id),
  INDEX (contest_id, score DESC, penalty_minutes ASC)  -- leaderboard query
);
```

---

## 6. High-Level Architecture

```text
+---------------------  Clients  ----------------------+
|  Web Browser (Monaco Editor)  |  Mobile App          |
+----------+---------------------+---------+-----------+
           |                               |
    [CDN: Static JS/CSS]          [API Gateway]
           |                      (Auth, Rate Limit, Route)
           |                               |
  +--------+---------+--------------------+--------+
  |                  |                    |        |
  v                  v                    v        v
+--------+    +----------+      +----------+  +--------+
|Problem |    |Submission|      |Contest   |  |User    |
|Catalog |    |Service   |      |Service   |  |Service |
|Service |    |          |      |          |  |        |
+---+----+    +----+-----+      +----+-----+  +---+----+
    |              |                 |             |
    v              v                 v             v
+-------+    +----------+      +----------+    +------+
|Problem|    | Job Queue|      |Leaderboard    |User  |
|DB     |    | (Kafka / |      |Service   |    |DB    |
|(PG)   |    |  Redis   |      |(Redis    |    |(PG)  |
+-------+    |  Queue)  |      | Sorted   |    +------+
    |         +----+-----+      | Sets)    |
    v              |            +----------+
+-------+    +-----v-----------+
|Redis  |    |  Judge Worker   | × 10,000 (auto-scaled)
|Cache  |    |  Pool           |
+-------+    | (Containers /   |
             |  VMs, sandboxed)|
             +--------+--------+
                      |
             +--------+--------+
             | Object Storage  |
             | (Test cases,    |
             |  submission code|
             |  artifacts)     |
             +-----------------+
```

---

## 7. Detailed Component Design

### 7.1 Online Judge — The Core Of The System

The **Online Judge** is the most interview-critical component. It must be:
- **Secure**: User code cannot escape the sandbox.
- **Fair**: Identical code → identical runtime measurement.
- **Fast**: Verdict in < 5 seconds (P95).
- **Scalable**: Handle 2,000 submissions/sec during contests.

#### 7.1.1 Sandbox Architecture

**Why sandboxing is hard:**  
User code is untrusted. It can try to:
- Read `/etc/passwd` or `/proc` (filesystem access).
- Call `os.system("rm -rf /")` (shell exec).
- Open network sockets and exfiltrate test cases.
- Fork-bomb the host (spawn infinite child processes).
- Allocate infinite memory until OOM.
- Run infinite loops (CPU DoS).

**Sandboxing techniques (defense in depth):**

```
Layer 1: Container isolation (Docker / gVisor)
  - Separate Linux namespace: PID, network, mount, user, IPC.
  - Each submission runs in its own ephemeral container.
  - Container has no network interface (--network none).
  - Read-only filesystem; writable tmpfs only for /tmp (size-limited).

Layer 2: Seccomp (Secure Compute Mode)
  - Whitelist of allowed Linux syscalls (read, write, mmap, exit...).
  - Deny: socket(), fork() beyond limit, exec() arbitrary binaries, mount().
  - Syscall violation → SIGKILL immediately.

Layer 3: Resource limits (cgroups)
  - CPU: SIGALRM after time_limit_ms + grace buffer.
  - Memory: OOM killer when memory_limit_kb exceeded.
  - PIDs: max 50 processes per container (prevents fork bomb).
  - File descriptor limit: max 64.

Layer 4: User namespace remapping
  - Container "root" mapped to unprivileged host UID (UID 100000+).
  - Even if container is "escaped", attacker is unprivileged on host.

Layer 5: Read-only test cases
  - Test case files bind-mounted read-only from a separate volume.
  - No write permission; sandboxed process cannot modify expected output.
```

**gVisor vs Docker:**

| Aspect | Docker + seccomp | gVisor (runsc) |
|---|---|---|
| Isolation depth | Strong but syscall passthrough | Kernel emulation; no host kernel syscalls |
| Performance overhead | Low (~5%) | Higher (~20%) |
| Security | Good | Excellent |
| Use case | High performance, well-known languages | Maximum isolation for untrusted code |

LeetCode-scale systems typically use **Docker + seccomp + cgroups** as the primary approach with gVisor for highest-risk languages.

#### 7.1.2 Execution Flow Per Submission

```
Step 1: Submission received by Submission Service.
  → Validate: code size < 64KB, language supported, problem exists, user has access.
  → Write submission record (status=queued) to DB.
  → Push job to Job Queue: { submission_id, problem_id, language, code_ref, user_id }.
  → Return submission_id to client immediately (async).

Step 2: Judge Worker picks job from queue.
  → Pull code from object store (or inline if small).
  → Pull test cases (input files) from private object store bucket.
  → Spin up ephemeral container:
       docker run --network=none --memory=256m --cpus=1
                  --pids-limit=50 --read-only
                  --security-opt seccomp=judge-profile.json
                  judge-image:{language}:{version}

Step 3: Compilation (if needed: Java, C++, Go).
  → Compile inside container.
  → If compile error: immediately return COMPILE_ERROR + stderr.

Step 4: Test case execution loop.
  For each test case (ordered by difficulty — cheapest first):
    a. Feed input file via stdin (or file argument per problem type).
    b. Start wall-clock timer.
    c. Execute solution binary/script.
    d. Capture stdout, check against expected output.
    e. Measure peak memory usage (read from cgroup memory.peak).
    f. If output mismatch → WRONG_ANSWER (stop, no need for more tests).
    g. If time_limit exceeded → TLE (stop).
    h. If memory_limit exceeded → MLE (stop).
    i. If runtime crash → RUNTIME_ERROR + truncated stderr.
    j. All tests pass → ACCEPTED.

Step 5: Worker reports verdict.
  → Update submission record in DB.
  → Publish result to Redis pub/sub channel: sub:{submission_id} → result_json.
  → Update user_problem_status, contest_scores if applicable.
  → Destroy container (ephemeral; never reused between users).

Step 6: Client receives result.
  → Client polls GET /submissions/{id} OR subscribes via WebSocket/SSE.
  → Result fetched from Redis cache (fast read) → returned to client.
```

#### 7.1.3 Output Comparison (Judge Strictness)

```
Standard: exact match after stripping trailing whitespace and newlines.
Float: epsilon comparison (|actual - expected| < 1e-5).
Special judge (SPJ): custom checker binary per problem for multiple valid outputs
  (e.g., "any valid topological sort accepted").
  SPJ is a compiled C++ binary run inside the container after user solution completes.
```

#### 7.1.4 Time Measurement Fairness

**Problem**: Container startup time varies. Python interpreter startup ≠ C++ binary startup.

**Solution: Measure only solution execution time, not container startup.**
```
Judge runner script (inside container):
  1. Start container, load interpreter/runtime (cold start paid once).
  2. For each test case:
     a. Record time_start = high_resolution_clock.now()
     b. Execute solution function (already compiled/loaded).
     c. time_end = high_resolution_clock.now()
     d. Elapsed = time_end - time_start (pure execution time)
  3. Check against time_limit_ms.
```

Language-specific multipliers (configurable per problem):
- C++: 1× base time limit.
- Java: 2× (JVM overhead).
- Python: 5× (interpreted overhead).
- JavaScript: 3×.

### 7.2 Job Queue Design

**Technology: Redis Queue (for normal load) + Kafka (for durable peak load).**

**Normal submissions (< 100/sec):**
- Redis `LPUSH` / `BRPOP` queue per language.
- Separate queues: `judge:python`, `judge:java`, `judge:cpp`, etc.
- Workers specialized per language → language runtime pre-loaded in memory.
- Avg queue depth: < 50 items. Latency: < 500ms queue wait.

**Contest peak (up to 2,000/sec):**
- Overflow to Kafka topic `judge_jobs` partitioned by `language`.
- Kafka partition per language → predictable ordering + consumer group scaling.
- Auto-scale judge workers using Kubernetes HPA (Horizontal Pod Autoscaler) triggered by queue depth metric.

**Priority queue design:**
```
Priority 1 (highest): Contest submissions during active contest.
Priority 2: Premium user submissions.
Priority 3: Free user submissions (run, not submit).
Priority 4: Background re-judging (when test cases updated).

Implemented via: separate Redis queues per priority; workers drain P1 first.
```

### 7.3 Judge Worker Pool

**Infrastructure:**
- Kubernetes cluster with auto-scaling node pools.
- Each worker Pod: 2 vCPU, 512 MB RAM (for worker itself).
- Each submission container: 1 vCPU, memory_limit (256 MB default) → strict cgroup limit.
- Workers run sequentially per submission (one submission at a time per worker) for predictable timing.

**Worker lifecycle:**
```
Worker starts → connects to Redis/Kafka queue.
Loop:
  1. BRPOP job from queue (blocking, waits up to 30s).
  2. Process submission (Steps 2–5 above).
  3. Ack job from queue.
  4. Health report to worker registry (Redis SET worker:{id} = last_heartbeat, TTL 60s).
5. If heartbeat stops → Kubernetes restarts pod.
```

**Auto-scaling triggers:**
- Contest starts → pre-scale to 80% of peak estimate (warm-up 3 min in advance).
- Queue depth > 500 → scale out 20%.
- CPU utilization > 70% → scale out.
- Queue depth < 20 for 10 min → scale in (with min floor of 50 workers).

### 7.4 Run vs Submit — Separation Of Concerns

| Attribute | Run (Sample) | Submit (Full Judge) |
|---|---|---|
| Test cases | 2–3 public samples | All 100–500 hidden cases |
| Custom input | Yes (user-provided) | No |
| Queue priority | Highest (feels instant) | Normal-to-high |
| Result visibility | Full stdout/stderr | Limited on failure |
| Rate limit | 5 runs/min/user | 20 submits/30 min/user |
| Execution time limit | Relaxed (5s per run) | Strict per-language limits |

**Why separate?** Run is a debugging tool — users expect immediate feedback (< 2s). Submit is a formal judgment. Mixing them wastes resources on expensive full-test-case execution for debugging iterations.

### 7.5 Submission Service

- **Validates** request (auth, rate limit, problem access for premium).
- **Stores code** in object store if > 8 KB; inline in DB otherwise.
- **Deduplication**: reject identical code submitted twice within 10 seconds (prevents accidental double-click duplicate submissions).
- **Enqueues** job and returns `submission_id` immediately.
- **Result polling**: client polls every 1–2 seconds for up to 30s; after 30s client gets "Judge timeout" and can retry polling.

**WebSocket alternative to polling:**
```
Client connects: WS /ws/submissions/{submissionId}
Server: holds connection open.
When worker publishes to Redis pub/sub channel sub:{submissionId}:
  Server pushes result over WebSocket → client receives instantly.
Connection closed after first result push.
```
WebSocket reduces polling load significantly during contests.

### 7.6 Contest Service And Real-Time Leaderboard

#### 7.6.1 Scoring Model (LeetCode Weekly Contest)
```
Score = sum of points for each accepted problem (3+4+5+6 = 18 max)
Penalty = sum of minutes from contest start to first accepted submission per problem
           + 5 minutes per wrong attempt before acceptance

Rank = sorted by (Score DESC, Penalty ASC, Finish_Time ASC)
```

#### 7.6.2 Real-Time Leaderboard Design

**Challenge**: 100K users, each with a score that updates on every accepted submission → leaderboard must show accurate rank in real-time.

**Solution: Redis Sorted Set**
```
Key: contest:{contestId}:leaderboard
Score: -(score * 1e12) + penalty_minutes   // negative for DESC sort by score
Member: {userId}

On accepted submission:
  1. Update contest_scores in DB (strong consistency, within transaction).
  2. ZADD contest:{contestId}:leaderboard {new_composite_score} {userId}
  3. Leaderboard rank: ZRANK contest:{contestId}:leaderboard {userId}

Get top-K: ZRANGE contest:{contestId}:leaderboard 0 49 WITHSCORES
Get user rank: ZRANK contest:{contestId}:leaderboard {userId}
```

**Composite score formulation for Redis ZADD:**
```
Since Redis sorted set sorts ASC, and we want:
  - Higher problem score → lower rank number (better)
  - Lower penalty → lower rank number (better)

Composite = -(score * 10^8) + penalty_minutes
ZADD with this composite → natural ascending ZRANGE gives correct ordering.
```

**Leaderboard read path:**
- Top 50: `ZRANGE` from Redis → served instantly (< 1ms).
- User rank: `ZRANK` → O(log N).
- Full leaderboard pagination: `ZRANGE` with LIMIT/OFFSET.
- DB is source of truth; Redis sorted set is the live view.
- After contest ends, final ranking computed from DB → stored permanently.

#### 7.6.3 Contest State Machine

```
UPCOMING → ACTIVE (at start_time; automated scheduler)
ACTIVE → FINISHED (at end_time; all in-flight submissions still judged)
FINISHED → RANKED (after final rating/ranking computation; async job)

Rating update (post-contest):
  - Fetch all final scores.
  - Run Elo/Glicko-2 rating update algorithm.
  - Update user global rating and contest rating history.
  - This is a batch job run 1–2 hours after contest end.
```

### 7.7 Problem Catalog Service

**Read-heavy, rarely written:** 3,000 problems, updated maybe once per week.

**Caching strategy:**
```
L1: CDN (problem description, constraints, examples) — TTL: 1 hour
    Cache key: /problems/{slug}  (static HTML rendering)
L2: Redis — problem metadata JSON — TTL: 30 min
    Key: problem:{problemId}:meta
L3: PostgreSQL read replica (source of truth for writes)
```

**Search and filter:**
- Elasticsearch index with fields: `title`, `tags[]`, `difficulty`, `companies[]`, `acceptance_rate`, `is_premium`.
- Filter queries: `tags:dp AND difficulty:hard AND company:google`.
- Full-text on title and problem description (partial for premium feature).

**Premium access control:**
- API Gateway checks `is_premium` flag on problem.
- If `is_premium=true` AND `user.subscription=free` → return problem stub without content.
- Checked on every request (JWT contains subscription tier).

### 7.8 User Progress And Streak Service

**Streak computation:**
```
On any submission with status=accepted:
  1. Check user_streaks.last_active_date.
  2. If today = last_active_date: no-op (already counted today).
  3. If today = last_active_date + 1 day: increment current_streak, update last_active_date.
  4. If today > last_active_date + 1 day: reset current_streak = 1, update last_active_date.
  5. If today < last_active_date: impossible (clock skew handled by timezone normalization to user's local date).
```

**Timezone handling**: Streak logic uses the user's profile timezone, not server UTC. A user in IST solving at 11:59 PM IST should count for that day, not the next UTC day.

**Acceptance rate per problem (denormalized):**
```
acceptance_rate = (total_accepted / total_submissions) * 100
Updated: async consumer on every submission result event.
Batch recalculation runs nightly to correct drift.
```

---

## 8. End-To-End Critical Flows

### 8.1 Code Submission Flow (Full Detail)

```
1. User clicks Submit in browser (Monaco Editor content).
2. Browser: POST /v1/problems/{problemId}/submit { language, code }
3. API Gateway: Auth check (JWT) + rate limit check.
4. Submission Service:
   a. Validate code size, language, problem exists, premium access.
   b. Dedup check (Redis: seen this exact code hash in last 10s? reject).
   c. INSERT submission (status=queued) to DB.
   d. Store code in object store (if > 8KB).
   e. LPUSH job to Redis queue (or Kafka if contest peak).
   f. Return: { submission_id: "sub_xyz" } in < 100ms.
5. Client: Polls GET /submissions/sub_xyz every 1s (or WebSocket subscribed).
6. Judge Worker (async):
   a. BRPOP job from queue.
   b. Pull test cases from private S3 bucket.
   c. docker run ... (sandboxed container).
   d. Compile (if needed).
   e. Execute each test case, compare output.
   f. Record verdict, runtime, memory.
   g. UPDATE submission SET status=accepted, runtime_ms=120 WHERE submission_id=...
   h. Compute percentiles: SELECT percentile_rank of 120ms among all accepted submissions.
   i. PUBLISH sub:sub_xyz → result_json via Redis pub/sub.
   j. UPDATE user_problem_status (solved=true if first AC).
   k. If contest: UPDATE contest_scores, ZADD leaderboard in Redis.
7. Server (WebSocket handler): receives pub/sub notify → pushes to client over WS.
8. Client: Displays verdict with runtime/memory percentile.
```

### 8.2 Contest Submission With Leaderboard Update

```
1. Normal submission flow (Steps 1–8 above) with contest_id in job.
2. After verdict computed:
   a. BEGIN TRANSACTION (contest_scores update)
      - If ACCEPTED and first AC for this problem:
          UPDATE contest_scores SET
            score = score + problem_points,
            penalty_minutes = penalty_minutes + (now - contest.start_time)
                              + (5 * prior_wrong_attempts),
            problems_solved = problems_solved || new_problem_entry
          WHERE contest_id=... AND user_id=...
      - INSERT contest_submission event.
      COMMIT
   b. ZADD contest:{contestId}:leaderboard {composite_score} {userId}
   c. Broadcast leaderboard update via WebSocket to all connected contest watchers.
3. Client UI: leaderboard auto-refreshes, user sees their new rank.
```

### 8.3 Flash Contest (100K Concurrent Users)

```
Pre-contest (5 min before start):
  → Pre-scale judge workers to 8,000 pods.
  → Pre-warm Redis with contest metadata and problem data.
  → CDN pre-caches problem HTML pages.

At T+0 (contest start):
  → Rate limiter: max 3 submits/min/user (prevents flooding).
  → Queue: Redis P1 queues for contest submissions.
  → Expected: 100K users * 1 attempt in first 2 min = ~800 submissions/sec.
  → 8,000 workers × 1 submission per 10s avg = 800 completions/sec. ✓

Leaderboard reads:
  → 100K users refreshing leaderboard → Redis ZRANGE: 1M reads/min.
  → Redis handles 100K+ reads/sec. Cached leaderboard snapshot every 5s for non-personal views.
  → Personal rank (ZRANK) per user fetch on each their own submission result.
```

---

## 9. Consistency And Idempotency

| Operation | Consistency | Why |
|---|---|---|
| Submission creation | Strong (DB write) | Submission ID must be authoritative |
| Verdict update | Strong | Must not lose final result |
| Contest score update | Strong (ACID transaction) | Rank correctness is critical |
| Leaderboard Redis update | Eventual (post-DB commit) | Brief lag acceptable; self-healing |
| Problem acceptance rate | Eventual (async update) | Approximate stats are fine |
| User streak | Strong (DB) | Streak is user-facing and trusted metric |
| Notification delivery | At-least-once | Duplicate notifications tolerable |

**Idempotency for submissions:**
- `submission_id` is server-generated UUID. Clients use it as dedup key.
- Worker processes each job once; Kafka consumer group ensures exactly-once delivery with offset commit after DB update.
- Re-judging (test case update): creates new internal submission record; visible as "re-judged" in history.

---

## 10. Caching Strategy

### 10.1 Cache Tiers

```
CDN:
  → Problem HTML pages, editorial content, blog posts.
  → Static assets (Monaco editor JS, CSS).
  → TTL: 1 hour for problem content; 24h for static assets.

Redis:
  → problem:{id}:meta → { title, difficulty, limits, tags }  TTL: 30 min
  → submission:{id}:result → verdict JSON  TTL: 24h (after judging)
  → contest:{id}:leaderboard → Redis Sorted Set (no TTL; managed explicitly)
  → rate_limit:{userId}:submit → sliding window counter  TTL: 30 min
  → session:{token} → userId, subscription_tier  TTL: 1h
  → problem_list:{filter_hash} → paginated result list  TTL: 5 min

DB Read Replica:
  → Heavy analytics queries (acceptance rate histograms, submission analytics).
```

### 10.2 Percentile Computation For Submission Result

After each accepted submission, show "Faster than X% of users":
```
Option A (Real-time): SELECT percentile_rank(runtime_ms)
                      WITHIN GROUP (ORDER BY runtime_ms)
                      among all accepted submissions for this problem+language.
  → Expensive for popular problems (millions of submissions).

Option B (Pre-computed): Maintain a histogram struct in Redis.
  → Redis HyperLogLog or a T-Digest sketch for approximate percentile.
  → Update on each AC verdict (INCR into runtime bucket).
  → Percentile lookup: O(1) from sketch.
  → Accuracy: within 1% — acceptable for this use case.
```

---

## 11. Data Partitioning And Scaling

### 11.1 Submissions Table (Biggest Table)

```
Partition by user_id:
  → All user's submissions in one shard → fast "My Submissions" queries.
  → Hash partition (prevent hotspot on sequential user IDs).
  → Shards: 16 initial; each handling ~3M submissions (rebalance as it grows).

Time-based archival:
  → Submissions older than 2 years moved to cold storage (S3 + compressed Parquet).
  → Recent 2 years in hot PostgreSQL.
  → Query routes based on date range.
```

### 11.2 Judge Worker Scaling

```
Normal days: 200 workers (auto-scaled down at night to 50).
Contest peak: 10,000 workers (pre-scaled 5 min before contest).
Cost optimization: Use spot/preemptible instances for workers (saving 70% cost).
  → Worker crashes on spot preemption → job requeued (Kafka visibility timeout).
  → Submission marked as "system_error" after 3 failed attempts → user notified to resubmit.
```

### 11.3 Kafka Partitioning For Judge Jobs

```
Topic: judge_jobs
Partitions: 50 per language (50 × 5 languages = 250 total partitions)
Key: user_id (hash-distributed)
Consumer group: judge-workers

This ensures:
  - Same user's jobs may go to same partition (ordering if needed).
  - Up to 50 parallel consumers per language.
  - Rebalancing if worker crashes.
```

---

## 12. Security

### 12.1 Code Execution Security (Most Critical)

**Defense in depth** (already covered in Section 7.1.1):
- Container isolation.
- Seccomp syscall filter.
- cgroups resource limits.
- User namespace remapping.
- No network access.

**Additional measures:**
- **Static analysis**: Before execution, scan code for known dangerous patterns (e.g., Python `import os; os.system(...)`). Block obvious exploits before even running.
- **Canary file detection**: Place a unique file in the container at a secret path. After execution, check if the canary was read → indicates sandbox escape attempt.
- **Container image immutability**: Judge images are read-only signed images. Any modification → image hash mismatch → container rejected.
- **Worker isolation**: Each worker VM serves only one submission at a time. No shared memory between submissions on the same worker.

### 12.2 Test Case Protection

```
Test cases must NEVER be exposed to users (they would trivially hardcode outputs).

Protections:
  - Stored in private object store bucket (no public URL).
  - IAM roles: only judge worker service accounts can read test case bucket.
  - User code runs in a container with NO credentials → cannot call cloud APIs.
  - Test case content never written to submission output or logs.
  - On Wrong Answer: show only first failing test case input (not expected output).
    For last few test cases (anti-cheating): show "Hidden test case failed" without revealing input.
```

### 12.3 Anti-Cheating During Contests

```
Detection signals:
  - Submission within seconds of problem being released (superhuman speed).
  - Identical code across multiple unrelated accounts.
  - Code matching known internet solutions (substring fingerprint matching).
  - Unusual spikes in submission pattern (bot-like uniform timing).

Enforcement pipeline:
  - Flag suspicious submissions for post-contest review.
  - Similarity detection: MinHash / SimHash on code → find near-duplicate submissions.
  - Consequence: contest score nullified; account warning or ban.
```

### 12.4 Rate Limiting

```
API Gateway rate limits (per user, per endpoint):
  - Submit: 20 per 30 minutes (prevents brute-force and resource abuse).
  - Run: 5 per minute (debugging tool, not a firewall).
  - Problem list: 100 per minute.
  - Auth endpoints: 10 per minute per IP (brute-force login protection).

Implementation: Redis sliding window (ZRANGEBYSCORE + ZADD).
Burst allowance: token bucket allows short bursts then enforces average.
```

---

## 13. Reliability And Disaster Recovery

### 13.1 Judge Worker Failure Handling

```
Scenario: Worker crashes mid-execution.
  - Kafka job's visibility timeout expires (e.g., 60s).
  - Job re-appears in queue for another worker to pick up.
  - Idempotent processing: worker checks if submission already has a verdict before processing.
  - After 3 failures: status=system_error; user shown retry option.

Scenario: Container OOM killed.
  - Container exit code non-zero → worker reports MLE if memory exceeded, RE otherwise.
  - Container cleaned up; next submission unaffected.

Scenario: Worker hangs (infinite loop in judge runner itself).
  - watchdog timer in worker: if execution > time_limit * 3 → SIGKILL container.
  - Worker records TLE and proceeds.
```

### 13.2 Service Availability

```
Problem Catalog Service failure:
  - Serve cached problem HTML from CDN.
  - Users can still read problems; submit disabled until service recovers.

Submission Service failure:
  - Queue drains; workers continue processing already-enqueued jobs.
  - New submissions fail fast → user told to retry.

Redis failure (leaderboard):
  - Contest leaderboard temporarily shows "updating..." .
  - DB-backed leaderboard served (slower but correct).
  - Redis Redis Cluster with 3 replicas → single node failure transparent.

DB primary failure:
  - Automatic failover to hot standby (PostgreSQL streaming replication + Patroni).
  - RTO: < 30s (Patroni auto-promotion).
  - RPO: < 1s (synchronous streaming replication for submissions).
```

### 13.3 Multi-Region Strategy

```
Primary region: US East (main compute, DB primary).
Secondary region: US West (DB replica, judge workers, read-only traffic).
Asia region: Singapore (CDN edge + judge workers for reduced latency).

Globally distributed judge workers reduce submission latency for non-US users.
DB writes always go to primary region (single master for consistency).
Contest leaderboard: served from each region's Redis replica (sync lag < 500ms acceptable).
```

---

## 14. Observability And SLOs

### 14.1 SLO Targets

| Metric | SLO |
|---|---|
| Problem page load (CDN hit) | P99 < 100ms |
| Submission verdict (P50) | < 3s |
| Submission verdict (P95) | < 8s |
| Contest leaderboard refresh | P99 < 200ms |
| Judge worker availability | 99.9% |
| Submission result durability | 99.999% |

### 14.2 Key Metrics To Monitor

**Judge health:**
- Queue depth per language (alert if > 1,000 for > 1 min).
- Worker throughput (submissions/sec/worker).
- Judge timeout rate (system_error %).
- Sandbox escape detection events (should be 0; any non-zero → immediate alert).

**User experience:**
- Verdict latency distribution (P50, P95, P99).
- Wrong verdict rate (judge returning incorrect verdicts → test case data corruption).
- Contest leaderboard update lag.

**Business:**
- Premium conversion rate.
- Daily submissions volume (leading indicator of engagement).
- Contest participation rate.

---

## 15. Major Trade-Offs And Why

### 15.1 Docker vs VM Isolation For Judge

| Approach | Security | Latency | Cost |
|---|---|---|---|
| Full VM per submission | Highest | High (VM boot: 10–30s) | Very expensive |
| Container (Docker + seccomp) | Strong | Low (container start: 100–500ms) | Moderate |
| Process-level sandbox (ptrace) | Moderate | Lowest (< 10ms) | Cheapest |
| gVisor containers | Very strong | Moderate (~20% overhead) | Moderate |

**Decision**: Container (Docker + seccomp + cgroups) for best latency-security tradeoff. gVisor for highest-risk scenarios. VM isolation only for enterprise/private judge deployments.

### 15.2 Polling vs WebSocket For Submission Results

- **Polling**: Simpler, works behind any proxy/firewall, no persistent connection management.
  Downside: 1–2s lag; amplifies load (N users × poll frequency = high QPS on polling endpoint).
- **WebSocket**: Real-time, single connection per tab, result delivered instantly.
  Downside: connection state management, load balancer sticky sessions or Redis pub/sub for cross-process delivery.
- **Decision**: WebSocket with Redis pub/sub as the bridge. Polling as fallback for clients where WS fails.

### 15.3 Redis Sorted Set vs SQL For Leaderboard

- SQL `ORDER BY score DESC` on 100K rows during active contest with frequent updates → expensive, row lock contention.
- Redis sorted set: O(log N) insert, O(log N) rank query, O(K) top-K read → handles 100K users trivially.
- **Decision**: Redis sorted set as live leaderboard. SQL as durable source of truth. Final ranking snapshot from SQL post-contest.

### 15.4 One Test Case At A Time vs Batch Execution

- **Sequential**: Fail-fast on first wrong answer → saves CPU on clearly incorrect solutions.
  Allows accurate TLE/WLE isolation per test case.
- **Parallel**: Faster total execution for many test cases. Harder to isolate individual results.
- **Decision**: Sequential (fail-fast). Saves ~60% of compute for wrong-answer submissions (which are the majority).

### 15.5 Test Case Storage: Object Store vs DB Blob

- DB blob: simpler to access, ACID, but bloats DB. 500K test cases × 10KB = 5GB in DB BLOB column → performance issue.
- Object store (S3/GCS): designed for large binary data, cheap, fast streaming access.
- **Decision**: Object store for test case files; DB stores only metadata (size, checksum, object key). Judge workers stream test case directly from S3.

---

## 16. Interview-Ready Deep Dive Talking Points

**"How do you prevent user code from stealing test cases?"**
> Container has no network access. Test case bucket accessible only by judge service account (IAM). User code runs as unmapped UID with no credentials. seccomp profile blocks socket() syscall. Static code analysis blocks obvious import attempts before execution even starts.

**"How do you scale to 100K contest submissions?"**
> Pre-scale Kubernetes worker pool before contest (predictive scaling via schedule). Redis queues with language-specific partitions. Priority queues for contest vs practice. Kafka as overflow buffer. Auto-scaling by queue depth metric. 10K workers × 1 submission/10s = 1K submissions/sec throughput.

**"How does the leaderboard stay real-time?"**
> Redis sorted set with composite score (score×penalty). ZADD on every accepted verdict after DB commit. ZRANK for user rank in O(log N). Top-50 via ZRANGE in < 1ms. WebSocket broadcast to contest watchers. Redis cluster ensures availability.

**"How do you ensure verdict correctness?"**
> Multiple judge runs for ambiguous verdicts (flaky test cases re-run ×3). Special judge (SPJ) binary for problems with multiple valid answers. Epsilon comparison for floating point. Checksum verification of test case files before use. Canary-based sandbox validation.

**"What breaks first at contest peak?"**
> 1. Judge queue depth → mitigate with pre-scaling via scheduled HPA. 2. Redis leaderboard hotspot → shard by contest / pipeline ZADDs. 3. DB write contention on contest_scores → row-level locking per user is sufficient (each user updates their own row).

---

## 17. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | Scope: judge, contests, catalog, scale numbers |
| 5–10 min | Capacity: submissions/day, peak contest QPS, worker estimate |
| 10–18 min | API design: submit, run, contest leaderboard |
| 18–30 min | Architecture + Online Judge deep dive (sandbox, execution flow, isolation) |
| 30–38 min | Contest leaderboard (Redis sorted set, real-time update, WebSocket) |
| 38–43 min | Scaling (worker pool, Kafka, auto-scale), security, consistency model |
| 43–45 min | Trade-offs, extensions |

---

## 18. Extensions To Mention If Time Permits

- **AI Code Hints**: GPT-powered hints that nudge without revealing full solution (premium feature).
- **Pair Programming Mode**: Two users co-edit code in real-time (WebRTC / CRDT-based collaborative editor).
- **Custom Test Case IDE**: Full environment with debugger and breakpoints (requires more powerful sandbox).
- **Company Interview Simulation**: Timed mock interview with a curated problem set, interviewer view.
- **Problem Creation Portal**: Verified setters create and unit-test problems, manage test generators.
- **Anti-plagiarism Engine**: ML model comparing code structure, variable names, and algorithm shape across all submissions.
- **LeetCode SQL Judge**: Runs SQL submissions against a sample database (PostgreSQL/MySQL inside container).
- **Multi-language output validation**: Automatically verifies that C++, Java, Python implementations of model solution agree on all test cases before problem is published.
