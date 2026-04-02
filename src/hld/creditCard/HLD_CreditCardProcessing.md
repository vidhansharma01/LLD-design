# HLD — Credit Card Processing System

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** Financial Systems / Distributed Transactions / Compliance
> **Real-world Analogues:** Stripe, Braintree, Adyen, Square, PayU
> **The Core Problem:** Process payments in < 2 seconds with zero data loss, exactly-once semantics, and PCI DSS compliance — even when any component fails.

---

## How to Navigate This in 45 Minutes

```
[0:00 -  3:00]  Step 1: Clarify Requirements
[3:00 -  6:00]  Step 2: Capacity Estimation
[6:00 -  9:00]  Step 3: API Design
[9:00 - 20:00]  Step 4: High-Level Architecture (draw the full payment flow)
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
   - 5.1 [Authorization Flow (Real-Time < 2s)](#51-authorization-flow-real-time--2s)
   - 5.2 [Idempotency & Exactly-Once Processing](#52-idempotency--exactly-once-processing)
   - 5.3 [Card Tokenization & PCI DSS Compliance](#53-card-tokenization--pci-dss-compliance)
   - 5.4 [Fraud Detection Engine](#54-fraud-detection-engine)
   - 5.5 [Settlement, Reconciliation & Chargebacks](#55-settlement-reconciliation--chargebacks)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### Payment Industry Terminology (Know This Before Interview)

```
MERCHANT:       Business accepting payment (Amazon, Swiggy, Netflix)
CARDHOLDER:     Person paying with credit/debit card
ACQUIRER:       Merchant's bank (processes payments on behalf of merchant)
ISSUER:         Cardholder's bank (issues the card; Chase, HDFC, SBI)
CARD NETWORK:   Payment rails (Visa, Mastercard, Amex, RuPay)
PAYMENT GATEWAY: Software that connects merchant to acquirer (Stripe, Razorpay)
PAN:            Primary Account Number — the 16-digit card number
CVV:            Card Verification Value — 3-4 digit security code
AUTHORIZATION:  Issuer approves/declines the payment (real-time)
CAPTURE:        Merchant requests actual money movement (can be later)
SETTLEMENT:     Actual fund transfer from issuer to acquirer to merchant (T+1/T+2)
CHARGEBACK:     Customer disputes a charge — reversal initiated by cardholder
PCI DSS:        Payment Card Industry Data Security Standard (compliance requirement)
```

### Clarifying Questions

```
Q1: "Are we building the full payment stack (gateway + processor + network)
     or just the payment gateway (Stripe-like) that sits in front of acquirers?"
     → This interview typically expects the gateway + core processing logic.

Q2: "Domestic only or international (multi-currency, cross-border)?"
     → Start with domestic; add multi-currency as extension.

Q3: "Online card-not-present (CNP) only, or also POS/in-store (card-present)?"
     → CNP: higher fraud risk; different auth flow.

Q4: "What payment methods? Credit, debit, prepaid, UPI, wallets?"
     → Focus on credit/debit card flow; mention UPI as extension.

Q5: "Do we need 3D Secure (OTP challenge) for high-risk transactions?"
     → Important for international compliance (EU PSD2, India RBI mandate).

Q6: "What's the dispute/chargeback handling requirement?"
     → Basic: log chargebacks. Advanced: automated evidence submission.
```

### Functional Requirements

| # | Requirement | SLA |
|---|---|---|
| FR-1 | **Authorize** a card payment — check funds, fraud, limits | < 2 seconds |
| FR-2 | **Capture** an authorized amount (deferred capture for hotels, etc.) | < 5 seconds |
| FR-3 | **Refund** a previously captured payment (full or partial) | < 5 seconds |
| FR-4 | **Tokenize** card data — store PAN securely, return a token | < 500ms |
| FR-5 | **Recurring billing** — charge saved token on schedule | < 5 seconds |
| FR-6 | **3D Secure** — redirect cardholder to bank for OTP if needed | User-interactive |
| FR-7 | **Settlement** — batch reconcile and transfer funds | T+1 (next day) |
| FR-8 | **Chargeback** — process dispute, freeze funds, send evidence | Manual + automated |
| FR-9 | **Fraud detection** — ML-based; block suspicious transactions | < 200ms inline |
| FR-10 | **Webhooks** — notify merchants of payment status changes | < 60s delay |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Throughput** | 10K TPM (transactions/min) sustained; 50K TPM peak |
| **Authorization latency** | p99 < 2 seconds end-to-end (merchant to response) |
| **Availability** | 99.999% (Five 9s — < 5 min downtime/year) |
| **Durability** | Zero payment records lost — ever |
| **Consistency** | Exactly-once: double-charge is worse than any downtime |
| **PCI DSS Level 1** | Highest compliance tier (> 6M transactions/year) |
| **Idempotency** | Network retries must NOT create duplicate charges |

---

## 2. Capacity Estimation

```
Transaction volume:
  10K transactions/min = 167 TPS sustained
  Peak (flash sale, Diwali, Black Friday): 5× = 835 TPS
  Annual volume: 10K × 60 × 24 × 365 = ~5.2B transactions/year

Per-transaction data:
  Authorization request: ~500 bytes (card data, merchant info, amount, metadata)
  Authorization response: ~300 bytes (decision, auth code, decline reason)
  Full transaction record: ~2 KB (with all metadata, fraud signals, network codes)

Storage sizing:
  5.2B transactions/year × 2 KB = 10.4 TB/year raw
  7-year retention (financial regulation): 10.4 TB × 7 = 72.8 TB
  With replication (3×): ~220 TB total
  With indexes: ~300 TB over 7 years

Card vault (tokens):
  Unique cards stored: 50M (cards on file for recurring billing)
  Per card record: ~512 bytes (token + encrypted PAN + metadata) = 25.6 GB
  → Tiny compared to transaction log

Latency budget per authorization (total < 2000ms):
  Merchant → Gateway:        50ms  (TLS + TCP)
  Gateway validation:        10ms
  Fraud scoring (ML):       150ms
  3D Secure check:           20ms  (basic; 0ms if not triggered)
  Acquirer routing:          50ms
  Card network (Visa/MC):   800ms  (BIN routing + issuer authorization)
  Issuer authorization:     500ms  (issuer bank processing)
  Gateway response:          50ms
  Gateway → Merchant:        50ms
  ─────────────────────────────────
  Total budget:            1680ms  (< 2000ms target ✓)
  Headroom:                 320ms  (for retries, timeouts)
```

---

## 3. API Design

### Merchant-Facing APIs (Stripe-like)

```http
# ============================================================
# TOKENIZE CARD (called client-side; PAN never hits merchant server)
# ============================================================
POST /v1/tokens
Content-Type: application/json
# Note: Called directly from browser/mobile SDK to avoid PAN touching merchant servers

{
  "pan":            "4111111111111111",   # 16-digit card number
  "exp_month":      12,
  "exp_year":       2028,
  "cvv":            "123",
  "cardholder_name":"John Doe",
  "billing_address": {
    "line1":    "123 Main St",
    "city":     "Mumbai",
    "state":    "MH",
    "zip":      "400001",
    "country":  "IN"
  }
}

Response 200: (CVV is NEVER stored; PAN is encrypted in vault)
{
  "token":          "tok_4xKnV7qQmJ3rPsL",   # single-use token
  "last4":          "1111",
  "brand":          "visa",                   # visa | mastercard | amex | rupay
  "exp_month":      12,
  "exp_year":       2028,
  "fingerprint":    "abc123def456",           # Hash of PAN (for dedup, not reversible)
  "expires_at":     "2026-04-01T10:15:00Z"    # Token valid for 15 minutes
}


# ============================================================
# CREATE PAYMENT INTENT (merchant backend; token from client)
# ============================================================
POST /v1/payment-intents
Authorization: Bearer sk_live_{merchant_secret_key}
Idempotency-Key: order_7f3a9b2c1d4e               # CRITICAL: merchant provides this

{
  "amount":          9999,                    # in smallest currency unit (paise)
  "currency":        "INR",
  "payment_method":  "tok_4xKnV7qQmJ3rPsL",  # single-use token from client
  "confirm":         true,                    # true = authorize + capture immediately
  "capture_method":  "automatic",            # automatic | manual (for holds)
  "description":     "Order #ORD-12345",
  "merchant_ref":    "ORD-12345",
  "metadata": {
    "customer_id":   "cust_xyz",
    "product_id":    "prod_abc"
  },
  "return_url":      "https://merchant.com/payment/return"  # for 3DS redirect
}

Response 200 (authorized and captured):
{
  "id":              "pi_9c3dE7fGhI2jK",   # payment intent ID (our system)
  "status":          "succeeded",           # created | requires_action | processing | succeeded | failed
  "amount":          9999,
  "currency":        "INR",
  "auth_code":       "AUTH543210",          # from card network
  "network_txn_id":  "VISA123456789",       # card network transaction ID
  "captured":        true,
  "captured_at":     "2026-04-01T10:00:01Z",
  "merchant_ref":    "ORD-12345",
  "failure_code":    null,
  "failure_message": null
}

Response 200 (3DS required):
{
  "id":     "pi_9c3dE7fGhI2jK",
  "status": "requires_action",
  "next_action": {
    "type": "redirect_to_url",
    "redirect_to_url": {
      "url":        "https://bank.example.com/3ds2/auth?session=xyz",
      "return_url": "https://merchant.com/payment/return"
    }
  }
}


# ============================================================
# CAPTURE PRE-AUTHORIZED PAYMENT
# ============================================================
POST /v1/payment-intents/{payment_intent_id}/capture
{ "amount": 9999 }    # can capture partial amount (< authorized)


# ============================================================
# REFUND
# ============================================================
POST /v1/refunds
Idempotency-Key: refund_{order_id}_{timestamp}
{
  "payment_intent": "pi_9c3dE7fGhI2jK",
  "amount":         5000,    # partial refund; omit for full refund
  "reason":         "requested_by_customer"   # duplicate | fraudulent | requested_by_customer
}

Response 200:
{
  "id":      "ref_2mNpQ4rS6tU",
  "status":  "pending",          # refunds take 5-7 business days to settle
  "amount":  5000,
  "currency": "INR"
}
```

---

## 4. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                            PAYMENT ACTORS                                    │
│                                                                              │
│   MERCHANT                    CARDHOLDER                 CARD NETWORK        │
│   (Amazon, Swiggy)            (John's browser/app)       (Visa / Mastercard) │
└──────┬──────────────────────────────┬───────────────────────────┬────────────┘
       │                              │ PAN (direct to gateway SDK)│
       │ API call (no PAN)            │                           │
       ▼                              ▼                           │
┌──────────────────────────────────────────────────────────────────────────────┐
│                        PAYMENT GATEWAY                                       │
│  ┌────────────────┐  ┌──────────────────┐  ┌──────────────────────────────┐ │
│  │ API Gateway    │  │  TOKENIZATION    │  │     CARD VAULT               │ │
│  │                │  │  SERVICE         │  │     (PCI Isolated Zone)      │ │
│  │ • Auth/AuthZ   │  │                  │  │     • AES-256 key per PAN    │ │
│  │ • Rate Limit   │  │  PAN → Token     │  │     • HSM for key management │ │
│  │ • TLS Termination│  CVV → Never stored  │     • token → encrypted PAN  │ │
│  │ • Idempotency  │  │                  │  │     • Air-gapped from rest   │ │
│  └────────┬───────┘  └──────────────────┘  └──────────────────────────────┘ │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                    PAYMENT ORCHESTRATOR                                 │ │
│  │                                                                         │ │
│  │  1. Validate amount, currency, merchant limits                          │ │
│  │  2. Invoke Fraud Detection Service (async ML scoring)                   │ │
│  │  3. 3DS check (high-risk transactions)                                  │ │
│  │  4. Route to acquirer / card network                                    │ │
│  │  5. Record result; emit events                                          │ │
│  └────────────────────────────────┬────────────────────────────────────────┘ │
└───────────────────────────────────┼──────────────────────────────────────────┘
                                    │
           ┌────────────────────────┼──────────────────────────────┐
           ▼                        ▼                              ▼
  ┌──────────────────┐   ┌──────────────────┐           ┌─────────────────────┐
  │  FRAUD DETECTION │   │ ACQUIRER GATEWAY │           │  PAYMENT DB         │
  │  SERVICE         │   │ (per acquirer:   │           │  (PostgreSQL        │
  │                  │   │  HDFC, Razorpay, │           │   + Cassandra)      │
  │  • Real-time ML  │   │  Stripe, Adyen)  │           │                     │
  │    score < 200ms │   │                  │           │  • Transactions     │
  │  • Rules engine  │   │  Translate to    │           │  • Authorizations   │
  │  • Velocity      │   │  ISO 8583 or     │           │  • Refunds          │
  │    checks        │   │  acquirer API    │           │  • Audit log        │
  │  • Device signals│   └────────┬─────────┘           │  (immutable WAL)    │
  └──────────────────┘            │ ISO 8583 / HTTPS    └─────────────────────┘
                                  ▼
                         ┌────────────────────┐
                         │   CARD NETWORK     │
                         │  (Visa / MC / Amex)│
                         │                   │
                         │  BIN table routing │
                         │  → Issuer Bank     │
                         └────────┬───────────┘
                                  │
                                  ▼
                         ┌────────────────────┐
                         │  ISSUER BANK       │
                         │  (HDFC, Chase,     │
                         │   SBI Credit Card) │
                         │                   │
                         │  • Balance check   │
                         │  • Risk scoring    │
                         │  • AUTH / DECLINE  │
                         └────────────────────┘

ASYNC SERVICES (post-authorization):
  ┌──────────────────┐  ┌──────────────────────┐  ┌─────────────────────┐
  │  WEBHOOK SERVICE │  │  SETTLEMENT SERVICE  │  │  NOTIFICATION SVC  │
  │  • Notify merchant│  │  • T+1 batch         │  │  • Email receipts  │
  │  • Retry with    │  │  • Reconciliation     │  │  • SMS alerts      │
  │    backoff       │  │  • Net settlement     │  │  • Merchant portal │
  └──────────────────┘  └──────────────────────┘  └─────────────────────┘
```

### ISO 8583 — The Language of Card Networks

```
Card networks communicate via ISO 8583 protocol (not HTTP/REST):
  Binary protocol; each message is a bitmap + data elements
  
  Key message types:
    0100: Authorization Request   (gateway → card network)
    0110: Authorization Response  (card network → gateway)
    0200: Financial Request        (for immediate debit)
    0420: Reversal Request         (cancel an authorization)
    0800: Network Management       (keepalive, key exchange)

  Key data elements in auth request (DE):
    DE2:  Primary Account Number (PAN)
    DE3:  Processing Code (00=purchase, 20=refund, 28=balance inquiry)
    DE4:  Transaction Amount
    DE12: Local Transaction Time
    DE22: Point-of-Service Entry Mode (01=manual, 07=chip, 10=contactless)
    DE35: Track 2 Equivalent Data (magnetic stripe)
    DE41: Terminal ID (merchant POS ID)
    DE42: Merchant ID (MID)
    DE49: Currency Code (356=INR, 840=USD)

  Key result codes in auth response (DE39):
    "00": Approved
    "05": Do Not Honor (issuer declined)
    "14": Invalid Card Number
    "51": Insufficient Funds
    "54": Expired Card
    "57": Transaction Not Permitted
    "91": Issuer Unavailable
    "96": System Error
```

---

## 5. Deep Dives

### 5.1 Authorization Flow (Real-Time < 2s)

```
Complete authorization trace for a ₹999 Swiggy order:

T+0ms:    Swiggy's checkout calls:
          POST /v1/payment-intents
          { amount: 99900, currency: "INR", payment_method: "tok_abc123" }
          Idempotency-Key: ORD-12345

T+5ms:    API Gateway:
          • Authenticate merchant API key (Redis lookup: key → merchant config)
          • Check Idempotency-Key: Redis SETNX idempotency:ORD-12345 "processing"
            → IF returns 0 (already exists): return cached response (duplicate request)
            → IF returns 1 (new): proceed
          • Rate limit check: 100 TPS per merchant (Redis INCR + TTL)

T+10ms:   Payment Orchestrator writes to DB:
          INSERT INTO transactions (
            id, merchant_id, amount, currency, status='INITIATED',
            idempotency_key, payment_method_token, created_at
          ) — returns transaction UUID

T+20ms:   Card Vault lookup:
          token "tok_abc123" → decrypt PAN (AES-256 in HSM)
          → PAN: 4111XXXXXXXX1111, BIN: 411111 (first 6 digits)

T+30ms:   BIN (Bank Identification Number) lookup:
          BIN 411111 → Issuer: HDFC Bank, Network: Visa, Card Type: Credit
          → Routing: send to Visa network via HDFC acquirer

T+50ms:   Fraud Detection Service (ASYNC parallel call):
          ML score computed in < 150ms (see §5.4)
          Block if fraud_score > 0.9; flag 0.7-0.9 for 3DS

T+100ms:  Build ISO 8583 0100 Authorization Request:
          DE2: 4111XXXXXXXX1111 (PAN from vault)
          DE4: 000000099900 (₹999.00 in paise)
          DE49: 356 (INR)
          DE41: SWIGGY_MID_{store_id}
          (CVV value included but NOT stored after this call)

T+100ms:  Send 0100 to Visa network via TCP/IP (dedicated leased line or TLS)
          → Visa routes to HDFC Card Authorization System

T+900ms:  HDFC Card Auth System (Issuer) processes:
          • Balance check: ₹999 available? YES
          • Spending limit: under monthly limit? YES
          • Fraud score (issuer's own ML): LOW RISK
          • Card status: ACTIVE, not blocked
          → Decision: APPROVE
          → Generate auth code: AUTH543210

T+950ms:  ISO 8583 0110 Response received by gateway:
          DE39: "00" (Approved)
          DE38: "543210" (Authorization Code)
          DE37: Network Transaction ID

T+960ms:  Gateway updates DB:
          UPDATE transactions SET
            status='AUTHORIZED',
            auth_code='AUTH543210',
            network_txn_id='VISA123456789',
            authorized_at=NOW()
          WHERE id='{txn_id}';
          
          CRITICAL: This UPDATE uses a state machine:
            INITIATED → AUTHORIZED (only valid transition here)
            Any other current status: REJECT the update (stale data guard)

T+970ms:  Update Idempotency-Key cache:
          Redis: idempotency:ORD-12345 → {full response JSON}
          TTL: 24 hours (for retries)

T+980ms:  Return 200 to Swiggy:
          { status: "succeeded", auth_code: "AUTH543210", ... }

T+980ms:  Async (non-blocking):
          • Emit TransactionAuthorized event to Kafka
          • Webhook service picks up → POST to Swiggy webhook URL
          • Fraud model feature store updated with new transaction signals

Total wall time: ~980ms ✓ (well under 2000ms target)
Network to issuer is the dominant latency (800-900ms is typical for Visa/Mastercard)
```

#### Authorization vs Capture Distinction

```
Two-phase commit in real life:

Phase 1: AUTHORIZATION (hold funds)
  → Issuer puts a HOLD on the cardholder's credit line
  → Merchant does NOT get the money yet
  → Hotel checks in: authorize $500 on checkout, capture actual amount on checkout
  → Auth is valid for: credit cards = 30 days; debit = 3-7 days

Phase 2: CAPTURE (request money movement)
  → Merchant submits captured transactions in a daily batch file
  → Initiates actual fund movement: Issuer → Visa → Acquirer → Merchant

Auth without Capture:
  → If auth is never captured (customer cancelled order): auth expires naturally
  → Merchant should explicitly VOID (reverse) the auth to release hold immediately
  → Send ISO 8583 0420 (Reversal) message

Pre-auth + Partial Capture:
  → Uber authorizes ₹1000 at trip start; captures actual ₹347 at trip end
  → Remaining ₹653 authorization released automatically
```

---

### 5.2 Idempotency & Exactly-Once Processing

> **Interview tip:** This is THE most important correctness concern in payments. A double-charge is far worse than downtime. Lead with this.

```
Problem: Network is unreliable. Merchant's server may:
  1. Send payment request
  2. Receive timeout (network dropped)
  3. Not know: did the charge succeed or fail?
  4. Retry the request
  
  Without idempotency: TWO charges on the cardholder's account.
  This is a regulatory violation and destroys customer trust.
```

#### Idempotency Key Design

```
Merchant provides Idempotency-Key header on EVERY write request:
  Idempotency-Key: ORD-12345-ATTEMPT-1
  → Must be unique per payment attempt
  → Merchant generates this (UUID, order ID, etc.)
  → Gateway stores response for 24 hours with this key

3-Layer Idempotency Protection:

Layer 1: Redis Fast Path (in-memory dedup, < 1ms)
  Key: idempotency:{merchant_id}:{idempotency_key}
  Value: { status: "processing" | "completed", response: {...} }
  TTL: 86400 seconds (24 hours)
  
  On request arrival:
    result = Redis SETNX idempotency:key "processing"
    
    IF result = 1 (new key set):
      → First request → process normally
    IF result = 0 (key already exists):
      → Duplicate detected
      current = Redis GET idempotency:key
      IF current.status == "completed":
        → Return cached response (idempotent return)
      IF current.status == "processing":
        → Request still in-flight → return 202 Accepted or wait

Layer 2: Database Unique Constraint (durable dedup)
  CREATE UNIQUE INDEX idx_idempotency 
  ON transactions(merchant_id, idempotency_key);
  
  Even if Redis is down:
    INSERT INTO transactions (..., idempotency_key)
    → ON CONFLICT (merchant_id, idempotency_key) DO NOTHING RETURNING *
    → Returns existing row if duplicate → return its result

Layer 3: Card Network Deduplication
  Include the same merchant_reference (DE37) in all retries to the network
  → Visa/MC track this; if same merchant_ref within 24h: network-level dedup
  → Issuer sees it as same transaction; won't double-charge

Idempotency FSM (Finite State Machine):
  PROCESSING → COMPLETED | FAILED | TIMED_OUT
  
  On timeout (caller retries):
    1. Check Redis: still "processing" after 30s → something went wrong
    2. Check DB: did auth succeed or fail?
    3. If AUTHORIZED in DB: consider succeeded; update Redis; return cached success
    4. If still INITIATED in DB after 30s: attempt void/reversal; mark TIMED_OUT
```

#### Transaction State Machine

```
States for a Payment Intent:

                    ┌──────────────┐
           CREATE   │              │
          ─────────►│   INITIATED   │
                    │              │
                    └──────┬───────┘
                           │
               ┌───────────┼───────────────┐
               │                           │
               ▼ (auth approved)           ▼ (auth declined / timeout)
    ┌──────────────────┐         ┌─────────────────────┐
    │   AUTHORIZED     │         │      FAILED          │
    │  (funds held)    │         │  (no charge made)    │
    └──────┬─────┬─────┘         └─────────────────────┘
           │     │
           │     └──────────────────────────────────────┐
           │ (captured)                                  │ (voided/expired)
           ▼                                             ▼
  ┌─────────────────────┐                    ┌──────────────────┐
  │     CAPTURED        │                    │     VOIDED       │
  │  (money requested)  │                    │  (hold released) │
  └──────┬──────────────┘                    └──────────────────┘
         │
         │ (settled T+1)
         ▼
  ┌─────────────────────┐
  │     SETTLED         │◄──────────────────────────────────────┐
  │  (money transferred)│                                       │
  └──────┬──────────────┘                                       │
         │                                                      │ (chargeback resolved for merchant)
         │ (chargeback filed)                                   │
         ▼                                                      │
  ┌─────────────────────┐         ┌──────────────────────────┐  │
  │    DISPUTED         │────────►│   CHARGEBACK_WON        │──┘
  │  (funds frozen)     │  win    │   (merchant keeps money) │
  └──────┬──────────────┘         └──────────────────────────┘
         │ lose
         ▼
  ┌─────────────────────┐
  │  CHARGEBACK_LOST    │
  │  (merchant refunded │
  │   cardholder)       │
  └─────────────────────┘

DB enforcement:
  All state transitions validated by CHECK constraint + trigger:
  BEFORE UPDATE: IF (old_status, new_status) NOT IN allowed_transitions: RAISE EXCEPTION
```

---

### 5.3 Card Tokenization & PCI DSS Compliance

> **Interview tip:** PCI DSS is non-negotiable in card processing. Explain it upfront — shows seniority.

#### PCI DSS Requirements (What Matters for Design)

```
PCI DSS: Payment Card Industry Data Security Standard

Critical rules:
  Rule 3.2: NEVER store CVV/CVC after authorization (EVER, even encrypted)
  Rule 3.4: PAN must be rendered unreadable in storage (encrypt or truncate)
  Rule 6.4: Only last 4 digits of PAN can be stored in clear text
  Rule 7:   Restrict access to cardholder data by business need
  Rule 8:   Unique IDs for every person with computer access
  Rule 10:  Track all access to cardholder data (immutable audit log)

Design implication for our system:
  ✗ MERCHANT SERVERS must NEVER see raw PAN/CVV
  ✓ Browser/mobile SDK calls tokenization API directly
  ✓ Merchant receives token (tok_abc123) — useless to attackers
  ✓ PAN lives only in the Card Vault (isolated, audited zone)
  ✓ CVV is DELETED immediately after authorization check — never stored
```

#### Card Vault Architecture (PCI Isolated Zone)

```
Physical/logical separation:
  Network: Card Vault in its own VPC with NO public internet access
  Access: Only authorized internal services can call Vault API
          (Payment Orchestrator only; no other service)
  Egress:  No egress from Vault to internet (zero exfiltration path)
  Logging: Every PAN access logged with requester identity + purpose
  Key rotation: Encryption keys rotated quarterly (automated via AWS KMS / HSM)

Tokenization flow:
  1. Browser calls: POST /v1/tokens with PAN + exp + CVV
  2. Tokenization Service (in PCI Zone):
     a. Validate Luhn checksum on PAN
     b. CVV validated against card network (as part of auth flow)
     c. Generate cryptographically random token: tok_{UUID}
     d. Encrypt PAN with AES-256-GCM:
          key = KMS.getKey(keyId="pan-encryption-key-v3")
          encrypted_pan = AES_GCM_encrypt(PAN, key, nonce=random_nonce)
     e. Store in vault_cards table:
          INSERT INTO vault_cards (
            token, encrypted_pan, nonce, key_version,
            last4, bin, brand, exp_month, exp_year,
            fingerprint = SHA256(PAN + SECRET_SALT)  -- for dedup without decrypting
          )
     f. DISCARD CVV immediately — never written to disk/log
     g. Return: { token, last4, brand, fingerprint }

  3. Merchant receives only the token
  4. Merchant stores token in their DB (safe — worthless without vault access)

De-tokenization (only during authorization):
  1. Payment Orchestrator sends: Vault.decrypt(token) → PAN + metadata
  2. Vault adds this access to immutable audit log:
       { token, requester='payment-orchestrator', purpose='authorization',
         transaction_id='txn_xyz', timestamp, requesting_user/service }
  3. PAN returned to Payment Orchestrator in memory ONLY (not logged)
  4. PAN used to build ISO 8583 message → sent to card network
  5. PAN zeroed out from memory after message sent

HSM (Hardware Security Module):
  Physical tamper-proof device for key management
  Keys never exist in software — only inside HSM
  HSM performs encrypt/decrypt operations; keys never leave HSM
  Dual-control: two authorized people required to change keys
  Examples: AWS CloudHSM, nCipher nShield, Thales Luna

Key rotation:
  Old encrypted PANs become inaccessible when key is rotated
  Solution: Re-encryption before rotation
    Background job: decrypt with old key → encrypt with new key → store
    Done during low-traffic window; no downtime
```

---

### 5.4 Fraud Detection Engine

#### Multi-Layer Fraud Defense

```
Layer 1: Hard Rules (< 1ms, inline)
  Applied before even touching card network:
  
  Rule: block if amount > $10,000 AND first transaction from this card
  Rule: block if velocity > 5 transactions in last 60 seconds (per card)
  Rule: block if card is on global blocklist (known stolen cards)
  Rule: block if merchant is in high-risk category AND CVV mismatch
  Rule: block if IP address in blocklist (known fraud IPs)
  
  Implementation: Redis for velocity checks + blocklist lookups
    INCR velocity:{card_fingerprint}:{minute_bucket} EX 60
    SISMEMBER global_blocklist {card_fingerprint}
  → All in < 1ms (Redis in-memory)

Layer 2: ML Scoring Model (< 150ms, parallel with auth)
  Features computed in real-time:
  
  Transaction features:
    amount, currency, merchant_category_code, time_of_day, day_of_week
    
  Cardholder behavior features:
    avg_transaction_amount_30d, transaction_count_24h
    distinct_merchants_7d, distinct_countries_7d
    amount_deviation_from_mean (z-score)
    
  Device/network features:
    ip_address, device_fingerprint, browser_user_agent
    ip_country vs card_country (mismatch = suspicious)
    vpn/proxy/TOR detection
    
  Merchant features:
    merchant_fraud_rate_30d, merchant_category, chargeback_rate
    
  Model: Gradient Boosted Trees (LightGBM or XGBoost)
    Training data: 5B historical transactions labeled fraud/not-fraud
    Inference: batch feature retrieval from Redis Feature Store → model → score
    Output: fraud_score in [0, 1]
    Threshold: > 0.9 = BLOCK; 0.7-0.9 = 3DS challenge; < 0.7 = proceed

Layer 3: 3D Secure Challenge (user-interactive, 5-15s)
  For fraud_score 0.7-0.9 OR high-value transactions OR first-time card:
    1. Gateway returns status="requires_action" with 3DS URL
    2. Cardholder redirected to their bank's 3DS page
    3. OTP sent to registered mobile → cardholder enters OTP
    4. Bank confirms → redirect back to merchant with 3DS success code
    5. Gateway resumes authorization with 3DS verification data
    → 3DS shifts fraud liability from merchant to issuer
    → Issuer now responsible if fraud occurs after 3DS challenge

Layer 4: Post-Authorization Fraud Monitoring (async, < 30min)
  Even approved transactions monitored continuously:
    If fraud signals emerge AFTER authorization (pattern across multiple merchants):
    → Trigger immediate card block with Issuer notification
    → Initiate chargeback on merchant's behalf (if card is confirmed compromised)
```

#### Feature Store Architecture

```
ML features must be served in < 50ms for inline scoring:

Real-time features (from Redis):
  Key: fraud_features:{card_fingerprint}
  Value: {
    txn_count_1h:    12,
    txn_count_24h:   34,
    txn_amount_24h:  4521.50,
    distinct_ip_24h: 3,
    last_country:    "IN",
    last_txn_ts:     1711963100
  }
  Updated: on every transaction event (Kafka consumer → Redis HMSET)
  Expiry: TTL 7 days (no transactions in 7 days → cold start → use global avg)

Historical features (from Cassandra, precomputed):
  Key: (card_fingerprint, date)
  Value: {avg_7d, std_7d, merchant_set_7d, ...}
  Updated: nightly batch job aggregating previous day's transactions
  Served: < 5ms Cassandra read (partition by card_fingerprint)

Model serving:
  Flask/FastAPI ML server with model loaded in memory
  TF Serving / Triton for GPU-accelerated inference (if neural model)
  Feature pipeline: Redis (real-time) + Cassandra (historical) → feature vector → model → score
  P99 inference: < 100ms including feature retrieval
```

---

### 5.5 Settlement, Reconciliation & Chargebacks

#### Settlement Process (Daily Batch)

```
Why settlement is separate from authorization:
  Authorization = permission to move money (instant)
  Settlement = actual movement of money (batch, takes time)
  
  This gap (auth-to-settle) exists because:
    Banks need to batch-net their positions (reduce actual fund movements)
    Visa/MC net trillions of transactions → only net amounts move
    Reduces systemic risk and settlement cost

Daily Settlement Flow:
  4:00 PM IST: Merchant batch submission deadline
  5:00 PM IST: Our settlement service aggregates:
    SELECT * FROM transactions 
    WHERE status='CAPTURED' 
    AND captured_at::DATE = TODAY 
    AND settlement_submitted = false

  Settlement file (ISO 8583 batch / NACHA ACH file):
    Group by acquirer:
      HDFC Acquirer batch: 150,000 transactions, net ₹4.2 Cr
      Citibank Acquirer: 80,000 transactions, net ₹2.1 Cr
      
  Visa processes:
    Nets all payments against all refunds for each acquirer
    Net position: [HDFC_Acquirer_inflow] - [HDFC_Acquirer_refunds] = NET
    
  T+1 morning: Fund transfer
    Visa → Acquirer: NET amount per acquirer (via RTGS/NEFT)
    Acquirer → Merchant: NET amount minus fees (2% MDR)
    
  T+1 afternoon: Reconciliation
    Settlement Service receives settlement confirmation file
    Reconcile: every captured transaction should appear in file
    If missing: dispute with acquirer
    Mark matched transactions: status = SETTLED

Merchant Discount Rate (MDR) accounting:
  Transaction: ₹1000
  Breakdown:
    Interchange fee (to Issuer):      ₹17.5 (1.75% — issuer earns this)
    Network fee (to Visa):            ₹2.0  (0.20%)
    Acquirer fee:                     ₹2.5  (0.25%)
    Gateway fee (our fee):            ₹8.0  (0.80%)
    ─────────────────────────────────────────
    Total MDR:                       ₹30.0  (3.00%)
    Merchant receives:              ₹970.0
```

#### Chargeback Handling

```
Chargeback: Cardholder tells their bank "I didn't authorize this charge."
  Bank reverses the charge, debiting the merchant.
  Merchant has 30-45 days to contest (provide evidence).

Chargeback reasons (reason codes):
  4853: Cardholder Dispute (goods not received)
  4837: No Cardholder Authorization (fraud)
  4831: Incorrect Transaction Amount
  4834: Duplicate Transaction

System flow on chargeback receipt:
  1. Issuer notifies Visa → Visa notifies Acquirer → Acquirer sends us chargeback file
  2. Chargeback Service:
       UPDATE transactions SET status='DISPUTED', chargeback_amount=?, chargeback_reason=?
       FREEZE merchant_account: hold equivalent amount from next settlement
       Notify merchant via webhook + email
       
  3. Evidence collection (automated):
       Compile evidence package:
         - Authorization approval record (auth_code, timestamp)
         - 3D Secure authentication proof (if applicable)
         - Delivery confirmation from logistics system (for physical goods)
         - Signed terms of service with customer agreement
         - IP address + device fingerprint at time of purchase
         - Previous successful transactions from same customer
         
  4. Submit evidence within deadline (typically Day 20 of chargeback):
       POST acquirer_api/chargebacks/{id}/evidence with evidence_package
       
  5. Outcome (Day 30-45):
       Merchant wins → CHARGEBACK_WON: frozen funds released, status='SETTLED'
       Merchant loses → CHARGEBACK_LOST: funds permanently debited
       
Chargeback rate monitoring:
  Visa threshold: > 1% chargeback rate → "Excessive Chargeback Program"
  > 2%: merchant account terminated
  Alert: if merchant's 30-day chargeback rate > 0.8%: warning notification
  → Trigger: improved fraud detection for that merchant; possibly require 3DS for all
```

---

## 6. Scale & Resilience

### Database Architecture for Financial Data

```
Primary DB: PostgreSQL (strong ACID for financial records)

Schema design:
  transactions:    Append-mostly; never UPDATE amount (re-insert with new status)
  authorization_log: Immutable log of every network message sent/received
  settlement_records: One record per settled batch
  chargeback_log: Full history of disputes

Immutability enforcement:
  Trigger: BEFORE UPDATE ON transactions
    IF OLD.amount != NEW.amount: RAISE EXCEPTION 'Amount is immutable'
    IF OLD.created_at != NEW.created_at: RAISE EXCEPTION
  → Only status, timestamps, settlement fields can change, never core financial data

Replication:
  1 Primary + 2 Read Replicas (streaming replication)
  Writes: always to Primary only
  Reads: dashboards, reconciliation jobs use Read Replicas
  
  Multi-region standby (warm standby):
    Primary: Mumbai (ap-south-1)
    Warm standby: Singapore (ap-southeast-1) — replication lag < 500ms
    Failover: DNS cutover in < 30s (RTO); RPO < 1 second

Sharding (for scale beyond single PostgreSQL):
  Shard by merchant_id (natural business unit)
  All transactions for merchant in one shard (keeps merchant rollups fast)
  Use Citus (distributed PostgreSQL) or manual sharding via application-level routing
  
Write-Ahead Log (WAL):
  Every financial transaction persisted to WAL before acknowledgment
  WAL archived to S3 every 5 minutes (point-in-time recovery)
  Recovery: restore from S3 backup + replay WAL to exact point in time
  RPO: < 5 minutes for catastrophic failure (all data lost scenario)
```

### High Availability for 99.999% Uptime

```
Five nines = 5 minutes and 16 seconds downtime per YEAR.

Active-Active multi-region:
  Region A (Primary, Mumbai):    60% traffic
  Region B (Secondary, Chennai): 40% traffic
  
  Session routing: user's payment tied to one region for its lifecycle
    → No cross-region in-flight transactions (avoids split-brain)
  
  Failover:
    Region A health check fails → Route 100% to Region B in < 30s
    In-progress transactions in Region A:
      → Check idempotency keys in Region B (replicated via async log)
      → If completed: return cached result
      → If in-progress: wait 30s → retry against card network with same merchant_ref

Circuit Breaker per Acquirer/Card Network connection:
  States: CLOSED (normal) → OPEN (failure) → HALF_OPEN (testing)
  
  CLOSED (normal operation):
    Each call to Visa/MC tracked; if error rate > 50% in 60s → OPEN
  OPEN (failing fast):
    Don't send requests to failed acquirer
    Instead: route to backup acquirer or queue for retry
    Reset timer: 30s before trying HALF_OPEN
  HALF_OPEN:
    Allow 1 test request: if succeeds → CLOSED; if fails → OPEN again

Backup acquirer routing:
  Primary: HDFC Acquirer
  Fallback: Citibank Acquirer (if HDFC circuit OPEN)
  Fallback 2: Direct Visa acquiring (our own acquirer license for critical path)
  
  Routing decision:
    routing_table = { visa: [HDFC, Citi, Direct], mastercard: [ICICI, HDFC] }
    On each auth request: pick first healthy acquirer from list

Timeout handling:
  Card network timeout (no response in 5s):
    → Mark transaction as TIMEOUT_PENDING
    → Query card network for transaction status (ISO 8583 0400 - Reversal Advice)
    → If network says APPROVED: complete the transaction
    → If network says DECLINED or UNKNOWN: VOID; return failure to merchant
    → This "timeout resolution" is critical for not double-charging
```

---

## 7. Trade-offs & Alternatives

| Decision | Choice | Alternative | Reason |
|---|---|---|---|
| **DB for transactions** | PostgreSQL (ACID) | Cassandra | Financial data MUST have ACID; no eventually-consistent ledger |
| **Idempotency** | Redis SETNX + DB unique constraint | DB only | Redis: fast (< 1ms) first-line defense; DB: durable backup; both needed |
| **Tokenization** | Vault with HSM + AES-256 | Tokenization-as-a-service (Basis Theory) | Own vault: full control, lower cost at scale; SaaS: faster compliance, higher cost |
| **Fraud detection** | Inline ML (< 150ms) + async | Batch fraud review only | Real-time: block fraud before charge; batch: catch fraud but merchant already charged |
| **Settlement** | Daily batch (T+1) | Real-time settlement | T+1: industry standard; real-time: technically possible but expensive; no ecosystem support |
| **State machine** | Enforced at DB trigger level | Application code only | DB trigger: cannot be bypassed by buggy code; application: can have race conditions |
| **Multi-region** | Active-Active (dual write) | Active-Passive | Active-Active: 40% less latency for secondary region; Active-Passive: simpler but all traffic through primary |
| **Card network protocol** | ISO 8583 | REST API | ISO 8583: industry standard; all networks use it; REST not supported by legacy networks |

### What If We Need to Scale Further?

```
Current design handles: 1,000 TPS (60,000 TPM)

To reach 10,000 TPS:
  1. Read replicas for all non-write DB paths
  2. Redis Cluster for idempotency + velocity (from single node)
  3. Kafka for async decoupling of fraud scoring (parallel, not sequential)
  4. Multiple acquirer connections with intelligent routing
  5. Citus distributed PostgreSQL for transaction sharding

To reach 100,000 TPS (Visa/MC scale):
  This is the actual Visa VisaNet scale
  Custom ASIC hardware for ISO 8583 processing
  Dedicated fiber leased lines to all major issuing banks
  In-memory database (RAMCloud, VoltDB) for authorization table
  Precomputed routing tables updated in microseconds
```

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Knew payment terminology: acquirer, issuer, card network, PAN, BIN
  [ ] Asked authorization vs capture distinction (two-phase)
  [ ] Addressed 3DS upfront (not as afterthought)
  [ ] Mentioned PCI DSS as a hard constraint before designing storage
  [ ] Distinguished settlement (batch T+1) from authorization (real-time)

CAPACITY
  [ ] 167 TPS sustained; 835 TPS peak
  [ ] Latency budget: 2000ms broken down by component (50+10+150+50+800+500+...)
  [ ] Card vault: 50M cards × 512B = 25.6 GB (tiny — often surprises interviewers)
  [ ] 72.8 TB data over 7 years × 3× = 220 TB total

ARCHITECTURE
  [ ] Explained full payment flow: Merchant → Gateway → Acquirer → Card Network → Issuer
  [ ] ISO 8583 protocol (showed knowledge of industry standard)
  [ ] PCI Isolated Zone for Card Vault (separate VPC, no internet egress)
  [ ] 3DS redirect flow for high-risk transactions
  [ ] Async webhook for merchant notification (not synchronous)

DEEP DIVES

  Authorization:
    [ ] Full latency trace (T+0ms to T+980ms)
    [ ] ISO 8583 message structure (DE2, DE4, DE39, DE38)
    [ ] Auth vs Capture: hotel use case, 30-day hold, void/reversal
    [ ] Timeout resolution: 0400 Reversal Advice to check network status

  Idempotency:
    [ ] "Exactly-once is THE most important correctness concern" (said proactively)
    [ ] 3-layer: Redis SETNX → DB unique constraint → Card network merchant_ref
    [ ] Transaction state machine with DB-level trigger enforcement
    [ ] Timeout resolution FSM (PROCESSING → check DB → void or confirm)

  Tokenization / PCI DSS:
    [ ] PCI rules: no CVV storage, PAN encrypted/truncated, last4 only in clear
    [ ] Browser SDK calls vault directly (PAN never hits merchant server)
    [ ] HSM for key management (keys never in software)
    [ ] Immutable audit log for every PAN access
    [ ] Key rotation: re-encryption job during low-traffic window

  Fraud Detection:
    [ ] 4-layer defense: hard rules → ML model → 3DS → post-auth monitoring
    [ ] Velocity checks in Redis (< 1ms)
    [ ] Feature store: Redis (real-time) + Cassandra (historical computed)
    [ ] 3DS shifts liability from merchant to issuer
    [ ] Fraud score thresholds: > 0.9 block, 0.7-0.9 3DS, < 0.7 proceed

  Settlement / Chargebacks:
    [ ] T+1 batch: captured transactions → settlement file → fund transfer → reconcile
    [ ] MDR breakdown: interchange + network + acquirer + gateway fees
    [ ] Chargeback FSM: DISPUTED → CHARGEBACK_WON / CHARGEBACK_LOST
    [ ] Automated evidence compilation: auth record + 3DS proof + delivery + IP
    [ ] Chargeback rate monitoring: 1% = warning, 2% = account termination

SCALE & RESILIENCE
  [ ] PostgreSQL: ACID, immutability triggers, append-only transactions
  [ ] Multi-region Active-Active: < 30s failover, RPO < 1s
  [ ] Circuit breaker per acquirer: CLOSED → OPEN → HALF_OPEN
  [ ] Backup acquirer routing (never single point of failure to card network)
  [ ] 7-year retention (financial regulation): WAL + S3 PITR

TRADE-OFFS
  [ ] PostgreSQL vs Cassandra (ACID requirement = no NoSQL for ledger)
  [ ] Inline fraud vs async (real-time blocking vs post-charge detection)
  [ ] Own vault vs tokenization SaaS (cost vs compliance speed)
  [ ] Active-Active vs Active-Passive (latency vs simplicity)
```
