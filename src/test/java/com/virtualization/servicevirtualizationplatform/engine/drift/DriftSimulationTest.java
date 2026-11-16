package com.virtualization.servicevirtualizationplatform.engine.drift;

import com.virtualization.servicevirtualizationplatform.model.DriftConfig;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriftSimulationTest {

    private final DriftSimulator simulator = new DriftSimulator();

    @Test
    void latencyInjection_addsSleepTime() {
        simulator.configure(new DriftConfig(50, 10, 0.0));

        VirtualResponse original = new VirtualResponse(
                "test-stub", 200, Map.of(), Map.of(), false, 0.1, List.of());

        long start = System.nanoTime();
        simulator.applyDrift(original, "/test");
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // Should sleep ~50ms ± 10ms + some overhead
        assertThat(elapsed).isBetween(35L, 75L);
    }

    @Test
    void errorInjection_matchesConfiguredRate() {
        simulator.configure(new DriftConfig(0, 0, 0.10)); // 10% errors

        VirtualResponse original = new VirtualResponse(
                "test-stub", 200, Map.of(), Map.of(), false, 0.1, List.of());

        int errors = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            VirtualResponse result = simulator.applyDrift(original, "/test");
            if (result.statusCode() == 500) errors++;
        }

        double errorRate = (double) errors / trials;
        // 10% ± 3% tolerance
        assertThat(errorRate).isBetween(0.07, 0.13);
    }

    @Test
    void errorInjection_500ResponseBody_containsDriftMarker() {
        // 100% error rate → every request returns 500
        simulator.configure(new DriftConfig(0, 0, 1.0));

        VirtualResponse original = new VirtualResponse(
                "stub-ok", 200, Map.of("Content-Type", "application/json"),
                Map.of("payment_id", "PAY-001", "status", "SUCCESS"),
                false, 0.5, List.of("path_pattern", "jsonpath_body_check"));

        VirtualResponse result = simulator.applyDrift(original, "/api/v1/payments");

        // Status overridden to 500
        assertThat(result.statusCode()).isEqualTo(500);
        // Body replaced with error message
        assertThat(result.body().get("error")).isEqualTo("Internal Server Error (simulated drift)");
        // Original body (payment_id, status) is NOT in the 500 response
        assertThat(result.body()).doesNotContainKey("payment_id");
        assertThat(result.body()).doesNotContainKey("status");
        // Matched rules show drift was injected
        assertThat(result.matchedRules()).contains("drift_error_injected");
        // Original stub ID preserved so you can trace which stub was affected
        assertThat(result.matchedStubId()).isEqualTo("stub-ok");
    }

    @Test
    void routeSpecificDrift_500OnlyOnConfiguredPath() {
        // Global: no drift. Route /api/v1/payments: 100% errors
        simulator.configure(DriftConfig.NONE);
        simulator.configureRoute("/api/v1/payments", new DriftConfig(0, 0, 1.0));

        VirtualResponse original = new VirtualResponse(
                "test-stub", 200, Map.of(), Map.of("ok", true), false, 0.1, List.of());

        // Payment path → 500
        VirtualResponse payResult = simulator.applyDrift(original, "/api/v1/payments");
        assertThat(payResult.statusCode()).isEqualTo(500);

        // Account path → 200 (no drift configured)
        VirtualResponse accResult = simulator.applyDrift(original, "/api/v1/accounts");
        assertThat(accResult.statusCode()).isEqualTo(200);

        simulator.clearRouteConfigs();
    }

    @Test
    void latencyPlusError_bothApplied() {
        // 50ms latency + 100% error rate → should sleep AND return 500
        simulator.configure(new DriftConfig(50, 0, 1.0));

        VirtualResponse original = new VirtualResponse(
                "test-stub", 200, Map.of(), Map.of(), false, 0.1, List.of());

        // Error check takes priority (checked first in applyDrift)
        VirtualResponse result = simulator.applyDrift(original, "/test");
        assertThat(result.statusCode()).isEqualTo(500);
    }

    @Test
    void driftMetrics_tracksErrorsAndLatency() {
        DriftSimulator freshSimulator = new DriftSimulator();
        freshSimulator.configure(new DriftConfig(0, 0, 1.0)); // 100% errors

        VirtualResponse original = new VirtualResponse(
                "test-stub", 200, Map.of(), Map.of(), false, 0.1, List.of());

        for (int i = 0; i < 100; i++) {
            freshSimulator.applyDrift(original, "/test");
        }

        Map<String, Object> snapshot = freshSimulator.getMetrics().getSnapshot();
        assertThat(((Number) snapshot.get("totalRequests")).longValue()).isEqualTo(100);
        assertThat(((Number) snapshot.get("errorsInjected")).longValue()).isEqualTo(100);
        assertThat(((Number) snapshot.get("errorRate")).doubleValue()).isEqualTo(1.0);
    }

    @Test
    void zeroDrift_noLatencyAdded() {
        simulator.configure(DriftConfig.NONE);

        VirtualResponse original = new VirtualResponse(
                "test-stub", 200, Map.of(), Map.of(), false, 0.1, List.of());

        long start = System.nanoTime();
        VirtualResponse result = simulator.applyDrift(original, "/test");
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsed).isLessThan(5);
        assertThat(result.statusCode()).isEqualTo(200);
    }
}
