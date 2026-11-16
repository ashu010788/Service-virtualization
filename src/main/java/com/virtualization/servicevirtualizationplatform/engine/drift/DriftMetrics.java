package com.virtualization.servicevirtualizationplatform.engine.drift;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks actual drift impact for the UI dashboard.
 * Thread-safe via AtomicLong — no locks.
 */
public class DriftMetrics {

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong errorsInjected = new AtomicLong(0);
    private final AtomicLong totalLatencyAddedMs = new AtomicLong(0);

    public void recordRequest(long latencyAddedMs, boolean errorInjected) {
        totalRequests.incrementAndGet();
        totalLatencyAddedMs.addAndGet(latencyAddedMs);
        if (errorInjected) errorsInjected.incrementAndGet();
    }

    public Map<String, Object> getSnapshot() {
        long reqs = totalRequests.get();
        long errors = errorsInjected.get();
        long latency = totalLatencyAddedMs.get();

        return Map.of(
                "totalRequests", reqs,
                "errorsInjected", errors,
                "avgLatencyAddedMs", reqs > 0 ? latency / reqs : 0,
                "errorRate", reqs > 0 ? (double) errors / reqs : 0.0
        );
    }

    public void reset() {
        totalRequests.set(0);
        errorsInjected.set(0);
        totalLatencyAddedMs.set(0);
    }
}
