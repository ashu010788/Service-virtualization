# Quality Criteria — Enterprise Service Virtualization Platform

> This document defines pass/fail thresholds **before** implementation begins.

---

## 1. Engine Performance & Concurrency

### Latency Targets

| Metric | Target | Rationale |
|--------|--------|-----------|
| **p50 matching latency** | < 1 ms | Median request should be sub-millisecond on the matching engine alone |
| **p95 matching latency** | < 5 ms | 95th percentile stays well within budget |
| **p99 matching latency** | < 10 ms | Hard ceiling — no request may exceed 10 ms of engine overhead |
| **p99 under concurrent load (16 threads)** | < 10 ms | Same target must hold when 16 threads execute simultaneously |

- Latency is measured **inside the matching engine** (from receiving a parsed `VirtualRequest` to returning a `VirtualResponse`), excluding network I/O and JSON serialization.
- Measurements are taken **after** a warm-up of at least 10,000 requests to allow JIT compilation.

### Throughput Targets

| Metric | Target |
|--------|--------|
| Single-thread throughput | > 50,000 RPS |
| Multi-thread throughput (8 cores) | > 200,000 RPS |
| Full HTTP stack (k6 load test) | > 20,000 RPS |

- If hardware cannot sustain 20,000 RPS, the actual number and bottleneck analysis must be reported honestly.

### Concurrency Model

- **Path Trie (stub routing):** Copy-on-Write — readers access a volatile root reference with zero locking; writers deep-copy, modify, and swap atomically.
- **Session State Store:** Nested `ConcurrentHashMap` (3 levels: session → collection → entity) — physically separate map objects per session guarantee zero cross-session contention.
- **Matching Engine:** Stateless pure function — no shared mutable state, no locks required.
- **Drift Simulator:** `volatile` config + `ThreadLocalRandom` — immediate visibility with zero contention.

---

## 2. Session State Isolation

| Criterion | Pass Condition |
|-----------|---------------|
| **Data isolation** | Session A's state is NEVER visible to Session B, even when both use identical entity keys |
| **Concurrent isolation** | 16 parallel sessions, each performing 5,000 create + read + cross-check operations (240,000 total assertions), with **zero** violations |
| **Session teardown** | `destroySession()` removes ALL state in O(1); subsequent reads return null/404 |
| **Session TTL** | Idle sessions auto-expire after configurable TTL (default 30 minutes) |
| **ID generation isolation** | Per-session sequential counters; session A's IDs are independent of session B's |

---

## 3. Sanitization Accuracy

| Metric | Target | Rationale |
|--------|--------|-----------|
| **Recall (sensitivity)** | >= 0.95 (95%) | A missed credit card or SSN is a PCI-DSS compliance breach. Favor catching everything. |
| **Precision** | >= 0.90 (90%) | Acceptable to over-redact ~10% of non-sensitive fields; unacceptable to miss sensitive ones. |
| **Card number detection** | Luhn-validated, 13-19 digit numbers with valid issuer prefix | Prevents false positives from random digit strings |
| **SSN detection** | Pattern `\d{3}-\d{2}-\d{4}` + field name context for undashed variants | Undashed 9-digit strings only redacted when field name suggests SSN |

### Confidence Thresholds for Auto-Publishing

| Confidence Score | Action |
|-----------------|--------|
| >= 0.90 | **Auto-publish** — register stub immediately |
| 0.70 - 0.89 | **Pending review** — flag for human inspection |
| < 0.70 | **Rejected** — too many unclassified fields, risk of data leakage |

**Override:** payment, fraud, and compliance endpoints (`CriticalPathClassifier`, matched by
path keyword) never auto-publish, regardless of confidence score — they always land in
**Pending review**. A wrong stub on a payment-critical path masks real integration bugs that
cost money in production, so a human always signs off first (see `CUSTOMER_VALUE.md`).

### Detection Pipeline

Three-layer classification (field name check → value type check → value pattern matching) with defense-in-depth: even if layer 1 misses, layer 3 catches card numbers via Luhn validation.

---

## 4. Success Metric for Day 1

The engine is considered **shippable** when:

1. All 33 labeled golden scenarios in `stub_match_scenarios.json` pass with correct status codes and matched
   stub IDs. The stubs are mentioned in `seed.stubs.json` . The set is organized into 5 categories (see below) and sized 30-40 by design — enough to cover
   every routing/matching/state edge case without becoming an unreadable wall of near-duplicates.
2. Sequential benchmark: p99 < 10 ms over 50,000 iterations after 10,000-iteration warm-up.
3. Concurrent benchmark: p99 < 10 ms with 16 threads and 160,000 total measurements.
4. Session isolation: 16 sessions x 5,000 operations x 2 cross-checks = 160,000 assertions, zero violations.
5. `eval_report.md` contains actual measured p50/p95/p99 values and multi-thread throughput numbers.


### Golden scenario categories (`stub_match_scenarios.json`, stubs in `seed.stubs.json`)


| # | Category | Count | What it proves |
|---|----------|-------|-----------------|
| 1 | **Complex path matching** | 11 | Copy-on-Write trie: exact-vs-wildcard precedence, method-case folding, dead ends past the deepest registered node, unregistered version prefixes, hyphenated path-variable values, gRPC/HTTP routing isolation, and **nested two-level path params** where a composite state key is derived from both wildcards at once (and correctly misses if either one is wrong) |
| 2 | **Multi-field JSONPath matching** | 6 | Two-field AND conditions outranking single-field stubs, nested-object field extraction (`$.address.country`), and graceful null resolution when the parent object is missing entirely |
| 3 | **Session state mutation** | 8 | Full CRUD lifecycle on one entity (CREATE → READ → UPDATE → READ confirms in-place overwrite → DELETE → READ confirms removal) plus a cross-collection WRITE/READ pair proving `accounts` and `payments` never share a namespace |
| 4 | **Multi-tenant session isolation** | 7 | Two independent sessions each create their own account under the *same literal entity key* (`ACC-0001`) with zero cross-visibility; a third, never-provisioned session gets a hard 404 on that key; one tenant's DELETE never touches another tenant's copy |
| 5 | **Fallback stub match** | 1 | A request with no body conditions (empty `{}`) still resolves to the conditionless fallback stub (`stub_account_create_fallback_v1`) rather than failing to match — i.e. the fallback stub itself is a legitimate, matched route, not a 404. Pure no-match 404 cases (unknown paths, unregistered verbs/gRPC methods/Kafka topics) are covered separately by targeted unit tests (`PathTrieTest`, `PathParamTest`, `TransparentProxyTest`, `ControlPlaneApiTest`) rather than this golden-scenario file |


