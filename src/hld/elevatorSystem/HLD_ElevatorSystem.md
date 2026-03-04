# 🛗 High-Level Design (HLD) — Elevator System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Object-Oriented Design, Scheduling Algorithms, Concurrency, State Machines, Scalability

---

## 1. Requirements

### 1.1 Functional Requirements
- A building has **N floors** and **M elevators**.
- Users can press an **external call button** (on the floor) to request an elevator — specifying direction (UP / DOWN).
- Inside the elevator, users can press an **internal floor button** to select the destination floor.
- The system must **dispatch the optimal elevator** per request.
- Elevator doors **open/close** automatically at destination; timeout if no entry/exit detected.
- Support **emergency stop** — elevator halts and doors open at nearest floor.
- Support **maintenance mode** — elevator taken out of service.
- **Priority for VIP floors** (e.g., lobby / penthouse) during peak hours (configurable).
- Display **real-time elevator status** on floor panels (which elevator is arriving, ETA).
- Handle **overload detection** — elevator doesn't move if weight limit exceeded.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Availability** | 99.99% — elevator control software must not have downtime |
| **Safety** | Safety-critical: door/motor control must be fail-safe |
| **Latency** | Dispatch decision < 100 ms; elevator must respond to button press in < 500 ms |
| **Scalability** | Support buildings with up to 200 floors and 32 elevators |
| **Concurrency** | Multiple simultaneous requests handled without race conditions |
| **Extensibility** | Easy to plug in new scheduling algorithms |

### 1.3 Assumptions
- Each elevator has a **capacity** (max weight / persons).
- Elevator speed and door open/close time are configurable.
- There is a **central controller** managing all elevators in the building.

---

## 2. Core Entities & Object Model

```
┌──────────────────────────────┐
│         Building             │
│  - floors: List<Floor>       │
│  - elevators: List<Elevator> │
│  - controller: ElevatorCtrl  │
└──────────────┬───────────────┘
               │ has
     ┌──────────┴──────────┐
     ▼                     ▼
┌──────────┐         ┌───────────────────────────────┐
│  Floor   │         │          Elevator              │
│ - floorNo│         │  - id                         │
│ - upBtn  │         │  - currentFloor: int           │
│ - downBtn│         │  - direction: UP/DOWN/IDLE     │
└──────────┘         │  - state: ElevatorState        │
                     │  - destinationQueue: MinHeap   │
                     │  - floorButtons: Set<int>      │
                     │  - currentLoad: int            │
                     │  - maxCapacity: int            │
                     └───────────────────────────────┘

ElevatorState (State Machine):
  IDLE → MOVING_UP → DOOR_OPEN → DOOR_CLOSING → IDLE
  IDLE → MOVING_DOWN → DOOR_OPEN → DOOR_CLOSING → IDLE
  ANY  → EMERGENCY_STOP
  ANY  → MAINTENANCE
```

---

## 3. High-Level Architecture

```
 Floor Panels (External Buttons)
 ┌────────────────────────────┐
 │  Floor 1 [▲] [▼]          │
 │  Floor 2 [▲] [▼]          │
 │  ...                       │
 └────────────┬───────────────┘
              │ ExternalRequest(floor, direction)
              ▼
   ┌───────────────────────────┐
   │   Elevator Controller     │  ◀── InternalRequest(floor) from
   │   (Central Dispatcher)    │      Elevator Panel (inside car)
   │                           │
   │  - Scheduling Algorithm   │
   │  - State Manager          │
   │  - Request Queue           │
   └──────────┬────────────────┘
              │ dispatch(elevatorId, targetFloor)
    ┌─────────┼──────────────────────┐
    ▼         ▼                      ▼
┌────────┐ ┌────────┐          ┌────────┐
│ Elev 1 │ │ Elev 2 │   ...    │ Elev M │
│ (Motor │ │ (Motor │          │ (Motor │
│  Door) │ │  Door) │          │  Door) │
└────┬───┘ └────┬───┘          └────┬───┘
     │           │                   │
     └───────────┴───────────────────┘
                 │ Status updates
                 ▼
     ┌───────────────────────────┐
     │   Display / Floor Panel   │
     │   (ETA, Elevator Number)  │
     └───────────────────────────┘
```

---

## 4. Key Components

### 4.1 Elevator Controller (Central Dispatcher)

The **brain** of the system. Receives all requests and decides which elevator to dispatch.

```java
class ElevatorController {
    List<Elevator> elevators;
    SchedulingStrategy strategy;   // pluggable algorithm

    void handleExternalRequest(int floor, Direction dir) {
        Elevator best = strategy.selectElevator(elevators, floor, dir);
        best.addDestination(floor);
    }

    void handleInternalRequest(int elevatorId, int floor) {
        elevators.get(elevatorId).addDestination(floor);
    }

    void updateElevatorStatus(int elevatorId, ElevatorStatus status) {
        // update state, notify floor displays
    }
}
```

**Responsibilities:**
- Receive external (floor panel) and internal (car panel) requests.
- Apply scheduling algorithm to pick the best elevator.
- Update floor panel displays (ETA, car number).
- Monitor elevator health; mark elevators as `MAINTENANCE` if unresponsive.

### 4.2 Elevator (Physical Car)

Each elevator runs its own **state machine** and manages its own **destination queue**.

```java
class Elevator {
    int id;
    int currentFloor;
    Direction direction;         // UP | DOWN | IDLE
    ElevatorState state;         // IDLE | MOVING | DOOR_OPEN | EMERGENCY | MAINTENANCE
    TreeSet<Integer> upQueue;    // pending floors above (ascending)
    TreeSet<Integer> downQueue;  // pending floors below (descending)
    int currentLoad;
    int maxCapacity;

    void addDestination(int floor) {
        if (floor > currentFloor) upQueue.add(floor);
        else downQueue.add(floor);
    }

    int nextFloor() {
        if (direction == UP)   return upQueue.first();
        if (direction == DOWN) return downQueue.last();
        // IDLE: pick nearest
        return nearest(upQueue, downQueue, currentFloor);
    }

    void openDoor()  { /* signal door motor, set state = DOOR_OPEN */ }
    void closeDoor() { /* signal door motor, set state = DOOR_CLOSING */ }
    void emergencyStop() { state = EMERGENCY_STOP; halt(); openDoor(); }
}
```

### 4.3 Scheduling Algorithms (Strategy Pattern)

The algorithm is **pluggable** via Strategy Pattern — easy to swap without changing core logic.

#### Algorithm 1: SCAN (Elevator Algorithm) — Default
- Elevator moves in one direction, serves all requests in that direction, then reverses.
- Like a disk-seek SCAN algorithm.
- **Pros:** Fair, prevents starvation, efficient.
- **Cons:** Slight wait for requests in opposite direction.

```
Elevator at Floor 5, going UP
Pending: [2, 7, 9, 3, 12]
Serve UP first: 7 → 9 → 12, then reverse DOWN: 3 → 2
```

#### Algorithm 2: LOOK (Optimized SCAN)
- Same as SCAN but reverses direction as soon as no more requests in current direction (doesn't go to top/bottom floor unnecessarily).
- More efficient than pure SCAN.

#### Algorithm 3: Nearest Car (SSTF — Shortest Seek Time First)
- Dispatch the elevator **closest to the requested floor**.
- Best average wait time; risk of **starvation** for far-away requests.

#### Algorithm 4: Zone-Based Dispatch
- Divide floors into zones (e.g., floors 1–10: Elevator A, 11–20: Elevator B).
- Good for **high-rise buildings** with dedicated bank elevators.

```java
interface SchedulingStrategy {
    Elevator selectElevator(List<Elevator> elevators, int floor, Direction dir);
}

class SCANStrategy implements SchedulingStrategy { ... }
class NearestCarStrategy implements SchedulingStrategy { ... }
class ZoneBasedStrategy implements SchedulingStrategy { ... }
```

**Cost Function for Elevator Selection:**
```
score = |currentFloor - requestedFloor|
      + (direction_penalty if going opposite way)
      + (load_penalty if near capacity)
Select elevator with minimum score.
```

### 4.4 State Machine — Elevator States

```
         ┌─────────────────────────────────────┐
         │                                     │
   IDLE ──▶ MOVING_UP ──▶ DOOR_OPEN ──▶ DOOR_CLOSING ──▶ IDLE
         │                     ▲                               │
         └─▶ MOVING_DOWN ──────┘                              │
                                                               │
         ┌────────── EMERGENCY_STOP ◀─── (from any state) ────┘
         │
         └────────── MAINTENANCE   ◀─── (operator command)
```

| State | Trigger | Action |
|---|---|---|
| `IDLE` | No pending requests | Wait |
| `MOVING_UP` | Next destination > currentFloor | Motor UP |
| `MOVING_DOWN` | Next destination < currentFloor | Motor DOWN |
| `DOOR_OPEN` | Reached destination floor | Open doors, 5s timeout |
| `DOOR_CLOSING` | Timeout / close button / sensor clear | Close doors |
| `EMERGENCY_STOP` | Emergency button / overload | Halt + open doors |
| `MAINTENANCE` | Operator command | Out of service |

### 4.5 Request Queue Management

```
External Request: Floor 8, Direction UP
    │
    ▼
Is any elevator:
  (a) currently below floor 8 AND moving UP?  → best candidate (same direction, will pass through)
  (b) currently IDLE?                          → second best (compute distance)
  (c) moving opposite direction?              → worst (will finish current sweep first)
    │
    ▼
Add floor 8 to selected elevator's upQueue (TreeSet<Integer>)
```

Using `TreeSet` (sorted set) for destination queues:
- `upQueue`: ascending order → `first()` gives next floor going up.
- `downQueue`: descending order → `last()` gives next floor going down.
- O(log n) insertion, O(1) next-floor retrieval.

---

## 5. Concurrency Design

The system is inherently concurrent — multiple users pressing buttons simultaneously.

| Concern | Solution |
|---|---|
| Multiple threads dispatching to same elevator | `ReentrantLock` per elevator for queue modification |
| External requests from multiple floors simultaneously | Thread-safe `ConcurrentLinkedQueue` for incoming requests |
| Elevator state transitions | `AtomicReference<ElevatorState>` for lock-free state reads |
| Sensor events (door, weight, floor sensor) | Event loop / dedicated sensor thread per elevator |

```java
class Elevator {
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<ElevatorState> state = 
        new AtomicReference<>(ElevatorState.IDLE);

    void addDestination(int floor) {
        lock.lock();
        try {
            // modify upQueue / downQueue safely
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 6. Data Flow — Handle External Request

```
User presses [▲] at Floor 7
        │
        ▼
ExternalCallEvent(floor=7, direction=UP)
        │
        ▼
ElevatorController.handleExternalRequest(7, UP)
        │
        ▼
SchedulingStrategy.selectElevator(elevators, 7, UP)
  → Computes cost for each elevator
  → Selects Elevator 3 (currently at floor 4, going UP, cost = 3)
        │
        ▼
Elevator3.addDestination(7)  // adds to upQueue
        │
        ▼
Elevator3 motor moves UP: 4 → 5 → 6 → 7
        │
        ▼
Floor sensor detects arrival at Floor 7
        │
        ▼
state = DOOR_OPEN → doors open
        │
        ▼
User enters, presses [12] inside car
        │
        ▼
InternalRequest(elevatorId=3, floor=12)
        │
        ▼
Elevator3.addDestination(12)  // adds to upQueue
        │
        ▼
5-second door timeout → DOOR_CLOSING → doors close → MOVING_UP → 12
```

---

## 7. Edge Cases & Special Scenarios

| Scenario | Handling |
|---|---|
| **Overload** | Weight sensor > max → door stays open + buzzer alert; elevator won't move |
| **Door obstruction** | IR sensor detects obstacle → door reopens automatically (max 3 retries) |
| **Power failure** | Elevator moves to nearest floor, opens doors, enters MAINTENANCE |
| **All elevators busy** | Request queued; served when elevator becomes available |
| **VIP/express floors** | Configurable fast-lane: dedicated elevator for lobby ↔ penthouse |
| **Fire emergency** | All elevators recalled to ground floor, disabled for public use |
| **Simultaneous same-floor UP/DOWN** | Two separate logical requests — dispatched to (possibly) two elevators |

---

## 8. Class Diagram (Summary)

```
Building
  ├── floors[]: Floor
  │     └── callButtons: Map<Direction, Button>
  ├── elevators[]: Elevator
  │     ├── upQueue: TreeSet<Integer>
  │     ├── downQueue: TreeSet<Integer>
  │     ├── state: ElevatorState (enum)
  │     ├── direction: Direction (enum)
  │     ├── Door
  │     └── WeightSensor
  └── controller: ElevatorController
        ├── strategy: SchedulingStrategy (interface)
        │     ├── SCANStrategy
        │     ├── LOOKStrategy
        │     ├── NearestCarStrategy
        │     └── ZoneBasedStrategy
        └── requestQueue: ConcurrentLinkedQueue<Request>

Request (abstract)
  ├── ExternalRequest (floor, direction)
  └── InternalRequest (elevatorId, floor)
```

---

## 9. Scalability Considerations

| Dimension | Strategy |
|---|---|
| **Many elevators (32+)** | Controller uses thread pool; each elevator runs in its own thread |
| **High-rise buildings (200 floors)** | Zone-based dispatch with elevator banks |
| **Multiple buildings** | Separate controller instance per building; no shared state |
| **Smart dispatch (ML)** | Replace SchedulingStrategy with an ML model predicting demand patterns |
| **IoT integration** | Sensors publish to MQTT broker → Controller subscribes to events |

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **SCAN as default scheduler** | Prevents starvation; fair | Slightly higher avg wait vs SSTF |
| **TreeSet for destination queue** | O(log n) insert, O(1) next | More memory than simple array |
| **Strategy Pattern for scheduling** | Pluggable algorithms | Extra abstraction layer |
| **Per-elevator ReentrantLock** | Fine-grained locking | Better concurrency vs single global lock |
| **State machine for elevator** | Explicit states + transitions | Safety-critical; easy to audit transitions |
| **Separate up/down queues** | Natural SCAN implementation | Slight complexity vs single sorted queue |

---

## 11. Future Enhancements
- **Predictive dispatch** — ML model learns traffic patterns (morning peak = lobby heavy) and pre-positions elevators.
- **Destination Dispatch** — User selects destination floor at the floor panel → system groups users going to same floor into one car (reduces stops).
- **Mobile app integration** — Call elevator from smartphone before arriving at the floor.
- **Energy optimization** — Park idle elevators at high-demand floors during peak hours.
- **Accessibility** — Extended door hold times for specific user profiles (wheelchair users).

---

*Document prepared for SDE 3 system design interviews. Focus areas: OOP design patterns (Strategy, State Machine), concurrency control, scheduling algorithms, and extensibility.*
