# Scalability & Architecture

## 20,000 RPS Engine Performance

Sub-10ms matching at 20K RPS is achieved through **zero-lock reads on the hot path**:

- **Copy-on-Write Trie** for HTTP path routing: readers grab a volatile root reference (single CPU instruction) and traverse the immutable snapshot. Writers deep-copy the trie, mutate the copy, and atomically swap the root. Readers in-flight during a write see the old snapshot — consistent and non-blocking.
- **ConcurrentHashMap** for gRPC (method name) and Kafka (topic name) lookups — O(1) with no contention.
- **No synchronized blocks** in `StubMatchingEngine` — all concurrency is delegated to thread-safe components.
- **Manual JSONPath evaluation** — no Jayway/Jackson JSONPath library overhead. Simple field traversal via `Map.get()` chains.
- **Measured**: p99 = 0.009ms (16 concurrent threads), throughput = 998K RPS on a single JVM. 50x headroom over the 20K target.

At startup, seed stubs use `insertDirect()` (no deep copy) since no readers exist yet. Copy-on-Write only activates for runtime stub registration.

## Protocol Shift & Synchronization

HTTP → Kafka async events are handled by `AsyncEventScheduler` — a `ScheduledExecutorService` with 4 daemon threads. When a POST stub defines `asyncEvents`, the engine:

1. Returns the HTTP response immediately (synchronous).
2. Schedules the Kafka event via `scheduler.schedule(runnable, delayMs ± jitterMs, MILLISECONDS)`.
3. The event fires on a background thread, writes to session state (`events:{topic}` collection), and optionally updates related entities.

This is **fire-and-forget from the caller's perspective** — the HTTP response is not delayed by the async event. Session state isolation ensures the event writes only affect the originating session. `ThreadLocalRandom` for jitter means zero contention between threads.

## Stub Sprawl & Governance

Three mechanisms prevent stub accumulation:

1. **TTL-based expiry**: Every auto-generated stub gets an `expiresAt` timestamp. `StubGovernanceScheduler` runs hourly (`@Scheduled(fixedRate=3600000)`) and removes expired stubs from the registry.
2. **Unused stub detection**: Stubs track `matchCount` (AtomicLong) and `lastMatchedAt` (volatile Instant). Stubs with zero matches after 7 days are flagged for cleanup.
3. **Source tagging**: Stubs are tagged `"source": "auto-generated"` vs `"manual"`. Auto-generated stubs are subject to stricter governance (lower TTL, auto-cleanup). Manual stubs require explicit deletion.
4. **Conflict detection**: `StubRegistry.checkConflicts()` warns when a new stub overlaps an existing one on the same path + method + body conditions.

## Production Monitoring

| Metric | Alert Threshold | Why |
|--------|----------------|-----|
| p99 matching latency | > 10ms | SLA breach imminent (target: <10ms) |
| Stub match miss rate | > 20% of requests | Stubs are stale or missing coverage |
| Session count | > 500 concurrent | Memory pressure — TTL may be too long |
| Auto-generated stub rejection rate | > 50% | Traffic patterns may have changed; sanitization rules need tuning |
| Governance cleanup count/hour | > 100 | Stub sprawl accelerating — investigate upstream API churn |
| Drift error injection mismatch | Configured 10% but measured >15% or <5% | ThreadLocalRandom seeding issue or config race |
| Ring buffer wraparound rate | > 10K entries/min | Traffic volume exceeding buffer capacity; consider scaling buffer size |
