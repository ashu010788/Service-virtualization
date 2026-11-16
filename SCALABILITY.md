# Scalability & Architecture

## 20,000 RPS Engine Performance

- **All in-memory, zero-lock reads on the hot path.** Sub-10ms matching at 20K RPS is achieved by holding all data structures entirely in-memory with no disk I/O on the read path. In production, a Redis-backed store with AOF persistence would provide durability and rebuild capability on restart.
- **Copy-on-Write Trie** for HTTP path routing: readers grab a `volatile` root reference (single CPU instruction) and traverse the immutable snapshot. Writers deep-copy, mutate the copy, and atomically swap the root. Readers in-flight see the old snapshot — consistent and non-blocking.
- **ConcurrentHashMap** for gRPC (method name) and Kafka (topic name) lookups — O(1) with no contention.
- **No synchronized blocks** in `StubMatchingEngine` — all concurrency is delegated to thread-safe components.
- **Manual JSONPath evaluation** — pure `Map.get()` + `List.get()` chain. No compilation, no regex at runtime. Jayway's `JsonPath.compile()` uses a regex-based tokenizer and allocates a Path object tree — too slow for the hot path.
- **PCI/PII sanitization** — 4-layer fail-fast cascade where the cheapest check (field-name `Set` lookup, ~10ns) runs first; only ~50% of fields reach the most expensive layer (compiled regex + Luhn checksum, ~200ns), yielding an amortized cost of ~40-80ns per field.
- At startup, seed stubs use `insertDirect()` (no deep copy) since no readers exist yet. Copy-on-Write only activates for runtime stub registration.

**Measured**: p99 = 0.005ms (16 concurrent threads), throughput = 1,014,576 RPS on a single JVM. 50x headroom over the 20K target.

## Protocol Shift & Synchronization

**A. HTTP to Kafka async events** are handled by `AsyncEventScheduler` — a `ScheduledExecutorService` with 4 daemon threads. When a POST stub defines `asyncEvents`, the engine returns the HTTP response immediately, then schedules the Kafka event via `scheduler.schedule(runnable, delayMs +/- jitterMs, MILLISECONDS)`. The event fires on a background thread, writes to session state (`events:{topic}` collection), and optionally updates related entities. Fire-and-forget from the caller's perspective — HTTP response is never delayed.

**B. Real embedded Kafka (stretch goal)**: messages are published to an input topic on the embedded KRaft broker, `KafkaVirtualConsumer` consumes them, routes through `StubMatchingEngine`, and publishes the stubbed response to an output topic (e.g. `payments` -> `payments-completed`). This can be further enhanced for protocol shift — an HTTP stub could publish to an input topic after responding, letting the Kafka consumer trigger a downstream chain within the same session.

## Stub Sprawl & Governance

**A. Three automated mechanisms prevent stub accumulation:**

1. **TTL-based expiry**: Auto-generated stubs get an `expiresAt` timestamp. `StubGovernanceScheduler` runs hourly and removes expired stubs.
2. **Unused stub detection**: Stubs track `matchCount` (AtomicLong) and `lastMatchedAt` (volatile Instant). Auto-generated stubs with zero matches after 7 days are removed; manual stubs inactive 30+ days are cleaned up.
3. **Source tagging**: Stubs tagged `"auto-generated"` vs `"manual"` — auto-generated stubs face stricter governance (lower TTL, auto-cleanup). Manual stubs require explicit deletion.

**B. Audit and monitoring**: Stub counts, match rates, and request-to-stub mapping are tracked for operational audit. Governance logic can be tuned (TTL thresholds, cleanup intervals) without code changes.

## Production Monitoring

| Metric | Alert Threshold | Why |
|--------|----------------|-----|
| p99 matching latency | > 10ms | SLA breach imminent |
| Stub match miss rate | > 20% | Stubs stale or missing coverage |
| Session count | > 500 concurrent | Memory pressure — TTL may be too long |
| Auto-generated stub rejection rate | > 50% | Traffic patterns changed; sanitization rules need tuning |
| Governance cleanup count/hour | > 100 | Stub sprawl accelerating — investigate upstream API churn |
| Drift error injection mismatch | Configured 10% but observed > 15% or < 5% | Config race or seeding issue |
