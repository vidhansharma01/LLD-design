# 📊 Probabilistic Data Structures — 7-Day Beginner Learning Plan

> **Goal:** Understand probabilistic data structures from scratch, learn their use-cases in large-scale systems, and implement each one in Java.

---

## 🤔 What Are Probabilistic Data Structures?

Probabilistic data structures are a class of data structures that **trade perfect accuracy for massive gains in speed and memory efficiency**. Instead of giving exact answers, they give **approximate answers with a controllable error rate**.

### Why Use Them?

| Scenario | Exact DS | Probabilistic DS |
|---|---|---|
| "Has this URL been crawled?" (1B URLs) | HashSet = ~32 GB RAM | Bloom Filter = ~1.2 GB |
| "How many unique visitors today?" (500M events) | HyperLogLog vs HashSet | 12 KB vs ~2 GB |
| "What are the top 10 trending items?" | Full sort = O(N log N) | Count-Min Sketch = O(1) |

### Core Trade-off

```
Exact Answer  ←————————————————→  Approximate Answer
   High Memory                        Low Memory
   Slow at Scale                      Fast at Any Scale
```

### Where You'll See Them in Real Systems
- **Google** — Bloom Filters in BigTable to avoid disk lookups
- **Apache Cassandra** — Bloom Filters to skip SSTables
- **Redis** — HyperLogLog for counting unique users
- **LinkedIn** — Count-Min Sketch for trending feed items
- **Akamai CDN** — Bloom Filters for cache membership checks

---

## 📅 7-Day Learning Plan

| Day | Topic | Difficulty |
|---|---|---|
| Day 1 | Bloom Filter | ⭐ Beginner |
| Day 2 | Counting Bloom Filter | ⭐⭐ Easy |
| Day 3 | HyperLogLog | ⭐⭐⭐ Medium |
| Day 4 | Count-Min Sketch | ⭐⭐ Easy |
| Day 5 | Skip List | ⭐⭐⭐ Medium |
| Day 6 | MinHash / LSH | ⭐⭐⭐ Medium |
| Day 7 | Cuckoo Filter + Review | ⭐⭐⭐ Medium |

---

---

# 📅 Day 1 — Bloom Filter

## What Is It?

A Bloom Filter answers the question: **"Is this element in the set?"**

- **NO** → definitely not in the set (100% accurate)
- **YES** → *probably* in the set (may be a false positive)

> There are **no false negatives**. Only false positives are possible.

## How It Works

1. Start with a **bit array** of `m` bits, all set to `0`.
2. Choose `k` independent **hash functions**.
3. **Insert element:** Hash it with all `k` functions → set those `k` bits to `1`.
4. **Query element:** Hash it → check all `k` bits. If all are `1` → "probably present". If any is `0` → "definitely absent".

```
Bit Array (m=10):  [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]

Insert "apple":
  h1("apple") = 2 → bit[2] = 1
  h2("apple") = 5 → bit[5] = 1
  h3("apple") = 9 → bit[9] = 1

Bit Array:         [0, 0, 1, 0, 0, 1, 0, 0, 0, 1]

Query "apple":    bits 2,5,9 all 1 → PROBABLY PRESENT ✓
Query "banana":   h1=3 (bit=0)   → DEFINITELY ABSENT ✓
Query "grape":    h1=2,h2=5,h3=9 → FALSE POSITIVE ✗ (all bits happen to be set)
```

## Key Formulas

```
False Positive Rate (p) ≈ (1 - e^(-kn/m))^k

Optimal k (hash functions) = (m/n) * ln(2)
Optimal m (bits needed)    = -n * ln(p) / (ln(2))^2

Where:
  n = expected number of elements
  m = bit array size
  k = number of hash functions
  p = desired false positive rate
```

## Real-World Use Cases

| System | Use |
|---|---|
| Apache Cassandra | Skip reading SSTables for missing keys |
| Google Chrome | Safe Browsing malicious URL check |
| Medium | "Already read" article detection |
| Akamai CDN | Cache membership lookup |
| PostgreSQL | Index join optimization |

## Limitations

- Cannot delete elements (bits are shared)
- Cannot count how many times an element was inserted
- False positive rate increases as more elements are added

---

## Java Implementation

```java
// BloomFilter.java
import java.util.BitSet;
import java.util.function.Function;

public class BloomFilter {

    private final BitSet bitSet;
    private final int size;           // m = total bits
    private final int numHashFunctions; // k
    private int elementCount;         // n = elements inserted so far

    /**
     * Creates a Bloom Filter optimized for expected number of elements
     * and a desired false positive probability.
     *
     * @param expectedElements    n — how many items you plan to insert
     * @param falsePositiveRate   p — e.g., 0.01 for 1% false positive rate
     */
    public BloomFilter(int expectedElements, double falsePositiveRate) {
        // m = -n * ln(p) / (ln(2))^2
        this.size = optimalBitSize(expectedElements, falsePositiveRate);
        // k = (m/n) * ln(2)
        this.numHashFunctions = optimalHashCount(size, expectedElements);
        this.bitSet = new BitSet(size);
        this.elementCount = 0;

        System.out.println("Bloom Filter created:");
        System.out.println("  Bit array size (m) = " + size);
        System.out.println("  Hash functions (k) = " + numHashFunctions);
    }

    /** Optimal bit size formula */
    private int optimalBitSize(int n, double p) {
        return (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    /** Optimal hash function count formula */
    private int optimalHashCount(int m, int n) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    /**
     * Generates k hash values for the given key using double hashing.
     * Uses two base hash functions and combines them: h_i(x) = h1(x) + i * h2(x)
     */
    private int[] getHashPositions(String key) {
        int[] positions = new int[numHashFunctions];
        int hash1 = murmurhash(key, 0);
        int hash2 = murmurhash(key, hash1);

        for (int i = 0; i < numHashFunctions; i++) {
            positions[i] = Math.abs((hash1 + i * hash2) % size);
        }
        return positions;
    }

    /**
     * Simple MurMur-inspired hash function.
     * In production, use Guava's Hashing or a proper MurmurHash3 library.
     */
    private int murmurhash(String key, int seed) {
        int h = seed;
        for (char c : key.toCharArray()) {
            h = 31 * h + c;
            h ^= (h >>> 16);
            h *= 0x85ebca6b;
            h ^= (h >>> 13);
            h *= 0xc2b2ae35;
            h ^= (h >>> 16);
        }
        return h;
    }

    /** Add element to the Bloom Filter */
    public void add(String element) {
        int[] positions = getHashPositions(element);
        for (int pos : positions) {
            bitSet.set(pos);
        }
        elementCount++;
    }

    /** Test membership — may return false positives, never false negatives */
    public boolean mightContain(String element) {
        int[] positions = getHashPositions(element);
        for (int pos : positions) {
            if (!bitSet.get(pos)) {
                return false; // Definitely NOT present
            }
        }
        return true; // Probably present
    }

    /** Estimate current false positive rate based on elements inserted */
    public double estimatedFalsePositiveRate() {
        double exponent = -(double) numHashFunctions * elementCount / size;
        return Math.pow(1 - Math.exp(exponent), numHashFunctions);
    }

    public int getElementCount() { return elementCount; }
    public int getBitSetSize() { return size; }

    // ===== DEMO =====
    public static void main(String[] args) {
        BloomFilter bf = new BloomFilter(1000, 0.01); // 1000 elements, 1% FP rate
        System.out.println();

        // Insert some URLs
        String[] insertedUrls = {
            "https://google.com", "https://amazon.com",
            "https://netflix.com", "https://youtube.com"
        };
        for (String url : insertedUrls) {
            bf.add(url);
            System.out.println("Inserted: " + url);
        }

        System.out.println("\n--- Membership Queries ---");

        // Check known present
        for (String url : insertedUrls) {
            System.out.println("mightContain('" + url + "'): " + bf.mightContain(url));
        }

        // Check known absent
        String[] absentUrls = {
            "https://notinserted.com", "https://example.org", "https://test.io"
        };
        System.out.println();
        for (String url : absentUrls) {
            System.out.println("mightContain('" + url + "'): " + bf.mightContain(url)
                + (bf.mightContain(url) ? " ← false positive!" : " ← correct"));
        }

        System.out.printf("%nEstimated false positive rate: %.4f%%%n",
            bf.estimatedFalsePositiveRate() * 100);
    }
}
```

### Expected Output
```
Bloom Filter created:
  Bit array size (m) = 9586
  Hash functions (k) = 7

Inserted: https://google.com
Inserted: https://amazon.com
Inserted: https://netflix.com
Inserted: https://youtube.com

--- Membership Queries ---
mightContain('https://google.com'): true
mightContain('https://amazon.com'): true
mightContain('https://netflix.com'): true
mightContain('https://youtube.com'): true

mightContain('https://notinserted.com'): false ← correct
mightContain('https://example.org'): false ← correct
mightContain('https://test.io'): false ← correct

Estimated false positive rate: 0.0002%
```

---

---

# 📅 Day 2 — Counting Bloom Filter

## What Is It?

A **Counting Bloom Filter** extends the standard Bloom Filter to support **element deletion** — something the standard version cannot do.

Instead of storing individual bits (0/1), each "slot" stores a **small integer counter**.

## How It Works

```
Standard BF:  bit[i] ∈ {0, 1}
Counting BF:  counter[i] ∈ {0, 1, 2, 3, ...}

Insert: increment all k counters
Delete: decrement all k counters
Query:  check if all k counters > 0
```

## Trade-off vs Standard Bloom Filter

| Feature | Bloom Filter | Counting Bloom Filter |
|---|---|---|
| Deletion | ❌ Not supported | ✅ Supported |
| Memory | 1 bit per slot | 4 bits per slot (typical) |
| False positives | Yes | Yes |
| False negatives | No | Possible if over-deleted |

## Java Implementation

```java
// CountingBloomFilter.java
public class CountingBloomFilter {

    private final int[] counters; // Each slot is a 4-bit counter (use int for simplicity)
    private final int size;
    private final int numHashFunctions;
    private int elementCount;

    public CountingBloomFilter(int expectedElements, double falsePositiveRate) {
        this.size = optimalBitSize(expectedElements, falsePositiveRate);
        this.numHashFunctions = optimalHashCount(size, expectedElements);
        this.counters = new int[size];
        this.elementCount = 0;

        System.out.println("Counting Bloom Filter: m=" + size + ", k=" + numHashFunctions);
    }

    private int optimalBitSize(int n, double p) {
        return (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    private int optimalHashCount(int m, int n) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    private int[] getHashPositions(String key) {
        int[] positions = new int[numHashFunctions];
        int h1 = hash(key, 0);
        int h2 = hash(key, h1);
        for (int i = 0; i < numHashFunctions; i++) {
            positions[i] = Math.abs((h1 + i * h2) % size);
        }
        return positions;
    }

    private int hash(String key, int seed) {
        int h = seed;
        for (char c : key.toCharArray()) h = 31 * h + c;
        return h;
    }

    /** Insert element — increment k counters */
    public void add(String element) {
        for (int pos : getHashPositions(element)) {
            counters[pos]++;
            // Cap at 15 to simulate 4-bit counter (max = 15)
            if (counters[pos] > 15) counters[pos] = 15;
        }
        elementCount++;
    }

    /**
     * Delete element — decrement k counters.
     * WARNING: Only delete elements that were actually inserted, otherwise
     * you risk creating false negatives.
     */
    public boolean remove(String element) {
        if (!mightContain(element)) {
            System.out.println("Element not in filter, cannot remove: " + element);
            return false;
        }
        for (int pos : getHashPositions(element)) {
            if (counters[pos] > 0) counters[pos]--;
        }
        elementCount--;
        return true;
    }

    /** Check membership — all k counters must be > 0 */
    public boolean mightContain(String element) {
        for (int pos : getHashPositions(element)) {
            if (counters[pos] == 0) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        CountingBloomFilter cbf = new CountingBloomFilter(500, 0.01);

        System.out.println("\n--- Insert Elements ---");
        cbf.add("user:alice");
        cbf.add("user:bob");
        cbf.add("user:charlie");

        System.out.println("Contains 'user:alice': " + cbf.mightContain("user:alice"));
        System.out.println("Contains 'user:dave': " + cbf.mightContain("user:dave"));

        System.out.println("\n--- Remove 'user:alice' ---");
        cbf.remove("user:alice");

        // After removal, alice should be gone
        System.out.println("Contains 'user:alice' after removal: " + cbf.mightContain("user:alice"));
        // Standard Bloom Filter can't do this!

        System.out.println("Contains 'user:bob' still: " + cbf.mightContain("user:bob"));
    }
}
```

---

---

# 📅 Day 3 — HyperLogLog

## What Is It?

HyperLogLog (HLL) estimates the **number of distinct elements** (cardinality) in a dataset using remarkably little memory.

> **Problem:** Count unique visitors to a website that gets 1 billion visits/day.
> **HashSet approach:** Stores all IDs → ~8 GB RAM
> **HyperLogLog:** ~12 KB RAM with ~1% error margin

## Key Insight — The Bit Pattern Trick

The core insight comes from hashing. If items are hashed uniformly:
- Probability of a hash starting with 0 is 1/2
- Probability of starting with "00" is 1/4
- Probability of starting with "000...0" (k zeros) is 1/2^k

If the **maximum number of leading zeros** observed is `k`, then there are roughly `2^k` distinct elements.

## How HyperLogLog Works

1. Hash each element to a binary string.
2. Use the first `b` bits to select a **register** (there are `2^b` registers → called `m` buckets).
3. In that register, track the **maximum position of the first `1` bit** (i.e., count of leading zeros + 1).
4. Combine all register values using harmonic mean → estimate cardinality.

```
Element → Hash → [bbbbbb|remaining bits]
                   ↑
               First b bits = bucket index
                         ↑
               Count leading zeros in remaining bits
                         → update register[bucket] = max(register[bucket], count)

Final Estimate = α * m² * (Σ 2^(-register[i]))^(-1)
```

## Java Implementation

```java
// HyperLogLog.java
public class HyperLogLog {

    private final int b;          // number of bits for bucket index
    private final int m;          // number of buckets = 2^b
    private final byte[] registers; // M[j] = max leading zeros seen
    private final double alphaMM;   // bias correction constant

    /**
     * @param b Number of bits for bucket index. More bits = more accurate but more memory.
     *          Typical values: b=4 (16 buckets) to b=16 (65536 buckets)
     *          Standard error ≈ 1.04 / sqrt(m) where m = 2^b
     */
    public HyperLogLog(int b) {
        if (b < 4 || b > 16) throw new IllegalArgumentException("b must be between 4 and 16");
        this.b = b;
        this.m = 1 << b;  // m = 2^b
        this.registers = new byte[m];

        // Alpha constants for bias correction (lookup table)
        this.alphaMM = getAlpha(m) * m * m;

        double stdError = 1.04 / Math.sqrt(m);
        System.out.printf("HyperLogLog: b=%d, m=%d buckets, ~%.2f%% std error, memory=%d bytes%n",
            b, m, stdError * 100, m);
    }

    private double getAlpha(int m) {
        switch (m) {
            case 16:   return 0.673;
            case 32:   return 0.697;
            case 64:   return 0.709;
            default:   return 0.7213 / (1 + 1.079 / m);
        }
    }

    /** Hash function (64-bit FNV-inspired) */
    private long hash(String value) {
        long h = 0xcbf29ce484222325L;
        for (byte byt : value.getBytes()) {
            h ^= byt;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Count leading zeros + 1 in the remaining (64-b) bits.
     * This is the ρ (rho) function in HLL literature.
     */
    private int rho(long w, int max) {
        for (int i = 0; i <= max; i++) {
            if ((w & (1L << (max - i))) != 0) return i + 1;
        }
        return max + 1;
    }

    /** Add an element to the HyperLogLog */
    public void add(String element) {
        long hash = hash(element);

        // Extract first b bits as bucket index
        int bucketIndex = (int) (hash >>> (64 - b));

        // Remaining bits (64 - b bits)
        long remainingBits = hash << b;

        // Count leading zeros in remaining bits
        int leadingZeroPlusOne = rho(remainingBits, 64 - b);

        // Update register to maximum observed value
        if (leadingZeroPlusOne > registers[bucketIndex]) {
            registers[bucketIndex] = (byte) leadingZeroPlusOne;
        }
    }

    /** Estimate cardinality (distinct count) */
    public long estimate() {
        doubleharmonicSum = 0.0;
        int zeroRegisters = 0;

        for (byte reg : registers) {
            harmonicSum += Math.pow(2, -reg);
            if (reg == 0) zeroRegisters++;
        }

        double estimate = alphaMM / harmonicSum;

        // Small range correction — use linear counting if estimate is small
        if (estimate <= 2.5 * m && zeroRegisters > 0) {
            estimate = m * Math.log((double) m / zeroRegisters);
        }

        // Large range correction (if estimate > 1/30 * 2^32)
        if (estimate > (1.0 / 30) * (1L << 32)) {
            estimate = -(1L << 32) * Math.log(1.0 - estimate / (1L << 32));
        }

        return Math.round(estimate);
    }

    public static void main(String[] args) {
        HyperLogLog hll = new HyperLogLog(10); // 1024 buckets, ~3.25% error

        System.out.println("\n--- Simulating Unique Visitor Count ---");

        // Insert 10,000 unique IDs
        int actualUnique = 10_000;
        for (int i = 0; i < actualUnique; i++) {
            hll.add("user-" + i);
        }

        // Insert 2,000 duplicates to prove they don't inflate count
        for (int i = 0; i < 2000; i++) {
            hll.add("user-" + (i % 500)); // re-inserting existing users
        }

        long estimated = hll.estimate();
        double error = Math.abs(estimated - actualUnique) * 100.0 / actualUnique;

        System.out.println("Actual unique elements: " + actualUnique);
        System.out.println("HyperLogLog estimate:   " + estimated);
        System.out.printf("Error: %.2f%%%n", error);
        System.out.printf("Memory used: %d bytes (vs ~%d bytes for HashSet)%n",
            hll.m, actualUnique * 8);
    }
}
```

---

---

# 📅 Day 4 — Count-Min Sketch

## What Is It?

Count-Min Sketch estimates the **frequency of elements** in a data stream — like "how many times did user X click?" or "what are the top trending hashtags?".

> Think of it as a frequency table that uses a **tiny fraction** of the memory a HashMap would need.

## How It Works

Uses a 2D grid of counters: **d rows × w columns**.

```
d hash functions, w counters each

Row 0 (h0): [ 0  | 3  | 0  | 1  | 2  ]
Row 1 (h1): [ 1  | 0  | 4  | 0  | 0  ]
Row 2 (h2): [ 0  | 2  | 0  | 3  | 0  ]

Insert "apple" (count=1):
  h0("apple") = 1 → table[0][1]++
  h1("apple") = 2 → table[1][2]++
  h2("apple") = 3 → table[2][3]++

Query "apple":
  count = min(table[0][1], table[1][2], table[2][3])
```

The **minimum** is taken because counters may be inflated by hash collisions, but the true count is never over-counted.

## Key Formulas

```
Width:  w = ceil(e / ε)       — ε = acceptable error
Depth:  d = ceil(ln(1/δ))     — δ = failure probability

Guarantee: estimate ≤ true_count + ε * N with prob ≥ 1-δ
Where N = total elements inserted
```

## Java Implementation

```java
// CountMinSketch.java
import java.util.*;

public class CountMinSketch {

    private final int width;   // w
    private final int depth;   // d
    private final long[][] table;
    private final long[] seeds; // Random seeds per row for hash functions
    private long totalCount;

    /**
     * @param epsilon Acceptable error as fraction of total (e.g., 0.01 = 1% of N)
     * @param delta   Probability of exceeding the error bound (e.g., 0.001 = 0.1%)
     */
    public CountMinSketch(double epsilon, double delta) {
        this.width = (int) Math.ceil(Math.E / epsilon);
        this.depth = (int) Math.ceil(Math.log(1.0 / delta));
        this.table = new long[depth][width];
        this.seeds = new long[depth];
        this.totalCount = 0;

        Random rand = new Random(42);
        for (int i = 0; i < depth; i++) seeds[i] = rand.nextLong();

        System.out.printf("Count-Min Sketch: %d rows x %d cols = %d counters%n",
            depth, width, depth * width);
    }

    /** Hash a string key for a given row using seeded hashing */
    private int hash(String key, int row) {
        long h = seeds[row];
        for (char c : key.toCharArray()) {
            h = h * 31 + c;
            h ^= (h >>> 17);
        }
        return (int) (Math.abs(h) % width);
    }

    /** Add element with count = 1 */
    public void add(String element) {
        add(element, 1);
    }

    /** Add element with a specific count (useful for batch processing) */
    public void add(String element, long count) {
        for (int i = 0; i < depth; i++) {
            table[i][hash(element, i)] += count;
        }
        totalCount += count;
    }

    /** Estimate frequency of an element */
    public long estimateCount(String element) {
        long minCount = Long.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            minCount = Math.min(minCount, table[i][hash(element, i)]);
        }
        return minCount;
    }

    /**
     * Find Top-K heavy hitters using Count-Min Sketch + Min-Heap.
     * This is the typical production usage pattern.
     */
    public List<Map.Entry<String, Long>> topK(String[] allElements, int k) {
        // Use a map to track candidates (in production, use reservoir sampling)
        Map<String, Long> estimates = new HashMap<>();
        for (String elem : allElements) {
            estimates.put(elem, estimateCount(elem));
        }

        // Sort by estimated count descending, take top K
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(estimates.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    public static void main(String[] args) {
        // ε = 1% error, δ = 0.1% failure probability
        CountMinSketch cms = new CountMinSketch(0.01, 0.001);

        System.out.println("\n--- Simulating Trending Hashtag Counts ---");

        // Simulate hashtag stream with skewed distribution
        Map<String, Integer> actualCounts = new LinkedHashMap<>();
        actualCounts.put("#JavaIsAwesome", 5000);
        actualCounts.put("#AI", 3000);
        actualCounts.put("#SystemDesign", 1500);
        actualCounts.put("#LeetCode", 800);
        actualCounts.put("#OpenAI", 300);

        for (Map.Entry<String, Integer> entry : actualCounts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                cms.add(entry.getKey());
            }
        }

        System.out.println("\nHashtag          | Actual | Estimated | Error");
        System.out.println("-----------------|--------|-----------|------");
        for (Map.Entry<String, Integer> entry : actualCounts.entrySet()) {
            long estimated = cms.estimateCount(entry.getKey());
            double error = Math.abs(estimated - entry.getValue()) * 100.0 / entry.getValue();
            System.out.printf("%-17s| %-7d| %-10d| %.2f%%%n",
                entry.getKey(), entry.getValue(), estimated, error);
        }

        System.out.println("\nTotal items counted: " + cms.totalCount);
    }
}
```

---

---

# 📅 Day 5 — Skip List

## What Is It?

A Skip List is a **probabilistic alternative to balanced trees** (like AVL or Red-Black trees). It provides O(log n) average-case search, insertion, and deletion with a simpler implementation.

## How It Works

Think of it as multiple layers of linked lists:
- **Bottom layer** (Level 0): Contains ALL elements in sorted order.
- **Higher layers**: Contain a random subset, acting as "express lanes".

```
Level 3:  1 --------→ 7 --------→ null
Level 2:  1 --→ 4 --→ 7 --→ 9 --→ null
Level 1:  1 → 3 → 4 → 5 → 7 → 8 → 9 → null
Level 0:  1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → null

Search for 8:
  Start at Level 3: 1 → 7 (7 < 8, move right) → null (overshoot, go down)
  Level 2: 7 → 9 (9 > 8, go down)
  Level 1: 7 → 8 ✓ Found!
```

## Why Probabilistic?

When inserting, the level of a new node is determined by **coin flipping**:
- Level 1 always: probability 1
- Level 2: probability 1/2
- Level 3: probability 1/4
- Level k: probability (1/2)^(k-1)

This randomness ensures O(log n) height on average without complex rebalancing.

## Java Implementation

```java
// SkipList.java
import java.util.Random;

public class SkipList {

    private static final int MAX_LEVEL = 16;
    private static final double PROBABILITY = 0.5;

    /** A single node in the skip list */
    static class Node {
        int key;
        int value;
        Node[] next; // forward pointers at each level

        Node(int key, int value, int level) {
            this.key = key;
            this.value = value;
            this.next = new Node[level + 1];
        }
    }

    private final Node head;   // Sentinel head node
    private int currentLevel;  // Highest level with elements
    private int size;
    private final Random random;

    public SkipList() {
        this.head = new Node(Integer.MIN_VALUE, 0, MAX_LEVEL);
        this.currentLevel = 0;
        this.size = 0;
        this.random = new Random();
    }

    /** Randomly generate level for new node using coin flip */
    private int randomLevel() {
        int level = 0;
        while (level < MAX_LEVEL - 1 && random.nextDouble() < PROBABILITY) {
            level++;
        }
        return level;
    }

    /**
     * Search for a key — O(log n) average
     */
    public Integer search(int key) {
        Node current = head;
        // Start from highest level and move down
        for (int i = currentLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i]; // move right
            }
        }
        current = current.next[0]; // Move to level 0
        if (current != null && current.key == key) {
            return current.value;
        }
        return null; // Not found
    }

    /**
     * Insert or update a key — O(log n) average
     */
    public void insert(int key, int value) {
        // Track nodes that need their forward pointers updated
        Node[] update = new Node[MAX_LEVEL + 1];
        Node current = head;

        for (int i = currentLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        // If key already exists, update value
        if (current != null && current.key == key) {
            current.value = value;
            return;
        }

        // New node — generate random level
        int newLevel = randomLevel();

        // If new level is higher than current, head must point to new node
        if (newLevel > currentLevel) {
            for (int i = currentLevel + 1; i <= newLevel; i++) {
                update[i] = head;
            }
            currentLevel = newLevel;
        }

        // Create new node and wire up forward pointers
        Node newNode = new Node(key, value, newLevel);
        for (int i = 0; i <= newLevel; i++) {
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }
        size++;
    }

    /**
     * Delete a key — O(log n) average
     */
    public boolean delete(int key) {
        Node[] update = new Node[MAX_LEVEL + 1];
        Node current = head;

        for (int i = currentLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current == null || current.key != key) {
            return false; // Not found
        }

        // Unlink node from each level
        for (int i = 0; i <= currentLevel; i++) {
            if (update[i].next[i] != current) break;
            update[i].next[i] = current.next[i];
        }

        // Lower currentLevel if top levels are now empty
        while (currentLevel > 0 && head.next[currentLevel] == null) {
            currentLevel--;
        }

        size--;
        return true;
    }

    /** Print the skip list structure */
    public void print() {
        System.out.println("\nSkip List (level " + currentLevel + ", size=" + size + "):");
        for (int i = currentLevel; i >= 0; i--) {
            Node node = head.next[i];
            System.out.print("Level " + i + ": HEAD");
            while (node != null) {
                System.out.print(" → " + node.key + "(" + node.value + ")");
                node = node.next[i];
            }
            System.out.println(" → null");
        }
    }

    public static void main(String[] args) {
        SkipList skipList = new SkipList();

        System.out.println("--- Inserting elements ---");
        int[] keys = {5, 2, 8, 1, 9, 3, 7, 4, 6, 10};
        for (int k : keys) {
            skipList.insert(k, k * 10);
        }
        skipList.print();

        System.out.println("\n--- Search ---");
        System.out.println("Search(7): " + skipList.search(7));    // 70
        System.out.println("Search(11): " + skipList.search(11));   // null

        System.out.println("\n--- Delete 5 ---");
        skipList.delete(5);
        skipList.print();

        System.out.println("\n--- Range Query [3..8] ---");
        // Traverse level 0 for range queries
        System.out.print("Keys in [3,8]: ");
        // Start traversal
        var node = skipList.head.next[0];
        while (node != null) {
            if (node.key >= 3 && node.key <= 8) System.out.print(node.key + " ");
            node = node.next[0];
        }
        System.out.println();
    }
}
```

---

---

# 📅 Day 6 — MinHash & Locality Sensitive Hashing (LSH)

## What Is It?

**MinHash** estimates the **Jaccard similarity** between two sets without actually comparing all elements.

> Jaccard Similarity = |A ∩ B| / |A ∪ B|
> "How similar are two documents, user behavior profiles, or product catalogs?"

## Core Idea

If you apply the same random permutation to two sets and take the minimum element, the probability that both sets have the same minimum equals their Jaccard similarity.

```
P(minHash(A) == minHash(B)) = |A ∩ B| / |A ∪ B| = Jaccard(A, B)
```

Use `k` different hash functions → estimate accuracy improves with `k`.

## Java Implementation

```java
// MinHash.java
import java.util.*;

public class MinHash {

    private final int numHashFunctions;  // k — more = better estimate
    private final int[][] coefficients;  // a, b parameters for hash functions
    private static final int LARGE_PRIME = 2_038_074_743; // large prime

    public MinHash(int numHashFunctions) {
        this.numHashFunctions = numHashFunctions;
        this.coefficients = new int[numHashFunctions][2];
        Random rand = new Random(42);
        for (int i = 0; i < numHashFunctions; i++) {
            coefficients[i][0] = rand.nextInt(Integer.MAX_VALUE - 1) + 1; // a
            coefficients[i][1] = rand.nextInt(Integer.MAX_VALUE - 1) + 1; // b
        }
    }

    /** Hash function h_i(x) = (a*x + b) % prime */
    private int hashFunction(int index, int value) {
        long a = coefficients[index][0];
        long b = coefficients[index][1];
        return (int) ((a * value + b) % LARGE_PRIME);
    }

    /**
     * Compute MinHash signature for a set.
     * @param set The set of integer elements (e.g., word hashes from a document)
     * @return Signature vector of size k
     */
    public int[] computeSignature(Set<Integer> set) {
        int[] signature = new int[numHashFunctions];
        Arrays.fill(signature, Integer.MAX_VALUE);

        for (int element : set) {
            for (int i = 0; i < numHashFunctions; i++) {
                int hashValue = hashFunction(i, element);
                if (hashValue < signature[i]) {
                    signature[i] = hashValue;
                }
            }
        }
        return signature;
    }

    /**
     * Estimate Jaccard similarity between two sets using their MinHash signatures.
     * Count how many positions in the signatures are equal.
     */
    public double estimateJaccard(int[] sig1, int[] sig2) {
        int matches = 0;
        for (int i = 0; i < numHashFunctions; i++) {
            if (sig1[i] == sig2[i]) matches++;
        }
        return (double) matches / numHashFunctions;
    }

    /** Compute actual Jaccard for comparison */
    public double actualJaccard(Set<Integer> setA, Set<Integer> setB) {
        Set<Integer> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<Integer> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    /** Convert a text document to a set of shingle hashes */
    public Set<Integer> shingleDocument(String text, int k) {
        Set<Integer> shingles = new HashSet<>();
        String[] words = text.toLowerCase().split("\\s+");
        for (int i = 0; i <= words.length - k; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < i + k; j++) sb.append(words[j]).append(" ");
            shingles.add(sb.toString().hashCode());
        }
        return shingles;
    }

    public static void main(String[] args) {
        MinHash mh = new MinHash(128); // 128 hash functions

        // Simulate document similarity
        String doc1 = "the quick brown fox jumps over the lazy dog";
        String doc2 = "the quick brown fox leaps over the sleeping dog"; // similar
        String doc3 = "machine learning and artificial intelligence systems"; // very different

        Set<Integer> s1 = mh.shingleDocument(doc1, 2);
        Set<Integer> s2 = mh.shingleDocument(doc2, 2);
        Set<Integer> s3 = mh.shingleDocument(doc3, 2);

        int[] sig1 = mh.computeSignature(s1);
        int[] sig2 = mh.computeSignature(s2);
        int[] sig3 = mh.computeSignature(s3);

        System.out.println("=== MinHash Document Similarity ===\n");

        System.out.println("Doc1 vs Doc2 (similar documents):");
        System.out.printf("  Actual Jaccard:    %.4f%n", mh.actualJaccard(s1, s2));
        System.out.printf("  MinHash Estimate:  %.4f%n", mh.estimateJaccard(sig1, sig2));

        System.out.println("\nDoc1 vs Doc3 (different documents):");
        System.out.printf("  Actual Jaccard:    %.4f%n", mh.actualJaccard(s1, s3));
        System.out.printf("  MinHash Estimate:  %.4f%n", mh.estimateJaccard(sig1, sig3));
    }
}
```

---

---

# 📅 Day 7 — Cuckoo Filter + Review

## What Is It?

A **Cuckoo Filter** is a modern alternative to the Bloom Filter with two key advantages:
1. **Supports deletion** (unlike standard Bloom Filters)
2. **Better space efficiency** at low false positive rates

## How It Works

Uses a technique called **cuckoo hashing**:
- Compute two candidate buckets for each element: `h1(x)` and `h2(x)`
- Store a **fingerprint** (small hash of the element) in one of the two buckets
- If both buckets are full, evict an existing fingerprint, move it to its alternate bucket (kicking, like a cuckoo bird pushing eggs out of nests)
- `h2(x)` can be derived from `h1(x)` and the fingerprint, enabling deletion

```
Insert "apple" (fingerprint = f):
  bucket1 = h1("apple") = 3   → table[3] has space → store f there ✓

Insert "banana" (fingerprint = f'):
  bucket1 = h1("banana") = 3  → full!
  bucket2 = h2("banana") = 7  → has space → store f' there ✓

Query "apple":
  bucket1 = h1("apple") = 3  → found f → PRESENT ✓

Delete "apple":
  bucket1 = 3 → remove f → DELETED ✓
```

## Java Implementation

```java
// CuckooFilter.java
import java.util.Random;

public class CuckooFilter {

    private static final int FINGERPRINT_SIZE = 8;    // bits per fingerprint
    private static final int BUCKET_SIZE = 4;          // entries per bucket
    private static final int MAX_KICKS = 500;          // max relocation attempts
    private static final int FINGERPRINT_MASK = (1 << FINGERPRINT_SIZE) - 1; // 0xFF

    private final byte[][] table;  // table[bucket][slot] = fingerprint
    private final int numBuckets;
    private int count;

    /**
     * @param capacity Expected number of elements
     */
    public CuckooFilter(int capacity) {
        this.numBuckets = nextPowerOf2(capacity / BUCKET_SIZE + 1);
        this.table = new byte[numBuckets][BUCKET_SIZE];
        this.count = 0;

        System.out.printf("Cuckoo Filter: %d buckets × %d slots, capacity≈%d%n",
            numBuckets, BUCKET_SIZE, numBuckets * BUCKET_SIZE);
    }

    private int nextPowerOf2(int n) {
        n--;
        n |= n >> 1; n |= n >> 2; n |= n >> 4; n |= n >> 8; n |= n >> 16;
        return n + 1;
    }

    /** Compute fingerprint — small non-zero hash of element */
    private byte fingerprint(String element) {
        int h = element.hashCode();
        byte f = (byte) (h & FINGERPRINT_MASK);
        return f == 0 ? 1 : f; // fingerprint must be non-zero
    }

    /** Primary bucket */
    private int bucket1(String element) {
        return Math.abs(element.hashCode()) % numBuckets;
    }

    /** Alternate bucket derived from fingerprint (for deletion support) */
    private int bucket2(int bucket1, byte fp) {
        // XOR with fingerprint hash — allows computing bucket1 from bucket2 and fp
        return (bucket1 ^ (fp * 0x5bd1e995)) & (numBuckets - 1);
    }

    /** Try to insert fingerprint into a bucket */
    private boolean insertIntoBucket(int bucketIndex, byte fp) {
        for (int slot = 0; slot < BUCKET_SIZE; slot++) {
            if (table[bucketIndex][slot] == 0) {
                table[bucketIndex][slot] = fp;
                count++;
                return true;
            }
        }
        return false; // Bucket full
    }

    /** Insert element into Cuckoo Filter */
    public boolean insert(String element) {
        byte fp = fingerprint(element);
        int b1 = bucket1(element);
        int b2 = bucket2(b1, fp);

        // Try inserting into bucket1 or bucket2 directly
        if (insertIntoBucket(b1, fp)) return true;
        if (insertIntoBucket(b2, fp)) return true;

        // Both full — start cuckoo kicking
        int currentBucket = b1;
        Random rand = new Random();

        for (int kick = 0; kick < MAX_KICKS; kick++) {
            // Randomly evict one fingerprint from currentBucket
            int evictSlot = rand.nextInt(BUCKET_SIZE);
            byte evictedFp = table[currentBucket][evictSlot];
            table[currentBucket][evictSlot] = fp;
            fp = evictedFp;

            // Move evicted fingerprint to its alternate bucket
            currentBucket = bucket2(currentBucket, fp);
            if (insertIntoBucket(currentBucket, fp)) return true;
        }

        // Filter is too full — insertion failed
        System.err.println("Cuckoo filter too full, insertion failed!");
        return false;
    }

    /** Check if element might be in the filter */
    public boolean mightContain(String element) {
        byte fp = fingerprint(element);
        int b1 = bucket1(element);
        int b2 = bucket2(b1, fp);

        // Check both candidate buckets
        for (int slot = 0; slot < BUCKET_SIZE; slot++) {
            if (table[b1][slot] == fp || table[b2][slot] == fp) return true;
        }
        return false;
    }

    /** Delete element from filter */
    public boolean delete(String element) {
        byte fp = fingerprint(element);
        int b1 = bucket1(element);
        int b2 = bucket2(b1, fp);

        // Search and remove from bucket1
        for (int slot = 0; slot < BUCKET_SIZE; slot++) {
            if (table[b1][slot] == fp) {
                table[b1][slot] = 0;
                count--;
                return true;
            }
        }
        // Search and remove from bucket2
        for (int slot = 0; slot < BUCKET_SIZE; slot++) {
            if (table[b2][slot] == fp) {
                table[b2][slot] = 0;
                count--;
                return true;
            }
        }
        return false; // Not found
    }

    public int getCount() { return count; }

    public static void main(String[] args) {
        CuckooFilter cf = new CuckooFilter(1000);

        System.out.println("\n--- Inserting items ---");
        String[] items = {"apple", "banana", "cherry", "date", "elderberry"};
        for (String item : items) {
            cf.insert(item);
            System.out.println("Inserted: " + item);
        }

        System.out.println("\n--- Membership Queries ---");
        for (String item : items) {
            System.out.println("mightContain('" + item + "'): " + cf.mightContain(item));
        }
        System.out.println("mightContain('grape'): " + cf.mightContain("grape"));

        System.out.println("\n--- Deleting 'banana' (impossible in standard Bloom Filter!) ---");
        cf.delete("banana");
        System.out.println("mightContain('banana') after deletion: " + cf.mightContain("banana"));
        System.out.println("mightContain('apple') still: " + cf.mightContain("apple"));

        System.out.println("\nTotal items in filter: " + cf.getCount());
    }
}
```

---

---

# 🔁 Day 7 — Full Review & Comparison Table

## Data Structure Cheat Sheet

| Data Structure | Query Type | False Neg? | False Pos? | Deletion | Space |
|---|---|---|---|---|---|
| Bloom Filter | Membership | ❌ Never | ✅ Yes | ❌ No | O(n) bits |
| Counting BF | Membership | ❌ Never | ✅ Yes | ✅ Yes | O(n × counter_bits) |
| Cuckoo Filter | Membership | ❌ Never | ✅ Yes | ✅ Yes | ~O(n) bits |
| HyperLogLog | Cardinality | N/A | N/A | ❌ No | O(log log n) |
| Count-Min Sketch | Frequency | ❌ Never | ✅ (overcount) | ❌ No | O(w × d) |
| Skip List | Ordered search | ❌ Never | ❌ Never | ✅ Yes | O(n log n) |
| MinHash | Similarity | N/A | N/A | ❌ No | O(k) per set |

## When to Use What

```
Need membership check with low memory?
  → Bloom Filter or Cuckoo Filter

Need to delete members?
  → Counting Bloom Filter or Cuckoo Filter

Need to count unique elements in a stream?
  → HyperLogLog

Need to find top-K most frequent items?
  → Count-Min Sketch

Need fast sorted operations without tree rotation complexity?
  → Skip List

Need to find similar documents or users?
  → MinHash + LSH
```

## Real System Design Connections

| System | Data Structure | Why |
|---|---|---|
| Cassandra SSTable lookup | Bloom Filter | Skip disk I/O for missing keys |
| Redis PFCOUNT | HyperLogLog | Count unique daily active users |
| Twitter trending | Count-Min Sketch | Top hashtag frequency in stream |
| Spotify recommendations | MinHash LSH | Find similar user taste profiles |
| Redis Sorted Sets | Skip List | O(log n) ranked leaderboards |
| Chrome Safe Browsing | Bloom Filter | Check malicious URLs locally |
| Akamai one-hit filter | Cuckoo Filter | Admission control for CDN cache |

---

## 📚 Next Steps After Day 7

1. **t-Digest** — Estimate percentiles (p50, p95, p99) in streaming data
2. **Reservoir Sampling** — Sample k random items from a stream
3. **Flajolet-Martin** — Earlier cardinality estimation algorithm
4. **SimHash** — Google's near-duplicate document detection
5. **XOR Filter** — Newer, more space-efficient alternative to Bloom Filter

### Recommended Libraries for Production Java Code

```xml
<!-- Guava (Google) — BloomFilter -->
<dependency>
  <groupId>com.google.guava</groupId>
  <artifactId>guava</artifactId>
  <version>32.1.3-jre</version>
</dependency>

<!-- HyperLogLog++ in Java -->
<dependency>
  <groupId>com.clearspring.analytics</groupId>
  <artifactId>stream</artifactId>
  <version>2.9.8</version>
</dependency>

<!-- Apache DataSketches — production-grade probabilistic structures -->
<dependency>
  <groupId>org.apache.datasketches</groupId>
  <artifactId>datasketches-java</artifactId>
  <version>4.1.0</version>
</dependency>
```

---

*Created for SDE 3 interview preparation. Covers probabilistic data structures used in large-scale distributed systems.*
