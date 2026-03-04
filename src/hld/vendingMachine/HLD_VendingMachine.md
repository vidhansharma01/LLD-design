# 🥤 High-Level Design (HLD) — Vending Machine System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** State Machine Design, OOP Patterns, Concurrency, Payment Handling, Inventory Management

---

## 1. Requirements

### 1.1 Functional Requirements
- Machine holds **multiple product slots**, each with a product type and quantity.
- Users can **select a product** and see its price.
- Users can **insert money** (coins / notes / card / UPI).
- Machine **dispenses the product** and returns **change** if overpaid.
- Machine shows **"Out of Stock"** for empty slots.
- Admin can **restock** products and **collect cash**.
- Machine handles **cancellation** — refunds inserted money before purchase.
- Display shows current **balance inserted** and **available products**.
- Support **multiple payment modes**: coins, notes, card, UPI (extensible).
- Machine is **fault-tolerant** — if dispense fails after payment, refund is issued.

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Reliability** | No money lost — payment and dispense are atomic (or refunded) |
| **Concurrency** | Single-user at a time per machine (physical constraint); multi-machine managed centrally |
| **Extensibility** | Easy to add new payment modes or product types |
| **Maintainability** | Admin operations: restock, cash collect, maintenance mode |
| **Latency** | Product dispense < 3 sec after payment confirmed |

### 1.3 Assumptions
- One transaction at a time per physical machine.
- Change is dispensed only in coins (fixed denominations available in machine).
- Card/UPI payments are handled via external payment gateway.

---

## 2. Core Entities & Object Model

```
VendingMachine
  ├── slots[]: ProductSlot[]
  │     ├── slotId
  │     ├── product: Product
  │     │     ├── productId
  │     │     ├── name
  │     │     ├── price: double
  │     │     └── category: SNACK | BEVERAGE | CANDY
  │     └── quantity: int
  ├── cashRegister: CashRegister
  │     ├── coinInventory: Map<Coin, Integer>
  │     └── totalCash: double
  ├── state: VendingMachineState
  ├── currentBalance: double        (money inserted in current session)
  └── selectedSlot: ProductSlot

Payment (abstract)
  ├── CashPayment (coins + notes)
  ├── CardPayment (tap/swipe via gateway)
  └── UPIPayment  (QR code scan)
```

---

## 3. State Machine Design

**The heart of the interview.** Every vending machine operation is a state transition.

```
                ┌──────────────────────────────┐
                │           IDLE               │ ◀─────────────────────┐
                │  (waiting for selection)     │                       │
                └──────────────┬───────────────┘                       │
                               │ selectProduct()                       │
                               ▼                                       │
                ┌──────────────────────────────┐                       │
                │       PRODUCT_SELECTED       │                       │
                │  (product chosen, showing    │                       │
                │   price, awaiting payment)   │                       │
                └──────┬───────────────────────┘                       │
                       │ insertMoney() / tapCard()                     │
                       ▼                                               │
                ┌──────────────────────────────┐                       │
                │       PAYMENT_PENDING        │ ─── cancel() ────────▶│
                │  (money inserted, awaiting   │   (refund & return)   │
                │   sufficient balance)        │                       │
                └──────┬───────────────────────┘                       │
                       │ balance >= price                              │
                       ▼                                               │
                ┌──────────────────────────────┐                       │
                │       DISPENSING             │ ─── dispense         │
                │  (payment complete,          │     failed ──────────▶│
                │   dispensing product)        │   (refund full amt)   │
                └──────┬───────────────────────┘                       │
                       │ dispense success                              │
                       ▼                                               │
                ┌──────────────────────────────┐                       │
                │       RETURNING_CHANGE       │ ─── give change ─────▶│
                │  (dispense done, returning   │
                │   excess coins)              │
                └──────────────────────────────┘

Special transitions (from ANY state):
  → MAINTENANCE  (admin puts machine in service mode)
  → OUT_OF_STOCK (all slots empty)
```

### State Transition Table

| Current State | Event | Next State | Action |
|---|---|---|---|
| IDLE | selectProduct(slotId) | PRODUCT_SELECTED | Show price, check stock |
| IDLE | selectProduct(OOS) | IDLE | Show "Out of Stock" |
| PRODUCT_SELECTED | insertMoney(amount) | PAYMENT_PENDING | Update balance display |
| PAYMENT_PENDING | insertMoney(amount) | PAYMENT_PENDING / DISPENSING | Accumulate balance |
| PAYMENT_PENDING | cancel() | IDLE | Refund inserted money |
| PAYMENT_PENDING | balance >= price | DISPENSING | Trigger dispense motor |
| DISPENSING | dispenseSuccess() | RETURNING_CHANGE | Calculate & return change |
| DISPENSING | dispenseFailed() | IDLE | Full refund issued |
| RETURNING_CHANGE | changeReturned() | IDLE | Transaction complete |
| ANY | adminMaintenance() | MAINTENANCE | Lock user interactions |
| MAINTENANCE | adminRestore() | IDLE | Resume normal operation |

---

## 4. High-Level Architecture

### 4.1 Single Machine View

```
 ┌───────────────────────────────────────────────────────┐
 │                    Vending Machine                    │
 │                                                       │
 │  ┌──────────┐    ┌────────────────┐  ┌────────────┐  │
 │  │  Display │    │ Product Slots  │  │  Keypad /  │  │
 │  │  Panel   │    │ (inventory)    │  │  Touchscreen│ │
 │  └────┬─────┘    └───────┬────────┘  └─────┬──────┘  │
 │       │                  │                 │         │
 │       └──────────────────▼─────────────────┘         │
 │                          │                           │
 │               ┌──────────▼──────────┐                │
 │               │  Vending Machine    │                │
 │               │  Controller         │                │
 │               │  (State Machine)    │                │
 │               └──────────┬──────────┘                │
 │                          │                           │
 │        ┌─────────────────┼──────────────┐            │
 │        ▼                 ▼              ▼            │
 │  ┌──────────┐    ┌──────────────┐  ┌──────────┐     │
 │  │  Payment │    │  Dispense    │  │   Cash   │     │
 │  │  Handler │    │  Motor       │  │  Register│     │
 │  └──────────┘    └──────────────┘  └──────────┘     │
 └───────────────────────────────────────────────────────┘
                           │ (telemetry, restocking alerts)
                           ▼
              ┌────────────────────────┐
              │   Central Management   │
              │   System (Cloud)       │
              │   - Inventory monitor  │
              │   - Sales analytics    │
              │   - Remote config      │
              └────────────────────────┘
```

### 4.2 Fleet Management (Multi-Machine / Cloud)

```
Vending Machine (IoT) ──▶ MQTT Broker ──▶ Central Backend
                                                  │
                                    ┌─────────────┼───────────────┐
                                    ▼             ▼               ▼
                              Inventory DB    Analytics      Alert Service
                              (stock levels)  (Kafka +       (low stock,
                                              Redshift)       maintenance)
```

---

## 5. Core Components

### 5.1 VendingMachineController

```java
class VendingMachineController {
    private VendingMachineState currentState;
    private List<ProductSlot>   slots;
    private CashRegister        cashRegister;
    private double              currentBalance;
    private ProductSlot         selectedSlot;
    private PaymentHandler      paymentHandler;  // pluggable

    void selectProduct(String slotId) {
        currentState.selectProduct(this, slotId);
    }
    void insertMoney(double amount) {
        currentState.insertMoney(this, amount);
    }
    void processPayment(PaymentMode mode) {
        currentState.processPayment(this, mode);
    }
    void cancel() {
        currentState.cancel(this);
    }
    void dispenseProduct() {
        // trigger motor; if fail → refund
    }
    void returnChange() {
        cashRegister.returnChange(currentBalance - selectedSlot.getPrice());
    }
    void setState(VendingMachineState state) {
        this.currentState = state;
    }
}
```

### 5.2 State Interface (State Pattern)

```java
interface VendingMachineState {
    void selectProduct(VendingMachineController ctx, String slotId);
    void insertMoney(VendingMachineController ctx, double amount);
    void processPayment(VendingMachineController ctx, PaymentMode mode);
    void cancel(VendingMachineController ctx);
    void dispense(VendingMachineController ctx);
}

class IdleState implements VendingMachineState {
    public void selectProduct(VendingMachineController ctx, String slotId) {
        ProductSlot slot = ctx.getSlot(slotId);
        if (slot.isEmpty()) {
            ctx.display("Out of Stock");
            return;
        }
        ctx.setSelectedSlot(slot);
        ctx.display("Price: " + slot.getPrice());
        ctx.setState(new ProductSelectedState());
    }
    // other methods throw IllegalStateException
}

class PaymentPendingState implements VendingMachineState {
    public void insertMoney(VendingMachineController ctx, double amount) {
        ctx.addBalance(amount);
        if (ctx.getBalance() >= ctx.getSelectedSlot().getPrice()) {
            ctx.setState(new DispensingState());
            ctx.dispenseProduct();
        }
    }
    public void cancel(VendingMachineController ctx) {
        ctx.refundBalance();   // return all inserted money
        ctx.setState(new IdleState());
    }
}

class DispensingState implements VendingMachineState {
    public void dispense(VendingMachineController ctx) {
        boolean success = ctx.triggerMotor(ctx.getSelectedSlot());
        if (success) {
            ctx.setState(new ReturningChangeState());
            ctx.returnChange();
        } else {
            ctx.refundBalance();  // fault tolerance: full refund
            ctx.setState(new IdleState());
        }
    }
}
```

### 5.3 Payment Handler (Strategy Pattern)

```java
interface PaymentHandler {
    PaymentResult processPayment(double amount, PaymentMode mode);
    void refund(double amount);
}

class CashPaymentHandler implements PaymentHandler {
    // Validates coins/notes, updates cash register
}

class CardPaymentHandler implements PaymentHandler {
    // Calls external payment gateway API
    // On success → return PaymentResult.SUCCESS
    // On failure → return PaymentResult.FAILED (no charge)
}

class UPIPaymentHandler implements PaymentHandler {
    // Generate QR code, poll for payment confirmation
    // Timeout after 60 sec → cancel
}
```

### 5.4 Cash Register & Change Calculation

```java
class CashRegister {
    Map<Coin, Integer> coinInventory;  // QUARTER=10, DIME=20, NICKEL=15, PENNY=50

    List<Coin> calculateChange(double changeAmount) {
        // Greedy algorithm: largest denomination first
        List<Coin> change = new ArrayList<>();
        for (Coin coin : Coin.values().sortedDescending()) {
            while (changeAmount >= coin.value && coinInventory.get(coin) > 0) {
                change.add(coin);
                changeAmount -= coin.value;
                coinInventory.merge(coin, -1, Integer::sum);
            }
        }
        if (changeAmount > 0) throw new InsufficientChangeException();
        return change;
    }
}
```

**Edge case:** Machine can't make exact change → must handle gracefully:
- Option A: Reject overpayment (prompt exact amount).
- Option B: Refund entire amount and cancel transaction.

### 5.5 Inventory Management

```java
class ProductSlot {
    String      slotId;
    Product     product;
    int         quantity;
    int         maxCapacity;

    boolean isEmpty()         { return quantity == 0; }
    boolean isLow()           { return quantity <= 2; }  // low stock alert

    void dispenseOne() {
        if (isEmpty()) throw new OutOfStockException();
        quantity--;
    }

    void restock(int count) {
        if (quantity + count > maxCapacity) throw new OverstockException();
        quantity += count;
    }
}
```

---

## 6. Design Patterns Used

| Pattern | Applied To | Purpose |
|---|---|---|
| **State Pattern** | VendingMachineState | Encapsulates state-specific behavior; avoids giant if-else |
| **Strategy Pattern** | PaymentHandler | Swap payment modes without changing core logic |
| **Factory Pattern** | PaymentHandlerFactory | Create payment handler based on selected mode |
| **Singleton** | VendingMachineController | One controller per physical machine |
| **Observer Pattern** | InventoryMonitor | Alert central system on low stock / empty slots |

---

## 7. Edge Cases & Fault Tolerance

| Scenario | Handling |
|---|---|
| **Product jams / motor failure** | Detect via sensor; refund full amount; set slot to MAINTENANCE |
| **Insufficient change coins** | Reject overpayment; prompt exact amount or card payment |
| **Power failure during dispense** | On restart: check last transaction log; refund if product not dispensed |
| **Card decline after product dispensed** | Cannot happen — card charged before dispense triggered |
| **UPI timeout** | 60-second timeout → cancel transaction, no charge |
| **All slots empty** | Transition to OUT_OF_STOCK state; disable product selection |
| **Admin restocks during transaction** | Admin operations only allowed in MAINTENANCE state |
| **Double payment (card + cash)** | Only one payment mode per transaction; second attempt rejected |

---

## 8. Class Diagram (Summary)

```
VendingMachineController
  ├── state: VendingMachineState (interface)
  │     ├── IdleState
  │     ├── ProductSelectedState
  │     ├── PaymentPendingState
  │     ├── DispensingState
  │     ├── ReturningChangeState
  │     ├── MaintenanceState
  │     └── OutOfStockState
  ├── slots[]: ProductSlot ──▶ Product
  ├── cashRegister: CashRegister ──▶ Coin (enum)
  └── paymentHandler: PaymentHandler (interface)
        ├── CashPaymentHandler
        ├── CardPaymentHandler
        └── UPIPaymentHandler

PaymentHandlerFactory ──▶ creates PaymentHandler per mode
InventoryMonitor ──▶ observes ProductSlot (Observer)
```

---

## 9. Multi-Machine Fleet (Cloud Architecture)

For managing 10,000+ vending machines across a city/country:

```
Each Machine (IoT)
  - Publishes telemetry every 60s: { machineId, slotInventory[], cashLevel, state }
  - Subscribes to config updates (price changes, new products)
  - Protocol: MQTT (lightweight for IoT)

Central Backend:
  - Inventory DB: real-time stock levels per machine per slot
  - Alert Service: low-stock → dispatch restock team
  - Analytics: sales by machine, by product, by time of day
  - Remote config: price changes pushed to machines
  - Remote diagnostics: motor status, coin sensor health
```

---

## 10. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **State Pattern** | One class per state | Clean transitions; more classes vs giant switch-case |
| **Strategy for payment** | PaymentHandler interface | Easy to add new modes; slight abstraction overhead |
| **Greedy change algorithm** | Largest coin first | Simple; may fail if coin inventory unbalanced |
| **Cash before dispense (card)** | Charge card BEFORE triggering motor | No free products; handles motor failure with refund |
| **Single transaction at a time** | Physical machine constraint | No concurrency needed per machine |
| **Transactional log** | Persist last transaction state | Power failure recovery; audit trail |

---

## 11. Future Enhancements
- **Remote price updates** — central system pushes new prices to machines without restock visit.
- **Touchscreen UX** — product images, nutritional info, recommendations.
- **Machine learning for restocking** — predict when each slot will run out based on historical sales.
- **Loyalty / rewards integration** — earn points per purchase via app-linked QR scan.
- **Dynamic pricing** — surge pricing during peak hours for premium locations.

---

*Document prepared for SDE 3 system design interviews. Focus areas: State Machine pattern, Strategy pattern for payments, fault tolerance (motor failure + refund), change calculation, and IoT fleet management.*
