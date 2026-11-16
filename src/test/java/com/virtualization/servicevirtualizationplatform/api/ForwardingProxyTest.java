package com.virtualization.servicevirtualizationplatform.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ForwardingProxyController — clients call actual URLs
 * (e.g. POST /api/v1/accounts) instead of /api/v1/virtual.
 *
 * record-on-miss is pinned to false here so these tests are self-contained
 * and always expect 404 on a stub miss — regardless of what application.properties
 * has. This means application.properties can be set to true for manual upstream
 * testing without breaking this test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "proxy.upstream.record-on-miss=false",
        "proxy.upstream.base-url=http://localhost:9999"
})
class ForwardingProxyTest {

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void resetDrift() {
        // Drift config is shared across tests (single Spring context).
        // Reset before each test to prevent bleed from ControlPlaneApiTest drift tests.
        http.delete("/api/v1/drift/config");
    }

    // ═══ HTTP stub matching via actual URLs ═══

    @Test
    @SuppressWarnings("unchecked")
    void post_actualAccountUrl_matchesStubAndReturns201() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "proxy-test-1");

        Map<String, Object> body = Map.of(
                "account_holder", "Alice",
                "initial_deposit", 500.0,
                "currency", "USD"
        );

        ResponseEntity<Map> res = http.exchange(
                "/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("account_id");
        assertThat(res.getBody().get("account_holder")).isEqualTo("Alice");
        assertThat(res.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void post_thenGet_statefulFlowViaActualUrls() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "proxy-stateful-test");

        // Step 1: Create account via actual URL
        Map<String, Object> createBody = Map.of(
                "account_holder", "Bob",
                "initial_deposit", 1000.0,
                "currency", "USD"
        );

        ResponseEntity<Map> createRes = http.exchange(
                "/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(createBody, headers), Map.class);

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) createRes.getBody().get("account_id");
        assertThat(accountId).startsWith("ACC-");

        // Step 2: GET the created account via actual URL
        ResponseEntity<Map> getRes = http.exchange(
                "/api/v1/accounts/" + accountId,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRes.getBody().get("account_holder")).isEqualTo("Bob");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionIsolation_viaActualUrls() {
        // Create account in session A
        HttpHeaders headersA = new HttpHeaders();
        headersA.setContentType(MediaType.APPLICATION_JSON);
        headersA.set("X-Session-Id", "proxy-session-A");

        ResponseEntity<Map> createRes = http.exchange(
                "/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(Map.of(
                        "account_holder", "SessionA-User", "currency", "USD", "initial_deposit", 100.0
                ), headersA), Map.class);

        String accountId = (String) createRes.getBody().get("account_id");

        // GET from session A → 200
        ResponseEntity<Map> resA = http.exchange(
                "/api/v1/accounts/" + accountId,
                HttpMethod.GET, new HttpEntity<>(headersA), Map.class);
        assertThat(resA.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET from session B → 404 (isolated)
        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-Session-Id", "proxy-session-B");

        ResponseEntity<Map> resB = http.exchange(
                "/api/v1/accounts/" + accountId,
                HttpMethod.GET, new HttpEntity<>(headersB), Map.class);
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingSessionId_returns400() {
        // X-Session-Id is now mandatory — omitting it must return 400
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No X-Session-Id header

        ResponseEntity<Map> res = http.exchange(
                "/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(Map.of(
                        "account_holder", "NoSession", "currency", "USD", "initial_deposit", 50.0
                ), headers), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("error").toString()).contains("X-Session-Id");
    }

    @Test
    void noMatchingStub_returns404() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "proxy-404-test");

        ResponseEntity<Map> res = http.exchange(
                "/some/unknown/path",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ═══ Admin endpoints NOT intercepted ═══

    @Test
    void adminStubsEndpoint_notIntercepted() {
        ResponseEntity<Object> res = http.getForEntity("/api/v1/stubs", Object.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminSessionsEndpoint_notIntercepted() {
        ResponseEntity<Object> res = http.getForEntity("/api/v1/sessions", Object.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminDriftEndpoint_notIntercepted() {
        ResponseEntity<Object> res = http.getForEntity("/api/v1/drift/config", Object.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminTrafficEndpoint_notIntercepted() {
        ResponseEntity<Object> res = http.getForEntity("/api/v1/traffic/recent", Object.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ═══ Exact assignment worked example ═══
    // "Session A calls POST /api/v1/accounts, the stub matches, state is written under session A, 201 returned."
    // "Session A calls GET /api/v1/accounts/ACC-9921, a read stub looks up accounts in session A, 200 returned."
    // "Session B calls the same GET and receives 404, because it has no such state."
    // NOTE: No explicit POST /api/v1/sessions needed — session auto-creates on first state write.

    @Test
    @SuppressWarnings("unchecked")
    void assignmentWorkedExample_dynamicSessionCreation_fullFlow() {
        // ── Session A: POST /api/v1/accounts ──
        HttpHeaders headersA = new HttpHeaders();
        headersA.setContentType(MediaType.APPLICATION_JSON);
        headersA.set("X-Session-Id", "session-A");

        ResponseEntity<Map> createRes = http.exchange(
                "/api/v1/accounts", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "account_holder", "Jane Doe",
                        "initial_deposit", 500.00,
                        "currency", "USD"
                ), headersA), Map.class);

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) createRes.getBody().get("account_id");
        assertThat(accountId).startsWith("ACC-");
        assertThat(createRes.getBody().get("account_holder")).isEqualTo("Jane Doe");
        assertThat(((Number) createRes.getBody().get("balance")).doubleValue()).isEqualTo(500.00);
        assertThat(createRes.getBody().get("status")).isEqualTo("ACTIVE");

        // ── Session A: GET /api/v1/accounts/{id} → 200 ──
        ResponseEntity<Map> getA = http.exchange(
                "/api/v1/accounts/" + accountId, HttpMethod.GET,
                new HttpEntity<>(headersA), Map.class);

        assertThat(getA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getA.getBody().get("account_holder")).isEqualTo("Jane Doe");
        assertThat(getA.getBody().get("account_id")).isEqualTo(accountId);

        // ── Session B: same GET → 404 (no state) ──
        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-Session-Id", "session-B");

        ResponseEntity<Map> getB = http.exchange(
                "/api/v1/accounts/" + accountId, HttpMethod.GET,
                new HttpEntity<>(headersB), Map.class);

        assertThat(getB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ── Verify session state via admin API (session auto-created) ──
        ResponseEntity<Map> stateA = http.getForEntity(
                "/api/v1/sessions/session-A/state", Map.class);
        assertThat(stateA.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> collections = (Map<String, Object>) stateA.getBody().get("collections");
        assertThat(collections).containsKey("accounts");
    }

    // ═══ Payment with async Kafka event ═══

    @Test
    @SuppressWarnings("unchecked")
    void post_payment_viaActualUrl_triggersAsyncEvent() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "proxy-payment-test");

        // Create session first
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "proxy-payment-test"), Map.class);

        // Create payment via actual URL
        ResponseEntity<Map> res = http.exchange(
                "/api/v1/payments",
                HttpMethod.POST, new HttpEntity<>(Map.of(
                        "amount", 250.0, "currency", "USD"
                ), headers), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("payment_id");

        // Wait for async event (500ms delay + jitter)
        Thread.sleep(800);

        // Check session state for async Kafka event
        ResponseEntity<Map> state = http.getForEntity(
                "/api/v1/sessions/proxy-payment-test/state", Map.class);

        Map<String, Object> collections = (Map<String, Object>) state.getBody().get("collections");
        assertThat(collections).containsKey("events:payment-events");
    }
}
