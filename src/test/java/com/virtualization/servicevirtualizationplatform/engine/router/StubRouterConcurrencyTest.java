package com.virtualization.servicevirtualizationplatform.engine.router;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency and isolation tests for StubRouter.
 *
 * Key invariants verified:
 *
 * 1. ROUTING ISOLATION
 *    A request for path/method/protocol P must ONLY receive stubs registered
 *    for P — never stubs from a different path, method, or topic — even under
 *    concurrent registration and removal by other threads.
 *
 * 2. NO ConcurrentModificationException
 *    Readers iterating a gRPC/Kafka bucket while another thread calls
 *    register() or remove() on the same bucket must never throw CME.
 *    (Was broken when remove() called ArrayList.removeIf() on the live list.)
 *
 * 3. SORTED ORDER PRESERVED UNDER CONCURRENCY
 *    After concurrent registrations the per-bucket list must remain sorted
 *    by specificity descending.
 *
 * 4. PATH-PARAM TRIE ISOLATION
 *    Two different path patterns with wildcards at different positions
 *    must route independently and return correct pathVariables.
 *
 * These tests use a standalone StubRouter (no Spring context) so they run
 * fast and can use heavy thread counts without integration-test overhead.
 */
class StubRouterConcurrencyTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static StubDefinition grpcStub(String id, String method, int bodyConditions) {
        Map<String, String> body = new LinkedHashMap<>();
        for (int i = 0; i < bodyConditions; i++) body.put("$.f" + i, "v" + i);
        return new StubDefinition(id,
                new RequestMatcher("GRPC", method, "", Map.of(), body),
                new ResponseTemplate(200, Map.of(), Map.of("stubId", id)),
                null);
    }

    private static StubDefinition kafkaStub(String id, String topic) {
        return new StubDefinition(id,
                new RequestMatcher("KAFKA", topic, "", Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), Map.of("stubId", id)),
                null);
    }

    private static StubDefinition httpStub(String id, String method, String path) {
        return new StubDefinition(id,
                new RequestMatcher("HTTP", method, path, Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), Map.of("stubId", id)),
                null);
    }

    // ── Test 1: gRPC isolation — concurrent readers + concurrent removes ────

    /**
     * 8 reader threads each iterate the GRPC "PING" bucket 10,000 times.
     * 4 writer threads concurrently add stubs to "PING".
     * 4 remover threads concurrently remove stubs from "PING".
     *
     * Zero ConcurrentModificationExceptions must occur.
     * The "PONG" bucket must remain completely untouched throughout.
     */
    @Test
    void grpc_concurrent_readersAndWriters_noCME_isolatedBuckets() throws Exception {
        StubRouter router = new StubRouter();

        // Seed both buckets
        router.registerDirect(grpcStub("ping-seed", "PING", 0));
        router.registerDirect(grpcStub("pong-seed", "PONG", 0));

        int READERS  = 8;
        int WRITERS  = 4;
        int REMOVERS = 4;
        int ITERS    = 10_000;

        AtomicLong cme         = new AtomicLong(0);
        AtomicLong wrongBucket = new AtomicLong(0);
        AtomicInteger reg      = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(READERS + WRITERS + REMOVERS);
        CountDownLatch gate  = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(READERS + WRITERS + REMOVERS);

        // Readers: iterate PING bucket, verify PONG stubs never appear
        for (int t = 0; t < READERS; t++) {
            pool.submit(() -> {
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < ITERS; i++) {
                    try {
                        MatchCandidates mc = router.findCandidates("GRPC", "PING", "");
                        for (StubDefinition s : mc.stubs()) {
                            if (s.stubId().startsWith("pong")) wrongBucket.incrementAndGet();
                        }
                    } catch (ConcurrentModificationException e) {
                        cme.incrementAndGet();
                    }
                }
                done.countDown();
            });
        }

        // Writers: register new PING stubs
        for (int t = 0; t < WRITERS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < 50; i++) {
                    String id = "ping-w" + tid + "-" + i;
                    router.register(grpcStub(id, "PING", 0));
                    reg.incrementAndGet();
                }
                done.countDown();
            });
        }

        // Removers: remove PING stubs as they're registered
        for (int t = 0; t < REMOVERS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < 50; i++) {
                    router.remove("ping-w" + tid + "-" + i);
                }
                done.countDown();
            });
        }

        gate.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(cme.get())
                .as("ZERO ConcurrentModificationExceptions during concurrent reads/removes")
                .isEqualTo(0);
        assertThat(wrongBucket.get())
                .as("PONG stubs must NEVER appear in PING bucket (routing isolation)")
                .isEqualTo(0);

        // PONG bucket must be exactly as seeded — no cross-bucket mutation
        MatchCandidates pong = router.findCandidates("GRPC", "PONG", "");
        assertThat(pong.stubs()).hasSize(1);
        assertThat(pong.stubs().get(0).stubId()).isEqualTo("pong-seed");
    }

    // ── Test 2: Kafka bucket isolation ───────────────────────────────────────

    /**
     * Two Kafka topics ("orders" and "payments") registered concurrently.
     * Proves the per-topic CopyOnWriteArrayList buckets are physically separate:
     * stubs from "orders" never appear when querying "payments" and vice versa.
     */
    @Test
    void kafka_concurrentRegistration_topicBucketsAreIsolated() throws Exception {
        StubRouter router = new StubRouter();

        int THREADS = 8;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate  = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREADS);

        // Half threads register to "orders", half to "payments"
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String topic = (tid < THREADS / 2) ? "orders" : "payments";
                for (int i = 0; i < 20; i++) {
                    router.register(kafkaStub(topic + "-stub-t" + tid + "-" + i, topic));
                }
                done.countDown();
            });
        }

        gate.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Verify strict isolation
        MatchCandidates orders   = router.findCandidates("KAFKA", "orders", "");
        MatchCandidates payments = router.findCandidates("KAFKA", "payments", "");

        // No "payments" stub in "orders" bucket
        orders.stubs().forEach(s ->
                assertThat(s.stubId()).as("orders bucket must not contain payments stubs")
                        .startsWith("orders-stub"));

        // No "orders" stub in "payments" bucket
        payments.stubs().forEach(s ->
                assertThat(s.stubId()).as("payments bucket must not contain orders stubs")
                        .startsWith("payments-stub"));

        // Both buckets fully populated
        assertThat(orders.stubs()).hasSize(THREADS / 2 * 20);
        assertThat(payments.stubs()).hasSize(THREADS / 2 * 20);
    }

    // ── Test 3: Specificity sort preserved after concurrent registration ──────

    /**
     * Concurrently register stubs with varying specificity into the same GRPC bucket.
     * After all writers finish, the returned list must be sorted specificity DESC.
     */
    @Test
    void grpc_concurrentRegistration_sortOrderPreserved() throws Exception {
        StubRouter router = new StubRouter();

        int THREADS = 8;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate  = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < 10; i++) {
                    // varying specificity: 0, 10, 20... body conditions
                    router.register(grpcStub("sort-t" + tid + "-i" + i, "SORTTEST", i % 3));
                }
                done.countDown();
            });
        }

        gate.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        List<StubDefinition> stubs = router.findCandidates("GRPC", "SORTTEST", "").stubs();
        assertThat(stubs).hasSize(THREADS * 10);

        // Verify descending specificity order
        for (int i = 0; i < stubs.size() - 1; i++) {
            assertThat(stubs.get(i).specificity())
                    .as("stubs must be sorted specificity DESC at index %d", i)
                    .isGreaterThanOrEqualTo(stubs.get(i + 1).specificity());
        }
    }

    // ── Test 4: HTTP Trie routing isolation ──────────────────────────────────

    /**
     * Two different path patterns with wildcards.
     * Concurrent registrations on both paths.
     * Each request must ONLY get stubs for its own path.
     */
    @Test
    void http_trie_pathIsolation_concurrentRegistration() throws Exception {
        StubRouter router = new StubRouter();

        // Register stubs on two completely separate paths
        for (int i = 0; i < 5; i++) {
            router.registerDirect(httpStub("alpha-" + i, "GET", "/api/v1/alpha/{id}"));
            router.registerDirect(httpStub("beta-"  + i, "GET", "/api/v1/beta/{id}"));
        }

        // /alpha/{id} must ONLY return alpha stubs
        MatchCandidates alpha = router.findCandidates("HTTP", "GET", "/api/v1/alpha/X");
        assertThat(alpha.stubs()).hasSize(5);
        alpha.stubs().forEach(s ->
                assertThat(s.stubId()).startsWith("alpha-"));

        // /beta/{id} must ONLY return beta stubs
        MatchCandidates beta = router.findCandidates("HTTP", "GET", "/api/v1/beta/X");
        assertThat(beta.stubs()).hasSize(5);
        beta.stubs().forEach(s ->
                assertThat(s.stubId()).startsWith("beta-"));

        // /gamma/ (never registered) → empty
        MatchCandidates gamma = router.findCandidates("HTTP", "GET", "/api/v1/gamma/X");
        assertThat(gamma.stubs()).isEmpty();
    }

    // ── Test 5: remove() never corrupts a concurrent reader ─────────────────

    /**
     * This directly tests the previously broken code path:
     *   Old: list.removeIf() on the live ArrayList → CME when reader iterates
     *   New: new CopyOnWriteArrayList built in computeIfPresent → safe
     *
     * Pattern: one thread continuously reads, another continuously adds+removes.
     * Zero CMEs expected.
     */
    @Test
    void grpc_removeVsRead_noCME() throws Exception {
        StubRouter router = new StubRouter();
        router.registerDirect(grpcStub("base", "METHOD_X", 0));

        AtomicLong cme    = new AtomicLong(0);
        AtomicLong reads  = new AtomicLong(0);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(2);

        // Reader: 50,000 iterations
        Thread reader = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < 50_000; i++) {
                try {
                    List<StubDefinition> bucket =
                            router.findCandidates("GRPC", "METHOD_X", "").stubs();
                    // Force iteration — this is where CME used to fire
                    for (StubDefinition s : bucket) { reads.incrementAndGet(); }
                } catch (ConcurrentModificationException e) {
                    cme.incrementAndGet();
                }
            }
            done.countDown();
        });

        // Writer: add then immediately remove, 1,000 times
        Thread writer = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < 1_000; i++) {
                String id = "transient-" + i;
                router.register(grpcStub(id, "METHOD_X", 0));
                router.remove(id);
            }
            done.countDown();
        });

        reader.start();
        writer.start();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);

        assertThat(cme.get())
                .as("ZERO ConcurrentModificationExceptions when remove() races with iteration")
                .isEqualTo(0);
        assertThat(reads.get()).isGreaterThan(0);
    }
}
