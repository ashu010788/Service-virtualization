package com.virtualization.servicevirtualizationplatform.engine.drift;

import com.virtualization.servicevirtualizationplatform.model.DriftConfig;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Injects latency, jitter, and random errors into virtual responses.
 * Simulates downstream service degradation for load testing.
 *
 * Concurrency: volatile config for immediate visibility, ThreadLocalRandom for zero contention.
 */
@Service
public class DriftSimulator {

    private volatile DriftConfig globalConfig = DriftConfig.NONE;
    private final ConcurrentHashMap<String, DriftConfig> routeConfigs = new ConcurrentHashMap<>();
    private final DriftMetrics metrics = new DriftMetrics();

    public void configure(DriftConfig config) {
        this.globalConfig = config;
    }

    public void configureRoute(String route, DriftConfig config) {
        routeConfigs.put(route, config);
    }

    public void clearRouteConfigs() {
        routeConfigs.clear();
    }

    public DriftConfig getGlobalConfig() {
        return globalConfig;
    }

    public Map<String, DriftConfig> getRouteConfigs() {
        return Map.copyOf(routeConfigs);
    }

    public DriftMetrics getMetrics() {
        return metrics;
    }

    /**
     * Apply drift to a response. Called AFTER stub matching, BEFORE returning.
     * May inject errors (return 500) or add latency (Thread.sleep).
     */
    public VirtualResponse applyDrift(VirtualResponse original, String path) {
        DriftConfig config = routeConfigs.getOrDefault(path, globalConfig);

        if (!config.isActive()) return original;

        // Error injection
        if (config.errorRate() > 0 &&
                ThreadLocalRandom.current().nextDouble() < config.errorRate()) {
            metrics.recordRequest(0, true);
            return new VirtualResponse(
                    original.matchedStubId(), 500,
                    Map.of("Content-Type", "application/json"),
                    Map.of("error", "Internal Server Error (simulated drift)"),
                    false, original.executionLatencyMs(),
                    List.of("drift_error_injected"));
        }

        // Latency injection
        long addedLatency = 0;
        if (config.baseLatencyMs() > 0) {
            long delay = config.baseLatencyMs();
            if (config.jitterMs() > 0) {
                delay += ThreadLocalRandom.current().nextLong(
                        -config.jitterMs(), config.jitterMs() + 1);
                delay = Math.max(0, delay);
            }
            addedLatency = delay;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        metrics.recordRequest(addedLatency, false);
        return original;
    }

    public boolean shouldInjectError() {
        return globalConfig.errorRate() > 0 &&
                ThreadLocalRandom.current().nextDouble() < globalConfig.errorRate();
    }
}
