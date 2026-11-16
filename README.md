Implementation: Java 21 + Spring Boot 3.4.1 | UI: Thymeleaf + Bootstrap 5 + vanilla JS

# Enterprise Service Virtualization Platform

A high-performance service virtualization engine that matches multi-protocol requests (HTTP/REST, gRPC, Kafka) against registered stubs with sub-10ms p99 latency at 20,000+ RPS, manages session-isolated dynamic state, records traffic with PCI-DSS/PII sanitization, and auto-generates stubs.

---

## Architecture Diagram

End-to-end flow: two independent front doors (real API traffic via the **ingress proxy**, and
admin/config traffic via the **Web UI** → **Control Plane API**) both bottom out in the same
**StubMatchingEngine**, which owns the **stub matcher** (`StubRouter`) and the **session state
store** (`SessionStateStore`).

```
┌────────────────────────────────────┐      ┌────────────────────────────────────────────┐
│ External API Clients               │      │ Web UI (Thymeleaf + Bootstrap 5 + JS)       │
│ (systems being virtualized,        │      │ Stubs / Sessions / Traffic /               │
│ from the caller's POV)             │      │ Drift / Recording admin pages              │
└────────────────────────────────────┘      └────────────────────────────────────────────┘
                   │                                               │
                   ▼                                               ▼
┌────────────────────────────────────┐      ┌────────────────────────────────────────────┐
│ INGRESS                            │      │ CONTROL PLANE REST API                     │
│ Forwarding Proxy + Virtual GW      │      │                                            │
│                                    │      │ /api/v1/stubs  /api/v1/sessions            │
│ ForwardingProxyController /**      │      │ /api/v1/recording/ingest                   │
│ VirtualController /api/v1/virtual  │      │ /api/v1/traffic  /api/v1/drift             │
└────────────────────────────────────┘      └────────────────────────────────────────────┘
                   └─────────────────────────┬─────────────────────┘
                                             │
                                             ▼
   (both doors call the SAME engine — ingress goes straight to matchAndExecute();
    the Control Plane only touches it indirectly, e.g. recording/ingest's auto-publish)
┌───────▼────────────────────────────────────────────────────────────────────────┐
│                     StubMatchingEngine  (pure function, no locks)              │
│                                                                                │
│  ┌────────────────────────────────────────┐  ┌──────────────────────────────┐  │
│  │              StubRouter                │  │      SessionStateStore        │  │
│  │                                        │  │                              │  │
│  │  HTTP ──▶ PathTrie (Copy-on-Write)     │  │  3-level ConcurrentHashMap   │  │
│  │           ┌───────────────────────┐    │  │                              │  │
│  │           │ startup: insertDirect │    │  │  session                     │  │
│  │           │  (no copy, no readers)│    │  │    └─ collection             │  │
│  │           │ runtime: insert()     │    │  │         └─ key → value       │  │
│  │           │  deep-copy + CAS swap │    │  │                              │  │
│  │           └───────────────────────┘    │  │  Auto-creates on first write  │  │
│  │                                        │  │  Per-session AtomicLong       │  │
│  │  GRPC ──▶ ConcurrentHashMap           │  │  counters for ID generation   │  │
│  │           method.toUpperCase()         │  │                              │  │
│  │             └─ List<StubDefinition>    │  │  TTL tracked per session      │  │
│  │                (sorted by specificity)  │  │  idle → evict via scheduler   │  │
│  │                                        │  └──────────────────────────────┘  │
│  │  KAFKA ──▶ ConcurrentHashMap          │                                    │
│  │           topic.toUpperCase()          │  ┌──────────────────────────────┐  │
│  │             └─ List<StubDefinition>    │  │      DriftSimulator          │  │
│  │                (sorted by specificity)  │  │  volatile DriftConfig        │  │
│  └────────────────────────────────────────┘  │  ConcurrentHashMap routes    │  │
│                                              │  ThreadLocalRandom jitter    │  │
│  ┌──────────────────────────┐                └──────────────────────────────┘  │
│  │    TemplateResolver      │                                                  │
│  │  {{request.body.X}}      │  ┌──────────────────────────────────────────┐   │
│  │  {{request.pathVar.id}}  │  │          AsyncEventScheduler             │   │
│  │  {{generate.id:PREFIX}}  │  │  ScheduledExecutorService (4 daemon)     │   │
│  │  {{generate.uuid}}       │  │  delay = delayMs ± jitterMs              │   │
│  │  {{state.result}}        │  │  fires: stateStore.put("events:<topic>") │   │
│  │  {{response.body.X}}     │  └──────────────────────────────────────────┘   │
│  └──────────────────────────┘                                                  │
│                                                                                │
│  ┌──────────────────────────┐  ┌──────────────────────────────────────────┐   │
│  │     TrafficLogger        │  │          PciPiiSanitizer                 │   │
│  │  Ring buffer [10K slots] │  │  Layer 1: field name Set lookup          │   │
│  │  AtomicInteger head idx  │  │  Layer 2: value instanceof check         │   │
│  │  Lock-free circular log  │  │  Layer 3: compiled regex + Luhn check    │   │
│  └──────────────────────────┘  │  Layer 4: entropy heuristic              │   │
│                                └──────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────────┘
```

**Legend — mapping the 5 required flow stages to real code:**

| Stage | Component(s) |
|---|---|
| **Ingress proxying** | `ForwardingProxyController` (`@RequestMapping("/**")`, real-looking URLs + `X-Session-Id` header) and `VirtualController` (`POST /api/v1/virtual`, JSON envelope for HTTP/gRPC/Kafka) — both call `engine.matchAndExecute()` directly |
| **Stub matcher** | `StubMatchingEngine` + `StubRouter` (Copy-on-Write `PathTrie` for HTTP, `ConcurrentHashMap` buckets for gRPC/Kafka, stubs pre-sorted by `specificity()`) |
| **Session state store** | `SessionStateStore` — 3-level nested `ConcurrentHashMap` (session → collection → key), physically isolated per session |
| **Control plane API** | `StubController`, `SessionController`, `RecordingController`, `TrafficController`, `DriftController` — admin/config endpoints under `/api/v1/*`, separate from ingress traffic |
| **Web UI** | Thymeleaf + Bootstrap 5 dashboard pages, dynamic behavior via vanilla JS `fetch()`, which themselves just call the Control Plane API |

---

## Setup and Run

```bash
# Build and run
mvn spring-boot:run

# Run all tests (313 tests)
mvn test

# Access
# Web UI:    http://localhost:8080/


```

**Prerequisites**: Java 21+ and Maven 3.9+.

**Why no Docker Compose**: this service has **zero external runtime dependencies** — no real
database, no real Kafka broker, no real gRPC server. Session state, the stub trie, drift config,
and the traffic ring buffer are all in-process (`ConcurrentHashMap`s, a `PathTrie`, a fixed-size
array) and gRPC/Kafka traffic is *simulated* through the same HTTP-based `/api/v1/virtual`
endpoint, not real protocol servers. A single runnable Spring Boot jar is the entire system —
there's nothing for Compose to orchestrate. If you want a container anyway for deployment
consistency, a one-stage `Dockerfile` (`FROM eclipse-temurin:21-jre` + copy the jar) covers it;
ask and I'll add one.

---

## Protocol Routing — HTTP vs gRPC vs Kafka

| Protocol | How to call |
|----------|-------------|
| **HTTP** | `POST /api/v1/virtual` with `"protocol":"HTTP"` **or** direct URL via forwarding proxy (e.g. `POST /api/v1/accounts` with `X-Session-Id` header) |
| **gRPC** | `POST /api/v1/virtual` with `"protocol":"GRPC"` |
| **Kafka** | `POST /api/v1/virtual` with `"protocol":"KAFKA"` **or** producing or consuming from embedded kafka.

sessionId is mandatory for state saving.
---

## Key Data Structures

### 1. Copy-on-Write Path Trie (HTTP routing)

Used by `PathTrie` / `StubRouter` for HTTP stub lookup.

**Four operations, two different synchronization strategies:**

```
search(method, path)                                — HOT PATH, ~20,000 calls/sec
  → one volatile read of `root`, then a pure read-only walk
  → zero locks, ever — readers never wait on a writer, full stop

insertDirect(stub)                                  — STARTUP ONLY (StubLoader @PostConstruct)
  → mutates trie nodes in place
  → safe only because zero readers exist yet
  → O(path-depth), no copy overhead

insert(stub) / remove(stubId) / rebuild(supplier)   — RUNTIME, rare
  → synchronized: writers serialize against EACH OTHER (not against readers)
  → deep-copy the whole tree → mutate the copy → atomically swap `root`
  → a reader already mid-walk keeps using the OLD snapshot to completion —
    never sees a half-built tree, never blocks on a writer
```

**Trie node structure (real example from `seed_stubs.json`):**
```
root
  └─ "api"
       └─ "v1"
            └─ "accounts"       → stubs: [stub_account_create_v1(specificity=10), ...]
                 └─ "{id}"       → stubs: [stub_account_get_v1(specificity=0), stub_account_not_found(specificity=0)]
                      └─ ...
```
**Fallback determination:** Specificity = `(bodyJsonPath conditions × 10) + (header conditions × 5)`. Candidates are pre-sorted descending at insert time — the engine walks top-to-bottom, and any check that fails (headers, body JSONPath, or state READ returning null) skips to the next candidate. For equal-specificity ties, seed-file registration order breaks the tie — e.g. the state-READ stub is tried first, and only if the key isn't found does the 404 fallback match.

Path variables (e.g. `{id}`) are extracted at match time and placed in `pathVariables` map for use in `{{request.pathVar.id}}` templates.

### 2. gRPC Index — HashMap of method → List<Stub>

```
grpcIndex: ConcurrentHashMap<String, List<StubDefinition>>

  "CREATEACCOUNT"  → [stub_grpc_create_account (specificity=20)]
  "GETBALANCE"     → [stub_grpc_get_balance_v1 (specificity=10)]
  "PROCESSPAYMENT" → [stub_grpc_process_payment_v1 (specificity=10)]
```

- Key = `method.toUpperCase()` (the gRPC method name)
- Value = list sorted by `specificity()` descending — first enabled match wins (these seed stubs
  have no header/body E, so they're all tied at 0 and fall back to seed-file order)
- Registered via seed JSON or `POST /api/v1/recording/ingest` with `"protocol":"GRPC"`

### 3. Kafka Index — HashMap of topic → List<Stub>

```
kafkaIndex: ConcurrentHashMap<String, List<StubDefinition>>

  "PAYMENT-EVENTS"  → [stub_kafka_payment_completed (specificity=0)]
  "ACCOUNT.EVENTS"  → [stub_kafka_account_event_v1 (specificity=0)]
```

- Key = `method.toUpperCase()` where `method` = Kafka topic name
- Value = list sorted by `specificity()` descending
- Registered via seed JSON or `POST /api/v1/recording/ingest` with `"protocol":"KAFKA"`

### 4. Session State — 3-Level Nested ConcurrentHashMap

```
SessionStateStore:
  ConcurrentHashMap<sessionId,
    ConcurrentHashMap<collection,
      ConcurrentHashMap<key, value>>>

Example after POST /api/v1/accounts in session "sess-A":
  "sess-A"
    └─ "accounts"
         └─ "ACC-0001" → { account_id: "ACC-0001", account_holder: "Alice", balance: 500.0 }
    └─ "_counters"
         └─ "ACC" → AtomicLong(1)   ← per-session ID sequence
    └─ "events:payment-events"
         └─ "evt-a1b2c3" → { event_type: "PAYMENT_COMPLETED", payment_id: "PAY-0001" }
```

Physical isolation: each session has its own CHM object. Cross-session reads always return null by construction — not a runtime check but a structural guarantee.

### 5. Traffic Ring Buffer

```
TrafficLogger:
  TrafficEntry[] buffer = new TrafficEntry[10_000]   ← fixed-size array, never resized
  AtomicInteger writeIndex                            ← monotonically increasing, CAS-incremented
  AtomicLong    totalLogged                           ← lifetime counter, independent of wraparound
```

**Writing (`log()`):** every request/response pair gets one slot, computed as
`index = writeIndex.getAndIncrement() % BUFFER_SIZE`. `getAndIncrement()` is a single atomic CAS —
no lock, no contention beyond the hardware-level compare-and-swap. 

**Reading in descending order (newest-first) — no sorting required:** `getRecentEntries(limit)`
and `search(...)` both read in descending order purely by walking the array *backwards* from
wherever `writeIndex` currently points, with the index wrapped via `Math.floorMod`:

- `currentIndex - 1` is the slot that was written *last* (writeIndex always points one past the
  most recent write), so `i = 0` yields the newest entry.


---

## Important Classes

| Class | Role |
|-------|------|
| `StubMatchingEngine` | Orchestrator — stateless pure function. No locks. Delegates to all subsystems. |
| `StubRouter` | Routes by protocol. Owns Trie + gRPC/Kafka HashMaps. CoW on HTTP insert. |
| `PathTrie` | Copy-on-Write trie. `insertDirect()` at startup, `insert()` at runtime. |
| `SessionStateStore` | 3-level ConcurrentHashMap. Auto-creates sessions on first write. TTL tracking. |
| `TemplateResolver` | Resolves `{{...}}` placeholders. Preserves types (Numbers stay Numbers). |
| `AsyncEventScheduler` | 4 daemon threads. Schedules delayed Kafka-style events with jitter. |
| `DriftSimulator` | Injects latency/jitter/errors. `volatile` config for zero-lock reads. |
| `PciPiiSanitizer` | 4-layer detection. Compiled regex. Luhn checksum for card validation. |
| `StubGenerator` | Generates stubs from sanitized traffic. Supports HTTP, GRPC, KAFKA. |
| `ConfidenceCalculator` | Scores generated stubs. ≥0.90 → auto-publish, 0.70–0.89 → review, <0.70 → reject. See [Recording Ingest — Approved, Pending Review, or Rejected?](#recording-ingest--approved-pending-review-or-rejected) for the full decision logic. |
| `ForwardingProxyController` | Catch-all `/**` for HTTP. Enforces `X-Session-Id`. Passes to engine. |
| `StubLoader` | `@PostConstruct` — loads `seed_stubs.json` using `insertDirect()` (startup-safe). |
| `TrafficLogger` | Lock-free ring buffer. AtomicInteger head. 10K capacity. |

---

## Session Isolation & TTL

### Session Lifecycle

```
1. Create explicitly (optional):
   POST /api/v1/sessions
   { "session_id": "ci-run-42", "ttl_minutes": 60 }

2. Auto-create on first HTTP virtual request:
   POST /api/v1/accounts
   X-Session-Id: ci-run-42          ← session created on first state write

3. Inspect state:
   GET /api/v1/sessions/ci-run-42/state

4. Clear (keep session, wipe data):
   POST /api/v1/sessions/ci-run-42/clear

5. Destroy:
   DELETE /api/v1/sessions/ci-run-42
```

### Session TTL

- Default TTL: **30 minutes** of idle time
- Custom TTL via `ttl_minutes` on creation
- TTL is idle-based: each state read/write resets the idle clock
- Sessions do not expire mid-request

### Stub TTL (Governance)

Auto-generated stubs (from recording ingest) get a **7-day TTL** by default. Manual stubs can
supply their own `expiresAt` (ISO-8601 timestamp) in the stub-registration payload instead of
taking the default — see **Appendix, section G (Stub Management)** at the end of this document
for the full `curl` example.

---

## PCI/PII Sanitization Pipeline

The `PciPiiSanitizer` is a 4-layer defense-in-depth pipeline that classifies and masks sensitive
data in recorded traffic before stubs are generated. It runs during the **ingest** path only (not
on every virtual API call), so its cost does not affect the hot-path p99 latency.

### Pipeline Architecture

```
              Incoming body field (key + value)
                           │
                ┌──────────▼──────────┐
                │      LAYER 1        │  Field name in SENSITIVE_FIELD_NAMES?
                │   HashSet.contains  │  "credit_card" → PCI_CARD
                │       ~10ns         │  "ssn" → PII_SSN, "email" → PII_EMAIL
                └──────────┬──────────┘
                           │ miss
                           │  Fuzzy name match with value-shape guard:
                           │  name contains "card" AND value is all-digits? → PCI_CARD
                           │  name contains "social" AND value looks like SSN? → PII_SSN
                           │
                ┌──────────▼──────────┐
                │      LAYER 2        │  Value type check
                │     instanceof      │  null / Boolean → SAFE (exit)
                │       ~5ns          │  Number → Luhn check (catches numeric PANs)
                └──────────┬──────────┘
                           │ miss (String values only from here)
                           │
                ┌──────────▼──────────┐
                │      LAYER 3        │  Pattern matching (all patterns pre-compiled)
                │   compiled regex    │
                │   + Luhn checksum   │  Card numbers: whole-value + embedded-in-text
                │     ~200ns          │  SSN: dashed (123-45-6789) + bare 9 digits
                │                     │  Email, JWT/Bearer, API keys, bare JWTs
                └──────────┬──────────┘
                           │ miss
                           │
                ┌──────────▼──────────┐
                │      LAYER 4        │  Heuristic safe-shape / high-risk-unknown
                │    shape check      │  "ACTIVE" → SAFE_ENUM
                │      ~50ns          │  "ACC-001" → SAFE_ID
                │                     │  Long mixed-alpha + 6+ digits → HIGH_RISK (masked)
                └─────────────────────┘
```

### Why It's Fast

1. **Cheapest checks first (fail-fast ordering)** — Layer 1 (`Set.contains()`, ~10ns) eliminates
   ~30% of fields before any regex runs. Layer 2 (`instanceof`, ~5ns) exits nulls, booleans, and
   most numbers instantly. Only String values that survive both layers reach the regex checks.

2. **All regex compiled once at class load** — The 9 patterns (`CARD_PATTERN`, `SSN_PATTERN`,
   `EMAIL_PATTERN`, `JWT_PATTERN`, etc.) are `private static final Pattern` fields, compiled once
   during JVM class loading. `Pattern.compile()` is expensive (~microseconds); `pattern.matcher(s).find()`
   is cheap (~100-300ns). We pay the compile cost exactly once, ever.

3. **Luhn via lookup table** — Card validation uses a precomputed `LUHN_DOUBLES` array instead of
   arithmetic: `LUHN_DOUBLES[digit]` replaces `d = digit * 2; if (d > 9) d -= 9;` — one array
   index per digit, no multiplication, no branches. A 16-digit card number = 16 lookups + 16 additions.

4. **No object allocation on the hot matching path** — The sanitizer runs only during `ingest()`
   (recording pipeline). The hot path (`/api/v1/virtual` → `matchAndExecute()`) never calls
   `PciPiiSanitizer` at all.

5. **Single recursive walk** — Nested objects and arrays are handled inline during the same
   traversal. No separate "deep scan" pass. Each leaf field is classified exactly once.

### What Each Layer Catches

| Layer | Detects | Method | Cost |
|-------|---------|--------|------|
| 1 | Known field names (`credit_card`, `ssn`, `cvv`, `email`, `token`, etc.) | `HashSet.contains()` | ~10ns |
| 1b | Fuzzy names (`*card*`, `*social*`) with value-shape guard | `String.contains()` + digit check | ~30ns |
| 2 | Numeric PANs/SSNs arriving as JSON numbers | `instanceof Number` → Luhn/SSN digit check | ~50ns |
| 3 | Card numbers (with/without separators, embedded in free text) | Pre-compiled regex + Luhn validation | ~200ns |
| 3 | SSN (dashed or bare 9 digits with SSA invalid-range filter) | Pre-compiled regex + plausibility filter | ~100ns |
| 3 | Email addresses, JWT/Bearer tokens, API keys, bare JWTs | Pre-compiled regex | ~100ns |
| 4 | Safe enums (`"ACTIVE"`, `"USD"`), safe IDs (`"ACC-001"`) | Short regex on survivors only | ~50ns |

### Masking Formats

| Type | Example Input | Masked Output |
|------|---------------|---------------|
| Credit card | `4111111111111111` | `****1111` |
| SSN | `123-45-6789` | `****6789` |
| Email | `jane@walmart.com` | `****@masked.com` |
| CVV / Token / API key | `123` / `Bearer eyJ...` | `****` |

Last 4 digits/domain preserved for debugging — enough to identify the record, never enough to
reconstruct the original value.

### Precision & Recall

Measured on a 30-field golden dataset (11 sensitive + 20 non-sensitive fields):

| Metric | Value | Target |
|--------|-------|--------|
| Recall (sensitive fields caught) | **100%** (11/11) | >= 95% |
| Precision (non-sensitive preserved) | **100%** (20/20) | >= 90% |

Plus a 24-case adversarial dataset targeting blind spots: look-alike field names (`giftcard_theme`,
`social_media_handle`), numeric PANs as JSON numbers, embedded card numbers in free text, bare
JWTs without "Bearer" prefix, UUIDs that shouldn't be masked.

---

## Recording Ingest — Approved, Pending Review, or Rejected?

Every call to `POST /api/v1/recording/ingest` (or the batch variant) runs the same pipeline —
**sanitize → generate stub → score confidence → route** — and ends in exactly one of three
states. This is the `RecordingService.ingest()` decision, backed by `ConfidenceCalculator` and
`CriticalPathClassifier`. (For the request/response payload shape and exact `curl` calls, see
**[Appendix: Sample cURL — Complete Workflows, section A](#a-dynamic-http-stub--record-by-ingest-then-call-actual-urls-session-isolated)**
at the end of this document.)

### 1. Confidence score — how "sure" the sanitizer + stub generator are

```
confidence = (classifiedFields / totalFields) − (highRiskUnknowns × 0.05)
```

- `totalFields` / `classifiedFields` are summed across **both** the sanitized request body and
  the sanitized response body (`SanitizationSummary`, one per side, added together).
- A field is "classified" the moment `PciPiiSanitizer` resolves it to anything other than
  `UNKNOWN` — a safe enum, a safe number, a PCI/PII hit, all count. Only genuinely unclassified
  fields drag the ratio down.
- Every `highRiskUnknown` (an unclassified field that *also* looks dangerous by shape — see
  `PciPiiSanitizer.isHighRiskUnknown()`) subtracts a flat **0.05** penalty on top of the ratio.
- The result is clamped to `[0.0, 1.0]`. An empty body (`totalFields == 0`) short-circuits to a
  perfect `1.0` — nothing to misclassify.

### 2. The critical-path override — payment/fraud/compliance never auto-publish

Independently of confidence, `CriticalPathClassifier.isCritical(path)` does a case-insensitive
substring check on the request path for `"payment"`, `"fraud"`, or `"compliance"`. If any of
those appear anywhere in the path, the record is flagged `requiresReview = true` — **no
confidence score, however perfect, can bypass this.** A wrongly auto-generated stub on a
payment-processing or fraud/compliance endpoint would mask a real integration bug in exactly the
place that's most expensive to get wrong (see `CUSTOMER_VALUE.md`).

### 3. The three outcomes

| Outcome | Condition | What happens |
|---|---|---|
| **`AUTO_PUBLISHED`** | `confidence >= 0.90` **AND** path is not critical | The generated stub is registered immediately via `registerOrReplaceByRequestShape()` — live and matchable on the very next request. If an earlier ingest already produced a stub with an identical request shape (same path/method/body conditions), it's replaced in place instead of piling up an unreachable duplicate. |
| **`PENDING_REVIEW`** | `0.70 <= confidence < 0.90`, **OR** the path is critical (regardless of score) | The stub is generated and attached to the record, but **not** registered yet. It sits in recording history until a human calls `approveRecord()` (promotes it to `AUTO_PUBLISHED`) or `rejectRecord()` (moves it to `REJECTED`). |
| **`REJECTED`** | `confidence < 0.70` **and** the path is not critical | The stub is generated (for visibility/debugging) but never registered and never offered for review — too many unclassified fields means too much risk of a stub leaking unsanitized data if it were ever promoted. |

Read as a flowchart:

```
                 ┌─────────────────────────┐
                 │ ingest() runs sanitizer  │
                 │ + stub generator first   │
                 └────────────┬─────────────┘
                              ▼
                 ┌─────────────────────────┐
                 │ path contains           │──── yes ───▶  PENDING_REVIEW
                 │ payment/fraud/compliance │               (human must approve/reject —
                 └────────────┬─────────────┘                no auto-publish, any score)
                              │ no
                              ▼
                 ┌─────────────────────────┐
                 │ confidence >= 0.90 ?     │──── yes ───▶  AUTO_PUBLISHED
                 └────────────┬─────────────┘               (stub live immediately)
                              │ no
                              ▼
                 ┌─────────────────────────┐
                 │ confidence >= 0.70 ?     │──── yes ───▶  PENDING_REVIEW
                 └────────────┬─────────────┘
                              │ no
                              ▼
                        REJECTED
                (too many unclassified fields —
                 stub generated but never registered)
```

### 4. Why these specific numbers

These thresholds are declared up front in `QUALITY_CRITERIA.md` (Section 3, "Confidence
Thresholds for Auto-Publishing") — they aren't tuned after the fact. The `>= 0.90` auto-publish
bar means at most ~1 in 10 fields can be unclassified (and zero high-risk-unknown ones) before a
stub is allowed to go live untouched by a human; the `< 0.70` reject floor exists because a stub
built mostly from fields the sanitizer couldn't make sense of is more likely to echo something it
shouldn't than to be a useful stub at all.

### 5. Moving a record between states after ingest, and checking what happened

`PENDING_REVIEW` is not a dead end — the Control Plane API lets a human see it and move it
forward or back:

- `GET /api/v1/recording/history?limit=200` → `RecordingService.getHistory()` — every ingested
  record, newest first, with its current `status` (`AUTO_PUBLISHED` / `PENDING_REVIEW` /
  `REJECTED` / `PROCESSING` / `ERROR`). This is how a reviewer finds what's waiting.
- `POST /api/v1/recording/{recordId}/approve` → `RecordingService.approveRecord()` → status
  becomes `AUTO_PUBLISHED` (and the stub is registered if it wasn't already).
- `POST /api/v1/recording/{recordId}/reject` → `RecordingService.rejectRecord()` → status becomes
  `REJECTED`.

Both approve/reject are no-ops (logged, not thrown) if the record isn't currently
`PENDING_REVIEW` — you can't "approve" something that's already `AUTO_PUBLISHED` or already
`REJECTED`.

---

## Design Decisions

1. **Matching data structures** — HTTP uses a Copy-on-Write `PathTrie` (lock-free reads at 998K RPS; `insertDirect()` mutates in place at startup before any readers exist, `insert()` deep-copies + atomically swaps the root reference at runtime). gRPC and Kafka use per-method/per-topic `ConcurrentHashMap<String, List<StubDefinition>>` buckets instead — there's no path hierarchy to walk for a flat method/topic name, so a trie would be pure overhead. Every candidate list (trie node or hash bucket) is pre-sorted by `specificity()` at insertion time, so the hot-path read is a straight linear scan in priority order, never a sort.

2. **State storage** — Nested `ConcurrentHashMap` (3 levels: `session → collection → entity`). Physical isolation, not a runtime check: each session owns its own CHM object, so a cross-session read returns `null` by construction — there's no shared map with a tenant-ID filter to forget. Per-session `AtomicLong` counters drive ID generation (`{{generate.id:PREFIX}}`), so two sessions creating "the first ACC" independently both get `ACC-0001` without colliding. Verified with 16 sessions × 5,000 ops = 160K assertions, zero violations.

3. **Concurrency model** — Lock-free on every hot-path read; locking (where it exists at all) is confined to rare administrative writes, and even then it's copy-then-swap, never in-place mutation. `PathTrie`: volatile root reference, CoW on write. `SessionStateStore`: plain `ConcurrentHashMap` composition — no coarse lock across sessions, `computeIfAbsent` handles the one legitimate race (first write to a brand-new session/collection). `DriftSimulator`: `volatile` config + `ThreadLocalRandom` (thread-local by design, zero contention). `StubMatchingEngine` itself: a pure function with no instance state to protect at all — nothing to lock because there's nothing shared to mutate. `TrafficLogger`: a fixed-size ring buffer with `AtomicInteger`/CAS writes, never a dynamically-resized structure that could need a lock.

4. **Sanitization logic** — 4-layer defense-in-depth, cheapest check first: Layer 1 field-name `Set` lookup, Layer 2 `instanceof` type check, Layer 3 compiled regex + Luhn checksum (catches a card number even if layer 1 missed because the field was named something unexpected), Layer 4 entropy heuristic for anything still unclassified. Masks render as `****1111` (cards), `****@masked.com` (emails), `****6789` (SSN) — enough left visible to debug a failing test, never enough to leak the real value.

5. **UI integration** — Server-rendered Thymeleaf, not a SPA. Deliberate: this is a single Spring Boot process, and Thymeleaf means the UI ships inside the exact same JAR as the API with zero separate frontend build pipeline (no npm/webpack/node toolchain to keep in sync with the Java build, no separate deploy artifact). Dynamic behavior — 3-second polling refresh on the Traffic page (`setInterval` + `fetch()`), stub enable/disable toggles, session CRUD, drift config — is hand-rolled vanilla JS calling the same REST Control Plane endpoints and rebuilding DOM fragments directly, not a declarative framework. (Note: an `htmx.org` script tag was present in an earlier iteration but was never actually wired to any `hx-*` attribute or JS-API call anywhere in the templates — confirmed dead weight, removed.) Bootstrap 5 covers responsive/accessible styling without hand-rolled CSS. Net result: `mvn package` produces one artifact, one process serves both the API and the UI — consistent with this service having zero other external runtime dependencies either (see "Why no Docker Compose" above).

### Additional Decisions

6. **`session_id` mandatory in `/api/v1/virtual` for all protocols (HTTP, gRPC, Kafka)** — The virtual API enforces `session_id` as a required field regardless of protocol. A missing or blank value returns an immediate `400` with a descriptive error:

   ```json
   {
     "error": "session_id is required",
     "hint":  "Every virtual request must include a non-blank session_id for state isolation"
   }
   ```

   **Why mandatory — the create/get state chain:** When a stub has a `stateAction WRITE`, it stores data under `session_id → collection → key`. A subsequent `stateAction READ` stub looks up data under the **same `session_id`**. If the caller omits `session_id` there is no way to associate a `POST /accounts` create with a later `GET /accounts/{id}` read — the read stub returns 404 because state was never written to any session. Without enforcement the platform would silently accept requests, write to a random or default session, and confuse callers who cannot explain why their `GET` after a `POST` returns 404.

   The same key name (`session_id`) is used for all three protocols by design — HTTP, gRPC and Kafka stubs can share state across protocols inside one session (e.g., an HTTP `POST /payments` stub writes state, a Kafka stub in the same session reads it).

7. **X-Session-Id mandatory for HTTP forwarding proxy** — All HTTP stub calls via actual URLs (e.g. `POST /api/v1/accounts`) must supply `X-Session-Id`. Missing header returns `400`. Prevents shared "default" session leaking data between concurrent CI/CD runners.

8. **Confidence-scored auto-publishing** — Stubs auto-generated from traffic get confidence scores. ≥0.90 → auto-publish into live registry. 0.70–0.89 → pending review. <0.70 → rejected. High-risk unknown fields penalise score by 0.05 each. Prevents low-quality stubs entering registry.

9. **Smart StubGenerator — dynamic echo + auto GET stubs** — On ingest, response fields that also appear in the (non-masked) request body become `{{request.body.FIELD}}` templates rather than static values. ID-shaped fields not in the request become `{{generate.id:PREFIX}}`. For HTTP GET, if the path contains `{paramName}`, the stub is auto-created with `stateAction READ` using the last path param as key and `{{state.result}}` as body — no manual registration needed. GET paths without a param return 400. Multiple params (e.g. `/{deptId}/{empId}`) use the last one.

10. **Manual JSONPath body matching** — No Jayway library. `resolvePath()` supports `$.field`, `$.nested.field`, `$.items[0].field`. Stateless pure function — no locks. Avoids library overhead on the hot path.

---

## NFRs & Concurrency Model

| NFR | Implementation |
|-----|----------------|
| p99 latency < 10ms | CoW trie (lock-free reads) + ConcurrentHashMap (no locks on reads) + stateless engine |
| 20,000+ RPS | No contention on hot path. Drift uses `volatile` + `ThreadLocalRandom` (thread-local, zero contention) |
| Session isolation | 3-level nested CHM — physical separation, not runtime guards |
| Zero cross-session leakage | Different session → different CHM object → null by construction |
| Concurrent stub registration | CoW trie: readers never blocked. Writers pay O(trie-size) copy cost |
| Async event delivery | 4 ScheduledExecutorService daemon threads, fire-and-forget |
| Traffic logging | AtomicInteger ring buffer — CAS increment, no locks, circular overwrite |
| PCI/PII safety | Compiled regex patterns (precompiled at startup). Luhn check on numeric strings |

---

## What I Would Do Differently With More Time

- **Authentication & RBAC** — Add an auth server with role-based access control. Reviewers would approve/reject `PENDING_REVIEW` stubs, admins would manage drift config and governance settings, and read-only roles would cover CI/CD service accounts.
- **Distributed deployment with Redis** — Multiple application servers in production sharing a common Redis instance (with snapshots for durability). All hot-path operations (stub matching, session state, traffic logging) would run against Redis for sub-ms latency across nodes. Persistent storage (PostgreSQL) would back stubs, audit logs, and recording history — Redis for real-time, PostgreSQL for durability.
- **Audit trail** — Every stub match attempt (hit or miss) persisted to storage with full request context, matched stub ID, response returned, and latency. Enables post-hoc review of what the platform served to each test run.
- **Enhanced UI** — More screens: ingested records vs. generated stubs side-by-side, session state inspector, audit log viewer, stub diff view. Editable TTL and governance scheduler intervals directly from the dashboard. Visual pipeline showing ingest → sanitize → score → publish flow.
- **Metrics & observability** — Prometheus exporters for p99 latency, match hit/miss rate, session count, stub count by protocol, sanitization stats, drift injection counts. Grafana dashboards for production alerting and capacity planning.
- **WebSocket traffic streaming** — Currently plain JS polls every 3s (`setInterval` + `fetch`). WebSocket push would give true real-time traffic display without polling overhead.
- **OpenAPI schema validation** — Auto-validate generated stubs against the downstream service's OpenAPI spec to catch response shape drift before it reaches CI.
- **Real gRPC** — Add protobuf schema parsing and a real gRPC server endpoint, so clients can call the platform using native gRPC binary protocol instead of the JSON-over-HTTP simulation.
- **Dockerization** — Multi-stage `Dockerfile` for minimal production image (`eclipse-temurin:21-jre`), plus `docker-compose.yml` orchestrating the app, Redis, and PostgreSQL for the distributed deployment mode.

---

## Test Coverage Summary

| Category | Tests | What's Tested |
|----------|-------|---------------|
| Stub Matching Engine | 96 | Path matching, body JSONPath, headers, state read/write/delete, gRPC, Kafka, 404, 500/503 stubs, async events. 33 parametrised scenarios from `stub_match_scenarios.json` — complex path matching, multi-field JSONPath, session state mutation, multi-tenant isolation, fallback stub match. Deep nesting 4+ levels, arrays, array-of-arrays, mixed types. Case-insensitive header matching, multi-condition, empty/null headers |
| Control Plane REST API | 43 | ControlPlaneApiTest: REST contract for all 3 protocols, session CRUD, ingest, drift config, full POST→GET round-trip, same-path method isolation |
| PCI/PII Sanitization | 42 | PciPiiSanitizerTest (13): Visa/MC/Amex/SSN/CVV/email/bearer token masking, Luhn validation. SanitizationAdversarialDatasetTest (24): look-alike numbers, partial SSNs, nested PII. SanitizationGoldenDatasetTest (5): 4 buckets incl. look-alikes |
| Stub Management | 20 | StubManagementTest: search by query/protocol/method/path/source/enabled, sort modes, response update, same-path override, stubId collision |
| Stub Generator (Ingest Pipeline) | 16 | StubGeneratorTest (12): ID templates, static fields, request-field echo, masked-field exclusion, GET stub with path param, gRPC/Kafka echo. TaskSpecIngestionTest (4): full ingest→sanitize→generate→register→invoke |
| Outbound Forwarding Proxy | 16 | OutboundForwardingTest: forward-on-miss, auto-record pipeline, WireMock upstream, proxy config API, stats tracking, 4xx/5xx capture |
| Path Trie (CoW) | 14 | PathTrieTest (12): wildcards, specificity ordering, method isolation, concurrent writes. PathParamTest (2): parameter extraction and variable capture |
| Forwarding Proxy | 11 | ForwardingProxyTest: actual URL routing, stateful round-trips, session isolation, mandatory X-Session-Id (400), admin bypass |
| UI Page Rendering | 11 | DashboardControllerUiTest: all 7 UI pages render without 500; filter/search/sort on stubs & sessions |
| Session/Traffic/Drift/Recording Gaps | 8 | SessionTrafficDriftRecordingGapsTest: session TTL, traffic logging, drift config, recording history edge cases |
| Embedded Kafka | 7 | KafkaConsumerEmbeddedTest (4): real KRaft broker, @KafkaListener consume→match→produce round-trip. KafkaProducerEmbeddedTest (3): KafkaTemplate publish, session ID extraction |
| Drift Simulation | 7 | DriftSimulationTest: latency injection, error rate, jitter, per-route config, metrics, no-drift pass-through |
| Performance & Benchmarks | 6 | PerformanceBenchmarkTest: p99 latency, 800K+ RPS throughput, 16-session isolation, sanitization precision/recall, resilience |
| Session Isolation | 5 | SessionIsolationTest: 3-level ConcurrentHashMap isolation, concurrent writes, cross-session 404, person SSN+salary isolation |
| Stub Router Concurrency | 5 | StubRouterConcurrencyTest: concurrent register/deregister, CoW Trie consistency under contention |
| Stub Governance (TTL) | 2 | StubGovernanceSchedulerTest: expired stub cleanup sweep, TTL enforcement |
| **Total** | **313** | **25 test classes, 0 failures** |

### What's NOT Tested
- Real gRPC binary protocol handling (simulated via JSON over `/api/v1/virtual`)


---

## Stretch Goal — Live Outbound Forwarding Proxy — Record on First Miss, Replay on Every Hit After

#### Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT (curl / test runner)                      │
│                                                                         │
│   curl -X POST http://localhost:8080/orders/place                       │
│        -H "X-Session-Id: demo"                                          │
│        -d '{"item":"Laptop","qty":1,"currency":"USD"}'                  │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                SVP (port 8080) — ForwardingProxyController              │
│                                                                         │
│   1. Receive request at /** catch-all                                   │
│   2. Check: is there a matching stub in StubRouter?                     │
│                                                                         │
│   ┌─── YES (stub hit) ──────────────────────────────────────────────┐   │
│   │  Return stub response immediately.                              │   │
│   │  Upstream is NEVER called. totalForwarded stays the same.       │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│   ┌─── NO (stub miss, 404) ────────────────────────────────────────┐   │
│   │  Is proxy enabled? (proxy.upstream.record-on-miss=true)        │   │
│   │                                                                 │   │
│   │  NO  → return 404 to client (normal behavior)                  │   │
│   │                                                                 │   │
│   │  YES → OutboundForwardingService.forwardAndRecord():           │   │
│   │     ┌──────────────────────────────────────────────────────┐   │   │
│   │     │ a) Forward request to upstream (WireMock / real API) │   │   │
│   │     │ b) Capture upstream response (any status code)       │   │   │
│   │     │ c) RecordingService.ingest() → sanitize → generate   │   │   │
│   │     │    → auto-publish stub into StubRouter               │   │   │
│   │     │ d) Return upstream response to client                │   │   │
│   │     └──────────────────────────┬───────────────────────────┘   │   │
│   │                                │                                │   │
│   │     Next identical request → stub exists → step 1 hits YES     │   │
│   │     → upstream NEVER called again for this request shape       │   │
│   └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                    (only on stub miss + proxy enabled)
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│           UPSTREAM (port 9999) — WireMock / real service / mock         │
│                                                                         │
│   Receives the forwarded request, returns its response.                 │
│   After auto-record, SVP never calls here again for the same request.  │
└─────────────────────────────────────────────────────────────────────────┘
```

#### Step-by-Step Walkthrough with Proof

You need **3 terminals** running simultaneously:

---

**TERMINAL 1 — Start mock upstream (simulates WireMock) on port 9999**

This prints a log line every time it receives a request — the proof that upstream was/wasn't called.

```bash
python3 -c '
from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({
            "order_id": "ORD-8888",
            "status": "CONFIRMED",
            "amount": 199.99,
            "currency": "USD",
            "region": "US"
        }).encode())
    def log_message(self, fmt, *args):
        print(f"[UPSTREAM] {args[0]}")

print("Mock upstream (WireMock substitute) listening on :9999")
print("Watch this terminal — every request prints a log line.")
print("When the stub answers instead, this terminal stays SILENT.\n")
HTTPServer(("localhost", 9999), Handler).serve_forever()
'
```

**TERMINAL 2 — Start SVP app on port 8080**

```bash
mvn spring-boot:run
```

**TERMINAL 3 — Run the test curls**

```bash
# ═══════════════════════════════════════════════════════════════════════
# STEP 1: Enable the outbound proxy → point at mock upstream
# ═══════════════════════════════════════════════════════════════════════

curl -s -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"baseUrl": "http://localhost:9999", "recordOnMiss": true}'
# → {"message":"Proxy configuration updated","enabled":true,
#    "baseUrl":"http://localhost:9999","recordOnMiss":true}


# ═══════════════════════════════════════════════════════════════════════
# STEP 2: FIRST CALL — no stub exists → forwarded to upstream (WireMock)
#
# PROOF: Terminal 1 prints "[UPSTREAM] POST /orders/place HTTP/1.1"
# ═══════════════════════════════════════════════════════════════════════

curl -s -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: demo" \
  -d '{"item": "Laptop", "quantity": 1, "currency": "USD"}'
# → {"order_id":"ORD-8888","status":"CONFIRMED","amount":199.99,
#    "currency":"USD","region":"US"}
#
# ✅ This response came FROM the upstream (WireMock).
# ✅ Terminal 1 shows: [UPSTREAM] POST /orders/place HTTP/1.1
# ✅ SVP auto-ingested it and created a stub.


# ═══════════════════════════════════════════════════════════════════════
# STEP 3: VERIFY — stub was auto-created
#
# PROOF: The stub appears in the stub list and recording history.
# ═══════════════════════════════════════════════════════════════════════

# 3a. Check recording history — should show AUTO_PUBLISHED
curl -s http://localhost:8080/api/v1/recording/history?limit=1 | python3 -m json.tool
# → [{"id": "rec-xxxx", "status": "AUTO_PUBLISHED",
#     "rawLog": {"request": {"path": "/orders/place", "method": "POST", ...}}}]

# 3b. Check proxy stats — 1 forwarded, 1 recorded
curl -s http://localhost:8080/api/v1/proxy/stats | python3 -m json.tool
# → {"totalForwarded": 1, "totalRecorded": 1,
#    "recentForwards": [{"method":"POST","path":"/orders/place",
#     "upstreamStatus":200,"ingestStatus":"AUTO_PUBLISHED","recordId":"rec-xxxx"}]}

# 3c. Find the auto-generated stub in stub list
curl -s "http://localhost:8080/api/v1/stubs?path=/orders/place" | python3 -m json.tool
# → Shows the auto-generated stub with:
#   stubId: "auto__orders_place_POST_..."
#   requestMatcher: {
#     protocol: "HTTP", method: "POST", pathPattern: "/orders/place",
#     bodyJsonPath: {"$.item":"Laptop", "$.quantity":"1", "$.currency":"USD"}
#   }
#   responseTemplate: {
#     statusCode: 200,
#     body: {"order_id":"ORD-8888","status":"CONFIRMED","amount":199.99,
#            "currency":"{{request.body.currency}}","region":"US"}
#   }


# ═══════════════════════════════════════════════════════════════════════
# STEP 4: SECOND CALL — same request → stub answers, upstream NOT called
#
# PROOF: Terminal 1 stays SILENT (no new log line).
# PROOF: totalForwarded stays at 1 (no increment).
# ═══════════════════════════════════════════════════════════════════════

curl -s -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: demo" \
  -d '{"item": "Laptop", "quantity": 1, "currency": "USD"}'
# → {"order_id":"ORD-8888","status":"CONFIRMED","amount":199.99,
#    "currency":"USD","region":"US"}
#
# ✅ Same response — but this time it came FROM THE STUB, not upstream.
# ✅ Terminal 1: SILENT — no "[UPSTREAM]" log line. WireMock was NOT called.

# PROOF: totalForwarded is still 1
curl -s http://localhost:8080/api/v1/proxy/stats | python3 -c "
import sys,json; d=json.load(sys.stdin)
print(f'totalForwarded: {d[\"totalForwarded\"]} (still 1 — stub answered)')
print(f'totalRecorded:  {d[\"totalRecorded\"]}')
"
# → totalForwarded: 1 (still 1 — stub answered)
# → totalRecorded:  1


# ═══════════════════════════════════════════════════════════════════════
# STEP 5: ULTIMATE PROOF — kill the upstream, request still works
#
# If the stub is truly answering, killing the upstream should have
# zero effect on the second call. The response still comes back.
# ═══════════════════════════════════════════════════════════════════════

# Kill Terminal 1 (Ctrl+C the python server), then:
curl -s -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: demo" \
  -d '{"item": "Laptop", "quantity": 1, "currency": "USD"}'
# → STILL returns {"order_id":"ORD-8888","status":"CONFIRMED",...}
# ✅ Upstream is DEAD, but the stub answers. Proof complete.


# ═══════════════════════════════════════════════════════════════════════
# STEP 6: Disable proxy
# ═══════════════════════════════════════════════════════════════════════

curl -s -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"recordOnMiss": false}'
# → {"enabled": false, ...}
```

#### How to Verify — Summary of Proof Points

| What to check | How | Expected |
|---|---|---|
| Upstream was called on 1st request | Terminal 1 shows `[UPSTREAM] POST /orders/place` | ✅ Log line appears |
| Stub was auto-created | `GET /api/v1/stubs?path=/orders/place` | ✅ Stub with `auto_` prefix exists |
| Recording history has the ingest | `GET /api/v1/recording/history?limit=1` | ✅ `status: AUTO_PUBLISHED` |
| Upstream NOT called on 2nd request | Terminal 1 stays **silent** | ✅ No new log line |
| `totalForwarded` didn't increment | `GET /api/v1/proxy/stats` → `totalForwarded` | ✅ Still `1` |
| Stub works even after killing upstream | Kill Terminal 1, repeat curl | ✅ Response still returns |

#### `record-on-miss` Behaviour Reference

| Setting | Behaviour |
|---|---|
| `record-on-miss=false` (default) | Platform is a **pure stub server**. If no stub matches → 404. The upstream is never contacted. |
| `record-on-miss=true` | Platform becomes a **recording proxy**. If no stub matches → forward to `base-url` upstream → capture response → auto-generate a stub via `RecordingService.ingest()` → return real response. Next identical call hits the auto-generated stub (no upstream call). |

> **Note:** Both settings require `base-url` to be non-blank. `OutboundForwardingTest` overrides these via `@DynamicPropertySource` — keep `record-on-miss=false` in `application.properties` so unit tests that expect 404 on stub miss are not broken.

#### Configuration

Two properties (disabled by default):
```properties
# application.properties (or set at runtime via API)
proxy.upstream.base-url=http://real-service:8080
proxy.upstream.record-on-miss=true
```

Runtime API (no restart required):
```bash
# Enable
curl -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"baseUrl": "http://real-service:8080", "recordOnMiss": true}'

# Check status
curl http://localhost:8080/api/v1/proxy/config

# Disable
curl -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"recordOnMiss": false}'
```

#### For Production — Real Upstream (not mock)

Replace `http://localhost:9999` with your real service URL. The flow is identical:

```bash
# Point at real service
curl -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"baseUrl": "http://my-real-api.internal:8080", "recordOnMiss": true}'

# First call → hits real service → auto-records stub
# Second call → stub answers → real service not called
# Once all endpoints are recorded, disable proxy:
curl -X PUT http://localhost:8080/api/v1/proxy/config \
  -H "Content-Type: application/json" \
  -d '{"recordOnMiss": false}'
# SVP is now a fully offline stub server with realistic captured responses.
```

**Key points:**
- **WireMock is test-scope only** (`<scope>test</scope>` in pom.xml) — it simulates the upstream in automated JUnit tests. For manual testing, the python mock server above (or any HTTP server) works as the upstream.
- **Existing `POST /api/v1/recording/ingest` works exactly as before** — the proxy is additive
- **Headers forwarded** — original request headers are sent to upstream (except `X-Session-Id`, `Host`, `Connection`, `Content-Length`)
- **4xx/5xx captured** — upstream error responses are returned as-is (not converted to 502) and also auto-recorded
- **Admin endpoints never forwarded** — `/api/v1/stubs`, `/api/v1/sessions`, `/api/v1/proxy`, etc. always route to their controllers

---

## Stretch Goal — Embedded Kafka (Real Broker, Real Topics)

#### What Is Being Virtualized

The platform already handles `protocol=KAFKA` stubs **in-memory** via `POST /api/v1/virtual`. This stretch goal adds a **real embedded Kafka broker** (KRaft mode via `spring-kafka-test`) so messages flow through actual Kafka topics — proving the platform can virtualize Kafka-based downstream services with real wire protocol.

**Real-world scenario:** An app-under-test publishes payment messages to a Kafka topic. In production, a downstream payment processor consumes those messages and publishes results. During testing, **the virtual service replaces that downstream** — consuming from the input topic, matching against stubs, and producing stubbed responses to the output topic.

#### Architecture

```
┌──────────────────┐                                    ┌──────────────────────────────┐
│  App-Under-Test  │                                    │  VIRTUAL SERVICE             │
│  (or test code)  │                                    │  (replaces real downstream)  │
│                  │     topic: "payments"               │                              │
│  Publishes ──────┼───────────────────────────────────▶│  KafkaVirtualConsumer        │
│  payment msg     │                                    │   @KafkaListener("payments") │
│                  │                                    │   Consumes message            │
│                  │                                    │   Converts → VirtualRequest   │
│                  │                                    │   Delegates to ↓              │
│                  │                                    │                              │
│                  │                                    │  StubMatchingEngine          │
│                  │                                    │   (EXISTING, UNTOUCHED)       │
│                  │                                    │   kafkaIndex["PAYMENTS"]      │
│                  │                                    │   → body JSONPath match       │
│                  │                                    │   → session state mutation    │
│                  │                                    │   → returns VirtualResponse   │
│                  │                                    │                              │
│                  │     topic: "payment-completed"      │  KafkaEventProducer          │
│  Consumes ◀──────┼───────────────────────────────────│   KafkaTemplate.send()        │
│  result          │                                    │   Publishes stubbed response  │
└──────────────────┘                                    └──────────────────────────────┘
                            ┌────────────────────┐
                            │ Embedded KRaft     │
                            │ Broker (in-process,│
                            │ spring-kafka-test) │
                            └────────────────────┘
```

#### Component Roles

| Component | Role | HTTP Analogy |
|---|---|---|
| **Kafka topic `"payments"`** | Input channel — where the app publishes messages | Like the URL `POST /api/v1/payments` |
| **KafkaVirtualConsumer** (`@KafkaListener` on `"payments"`) | Consumes messages from input topic, converts to `VirtualRequest`, delegates to matching engine | Like `VirtualController.handleVirtualRequest()` |
| **StubMatchingEngine** | Matches message body against Kafka stubs (`kafkaIndex`), mutates session state | **Identical** — same engine, same stubs, `protocol=KAFKA` |
| **KafkaEventProducer** (produces to `"payment-completed"`) | Publishes the stubbed response to the output topic | Like `ResponseEntity.ok(response)` — the response going back to the caller |
| **Kafka topic `"payment-completed"`** | Output channel — where the app consumes responses | Like the HTTP response body |

#### Session ID Handling

Every Kafka message must carry a session ID for state isolation. Extraction strategy:
1. **Kafka header `X-Session-ID`** (preferred) — clean, doesn't pollute the message key
2. **Message key** — fallback, key is used as session ID directly
3. **Default** — if neither present, uses `"kafka-default"` session

#### Technology

- **`spring-kafka`** — production dependency for `KafkaTemplate` and `@KafkaListener`
- **`spring-kafka-test`** (`@EmbeddedKafka`) — test-scope dependency, runs a real KRaft broker in-process (~2-3s startup, no Docker required)
- **`@ConditionalOnProperty("kafka.enabled")`** — Kafka module is opt-in; existing tests and default startup are unaffected

#### New Files (zero changes to existing code)

```
src/main/java/.../kafkaembedded/
    KafkaVirtualConfig.java          — @Configuration, topic names, serializers, conditional activation
    KafkaVirtualConsumer.java        — @KafkaListener on input topics, converts to VirtualRequest
    KafkaEventProducer.java          — KafkaTemplate wrapper, publishes stub responses to output topics
    KafkaSessionIdExtractor.java     — Extracts session ID from Kafka headers/key

src/test/java/.../kafkaembedded/
    EmbeddedKafkaIntegrationTest.java   — Full round-trip: publish → consume → match → produce → assert
    KafkaSessionIsolationTest.java      — Proves two sessions on same topic don't leak state
```

#### How to Test

```bash
# All Kafka tests use @EmbeddedKafka — no Docker needed, no external broker
mvn test -pl . -Dtest="com.virtualization.servicevirtualizationplatform.kafkaembedded.*"
```

Example test flow:
1. Register a Kafka stub: `protocol=KAFKA`, `method=payments`, `bodyJsonPath: {$.currency: USD}`
2. Publish a real Kafka message to topic `"payments"` with body `{"amount": 100, "currency": "USD"}`
3. `KafkaVirtualConsumer` consumes it → `StubMatchingEngine` matches → state mutated
4. `KafkaEventProducer` publishes response to `"payment-completed"`
5. Test consumes from `"payment-completed"` and asserts the stubbed response body

---

## Appendix: Sample cURL — Complete Workflows

All curls assume the app is running on `http://localhost:8080` (`mvn spring-boot:run`).

### A. Stub Management — Manual Registration

Register stubs via `POST /api/v1/stubs` with full control over matching conditions, response
templates, state actions, and async events.

```bash
# ═══ Register POST stub — creates product, writes to session state ═══
curl -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId":   "stub_product_create",
    "requestMatcher": {
      "protocol": "HTTP", "method": "POST", "pathPattern": "/api/v1/products",
      "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 201,
      "headers": {"Content-Type": "application/json"},
      "body": {
        "product_id": "{{generate.id:PRD}}",
        "name":       "{{request.body.name}}",
        "price":      "{{request.body.price}}",
        "status":     "AVAILABLE"
      }
    },
    "stateAction": {
      "op": "WRITE", "collection": "products",
      "keyExpr": "{{response.body.product_id}}", "valueExpr": "{{response.body}}"
    }
  }'

# ═══ Register GET stub — reads from state via path variable ═══
curl -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId":   "stub_product_get",
    "requestMatcher": {
      "protocol": "HTTP", "method": "GET", "pathPattern": "/api/v1/products/{id}",
      "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": {
      "statusCode": 200, "headers": {"Content-Type": "application/json"},
      "body": "{{state.result}}"
    },
    "stateAction": { "op": "READ", "collection": "products", "keyExpr": "{{request.pathVar.id}}" }
  }'

# ═══ Register 404 fallback (same path, lower specificity) ═══
curl -X POST http://localhost:8080/api/v1/stubs \
  -H "Content-Type: application/json" \
  -d '{
    "stubId": "stub_product_not_found",
    "requestMatcher": {
      "protocol": "HTTP", "method": "GET", "pathPattern": "/api/v1/products/{id}",
      "headers": {}, "bodyJsonPath": {}
    },
    "responseTemplate": { "statusCode": 404, "headers": {}, "body": {"error": "Product not found"} }
  }'

# ═══ List, search, toggle, patch, delete stubs ═══
curl "http://localhost:8080/api/v1/stubs?protocol=HTTP&method=POST&limit=10"
curl http://localhost:8080/api/v1/stubs/stub_product_create
curl -X POST http://localhost:8080/api/v1/stubs/stub_product_create/toggle
curl -X PATCH http://localhost:8080/api/v1/stubs/stub_product_create/response \
  -H "Content-Type: application/json" \
  -d '{"statusCode": 503, "headers": {}, "body": {"error": "Service unavailable"}}'
curl -X DELETE http://localhost:8080/api/v1/stubs/stub_product_create
```

---

### B. Dynamic Record Ingest — Auto-Generate Stubs from Raw Traffic

Ingest raw request/response traffic via `POST /api/v1/recording/ingest`. The platform
sanitizes PCI/PII, scores confidence, and auto-publishes the generated stub.

#### B1. HTTP Ingest

```bash
curl -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST", "path": "/api/v1/employees",
      "headers": {"Content-Type": "application/json"},
      "body": {"name": "Jane Doe", "ssn": "123-45-6789", "salary": 95000, "department": "ENGINEERING"}
    },
    "response": {
      "statusCode": 201, "headers": {"Content-Type": "application/json"},
      "body": {"employee_id": "EMP-0001", "name": "Jane Doe", "salary": 95000, "status": "ACTIVE"}
    }
  }'
# → AUTO_PUBLISHED, confidence: 1.0
# → SSN sanitized (not in stub), salary preserved
# → bodyJsonPath: {"$.name":"Jane Doe", "$.salary":"95000", "$.department":"ENGINEERING"}
# → response body: employee_id static, name/salary echoed via {{request.body.X}}

# Invoke the auto-generated stub
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "emp-sess", "protocol": "HTTP", "method": "POST",
    "path": "/api/v1/employees", "headers": {},
    "body": {"name": "Jane Doe", "salary": 95000, "department": "ENGINEERING"}
  }'
# → 201 {"employee_id":"EMP-0001","name":"Jane Doe","salary":95000,"status":"ACTIVE"}
```

#### B2. Kafka Ingest

```bash
curl -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "KAFKA", "method": "inventory-updates", "path": "", "headers": {},
      "body": {"event_type": "STOCK_DEPLETED", "product_id": "SKU-9981", "quantity": 0}
    },
    "response": {
      "statusCode": 200, "headers": {},
      "body": {"ack": true, "event_type": "STOCK_DEPLETED", "processed": true}
    }
  }'
# → AUTO_PUBLISHED, protocol: KAFKA, method: inventory-updates

# Invoke
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "kafka-sess", "protocol": "KAFKA", "method": "inventory-updates", "path": "",
    "headers": {}, "body": {"event_type": "STOCK_DEPLETED", "product_id": "SKU-9981", "quantity": 0}
  }'
# → {"matched_stub_id":"auto_inventory_updates_...","status_code":200,"body":{"ack":true,...}}
```

#### B3. gRPC Ingest

```bash
curl -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "GRPC", "method": "GetAccountSummary", "path": "",
      "headers": {"content-type": "application/grpc"},
      "body": {"account_id": "ACC-9001", "currency": "USD"}
    },
    "response": {
      "statusCode": 200, "headers": {"Content-Type": "application/grpc"},
      "body": {"account_id": "ACC-9001", "balance": 5000.00, "currency": "USD", "status": "ACTIVE"}
    }
  }'
# → AUTO_PUBLISHED, protocol: GRPC, method: GetAccountSummary

# Invoke
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "grpc-sess", "protocol": "GRPC", "method": "GetAccountSummary", "path": "",
    "headers": {}, "body": {"account_id": "ACC-9001", "currency": "USD"}
  }'
# → {"matched_stub_id":"auto_GetAccountSummary_...","status_code":200,"body":{"balance":5000.0,...}}
```

---

### C. Virtual API — Invoke Stubs

All three protocols use `POST /api/v1/virtual`. HTTP can also use actual URLs via the forwarding proxy.

#### `session_id` — Mandatory for All Protocols

`session_id` is **required** in every `/api/v1/virtual` call — HTTP, gRPC, and Kafka alike. Omitting it or sending a blank value returns `400` immediately, before the engine is ever invoked:

```bash
# Missing session_id → 400
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{"protocol":"KAFKA","method":"payments","body":{"amount":100}}'
# → 400
# {
#   "error": "session_id is required",
#   "hint":  "Every virtual request must include a non-blank session_id for state isolation"
# }
```

**Why it is mandatory — the create/get chain:**

```
Session "ci-run-42"                         Session "ci-run-99"
─────────────────────────────────           ─────────────────────────────────
POST /api/v1/accounts                       POST /api/v1/accounts
  session_id: "ci-run-42"                    session_id: "ci-run-99"
  → stub WRITES state:                       → stub WRITES state:
    accounts["ACC-0001"] = {Alice}             accounts["ACC-0001"] = {Bob}

GET /api/v1/accounts/ACC-0001               GET /api/v1/accounts/ACC-0001
  session_id: "ci-run-42"                    session_id: "ci-run-99"
  → stub READS accounts["ACC-0001"]          → stub READS accounts["ACC-0001"]
  → 200 {Alice}  ✅                          → 200 {Bob}  ✅  (not Alice — isolated)
```

Without `session_id` there is no key to associate a `POST` write with a subsequent `GET` read. The read stub would search an unknown or default session, find nothing, and return 404 — no error thrown, just silent confusion.

The same `session_id` value is shared across all three protocols. An HTTP stub that writes state and a Kafka stub that reads it in the same session will correctly share data, as long as both carry the same `session_id`.

---

#### C1. HTTP — via /api/v1/virtual

```bash
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "s1", "protocol": "HTTP", "method": "POST",
    "path": "/api/v1/accounts", "headers": {},
    "body": {"account_holder": "Jane Doe", "initial_deposit": 500.0, "currency": "USD"}
  }'
# → {"matched_stub_id":"stub_account_create_v1","status_code":201,
#    "body":{"account_id":"ACC-0001","account_holder":"Jane Doe","balance":500.0,"status":"ACTIVE"}}
```

#### C2. HTTP — via Forwarding Proxy (actual URLs + X-Session-Id)

```bash
# POST via actual URL
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: proxy-sess" \
  -d '{"account_holder": "Alice", "initial_deposit": 500.0, "currency": "USD"}'
# → 201 {"account_id":"ACC-0001","account_holder":"Alice","balance":500.0,"status":"ACTIVE"}

# GET via actual URL
curl http://localhost:8080/api/v1/accounts/ACC-0001 -H "X-Session-Id: proxy-sess"
# → 200 {"account_id":"ACC-0001","account_holder":"Alice",...}

# Different session → 404 (isolated)
curl http://localhost:8080/api/v1/accounts/ACC-0001 -H "X-Session-Id: other-sess"
# → 404

# Missing X-Session-Id → 400
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"account_holder":"NoSession","currency":"USD","initial_deposit":100.0}'
# → 400 {"error":"Missing required header: X-Session-Id"}
```

#### C3. gRPC

```bash
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "g1", "protocol": "GRPC", "method": "CreateAccount", "path": "",
    "headers": {}, "body": {"account_holder": "gRPC User", "currency": "USD"}
  }'
# → {"matched_stub_id":"stub_grpc_create_account","status_code":200,
#    "body":{"account_holder":"gRPC User",...}}
```

#### C4. Kafka

```bash
curl -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "k1", "protocol": "KAFKA", "method": "payment-events", "path": "",
    "headers": {},
    "body": {"event_type": "PAYMENT_COMPLETED", "payment_id": "PAY-001", "amount": 150.0}
  }'
# → {"matched_stub_id":"stub_kafka_payment_completed","status_code":200,
#    "body":{"ack":true,"event_type":"PAYMENT_COMPLETED","processed":true}}
```

---

### D. PCI/PII Sanitization — Ingest with Sensitive Data

#### D1. Credit card + CVV masked

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

echo $INGEST | jq '{status: .status, confidence: .confidence, pci_masked: .requestSummary.pciFieldsMasked}'
# → {"status":"AUTO_PUBLISHED","confidence":1.0,"pci_masked":2}

echo $INGEST | jq '.generatedStub.requestMatcher.bodyJsonPath'
# → {"$.amount":"250.0","$.currency":"USD"}   ← card/cvv EXCLUDED

echo $INGEST | jq '.generatedStub | tostring | test("4111")'
# → false   ← card number NOT in stub
```

#### D2. SSN + email masked

```bash
curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST", "path": "/api/v1/users",
      "headers": {},
      "body": {"name": "John Doe", "email": "john@example.com", "ssn": "123-45-6789", "age": 35}
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {"user_id": "USR-0001", "name": "John Doe", "status": "ACTIVE"}
    }
  }' | jq '{status: .status, pii_masked: .requestSummary.piiFieldsMasked}'
# → {"status":"AUTO_PUBLISHED","pii_masked":2}
# email → ****@masked.com, SSN → ****6789
```

#### D3. Nested + array with PCI inside

```bash
curl -s -X POST http://localhost:8080/api/v1/recording/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
      "protocol": "HTTP", "method": "POST", "path": "/api/v1/checkout",
      "headers": {"Content-Type": "application/json"},
      "body": {
        "customer_id": "CUS-001", "total": 549.98, "currency": "USD",
        "items": [{"name": "Laptop", "price": 499.99, "quantity": 1}],
        "payment_methods": [
          {"credit_card": "4111111111111111", "cvv": "321"},
          {"credit_card": "5500000000000004", "cvv": "654"}
        ]
      }
    },
    "response": {
      "statusCode": 201, "headers": {},
      "body": {"checkout_id": "CHK-9001", "customer_id": "CUS-001", "total": 549.98, "status": "CONFIRMED"}
    }
  }' | jq '{status: .status, pci_masked: .requestSummary.pciFieldsMasked}'
# → {"status":"AUTO_PUBLISHED","pci_masked":4}
# 4 = two credit_cards + two cvvs inside payment_methods[] array

# Invoke the sanitized stub
curl -s -X POST http://localhost:8080/api/v1/virtual \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "san-sess", "protocol": "HTTP", "method": "POST",
    "path": "/api/v1/checkout", "headers": {},
    "body": {"customer_id": "CUS-001", "total": 549.98, "currency": "USD"}
  }' | jq '.body'
# → {"checkout_id":"CHK-9001","customer_id":"CUS-001","total":549.98,"status":"CONFIRMED"}
# No card data in response — sanitized at ingest time.
```

---

### E. Async Events + Drift Simulation

#### E1. HTTP Stub Triggers Delayed Kafka Event

```bash
# The seed stub "stub_payment_create_v1" has asyncEvents configured.
# POST /api/v1/payments fires, schedules a Kafka event 500ms later.

curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"session_id": "async-test-session", "ttl_minutes": 30}'

curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: async-test-session" \
  -d '{"amount": 150.00, "currency": "USD"}'
# → 201 {"payment_id":"PAY-0001","status":"COMPLETED"}
# → 500ms later: Kafka event written to session state

sleep 1
curl http://localhost:8080/api/v1/sessions/async-test-session/state
# → {"collections":{"payments":{"PAY-0001":{...}},
#    "events:payment-events":{"evt-xxxx":{"event_type":"PAYMENT_COMPLETED","payment_id":"PAY-0001",...}}}}
```

#### E2. Drift — Global Latency + Error Injection

```bash
# 100ms latency + 20ms jitter + 10% random 500 errors
curl -X POST http://localhost:8080/api/v1/drift/config \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 100, "jitterMs": 20, "errorRate": 0.10}'

# Protocol-level drift (KAFKA only — HTTP unaffected)
curl -X POST "http://localhost:8080/api/v1/drift/config/protocol?protocol=KAFKA" \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 300, "jitterMs": 100, "errorRate": 0.50}'

# Method-level drift (specific Kafka topic only)
curl -X POST "http://localhost:8080/api/v1/drift/config/method?method=inventory-updates" \
  -H "Content-Type: application/json" \
  -d '{"baseLatencyMs": 500, "jitterMs": 0, "errorRate": 1.0}'

# Check which drift tier applies
curl "http://localhost:8080/api/v1/drift/check?method=inventory-updates&protocol=KAFKA"
# → {"tier":"METHOD","key":"inventory-updates","config":{"baseLatencyMs":500,"errorRate":1.0,...}}

# Drift metrics
curl http://localhost:8080/api/v1/drift/metrics
# → {"totalRequests":1500,"errorsInjected":74,"totalLatencyAdded":75000}

# Reset all drift
curl -X DELETE http://localhost:8080/api/v1/drift/config
```

> **Drift precedence:** route (HTTP path) > method/topic > protocol > global > none

---

### F. Session Management

```bash
# ═══ Create session with explicit TTL ═══
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"session_id": "my-ci-pipeline-42", "ttl_minutes": 120}'

# ═══ Auto-create on first request (no explicit create needed) ═══
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: auto-created-session" \
  -d '{"account_holder": "Alice", "currency": "USD", "initial_deposit": 500.0}'
# → Session auto-created on first state write

# ═══ List all active sessions ═══
curl http://localhost:8080/api/v1/sessions

# ═══ Inspect session state ═══
curl http://localhost:8080/api/v1/sessions/my-ci-pipeline-42/state
# → {"session_id":"...","collections":{"accounts":{"ACC-0001":{...}}}}

# ═══ Session isolation proof ═══
# Session A creates account
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" -H "X-Session-Id: sess-A" \
  -d '{"account_holder":"Alice","currency":"USD","initial_deposit":1000.0}'
# → 201 {"account_id":"ACC-0001","account_holder":"Alice",...}

# Session A reads → 200
curl http://localhost:8080/api/v1/accounts/ACC-0001 -H "X-Session-Id: sess-A"
# → 200

# Session B reads same ID → 404 (isolated)
curl http://localhost:8080/api/v1/accounts/ACC-0001 -H "X-Session-Id: sess-B"
# → 404

# ═══ Clear, delete entity, destroy session ═══
curl -X POST http://localhost:8080/api/v1/sessions/sess-A/clear
curl -X DELETE http://localhost:8080/api/v1/sessions/my-ci-pipeline-42/accounts/ACC-0001
curl -X DELETE http://localhost:8080/api/v1/sessions/my-ci-pipeline-42
```

---


