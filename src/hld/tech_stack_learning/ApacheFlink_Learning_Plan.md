# Apache Flink: 7-Day Learning Plan — Beginner to Advanced

> **What is Apache Flink?**
> Apache Flink is a distributed stream processing framework for stateful computations
> over unbounded (streams) and bounded (batch) data. It processes events with low latency
> (milliseconds), exactly-once guarantees, and fault tolerance via checkpointing.
>
> **Flink vs Spark Streaming**: Flink is true streaming (event-by-event). Spark Streaming
> is micro-batch (processes small batches of events). Flink has lower latency.
>
> **Goal**: By Day 7 you can build, deploy, and optimize production Flink jobs,
> and explain streaming internals confidently in SDE3 interviews.
>
> **Time per day**: 2–3 hours study + coding.
> **Tools**: Docker (local Flink), IntelliJ IDEA, Maven, Java 11+.

---

## Setup (Do This Before Day 1)

```bash
# Start Flink via Docker
docker run -d --name flink-jobmanager \
  -p 8081:8081 \
  -e JOB_MANAGER_RPC_ADDRESS=jobmanager \
  flink:1.18-scala_2.12 jobmanager

docker run -d --name flink-taskmanager \
  --link flink-jobmanager:jobmanager \
  -e JOB_MANAGER_RPC_ADDRESS=jobmanager \
  flink:1.18-scala_2.12 taskmanager

# Flink Web UI: http://localhost:8081

# Maven pom.xml dependencies
# <dependency>
#   <groupId>org.apache.flink</groupId>
#   <artifactId>flink-streaming-java</artifactId>
#   <version>1.18.0</version>
# </dependency>
# <dependency>
#   <groupId>org.apache.flink</groupId>
#   <artifactId>flink-connector-kafka</artifactId>
#   <version>3.1.0-1.18</version>
# </dependency>
```

---

## Day 1 — Core Concepts And First Flink Job

### 1.1 The Flink Mental Model

```
Traditional request-response (REST API):
  Request → Process → Response. Finite, one-shot.

Batch processing (Spark, MapReduce):
  Read entire file → Process all records → Write output.
  Works on BOUNDED data (has a start and end).

Stream processing (Flink):
  Events arrive continuously and forever → process each as it arrives → emit results continuously.
  Works on UNBOUNDED data (no defined end).

Why stream processing?
  → Fraud detection: detect fraudulent transaction WHILE it is happening (not next morning in batch).
  → Real-time leaderboard: score updates reflected in < 1 second.
  → Live metrics: "CPU usage over last 5 minutes" refreshed every 10 seconds.
  → IoT: sensor readings processed as they arrive from millions of devices.
```

### 1.2 Flink Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Flink Cluster                          │
│                                                          │
│  ┌─────────────────┐        ┌──────────────────────────┐ │
│  │   JobManager    │        │      TaskManagers        │ │
│  │                 │◄──────►│  ┌────┐  ┌────┐  ┌────┐ │ │
│  │  - Job scheduling        │  │ TM │  │ TM │  │ TM │ │ │
│  │  - Checkpointing         │  │    │  │    │  │    │ │ │
│  │  - Failure recovery      │  │Task│  │Task│  │Task│ │ │
│  │  - REST API (:8081)      │  │Slot│  │Slot│  │Slot│ │ │
│  └─────────────────┘        │  └────┘  └────┘  └────┘ │ │
│                              └──────────────────────────┘ │
└──────────────────────────────────────────────────────────┘

JobManager:   Brain. Schedules tasks, coordinates checkpoints, handles failures.
TaskManager:  Worker. Executes actual computation in Task Slots.
Task Slot:    Unit of resource isolation inside a TaskManager (one slot = one thread).
Parallelism:  How many parallel instances of each operator run. Each instance = one slot.
```

### 1.3 Key Concepts

```
DataStream:   Infinite sequence of records (events). Core abstraction in Flink.
Source:       Where data comes from (Kafka, socket, file, custom).
Transformation: Map, FlatMap, Filter, KeyBy, Window, Reduce, etc.
Sink:         Where results go (Kafka, DB, file, Elasticsearch, custom).

Job Graph:
  Source → Transform1 → Transform2 → ... → Sink
  Each arrow = network transfer between operators (if on different nodes).

Operator Chain:
  Flink merges consecutive operators if: same parallelism + no shuffle between them.
  Chained operators run in same thread → no network overhead between them. ✓
```

### 1.4 First Flink Job (Word Count)

```java
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;

public class WordCount {
    public static void main(String[] args) throws Exception {

        // 1. Set up the execution environment (local or cluster)
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);  // 2 parallel instances of each operator

        // 2. Source: socket (run: nc -lk 9999 in terminal, type words)
        DataStream<String> text = env.socketTextStream("localhost", 9999);

        // 3. Transformations
        DataStream<Tuple2<String, Integer>> counts = text
            .flatMap(new Tokenizer())           // split line → (word, 1)
            .keyBy(value -> value.f0)           // group by word
            .sum(1);                             // sum the count field (index 1)

        // 4. Sink: print to stdout
        counts.print();

        // 5. Execute (Flink is LAZY — nothing runs until execute() is called)
        env.execute("Word Count");
    }

    public static class Tokenizer implements FlatMapFunction<String, Tuple2<String, Integer>> {
        @Override
        public void flatMap(String line, Collector<Tuple2<String, Integer>> out) {
            for (String word : line.toLowerCase().split("\\s+")) {
                if (!word.isEmpty()) {
                    out.collect(new Tuple2<>(word, 1));
                }
            }
        }
    }
}
```

### 1.5 Day 1 Practice

```
1. Start Flink via Docker. Open Web UI at http://localhost:8081.
2. Build and run the WordCount job (socket source).
3. Open a terminal: nc -lk 9999. Type words. Watch output.
4. Observe: Web UI shows the job, DAG of operators, parallelism.
5. Experiment: change parallelism to 4. Notice more task slots used.
```

---

## Day 2 — DataStream API Transformations

### 2.1 Core Transformation Operations

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

DataStream<String> stream = env.fromElements("apple", "banana", "apple", "cherry", "banana", "apple");

// map — one-to-one transformation
DataStream<Integer> lengths = stream.map(s -> s.length());
// apple → 5, banana → 6, apple → 5, ...

// flatMap — one-to-many (or zero-to-many)
DataStream<Character> chars = stream.flatMap((String s, Collector<Character> out) -> {
    for (char c : s.toCharArray()) out.collect(c);
});

// filter — keep elements matching predicate
DataStream<String> longWords = stream.filter(s -> s.length() > 5);
// keeps: banana, cherry, banana

// keyBy — partition stream by key (like GROUP BY in SQL)
// Elements with same key go to same parallel instance → stateful ops per key
KeyedStream<String, String> keyed = stream.keyBy(s -> s);  // key = the string itself

// reduce — rolling aggregation per key
DataStream<Tuple2<String, Integer>> wordCount = stream
    .map(s -> Tuple2.of(s, 1))
    .keyBy(t -> t.f0)
    .reduce((a, b) -> Tuple2.of(a.f0, a.f1 + b.f1));
// For "apple": (apple,1) + (apple,1) = (apple,2) + (apple,1) = (apple,3)

// union — merge multiple streams of same type
DataStream<String> stream2 = env.fromElements("date", "elderberry");
DataStream<String> merged = stream.union(stream2);

// connect — merge two streams of DIFFERENT types (use CoMapFunction)
DataStream<Integer> numbers = env.fromElements(1, 2, 3);
ConnectedStreams<String, Integer> connected = stream.connect(numbers);
DataStream<String> result = connected.map(
    s -> "string: " + s,      // handler for String stream
    n -> "number: " + n        // handler for Integer stream
);

// process — most powerful: access to context, timer, side output
DataStream<String> processed = stream.process(new ProcessFunction<String, String>() {
    @Override
    public void processElement(String value, Context ctx, Collector<String> out) {
        long timestamp = ctx.timestamp();          // event timestamp
        long processingTime = ctx.timerService().currentProcessingTime();
        out.collect(value.toUpperCase());          // emit to main output

        // Register a timer for 1 second from now
        ctx.timerService().registerProcessingTimeTimer(processingTime + 1000);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) {
        out.collect("Timer fired at: " + timestamp);
    }
});
```

### 2.2 Side Outputs (Split Stream)

```java
// OutputTag identifies a side output (must be anonymous class for type info)
OutputTag<String> longWordTag = new OutputTag<String>("long-words") {};
OutputTag<String> errorTag    = new OutputTag<String>("errors") {};

SingleOutputStreamOperator<String> mainStream = stream.process(
    new ProcessFunction<String, String>() {
        @Override
        public void processElement(String s, Context ctx, Collector<String> out) {
            if (s.length() > 5) {
                ctx.output(longWordTag, s);  // → side output 1
            } else if (s.equals("error")) {
                ctx.output(errorTag, s);     // → side output 2
            } else {
                out.collect(s);              // → main output
            }
        }
    }
);

DataStream<String> longWords = mainStream.getSideOutput(longWordTag);
DataStream<String> errors    = mainStream.getSideOutput(errorTag);

// Use case: one job, multiple output sinks based on event type.
//   Purchases → DB, Errors → error Kafka topic, Anomalies → alert Kafka topic.
```

### 2.3 Physical Partitioning

```java
// rebalance — round-robin across all parallel instances (load balancing after source)
stream.rebalance()

// shuffle — random distribution
stream.shuffle()

// rescale — round-robin only to local downstream instances (no network if same TM)
stream.rescale()

// broadcast — send all elements to ALL parallel instances
// Use case: send small config/rule stream to all instances that process big data stream.
stream.broadcast()

// keyBy — hash partition by key (GROUP BY equivalent, keeps order per key)
stream.keyBy(e -> e.userId)

// global — all elements to one instance (parallelism 1 — use carefully, bottleneck!)
stream.global()

// When to use each:
//   Source has uneven partitioning (one partition way hotter) → rebalance() after source.
//   Need to send rule updates to all workers processing events → broadcast().
//   Stateful computation (counters per user, sessions) → always keyBy().
```

### 2.4 Day 2 Practice

```java
// Build: e-commerce event stream processor
// Events: { user_id, item_id, event_type (view/cart/purchase), amount }

// 1. Filter: keep only "purchase" events.
// 2. Map: extract (user_id, amount) tuple.
// 3. KeyBy user_id.
// 4. Reduce: rolling total spend per user.
// 5. Side output: flag users with total > 50000 as "high-value" to a side output.
// 6. Print both main stream and side output.
```

---

## Day 3 — Time, Watermarks, And Windows

### 3.1 Time Semantics

```
Three types of time in streaming:

Event Time:   When did the event HAPPEN (timestamp in event payload).
              E.g., user clicked at 10:30:00 PM (even if we receive it at 10:30:05).
              Best for accurate analytics. Requires watermarks to handle late arrivals.

Ingestion Time: When did the event ARRIVE at Flink source.
                Between event time and processing time in accuracy.

Processing Time: When is Flink processing it right now (system clock).
                Fastest. No watermark logic. But non-deterministic (reprocessing gives
                different results based on when reprocessing happens).

For most production systems: USE EVENT TIME.
  Why: replay correctness. If Flink job crashes and replays Kafka from offset 0,
  event time windows produce identical results. Processing time does not.
```

### 3.2 Watermarks (Handling Late Events)

```
Problem: events arrive OUT OF ORDER.
  Event timestamps: 10:00:01, 10:00:03, 10:00:02, 10:00:05, 10:00:04
  How does Flink know when a 1-minute window "10:00:00–10:01:00" is COMPLETE?
  → It can't wait forever (late events could arrive hours later).
  → Answer: WATERMARK = "I believe no more events earlier than timestamp T will arrive."

Watermark = a timestamp flowing through the stream alongside events.
When watermark W reaches an operator: all event-time windows ending before W are triggered.

Bounded out-of-orderness watermark strategy:
  maxOutOfOrderness = 5 seconds.
  watermark = max_event_timestamp_seen - maxOutOfOrderness.
  Meaning: "I accept events up to 5 seconds late."

WatermarkStrategy<Event> strategy = WatermarkStrategy
    .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
    .withTimestampAssigner((event, ts) -> event.getTimestamp());

stream.assignTimestampsAndWatermarks(strategy);

// Custom watermark strategy (e.g., periodic based on heartbeat events):
WatermarkStrategy.<Event>forMonotonousTimestamps()
    // assumes events arrive in order — if data is pre-sorted by timestamp.

// Late events: what happens to events arriving after watermark has passed?
// Option 1: Drop (default).
// Option 2: allowedLateness(Duration.ofSeconds(10)) → window re-fires for late events.
// Option 3: Side output for late events (capture and handle separately).
```

### 3.3 Windows — The Core Of Stream Aggregation

```
Window: collect a subset of stream events, then apply computation on that subset.

Types of windows:

1. Tumbling Window (non-overlapping, fixed size):
   Each event belongs to exactly ONE window.
   ├──window1──┤├──window2──┤├──window3──┤
   [0s──────60s][60s────120s][120s───180s]
   
   Use case: "Sales total every 1 hour." "Requests per minute."

2. Sliding Window (overlapping, fixed size + slide):
   Each event belongs to MULTIPLE windows.
   Window size=60s, slide=30s:
   ├──────win1──────┤
           ├──────win2──────┤
                   ├──────win3──────┤
   
   Use case: "Rolling average of last 5 minutes, updated every 1 minute."

3. Session Window (dynamic, gap-based):
   Window ends when gap between events exceeds session_gap.
   Events close together → same session. Long pause → new session.
   
   Use case: "User activity session" (page views, clicks for one browsing session).

4. Global Window (all events in one window — requires custom trigger):
   Rarely used directly. Basis for custom window logic.
```

### 3.4 Window Code Examples

```java
// Assume keyed stream of (userId, amount) purchase events with event timestamp

KeyedStream<Purchase, String> keyedStream = purchaseStream
    .assignTimestampsAndWatermarks(
        WatermarkStrategy.<Purchase>forBoundedOutOfOrderness(Duration.ofSeconds(5))
            .withTimestampAssigner((e, ts) -> e.getTimestampMs()))
    .keyBy(Purchase::getUserId);

// Tumbling window — total spend per user per hour
DataStream<Tuple2<String, Double>> hourlySpend = keyedStream
    .window(TumblingEventTimeWindows.of(Time.hours(1)))
    .sum("amount");

// Sliding window — average spend per user over last 5 minutes, updated every minute
DataStream<Tuple2<String, Double>> rollingAvg = keyedStream
    .window(SlidingEventTimeWindows.of(Time.minutes(5), Time.minutes(1)))
    .aggregate(new AverageAggregate());  // custom AggregateFunction

// Session window — total per session (user stops clicking → session ends)
DataStream<Tuple2<String, Double>> sessionTotal = keyedStream
    .window(EventTimeSessionWindows.withGap(Time.minutes(30)))
    .sum("amount");

// ProcessWindowFunction — full access to all events in window + context
keyedStream
    .window(TumblingEventTimeWindows.of(Time.minutes(1)))
    .process(new ProcessWindowFunction<Purchase, String, String, TimeWindow>() {
        @Override
        public void process(String key, Context ctx, Iterable<Purchase> events, Collector<String> out) {
            TimeWindow window = ctx.window();
            long start = window.getStart();
            long end   = window.getEnd();
            double total = StreamSupport.stream(events.spliterator(), false)
                .mapToDouble(Purchase::getAmount).sum();
            out.collect(String.format("User %s: $%.2f [%d–%d]", key, total, start, end));
        }
    });

// Handle late events with allowed lateness + side output
OutputTag<Purchase> lateTag = new OutputTag<Purchase>("late-purchases") {};

SingleOutputStreamOperator<String> result = keyedStream
    .window(TumblingEventTimeWindows.of(Time.minutes(1)))
    .allowedLateness(Duration.ofSeconds(10))  // accept up to 10s late
    .sideOutputLateData(lateTag)              // late beyond 10s → side output
    .process(new MyWindowFunction());

DataStream<Purchase> lateEvents = result.getSideOutput(lateTag);
lateEvents.addSink(new LateEventSink());
```

### 3.5 Triggers And Evictors (Advanced)

```java
// Custom trigger: fire window every 10 events OR after 1 minute (whichever comes first)
keyedStream
    .window(TumblingEventTimeWindows.of(Time.minutes(1)))
    .trigger(new Trigger<Purchase, TimeWindow>() {
        int count = 0;

        @Override
        public TriggerResult onElement(Purchase e, long ts, TimeWindow w, TriggerContext ctx) {
            count++;
            if (count >= 10) { count = 0; return TriggerResult.FIRE; }
            return TriggerResult.CONTINUE;
        }

        @Override
        public TriggerResult onEventTime(long time, TimeWindow w, TriggerContext ctx) {
            return TriggerResult.FIRE_AND_PURGE;  // fire at window end
        }
        // onProcessingTime, clear methods...
    });
```

### 3.6 Day 3 Practice

```
1. Create a stream with timestamps and add BoundedOutOfOrderness watermark (5s).
2. Apply tumbling 1-minute event-time window: count events per key per minute.
3. Apply sliding window (5m window, 1m slide): rolling average amount.
4. Deliberately send a late event (timestamp 10s in past). With allowedLateness(5s):
   does it get processed or go to side output?
5. Session window: simulate user events with 30s gap → observe session boundaries.
```

---

## Day 4 — State Management And Fault Tolerance

### 4.1 Why State Matters

```
Stateless: each event processed independently. No memory between events.
  → FlatMap, Map, Filter — all stateless.

Stateful: processing of event X depends on previously seen events.
  → Word count: need to remember count so far.
  → Fraud detection: need to remember last 10 transactions for this user.
  → Session detection: need to remember time of last event for this user.
  → Deduplication: need to remember which event_ids we've seen.

Flink manages state — not the developer's HashMap.
Why? Because Flink can:
  → Checkpoint state to durable storage (S3) → survive failures.
  → Redistribute state when parallelism changes (rescaling).
  → TTL-expire stale state automatically.
  → Provide exactly-once guarantees by combining state + checkpointing.
```

### 4.2 State Types

```java
// ValueState<T> — one value per key
public class CounterFunction extends KeyedProcessFunction<String, Event, String> {
    // Declare state — one counter per key (user_id)
    private transient ValueState<Long> counterState;

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Long> descriptor =
            new ValueStateDescriptor<>("counter", Long.class, 0L);
        counterState = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(Event event, Context ctx, Collector<String> out) throws Exception {
        Long current = counterState.value();   // read current value for this key
        current++;
        counterState.update(current);          // write back
        out.collect("User " + event.getUserId() + " count: " + current);
    }
}

// ListState<T> — list of values per key (e.g., last N events)
private transient ListState<Event> recentEvents;
// In open():   recentEvents = getRuntimeContext().getListState(new ListStateDescriptor<>("recent", Event.class));
// In process(): recentEvents.add(event); // append
//               for (Event e : recentEvents.get()) { ... }  // iterate

// MapState<K, V> — map per key (e.g., counts per category per user)
private transient MapState<String, Integer> categoryCounts;
// MapState is more efficient than ValueState<HashMap<K,V>> for large maps.

// ReducingState<T> — automatically reduces; stores single value
// AggregatingState<IN, ACC, OUT> — like ReducingState but input/output can differ

// State TTL (auto-expire stale state — critical for memory management)
StateTtlConfig ttlConfig = StateTtlConfig
    .newBuilder(Time.hours(24))
    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)  // reset TTL on each write
    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
    .build();
descriptor.enableTimeToLive(ttlConfig);  // apply to descriptor before registering
```

### 4.3 Checkpointing — Fault Tolerance

```
Checkpoint = snapshot of ALL operator state at a specific point in time.
Stored in: JobManager memory (tiny), FileSystem (production: S3, HDFS, GCS).

If Flink job crashes:
  1. JobManager detects failure.
  2. Restarts job from last successful checkpoint.
  3. Restores all operator state from checkpoint.
  4. Replays events from Kafka offset saved in checkpoint.
  5. Job continues as if crash never happened. ✓

Chandy-Lamport Algorithm (how Flink checks points without stopping):
  → JobManager injects a "barrier" marker into each source partition.
  → Each operator: when it receives barriers from ALL its input partitions →
    snapshot its state → forward barrier downstream.
  → When barrier reaches all sinks → checkpoint is complete.
  → No need to pause the stream. Only brief delay per operator.

Configuration:
  env.enableCheckpointing(60_000);   // checkpoint every 60 seconds
  env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
  env.getCheckpointConfig().setMinPauseBetweenCheckpoints(30_000);  // min gap
  env.getCheckpointConfig().setCheckpointTimeout(120_000);          // fail if > 2min
  env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);          // one at a time

  // Checkpoint storage (S3)
  env.getCheckpointConfig().setCheckpointStorage("s3://my-bucket/flink-checkpoints/");

Savepoint (manual checkpoint for upgrades):
  // Trigger via CLI before stopping job:
  flink savepoint <job_id> s3://my-bucket/savepoints/
  
  // Start new job version from savepoint:
  flink run -s s3://my-bucket/savepoints/<sp-id> my-new-job.jar
  // State is restored. Data not lost across job upgrades. ✓
```

### 4.4 Exactly-Once Semantics

```
Three delivery guarantees:
  At-most-once:  events may be lost (no retry). Fastest.
  At-least-once: events may be duplicated (retry on failure). Common.
  Exactly-once:  each event effects output exactly once. Most complex.

Flink achieves exactly-once with Kafka:
  → Source: Kafka consumer offset stored IN checkpoint.
     On restart: replay from checkpointed offset → no lost events.
  → Sink: two-phase commit (2PC) or idempotent writes.
     KafkaSink uses 2PC: pre-commit on checkpoint → commit when checkpoint complete.
     If failure between pre-commit and commit → abort transaction → retry.

  Source offset + State + Sink transaction → all committed atomically at checkpoint.
  → Even if machine dies mid-checkpoint: restart rolls back to previous checkpoint state.

Limitations of exactly-once:
  → Only possible with transactional sinks (Kafka, databases with transactions).
  → For non-transactional sinks (Elasticsearch, stdout): at-least-once only.
  → Checkpoint interval = latency of exactly-once guarantee.
    Checkpoint every 60s → up to 60s of re-processing on failure.
```

### 4.5 Day 4 Practice

```java
// Build a stateful deduplication job:
// Input: stream of events with { event_id, user_id, payload }.
// Some events are duplicates (same event_id sent twice).
// Goal: emit each event_id exactly once.

// Solution using MapState<String, Boolean>:
public class DeduplicationFunction extends KeyedProcessFunction<String, Event, Event> {
    private transient ValueState<Boolean> seenState;

    @Override
    public void open(Configuration params) {
        StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.hours(1)).build();
        ValueStateDescriptor<Boolean> desc = new ValueStateDescriptor<>("seen", Boolean.class);
        desc.enableTimeToLive(ttl);
        seenState = getRuntimeContext().getState(desc);
    }

    @Override
    public void processElement(Event e, Context ctx, Collector<Event> out) throws Exception {
        if (seenState.value() == null) {
            seenState.update(true);  // first time seeing this event_id
            out.collect(e);          // emit
        }
        // else: duplicate → drop silently
    }
}
// stream.keyBy(Event::getEventId).process(new DeduplicationFunction())
```

---

## Day 5 — Kafka Integration And Real-World Pipelines

### 5.1 Kafka Source

```java
// Kafka consumer as Flink source
KafkaSource<String> source = KafkaSource.<String>builder()
    .setBootstrapServers("kafka:9092")
    .setTopics("purchase-events")
    .setGroupId("flink-consumer-group")
    .setStartingOffsets(OffsetsInitializer.earliest())  // or latest(), committedOffsets()
    .setValueOnlyDeserializer(new SimpleStringSchema())
    .build();

DataStream<String> stream = env.fromSource(
    source,
    WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofSeconds(5)),
    "Kafka Source"
);

// With custom deserializer (JSON events)
KafkaSource<PurchaseEvent> typedSource = KafkaSource.<PurchaseEvent>builder()
    .setBootstrapServers("kafka:9092")
    .setTopics("purchase-events")
    .setGroupId("flink-consumer-group")
    .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
    .setDeserializer(KafkaRecordDeserializationSchema.valueOnly(
        new JsonDeserializationSchema<>(PurchaseEvent.class)))
    .build();

// Watermark from Kafka message timestamp:
WatermarkStrategy.<PurchaseEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
    .withTimestampAssigner((event, kafkaTs) -> event.getEventTimestampMs());
```

### 5.2 Kafka Sink

```java
// Kafka producer as Flink sink (with exactly-once via transactions)
KafkaSink<String> sink = KafkaSink.<String>builder()
    .setBootstrapServers("kafka:9092")
    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
        .setTopic("output-topic")
        .setValueSerializationSchema(new SimpleStringSchema())
        .build())
    .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)      // requires 2PC
    // .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)  // simpler, no transactions
    .setTransactionalIdPrefix("my-flink-job")
    .build();

stream.sinkTo(sink);

// For exactly-once Kafka sink: Kafka broker transaction.timeout.ms must be > checkpoint interval.
// Set: transaction.timeout.ms=900000 (15 min) on Kafka broker.
```

### 5.3 Real-World Pipeline: Fraud Detection

```java
public class FraudDetectionJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(30_000);   // checkpoint every 30s
        env.setParallelism(4);

        // Source: purchase events from Kafka
        KafkaSource<Transaction> source = KafkaSource.<Transaction>builder()
            .setBootstrapServers("kafka:9092")
            .setTopics("transactions")
            .setGroupId("fraud-detection")
            .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
            .setDeserializer(KafkaRecordDeserializationSchema.valueOnly(
                new JsonDeserializationSchema<>(Transaction.class)))
            .build();

        DataStream<Transaction> transactions = env.fromSource(
            source,
            WatermarkStrategy.<Transaction>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                .withTimestampAssigner((t, ts) -> t.getTimestampMs()),
            "Transaction Source"
        );

        // Key by card_id — all transactions for same card go to same instance
        KeyedStream<Transaction, String> byCard = transactions.keyBy(Transaction::getCardId);

        // Detect: same card, 3+ transactions > $500 within 5 minutes
        OutputTag<Alert> fraudTag = new OutputTag<Alert>("fraud-alerts") {};

        SingleOutputStreamOperator<Transaction> processed = byCard
            .process(new FraudDetectorFunction(fraudTag));

        DataStream<Alert> fraudAlerts = processed.getSideOutput(fraudTag);

        // Sink: fraud alerts to separate Kafka topic
        KafkaSink<Alert> alertSink = KafkaSink.<Alert>builder()
            .setBootstrapServers("kafka:9092")
            .setRecordSerializer(
                KafkaRecordSerializationSchema.builder()
                    .setTopic("fraud-alerts")
                    .setValueSerializationSchema(new JsonSerializationSchema<>())
                    .build())
            .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
            .build();

        fraudAlerts.sinkTo(alertSink);
        env.execute("Fraud Detection Pipeline");
    }
}

public class FraudDetectorFunction extends KeyedProcessFunction<String, Transaction, Transaction> {
    private final OutputTag<Alert> fraudTag;

    // State: list of recent high-value transaction timestamps per card
    private transient ListState<Long> highValueTimestamps;

    @Override
    public void open(Configuration params) {
        StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.minutes(10)).build();
        ListStateDescriptor<Long> desc = new ListStateDescriptor<>("timestamps", Long.class);
        desc.enableTimeToLive(ttl);
        highValueTimestamps = getRuntimeContext().getListState(desc);
    }

    @Override
    public void processElement(Transaction txn, Context ctx, Collector<Transaction> out) throws Exception {
        out.collect(txn);  // always emit transaction to main output

        if (txn.getAmount() > 500) {
            long now = txn.getTimestampMs();

            // Clean up timestamps older than 5 minutes
            List<Long> recent = new ArrayList<>();
            for (Long ts : highValueTimestamps.get()) {
                if (now - ts <= 5 * 60 * 1000L) recent.add(ts);
            }
            recent.add(now);
            highValueTimestamps.update(recent);

            // If 3+ high-value transactions in 5 min → flag as fraud
            if (recent.size() >= 3) {
                ctx.output(fraudTag, new Alert(txn.getCardId(), "3+ high-value txns in 5min", now));
            }
        }
    }
}
```

### 5.4 Day 5 Practice

```
1. Set up a local Kafka (Docker: bitnami/kafka:latest).
2. Create topics: "transactions", "fraud-alerts".
3. Build the fraud detection job above.
4. Produce test transactions via kafka-console-producer.
5. Consume fraud alerts from kafka-console-consumer.
6. Force a simulated failure (kill and restart Flink job). Verify it resumes from checkpoint.
```

---

## Day 6 — Table API, SQL, And Complex Event Processing

### 6.1 Table API And Flink SQL

```java
// Table API wraps DataStream as a relational table
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

// Register DataStream as a table
Table txnTable = tableEnv.fromDataStream(transactions,
    Schema.newBuilder()
        .column("cardId", DataTypes.STRING())
        .column("amount", DataTypes.DOUBLE())
        .column("merchantCategory", DataTypes.STRING())
        .columnByExpression("eventTime", "CAST(timestampMs AS TIMESTAMP_LTZ(3))")
        .watermark("eventTime", "eventTime - INTERVAL '5' SECOND")
        .build());

tableEnv.createTemporaryView("transactions", txnTable);

// SQL query on streaming data
Table result = tableEnv.sqlQuery(
    "SELECT " +
    "  cardId, " +
    "  SUM(amount) AS totalAmount, " +
    "  COUNT(*) AS txnCount, " +
    "  TUMBLE_START(eventTime, INTERVAL '1' HOUR) AS windowStart " +
    "FROM transactions " +
    "GROUP BY cardId, TUMBLE(eventTime, INTERVAL '1' HOUR)"
);

// Convert back to DataStream
DataStream<Row> resultStream = tableEnv.toDataStream(result);
resultStream.print();

// Flink SQL with DDL (create table pointing to Kafka)
tableEnv.executeSql("CREATE TABLE purchases (" +
    "  userId STRING," +
    "  itemId STRING," +
    "  amount DOUBLE," +
    "  eventTime TIMESTAMP(3)," +
    "  WATERMARK FOR eventTime AS eventTime - INTERVAL '5' SECOND" +
    ") WITH (" +
    "  'connector' = 'kafka'," +
    "  'topic' = 'purchases'," +
    "  'properties.bootstrap.servers' = 'kafka:9092'," +
    "  'format' = 'json'" +
    ")");

tableEnv.executeSql("CREATE TABLE top_spenders WITH ('connector' = 'kafka', ...) AS " +
    "SELECT userId, SUM(amount) FROM purchases GROUP BY userId, TUMBLE(eventTime, INTERVAL '1' HOUR)");
```

### 6.2 Window Functions In SQL

```sql
-- TUMBLE window
SELECT userId, SUM(amount) AS total,
       TUMBLE_START(eventTime, INTERVAL '1' HOUR) AS w_start
FROM purchases
GROUP BY userId, TUMBLE(eventTime, INTERVAL '1' HOUR);

-- HOP window (sliding)
SELECT userId, AVG(amount) AS avg_amount
FROM purchases
GROUP BY userId, HOP(eventTime, INTERVAL '10' MINUTE, INTERVAL '1' HOUR);
-- window size=1h, slide=10min

-- SESSION window
SELECT userId, SUM(amount) AS session_total
FROM purchases
GROUP BY userId, SESSION(eventTime, INTERVAL '30' MINUTE);

-- OVER window (running aggregation — like SQL window functions)
SELECT cardId, amount,
       SUM(amount) OVER (PARTITION BY cardId ORDER BY eventTime
                         ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS rolling_10_sum
FROM transactions;
-- Running sum of last 10 transactions per card (stateful, no window boundary needed).
```

### 6.3 Complex Event Processing (CEP) Pattern Matching

```java
// Detect pattern: small transaction followed by large transaction within 10 minutes
// (common fraud signal: test card with small amount, then large purchase)

import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;

Pattern<Transaction, ?> fraudPattern = Pattern.<Transaction>begin("small-txn")
    .where(new SimpleCondition<Transaction>() {
        @Override public boolean filter(Transaction t) { return t.getAmount() < 10; }
    })
    .next("large-txn")   // "next" = immediately after. "followedBy" = any time after.
    .where(new SimpleCondition<Transaction>() {
        @Override public boolean filter(Transaction t) { return t.getAmount() > 1000; }
    })
    .within(Time.minutes(10));  // both must occur within 10 minutes

PatternStream<Transaction> patternStream = CEP.pattern(byCard, fraudPattern);

DataStream<Alert> alerts = patternStream.select(
    (Map<String, List<Transaction>> pattern) -> {
        Transaction small = pattern.get("small-txn").get(0);
        Transaction large = pattern.get("large-txn").get(0);
        return new Alert(large.getCardId(),
                         "Test-then-large fraud: $" + small.getAmount() + " then $" + large.getAmount());
    }
);

// CEP patterns available:
//   .next()        — direct succession (next event for same key)
//   .followedBy()  — non-deterministic relaxed contiguity (any event can be in between)
//   .notNext()     — pattern must NOT immediately follow
//   .times(3)      — pattern must match exactly 3 times
//   .timesOrMore(2) — 2 or more times
//   .optional()    — pattern is optional
//   .greedy()      — match as many as possible (with times/timesOrMore)
```

### 6.4 Day 6 Practice

```
1. Register a DataStream as a Flink SQL table.
2. Write SQL: total amount per user per 1-hour tumbling window.
3. Write SQL with HOP window: 5-minute rolling average per user.
4. Build a CEP pattern: login from NEW country immediately followed by high-value purchase
   within 5 minutes → generate "suspicious login" alert.
```

---

## Day 7 — Deployment, Tuning, And Interview Mastery

### 7.1 Deployment Modes

```
Session Mode (default for development):
  → Long-running Flink cluster. Submit multiple jobs to same cluster.
  → Pros: fast job startup (cluster already running). Shared resources.
  → Cons: resource contention between jobs. One bad job can affect others.

Per-Job Mode (deprecated in Flink 1.17 for native K8s/YARN):
  → Dedicated cluster per job. Cluster starts up with job, tears down after.
  → Pros: full isolation. Cons: slow startup (spin up new cluster per job).

Application Mode (recommended for production):
  → Cluster created per application. main() runs on JobManager (not client).
  → Pros: isolation + fast submission (no dependency upload from client). Client not needed.
  → Use for production Kubernetes deployments.
  
  kubernetes-application:
    ./bin/flink run-application \
      --target kubernetes-application \
      -Dkubernetes.cluster-id=my-flink-job \
      -Dkubernetes.container.image=my-flink-image:1.0 \
      local:///opt/flink/usrlib/my-job.jar
```

### 7.2 Performance Tuning

```
Parallelism:
  → Set at job level: env.setParallelism(N).
  → Override per operator: stream.map(...).setParallelism(8).
  → Rule: parallelism ≈ number of Kafka partitions for source operators.
    More parallelism than partitions → idle tasks. Less → bottleneck.

Backpressure:
  → Flink Web UI shows backpressure per operator.
  → If operator is backpressured: it cannot keep up with incoming rate.
  → Upstream operators slow down automatically (credit-based flow control).
  → Fix: increase parallelism of bottleneck operator, or optimize its logic.

Memory tuning:
  → taskmanager.memory.process.size: total TM memory (JVM heap + native + overhead).
  → taskmanager.memory.managed.fraction: fraction for managed memory (state backend).

State backend:
  HashMapStateBackend (default):
    → State stored in JVM heap. Fast. Limited to heap size.
    → Good for: small state per key, dev/test.
  
  EmbeddedRocksDBStateBackend:
    → State stored in RocksDB on local disk + SSDs.
    → Much larger state capacity (disk >> RAM).
    → Slightly slower (serialization for every access).
    → PRODUCTION DEFAULT for large state jobs.
    → Required for incremental checkpoints.

  env.setStateBackend(new EmbeddedRocksDBStateBackend(true));  // true = incremental
  // Incremental checkpoints: only changes since last checkpoint → much smaller checkpoint size.

Operator chaining:
  → Flink auto-chains compatible operators. Disable for debugging:
    env.disableOperatorChaining();
  → Disable for specific operator: .disableChaining() or .startNewChain().

Network buffers:
  → taskmanager.network.memory.fraction: 0.1 default. Increase for high-throughput jobs.

Async I/O (database lookups in Flink without blocking):
  DataStream<EnrichedEvent> enriched = AsyncDataStream.unorderedWait(
      eventsStream,
      new AsyncDatabaseLookup(),   // implements AsyncFunction
      5, TimeUnit.SECONDS,         // timeout
      100                          // max concurrent async requests
  );
  // Without async I/O: 1000 events/sec × 10ms DB lookup = 10 events/sec throughput.
  // With async I/O: 1000 concurrent DB lookups → 1000 events/sec. ✓
```

### 7.3 Monitoring And Observability

```
Flink Web UI (http://jobmanager:8081):
  → Job DAG with operator metrics.
  → Backpressure visualization (green = OK, red = busy).
  → Checkpoint history and duration.
  → Task manager status and resource utilization.

Metrics reporters:
  // flink-conf.yaml
  metrics.reporter.promgateway.class: org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporter
  metrics.reporter.promgateway.host: prometheus-pushgateway
  metrics.reporter.promgateway.port: 9091

  Then wire Grafana → Prometheus → Flink dashboard.

Key metrics to watch:
  numRecordsInPerSecond:  throughput of source operators.
  numRecordsOutPerSecond: throughput of sink operators.
  isBackPressured:        1 if operator is backpressured.
  lastCheckpointDuration: checkpoint time. Alert if > 30s.
  numberOfFailedCheckpoints: non-zero = trouble.
  currentLag (Kafka source): how far behind Flink is from Kafka latest offset.
  heapMemoryUsed:         JVM heap usage. Alert if > 80%.
```

### 7.4 Interview Cheat Sheet

```
"What is the difference between Flink and Spark Streaming?"
  → Flink: true event-at-a-time streaming. Latency: milliseconds.
  → Spark Streaming: micro-batch (processes 0.1-30 second batches). Latency: seconds.
  → Flink has lower latency and better event-time / watermark support.
  → Spark has better ML/SQL ecosystem and simpler operations.
  → For real-time fraud detection, leaderboards, anomaly detection: Flink.
  → For batch analytics and ML pipelines: Spark.

"How does Flink achieve exactly-once with Kafka?"
  → Three-phase commit:
    1. Checkpoint begins → Kafka source commits current offset to checkpoint state.
    2. Kafka sink pre-commits records in a transaction (not yet visible to consumers).
    3. Checkpoint completes → Kafka sink commits transaction (records become visible).
  → If failure before commit: transaction rolled back. Job restarts from checkpoint.
    Kafka source replays from checkpointed offset → exact same records → re-committed. ✓

"Explain watermarks and why they're necessary."
  → Events arrive out of order (network delays, retries). Flink needs to know when an
    event-time window is "done" (no more events for that time period will arrive).
  → Watermark = assertion: "no events before timestamp W will arrive."
  → When watermark W crosses a window's end time → window fires.
  → Tradeoff: higher maxOutOfOrderness → more late events handled, higher latency.

"What is the difference between event time and processing time?"
  → Processing time: current wall clock when Flink executes. Non-deterministic (replay ≠ original).
  → Event time: timestamp embedded in event payload. Deterministic (replay gives same results).
  → Use event time for: correct analytics, reproducible results, historical reprocessing.

"What state backends does Flink support? Which for production?"
  → HashMapStateBackend: in JVM heap. Fast, limited by heap size. Dev/test.
  → EmbeddedRocksDBStateBackend: on-disk RocksDB. Unlimited state. Incremental checkpoints.
    → PRODUCTION DEFAULT. For any job with > a few GB of state.

"How does Flink handle failure recovery?"
  → Checkpoints: snapshots of all operator state + Kafka offsets saved to S3.
  → On failure: JobManager restarts job, restores state from last checkpoint,
    Kafka source replays from checkpointed offset.
  → Incremental checkpoints (RocksDB): only changed state pages written → faster checkpoints.

"What is backpressure in Flink?"
  → Fast source + slow sink → records accumulate. In Flink: credit-based flow control.
    Downstream operator grants credits to upstream. Upstream sends only up to granted credits.
    Backpressure propagates upstream → source reads from Kafka more slowly → Kafka lag builds.
  → Detect in Web UI → fix by increasing parallelism or optimizing slow operator.
```

### 7.5 Production Architecture Checklist

```
✅ Application Mode deployment (Kubernetes).
✅ EmbeddedRocksDBStateBackend with incremental checkpoints.
✅ Checkpoint interval: 60s. MinPause: 30s. Timeout: 120s.
✅ Checkpoint storage: S3 (reliable, durable).
✅ Savepoint before every job upgrade (allows rollback).
✅ Kafka source: committedOffsets with OffsetResetStrategy.EARLIEST (resume on restart).
✅ Kafka sink: EXACTLY_ONCE for financial data; AT_LEAST_ONCE for metrics/logs.
✅ State TTL on all state (prevents unbounded memory growth).
✅ Metrics → Prometheus → Grafana dashboards.
✅ Alerts: lastCheckpointDuration > 30s, numberOfFailedCheckpoints > 0, Kafka lag > 100K.
✅ Parallelism = number of Kafka source partitions (no idle tasks, no bottleneck).
✅ Async I/O for any external DB lookups (never blocking calls in processElement).
```

---

## Summary: 7-Day Roadmap

| Day | Topics | Key Concepts |
|---|---|---|
| **1** | Fundamentals, First Job | JobManager, TaskManager, Slot, Parallelism, Word Count |
| **2** | DataStream API | map, flatMap, filter, keyBy, side outputs, partitioning |
| **3** | Time & Windows | Event vs Processing time, Watermarks, Tumbling/Sliding/Session windows |
| **4** | State & Fault Tolerance | ValueState/ListState/MapState, Checkpointing, Exactly-once, Savepoints |
| **5** | Kafka Integration | KafkaSource, KafkaSink, Fraud detection pipeline, end-to-end exactly-once |
| **6** | Table API, SQL, CEP | Flink SQL windows, OVER aggregations, CEP pattern matching |
| **7** | Production & Interviews | Deployment modes, RocksDB backend, backpressure, async I/O, interview Q&A |

---

## Resources

- **Official Docs**: https://nightlies.apache.org/flink/flink-docs-stable/
- **Flink Training**: https://nightlies.apache.org/flink/flink-docs-stable/docs/learn-flink/overview/
- **Book**: "Stream Processing with Apache Flink" — Fabian Hueske (O'Reilly)
- **Flink Playgrounds**: https://github.com/apache/flink-playgrounds (ready-to-run Docker examples)
- **Awesome Flink**: https://github.com/wuchong/awesome-flink (curated resources)
