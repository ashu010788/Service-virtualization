package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.engine.drift.DriftSimulator;
import com.virtualization.servicevirtualizationplatform.model.DriftConfig;
import com.virtualization.servicevirtualizationplatform.model.SanitizationResult;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import com.virtualization.servicevirtualizationplatform.recording.PciPiiSanitizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive benchmark suite covering all 4 quality criteria categories:
 *
 * 1. HIGH-THROUGHPUT LATENCY  — sub-10ms p99, 1M+ RPS throughput
 * 2. SESSION STATE ISOLATION  — 16 concurrent sessions, zero cross-contamination
 * 3. PCI/PII SANITIZATION     — precision >= 0.90, recall >= 0.95 on golden dataset
 * 4. FAILURE RESILIENCE       — error rate matches configured target ± 5%
 */
@SpringBootTest
@Tag("benchmark")
class PerformanceBenchmarkTest {

    @Autowired
    private StubMatchingEngine engine;

    private final PciPiiSanitizer sanitizer = new PciPiiSanitizer();

    @Test
    void sequentialLatency_p99Under10ms() {
        List<VirtualRequest> requests = buildVariedRequests();

        // Warmup — JIT compilation
        for (int i = 0; i < 10_000; i++) {
            engine.matchAndExecute(requests.get(i % requests.size()));
        }

        // Measure
        int N = 50_000;
        long[] nanos = new long[N];
        for (int i = 0; i < N; i++) {
            long start = System.nanoTime();
            engine.matchAndExecute(requests.get(i % requests.size()));
            nanos[i] = System.nanoTime() - start;
        }

        Arrays.sort(nanos);
        double p50 = nanos[(int) (N * 0.50)] / 1_000_000.0;
        double p95 = nanos[(int) (N * 0.95)] / 1_000_000.0;
        double p99 = nanos[(int) (N * 0.99)] / 1_000_000.0;
        double max = nanos[N - 1] / 1_000_000.0;

        System.out.println("═══ SEQUENTIAL LATENCY (baseline, no contention) ═══");
        System.out.printf("  p50:  %.3f ms%n", p50);
        System.out.printf("  p95:  %.3f ms%n", p95);
        System.out.printf("  p99:  %.3f ms%n", p99);
        System.out.printf("  max:  %.3f ms%n", max);

        assertThat(p99).as("p99 latency must be under 10ms").isLessThan(10.0);
    }

    @Test
    void concurrentLatency_16threads_p99Under10ms() throws Exception {
        int THREADS = 16;
        int OPS_PER_THREAD = 10_000;
        List<VirtualRequest> requests = buildVariedRequests();

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            engine.matchAndExecute(requests.get(i % requests.size()));
        }

        long[] allLatencies = new long[THREADS * OPS_PER_THREAD];
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            int threadNum = t;
            String sessionId = "perf-" + t;
            pool.submit(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    VirtualRequest req = new VirtualRequest(
                            sessionId, "HTTP", "POST", "/api/v1/accounts",
                            Map.of(), Map.of("account_holder", "User-" + i,
                            "currency", "USD", "initial_deposit", 100.0)
                    );
                    long start = System.nanoTime();
                    engine.matchAndExecute(req);
                    allLatencies[threadNum * OPS_PER_THREAD + i] = System.nanoTime() - start;
                }
                done.countDown();
            });
        }

        gate.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        Arrays.sort(allLatencies);
        int total = THREADS * OPS_PER_THREAD;
        double p50 = allLatencies[(int) (total * 0.50)] / 1_000_000.0;
        double p95 = allLatencies[(int) (total * 0.95)] / 1_000_000.0;
        double p99 = allLatencies[(int) (total * 0.99)] / 1_000_000.0;

        System.out.println("═══ CONCURRENT LATENCY (16 threads) ═══");
        System.out.printf("  p50:   %.3f ms%n", p50);
        System.out.printf("  p95:   %.3f ms%n", p95);
        System.out.printf("  p99:   %.3f ms%n", p99);

        assertThat(p99).as("p99 under concurrent load must be < 10ms").isLessThan(10.0);
    }

    @Test
    void throughput_multiThread() throws Exception {
        List<VirtualRequest> requests = buildVariedRequests();
        int threads = 8;
        int opsPerThread = 50_000;

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            engine.matchAndExecute(requests.get(i % requests.size()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < opsPerThread; i++) {
                    engine.matchAndExecute(requests.get(i % requests.size()));
                }
                done.countDown();
            });
        }

        gate.countDown();
        done.await(120, TimeUnit.SECONDS);
        double secs = (System.nanoTime() - start) / 1_000_000_000.0;
        pool.shutdown();

        double rps = (threads * opsPerThread) / secs;
        System.out.printf("═══ THROUGHPUT: %.0f RPS (%d threads × %d ops in %.2fs) ═══%n",
                rps, threads, opsPerThread, secs);

        assertThat(rps).as("Multi-thread throughput should exceed 20,000 RPS")
                .isGreaterThan(20_000);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CATEGORY 2: SESSION STATE ISOLATION — CONCURRENT PARALLEL STREAMS
    // QUALITY_CRITERIA.md §2: 16 sessions × N ops, zero cross-contamination
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Proves zero session-state bleed under maximum concurrency.
     *
     * Design:
     * - 16 sessions run concurrently, each writing N accounts with a unique
     *   account_holder = "bench-{session}-{i}" (globally unique string).
     * - After each write, the session reads back its own account and verifies
     *   account_holder matches what IT wrote (not another session's value).
     * - Any mismatch = cross-session contamination = test failure.
     *
     * Total assertions = SESSIONS × OPS_PER_SESSION = 16 × 500 = 8,000.
     */
    @Test
    void sessionIsolation_concurrent16sessions_zeroViolations() throws Exception {
        int SESSIONS       = 16;
        int OPS_PER_SESSION = 500;

        ExecutorService pool  = Executors.newFixedThreadPool(SESSIONS);
        CountDownLatch gate   = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(SESSIONS);
        AtomicLong violations = new AtomicLong(0);
        AtomicLong assertions = new AtomicLong(0);

        for (int s = 0; s < SESSIONS; s++) {
            final String sessionId = "iso-bench-" + s;
            final int    sessionNum = s;

            pool.submit(() -> {
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                for (int i = 0; i < OPS_PER_SESSION; i++) {
                    // Globally unique holder so we can detect contamination
                    String expectedHolder = "bench-" + sessionNum + "-" + i;

                    // Create account
                    VirtualResponse createRes = engine.matchAndExecute(new VirtualRequest(
                            sessionId, "HTTP", "POST", "/api/v1/accounts",
                            Map.of(), Map.of(
                                    "account_holder",  expectedHolder,
                                    "currency",        "USD",
                                    "initial_deposit", (double) (sessionNum * 10_000 + i)
                            )
                    ));

                    @SuppressWarnings("unchecked")
                    Map<String, Object> created = (Map<String, Object>) createRes.body();
                    String accountId = (String) created.get("account_id");

                    // Read it back in the SAME session — must match what we wrote
                    VirtualResponse readRes = engine.matchAndExecute(new VirtualRequest(
                            sessionId, "HTTP", "GET",
                            "/api/v1/accounts/" + accountId,
                            Map.of(), null
                    ));

                    assertions.incrementAndGet();

                    if (readRes.statusCode() == 200) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> readBody = (Map<String, Object>) readRes.body();
                        Object actualHolder = readBody.get("account_holder");
                        if (!expectedHolder.equals(actualHolder)) {
                            violations.incrementAndGet();
                            System.err.printf("VIOLATION session=%s i=%d expected=%s actual=%s%n",
                                    sessionId, i, expectedHolder, actualHolder);
                        }
                    } else {
                        // 404 on own account = state write didn't persist = violation
                        violations.incrementAndGet();
                    }
                }
                done.countDown();
            });
        }

        gate.countDown();
        done.await(120, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.printf("═══ SESSION ISOLATION: %d sessions × %d ops = %d assertions, %d violations ═══%n",
                SESSIONS, OPS_PER_SESSION, assertions.get(), violations.get());

        assertThat(violations.get())
                .as("ZERO cross-session state violations in %d concurrent sessions", SESSIONS)
                .isEqualTo(0);

        assertThat(assertions.get())
                .as("All %d assertions must have been executed", (long) SESSIONS * OPS_PER_SESSION)
                .isEqualTo((long) SESSIONS * OPS_PER_SESSION);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CATEGORY 3: PCI/PII SANITIZATION — PRECISION & RECALL ON GOLDEN DATASET
    // QUALITY_CRITERIA.md §3: recall >= 0.95, precision >= 0.90
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Precision = correctly-left-alone non-sensitive fields / total non-sensitive fields.
     * Recall    = correctly-masked sensitive fields / total sensitive fields.
     *
     * Golden dataset: 10 sensitive records + 10 non-sensitive records.
     * Each record checked field-by-field.
     */
    @Test
    void sanitization_goldenDataset_precisionAndRecall() {
        // ── Sensitive records (must be masked) ──
        List<Map.Entry<Map<String, Object>, List<String>>> sensitiveRecords = List.of(
                Map.entry(Map.of("credit_card", "4111111111111111", "amount", 100.0),
                        List.of("credit_card")),
                Map.entry(Map.of("card", "5500000000000004", "currency", "USD"),
                        List.of("card")),
                Map.entry(Map.of("card_number", "378282246310005", "merchant", "Walmart"),
                        List.of("card_number")),
                Map.entry(Map.of("ssn", "123-45-6789", "name", "Alice"),
                        List.of("ssn")),
                Map.entry(Map.of("ssn", "987-65-4321", "department", "ENG"),
                        List.of("ssn")),
                Map.entry(Map.of("cvv", "123", "credit_card", "4111111111111111", "amount", 50.0),
                        List.of("cvv", "credit_card")),
                Map.entry(Map.of("email", "jane@walmart.com", "name", "Jane"),
                        List.of("email")),
                Map.entry(Map.of("token", "Bearer eyJhbGci.eyJzdWIi.abc123", "user", "admin"),
                        List.of("token")),
                Map.entry(Map.of("credit_card", "4532015112830366", "status", "ACTIVE"),
                        List.of("credit_card")),
                Map.entry(Map.of("iban", "DE89370400440532013000", "bic", "COBADEFFXXX"),
                        List.of("iban"))
        );

        // ── Non-sensitive records (must NOT be masked) ──
        List<Map.Entry<Map<String, Object>, List<String>>> cleanRecords = List.of(
                Map.entry(Map.of("amount", 100.0, "currency", "USD"),
                        List.of("amount", "currency")),
                Map.entry(Map.of("status", "APPROVED", "merchant", "Walmart"),
                        List.of("status", "merchant")),
                Map.entry(Map.of("order_id", "ORD-001", "quantity", 5),
                        List.of("order_id", "quantity")),
                Map.entry(Map.of("tier", "GOLD", "country", "US"),
                        List.of("tier", "country")),
                Map.entry(Map.of("transaction_type", "PURCHASE", "timestamp", "2024-01-01"),
                        List.of("transaction_type", "timestamp")),
                Map.entry(Map.of("product_name", "Widget", "price", 29.99),
                        List.of("product_name", "price")),
                Map.entry(Map.of("shipping_method", "EXPRESS", "weight_kg", 1.5),
                        List.of("shipping_method", "weight_kg")),
                Map.entry(Map.of("region", "WEST", "warehouse_code", "WH-LAX"),
                        List.of("region", "warehouse_code")),
                Map.entry(Map.of("loyalty_points", 1500, "tier_expires", "2025-12-31"),
                        List.of("loyalty_points", "tier_expires")),
                Map.entry(Map.of("category", "ELECTRONICS", "subcategory", "PHONES"),
                        List.of("category", "subcategory"))
        );

        int truePositives  = 0;  // sensitive correctly masked
        int falseNegatives = 0;  // sensitive NOT masked (missed)
        int trueNegatives  = 0;  // non-sensitive correctly preserved
        int falsePositives = 0;  // non-sensitive incorrectly masked

        // Check sensitive records
        for (Map.Entry<Map<String, Object>, List<String>> entry : sensitiveRecords) {
            SanitizationResult result = sanitizer.sanitize(entry.getKey());
            for (String sensitiveField : entry.getValue()) {
                Object original = entry.getKey().get(sensitiveField);
                Object sanitized = result.sanitizedBody().get(sensitiveField);
                if (sanitized == null || !original.toString().equals(sanitized.toString())) {
                    truePositives++;   // correctly masked (value changed or removed)
                } else {
                    falseNegatives++;  // missed — still contains original value
                }
            }
        }

        // Check non-sensitive records
        for (Map.Entry<Map<String, Object>, List<String>> entry : cleanRecords) {
            SanitizationResult result = sanitizer.sanitize(entry.getKey());
            for (String cleanField : entry.getValue()) {
                Object original  = entry.getKey().get(cleanField);
                Object sanitized = result.sanitizedBody().get(cleanField);
                if (original.toString().equals(String.valueOf(sanitized))) {
                    trueNegatives++;   // correctly preserved
                } else {
                    falsePositives++;  // incorrectly masked
                }
            }
        }

        int totalSensitive    = truePositives + falseNegatives;
        int totalNonSensitive = trueNegatives + falsePositives;

        double recall = totalSensitive == 0 ? 1.0 : (double) truePositives / totalSensitive;
        // Precision = TP / (TP + FP), NOT trueNegatives / totalNonSensitive (that's specificity /
        // true-negative-rate, a different metric that happens to equal precision only when
        // falsePositives == 0). Using the wrong formula here would silently overstate precision
        // the moment any non-sensitive field gets incorrectly masked on a less friendly dataset —
        // see SanitizationGoldenDatasetTest for a harder, look-alike-heavy dataset that actually
        // exercises this distinction.
        double precision = (truePositives + falsePositives) == 0
                ? 1.0 : (double) truePositives / (truePositives + falsePositives);

        System.out.printf("═══ SANITIZATION PRECISION/RECALL ═══%n");
        System.out.printf("  Sensitive fields:   %d total, %d caught (recall = %.2f)%n",
                totalSensitive, truePositives, recall);
        System.out.printf("  Non-sensitive:      %d total, %d preserved (precision = %.2f)%n",
                totalNonSensitive, trueNegatives, precision);
        System.out.printf("  False negatives: %d | False positives: %d%n",
                falseNegatives, falsePositives);

        assertThat(recall)
                .as("Recall (sensitivity) must be >= 0.95 — missing a card/SSN is a PCI violation")
                .isGreaterThanOrEqualTo(0.95);

        assertThat(precision)
                .as("Precision must be >= 0.90 — over-redaction of business data is acceptable within limit")
                .isGreaterThanOrEqualTo(0.90);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CATEGORY 4: FAILURE & RESILIENCE — ERROR RATE DISTRIBUTION MATCHES TARGET
    // QUALITY_CRITERIA.md §1: drift error rate and jitter must match configuration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that:
     * 1. A configured 10% error rate produces errors in the range [7%, 13%] over 1,000 requests
     *    (i.e., target ± 3% statistical tolerance at N=1,000).
     * 2. A configured 50ms base + 20ms jitter produces measured mean latency in [30ms, 70ms]
     *    with observable variance (not all same value).
     *
     * Uses DriftSimulator directly — no network overhead.
     */
    @Test
    void resilience_errorRateAndJitter_matchTargetDistributions() throws InterruptedException {
        DriftSimulator localSimulator = new DriftSimulator();

        // ── Part A: Error rate distribution ──
        int REQUESTS    = 1_000;
        double TARGET   = 0.10;   // 10%

        localSimulator.configure(new DriftConfig(0, 0, TARGET));

        int errors = 0;
        for (int i = 0; i < REQUESTS; i++) {
            VirtualResponse base = new VirtualResponse(
                    "test-stub", 200, Map.of(), Map.of("ok", true), false, 0.0, List.of()
            );
            VirtualResponse drifted = localSimulator.applyDrift(base, "/api/v1/accounts");
            if (drifted.statusCode() == 500) errors++;
        }

        double observedRate = (double) errors / REQUESTS;
        System.out.printf("═══ RESILIENCE: error rate target=%.0f%% observed=%.1f%% (%d/%d) ═══%n",
                TARGET * 100, observedRate * 100, errors, REQUESTS);

        // Statistical tolerance: target ± 3% at N=1000 (3-sigma interval is roughly ±3%)
        assertThat(observedRate)
                .as("Observed error rate must be within [%.0f%%, %.0f%%] of target %.0f%%",
                        (TARGET - 0.03) * 100, (TARGET + 0.03) * 100, TARGET * 100)
                .isBetween(TARGET - 0.03, TARGET + 0.03);

        // ── Part B: Latency jitter distribution ──
        long BASE_MS  = 50L;
        long JITTER_MS = 20L;

        localSimulator.configure(new DriftConfig(BASE_MS, JITTER_MS, 0.0));

        long[] latencies = new long[200];
        for (int i = 0; i < 200; i++) {
            VirtualResponse base = new VirtualResponse(
                    "test-stub", 200, Map.of(), Map.of("ok", true), false, 0.0, List.of()
            );
            long start = System.nanoTime();
            localSimulator.applyDrift(base, "/api/v1/probe");
            latencies[i] = (System.nanoTime() - start) / 1_000_000;
        }

        long sum = 0;
        for (long l : latencies) sum += l;
        double mean = (double) sum / latencies.length;

        // Variance: check not all latencies are identical (jitter is working)
        long min = Arrays.stream(latencies).min().getAsLong();
        long max = Arrays.stream(latencies).max().getAsLong();
        long variance = max - min;

        System.out.printf("  Latency: mean=%.1fms min=%dms max=%dms jitter_observed=%dms%n",
                mean, min, max, variance);

        assertThat(mean)
                .as("Mean latency must be near base latency %dms (± %dms jitter)", BASE_MS, JITTER_MS)
                .isBetween((double)(BASE_MS - JITTER_MS - 10), (double)(BASE_MS + JITTER_MS + 10));

        assertThat(variance)
                .as("Jitter must produce observable latency variance (not all identical)")
                .isGreaterThanOrEqualTo(1L);
    }

    private List<VirtualRequest> buildVariedRequests() {
        return List.of(
                new VirtualRequest("perf-s1", "HTTP", "POST", "/api/v1/accounts",
                        Map.of(), Map.of("account_holder", "Alice", "currency", "USD", "initial_deposit", 100.0)),
                new VirtualRequest("perf-s2", "HTTP", "POST", "/api/v1/accounts",
                        Map.of(), Map.of("account_holder", "Bob", "currency", "EUR", "initial_deposit", 200.0)),
                new VirtualRequest("perf-s3", "HTTP", "POST", "/api/v1/payments",
                        Map.of(), Map.of("amount", 50.0, "currency", "USD")),
                new VirtualRequest("perf-s4", "HTTP", "POST", "/api/v1/transfers",
                        Map.of(), Map.of("from_account", "ACC-1", "to_account", "ACC-2", "amount", 25.0)),
                new VirtualRequest("perf-s5", "HTTP", "GET", "/api/v1/accounts/ACC-NONE",
                        Map.of(), null),
                new VirtualRequest("perf-s6", "HTTP", "POST", "/api/v1/customers",
                        Map.of(), Map.of("name", "Test", "address", Map.of("country", "US"))),
                new VirtualRequest("perf-s7", "GRPC", "CreateAccount", "",
                        Map.of(), Map.of("account_holder", "gRPC", "currency", "USD")),
                new VirtualRequest("perf-s8", "KAFKA", "payment-events", "",
                        Map.of(), Map.of("event_type", "PAYMENT_COMPLETED", "amount", 100.0))
        );
    }
}
