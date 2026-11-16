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
    private final ConcurrentHashMap<String, DriftConfig> protocolConfigs = new ConcurrentHashMap<>();
    /**
     * Keyed by "method" — for HTTP this is a verb (GET/POST/...), but for GRPC/KAFKA the
     * VirtualRequest.method() field is where the RPC method name / Kafka topic name actually
     * lives (there's no REST-style path for those protocols). This lets drift be targeted at
     * a specific Kafka topic or gRPC method, which an exact-path route override can never do
     * since path is always "" / "/" for non-HTTP traffic.
     */
    private final ConcurrentHashMap<String, DriftConfig> methodConfigs = new ConcurrentHashMap<>();
    private final DriftMetrics metrics = new DriftMetrics();

    public void configure(DriftConfig config) {
        this.globalConfig = config;
    }

    public void configureRoute(String route, DriftConfig config) {
        routeConfigs.put(route, config);
    }

    public void configureProtocol(String protocol, DriftConfig config) {
        protocolConfigs.put(protocol.toUpperCase(), config);
    }

    public void configureMethod(String method, DriftConfig config) {
        methodConfigs.put(method, config);
    }

    public void clearRouteConfigs() {
        routeConfigs.clear();
    }

    public void clearProtocolConfigs() {
        protocolConfigs.clear();
    }

    public void clearMethodConfigs() {
        methodConfigs.clear();
    }

    public boolean removeRouteConfig(String route) {
        return routeConfigs.remove(route) != null;
    }

    public boolean removeProtocolConfig(String protocol) {
        return protocolConfigs.remove(protocol.toUpperCase()) != null;
    }

    public boolean removeMethodConfig(String method) {
        return methodConfigs.remove(method) != null;
    }

    public DriftConfig getGlobalConfig() {
        return globalConfig;
    }

    public Map<String, DriftConfig> getRouteConfigs() {
        return Map.copyOf(routeConfigs);
    }

    public Map<String, DriftConfig> getProtocolConfigs() {
        return Map.copyOf(protocolConfigs);
    }

    public Map<String, DriftConfig> getMethodConfigs() {
        return Map.copyOf(methodConfigs);
    }

    public DriftMetrics getMetrics() {
        return metrics;
    }

    /**
     * Apply drift to a response. Called AFTER stub matching, BEFORE returning.
     * May inject errors (return 500) or add latency (Thread.sleep).
     *
     * Precedence (most specific wins): exact route override &gt; protocol override &gt; global.
     */
    public VirtualResponse applyDrift(VirtualResponse original, String path) {
        return applyDrift(original, path, null, null);
    }

    public VirtualResponse applyDrift(VirtualResponse original, String path, String protocol) {
        return applyDrift(original, path, null, protocol);
    }

    /**
     * Precedence (most specific wins): exact route (path) &gt; exact method/topic &gt;
     * protocol &gt; global. Route only ever matches HTTP (path is ""/"/" for GRPC/KAFKA);
     * method matches the gRPC method name or Kafka topic carried in VirtualRequest.method().
     */
    public VirtualResponse applyDrift(VirtualResponse original, String path, String method, String protocol) {
        DriftConfig config = routeConfigs.get(path);
        if (config == null && method != null) {
            config = methodConfigs.get(method);
        }
        if (config == null && protocol != null) {
            config = protocolConfigs.get(protocol.toUpperCase());
        }
        if (config == null) {
            config = globalConfig;
        }

        if (!config.isActive()) return original;

        // Latency + jitter injection FIRST — a simulated downstream error should still pay
        // the realistic delay instead of failing back instantly. Order now matches the real
        // world: a slow dependency is slow whether it ultimately succeeds or errors out.
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

        // Error injection — happens after the delay above.
        if (config.errorRate() > 0 &&
                ThreadLocalRandom.current().nextDouble() < config.errorRate()) {
            metrics.recordRequest(addedLatency, true);
            return new VirtualResponse(
                    original.matchedStubId(), 500,
                    Map.of("Content-Type", "application/json"),
                    Map.of("error", "Internal Server Error (simulated drift)"),
                    false, original.executionLatencyMs(),
                    List.of("drift_error_injected"));
        }

        metrics.recordRequest(addedLatency, false);
        return original;
    }

    public boolean shouldInjectError() {
        return globalConfig.errorRate() > 0 &&
                ThreadLocalRandom.current().nextDouble() < globalConfig.errorRate();
    }

    /**
     * Diagnostic lookup — answers "if a request with this route/method/protocol came in right
     * now, would drift be applied, and from which tier?" without actually injecting anything.
     * Used by the UI/API to let someone verify an override actually took effect instead of
     * guessing from behavior.
     */
    public DriftResolution resolve(String route, String method, String protocol) {
        if (route != null && !route.isBlank() && routeConfigs.containsKey(route)) {
            return new DriftResolution("ROUTE", route, routeConfigs.get(route));
        }
        if (method != null && !method.isBlank() && methodConfigs.containsKey(method)) {
            return new DriftResolution("METHOD", method, methodConfigs.get(method));
        }
        if (protocol != null && !protocol.isBlank() && protocolConfigs.containsKey(protocol.toUpperCase())) {
            return new DriftResolution("PROTOCOL", protocol.toUpperCase(), protocolConfigs.get(protocol.toUpperCase()));
        }
        return new DriftResolution(globalConfig.isActive() ? "GLOBAL" : "NONE", null, globalConfig);
    }

    /**
     * @param tier   which override tier actually matched: ROUTE, METHOD, PROTOCOL, GLOBAL, or
     *               NONE (global config exists but is inactive — zero latency/jitter/error)
     * @param key    the matched route/method/protocol value, or null for GLOBAL/NONE
     * @param config the resolved DriftConfig that would be applied
     */
    public record DriftResolution(String tier, String key, DriftConfig config) {}
}
