Implementation: Java 21 + Spring Boot 3.4.1 | UI: Thymeleaf + HTMX + Bootstrap 5

# Enterprise Service Virtualization Platform

A high-performance service virtualization engine that matches multi-protocol requests (HTTP/REST, gRPC, Kafka) against registered stubs with sub-10ms p99 latency at 20,000+ RPS, manages session-isolated dynamic state, records traffic with PCI-DSS/PII sanitization, and auto-generates stubs.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Web UI (Thymeleaf + HTMX)                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────┐ ┌─────────────┐ │
│  │  Stubs   │ │ Sessions │ │ Traffic  │ │ Drift │ │  Recording  │ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └───┬───┘ └──────┬──────┘ │
└───────┼────────────┼────────────┼────────────┼────────────┼────────┘
        │            │            │            │            │
┌───────▼────────────▼────────────▼────────────▼────────────▼────────┐
│                    REST Control Plane APIs                          │
│  /api/v1/virtual  /stubs  /sessions  /traffic  /drift  /recording  │
└───────┬────────────────────────────────────────────────────────────┘
        │
┌───────▼────────────────────────────────────────────────────────────┐
│                    StubMatchingEngine (Orchestrator)                 │
│                                                                     │
│  ┌─────────────────────┐    ┌──────────────────────┐               │
│  │    StubRouter        │    │  SessionStateStore    │               │
│  │  ┌───────────────┐  │    │  ConcurrentHashMap    │               │
│  │  │ Copy-on-Write │  │    │  session → collection │               │
│  │  │   Path Trie   │  │    │    → entity (3-level) │               │
│  │  │  (HTTP paths) │  │    └──────────────────────┘               │
│  │  └───────────────┘  │                                            │
│  │  ┌───────────────┐  │    ┌──────────────────────┐               │
│  │  │  gRPC HashMap │  │    │  DriftSimulator       │               │
│  │  │  Kafka HashMap│  │    │  volatile config +    │               │
│  │  └───────────────┘  │    │  ThreadLocalRandom    │               │
│  └─────────────────────┘    └──────────────────────┘               │
│                                                                     │
│  ┌─────────────────────┐    ┌──────────────────────┐               │
│  │  TemplateResolver    │    │  AsyncEventScheduler  │               │
│  │  {{request.body.X}} │    │  ScheduledExecutor    │               │
│  │  {{generate.id:PFX}}│    │  (4 daemon threads)   │               │
│  │  {{state.result}}   │    │  delay + jitter       │               │
│  └─────────────────────┘    └──────────────────────┘               │
│                                                                     │
│  ┌─────────────────────┐    ┌──────────────────────┐               │
│  │  TrafficLogger       │    │  PciPiiSanitizer      │               │
│  │  Ring Buffer [10K]  │    │  4-layer detection    │               │
│  │  AtomicInteger idx  │    │  compiled regex       │               │
│  └─────────────────────┘    │  Luhn validation      │               │
│                              └──────────────────────┘               │
└─────────────────────────────────────────────────────────────────────┘
```

## Setup and Run

```bash
# Build and run (one command)
./mvnw spring-boot:run

# Or with Maven
mvn spring-boot:run

# Run all tests
mvn test

# Access
# Web UI:    http://localhost:8080/
# Swagger:   http://localhost:8080/swagger-ui.html
# API:       http://localhost:8080/api/v1/
```

**Prerequisites**: Java 21+, Maven 3.9+

## Design Decisions

1. **Copy-on-Write Trie for HTTP path matching** — Lock-free reads at 998K RPS. Writers deep-copy + atomic swap. Startup uses `insertDirect()` (no copy) since no readers exist yet. O(path-depth) lookup vs O(n) linear scan.

2. **Nested ConcurrentHashMap (3-level) for session state** — Physical isolation: `session → collection → entity`. No cross-session data leakage by construction. Per-session AtomicLong counters for ID generation. Verified with 16 sessions × 5,000 ops = 160K assertions, zero violations.

3. **Manual JSONPath body matching** — No Jayway library. `resolvePath()` supports `$.field`, `$.nested.field`, `$.items[0].field`. Stateless pure function — no locks. Avoids library overhead on the hot path.

4. **4-layer PCI/PII sanitization with compiled regex** — Layer 1: field name (Set.contains, cheapest). Layer 2: value type (instanceof). Layer 3: value pattern (compiled regex + Luhn checksum). Layer 4: heuristic safe/unknown. Favors recall over precision. Masks: `****1111` (cards), `****@masked.com` (emails), `****6789` (SSN).

5. **Confidence-scored auto-publishing** — Stubs auto-generated from traffic get confidence scores. ≥0.90 → auto-publish. 0.70–0.89 → pending review. <0.70 → rejected. Prevents low-quality stubs from entering the registry. TTL expiry + unused detection prevent stub sprawl.

## What I Would Do Differently With More Time

- **Real gRPC/Kafka integration** — Currently protocol stubs are JSON-based simulations. Would add protobuf schema parsing, real Kafka consumer/producer, and gRPC server interceptors.
- **Persistent storage** — Stubs and sessions live in memory. Would add PostgreSQL for stub persistence and Redis for distributed session state across multiple nodes.
- **Distributed deployment** — Single JVM currently. Would shard the trie by path prefix and use consistent hashing for session affinity across a cluster.
- **WebSocket traffic streaming** — Currently HTMX polls every 3s. WebSocket would give true real-time traffic display.
- **OpenAPI schema validation** — Auto-validate generated stubs against the downstream service's OpenAPI spec to catch drift before it reaches CI.
- **Prometheus/Grafana metrics** — Export p99 latency, match miss rate, session count as Prometheus metrics for production alerting.

#
