# Manual Test Guide — Service Virtualization Platform

```
mvn spring-boot:run
Base URL: http://localhost:8080
```

## Virtual Endpoint Contract

Every test goes through **`POST /api/v1/virtual`** (or the transparent proxy).

| Field | Type | Notes |
|---|---|---|
| `session_id` | string | **Mandatory.** Absent → HTTP 400. Isolates all state. |
| `protocol` | string | `HTTP`, `GRPC`, or `KAFKA` |
| `method` | string | HTTP verb, gRPC method name, or Kafka topic |
| `path` | string | URL path for HTTP; empty string for gRPC/Kafka |
| `headers` | object | Key-value string map |
| `body` | object | Request payload |

**Response envelope** (`status_code` holds the stub's logical code — transport HTTP is always 200):

```json
{
  "matched_stub_id":      "stub_account_create_v1",
  "status_code":          201,
  "headers":              {"Content-Type": "application/json"},
  "body":                 { ... },
  "state_mutated":        true,
  "execution_latency_ms": 2.1,
  "matched_rules":        ["path_pattern", "jsonpath_body_check"]
}
```

---

## 0. KEY CONCEPT — Same Path + Path Param + Different Body → Different Response

> **This is the core routing demonstration.** Two stubs share the **same path pattern** with a
> `{id}` wildcard. They differ only in one body field value. The virtual API routes each
> request to its matching stub and returns a completely different response.

### Setup — register two stubs at `/api/v1/orders/{id}/action`

Same path, same method, same `{id}` wildcard — but `$.action` value differs.

```bash
# ── Stub A: action=SHIP ──────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_order_ship",
    "requestMatcher": {
      "protocol": "HTTP",
      "method":   "POST",
      "pathPattern": "/api/v1/orders/{id}/action",
      "headers":  {},
      "bodyJsonPath": {"$.action": "SHIP"}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {"Content-Type": "application/json"},
      "body": {
        "order_id":   "{{request.pathVar.id}}",
        "result":     "SHIPPED",
        "message":    "Order {{request.pathVar.id}} dispatched successfully",
        "tracking":   "TRK-9001",
        "carrier":    "FEDEX"
      }
    }
  }' | jq '{registered: .stubId, status: .status}'
```

```bash
# ── Stub B: action=CANCEL ─────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_order_cancel",
    "requestMatcher": {
      "protocol": "HTTP",
      "method":   "POST",
      "pathPattern": "/api/v1/orders/{id}/action",
      "headers":  {},
      "bodyJsonPath": {"$.action": "CANCEL"}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {"Content-Type": "application/json"},
      "body": {
        "order_id":   "{{request.pathVar.id}}",
        "result":     "CANCELLED",
        "message":    "Order {{request.pathVar.id}} has been cancelled",
        "refund_id":  "REF-5001",
        "refund_eta": "3-5 business days"
      }
    }
  }' | jq '{registered: .stubId, status: .status}'
```

Both stubs coexist. Same Trie node (`{id}` wildcard), specificity = 10 each, different `bodyJsonPath` value → independent routing.

---

### Virtual API Call 1 — `action=SHIP` with order `ORD-9001`

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "demo",
    "protocol":   "HTTP",
    "method":     "POST",
    "path":       "/api/v1/orders/ORD-9001/action",
    "headers":    {},
    "body":       {"action": "SHIP", "warehouse": "NYC"}
  }' | jq .
```

**Expected:**
```json
{
  "matched_stub_id": "stub_order_ship",
  "status_code":     200,
  "body": {
    "order_id": "ORD-9001",
    "result":   "SHIPPED",
    "message":  "Order ORD-9001 dispatched successfully",
    "tracking": "TRK-9001",
    "carrier":  "FEDEX"
  },
  "matched_rules": ["path_pattern", "jsonpath_body_check"]
}
```

> `matched_rules: ["path_pattern", "jsonpath_body_check"]` confirms BOTH the Trie wildcard AND the `$.action=SHIP` body condition were evaluated.
> `order_id: "ORD-9001"` — the actual path value was captured from `{id}` and echoed via `{{request.pathVar.id}}`.

---

### Virtual API Call 2 — `action=CANCEL` with the SAME order `ORD-9001`

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "demo",
    "protocol":   "HTTP",
    "method":     "POST",
    "path":       "/api/v1/orders/ORD-9001/action",
    "headers":    {},
    "body":       {"action": "CANCEL", "reason": "Customer request"}
  }' | jq .
```

**Expected:**
```json
{
  "matched_stub_id": "stub_order_cancel",
  "status_code":     200,
  "body": {
    "order_id":   "ORD-9001",
    "result":     "CANCELLED",
    "message":    "Order ORD-9001 has been cancelled",
    "refund_id":  "REF-5001",
    "refund_eta": "3-5 business days"
  },
  "matched_rules": ["path_pattern", "jsonpath_body_check"]
}
```

> Same path `/api/v1/orders/ORD-9001/action`, same session — but `$.action=CANCEL` → routes to `stub_order_cancel` → completely different response.

---

### Virtual API Call 3 — `action=SHIP` with a DIFFERENT order ID

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "demo",
    "protocol":   "HTTP",
    "method":     "POST",
    "path":       "/api/v1/orders/ORD-XYZ-9999/action",
    "headers":    {},
    "body":       {"action": "SHIP", "warehouse": "LAX"}
  }' | jq '{matched: .matched_stub_id, order_id: .body.order_id, result: .body.result}'
```

**Expected:**
```json
{ "matched": "stub_order_ship", "order_id": "ORD-XYZ-9999", "result": "SHIPPED" }
```

> `{id}` wildcard matches **any** value — `ORD-9001`, `ORD-XYZ-9999`, `12345`, anything.
> The captured value is injected into `order_id` and `message` via `{{request.pathVar.id}}`.

---

### Virtual API Call 4 — unknown `action` → 404

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "demo",
    "protocol":   "HTTP",
    "method":     "POST",
    "path":       "/api/v1/orders/ORD-9001/action",
    "headers":    {},
    "body":       {"action": "RETURN"}
  }' | jq '{status: .status_code, matched: .matched_stub_id}'
```

**Expected:**
```json
{ "status": 404, "matched": null }
```

> `$.action=RETURN` matches neither `SHIP` nor `CANCEL` body condition → no stub selected → 404.

---

### What just happened — routing diagram

```
POST /api/v1/orders/{id}/action
              │
              ▼
         Trie lookup
         matches {id} wildcard
         pathVars["id"] = "ORD-9001"
              │
              ▼
         Candidates (both stubs at same Trie node):
           [stub_order_ship  (spec=10, $.action=SHIP  )]
           [stub_order_cancel(spec=10, $.action=CANCEL)]
              │
              ├── body={"action":"SHIP"}   → $.action=SHIP   matches → stub_order_ship   → {result:SHIPPED}
              ├── body={"action":"CANCEL"} → $.action=CANCEL matches → stub_order_cancel  → {result:CANCELLED}
              └── body={"action":"RETURN"} → neither matches → 404
```

---

## 1. Seed Stubs (auto-loaded at startup from `seed_stubs.json`)

Verify the 26 seed stubs are loaded before the first request:

```bash
curl -s http://localhost:8080/api/v1/stubs | jq 'length'
# Expected: 26
```

---

### 1a. POST /api/v1/accounts — USD account (specificity = 10)

**What the stub does:** Matches any POST to `/api/v1/accounts` where `$.currency = "USD"`.
Writes the created account to session state so a later GET can read it back.

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "s1",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {},
    "body": {"account_holder": "Jane Doe", "initial_deposit": 500.0, "currency": "USD"}
  }' | jq '{matched: .matched_stub_id, status: .status_code, id: .body.account_id, holder: .body.account_holder, state_mutated}'
```

**Expected:**
```json
{
  "matched":       "stub_account_create_v1",
  "status":        201,
  "id":            "ACC-0001",
  "holder":        "Jane Doe",
  "state_mutated": true
}
```

> `account_holder` and `balance` come from request body templates; `account_id` from `{{generate.id:ACC}}`.

---

### 1b. POST /api/v1/accounts — SAVINGS account wins over plain USD (specificity = 20 > 10)

**What the stub does:** Matches when BOTH `$.currency=USD` AND `$.type=SAVINGS`. Specificity 20 → sorted before the plain USD stub (specificity 10), so it fires first.

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "s2",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {},
    "body": {"account_holder": "Saver", "initial_deposit": 2000.0, "currency": "USD", "type": "SAVINGS"}
  }' | jq '{matched: .matched_stub_id, type: .body.type, status: .status_code}'
```

**Expected:** `{ "matched": "stub_savings_account_create_v1", "type": "SAVINGS", "status": 201 }`

---

### 1c. POST /api/v1/accounts — EUR account

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "s3",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {},
    "body": {"account_holder": "Klaus", "initial_deposit": 1000.0, "currency": "EUR"}
  }' | jq '{matched: .matched_stub_id, currency: .body.currency}'
```

**Expected:** `{ "matched": "stub_account_create_eur_v1", "currency": "EUR" }`

---

### 1d. GET /api/v1/accounts/{id} — path param → state READ → session isolation

**How path params work:** Stub pathPattern `/api/v1/accounts/{id}` stores a Trie wildcard node.
When the virtual API receives `/api/v1/accounts/ACC-0001`, the Trie matches the `{id}` wildcard and captures `pathVars["id"] = "ACC-0001"`.
The stateAction READ key `{{request.pathVar.id}}` resolves to `"ACC-0001"` and looks it up in session state.

```bash
# Step 1 — create account (session A)
ACCT=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "sess-A",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "Alice", "currency": "USD", "initial_deposit": 100.0}
  }' | jq -r '.body.account_id')
echo "Created: $ACCT"

# Step 2 — GET same session → 200
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"sess-A\",
    \"protocol\": \"HTTP\", \"method\": \"GET\",
    \"path\": \"/api/v1/accounts/$ACCT\",
    \"headers\": {}, \"body\": {}
  }" | jq '{matched: .matched_stub_id, status: .status_code, holder: .body.account_holder}'
# Expected: { "matched": "stub_account_get_v1", "status": 200, "holder": "Alice" }

# Step 3 — GET DIFFERENT session → 404 (session B has no state)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"sess-B\",
    \"protocol\": \"HTTP\", \"method\": \"GET\",
    \"path\": \"/api/v1/accounts/$ACCT\",
    \"headers\": {}, \"body\": {}
  }" | jq '{matched: .matched_stub_id, status: .status_code}'
# Expected: { "matched": "stub_account_not_found", "status": 404 }
```

---

### 1e. POST /api/v1/payments + async Kafka event

**What the stub does:** Creates payment AND fires async event to `payment-events` Kafka topic after 500ms.

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "pay-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/payments",
    "headers": {}, "body": {"amount": 250.0, "currency": "USD"}
  }' | jq '{matched: .matched_stub_id, status: .status_code, payment_id: .body.payment_id, state_mutated}'
# Expected: { "matched": "stub_payment_create_v1", "status": 201, "payment_id": "PAY-0001", "state_mutated": true }

# Check async Kafka event written to session state (~600ms later)
sleep 1
curl -s http://localhost:8080/api/v1/sessions/pay-sess/state | jq '.collections["events:payment-events"]'
# Expected: Kafka event entry in session state
```

---

### 1f. POST/GET /api/v1/transfers — full stateful round trip

```bash
TXF=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "txf-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/transfers",
    "headers": {}, "body": {"from_account": "ACC-001", "to_account": "ACC-002", "amount": 500.0}
  }' | jq -r '.body.transfer_id')
echo "Transfer: $TXF"

curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"txf-sess\",
    \"protocol\": \"HTTP\", \"method\": \"GET\",
    \"path\": \"/api/v1/transfers/$TXF\",
    \"headers\": {}, \"body\": {}
  }" | jq '{matched: .matched_stub_id, status: .status_code, from: .body.from_account}'
# Expected: { "matched": "stub_transfer_get_v1", "status": 200, "from": "ACC-001" }
```

---

### 1g. gRPC stubs — CreateAccount, GetBalance, ProcessPayment

```bash
# CreateAccount
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "g1",
    "protocol": "GRPC", "method": "CreateAccount", "path": "",
    "headers": {}, "body": {"account_holder": "gRPC User", "currency": "USD"}
  }' | jq '{matched: .matched_stub_id, status: .status_code, holder: .body.account_holder}'
# Expected: { "matched": "stub_grpc_create_account", "status": 200, "holder": "gRPC User" }

# GetBalance
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "g2",
    "protocol": "GRPC", "method": "GetBalance", "path": "",
    "headers": {}, "body": {"account_id": "ACC-9999"}
  }' | jq '{matched: .matched_stub_id, balance: .body.balance, currency: .body.currency}'
# Expected: { "matched": "stub_grpc_get_balance_v1", "balance": 1000.0, "currency": "USD" }

# ProcessPayment
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "g3",
    "protocol": "GRPC", "method": "ProcessPayment", "path": "",
    "headers": {}, "body": {"amount": 150.0, "currency": "USD", "account_id": "ACC-001"}
  }' | jq '{matched: .matched_stub_id, payment_id: .body.payment_id}'
# Expected: { "matched": "stub_grpc_process_payment_v1", "payment_id": "PAY-0001" }
```

---

### 1h. Kafka stubs — payment-events, account.events

```bash
# payment-events (body condition $.event_type=PAYMENT_COMPLETED, specificity=10)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "k1",
    "protocol": "KAFKA", "method": "payment-events", "path": "",
    "headers": {}, "body": {"event_type": "PAYMENT_COMPLETED", "payment_id": "PAY-001", "amount": 150.0}
  }' | jq '{matched: .matched_stub_id, ack: .body.ack}'
# Expected: { "matched": "stub_kafka_payment_completed", "ack": true }

# account.events (no body condition, specificity=0)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "k2",
    "protocol": "KAFKA", "method": "account.events", "path": "",
    "headers": {}, "body": {"event_type": "ACCOUNT_CREATED", "account_id": "ACC-5678"}
  }' | jq '{matched: .matched_stub_id}'
# Expected: { "matched": "stub_kafka_account_event_v1" }
```

---

## Stub Override Rules

| Scenario | Behavior |
|---|---|
| Re-register with **same `stubId`** | Replaces existing stub (self-update) |
| Register with **different `stubId`**, any matcher | Coexists alongside existing stubs (never auto-replaced) |
| Same path/method, **different `bodyJsonPath`** | Both coexist, routed by specificity |
| Same path/method, **same `bodyJsonPath`** | Both coexist (use same `stubId` to truly replace) |

**Key insight:** Use the **same stubId** to update a stub. Use **different stubIds** to register multiple stubs at the same path (e.g. READ stub + 404 fallback, or USD stub + EUR stub).

## Stub Naming Convention

```
stub_{resource}_{operation}_{variant}_{version}
```

| Pattern | Examples |
|---|---|
| HTTP | `stub_account_create_usd_v1`, `stub_order_get_v1`, `stub_transfer_create_v1` |
| gRPC | `stub_grpc_createaccount_v1`, `stub_grpc_getbalance_urgent_v1` |
| Kafka | `stub_kafka_payment_events_completed_v1` |
| Fallback | `stub_account_not_found`, `stub_loan_fallback` |
| Auto-ingest | `auto__api_v1_employees_123__POST_{timestamp}` |

---

## 2. Register HTTP Stubs via API (POST /api/v1/stubs)

> **Rule:** Every stub registration is immediately followed by a virtual API invocation.

---

### 2a. POST with body condition — dynamic echo response

**Register:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_order_express",
    "requestMatcher": {
      "protocol": "HTTP", "method": "POST",
      "pathPattern": "/api/v1/orders",
      "headers": {},
      "bodyJsonPath": {"$.shipping": "EXPRESS"}
    },
    "responseTemplate": {
      "statusCode": 201,
      "headers": {"Content-Type": "application/json"},
      "body": {
        "order_id":  "{{generate.id:ORD}}",
        "item":      "{{request.body.item}}",
        "quantity":  "{{request.body.quantity}}",
        "shipping":  "EXPRESS",
        "status":    "CREATED_EXPRESS"
      }
    },
    "stateAction": {
      "op": "WRITE", "collection": "orders",
      "keyExpr": "{{response.body.order_id}}", "valueExpr": "{{response.body}}"
    }
  }' | jq '{registered: .stubId, status: .status}'
# Expected: { "registered": "stub_order_express", "status": "registered" }
```

**Invoke — matches because `$.shipping = EXPRESS`:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "order-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/orders",
    "headers": {},
    "body": {"item": "Widget Pro", "quantity": 2, "shipping": "EXPRESS"}
  }' | jq '{matched: .matched_stub_id, status: .status_code, order_id: .body.order_id, item: .body.item, quantity: .body.quantity}'
```

**Expected:**
```json
{
  "matched":   "stub_order_express",
  "status":    201,
  "order_id":  "ORD-0001",
  "item":      "Widget Pro",
  "quantity":  2,
  "state_mutated": true
}
```

**Invoke — does NOT match (shipping = STANDARD, not EXPRESS) → 404:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "order-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/orders",
    "headers": {}, "body": {"item": "Widget", "shipping": "STANDARD"}
  }' | jq '.status_code'
# Expected: 404
```

---

### 2b. POST with header condition (specificity = 5)

**Register:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_tenant_walmart",
    "requestMatcher": {
      "protocol": "HTTP", "method": "POST",
      "pathPattern": "/api/v1/products",
      "headers": {"X-Tenant": "walmart"},
      "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {"Content-Type": "application/json"},
      "body": {
        "tenant":     "walmart",
        "product_id": "{{generate.id:PRD}}",
        "name":       "{{request.body.name}}"
      }
    }
  }' | jq .stubId
```

**Invoke WITH header → matches (header condition met):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "hdr-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/products",
    "headers": {"X-Tenant": "walmart"},
    "body": {"name": "Blue Widget", "price": 9.99}
  }' | jq '{matched: .matched_stub_id, tenant: .body.tenant, name: .body.name}'
# Expected: { "matched": "stub_tenant_walmart", "tenant": "walmart", "name": "Blue Widget" }
```

**Invoke WITHOUT header → 404 (no other /products stub exists):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "hdr-sess-2",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/products",
    "headers": {},
    "body": {"name": "Blue Widget"}
  }' | jq '.status_code'
# Expected: 404 (header condition fails, no fallback stub for /products)
```

---

### 2c. GET with path param `{id}` — Trie wildcard + stateAction READ

**How the Trie works:**
1. Stub is registered with `pathPattern: "/api/v1/orders/{id}"`
2. Trie stores a **wildcard node** at position 4 (the `{id}` segment)
3. Virtual API receives `"path": "/api/v1/orders/ORD-0001"`
4. Trie walks: `api` → `v1` → `orders` → `ORD-0001` (no exact child) → **wildcard matches!** → captures `pathVars["id"] = "ORD-0001"`
5. `stateAction READ keyExpr = {{request.pathVar.id}}` resolves to `"ORD-0001"` → reads from session state

**Register GET stub:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_order_get",
    "requestMatcher": {
      "protocol": "HTTP", "method": "GET",
      "pathPattern": "/api/v1/orders/{id}",
      "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {"Content-Type": "application/json"},
      "body": "{{state.result}}"
    },
    "stateAction": {
      "op": "READ", "collection": "orders", "keyExpr": "{{request.pathVar.id}}"
    }
  }' | jq .stubId
```

**Register 404 fallback (same path, lower insertion order → fires when READ returns null):**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_order_not_found",
    "requestMatcher": {
      "protocol": "HTTP", "method": "GET",
      "pathPattern": "/api/v1/orders/{id}",
      "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 404,
      "headers": {},
      "body": {"error": "Order not found"}
    }
  }' | jq .stubId
```

**Full round-trip with session isolation:**
```bash
# 1. Create order (uses stub_order_express from 2a)
ORD=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "rt-sess-A",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/orders",
    "headers": {}, "body": {"item": "Gadget", "shipping": "EXPRESS"}
  }' | jq -r '.body.order_id')
echo "Order ID: $ORD"

# 2. GET same session → 200, Trie wildcard matches any ORD-XXXX value
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"rt-sess-A\",
    \"protocol\": \"HTTP\", \"method\": \"GET\",
    \"path\": \"/api/v1/orders/$ORD\",
    \"headers\": {}, \"body\": {}
  }" | jq '{matched: .matched_stub_id, status: .status_code, item: .body.item}'
# Expected: { "matched": "stub_order_get", "status": 200, "item": "Gadget" }

# 3. GET different session → 404 (READ returns null → falls to 404 stub)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"rt-sess-B\",
    \"protocol\": \"HTTP\", \"method\": \"GET\",
    \"path\": \"/api/v1/orders/$ORD\",
    \"headers\": {}, \"body\": {}
  }" | jq '{matched: .matched_stub_id, status: .status_code}'
# Expected: { "matched": "stub_order_not_found", "status": 404 }

# 4. Verify path param echoed in matched_rules
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"rt-sess-A\",
    \"protocol\": \"HTTP\", \"method\": \"GET\",
    \"path\": \"/api/v1/orders/$ORD\",
    \"headers\": {}, \"body\": {}
  }" | jq '.matched_rules'
# Expected includes: ["path_pattern", "state_lookup"]
```

---

### 2d. GET with LITERAL path (no wildcard) — exact Trie match

A GET stub with a fully literal path matches ONLY that exact path — different IDs don't match.

**Register:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_config_v1",
    "requestMatcher": {
      "protocol": "HTTP", "method": "GET",
      "pathPattern": "/api/v1/config/features",
      "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {},
      "body": {"feature_a": true, "feature_b": false, "max_retries": 3}
    }
  }' | jq .stubId
```

**Invoke exact path → 200:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "cfg-sess",
    "protocol": "HTTP", "method": "GET", "path": "/api/v1/config/features",
    "headers": {}, "body": {}
  }' | jq '{status: .status_code, feature_a: .body.feature_a}'
# Expected: { "status": 200, "feature_a": true }
```

**Invoke different path → 404 (literal match only):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "cfg-sess",
    "protocol": "HTTP", "method": "GET", "path": "/api/v1/config/other",
    "headers": {}, "body": {}
  }' | jq '.status_code'
# Expected: 404
```

---

### 2e. Deep nested JSONPath + array index (specificity = 20)

**Register (two body conditions → specificity = 20):**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_gold_electronics",
    "requestMatcher": {
      "protocol": "HTTP", "method": "POST",
      "pathPattern": "/api/v1/checkout",
      "headers": {},
      "bodyJsonPath": {
        "$.customer.tier":      "GOLD",
        "$.items[0].category":  "ELECTRONICS"
      }
    },
    "responseTemplate": {
      "statusCode": 201,
      "headers": {},
      "body": {
        "checkout_id": "{{generate.id:CHK}}",
        "discount":    "15%",
        "tier":        "GOLD"
      }
    }
  }' | jq .stubId
```

**Invoke — BOTH conditions match → 201 with discount:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "chk-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/checkout",
    "headers": {},
    "body": {
      "customer": {"tier": "GOLD", "id": "CUS-001"},
      "items": [
        {"category": "ELECTRONICS", "sku": "PHONE-X", "price": 999.0},
        {"category": "CLOTHING",    "sku": "SHIRT-Y", "price": 29.0}
      ]
    }
  }' | jq '{matched: .matched_stub_id, discount: .body.discount}'
# Expected: { "matched": "stub_gold_electronics", "discount": "15%" }
```

**Invoke — tier is SILVER → condition fails → 404:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "chk-sess-b",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/checkout",
    "headers": {},
    "body": {"customer": {"tier": "SILVER"}, "items": [{"category": "ELECTRONICS"}]}
  }' | jq '.status_code'
# Expected: 404
```

---

### 2f. Specificity ordering — high-specificity wins over low-specificity at same path

```bash
# Register low-specificity fallback first (specificity=0)
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_loan_fallback",
    "requestMatcher": {"protocol": "HTTP", "method": "POST", "pathPattern": "/api/v1/loans", "headers": {}, "bodyJsonPath": {}},
    "responseTemplate": {"statusCode": 200, "headers": {}, "body": {"type": "GENERIC_LOAN"}}
  }' | jq .stubId

# Register high-specificity stub second (specificity=10) — sorts BEFORE the fallback
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_loan_mortgage",
    "requestMatcher": {
      "protocol": "HTTP", "method": "POST", "pathPattern": "/api/v1/loans",
      "headers": {}, "bodyJsonPath": {"$.loan_type": "MORTGAGE"}
    },
    "responseTemplate": {"statusCode": 201, "headers": {}, "body": {"type": "MORTGAGE_LOAN", "rate": "3.5%"}}
  }' | jq .stubId

# Invoke with MORTGAGE → higher specificity stub wins even though registered second
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "loan-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/loans",
    "headers": {}, "body": {"loan_type": "MORTGAGE", "amount": 300000}
  }' | jq '{matched: .matched_stub_id, type: .body.type, rate: .body.rate}'
# Expected: { "matched": "stub_loan_mortgage", "type": "MORTGAGE_LOAN", "rate": "3.5%" }

# Invoke without loan_type → fallback (specificity=0)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "loan-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/loans",
    "headers": {}, "body": {"amount": 10000}
  }' | jq '{matched: .matched_stub_id, type: .body.type}'
# Expected: { "matched": "stub_loan_fallback", "type": "GENERIC_LOAN" }
```

---

## 3. Register gRPC Stubs via API

### 3a. gRPC with request body echo

**Register:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_grpc_get_shipment",
    "requestMatcher": {
      "protocol": "GRPC", "method": "GetShipment",
      "pathPattern": "", "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {"Content-Type": "application/grpc"},
      "body": {
        "shipment_id": "{{request.body.shipment_id}}",
        "status":      "IN_TRANSIT",
        "eta_hours":   48
      }
    }
  }' | jq .stubId
```

**Invoke — shipment_id echoed from request:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "grpc-ship",
    "protocol": "GRPC", "method": "GetShipment", "path": "",
    "headers": {}, "body": {"shipment_id": "SHP-5001", "carrier": "FEDEX"}
  }' | jq '{matched: .matched_stub_id, shipment_id: .body.shipment_id, status: .body.status}'
# Expected: { "matched": "stub_grpc_get_shipment", "shipment_id": "SHP-5001", "status": "IN_TRANSIT" }

# Different shipment_id — same stub matches, different echoed value
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "grpc-ship",
    "protocol": "GRPC", "method": "GetShipment", "path": "",
    "headers": {}, "body": {"shipment_id": "SHP-9999"}
  }' | jq '.body.shipment_id'
# Expected: "SHP-9999"
```

---

### 3b. gRPC with body condition (specificity = 10)

**Register:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_grpc_urgent_shipment",
    "requestMatcher": {
      "protocol": "GRPC", "method": "GetShipment",
      "pathPattern": "", "headers": {},
      "bodyJsonPath": {"$.priority": "URGENT"}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {},
      "body": {"shipment_id": "{{request.body.shipment_id}}", "status": "PRIORITY_DISPATCH", "eta_hours": 2}
    }
  }' | jq .stubId
```

**Invoke with priority=URGENT → higher specificity stub wins:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "grpc-urgent",
    "protocol": "GRPC", "method": "GetShipment", "path": "",
    "headers": {}, "body": {"shipment_id": "SHP-0001", "priority": "URGENT"}
  }' | jq '{matched: .matched_stub_id, status: .body.status, eta: .body.eta_hours}'
# Expected: { "matched": "stub_grpc_urgent_shipment", "status": "PRIORITY_DISPATCH", "eta": 2 }
```

---

## 4. Register Kafka Stubs via API

### 4a. Kafka with body condition

**Register:**
```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_kafka_order_placed",
    "requestMatcher": {
      "protocol": "KAFKA", "method": "order-events",
      "pathPattern": "", "headers": {},
      "bodyJsonPath": {"$.event_type": "ORDER_PLACED"}
    },
    "responseTemplate": {
      "statusCode": 200,
      "headers": {},
      "body": {"ack": true, "event_type": "ORDER_PLACED", "processed": true}
    }
  }' | jq .stubId
```

**Invoke — event_type matches → ack:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "kafka-ord",
    "protocol": "KAFKA", "method": "order-events", "path": "",
    "headers": {}, "body": {"event_type": "ORDER_PLACED", "order_id": "ORD-9001", "amount": 149.99}
  }' | jq '{matched: .matched_stub_id, ack: .body.ack}'
# Expected: { "matched": "stub_kafka_order_placed", "ack": true }

# Different event_type → 404 (condition fails)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "kafka-ord",
    "protocol": "KAFKA", "method": "order-events", "path": "",
    "headers": {}, "body": {"event_type": "ORDER_CANCELLED", "order_id": "ORD-9001"}
  }' | jq '.status_code'
# Expected: 404
```

---

## 5. Record Ingestor (POST /api/v1/recording/ingest)

The ingestor takes **raw captured traffic** (actual values, no expressions), runs it through:
1. `PciPiiSanitizer` — masks card numbers, SSNs, emails
2. `StubGenerator` — creates expressions, body conditions, response templates
3. `ConfidenceCalculator` — confidence ≥ 0.90 → `AUTO_PUBLISHED` (registered immediately)

---

### 5a. Case 1 — POST with literal path → body conditions + dynamic echo

**What happens:**
- Path `/api/v1/employees/123` is literal (no `{}`). Used as exact pathPattern.
- Request body fields (string + numeric) become `bodyJsonPath` conditions.
- Response body fields that also appear in request → `{{request.body.FIELD}}` templates.
- ID-shaped fields → `{{generate.id:PREFIX}}`.

**Ingest:**
```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST",
      "path": "/api/v1/employees/123",
      "headers": {"Content-Type": "application/json"},
      "body": {"name": "Jane Doe", "salary": 95000, "department": "ENGINEERING"}
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {"employee_id": "EMP-0001", "name": "Jane Doe", "salary": 95000, "status": "ACTIVE"}
    }
  }')

echo "=== Ingest result ==="
echo $INGEST | jq '{status: .status, confidence: .confidence}'
echo "=== pathPattern (must be literal /api/v1/employees/123) ==="
echo $INGEST | jq '.generatedStub.requestMatcher.pathPattern'
echo "=== bodyJsonPath conditions (name + salary + department) ==="
echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
echo "=== response body templates ==="
echo $INGEST | jq '.generatedStub.responseTemplate.body'
echo "=== stateAction (WRITE for POST) ==="
echo $INGEST | jq '.generatedStub.stateAction'
```

**Expected:**
```
status: "AUTO_PUBLISHED"  confidence: ≥0.90
pathPattern: "/api/v1/employees/123"
bodyJsonPath: { "$.name": "Jane Doe", "$.salary": "95000", "$.department": "ENGINEERING" }
body: {
  "employee_id": "EMP-0001",           ← STATIC recorded value (record/replay semantics)
  "name":        "{{request.body.name}}",   ← echoed from request (same field in request)
  "salary":      "{{request.body.salary}}", ← echoed from request (same field in request)
  "status":      "ACTIVE"              ← static (only in response)
}
stateAction: { "op": "WRITE", "collection": "employees", ... }
```

> **Record/replay semantics:** Ingest stubs replay ID fields verbatim from the recording — `employee_id: "EMP-0001"` is replayed as `"EMP-0001"` every time. For dynamic IDs per invocation, register a manual stub with explicit `{{generate.id:EMP}}`.

**Invoke the generated stub — must match literal path AND body conditions:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "emp-sess",
    "protocol": "HTTP", "method": "POST",
    "path": "/api/v1/employees/123",
    "headers": {"Content-Type": "application/json"},
    "body": {"name": "Jane Doe", "salary": 95000, "department": "ENGINEERING"}
  }' | jq '{status: .status_code, employee_id: .body.employee_id, name: .body.name, salary: .body.salary}'
# Expected: { "status": 201, "employee_id": "EMP-0001", "name": "Jane Doe", "salary": 95000 }
# employee_id is the STATIC recorded value (EMP-0001), name/salary are echoed from request body
```

**Invoke with wrong path → 404 (literal path, exact match required):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "emp-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/employees/999",
    "headers": {}, "body": {"name": "Jane Doe", "salary": 95000, "department": "ENGINEERING"}
  }' | jq '.status_code'
# Expected: 404 (path /api/v1/employees/999 ≠ /api/v1/employees/123)
```

---

### 5b. Case 2 — GET with `{param}` → Trie wildcard + STATIC recorded response

**What happens:**
- Path `/api/v1/department/{departmentId}` has `{}` → Trie wildcard.
- Wildcard matches **any** real value: `/api/v1/department/434`, `/api/v1/department/999`, etc.
- Response body = **static** values copied verbatim from recording. No `stateAction READ`.

**Ingest:**
```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "GET",
      "path": "/api/v1/department/{departmentId}",
      "headers": {}, "body": {}
    },
    "response": {
      "statusCode": 200, "headers": {},
      "body": {"deptName": "account", "deptId": 123, "headCount": 45}
    }
  }')

echo "=== pathPattern (must keep {departmentId}) ==="
echo $INGEST | jq '.generatedStub.requestMatcher.pathPattern'
echo "=== stateAction (must be null — static replay) ==="
echo $INGEST | jq '.generatedStub.stateAction'
echo "=== static response body ==="
echo $INGEST | jq '.generatedStub.responseTemplate.body'
```

**Expected:**
```
pathPattern: "/api/v1/department/{departmentId}"
stateAction: null
body: { "deptName": "account", "deptId": 123, "headCount": 45 }
```

**Invoke with ANY real departmentId value → same static response:**
```bash
for ID in 434 436 0 DEPT-99 abc; do
  RESULT=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
    -H "Content-Type: application/json" \
    -d "{
      \"session_id\": \"dept-sess\",
      \"protocol\": \"HTTP\", \"method\": \"GET\",
      \"path\": \"/api/v1/department/$ID\",
      \"headers\": {}, \"body\": {}
    }" | jq '{status: .status_code, deptName: .body.deptName, deptId: .body.deptId}')
  echo "deptId=$ID → $RESULT"
done
# Expected for ALL: status=200, deptName="account", deptId=123
```

**Different path depth → 404 (Trie is depth-exact):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "dept-sess",
    "protocol": "HTTP", "method": "GET", "path": "/api/v1/department/434/teams",
    "headers": {}, "body": {}
  }' | jq '.status_code'
# Expected: 404 (extra segment not in stub pathPattern)
```

> **Note:** If you run Case 2 (wildcard) AND Case 3 (literal) in the same session, the wildcard `/api/v1/department/{departmentId}` will match paths like `/999` too. The literal stub at `/234` only "wins" for exactly `/234`. To test Case 3's 404 in isolation, skip registering Case 2.

---

### 5c. Case 3 — GET with literal path segment (no `{}`) → exact Trie match

**What happens:**
- Path `/api/v1/department/234` is literal. No `{}` brackets. Exact Trie match.
- Response is STATIC from recording. No error (old behavior was 400 — now succeeds).
- Only `/api/v1/department/234` matches; `/api/v1/department/999` does NOT.

**Ingest:**
```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "GET",
      "path": "/api/v1/department/234",
      "headers": {}, "body": {}
    },
    "response": {
      "statusCode": 200, "headers": {},
      "body": {"deptName": "engineering", "deptId": 234, "location": "NYC"}
    }
  }')

echo "HTTP status (must be 201 CREATED, not 400):"
echo "=== pathPattern (literal) ===" && echo $INGEST | jq '.generatedStub.requestMatcher.pathPattern'
echo "=== stateAction (null) ===" && echo $INGEST | jq '.generatedStub.stateAction'
echo "=== static body ===" && echo $INGEST | jq '.generatedStub.responseTemplate.body'
```

**Invoke exact path → 200:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "lit-sess",
    "protocol": "HTTP", "method": "GET", "path": "/api/v1/department/234",
    "headers": {}, "body": {}
  }' | jq '{status: .status_code, deptName: .body.deptName, location: .body.location}'
# Expected: { "status": 200, "deptName": "engineering", "location": "NYC" }
```

**Invoke different segment → 404 (literal match only):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "lit-sess",
    "protocol": "HTTP", "method": "GET", "path": "/api/v1/department/999",
    "headers": {}, "body": {}
  }' | jq '.status_code'
# Expected: 404
```

---

### 5d. POST with PCI card number — sanitizer masks before stub generation

**Ingest (card number in request body):**
```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST", "path": "/api/v1/payments",
      "headers": {"Content-Type": "application/json"},
      "body": {"credit_card": "4111111111111111", "cvv": "123", "amount": 250.0, "currency": "USD"}
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {"payment_id": "PAY-9001", "amount": 250.0, "status": "APPROVED"}
    }
  }')

echo "=== PCI fields masked (must be 2: card + cvv) ===" && echo $INGEST | jq '.requestSummary.pciFieldsMasked'
echo "=== Card number NOT in stub (must be false) ===" && echo $INGEST | jq '.generatedStub | tostring | test("4111")'
echo "=== CVV NOT in stub (must be false) ===" && echo $INGEST | jq '.generatedStub | tostring | test("123")'
echo "=== bodyJsonPath (card/cvv excluded, only clean fields) ===" && echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
echo "=== response body templates ===" && echo $INGEST | jq '.generatedStub.responseTemplate.body'
```

**Expected:**
```
pciFieldsMasked: 2
Card in stub: false
CVV in stub: false
bodyJsonPath: { "$.amount": "250.0", "$.currency": "USD" }  ← clean numeric + string fields only
body: {
  "payment_id": "PAY-9001",          ← STATIC recorded value (not in request body)
  "amount": "{{request.body.amount}}", ← echoed (amount IS in request body)
  "status": "APPROVED"               ← static
}
```

> amount=250.0 becomes a bodyJsonPath condition (numeric values included since they're stable).
> credit_card and cvv are excluded from bodyJsonPath (masked by sanitizer).

**Invoke the sanitized stub (no card in request):**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "pci-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/payments",
    "headers": {}, "body": {"amount": 250.0, "currency": "USD"}
  }' | jq '{status: .status_code, payment_id: .body.payment_id, amount: .body.amount}'
# Expected: { "status": 201, "payment_id": "PAY-0001", "amount": 250.0 }
```

---

### 5e. PII sanitization — SSN and email masked

```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST", "path": "/api/v1/users",
      "headers": {},
      "body": {"name": "John Doe", "email": "john.doe@example.com", "ssn": "123-45-6789", "age": 35}
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {"user_id": "USR-0001", "name": "John Doe", "status": "ACTIVE"}
    }
  }')

echo "=== PII masked ===" && echo $INGEST | jq '.requestSummary.piiFieldsMasked'
echo "=== SSN not in stub ===" && echo $INGEST | jq '.generatedStub | tostring | test("123-45-6789")'
echo "=== Email not in stub ===" && echo $INGEST | jq '.generatedStub | tostring | test("example.com")'
echo "=== bodyJsonPath (only clean fields: name, age) ===" && echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
```

---

### 5f. gRPC ingest — auto-generated gRPC stub

```bash
GRPC_INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "GRPC", "method": "CreateShipment",
      "path": "", "headers": {},
      "body": {"order_id": "ORD-001", "warehouse": "NYC", "priority": "HIGH"}
    },
    "response": {
      "statusCode": 200,
      "headers": {"Content-Type": "application/grpc"},
      "body": {"shipment_id": "SHP-7001", "warehouse": "NYC", "status": "DISPATCHED"}
    }
  }')

echo "=== Protocol ===" && echo $GRPC_INGEST | jq '.generatedStub.requestMatcher.protocol'
echo "=== Method ===" && echo $GRPC_INGEST | jq '.generatedStub.requestMatcher.method'
echo "=== Response body templates ===" && echo $GRPC_INGEST | jq '.generatedStub.responseTemplate.body'
```

**Expected:**
```
protocol: "GRPC"
method:   "CreateShipment"
body: {
  "shipment_id": "SHP-7001",             ← STATIC recorded value (not in request body)
  "warehouse":   "{{request.body.warehouse}}",  ← echoed (warehouse IS in request body)
  "status":      "DISPATCHED"            ← static (only in response)
}
```

**Invoke the auto-generated gRPC stub:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "grpc-ingest-sess",
    "protocol": "GRPC", "method": "CreateShipment", "path": "",
    "headers": {}, "body": {"order_id": "ORD-001", "warehouse": "LAX", "priority": "HIGH"}
  }' | jq '{status: .status_code, shipment_id: .body.shipment_id, warehouse: .body.warehouse}'
# Expected: { "status": 200, "shipment_id": "SHP-7001", "warehouse": "LAX" }
# shipment_id is the STATIC recorded value; warehouse is echoed from request
```

---

### 5g. Kafka ingest with PCI data — card masked, stub auto-published

```bash
KAFKA_INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "KAFKA", "method": "payment-notifications",
      "path": "", "headers": {},
      "body": {"credit_card": "5500000000000004", "event_type": "CHARGE", "amount": 99.99}
    },
    "response": {"statusCode": 200, "headers": {}, "body": {"ack": true, "status": "RECEIVED"}}
  }')

echo $KAFKA_INGEST | jq '{status: .status, pci: .requestSummary.pciFieldsMasked, protocol: .generatedStub.requestMatcher.protocol, method: .generatedStub.requestMatcher.method}'
# Expected: { "status": "AUTO_PUBLISHED", "pci": 1, "protocol": "KAFKA", "method": "payment-notifications" }
```

**Invoke the auto-generated Kafka stub:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "kafka-ingest-sess",
    "protocol": "KAFKA", "method": "payment-notifications", "path": "",
    "headers": {}, "body": {"event_type": "CHARGE", "amount": 99.99}
  }' | jq '{matched: .matched_stub_id, ack: .body.ack, status: .body.status}'
# Expected: { "matched": "auto_...", "ack": true, "status": "RECEIVED" }
```

---

## 6. Session Isolation

Each `session_id` has its own independent state store. Stubs (and their path/Trie routing) are **shared** across sessions — only state is isolated.

### 6a. Two sessions create independent accounts — only your own session's data is visible

> **Important:** Per-session ID counters start at 1 independently. Both sessions create `ACC-0001`
> as their first account. To prove isolation, create **two accounts in session A** (gets ACC-0001
> and ACC-0002), then one in session B (gets ACC-0001). Session B cannot read ACC-0002.

```bash
# Session A creates TWO accounts
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{"session_id":"iso-A","protocol":"HTTP","method":"POST","path":"/api/v1/accounts","headers":{},"body":{"account_holder":"Alice","currency":"USD","initial_deposit":1000.0}}' \
  | jq '.body.account_id'  # → "ACC-0001"

ACCT_A2=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{"session_id":"iso-A","protocol":"HTTP","method":"POST","path":"/api/v1/accounts","headers":{},"body":{"account_holder":"Alice2","currency":"USD","initial_deposit":200.0}}' \
  | jq -r '.body.account_id')
echo "Session A second account: $ACCT_A2"  # → ACC-0002

# Session B creates ONE account → also gets ACC-0001 (independent counter)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{"session_id":"iso-B","protocol":"HTTP","method":"POST","path":"/api/v1/accounts","headers":{},"body":{"account_holder":"Bob","currency":"USD","initial_deposit":500.0}}' \
  | jq '.body.account_id'  # → "ACC-0001"

# Session A reads its own ACC-0002 → 200 (Alice2)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{\"session_id\":\"iso-A\",\"protocol\":\"HTTP\",\"method\":\"GET\",\"path\":\"/api/v1/accounts/$ACCT_A2\",\"headers\":{},\"body\":{}}" \
  | jq '{status:.status_code,holder:.body.account_holder}'
# Expected: { "status": 200, "holder": "Alice2" }

# Session B tries to read ACC-0002 → 404 (B's state only has ACC-0001)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d "{\"session_id\":\"iso-B\",\"protocol\":\"HTTP\",\"method\":\"GET\",\"path\":\"/api/v1/accounts/$ACCT_A2\",\"headers\":{},\"body\":{}}" \
  | jq '{status:.status_code}'
# Expected: { "status": 404 }
```

---

### 6b. Multiple operations within same session — IDs increment per session

```bash
# Session X: 3 sequential creates → IDs increment within this session
for NAME in Alice Bob Carol; do
  curl -s -X POST http://localhost:8080/api/v1/virtual \
    -H "Content-Type: application/json" \
    -d "{
      \"session_id\": \"multi-sess\",
      \"protocol\": \"HTTP\", \"method\": \"POST\", \"path\": \"/api/v1/accounts\",
      \"headers\": {}, \"body\": {\"account_holder\": \"$NAME\", \"currency\": \"USD\", \"initial_deposit\": 100.0}
    }" | jq '{holder: .body.account_holder, id: .body.account_id}'
done
# Expected: ACC-0001, ACC-0002, ACC-0003 — sequential within session

# Session Y: starts fresh at 0001 again (independent counter)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "fresh-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "Dave", "currency": "USD", "initial_deposit": 100.0}
  }' | jq '.body.account_id'
# Expected: "ACC-0001"  ← fresh counter for new session
```

---

### 6c. Inspect and clear session state

```bash
# Inspect state after operations in multi-sess
curl -s http://localhost:8080/api/v1/sessions/multi-sess/state | jq '.collections.accounts | keys'
# Expected: ["ACC-0001", "ACC-0002", "ACC-0003"]

# Clear state for a session



curl -s -X POST http://localhost:8080/api/v1/sessions/multi-sess/clear | jq .
curl -s http://localhost:8080/api/v1/sessions/multi-sess/state | jq '.collections'
# Expected: {} (empty after clear)
```

---

## 7. Drift Simulation

### 7a. Configure global drift: 100ms latency + 20ms jitter + 10% error rate

```bash
curl -s -X POST http://localhost:8080/api/v1/drift/config \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 100, "jitterMs": 20, "errorRate": 0.10}' | jq .
```

**Send 15 requests — observe latency variance and ~10% errors (500):**
```bash
for i in $(seq 1 15); do
  RESP=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
    -H "Content-Type: application/json" \
    -d '{
      "session_id": "drift-sess",
      "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
      "headers": {}, "body": {"account_holder": "Drifter", "currency": "USD", "initial_deposit": 100.0}
    }')
  STATUS=$(echo $RESP | jq '.status_code')
  LATENCY=$(echo $RESP | jq '.execution_latency_ms')
  echo "Request $i: status=$STATUS  latency=${LATENCY}ms"
done
# Expected: Most 201; ~1-2 out of 15 return 500; latency 80-120ms range (100±20)
```

---

### 7b. High jitter — wide latency spread

```bash
curl -s -X POST http://localhost:8080/api/v1/drift/config \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 50, "jitterMs": 200, "errorRate": 0.0}' | jq .

# 5 requests — latency varies 0-250ms
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/api/v1/virtual \
    -H "Content-Type: application/json" \
    -d '{
      "session_id": "jitter-sess",
      "protocol": "HTTP", "method": "GET", "path": "/api/v1/transfers/TXF-001",
      "headers": {}, "body": {}
    }' | jq '{request: '"$i"', status: .status_code, latency_ms: .execution_latency_ms}'
done
```

---

### 7c. 100% error rate simulation (chaos mode)

```bash
curl -s -X POST http://localhost:8080/api/v1/drift/config \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 0, "jitterMs": 0, "errorRate": 1.0}' | jq .

curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "chaos",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "X", "currency": "USD", "initial_deposit": 1.0}
  }' | jq '.status_code'
# Expected: 500 (every time — drift error_rate=1.0)
```

---

### 7d. Per-route drift override

```bash
# Global: no drift
curl -s -X DELETE http://localhost:8080/api/v1/drift/config

# /api/v1/payments gets 50% errors
curl -s -X POST http://localhost:8080/api/v1/drift/config/api_v1_payments \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 0, "jitterMs": 0, "errorRate": 0.5}' | jq .

# Accounts still work (global=no drift)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "route-drift",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "X", "currency": "USD", "initial_deposit": 1.0}
  }' | jq '.status_code'
# Expected: 201 (not affected by payment route drift)

# Reset all
curl -s -X DELETE http://localhost:8080/api/v1/drift/config
```

---

## 8. Error Cases

### 8a. Missing session_id → HTTP 400

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{"protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts", "headers": {}, "body": {}}' | jq .
# Expected: HTTP 400, { "error": "session_id is required", "hint": "..." }
```

---

### 8b. No matching stub → status_code 404

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "test",
    "protocol": "HTTP", "method": "DELETE", "path": "/api/v1/nonexistent",
    "headers": {}, "body": {}
  }' | jq '{status: .status_code, matched: .matched_stub_id}'
# Expected: { "status": 404, "matched": null }
```

---

### 8c. Body condition not met → falls to lower-specificity or fallback stub

```bash
# stub_account_create_v1 requires $.currency=USD (specificity=10)
# But stub_account_create_fallback_v1 has no conditions (specificity=0) and matches any POST
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "test",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "X", "initial_deposit": 100.0}
  }' | jq '{status: .status_code, matched: .matched_stub_id}'
# Expected: status=201, matched="stub_account_create_fallback_v1"
# (USD stub condition fails → fallback stub matches → 201 not 404)
# 404 only happens when NO stub exists for the path at all
```

---

### 8d. Transparent proxy — missing X-Session-Id → 400

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"account_holder": "Test", "currency": "USD", "initial_deposit": 100.0}' | jq .
# Expected: { "error": "Missing required header: X-Session-Id" }
```

---

### 8e. Stub disabled → not matched

```bash
# Disable
curl -s -X POST http://localhost:8080/api/v1/stubs/stub_account_create_v1/toggle | jq .

# Request no longer matches the disabled stub
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "toggle-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "X", "currency": "USD", "initial_deposit": 100.0}
  }' | jq '{matched: .matched_stub_id}'
# stub_account_create_v1 NOT in matched_stub_id; fallback or savings stub may match instead

# Re-enable
curl -s -X POST http://localhost:8080/api/v1/stubs/stub_account_create_v1/toggle | jq .
```

---

## 9. Transparent Proxy (Actual URLs + X-Session-Id header)

Admin endpoints are excluded. All other paths hit the matching engine via the proxy filter.

### 9a. POST via actual URL

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-sess" \
  -d '{"account_holder": "Bob", "initial_deposit": 1000.0, "currency": "USD"}' | jq .
# Expected: direct stub body (not VirtualResponse wrapper), HTTP 201
```

### 9b. Full create → GET → cross-session via actual URLs

```bash
ACCT=$(curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-round" \
  -d '{"account_holder": "Alice", "initial_deposit": 500.0, "currency": "USD"}' | jq -r '.account_id')
echo "Account: $ACCT"

# Same session GET → 200
curl -s http://localhost:8080/api/v1/accounts/$ACCT \
  -H "X-Session-Id: proxy-round" | jq '{holder: .account_holder, status: "200 expected"}'

# Different session GET → 404
curl -s -o /dev/null -w "HTTP %{http_code}\n" \
  http://localhost:8080/api/v1/accounts/$ACCT \
  -H "X-Session-Id: proxy-other"
# Expected: HTTP 404
```

---

## 10. Stub Management API

### 10a. List all stubs

```bash
curl -s http://localhost:8080/api/v1/stubs | jq 'length'
# Expected: 26+ (seed stubs + any registered in session)
```

### 10b. Filter by protocol / path / method

```bash
curl -s "http://localhost:8080/api/v1/stubs?protocol=GRPC"  | jq '.[].stubId'
curl -s "http://localhost:8080/api/v1/stubs?protocol=KAFKA" | jq '.[].stubId'
curl -s "http://localhost:8080/api/v1/stubs?path=/api/v1/accounts" | jq '.[].stubId'
curl -s "http://localhost:8080/api/v1/stubs?method=GET"     | jq '.[].stubId'
```

### 10c. Get specific stub by ID

```bash
curl -s http://localhost:8080/api/v1/stubs/stub_account_create_v1 | jq '{stubId, specificity: .requestMatcher.bodyJsonPath | length}'
```

### 10d. Delete a stub

```bash
curl -s -X DELETE http://localhost:8080/api/v1/stubs/stub_order_express | jq .
# Then verify it no longer routes:
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "del-test",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/orders",
    "headers": {}, "body": {"item": "X", "shipping": "EXPRESS"}
  }' | jq '.status_code'
# Expected: 404 (stub deleted)
```

### 10e. Session management

```bash
# Create session with TTL
curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"session_id": "ci-session", "ttl_minutes": 60}' | jq .

# List sessions
curl -s http://localhost:8080/api/v1/sessions | jq '.[].session_id'

# Inspect state
curl -s http://localhost:8080/api/v1/sessions/sess-A/state | jq '.collections'

# Delete session
curl -s -X DELETE http://localhost:8080/api/v1/sessions/ci-session && echo "Deleted"
```

---

## Specificity Cheat Sheet

| Scenario | Body Conditions | Header Conditions | Specificity | Wins over |
|---|---|---|---|---|
| `stub_savings_account_create_v1` | 2 (`$.currency` + `$.type`) | 0 | **20** | All at same path |
| `stub_account_create_v1` | 1 (`$.currency=USD`) | 0 | **10** | Fallback |
| `stub_account_create_eur_v1` | 1 (`$.currency=EUR`) | 0 | **10** | Fallback |
| Header-only stub | 0 | 1 | **5** | Zero-condition stubs |
| `stub_gold_electronics` | 2 (tier + category) | 0 | **20** | Single-condition stubs |
| `stub_account_get_v1` (READ) | 0 | 0 | **0** | 404 fallback by insertion |
| `stub_account_not_found` (404) | 0 | 0 | **0** | Never (last) |

> **Formula:** `specificity = bodyJsonPath.size() × 10 + headers.size() × 5`
> Java stable sort preserves insertion order for equal-specificity stubs.

---

## Path Param Cheat Sheet

| pathPattern | Virtual path | Trie match? | pathVars captured |
|---|---|---|---|
| `/api/v1/orders/{id}` | `/api/v1/orders/ORD-0001` | ✅ wildcard | `{id: "ORD-0001"}` |
| `/api/v1/orders/{id}` | `/api/v1/orders/ABC` | ✅ wildcard | `{id: "ABC"}` |
| `/api/v1/orders/{id}` | `/api/v1/orders/ORD-0001/items` | ❌ depth mismatch | — |
| `/api/v1/orders/123` | `/api/v1/orders/123` | ✅ literal | — |
| `/api/v1/orders/123` | `/api/v1/orders/456` | ❌ literal mismatch | — |
| `/dept/{deptId}/emp/{empId}` | `/dept/10/emp/55` | ✅ two wildcards | `{deptId:"10", empId:"55"}` |

> `{{request.pathVar.id}}` in stateAction/response templates resolves to the captured value.

---

## 11. Kafka Drift — Protocol & Method Level

Drift can target specific protocols or Kafka topics without affecting others.

### 11a. Register a Kafka stub

```bash
curl -s -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_kafka_inventory_update",
    "requestMatcher": {
      "protocol": "KAFKA", "method": "inventory-updates",
      "pathPattern": "", "headers": {},
      "bodyJsonPath": {"$.event_type": "STOCK_UPDATE"}
    },
    "responseTemplate": {
      "statusCode": 200, "headers": {},
      "body": {"ack": true, "event_type": "STOCK_UPDATE", "status": "PROCESSED", "region": "US"}
    }
  }' | jq .stubId
# Expected: "stub_kafka_inventory_update"
```

### 11b. Baseline — call without drift

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "kafka-drift-sess",
    "protocol": "KAFKA", "method": "inventory-updates", "path": "",
    "headers": {}, "body": {"event_type": "STOCK_UPDATE", "product_id": "SKU-001", "quantity": 50}
  }' | jq '{matched: .matched_stub_id, status: .status_code, latency: .execution_latency_ms}'
# Expected: { "matched": "stub_kafka_inventory_update", "status": 200, "latency": ~1ms }
```

### 11c. Global drift — affects ALL protocols

```bash
curl -s -X POST http://localhost:8080/api/v1/drift/config \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 200, "jitterMs": 50, "errorRate": 0.20}' | jq .

# Send 10 Kafka requests — ~20% should return 500
for i in $(seq 1 10); do
  RESP=$(curl -s -X POST http://localhost:8080/api/v1/virtual \
    -H "Content-Type: application/json" \
    -d '{
      "sessionId": "kafka-drift-sess",
      "protocol": "KAFKA", "method": "inventory-updates", "path": "",
      "headers": {}, "body": {"event_type": "STOCK_UPDATE", "product_id": "SKU-001", "quantity": 50}
    }')
  echo "  [$i] status=$(echo $RESP | jq .status_code) latency=$(echo $RESP | jq .execution_latency_ms)ms"
done
# Expected: ~8 with status=200, ~2 with status=500 (random)

curl -s -X DELETE http://localhost:8080/api/v1/drift/config | jq .
```

### 11d. Protocol-level drift — KAFKA only (HTTP unaffected)

Configure drift for KAFKA protocol only. HTTP requests should have zero drift.

```bash
curl -s -X POST "http://localhost:8080/api/v1/drift/config/protocol?protocol=KAFKA" \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 300, "jitterMs": 100, "errorRate": 0.50}' | jq .
# → {"protocol": "KAFKA", "message": "Protocol drift configured", ...}

# Kafka request — AFFECTED by drift (50% errors)
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/api/v1/virtual \
    -H "Content-Type: application/json" \
    -d '{
      "sessionId": "kd-sess", "protocol": "KAFKA", "method": "inventory-updates", "path": "",
      "headers": {}, "body": {"event_type": "STOCK_UPDATE", "product_id": "SKU-001", "quantity": 50}
    }' | jq '{status: .status_code, latency: .execution_latency_ms}'
done
# Expected: ~50% status=500, latency 200-400ms

# HTTP request — NOT affected (no global or HTTP drift configured)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "kd-sess", "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts",
    "headers": {}, "body": {"account_holder": "NoDrift", "currency": "USD", "initial_deposit": 100.0}
  }' | jq '{status: .status_code, latency: .execution_latency_ms}'
# Expected: status=201, latency ~1ms (no drift)

curl -s -X DELETE http://localhost:8080/api/v1/drift/config | jq .
```

### 11e. Method-level drift — specific Kafka topic only

Target a single Kafka topic. Other topics and protocols are unaffected.

```bash
curl -s -X POST "http://localhost:8080/api/v1/drift/config/method?method=inventory-updates" \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 500, "jitterMs": 0, "errorRate": 1.0}' | jq .
# → {"method": "inventory-updates", "message": "Method/topic drift configured", ...}

# inventory-updates topic — 100% errors + ~500ms latency
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "kd-sess", "protocol": "KAFKA", "method": "inventory-updates", "path": "",
    "headers": {}, "body": {"event_type": "STOCK_UPDATE", "product_id": "SKU-001", "quantity": 50}
  }' | jq '{status: .status_code, latency: .execution_latency_ms}'
# Expected: { "status": 500, "latency": ~500ms }

# payment-events topic — NOT affected (different topic)
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "kd-sess", "protocol": "KAFKA", "method": "payment-events", "path": "",
    "headers": {}, "body": {"event_type": "PAYMENT_COMPLETED", "payment_id": "PAY-001", "amount": 150.0}
  }' | jq '{status: .status_code, latency: .execution_latency_ms}'
# Expected: { "status": 200, "latency": ~0ms } (no drift)

curl -s -X DELETE http://localhost:8080/api/v1/drift/config | jq .
```

### 11f. Check drift resolution — which tier applies?

```bash
curl -s "http://localhost:8080/api/v1/drift/check?method=inventory-updates&protocol=KAFKA" | jq .
# Expected (when method drift is active):
# {
#   "tier": "METHOD",
#   "key": "inventory-updates",
#   "config": { "baseLatencyMs": 500, "jitterMs": 0, "errorRate": 1.0 }
# }
```

> **Drift precedence:** route (HTTP path) > method/topic > protocol > global > none

---

## 12. Live Outbound Proxy — Record on First Miss, Replay on Hit

The transparent proxy can forward stub misses to a real upstream, auto-record the response
as a stub, and replay it on subsequent identical requests.

> **Setup:** Start a mock upstream on port 9999 (or point at your real service).
> WireMock is used in automated tests; these curls work with any HTTP server.

### 12a. Enable proxy

```bash
curl -s -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"baseUrl": "http://localhost:9999", "recordOnMiss": true}' | jq .
# → {"message":"Proxy configuration updated","enabled":true,"baseUrl":"http://localhost:9999","recordOnMiss":true}
```

### 12b. First call — stub miss → forward to upstream → auto-record

No stub exists for `/orders/create`. The proxy forwards to upstream, captures the response,
auto-ingests it, and returns the upstream response.

```bash
curl -s -X POST http://localhost:8080/orders/create \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-test" \
  -d '{"item": "Widget", "qty": 5}' | jq .
# → upstream response: {"order_id":"ORD-7777","status":"CONFIRMED","amount":99.99,"region":"US"}
```

**App logs:**
```
[OutboundProxy] Stub miss for session=proxy-test, forwarding POST /orders/create → http://localhost:9999/orders/create
[OutboundProxy] Auto-ingested POST /orders/create → recordId=rec-xxxx, status=AUTO_PUBLISHED
```

### 12c. Check proxy stats — 1 forward, 1 recorded

```bash
curl -s http://localhost:8080/api/v1/proxy/stats | jq '{totalForwarded, totalRecorded, recentForwards}'
# → {
#     "totalForwarded": 1,
#     "totalRecorded": 1,
#     "recentForwards": [{
#       "method": "POST", "path": "/orders/create",
#       "upstreamStatus": 200, "ingestStatus": "AUTO_PUBLISHED",
#       "recordId": "rec-xxxx"
#     }]
#   }
```

### 12d. Check recording history — auto-ingested record

```bash
curl -s http://localhost:8080/api/v1/recording/history?limit=3 | jq '.[] | {id, status, path: .rawLog.request.path}'
# → { "id": "rec-xxxx", "status": "AUTO_PUBLISHED", "path": "/orders/create" }
```

### 12e. Second call — stub answers, upstream NOT called

The auto-generated stub from step 12b now matches. Upstream receives zero new requests.

```bash
curl -s -X POST http://localhost:8080/orders/create \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-test" \
  -d '{"item": "Widget", "qty": 5}' | jq .
# → same response from auto-generated stub (NOT upstream)

# Verify: totalForwarded still 1
curl -s http://localhost:8080/api/v1/proxy/stats | jq '.totalForwarded'
# → 1 (no new forward — stub answered)
```

### 12f. Disable proxy

```bash
curl -s -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"recordOnMiss": false}' | jq .
# → {"enabled": false, ...}
```

> **Key point:** For production, replace `http://localhost:9999` with your real service URL.
> WireMock (`<scope>test</scope>`) simulates the upstream in automated tests only.

---

## 13. Sanitization End-to-End — Ingest with PCI/PII → Invoke Clean Stub

### 13a. Ingest traffic with sensitive data (card, cvv, email, ssn)

```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST",
      "path": "/api/v1/customers",
      "headers": {"Content-Type": "application/json"},
      "body": {
        "name": "John Doe",
        "credit_card": "4111111111111111",
        "cvv": "456",
        "email": "john@example.com",
        "ssn": "123-45-6789",
        "amount": 500.0,
        "currency": "USD"
      }
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {
        "customer_id": "CUS-0001",
        "name": "John Doe",
        "amount": 500.0,
        "status": "ACTIVE"
      }
    }
  }')

echo "=== Result ==="
echo $INGEST | jq '{status: .status, confidence: .confidence}'
echo "=== Sanitization Summary ==="
echo $INGEST | jq '{pci_masked: .requestSummary.pciFieldsMasked, pii_masked: .requestSummary.piiFieldsMasked, safe_kept: .requestSummary.safeFieldsKept}'
echo "=== bodyJsonPath (only clean fields — card/cvv/email/ssn excluded) ==="
echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
echo "=== Response body template ==="
echo $INGEST | jq '.generatedStub.responseTemplate.body'
echo "=== Sensitive data NOT in stub ==="
echo $INGEST | jq '.generatedStub | tostring | test("4111")' # false
echo $INGEST | jq '.generatedStub | tostring | test("456")'  # false
echo $INGEST | jq '.generatedStub | tostring | test("example.com")' # false
echo $INGEST | jq '.generatedStub | tostring | test("123-45")' # false
```

**Expected:**
```
status: "AUTO_PUBLISHED"   confidence: 1.0
pci_masked: 2 (credit_card + cvv)
pii_masked: 2 (email + ssn)
safe_kept: 3  (name, amount, currency)

bodyJsonPath: { "$.name": "John Doe", "$.amount": "500.0", "$.currency": "USD" }
  → card/cvv/email/ssn EXCLUDED from match conditions

response body: {
  "customer_id": "CUS-0001",
  "name":        "{{request.body.name}}",    ← echoed (in request body, not masked)
  "amount":      "{{request.body.amount}}",  ← echoed
  "status":      "ACTIVE"                    ← static
}

Card in stub: false | CVV in stub: false | Email in stub: false | SSN in stub: false
```

### 13b. Invoke the sanitized stub — response is clean

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "san-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/customers",
    "headers": {}, "body": {"name": "John Doe", "amount": 500.0, "currency": "USD"}
  }' | jq '{matched: .matched_stub_id, status: .status_code, body}'
```

**Expected:**
```json
{
  "matched": "auto__api_v1_customers_POST_...",
  "status": 201,
  "body": {
    "customer_id": "CUS-0001",
    "name": "John Doe",
    "amount": 500.0,
    "status": "ACTIVE"
  }
}
```

> No card number, CVV, email, or SSN anywhere in the response — sanitized at ingest time.
> `name` and `amount` are echoed dynamically from the request body (clean fields).

### 13c. Invoke with different name — dynamic echo

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "san-sess-2",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/customers",
    "headers": {}, "body": {"name": "Alice Smith", "amount": 500.0, "currency": "USD"}
  }' | jq '.body.name'
# Expected: "Alice Smith" (echoed from request, not the recorded "John Doe")
```

---

## 14. Nested JSON & Arrays — Ingest, Sanitization, and Invoke

### 14a. Nested objects with PCI inside nested `payment` object

Ingest an order with nested `shipping` address and nested `payment` containing card data.
The sanitizer recurses into nested objects — card/cvv inside `payment` are masked.

```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST",
      "path": "/api/v1/orders",
      "headers": {"Content-Type": "application/json"},
      "body": {
        "customer_name": "Jane Doe",
        "total": 299.99,
        "currency": "USD",
        "shipping": {
          "street": "123 Main St",
          "city": "Austin",
          "state": "TX",
          "zip": "78701"
        },
        "payment": {
          "credit_card": "4111111111111111",
          "cvv": "789",
          "expiry": "12/25"
        }
      }
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {
        "order_id": "ORD-5001",
        "customer_name": "Jane Doe",
        "total": 299.99,
        "status": "PLACED"
      }
    }
  }')

echo "=== Result ==="
echo $INGEST | jq '{status: .status, confidence: .confidence}'
echo "=== PCI masked (card + cvv inside nested payment) ==="
echo $INGEST | jq '.requestSummary.pciFieldsMasked'
echo "=== Card NOT in stub ==="
echo $INGEST | jq '.generatedStub | tostring | test("4111")'
echo "=== bodyJsonPath (only flat safe fields) ==="
echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
echo "=== Response body ==="
echo $INGEST | jq '.generatedStub.responseTemplate.body'
```

**Expected:**
```
status: "AUTO_PUBLISHED"    confidence: 1.0
PCI masked: 2 (credit_card + cvv inside nested payment object)
Card in stub: false

bodyJsonPath: {
  "$.customer_name": "Jane Doe",
  "$.total": "299.99",
  "$.currency": "USD"
}
→ Nested objects (shipping, payment) are NOT in bodyJsonPath — only flat scalar fields.
→ card/cvv excluded because they were masked by sanitizer.
→ street, zip, city, state, expiry all classified as SAFE (in KNOWN_SAFE_NAMES).

response body: {
  "order_id": "ORD-5001",
  "customer_name": "{{request.body.customer_name}}",
  "total": "{{request.body.total}}",
  "status": "PLACED"
}
```

### 14b. Invoke the nested stub — works immediately (AUTO_PUBLISHED)

```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "nested-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/orders",
    "headers": {}, "body": {
      "customer_name": "Jane Doe",
      "total": 299.99,
      "currency": "USD",
      "shipping": {"street": "123 Main St", "city": "Austin", "state": "TX", "zip": "78701"}
    }
  }' | jq '{matched: .matched_stub_id, status: .status_code, body}'
```

**Expected:**
```json
{
  "matched": "auto__api_v1_orders_POST_...",
  "status": 201,
  "body": {
    "order_id": "ORD-5001",
    "customer_name": "Jane Doe",
    "total": 299.99,
    "status": "PLACED"
  }
}
```

> **Record/replay semantics:** The auto-generated stub has `bodyJsonPath`
> conditions with the EXACT values from the recording (`$.customer_name = "Jane Doe"`,
> `$.total = 299.99`, `$.currency = "USD"`). Sending different values (e.g. "Bob Smith")
> **will NOT match** → 404. This is intentional — record once, replay the exact scenario.
> For dynamic matching, register a manual stub with no bodyJsonPath conditions.

---

### 14c. Array with PCI inside — `items[]` + `payment_methods[]` containing card data

The sanitizer recurses into array elements. Card numbers inside `payment_methods[]` are masked.

```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST",
      "path": "/api/v1/checkout",
      "headers": {"Content-Type": "application/json"},
      "body": {
        "customer_id": "CUS-001",
        "total": 549.98,
        "currency": "USD",
        "items": [
          {"name": "Laptop", "price": 499.99, "quantity": 1},
          {"name": "Mouse", "price": 49.99, "quantity": 1}
        ],
        "payment_methods": [
          {"credit_card": "4111111111111111", "cvv": "321"},
          {"credit_card": "5500000000000004", "cvv": "654"}
        ]
      }
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {
        "checkout_id": "CHK-9001",
        "customer_id": "CUS-001",
        "total": 549.98,
        "status": "CONFIRMED"
      }
    }
  }')

echo "=== Result ==="
echo $INGEST | jq '{status: .status, confidence: .confidence}'
echo "=== PCI masked (4 = two cards + two cvvs inside array) ==="
echo $INGEST | jq '.requestSummary.pciFieldsMasked'
echo "=== Cards NOT in stub ==="
echo $INGEST | jq '.generatedStub | tostring | test("4111")'
echo $INGEST | jq '.generatedStub | tostring | test("5500")'
echo "=== bodyJsonPath ==="
echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
echo "=== Response body ==="
echo $INGEST | jq '.generatedStub.responseTemplate.body'
```

**Expected:**
```
status: "AUTO_PUBLISHED"    confidence: 1.0
PCI masked: 4 (two credit_card + two cvv across two array elements)
Card '4111' in stub: false
Card '5500' in stub: false

bodyJsonPath: {
  "$.customer_id": "CUS-001",
  "$.total": "549.98",
  "$.currency": "USD"
}
→ Array fields (items[], payment_methods[]) are NOT in bodyJsonPath.
→ All card/cvv values inside arrays were masked by recursive sanitization.

response body: {
  "checkout_id": "CHK-9001",
  "customer_id": "{{request.body.customer_id}}",
  "total": "{{request.body.total}}",
  "status": "CONFIRMED"
}
```

**Invoke the array stub:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "array-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/checkout",
    "headers": {}, "body": {
      "customer_id": "CUS-001",
      "total": 549.98,
      "currency": "USD",
      "items": [
        {"name": "Laptop", "price": 499.99, "quantity": 1},
        {"name": "Mouse", "price": 49.99, "quantity": 1}
      ]
    }
  }' | jq '{matched: .matched_stub_id, status: .status_code, body}'
```

**Expected:**
```json
{
  "matched": "auto__api_v1_checkout_POST_...",
  "status": 201,
  "body": {
    "checkout_id": "CHK-9001",
    "customer_id": "CUS-001",
    "total": 549.98,
    "status": "CONFIRMED"
  }
}
```

> No card data anywhere in the response — sanitized at ingest time.

---

### 14d. 3-level deep nested JSON — `destination.warehouse.type`

```bash
INGEST=$(curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST",
      "path": "/api/v1/shipments",
      "headers": {"Content-Type": "application/json"},
      "body": {
        "order_id": "ORD-123",
        "destination": {
          "country": "US",
          "region": "WEST",
          "warehouse": {
            "name": "Primary",
            "type": "FULFILLMENT"
          }
        },
        "items": [
          {"name": "Widget", "quantity": 5, "price": 19.99},
          {"name": "Gadget", "quantity": 2, "price": 39.99}
        ]
      }
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {
        "shipment_id": "SHP-9001",
        "order_id": "ORD-123",
        "status": "DISPATCHED",
        "region": "WEST"
      }
    }
  }')

echo $INGEST | jq '{status: .status, confidence: .confidence}'
echo "=== bodyJsonPath (only flat field: order_id) ==="
echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
echo "=== Response body ==="
echo $INGEST | jq '.generatedStub.responseTemplate.body'
```

**Expected:**
```
status: "AUTO_PUBLISHED"    confidence: 1.0

bodyJsonPath: { "$.order_id": "ORD-123" }
→ Only the flat field "order_id" (SAFE_ID pattern) becomes a condition.
→ Nested objects and arrays are recursively sanitized but NOT added to bodyJsonPath.

response body: {
  "shipment_id": "SHP-9001",
  "order_id": "{{request.body.order_id}}",   ← echoed (in request body)
  "status": "DISPATCHED",                     ← static
  "region": "WEST"                            ← static (in response only)
}
```

**Invoke:**
```bash
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "deep-sess",
    "protocol": "HTTP", "method": "POST", "path": "/api/v1/shipments",
    "headers": {}, "body": {
      "order_id": "ORD-123",
      "destination": {"country": "US", "region": "WEST", "warehouse": {"name": "Primary", "type": "FULFILLMENT"}},
      "items": [{"name": "Widget", "quantity": 5, "price": 19.99}]
    }
  }' | jq '{matched: .matched_stub_id, status: .status_code, body}'
```

**Expected:**
```json
{
  "matched": "auto__api_v1_shipments_POST_...",
  "status": 201,
  "body": {
    "shipment_id": "SHP-9001",
    "order_id": "ORD-123",
    "status": "DISPATCHED",
    "region": "WEST"
  }
}
```

---

### Nested/Array Behavior Summary

| Input shape | Sanitization | bodyJsonPath | Response template |
|---|---|---|---|
| Flat scalar (`"name": "Jane"`) | Classified per field | Included if safe (not masked) | `{{request.body.name}}` if in request |
| Nested object (`"shipping": {...}`) | Recurses — masks PCI/PII inside | **NOT** included (only flat fields) | Static value from recording |
| Array of objects (`"items": [...]`) | Recurses into each element | **NOT** included | Static value from recording |
| PCI inside nested (`"payment.credit_card"`) | Masked (`****1111`) | Excluded | Excluded |
| PCI inside array (`"payment_methods[0].credit_card"`) | Masked per element | Excluded | Excluded |

---

## 15. Transparent Proxy — Actual URLs with Live Upstream

> The transparent proxy calls **actual URLs** (e.g. `POST /warehouse/ship`) with an
> `X-Session-Id` header. On stub miss, the request is forwarded to the configured upstream,
> the response is auto-recorded as a stub, and subsequent identical calls hit the stub.
>
> **Setup:** Point `proxy.upstream.base-url` at your real service (or a mock on port 9999).
> In automated tests, WireMock simulates the upstream. These curls work with any HTTP server.

### 15a. Enable proxy and send first request (stub miss → forward)

```bash
# Enable proxy pointing at upstream
curl -s -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"baseUrl": "http://localhost:9999", "recordOnMiss": true}' | jq .
# → {"enabled":true,"baseUrl":"http://localhost:9999","recordOnMiss":true}

# POST via transparent proxy — no stub for /warehouse/ship → forwards to upstream
curl -s -X POST http://localhost:8080/warehouse/ship \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-manual-test" \
  -d '{"order_id": "ORD-100", "destination": "NYC"}' | jq .
```

**Expected:** The **upstream's actual response** (not a 404):
```json
{
  "order_id": "ORD-8888",
  "status": "SHIPPED",
  "carrier": "FEDEX",
  "region": "US"
}
```

**App logs:**
```
[OutboundProxy] Stub miss for session=proxy-manual-test, forwarding POST /warehouse/ship → http://localhost:9999/warehouse/ship
[OutboundProxy] Auto-ingested POST /warehouse/ship → recordId=rec-xxxx, status=AUTO_PUBLISHED
```

### 15b. Check proxy stats and recording history

```bash
# Stats — 1 forward, 1 recorded
curl -s http://localhost:8080/api/v1/proxy/stats | jq '{totalForwarded, totalRecorded, forward: .recentForwards[0]}'
# → {
#     "totalForwarded": 1,
#     "totalRecorded": 1,
#     "forward": {
#       "method": "POST", "path": "/warehouse/ship",
#       "upstreamStatus": 200, "ingestStatus": "AUTO_PUBLISHED",
#       "recordId": "rec-xxxx"
#     }
#   }

# Recording history
curl -s http://localhost:8080/api/v1/recording/history?limit=3 \
  | jq '.[] | {id, status, path: .rawLog.request.path}'
# → { "id": "rec-xxxx", "status": "AUTO_PUBLISHED", "path": "/warehouse/ship" }
```

### 15c. Second call — replay from auto-generated stub (upstream NOT called)

```bash
# Same request — auto-generated stub should answer
curl -s -X POST http://localhost:8080/warehouse/ship \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-manual-test" \
  -d '{"order_id": "ORD-100", "destination": "NYC"}' | jq .
```

**Expected:** Response from stub (upstream gets zero new requests):
```json
{
  "order_id": "ORD-100",
  "status": "SHIPPED",
  "carrier": "FEDEX",
  "region": "US"
}
```

> Note: `order_id` is `"ORD-100"` (echoed from request via `{{request.body.order_id}}`)
> while the recorded response had `"ORD-8888"`. The stub generator created a dynamic echo
> template because `order_id` appeared in both request and response.

```bash
# Verify: totalForwarded still 1 — stub answered, not upstream
curl -s http://localhost:8080/api/v1/proxy/stats | jq '.totalForwarded'
# → 1
```

### 15d. GET via transparent proxy

```bash
curl -s http://localhost:8080/catalog/browse \
  -H "X-Session-Id: proxy-manual-test" | jq .
# → upstream response: {"product_id":"PRD-100","name":"Widget","price":29.99,"category":"TOOLS"}
```

### 15e. Existing stubs are NOT forwarded

```bash
# /api/v1/accounts has a seed stub — matches directly, never forwarded
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-manual-test" \
  -d '{"account_holder": "TestUser", "currency": "USD", "initial_deposit": 100.0}' | jq .
# → {"account_id":"ACC-0001",...} from seed stub (upstream NOT called)
```

### 15f. Disable proxy

```bash
curl -s -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"recordOnMiss": false}' | jq .
# → {"enabled": false, ...}

# After disabling, stub misses return 404 again (no forwarding)
curl -s -X POST http://localhost:8080/unknown/endpoint \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-manual-test" \
  -d '{"data": "test"}' | jq .status_code
# → 404 (proxy disabled — no forwarding)
```

### Proxy Flow Diagram

```
Client → POST /warehouse/ship (X-Session-Id: proxy-test)
           │
           ▼
  TransparentProxyController
           │
           ├── StubMatchingEngine: stub found? → return stub response
           │
           └── StubMatchingEngine: no match (404)?
                   │
                   ├── Proxy disabled? → return 404
                   │
                   └── Proxy enabled?
                           │
                           ▼
                   OutboundForwardingService.forwardAndRecord()
                           │
                           ├── Forward to http://localhost:9999/warehouse/ship
                           ├── Capture upstream response (200, 4xx, 5xx — all captured)
                           ├── Call RecordingService.ingest() → sanitize → generate stub
                           └── Return upstream response to client
                                   │
                                   ▼
                           Next identical request → auto-generated stub answers
```
