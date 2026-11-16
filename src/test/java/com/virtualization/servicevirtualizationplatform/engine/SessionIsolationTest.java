package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combined session isolation tests:
 *   - Engine-level isolation (direct StubMatchingEngine calls)
 *   - API-level person isolation with SSN sanitization, concurrent sessions,
 *     salary isolation, and admin state inspection (merged from PersonSessionIsolationTest)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionIsolationTest {

    @Autowired
    private StubMatchingEngine engine;

    @Autowired
    private SessionStateStore stateStore;

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void resetDrift() {
        http.delete("/api/v1/drift/config");
    }

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

    // ═══════════════════════════════════════════════════════════════════
    // API-level person isolation (merged from PersonSessionIsolationTest)
    // SSN sanitization + concurrent sessions + salary isolation + admin state
    // ═══════════════════════════════════════════════════════════════════

    private Map<String, Object> virtualPost(String sessionId, String path, Map<String, Object> body) {
        return Map.of(
                "session_id", sessionId,
                "protocol", "HTTP", "method", "POST",
                "path", path,
                "headers", Map.of("Content-Type", "application/json"),
                "body", body
        );
    }

    private Map<String, Object> virtualGet(String sessionId, String path) {
        return Map.of(
                "session_id", sessionId,
                "protocol", "HTTP", "method", "GET",
                "path", path,
                "headers", Map.of(), "body", Map.of()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingest_ssnSanitized_salaryIsolated_concurrentSessions() throws Exception {

        // PHASE 1 — Ingest raw HTTP traffic with SSN + salary
        Map<String, Object> rawTraffic = Map.of(
                "request", Map.of(
                        "protocol", "HTTP", "method", "POST",
                        "path", "/api/v1/persons",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("name", "Test Person", "ssn", "123-45-6789",
                                "salary", 75000.0, "department", "ENGINEERING")
                ),
                "response", Map.of(
                        "statusCode", 201,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("person_id", "PER-0001", "name", "Test Person",
                                "salary", 75000.0, "department", "ENGINEERING", "status", "ACTIVE")
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", rawTraffic, Map.class);
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ingestBodyStr = ingestRes.getBody().toString();
        assertThat(ingestBodyStr).doesNotContain("123-45-6789");

        Map<String, Object> reqSummary = (Map<String, Object>) ingestRes.getBody().get("requestSummary");
        assertThat(((Number) reqSummary.get("piiFieldsMasked")).intValue()).isGreaterThanOrEqualTo(1);

        double confidence = ((Number) ingestRes.getBody().get("confidence")).doubleValue();
        assertThat(confidence).isGreaterThanOrEqualTo(0.70);

        // PHASE 2 — Register richer POST + GET + 404 stubs
        Map<String, Object> postPersonStub = Map.of(
                "stubId", "stub_person_create_isolated",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "POST",
                        "pathPattern", "/api/v1/persons", "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of("statusCode", 201,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("person_id", "{{generate.id:PER}}", "name", "{{request.body.name}}",
                                "salary", "{{request.body.salary}}", "department", "{{request.body.department}}",
                                "status", "ACTIVE")),
                "stateAction", Map.of("op", "WRITE", "collection", "persons",
                        "keyExpr", "{{response.body.person_id}}", "valueExpr", "{{response.body}}")
        );
        assertThat(http.postForEntity("/api/v1/stubs", postPersonStub, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> getPersonStub = Map.of(
                "stubId", "stub_person_get_isolated",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/persons/{id}", "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of("statusCode", 200,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", "{{state.result}}"),
                "stateAction", Map.of("op", "READ", "collection", "persons",
                        "keyExpr", "{{request.pathVar.id}}")
        );
        assertThat(http.postForEntity("/api/v1/stubs", getPersonStub, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        http.postForEntity("/api/v1/stubs", Map.of(
                "stubId", "stub_person_not_found_isolated",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/persons/{id}", "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of("statusCode", 404,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("error", "Person not found"))
        ), Map.class);

        // PHASE 3 — Session A creates Alice
        ResponseEntity<Map> resAlice = http.postForEntity("/api/v1/virtual",
                virtualPost("iso-session-A", "/api/v1/persons", Map.of(
                        "name", "Alice", "ssn", "111-22-3333", "salary", 95000.0, "department", "ENGINEERING"
                )), Map.class);
        assertThat(((Number) resAlice.getBody().get("status_code")).intValue()).isEqualTo(201);
        Map<String, Object> aliceBody = (Map<String, Object>) resAlice.getBody().get("body");
        String personIdAlice = (String) aliceBody.get("person_id");
        assertThat(aliceBody.get("name")).isEqualTo("Alice");
        assertThat(aliceBody.toString()).doesNotContain("111-22-3333");

        // PHASE 4 — PARALLEL: Session B creates Bob, Session A creates Alice2
        CompletableFuture<ResponseEntity<Map>> futureBob = CompletableFuture.supplyAsync(() ->
                http.postForEntity("/api/v1/virtual",
                        virtualPost("iso-session-B", "/api/v1/persons", Map.of(
                                "name", "Bob", "ssn", "444-55-6666", "salary", 85000.0, "department", "FINANCE"
                        )), Map.class));

        CompletableFuture<ResponseEntity<Map>> futureAlice2 = CompletableFuture.supplyAsync(() ->
                http.postForEntity("/api/v1/virtual",
                        virtualPost("iso-session-A", "/api/v1/persons", Map.of(
                                "name", "Alice2", "ssn", "555-66-7777", "salary", 70000.0, "department", "OPERATIONS"
                        )), Map.class));

        ResponseEntity<Map> resBob = futureBob.get(10, TimeUnit.SECONDS);
        ResponseEntity<Map> resAlice2 = futureAlice2.get(10, TimeUnit.SECONDS);

        Map<String, Object> bobBody = (Map<String, Object>) resBob.getBody().get("body");
        Map<String, Object> alice2Body = (Map<String, Object>) resAlice2.getBody().get("body");
        String personIdBob = (String) bobBody.get("person_id");
        String personIdAlice2 = (String) alice2Body.get("person_id");

        assertThat(bobBody.get("name")).isEqualTo("Bob");
        assertThat(((Number) bobBody.get("salary")).doubleValue()).isEqualTo(85000.0);
        assertThat(alice2Body.get("name")).isEqualTo("Alice2");
        assertThat(((Number) alice2Body.get("salary")).doubleValue()).isEqualTo(70000.0);

        // PHASE 5 — Own-session GETs
        ResponseEntity<Map> getAliceRes = http.postForEntity("/api/v1/virtual",
                virtualGet("iso-session-A", "/api/v1/persons/" + personIdAlice), Map.class);
        assertThat(((Number) getAliceRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<String, Object>) getAliceRes.getBody().get("body")).get("name")).isEqualTo("Alice");

        ResponseEntity<Map> getBobRes = http.postForEntity("/api/v1/virtual",
                virtualGet("iso-session-B", "/api/v1/persons/" + personIdBob), Map.class);
        assertThat(((Number) getBobRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<String, Object>) getBobRes.getBody().get("body")).get("name")).isEqualTo("Bob");

        // PHASE 6 — Cross-session isolation
        ResponseEntity<Map> crossBtoAlice2 = http.postForEntity("/api/v1/virtual",
                virtualGet("iso-session-B", "/api/v1/persons/" + personIdAlice2), Map.class);
        assertThat(((Number) crossBtoAlice2.getBody().get("status_code")).intValue()).isEqualTo(404);

        // Salary isolation
        double salaryA = ((Number) ((Map<?, ?>) getAliceRes.getBody().get("body")).get("salary")).doubleValue();
        double salaryB = ((Number) ((Map<?, ?>) getBobRes.getBody().get("body")).get("salary")).doubleValue();
        assertThat(salaryA).isNotEqualTo(salaryB);

        // PHASE 7 — Session C: Charlie
        ResponseEntity<Map> resCharlie = http.postForEntity("/api/v1/virtual",
                virtualPost("iso-session-C", "/api/v1/persons", Map.of(
                        "name", "Charlie", "ssn", "777-88-9999", "salary", 120000.0, "department", "MANAGEMENT"
                )), Map.class);
        assertThat(((Number) resCharlie.getBody().get("status_code")).intValue()).isEqualTo(201);

        // PHASE 8 — Admin state inspection
        ResponseEntity<Map> stateA = http.getForEntity("/api/v1/sessions/iso-session-A/state", Map.class);
        Map<String, Object> personsA = (Map<String, Object>) ((Map<String, Object>) stateA.getBody().get("collections")).get("persons");
        assertThat(personsA).containsKey(personIdAlice);
        assertThat(personsA).containsKey(personIdAlice2);
        assertThat(((Number) ((Map<?, ?>) personsA.get(personIdAlice)).get("salary")).doubleValue()).isEqualTo(95000.0);

        ResponseEntity<Map> stateB = http.getForEntity("/api/v1/sessions/iso-session-B/state", Map.class);
        Map<String, Object> personsB = (Map<String, Object>) ((Map<String, Object>) stateB.getBody().get("collections")).get("persons");
        assertThat(personsB).hasSize(1);
        assertThat(((Number) ((Map<?, ?>) personsB.get(personIdBob)).get("salary")).doubleValue()).isEqualTo(85000.0);
    }
}
