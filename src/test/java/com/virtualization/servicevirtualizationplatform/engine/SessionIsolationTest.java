package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SessionIsolationTest {

    @Autowired
    private StubMatchingEngine engine;

    @Autowired
    private SessionStateStore stateStore;

    @Test
    void sessionA_cannotSee_sessionB_data() {
        VirtualResponse createRes = engine.matchAndExecute(new VirtualRequest(
                "iso-A", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Ivan", "currency", "USD", "initial_deposit", 500.0)
        ));
        String accId = (String) createRes.body().get("account_id");

        // Session B cannot see Session A's account
        VirtualResponse getRes = engine.matchAndExecute(new VirtualRequest(
                "iso-B", "HTTP", "GET", "/api/v1/accounts/" + accId,
                Map.of(), null
        ));
        assertThat(getRes.statusCode()).isEqualTo(404);
    }

    @Test
    void twoSessions_sameLogicalEntity_independentState() {
        VirtualResponse resX = engine.matchAndExecute(new VirtualRequest(
                "iso-X", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "User-X", "currency", "USD", "initial_deposit", 100.0)
        ));
        VirtualResponse resY = engine.matchAndExecute(new VirtualRequest(
                "iso-Y", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "User-Y", "currency", "USD", "initial_deposit", 999.0)
        ));

        String xId = (String) resX.body().get("account_id");
        VirtualResponse getX = engine.matchAndExecute(new VirtualRequest(
                "iso-X", "HTTP", "GET", "/api/v1/accounts/" + xId,
                Map.of(), null
        ));
        assertThat(getX.statusCode()).isEqualTo(200);
        assertThat(getX.body().get("account_holder")).isEqualTo("User-X");
    }

    @Test
    void sessionTeardown_clearsAllState() {
        VirtualResponse createRes = engine.matchAndExecute(new VirtualRequest(
                "teardown-sess", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Karl", "currency", "USD", "initial_deposit", 100.0)
        ));
        String accId = (String) createRes.body().get("account_id");

        stateStore.destroySession("teardown-sess");

        VirtualResponse getRes = engine.matchAndExecute(new VirtualRequest(
                "teardown-sess", "HTTP", "GET", "/api/v1/accounts/" + accId,
                Map.of(), null
        ));
        assertThat(getRes.statusCode()).isEqualTo(404);
    }

    @Test
    void concurrent_16Sessions_5000Ops_zeroViolations() throws Exception {
        int SESSIONS = 16;
        int OPS_PER_SESSION = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(SESSIONS);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            final int sNum = s;
            final String sid = "conc-" + s;
            futures.add(pool.submit(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                for (int i = 0; i < OPS_PER_SESSION; i++) {
                    // CREATE in my session
                    VirtualResponse cr = engine.matchAndExecute(new VirtualRequest(
                            sid, "HTTP", "POST", "/api/v1/accounts",
                            Map.of(), Map.of("account_holder", sid + "-user-" + i,
                            "currency", "USD", "initial_deposit", 100.0)
                    ));
                    String id = (String) cr.body().get("account_id");

                    // Own session: must find it
                    VirtualResponse myGet = engine.matchAndExecute(new VirtualRequest(
                            sid, "HTTP", "GET", "/api/v1/accounts/" + id,
                            Map.of(), null
                    ));
                    if (myGet.statusCode() != 200) {
                        violations.incrementAndGet();
                    }

                    // Cross-session check: if other session has same ID,
                    // the data must belong to THEM, not to us.
                    // (Per-session counters mean IDs can collide across sessions,
                    //  but the DATA stored must be isolated.)
                    if (myGet.statusCode() == 200) {
                        Object holder = myGet.body().get("account_holder");
                        if (holder != null && !holder.toString().startsWith(sid)) {
                            violations.incrementAndGet(); // GOT ANOTHER SESSION'S DATA!
                        }
                    }
                }
                return null;
            }));
        }

        gate.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 16 × 5000 × 2 = 160,000 isolation assertions
        assertThat(violations.get()).as("ZERO session isolation violations").isZero();
    }
}
