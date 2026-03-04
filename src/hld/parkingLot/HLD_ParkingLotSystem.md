# 🅿️ High-Level Design (HLD) — Parking Lot System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** OOP Design, Concurrency, Pricing Models, Scalability, Real-time Availability

---

## 1. Requirements

### 1.1 Functional Requirements
- Support **multiple parking lots**, each with **multiple floors** and **multiple spot types**: Compact, Large, Handicapped, Motorcycle, EV.
- Users can **enter** the parking lot — system assigns the **nearest available spot**.
- Generate a **ticket** on entry (vehicle type, spot assigned, entry time).
- Users **pay at exit** — fee calculated based on duration + pricing model.
- Support **multiple payment modes**: Cash, Card, UPI, Monthly Pass.
- Display **real-time availability** on entrance panels and app.
- Support **pre-booking** a spot in advance.
- Track **EV charging slots** — manage charging session + billing.
- Support **monthly pass** holders — reserved spots, flat fee.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Availability** | 99.99% — gate must always work |
| **Concurrency** | Handle simultaneous entry/exit at multiple gates without double-assigning spots |
| **Latency** | Spot assignment < 200 ms; payment < 1 sec |
| **Scalability** | 1000s of parking lots, millions of spots |
| **Consistency** | Strong consistency for spot assignment (no double-booking) |

### 1.3 Out of Scope
- Navigation inside the parking lot (GPS guidance to spot)
- License plate recognition integration (future enhancement)

---

## 2. Core Entities & Object Model

```
Building / ParkingLot
  ├── ParkingFloor[]
  │     └── ParkingSpot[]
  │           ├── type: SpotType (COMPACT, LARGE, HANDICAPPED, MOTORCYCLE, EV)
  │           ├── status: SpotStatus (AVAILABLE, OCCUPIED, RESERVED, MAINTENANCE)
  │           └── spotNumber: String
  ├── EntranceGate[]
  ├── ExitGate[]
  └── ParkingAttendant / Admin

ParkingTicket
  ├── ticketId
  ├── vehicleNumber
  ├── vehicleType
  ├── spotAssigned: ParkingSpot
  ├── entryTime: Timestamp
  └── exitTime: Timestamp

Payment
  ├── paymentId
  ├── ticketId
  ├── amount
  ├── mode: PaymentMode (CASH, CARD, UPI, MONTHLY_PASS)
  └── status: PENDING | SUCCESS | FAILED

Vehicle
  ├── vehicleNumber
  └── type: VehicleType (CAR, TRUCK, MOTORCYCLE, EV)
```

---

## 3. High-Level Architecture

```
         Mobile App / Web                    Physical Gate Panel
              │                                      │
              ▼                                      ▼
       ┌──────────────┐                   ┌──────────────────┐
       │  API Gateway │                   │  Gate Controller │
       │  (Auth, RL)  │                   │  (IoT Embedded)  │
       └──────┬───────┘                   └────────┬─────────┘
              │                                    │
              └───────────────┬────────────────────┘
                              ▼
                  ┌─────────────────────┐
                  │  Parking Lot Service │
                  │  (Core Logic)        │
                  └────────┬────────────┘
                           │
         ┌─────────────────┼──────────────────┐
         ▼                 ▼                  ▼
  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐
  │  Spot       │  │  Ticket      │  │  Payment     │
  │  Service    │  │  Service     │  │  Service     │
  └──────┬──────┘  └──────┬───────┘  └──────┬───────┘
         │                │                  │
         ▼                ▼                  ▼
  ┌─────────────────────────────────────────────────┐
  │              Primary DB (PostgreSQL)             │
  │  parking_spots | tickets | payments | passes     │
  └─────────────────────────────────────────────────┘
         │
  ┌──────▼──────┐    ┌──────────────┐
  │  Redis      │    │  Kafka       │
  │  (spot      │    │  (events:    │
  │  availability    │  entry/exit/ │
  │  cache)     │    │  payment)    │
  └─────────────┘    └──────────────┘
         │
  ┌──────▼──────┐
  │  Display    │
  │  Service    │  (real-time availability boards)
  └─────────────┘
```

---

## 4. Core Components

### 4.1 Spot Service — Assignment Logic

**Nearest available spot** assignment strategy:

```java
interface SpotAssignmentStrategy {
    ParkingSpot assign(List<ParkingFloor> floors, VehicleType type);
}

class NearestSpotStrategy implements SpotAssignmentStrategy {
    // BFS / Floor-priority: search floor 1 → 2 → 3
    // Within a floor: spot closest to entrance (by spot number)
    public ParkingSpot assign(List<ParkingFloor> floors, VehicleType type) {
        for (ParkingFloor floor : floors) {
            Optional<ParkingSpot> spot = floor.getAvailableSpots(type).stream()
                .filter(s -> s.getStatus() == AVAILABLE)
                .findFirst();
            if (spot.isPresent()) return spot.get();
        }
        throw new NoSpotAvailableException();
    }
}
```

**Vehicle → Spot type mapping:**

| Vehicle Type   | Eligible Spot Types                    |
|----------------|----------------------------------------|
| Motorcycle     | Motorcycle, Compact                    |
| Car            | Compact, Large                         |
| Large/Truck    | Large                                  |
| Handicapped    | Handicapped (priority), Compact        |
| EV Car         | EV (preferred), Compact                |

### 4.2 Concurrency — Double-Booking Prevention

Spot assignment is the **most critical concurrency concern**.

**Strategy: Optimistic Locking + DB-level constraint**

```sql
-- Spot table with version column for optimistic locking
UPDATE parking_spots
SET status = 'OCCUPIED', ticket_id = ?, version = version + 1
WHERE spot_id = ? AND status = 'AVAILABLE' AND version = ?;

-- If 0 rows updated → another transaction grabbed it → retry with next spot
```

**In-memory with Redis (for fast assignment):**
```
Redis SET NX (atomic):
  Key:   spot:{lotId}:{spotId}
  Value: ticketId
  cmd:   SET spot:L1:S42 T789 NX EX 3600

If SET NX fails → spot already taken → try next spot
```

- **Redis** for fast real-time availability; **PostgreSQL** as source of truth.
- On Redis failure → fall back to DB with row-level lock.

### 4.3 Ticket Service

```
Entry Flow:
  Vehicle arrives at gate
       │
  Scan vehicle (manual / ANPR)
       │
  SpotService.assign(vehicleType) → ParkingSpot
       │
  Create ParkingTicket {
      ticketId = UUID,
      vehicleNumber,
      vehicleType,
      spotId,
      entryTime = NOW()
  }
       │
  Mark spot OCCUPIED in Redis + DB
       │
  Issue physical/QR ticket to driver
       │
  Barrier opens

Exit Flow:
  Vehicle arrives at exit gate
       │
  Scan ticket / QR code
       │
  Fetch ticket → compute duration
       │
  PaymentService.calculateFee(ticket)
       │
  Driver pays → PaymentService.process()
       │
  Mark spot AVAILABLE in Redis + DB
       │
  Barrier opens
       │
  Publish SpotFreed event → Kafka
```

### 4.4 Pricing Service (Strategy Pattern)

Multiple pricing models supported via Strategy Pattern:

```java
interface PricingStrategy {
    double calculateFee(ParkingTicket ticket);
}

class HourlyPricing implements PricingStrategy {
    // First hour: flat rate, subsequent hours: rate/hr
    // e.g., ₹50 first hour, ₹30/hr after
}

class DailyCapPricing implements PricingStrategy {
    // Max cap per day (e.g., ₹200/day max regardless of hours)
}

class MonthlyPassPricing implements PricingStrategy {
    // Monthly pass holder → fee = 0
}

class EVChargingPricing implements PricingStrategy {
    // Parking fee + kWh consumed × rate
}
```

**Fee Calculation Example:**
```
Duration = exitTime - entryTime
If VehicleType == EV:
    fee = parkingFee(duration) + chargingFee(kWhUsed × ratePerKwh)
Else:
    fee = hourlyRate × ceil(duration.hours)
    cap = min(fee, dailyCap)
```

### 4.5 Parking Spot Data Model (DB Schema)

```sql
-- Parking Spots
CREATE TABLE parking_spots (
    spot_id       UUID PRIMARY KEY,
    lot_id        UUID NOT NULL,
    floor_number  INT,
    spot_number   VARCHAR(10),
    spot_type     ENUM('COMPACT','LARGE','HANDICAPPED','MOTORCYCLE','EV'),
    status        ENUM('AVAILABLE','OCCUPIED','RESERVED','MAINTENANCE') DEFAULT 'AVAILABLE',
    ticket_id     UUID REFERENCES tickets(ticket_id),
    version       INT DEFAULT 0    -- for optimistic locking
);

-- Tickets
CREATE TABLE tickets (
    ticket_id     UUID PRIMARY KEY,
    vehicle_no    VARCHAR(20),
    vehicle_type  ENUM('CAR','TRUCK','MOTORCYCLE','EV'),
    spot_id       UUID REFERENCES parking_spots(spot_id),
    lot_id        UUID,
    entry_time    TIMESTAMP NOT NULL,
    exit_time     TIMESTAMP,
    status        ENUM('ACTIVE','PAID','LOST')
);

-- Payments
CREATE TABLE payments (
    payment_id    UUID PRIMARY KEY,
    ticket_id     UUID REFERENCES tickets(ticket_id),
    amount        DECIMAL(10,2),
    mode          ENUM('CASH','CARD','UPI','MONTHLY_PASS'),
    status        ENUM('PENDING','SUCCESS','FAILED'),
    paid_at       TIMESTAMP
);

-- Monthly Passes
CREATE TABLE monthly_passes (
    pass_id       UUID PRIMARY KEY,
    user_id       UUID,
    vehicle_no    VARCHAR(20),
    lot_id        UUID,
    spot_id       UUID,       -- reserved spot (nullable for flexible pass)
    valid_from    DATE,
    valid_until   DATE,
    status        ENUM('ACTIVE','EXPIRED')
);
```

### 4.6 Real-Time Availability — Redis

```
Redis Data Structures:

1. Available spot count per type (fast display boards):
   Key:  avail:{lotId}:{floor}:{spotType}
   Type: String (counter)
   Ops:  INCR on exit, DECR on entry

2. Spot occupancy (fast assignment check):
   Key:  spot:{lotId}:{spotId}
   Type: String (ticketId if occupied, absent if available)
   Ops:  SET NX on entry, DEL on exit

3. Pre-booked spots:
   Key:  booking:{lotId}:{spotId}:{date}
   Type: String (bookingId)
   TTL:  until booking window expires
```

### 4.7 Display Service

- Subscribes to Kafka topics: `spot.occupied`, `spot.freed`.
- Updates **floor-wise LED boards** (available count per type).
- Updates **entrance panels** (total available count).
- Updates **mobile app** via WebSocket push.

---

## 5. Pre-Booking Flow

```
User books a spot via app (1 hour in advance)
        │
        ▼
Check availability in SpotService
        │
        ▼
Reserve spot in DB (status = RESERVED)
Set Redis booking key with TTL = booking_start_time
        │
        ▼
Generate booking confirmation + QR code
        │
        ▼
User arrives → scans QR → system recognizes booking
Spot status: RESERVED → OCCUPIED
Ticket created with entry_time
        │
        ▼
If user doesn't arrive within 15 min of booking:
  Booking expires → spot released → status = AVAILABLE
```

---

## 6. EV Charging Flow

```
EV arrives → assigned to EV spot (has charger)
Entry ticket created
        │
        ▼
Charging session starts (ChargingService.startSession(spotId))
kWh meter starts tracking energy consumed
        │
        ▼
On exit:
  ChargingService.endSession() → returns kWhConsumed
  Fee = parkingFee + (kWhConsumed × ratePerKwh)
```

---

## 7. Edge Cases

| Scenario | Handling |
|---|---|
| **Parking lot full** | Return `LOT_FULL` at entrance; display boards show 0 available |
| **Lost ticket** | Charge maximum daily rate; manual verification by attendant |
| **Payment failure** | Barrier stays closed; retry or alternate payment |
| **Spot sensor malfunction** | Admin marks spot as `MAINTENANCE`; excluded from assignment |
| **Monthly pass vehicle in wrong lot** | Pass validated against lot_id; deny entry |
| **Multiple vehicles same ticket** | Ticket status checked — `ACTIVE` only once; prevent replay |
| **Redis down** | Fall back to DB-only assignment with row-level locking |

---

## 8. Class Diagram (Summary)

```
ParkingLot
  ├── ParkingFloor[] ──▶ ParkingSpot[]
  │                         ├── SpotType (enum)
  │                         └── SpotStatus (enum)
  ├── EntranceGate ──▶ ParkingAttendant
  ├── ExitGate ──▶ PaymentPanel
  └── DisplayBoard

ParkingLotController
  ├── SpotAssignmentStrategy (interface)
  │     ├── NearestSpotStrategy
  │     └── ZoneBasedStrategy
  ├── PricingStrategy (interface)
  │     ├── HourlyPricing
  │     ├── DailyCapPricing
  │     ├── MonthlyPassPricing
  │     └── EVChargingPricing
  └── TicketManager

ParkingTicket ──▶ ParkingSpot, Vehicle
Payment ──▶ ParkingTicket
MonthlyPass ──▶ User, ParkingSpot
```

---

## 9. Scalability

| Dimension | Strategy |
|---|---|
| **Multiple lots** | Each lot is an independent service instance; shared DB with `lot_id` partitioning |
| **High entry/exit rate** | Horizontal scale of ParkingLotService; Redis handles spot assignment under load |
| **Spot availability reads** | Redis counters serve all display board reads; zero DB hits |
| **Reporting / Analytics** | Kafka events → Data Warehouse (Redshift/BigQuery) for occupancy reports |
| **Global chain of lots** | Multi-region deployment; each region manages its own lots |

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Spot assignment concurrency** | Redis SET NX + DB optimistic locking | Fast + safe; retry logic on contention |
| **DB choice** | PostgreSQL (relational) | Structured data with joins; simpler than NoSQL for this use case |
| **Redis for availability** | Separate counters per floor/type | Sub-ms reads for display boards; eventual consistency acceptable |
| **Strategy Pattern for pricing** | Pluggable pricing models | Easy to add new pricing rules without code changes |
| **Kafka for events** | Async propagation to displays/analytics | Decoupled; display updates can lag slightly |
| **Optimistic locking** | Version column in DB | Better throughput than pessimistic lock; handles low-contention well |

---

## 11. Future Enhancements
- **ANPR (License Plate Recognition)** — automatic vehicle detection at gates; no physical ticket.
- **Dynamic Pricing** — surge pricing during peak hours or events.
- **Navigation** — in-app map guides driver to assigned spot.
- **Loyalty Program** — points per visit, discounts for frequent parkers.
- **Smart Sensors** — ultrasonic/IR sensors per spot; automatically update availability without gate scans.

---

*Document prepared for SDE 3 system design interviews. Focus areas: OOP patterns (Strategy), concurrency control, optimistic locking, real-time availability, and extensible pricing.*
