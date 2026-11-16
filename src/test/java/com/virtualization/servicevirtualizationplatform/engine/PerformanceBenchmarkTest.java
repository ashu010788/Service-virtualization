package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("benchmark")
class PerformanceBenchmarkTest {

    @Autowired
    private StubMatchingEngine engine;

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
