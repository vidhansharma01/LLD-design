# High-Level Design: PhonePe / UPI Payment System (SDE3 Interview)

## 0. How To Use This Document In Interview
Present in this order:
1. Clarify scope: P2P transfers, merchant payments, bill payments, UPI rails.
2. Establish scale and non-functional requirements (latency, availability, consistency).
3. Show API surface and core data model.
4. Walk through the end-to-end UPI payment flow — the most critical deep-dive.
5. Cover idempotency, failure handling, fraud detection, reconciliation, and security.

If the interviewer asks to deep-dive, jump to: UPI flow (Section 8.1), idempotency (Section 9), or fraud detection (Section 12).

---

## 1. Problem Statement And Scope

Design a UPI-based digital payments platform like PhonePe that supports:
- **P2P (Peer-to-Peer)**: Send money to any UPI ID or mobile number.
- **P2M (Peer-to-Merchant)**: Pay at shops via QR code scan or UPI ID.
- **Collect requests**: Merchant/user raises a collect request; payer approves.
- **Bill payments**: Electricity, gas, DTH, credit card bills via BBPS.
- **Recharge**: Mobile, DTH top-up.
- **Bank account linking**: Add/remove bank accounts, set default UPI PIN.
- **Transaction history**: Full ledger of all transactions.
- **Notifications**: Real-time SMS, push, in-app notifications on every transaction.

### 1.1 In Scope
- UPI payment flow: intent, collect, and QR (all three UPI payment modes).
- Bank account registration and VPA (Virtual Payment Address) management.
- Transaction ledger and history.
- Fraud detection and risk scoring.
- Reconciliation and settlement.
- Notifications and alerts.
- Regulatory compliance (RBI, NPCI guidelines).

### 1.2 Out Of Scope
- Core NPCI (National Payments Corporation of India) switch internals.
- GST computation for merchant settlements.
- Physical PoS terminal integration.
- Lending/BNPL products in depth.

---

## 2. UPI Architecture Primer (Essential Background)

Understanding the **UPI ecosystem** is mandatory before designing any UPI app.

```
+----------+       +----------+        +------+        +----------+
| Payer    |       | Payer's  |        | NPCI |        | Payee's  |
| App      | <---> | PSP Bank | <----> | UPI  | <----> | PSP Bank |
|(PhonePe) |       |(PhonePe  |        |Switch|        |(Paytm /  |
|          |       | Bank /   |        |      |        | HDFC /   |
|          |       | Yes Bank)|        |      |        | ICICI)   |
+----------+       +----------+        +------+        +----------+
                        |                                   |
                   +----+----+                        +-----+-----+
                   | Payer's |                        | Payee's   |
                   | Bank    |                        | Bank      |
                   |(HDFC,   |                        |(SBI, Axis)|
                   | SBI...) |                        |           |
                   +---------+                        +-----------+
```

**Key actors:**
- **PSP (Payment Service Provider)**: PhonePe is an app built on top of a PSP (Yes Bank / ICICI Bank as PhonePe's partner bank). The PSP holds the UPI license.
- **NPCI UPI Switch**: The regulatory backbone. Routes messages between PSP banks. PhonePe does NOT directly connect to NPCI — the PSP bank does.
- **Payer's Bank**: Where money actually debits from.
- **Payee's Bank**: Where money credits to.

**UPI Message Protocol**: Based on ISO 20022 XML messages over HTTPS. Specific message types: `ReqPay`, `RespPay`, `ReqBalEnq`, `RespBalEnq`, `ReqRegMob`, etc.

**VPA (Virtual Payment Address)**: `username@phonepe`, `mobile@ybl`, `user@oksbi`. Resolves to a bank account. PhonePe manages VPA registration in its systems and NPCI's mapper.

---

## 3. Requirements

### 3.1 Functional Requirements
1. User registers phone number, links bank account(s), sets UPI PIN.
2. User creates a VPA (e.g., `john@ybl`).
3. User sends money to another VPA or mobile number (P2P pay).
4. User pays a merchant QR (P2M — static or dynamic QR).
5. User approves or declines collect requests.
6. User pays utility bills (BBPS integration).
7. User views transaction history (last 90 days active, older archived).
8. User gets real-time notifications (push + SMS) for every credit and debit.
9. User can raise a dispute (transaction not received, wrong debit).

### 3.2 Non-Functional Requirements
- **Availability**: 99.99% for payment initiation. 99.999% for transaction ledger reads.
- **Latency**: UPI payment end-to-end (payer app → debit → credit → confirmation): **< 10 seconds (P95)**. NPCI mandates 30s timeout but target P95 is much lower.
- **Throughput**: India UPI processes **12 billion transactions/month** (2024). Peak during festivals: **5,000 TPS (Transactions Per Second)** across the ecosystem. PhonePe's share: ~40% → **2,000 TPS peak**.
- **Consistency**: **Exactly-once money movement** — no double debit, no double credit, ever.
- **Idempotency**: Any network retry must NOT create duplicate transactions.
- **Durability**: Transaction records must never be lost (7-year regulatory retention).
- **Security**: PCI-DSS; encrypted UPI PIN; no plaintext sensitive data in logs.

---

## 4. Back-Of-The-Envelope Capacity Planning

| Parameter | Value |
|---|---|
| PhonePe registered users | 500 million |
| MAU | 250 million |
| DAU | 50 million |
| Transactions/user/day | 3 |
| Peak TPS (PhonePe share) | 2,000 |
| Avg transaction size (₹) | ₹1,500 |
| Transaction record size | 2 KB |
| Notifications per transaction | 2 (debit + credit) |

### 4.1 Transaction Volume
- Daily: 50M * 3 = **150M transactions/day**.
- Average TPS: 150M / 86400 = **1,736 TPS**.
- Peak TPS: **2,000–3,000 TPS** (Diwali, sale seasons, salary day).

### 4.2 Storage
- Transactions: 150M/day * 2 KB = **300 GB/day** → ~110 TB/year (hot storage).
- Archived (cold): 7 years × 110 TB = **770 TB** (compressed cold storage).
- Ledger entries: 2 entries per transaction (debit + credit) → 300M ledger rows/day.
- Notifications: 300M/day push + SMS + in-app.

### 4.3 NPCI Throughput
- Every UPI transaction involves 4–6 NPCI API calls (VPA resolve, debit, credit, confirm).
- At 2,000 TPS: up to **12,000 NPCI API calls/sec** from PhonePe's PSP bank.
- NPCI imposes per-PSP rate limits → critical to stay within allocation.

---

## 5. High-Level API Design

### 5.1 Bank Account Management

```http
# Link bank account via mobile number (UPI registration flow)
POST /v1/accounts/link
{
  "mobile": "9876543210",
  "bank_ifsc": "HDFC0001234"   // selected from discovered bank list
}
→ Triggers OTP-based bank discovery via NPCI RespRegMob flow.

# List linked accounts
GET /v1/accounts
→ [{ account_id, bank_name, masked_account_no, ifsc, is_default, vpa_list[] }]

# Set UPI PIN (first time setup)
POST /v1/accounts/{accountId}/pin/set
{
  "debit_card_last6": "123456",
  "debit_card_expiry": "12/26",
  "new_pin_encrypted": "<RSA encrypted PIN>"   // PIN never in plaintext
}

# Change UPI PIN
POST /v1/accounts/{accountId}/pin/change
{ "old_pin_encrypted": "...", "new_pin_encrypted": "..." }
```

### 5.2 VPA Management

```http
# Check VPA availability
GET /v1/vpa/check?vpa=johndoe@ybl

# Create VPA
POST /v1/vpa
{ "preferred_vpa": "johndoe@ybl" }

# Resolve VPA (lookup name before payment — trust signal)
GET /v1/vpa/resolve?vpa=merchant@paytm
→ { "registered_name": "Merchant XYZ Pvt Ltd", "is_verified_merchant": true }
```

### 5.3 Payment APIs

```http
# Initiate Pay (P2P or P2M — push payment)
POST /v1/payments/pay
{
  "to_vpa": "merchant@ybl",
  "amount": 500.00,
  "currency": "INR",
  "remarks": "Dinner split",
  "from_account_id": "ACC_123",
  "client_ref_no": "CLIENT_UUID_abc123"   // client-generated idempotency key
}
→ { "transaction_id": "TXN_xyz", "status": "pending", "expires_at": "..." }

# Approve collect request (P2M or P2P collect)
POST /v1/payments/collect/{collectId}/approve
{
  "upi_pin_encrypted": "<RSA encrypted PIN>",
  "from_account_id": "ACC_123"
}
→ { "transaction_id": "TXN_abc", "status": "pending" }

# Get transaction status
GET /v1/payments/{transactionId}/status
→ {
    "status": "success" | "failed" | "pending" | "reversed",
    "debit_amount": 500.00,
    "credit_amount": 500.00,
    "rrn": "NPCI_Reference_Number",
    "timestamp": "...",
    "failure_reason": null | "insufficient_funds" | "bank_timeout" | ...
  }

# Transaction history
GET /v1/payments/history?from=2024-01-01&to=2024-03-31&type=debit&page=1
```

### 5.4 Collect Request (Merchant Initiates)

```http
# Merchant raises collect request
POST /v1/payments/collect
{
  "from_vpa": "customer@hdfc",
  "amount": 1200.00,
  "remarks": "Order #ORD_456",
  "merchant_ref": "ORD_456",
  "expiry_minutes": 10
}
→ { "collect_id": "COL_zzz", "deep_link": "upi://collect?pa=merchant@ybl&am=1200&..." }
```

### 5.5 QR Payment

```http
# Parse and validate a QR code before payment
POST /v1/qr/parse
{ "qr_raw": "upi://pay?pa=merchant@oksbi&pn=Coffee+Shop&am=&cu=INR&..." }
→ { "vpa": "merchant@oksbi", "name": "Coffee Shop", "amount": null, "is_verified": true }

# After user enters amount and UPI PIN → same POST /v1/payments/pay flow
```

---

## 6. Data Model

### 6.1 User And Bank Account

```sql
CREATE TABLE users (
  user_id       VARCHAR(64) PRIMARY KEY,
  mobile        VARCHAR(15) UNIQUE NOT NULL,
  name          VARCHAR(256),
  email         VARCHAR(256),
  kyc_status    ENUM('pending','basic','full') DEFAULT 'pending',
  account_status ENUM('active','suspended','blocked') DEFAULT 'active',
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE bank_accounts (
  account_id      VARCHAR(64) PRIMARY KEY,
  user_id         VARCHAR(64) NOT NULL,
  bank_ifsc       VARCHAR(11) NOT NULL,
  bank_name       VARCHAR(128) NOT NULL,
  masked_account_no VARCHAR(20) NOT NULL,   -- last 4 digits only
  account_ref_no  VARCHAR(128) NOT NULL,    -- token from bank/NPCI (NOT actual account no)
  is_default      BOOLEAN DEFAULT FALSE,
  is_active       BOOLEAN DEFAULT TRUE,
  registered_at   TIMESTAMP NOT NULL,
  INDEX (user_id)
);

CREATE TABLE vpas (
  vpa           VARCHAR(128) PRIMARY KEY,   -- e.g., johndoe@ybl
  user_id       VARCHAR(64) NOT NULL,
  account_id    VARCHAR(64) NOT NULL,
  is_default    BOOLEAN DEFAULT FALSE,
  is_active     BOOLEAN DEFAULT TRUE,
  created_at    TIMESTAMP NOT NULL,
  INDEX (user_id)
);
```

**Security note**: Actual bank account numbers are NEVER stored by PhonePe. NPCI provides a reference/token. The PSP bank holds the mapping. This is a regulatory requirement — reduces PCI compliance scope.

### 6.2 Transactions (Core Ledger)

```sql
-- Master transaction record
CREATE TABLE transactions (
  transaction_id      VARCHAR(64) PRIMARY KEY,
  client_ref_no       VARCHAR(128) UNIQUE NOT NULL,  -- idempotency key
  type                ENUM('pay','collect','refund','reversal') NOT NULL,
  status              ENUM('initiated','pending','success','failed',
                           'timeout','reversed','disputed') NOT NULL,
  payer_vpa           VARCHAR(128) NOT NULL,
  payee_vpa           VARCHAR(128) NOT NULL,
  amount              DECIMAL(12,2) NOT NULL,
  currency            CHAR(3) NOT NULL DEFAULT 'INR',
  remarks             VARCHAR(256),

  -- NPCI references
  npci_txn_id         VARCHAR(64),           -- NPCI-assigned transaction ID
  rrn                 VARCHAR(64),           -- Retrieval Reference Number (global unique)

  -- Bank references
  payer_bank_ref      VARCHAR(128),          -- reference from payer's bank
  payee_bank_ref      VARCHAR(128),          -- reference from payee's bank

  -- Timing
  initiated_at        TIMESTAMP NOT NULL,
  updated_at          TIMESTAMP NOT NULL,
  completed_at        TIMESTAMP,

  -- Risk
  risk_score          DECIMAL(4,3),          -- 0.000 to 1.000
  risk_action         ENUM('allow','review','block'),

  failure_reason      VARCHAR(256),

  INDEX (payer_vpa, initiated_at DESC),
  INDEX (payee_vpa, initiated_at DESC),
  INDEX (rrn),
  INDEX (npci_txn_id),
  INDEX (client_ref_no),
  INDEX (status, initiated_at)
);

-- Immutable event log for every state transition
CREATE TABLE transaction_events (
  event_id        VARCHAR(64) PRIMARY KEY,
  transaction_id  VARCHAR(64) NOT NULL,
  event_type      VARCHAR(64) NOT NULL,   -- INITIATED, DEBIT_SENT, DEBIT_SUCCESS, CREDIT_SENT, ...
  event_data      JSONB,
  occurred_at     TIMESTAMP NOT NULL,
  source          VARCHAR(64),            -- 'npci_callback', 'bank_webhook', 'internal_timeout', ...
  INDEX (transaction_id, occurred_at)
);
```

### 6.3 Ledger (Double-Entry Accounting)

```sql
-- Every transaction generates 2 ledger entries (debit + credit)
CREATE TABLE ledger_entries (
  entry_id        VARCHAR(64) PRIMARY KEY,
  transaction_id  VARCHAR(64) NOT NULL,
  account_id      VARCHAR(64) NOT NULL,    -- internal account reference
  entry_type      ENUM('debit','credit') NOT NULL,
  amount          DECIMAL(12,2) NOT NULL,
  balance_after   DECIMAL(12,2),           -- snapshot; used for reconciliation
  posted_at       TIMESTAMP NOT NULL,
  INDEX (account_id, posted_at DESC),
  INDEX (transaction_id)
);
```

**Double-entry ensures bookkeeping integrity**: Every ₹500 debit from Account A must have a corresponding ₹500 credit to Account B. Unbalanced ledger = bug.

### 6.4 Collect Requests

```sql
CREATE TABLE collect_requests (
  collect_id      VARCHAR(64) PRIMARY KEY,
  payee_vpa       VARCHAR(128) NOT NULL,   -- merchant/initiator
  payer_vpa       VARCHAR(128) NOT NULL,   -- person being asked to pay
  amount          DECIMAL(12,2) NOT NULL,
  remarks         VARCHAR(256),
  status          ENUM('pending','approved','declined','expired','cancelled'),
  expires_at      TIMESTAMP NOT NULL,
  transaction_id  VARCHAR(64),             -- populated on approval
  created_at      TIMESTAMP NOT NULL,
  INDEX (payer_vpa, status),
  INDEX (payee_vpa, status)
);
```

### 6.5 Dispute Management

```sql
CREATE TABLE disputes (
  dispute_id      VARCHAR(64) PRIMARY KEY,
  transaction_id  VARCHAR(64) NOT NULL,
  user_id         VARCHAR(64) NOT NULL,
  reason          VARCHAR(512) NOT NULL,
  status          ENUM('raised','under_review','resolved','rejected'),
  resolution      VARCHAR(512),
  raised_at       TIMESTAMP NOT NULL,
  resolved_at     TIMESTAMP,
  INDEX (transaction_id),
  INDEX (user_id, raised_at DESC)
);
```

---

## 7. High-Level Architecture

```text
+----------------------------  Clients  ----------------------------+
| PhonePe Android/iOS App | Merchant SDK | Web Dashboard           |
+---+---------------------+-------+------+--+---------------------+
    |                             |           |
[CDN: Static Assets]         [API Gateway]  [Merchant API Gateway]
                              (Auth, RL,    (API Key Auth, Webhook)
                               Routing)
                                  |
    +-----------------------------+---------------------+
    |          |           |            |               |
    v          v           v            v               v
+-------+ +-------+ +----------+ +----------+    +----------+
|Account| | VPA   | |Payment   | |Txn       |    |Notify    |
|Service| |Service| |Orchestr. | |History   |    |Service   |
|       | |       | |Service   | |Service   |    |          |
+---+---+ +---+---+ +----+-----+ +----+-----+    +----+-----+
    |          |          |            |               |
    v          v          v            v               v
+-------+  +------+  +--------+  +----------+   +----------+
|User DB|  |VPA DB|  |Txn DB  |  |Redis Cache|  |Kafka     |
|(PG)   |  |(PG)  |  |(PG +   |  |           |  |Event Bus |
|       |  |      |  | Redis) |  +-----------+  +----------+
+-------+  +------+  +---+----+
                         |
              +----------+-----------+
              |                      |
              v                      v
     +----------------+    +------------------+
     | NPCI UPI       |    | Fraud & Risk     |
     | Connector      |    | Engine           |
     | (PSP Bank API) |    | (ML + Rules)     |
     +-------+--------+    +------------------+
             |
     +-------+------+
     | PSP Bank     |   (Yes Bank / ICICI — PhonePe's partner)
     | (NPCI switch |
     |  participant)|
     +--------------+
```

---

## 8. Detailed Component Design

### 8.1 Payment Orchestration Service — The Heart Of The System

This is the most interview-critical component. It coordinates the multi-step UPI payment flow while ensuring exactly-once semantics.

#### 8.1.1 UPI Pay Flow (Push Payment — P2P / P2M)

```
User initiates: POST /v1/payments/pay { to_vpa, amount, pin_encrypted, client_ref_no }

Step 1: Validate & Idempotency Check
  → Check client_ref_no in Redis / DB → if exists, return existing transaction.
  → Validate payer's account is active, daily limit not breached.
  → Validate amount > 0, ≤ ₹1,00,000 (UPI daily limit; higher for verified merchants).
  → Check payee VPA exists (call NPCI VPA resolution).

Step 2: Fraud Pre-Check (synchronous, must complete < 200ms)
  → Call Fraud Engine with transaction features.
  → If risk_score > threshold_block: REJECT immediately.
  → If risk_score > threshold_review: flag for post-transaction review.

Step 3: Persist Transaction (status = INITIATED)
  → INSERT transactions (client_ref_no, status=INITIATED, payer_vpa, payee_vpa, amount).
  → This is the idempotency anchor — any future retry finds this record.

Step 4: Send Debit Request to NPCI (via PSP Bank API)
  → Call PSP Bank: ReqPay message (ISO 20022 XML or PSP's REST wrapper).
  → Include: payer_vpa, payee_vpa, amount, npci_txn_id (system-generated), encrypted_pin.
  → Update transaction status = PENDING.
  → Set timeout timer: 30 seconds (NPCI mandate).

Step 5: NPCI Processes
  NPCI resolves payee VPA → identifies payee's PSP bank.
  NPCI sends debit instruction to payer's bank.
  Payer's bank validates PIN, checks balance → debits account.
  NPCI sends credit instruction to payee's bank.
  Payee's bank credits account.
  NPCI returns success/failure response.

Step 6: Receive NPCI Response (async callback / synchronous response)
  → On SUCCESS:
      - Update transaction status = SUCCESS, rrn = NPCI_RRN.
      - Write ledger entries (debit payer, credit payee).
      - Emit TXN_SUCCESS event to Kafka.
  → On FAILURE (bank declined, wrong PIN, insufficient funds):
      - Update transaction status = FAILED, failure_reason = error_code.
      - Emit TXN_FAILED event.
  → On TIMEOUT (no response in 30s):
      - Update transaction status = TIMEOUT.
      - Initiate status check loop (Section 8.1.3).

Step 7: Notification (async, post-response)
  → Kafka consumer → Notification Service.
  → Send push + SMS to payer (debit alert) AND payee (credit alert).
```

#### 8.1.2 Transaction State Machine

```
INITIATED
    │
    ▼
PENDING ──────── NPCI Response ─────────────────────────────────┐
    │                                                            │
    │ Timeout (30s)                                              │
    ▼                                                            │
TIMEOUT                                               SUCCESS / FAILED
    │                                                     │
    │ Status check loop (Section 8.1.3)                   │
    ├────────────────────────────────── RESOLVED ──────────┘
    │
    └── After max retries → TIMEOUT_UNRESOLVED (manual reconciliation)

SUCCESS → (optional complaint) → DISPUTED → REVERSED/REFUNDED
```

#### 8.1.3 Timeout Recovery — The Hardest Part

**Problem**: Network timeout between PhonePe and NPCI/PSP bank. Money may or may not have moved. The system is in an unknown state.

This is a **critical SDE3 talking point** — most candidates miss this.

```
When transaction enters TIMEOUT state:

Phase 1: Async status check loop (ReqTxnStatus)
  → Every 10 seconds, call NPCI/PSP Bank: "What is the status of TXN_ID xyz?"
  → Max 6 retries (1 min total).
  → On SUCCESS response: update DB, post ledger, notify user.
  → On FAILED response: update DB, notify user.
  → On still-pending: continue retrying.

Phase 2: If still unknown after 6 retries
  → Mark transaction as TIMEOUT_UNRESOLVED.
  → Flag for manual reconciliation team.
  → Deduct amount from payer's "in-flight" balance (to prevent double spend from payer's perspective).
  → Notify payer: "Payment in progress, we'll confirm shortly."

Phase 3: Reconciliation (runs daily)
  → Pull settlement file from PSP bank (T+0 file, next-day settlement confirmation).
  → Match every TIMEOUT transaction against settlement → resolve to SUCCESS or FAILED.
  → If resolved as SUCCESS: update DB, post ledger, notify.
  → If resolved as FAILED: release in-flight hold, notify.

Why this matters:
  - Without proper timeout handling → payer's money debited but they see "failed".
  - This is the #1 source of customer complaints in UPI/payment systems.
  - NPCI mandates refund within T+1 business day for failed transactions where debit happened.
```

#### 8.1.4 Collect Flow (Merchant Pull Payment)

```
Merchant initiates collect:
  POST /v1/payments/collect {from_vpa, amount, remarks, expiry=10min}
  → Create collect_request record (status=PENDING).
  → Send collect notification via NPCI to payer's UPI-linked app.

Payer receives collect notification (push notification or in-app):
  → Sees: "Merchant XYZ requests ₹500 for Order #456"
  → User approves: POST /v1/payments/collect/{id}/approve { upi_pin_encrypted }
  → Same flow as push payment from Step 2 onwards.

Payer declines:
  → Update collect status = DECLINED.
  → Notify merchant.

Timer expires without action:
  → Background job: scan collect_requests WHERE expires_at < NOW() AND status = PENDING.
  → Mark EXPIRED, notify merchant.
```

### 8.2 Idempotency Design (Exact-Once Payments)

**The most critical correctness requirement in payments.**

**Problem**: Mobile networks drop connections. If the client retries a payment request, we must NOT create a duplicate transaction.

**Solution: Client-Generated Idempotency Key**

```
Client generates: client_ref_no = UUID v4 (generated once per payment intent, stored locally).
Client includes this in every retry of the same request.

Server-side handling:
  Step 1: Check Redis: GET idempotency:{client_ref_no}
    → Cache HIT with status=PENDING: return "payment in progress".
    → Cache HIT with status=SUCCESS/FAILED: return cached result immediately (no re-processing).
    → Cache MISS: proceed to create new transaction.

  Step 2: Before any NPCI call:
    → INSERT transaction with client_ref_no (has UNIQUE constraint).
    → If INSERT violates unique constraint → another thread/retry already created it.
    → SELECT and return existing transaction (idempotent).

  Step 3: On completion:
    → SET idempotency:{client_ref_no} = {transaction_id, status, result} EX 86400 (24h TTL).

Why both Redis AND DB unique constraint?
  → Redis for speed (sub-millisecond lookup before DB write).
  → DB unique constraint as correctness guarantee if Redis is unavailable.
  → Two-layer protection against duplicate transactions.
```

**Server-to-NPCI idempotency:**
```
Every NPCI API call includes: npci_txn_id (PhonePe-generated UUID).
NPCI uses this as its idempotency key.
If PhonePe sends same npci_txn_id twice → NPCI returns cached result, does NOT process twice.
PSP banks also idempotent on bank_ref_no from PSP.
Result: idempotency enforced at EVERY hop.
```

### 8.3 VPA Service

**VPA (Virtual Payment Address)** is the user-facing identity for payments (like johndoe@ybl).

**Registration flow:**
```
1. User enters preferred VPA: johndoe@ybl ("ybl" = Yes Bank Limited, PhonePe's handle).
2. VPA Service checks availability: SELECT 1 FROM vpas WHERE vpa = 'johndoe@ybl'.
3. If available: INSERT into local DB + REGISTER with NPCI mapper.
4. NPCI mapper stores: johndoe@ybl → PhonePe PSP bank.
5. When another app resolves johndoe@ybl → NPCI returns PhonePe PSP → PhonePe resolves to user's bank.
```

**VPA resolution in payment flow:**
```
Resolving merchant@paytm:
  1. PhonePe asks NPCI: "Who handles merchant@paytm?"
  2. NPCI returns: Paytm's PSP (One97 Communications / Axis Bank).
  3. PhonePe asks Paytm's PSP: "What is the registered name for merchant@paytm?"
  4. Response: { name: "Merchant XYZ", is_verified: true }
  5. Shown to user before they confirm payment.
```

**Why show name before payment?** Trust signal. Users can verify they're paying the right entity. Reduces fraud where someone creates a VPA mimicking a merchant.

### 8.4 NPCI Connector Service

**Acts as the integration layer between PhonePe and the PSP Bank's NPCI interface.**

**Key responsibilities:**
- Translate PhonePe's internal API calls to ISO 20022 UPI XML format.
- Maintain connection pool to PSP Bank's API endpoints (HTTPS, mutual TLS).
- Handle NPCI response codes and map to PhonePe error taxonomy.
- Manage PSP Bank RBA (Risk-Based Authentication) triggers.
- Enforce NPCI rate limits per PSP allocation.
- Health monitoring: heartbeat to PSP, failover to secondary PSP if primary is degraded.

**NPCI Error Code Mapping:**
```
U16  → INSUFFICIENT_FUNDS
U30  → TRANSACTION_LIMIT_EXCEEDED
U68  → TRANSACTION_FREQUENCY_LIMIT_EXCEEDED
U09  → BANK_SERVER_UNREACHABLE (retry eligible)
U69  → MERCHANT_NOT_PERMITTED
B0   → SUCCESS
```
Retry eligibility is critical: U09 is retriable; U16 (insufficient funds) is NOT.

**Circuit Breaker on PSP Bank:**
```
If PSP Bank error rate > 30% in 60s window:
  → Open circuit: reject new payment attempts with "Bank temporarily unavailable".
  → Retry PSP every 30s.
  → After 3 consecutive successes: close circuit (resume normal flow).
  → This prevents cascading failures into NPCI and protects PSP from thundering herd.
```

### 8.5 Fraud Detection Engine

Payments fraud is a critical SDE3 topic. This engine runs synchronously in the payment path (< 200ms budget).

**Risk signals (features):**
```
Device features:
  - device_id, device_fingerprint (new device for this user?)
  - is_rooted / is_emulator
  - location (GPS), IP address, VPN detected?
  - Screen reader / accessibility service active? (malware indicator)

Behavioral features:
  - time_since_last_login (seconds)
  - velocity: transactions in last 1h, 24h for this user
  - amount vs user's historical average transaction amount
  - new payee VPA? (first-time payment to this VPA)
  - time of day (transactions at 3 AM are higher risk)
  - typing speed in PIN entry (bot detection)

Network features:
  - payee VPA is blacklisted (known fraud account)
  - payee VPA newly created (< 7 days old)
  - payee registered with suspicious patterns

Transaction features:
  - round numbers (₹500, ₹1000 — common in fraud)
  - amount threshold triggers (₹99,999 — just under ₹1,00,000 limit)
  - rapid succession of same-amount payments to same payee
```

**Rule engine + ML scoring:**
```
Stage 1: Hard Rules (deterministic, < 1ms)
  - Blacklisted payee → BLOCK immediately.
  - Payer account suspended → BLOCK.
  - Amount > daily limit → BLOCK.
  - Transaction destination is OFAC/sanctions list → BLOCK.

Stage 2: ML Scoring (LightGBM model, < 50ms)
  - Feature vector assembled from Redis (pre-computed user features + real-time signals).
  - Model returns risk_score ∈ [0.0, 1.0].
  - score < 0.3 → ALLOW.
  - 0.3 ≤ score < 0.7 → ALLOW with monitoring (flag for review).
  - score ≥ 0.7 → ADD FRICTION (OTP step-up, additional confirmation).
  - score ≥ 0.9 → BLOCK.

Stage 3: Post-transaction async analysis
  - Graph-based analysis: is payee part of a known fraud ring?
  - Velocity checks across all PhonePe users (collective anomaly detection).
  - Flagged transactions sent to ops team for review.
```

**Real-time feature store:**
- User transaction velocity counters in Redis (INCR with EXPIRE).
- `velocity:{userId}:1h` → count of transactions in last 1 hour (sliding window using sorted set).
- Pre-computed user risk profile refreshed every 15 minutes by batch job.

### 8.6 Notification Service

**Every UPI transaction generates 2 critical notifications**: debit alert (payer) + credit alert (payee).

**Channels and SLA:**
```
Channel         | Delivery SLA  | Use case
----------------|---------------|---------------------------
In-app WebSocket| < 1 second    | User is active in app
Push (FCM/APNS) | < 5 seconds   | User app in background
SMS (via BSP)   | < 30 seconds  | Regulatory requirement (RBI mandates SMS for every bank transaction)
Email           | < 5 minutes   | Statement and receipts
```

**Architecture:**
```
Kafka topic: payment_events (partitioned by user_id for ordering)
  → Notification Service consumer
      Step 1: Fetch user notification preferences (push enabled? SMS opted-in?).
      Step 2: Template rendering: "₹500 debited from your A/C **1234 via UPI. Ref: NPCI_REF".
      Step 3: Channel dispatch (parallel):
               → FCM/APNS for push.
               → BSP (Bulk SMS Provider: Kaleyra / Exotel) for SMS.
               → In-app WebSocket push.
      Step 4: Delivery tracking (ACK from FCM; SMS DLR from BSP).
      Step 5: Retry failed deliveries (push failed → fallback to SMS).

SMS regulatory requirement (RBI):
  - Every debit from a bank account MUST trigger an SMS to registered mobile.
  - Cannot be disabled by user.
  - Must contain: amount, masked account number, timestamp, NPCI reference.
```

### 8.7 Transaction History Service

**High-read, write-once workload.**

**Storage tiering:**
```
Hot (0-90 days):   PostgreSQL → fast query with indexes.
Warm (90d-2yr):    PostgreSQL read replica or columnar store (Redshift/BigQuery).
Cold (2yr-7yr):    S3/GCS compressed Parquet files → queried via Athena/BigQuery on demand.
```

**Query patterns:**
```
Most common:
  SELECT * FROM transactions
  WHERE (payer_vpa = ? OR payee_vpa = ?)
  AND initiated_at BETWEEN ? AND ?
  ORDER BY initiated_at DESC
  LIMIT 20 OFFSET ?;

Index: (payer_vpa, initiated_at DESC) and (payee_vpa, initiated_at DESC).
For users with thousands of transactions: cursor-based pagination (keyset pagination)
instead of OFFSET to avoid deep page performance degradation.

Keyset pagination:
  WHERE payer_vpa = ? AND initiated_at < :last_seen_timestamp
  ORDER BY initiated_at DESC LIMIT 20;
  → Returns cursor = last_seen_timestamp for next page.
```

**Caching:**
- Recent 20 transactions cached per user in Redis (updated on every new transaction).
- Initial history load: cache hit (< 5ms). Older pages: DB query.

### 8.8 Reconciliation Service

**The financial backbone — ensures ledger correctness.**

**Daily reconciliation flow:**
```
T+0: During the day
  → Every transaction written to DB with status and RRN.
  → Ledger entries posted for confirmed SUCCESS transactions.

T+1: Morning (settlement file arrives from PSP bank)
  → PSP bank provides: settlement_date, all RRNs settled, direction (debit/credit), amount.
  → Reconciliation Service:
      For each settlement entry:
        MATCH against transactions table by RRN.
        VERIFY amount matches.
        VERIFY direction (debit/credit) matches.
        UPDATE status if previously TIMEOUT_UNRESOLVED.

Discrepancy categories and resolution:
  1. Settlement entry found, PhonePe shows SUCCESS: ✓ MATCHED → no action.
  2. Settlement entry found, PhonePe shows TIMEOUT: → Update to SUCCESS, post ledger.
  3. No settlement entry, PhonePe shows SUCCESS: → INVESTIGATE (money sent but not settled? Rare.)
  4. Settlement entry found, PhonePe shows FAILED: → REFUND immediately (deducted but not credited).
  5. Settlement amount ≠ PhonePe amount: → ALERT escalation (data corruption risk).

Reporting:
  → Generate daily reconciliation report.
  → Forward to finance team for NOSTRO account management (PSP bank holds PhonePe funds).
```

---

## 9. Exactly-Once Money Movement

This is the core correctness guarantee of any payment system.

### 9.1 The Problem

```
Scenario: Network drops after PhonePe sends debit request to NPCI.
  → PhonePe's DB shows status = PENDING (or TIMEOUT).
  → NPCI may have debited money (or may not have).
  → User sees "Payment failed" and might retry.
  → If retry creates a NEW transaction → double debit.
```

### 9.2 The Solution: Multi-Layer Idempotency

```
Layer 1: Client-side idempotency key (client_ref_no)
  → Stored on device; reused on every retry of same payment intent.
  → Server enforces uniqueness at DB level.

Layer 2: NPCI transaction ID idempotency
  → Same npci_txn_id reused on retries to NPCI.
  → NPCI deduplicates on its end.

Layer 3: Status check before re-initiating
  → If transaction_id exists with PENDING: query NPCI for status BEFORE retrying.
  → Only initiate new NPCI call if status is confirmed FAILED.

Layer 4: Database UNIQUE constraint
  → client_ref_no has UNIQUE index → SQL-level guarantee no duplicate rows.

Layer 5: Ledger double-entry verification
  → Reconciliation detects if ledger is unbalanced (one-sided entry = bug).

Result: Money moves exactly once even across retries, crashes, and network failures.
```

### 9.3 Distributed Transaction (Debit + Credit) Correctness

```
The fundamental challenge: Debit happens in payer's bank (System A).
                            Credit happens in payee's bank (System B).
                            These are different systems. 2PC would require NPCI as coordinator.

How UPI solves it:
  → NPCI acts as the orchestrator (not a 2PC coordinator, but functionally similar):
     1. NPCI instructs payer bank to debit.
     2. Payer bank debits and confirms.
     3. NPCI instructs payee bank to credit.
     4. Payee bank credits and confirms.
     5. NPCI returns final SUCCESS.

  → If step 3 fails: NPCI instructs payer bank to REVERSE the debit.
  → NPCI guarantees the credit or the reversal — never both fail simultaneously.
  → This is enforced by NPCI's protocol and audited by RBI.

PhonePe's responsibility:
  → Sync status with NPCI (timeout recovery loop).
  → Post ledger entries based on NPCI's final status.
  → Reconcile daily with settlement file.
```

---

## 10. Caching Strategy

### 10.1 Cache Tiers

```
Redis Cluster:
  → idempotency:{client_ref_no} → transaction result  TTL: 24h
  → vpa:{vpa_string} → {user_id, account_id, registered_name}  TTL: 5 min
  → user_daily_limit:{userId}:{date} → amount_used_today  TTL: until end of day
  → velocity:{userId}:1h → transaction count (sorted set with timestamps)  TTL: 2h
  → blacklist:{vpa} → 1  TTL: configurable (minutes to days based on severity)
  → collect:{collectId} → collect request details  TTL: collect expiry
  → recent_transactions:{userId} → last 20 transactions JSON  TTL: 1h

CDN:
  → Static assets (app JS, images, bill payment operator logos).

DB Read Replica:
  → Transaction history queries older than 5 minutes.
  → Analytics and reporting queries.
```

### 10.2 Daily Limit Enforcement

```
User UPI daily limit: ₹1,00,000 (RBI mandate for standard accounts).

Enforcement:
  → Redis key: daily_limit:{userId}:{YYYYMMDD}
  → On payment initiation: INCRBY daily_limit:{userId}:{today} {amount}
     → If result > 100000: DECRBY (rollback) and REJECT.
     → If result ≤ 100000: proceed.
  → TTL: set to midnight of current day (expire at day rollover).

Problem: Redis failure → limits not enforced?
  → Fallback: DB-level check (SELECT SUM(amount) WHERE payer_vpa = ? AND date = today).
  → Slightly slower but safe.
  → Redis is optimization, DB is correctness.
```

---

## 11. Data Partitioning And Scaling

### 11.1 Transaction DB Sharding

```
Shard by payer_vpa (hashed):
  → All transactions initiated by same user on same shard.
  → "My transaction history" is single-shard query.
  → Payee-perspective queries (what payments did I receive?) cross-shard.

  Solutions for cross-shard payee queries:
  Option A: Dual-write: write transaction to payer shard AND payee shard (eventual consistency).
  Option B: Secondary index DB (separate table partitioned by payee_vpa).
  Option C: Event stream: Kafka consumer writes payee-indexed records to separate read model.

Chosen: Option C (event-sourced read models).
  → Kafka: payment_events → payee_ledger_writer → payee_transactions_table (separate DB).
  → Query separation: payer queries his shard; payee queries read model.
```

### 11.2 Handling 2,000 TPS Peak

```
At 2,000 TPS:
  → API Gateway: 2,000 req/s → horizontally scaled stateless pods.
  → Payment Orchestration: 2,000 concurrent payment flows.
     Each flow involves: DB read, Redis check, Fraud engine, NPCI call, DB write.
     Longest step: NPCI call (200–3000ms). Use async I/O; non-blocking orchestration.
  → NPCI Connector: 2,000 concurrent NPCI calls.
     Connection pool to PSP bank: 2,000 connections.
     PSP bank's rate limit: managed by NPCI per-PSP-allocation.
  → Transaction DB: 2,000 writes/s + 6,000 reads/s.
     Handled by: connection pool (PgBouncer) + read replicas + write-optimized primary.
  → Redis: 20,000 ops/s → handled trivially by Redis cluster (1M ops/s per node).
```

### 11.3 NPCI Timeout And Rate Limit Management

```
NPCI has per-PSP-bank daily and per-second rate limits.

Rate limit tracking:
  → Redis key: npci_rate:{psp_id}:{second} → INCR counter with EX 2.
  → If counter > NPCI_LIMIT → queue or shed load.
  → Priority queue: real-time P2P payments > bill payments > low-priority.

Timeout management:
  → Each NPCI call: context timeout = 28s (buffer before NPCI's 30s hard timeout).
  → If timeout → trigger status check loop (Section 8.1.3).
  → Never retry a NPCI ReqPay call with same npci_txn_id if result is unknown.
     (Retrying an unknown transaction could cause double-debit at NPCI level.)
```

---

## 12. Security And Compliance

### 12.1 UPI PIN Security

```
UPI PIN: 4 or 6 digit number known only to user.
It MUST NEVER be stored anywhere — not in PhonePe's DB, not in PSP bank's DB.

Flow:
  1. User enters PIN in PhonePe's secure SDK keyboard (custom keyboard, not system keyboard).
     → Custom keyboard prevents keyloggers and screen capture.
  2. PIN encrypted on-device using NPCI's public key (RSA-2048 or ECDH).
  3. Encrypted PIN sent to PhonePe's backend.
  4. PhonePe forwards encrypted PIN opaquely to PSP Bank.
  5. PSP Bank forwards to NPCI switch.
  6. NPCI decrypts PIN using its private key and validates against bank's stored PIN hash.
  7. PhonePe NEVER sees the plaintext PIN.
  8. PIN validation result returned (valid/invalid) — not the PIN itself.

Defense against:
  - Phishing: custom keyboard not accessible to other apps.
  - TLS interception: PIN encrypted with NPCI key layered INSIDE TLS.
  - Replay: Each encrypted PIN blob includes nonce/timestamp; NPCI rejects replays.
```

### 12.2 Device Binding And Session Security

```
Device registration:
  1. On app install: generate device_id + RSA key pair (private key in Android Keystore / iOS Secure Enclave).
  2. Register device_id + public key with PhonePe.
  3. All API calls signed with device private key.
  4. Server verifies signature → ensures request originates from registered device.
  5. Private key never leaves device (hardware-backed).

Benefits:
  - SIM swap attack mitigation: even if attacker clones SIM, they don't have device key.
  - Rooted device detection: key store unavailable on rooted devices → registration blocked.

Session management:
  - JWT tokens: short-lived (15 min access token, 30 days refresh token).
  - Refresh token bound to device_id → stolen refresh token useless on different device.
  - On suspicious login (new device, new location): force re-authentication.
```

### 12.3 Regulatory Compliance (RBI Guidelines)

```
1. Transaction limits:
   - Standard UPI: ₹1,00,000/transaction, ₹1,00,000/day.
   - UPI Lite: ₹500/transaction, ₹2,000/wallet (offline, no PIN needed).
   - UPI 123PAY: Feature phone payments, ₹5,000 limit.
   - Enhanced limits for verified merchants (up to ₹10,00,000).

2. KYC (Know Your Customer):
   - Basic KYC: mobile + bank account linking (minimum).
   - Full KYC: Aadhaar-based biometric verification → higher limits and financial products.
   - UPI works on basic KYC for payments.

3. Data localization (RBI mandate):
   - All payment data of Indian customers must be stored in India.
   - Cross-border sharing allowed for processing but local copy mandatory.

4. AML / CFT (Anti-Money Laundering / Countering Financing of Terrorism):
   - Transaction monitoring for patterns: structuring (just below reporting thresholds), layering.
   - Suspicious activity reports to FIU-IND (Financial Intelligence Unit).
   - OFAC/UN sanctions list screening on every VPA.

5. Grievance redressal:
   - Failed/disputed transactions: resolved within 1 business day (RBI mandate).
   - Customer must be notified of resolution within timeline.

6. Audit logs:
   - Retain all transaction records for 7 years.
   - Immutable: no UPDATE/DELETE on transaction or ledger records.
   - Accessible to RBI/enforcement agencies on demand.
```

### 12.4 Encryption At Rest And In Transit

```
In transit:
  - TLS 1.3 (minimum) for all client-server and service-service communication.
  - Mutual TLS (mTLS) for PSP bank and NPCI communication.
  - Certificate pinning in app (prevents MITM even with trusted CA compromise).

At rest:
  - Transaction DB: Transparent Data Encryption (TDE) at DB level (AES-256).
  - No sensitive data (account numbers, VPA) stored in application logs.
  - Bank account reference tokens stored (not real account numbers) — PSP holds mapping.
  - Redis: no plaintext PII, only user IDs and anonymized risk features.

Key management:
  - HSM (Hardware Security Module) for NPCI key operations.
  - Application secrets via Vault/KMS (never in config files or environment variables).
  - Key rotation: quarterly for application keys, annually for root CA.
```

---

## 13. Observability And SLOs

### 13.1 SLOs

| Metric | Target |
|---|---|
| Payment initiation API latency P95 | < 500ms |
| NPCI call success rate | > 99.5% |
| End-to-end transaction success rate | > 99.0% |
| Transaction status delivery to user (push) | < 5s P95 |
| SMS notification delivery | < 30s P95 |
| Idempotency correctness | 100% (zero tolerance) |
| Reconciliation completion (T+1) | 100% of transactions matched |

### 13.2 Critical Metrics To Monitor

**Payment health:**
- TPS (real-time transactions per second).
- NPCI success rate by bank, by error code.
- UPI timeout rate (> 2% = alert).
- Double-debit incidents (should be 0; any non-zero = P0 incident).

**Fraud metrics:**
- Fraud detection rate (% of fraud caught before transaction).
- False positive rate (legitimate transactions blocked).
- Average fraud detection latency (must be < 200ms).

**Infrastructure:**
- PSP bank API latency distribution.
- Redis memory utilization (evictions = data loss risk).
- Kafka consumer lag (notification delays).

### 13.3 Alerting

```
P0 (Immediate, 24x7 oncall):
  - Transaction success rate drops below 95%.
  - Any double-debit/double-credit event detected.
  - NPCI connector circuit breaker opens.
  - Fraud engine response time > 500ms (payments will fail).

P1 (15-minute response):
  - Payment API P95 latency > 2 seconds.
  - SMS delivery rate below 98%.
  - Reconciliation discrepancy detected.

P2 (Business hours):
  - Elasticsearch cold storage query failures.
  - Fraud model drift detected (monitoring accuracy metrics).
```

---

## 14. Major Trade-Offs And Why

### 14.1 Synchronous vs Asynchronous Payment Response
- **Sync**: User waits for NPCI response (up to 30s). Simple but user experience is blocking.
- **Async**: Acknowledge immediately, poll for result. Better UX (spinner not blocking).
- **Decision**: Hybrid — response returned in < 2s if NPCI responds quickly (majority of cases). If NPCI takes > 2s, return `status=pending` and client polls/WebSocket delivers result.

### 14.2 Strong Consistency for Transaction DB vs Eventual
- Money movement demands **strong consistency**. No eventual consistency for ledger writes.
- Spanner / CockroachDB (globally consistent, serializable) vs MySQL/PostgreSQL (single-region strong).
- At PhonePe's scale (2,000 TPS) → PostgreSQL with read replicas per region handles writes; Spanner if truly global.
- **Decision**: PostgreSQL with synchronous streaming replication for HA. Both primary and one replica must ACK each write (RPO = 0).

### 14.3 Idempotency via DB UNIQUE vs Distributed Lock
- Distributed lock (Redis SETNX): fast but lock can expire mid-flight → race condition.
- DB UNIQUE constraint: atomic at DB level; cannot race. Slightly slower (DB write).
- **Decision**: DB UNIQUE constraint as the source of truth + Redis as fast pre-check (performance optimization, not correctness layer).

### 14.4 Fraud Check In-Path vs Out-of-Path
- In-path (synchronous): higher friction latency but prevents fraud before money moves. Safer.
- Out-of-path (async): lowest latency but fraud detected after money moved → harder to reverse.
- **Decision**: In-path for all transactions (< 200ms budget). Hard rules: < 1ms. ML: < 50ms. Still within UPI's perceived latency budget.

### 14.5 Single PSP Bank vs Multiple PSP Banks
- Single PSP: simpler integration; SPOF if PSP has outage.
- Multiple PSPs: redundancy, can route to healthy PSP. More regulatory complexity (each PSP has its own UPI handle).
- **Decision**: PhonePe uses a primary PSP (Yes Bank) with secondary PSP for failover. Failover is not seamless (VPA handle is PSP-specific) but prevents complete service outage.

---

## 15. Interview-Ready Deep Dive Talking Points

**"How do you prevent double debit?"**
> Three-layer defense: (1) Client generates idempotency key, reused on every retry. (2) Server enforces DB UNIQUE constraint on client_ref_no — SQL-level guarantee. (3) Same npci_txn_id forwarded to NPCI on retries — NPCI deduplicates on its end. Reconciliation catches any residual discrepancy T+1.

**"What happens if the network drops after NPCI processes but before PhonePe gets the response?"**
> Transaction enters TIMEOUT state. Async status check loop polls NPCI every 10s up to 6 times. If still unknown → daily reconciliation against PSP bank settlement file resolves it. User sees "Payment in progress" and is notified on resolution. Money never lost — either confirmed or reversed.

**"How do you scale to 2,000 TPS?"**
> API Gateway horizontally scaled; stateless pods. Payment Orchestration uses async I/O (each payment is a non-blocking flow). NPCI Connector maintains 2,000-connection pool to PSP bank. Transaction DB: connection pooling (PgBouncer) + 1 primary + 3 replicas. Redis handles idempotency and rate limiting at 1M ops/sec. Kafka absorbs notification fan-out.

**"How do you secure the UPI PIN?"**
> Custom keyboard prevents keyloggers. PIN encrypted on-device using NPCI's RSA public key before leaving the device. PhonePe backend never sees or stores plaintext PIN — transmitted opaquely to NPCI. NPCI decrypts, validates, returns success/failure only. Result is non-replayable (timestamp nonce included in encrypted blob).

**"How does fraud detection work without adding latency?"**
> Hard rules evaluated in < 1ms (blacklist check in Redis, limit check). ML scoring using pre-computed user features from Feature Store (< 50ms LightGBM inference). Total fraud check budget: < 200ms. Features pre-computed every 15 min by batch job + real-time velocity counters updated on every transaction. ML model retrained weekly on labeled fraud data.

---

## 16. Possible 45-Minute Interview Narrative

| Time | Focus |
|---|---|
| 0–5 min | UPI ecosystem primer: actors, VPA, NPCI, PSP roles |
| 5–10 min | Scale: 2,000 TPS, 150M txns/day, latency and consistency SLOs |
| 10–18 min | API design + Data model: transactions table, ledger, idempotency key |
| 18–30 min | Payment orchestration deep dive: push flow, timeout recovery, state machine |
| 30–37 min | Idempotency, fraud detection, PIN security |
| 37–43 min | Scaling architecture, caching, reconciliation |
| 43–45 min | Trade-offs, extensions |

---

## 17. Extensions To Mention If Time Permits

- **UPI Lite**: Offline wallet on device for small payments without PIN (< ₹500). Pre-loaded from bank account. Syncs periodically.
- **UPI 123PAY**: Feature phone UPI — IVR call, missed call, or sound-wave-based payment. Extends UPI to non-smartphone users (400M in India).
- **Credit on UPI**: UPI-linked credit lines (BNPL) — pay via UPI drawing from pre-approved credit limit, not bank balance.
- **International UPI**: India-Singapore UPI linkage; India-UAE cross-border UPI payments in progress.
- **Merchant analytics**: Real-time GMV dashboard for merchants using PhonePe Business; powered by Druid/ClickHouse.
- **Auto-pay mandates**: Standing instructions for recurring payments (subscriptions, EMI) — UPI AutoPay (e-NACH equivalent).
- **Voice payments**: "Hey PhonePe, send ₹200 to Rahul" — NLP + voice biometric authentication.
- **PhonePe Switch**: Super-app model — third-party mini-apps embedded in PhonePe (Ola, Swiggy, etc.) using PhonePe's payment rails seamlessly.
