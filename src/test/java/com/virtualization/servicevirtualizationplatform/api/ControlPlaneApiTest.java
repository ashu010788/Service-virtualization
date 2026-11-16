package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlPlaneApiTest {

    @Autowired
    private TestRestTemplate http;

    // ═══ STUBS API ═══

    @Test
    void postStub_registersAndReturns201() {
        Map<String, Object> stub = Map.of(
                "stubId", "api-test-stub-1",
                "priority", 10,
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/test-endpoint",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of(
                        "statusCode", 200,
                        "body", Map.of("result", "ok"))
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/stubs", stub, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("stubId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStubs_returnsRegistered() {
        ResponseEntity<List> res = http.getForEntity("/api/v1/stubs", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty(); // seed stubs loaded at startup
    }

    // ═══ EDIT RESPONSE TEMPLATE (PATCH) ═══

    @Test
    @SuppressWarnings("unchecked")
    void patchResponseTemplate_updatesAndPersists() {
        // Create a stub with a known response
        Map<String, Object> stub = Map.of(
                "stubId", "edit-response-test-stub",
                "priority", 5,
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/edit-test",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of(
                        "statusCode", 200,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("message", "original"))
        );
        http.postForEntity("/api/v1/stubs", stub, Map.class);

        // PATCH the response template with new status code and body
        Map<String, Object> newResponse = Map.of(
                "statusCode", 202,
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("message", "updated", "extra", "field")
        );
        ResponseEntity<Map> patchRes = http.exchange(
                "/api/v1/stubs/edit-response-test-stub/response",
                HttpMethod.PATCH, new HttpEntity<>(newResponse), Map.class);

        assertThat(patchRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchRes.getBody()).containsEntry("stubId", "edit-response-test-stub");
        assertThat(patchRes.getBody()).containsEntry("message", "Response template updated");

        // Verify the stub was actually updated by fetching it back
        ResponseEntity<Map> getRes = http.getForEntity(
                "/api/v1/stubs/edit-response-test-stub", Map.class);
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> rt = (Map<String, Object>) getRes.getBody().get("responseTemplate");
        assertThat(((Number) rt.get("statusCode")).intValue()).isEqualTo(202);

        Map<String, Object> body = (Map<String, Object>) rt.get("body");
        assertThat(body).containsEntry("message", "updated");
        assertThat(body).containsEntry("extra", "field");
    }

    @Test
    void patchResponseTemplate_returns404ForUnknownStub() {
        Map<String, Object> newResponse = Map.of(
                "statusCode", 200,
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("message", "nope")
        );
        ResponseEntity<Map> res = http.exchange(
                "/api/v1/stubs/nonexistent-stub-xyz/response",
                HttpMethod.PATCH, new HttpEntity<>(newResponse), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void toggleStub_changesState() {
        // Toggle a seed stub
        ResponseEntity<Map> res = http.postForEntity(
                "/api/v1/stubs/stub_account_create_v1/toggle", null, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("enabled");

        // Toggle back
        http.postForEntity("/api/v1/stubs/stub_account_create_v1/toggle", null, Map.class);
    }

    // ═══ SESSIONS API ═══

    @Test
    void createSession_returns201() {
        ResponseEntity<Map> res = http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "api-test-session", "ttl_minutes", 60), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("session_id")).isEqualTo("api-test-session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSessionState_returnsState() {
        // Create session and add data via virtual endpoint
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "inspect-session"), Map.class);

        http.postForEntity("/api/v1/virtual",
                Map.of("sessionId", "inspect-session", "protocol", "HTTP",
                        "method", "POST", "path", "/api/v1/accounts",
                        "headers", Map.of(),
                        "body", Map.of("account_holder", "Test", "currency", "USD",
                                "initial_deposit", 100.0)),
                Map.class);

        ResponseEntity<Map> state = http.getForEntity(
                "/api/v1/sessions/inspect-session/state", Map.class);

        assertThat(state.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state.getBody()).containsKey("collections");
    }

    @Test
    void deleteSession_removes() {
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "to-delete-session"), Map.class);

        http.delete("/api/v1/sessions/to-delete-session");

        ResponseEntity<Map> state = http.getForEntity(
                "/api/v1/sessions/to-delete-session/state", Map.class);
        Map<String, Object> collections = (Map<String, Object>) state.getBody().get("collections");
        // Should be empty after deletion
        assertThat(collections == null || collections.isEmpty()).isTrue();
    }

    // ═══ RECORDING / INGEST API ═══

    @Test
    @SuppressWarnings("unchecked")
    void ingestTrafficLog_sanitizesAndReturnsStub() {
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "method", "POST",
                        "path", "/api/v1/payments",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("credit_card", "4111111111111111", "cvv", "123", "amount", 100.0)
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of(),
                        "body", Map.of("payment_id", "PAY-99281", "status", "APPROVED", "amount", 100.0)
                )
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", log, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("generatedStub");
        assertThat(res.getBody()).containsKey("confidence");

        // Verify card number not in generated stub body (sanitized)
        Map<String, Object> generatedStub = (Map<String, Object>) res.getBody().get("generatedStub");
        assertThat(generatedStub.toString()).doesNotContain("4111111111111111");
    }

    // ═══ VIRTUAL ENDPOINT ═══

    @Test
    @SuppressWarnings("unchecked")
    void virtualEndpoint_matchesStubAndReturns() {
        Map<String, Object> req = Map.of(
                "sessionId", "http-api-test",
                "protocol", "HTTP",
                "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "API-Test", "currency", "USD", "initial_deposit", 250.0)
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body.get("matchedStubId")).isEqualTo("stub_account_create_v1");
        assertThat(((Number) body.get("statusCode")).intValue()).isEqualTo(201);
    }

    // ═══ DRIFT API ═══

    @Test
    void driftConfig_setAndGet() {
        Map<String, Object> config = Map.of(
                "baseLatencyMs", 100,
                "jitterMs", 20,
                "errorRate", 0.05
        );

        ResponseEntity<Map> setRes = http.postForEntity("/api/v1/drift/config", config, Map.class);
        assertThat(setRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> getRes = http.getForEntity("/api/v1/drift/config", Map.class);
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRes.getBody()).containsKey("global");

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void driftLatency_addsDelayToVirtualRequests() {
        // Set 50ms base latency
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 50, "jitterMs", 0, "errorRate", 0.0), Map.class);

        long start = System.nanoTime();
        http.postForEntity("/api/v1/virtual",
                Map.of("sessionId", "drift-latency-test", "protocol", "HTTP",
                        "method", "GET", "path", "/api/v1/accounts",
                        "headers", Map.of()),
                Map.class);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // Should take at least ~50ms due to drift
        assertThat(elapsed).isGreaterThan(30);

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void driftErrorRate_injects500s() {
        // Set 100% error rate
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 0, "jitterMs", 0, "errorRate", 1.0), Map.class);

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual",
                Map.of("sessionId", "drift-error-test", "protocol", "HTTP",
                        "method", "GET", "path", "/api/v1/accounts",
                        "headers", Map.of()),
                Map.class);

        // 100% error rate → must return 500
        assertThat(((Number) res.getBody().get("statusCode")).intValue()).isEqualTo(500);

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void driftMetrics_reflectsActivity() {
        // Set drift with 100% errors
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 0, "jitterMs", 0, "errorRate", 1.0), Map.class);

        // Send 5 requests
        for (int i = 0; i < 5; i++) {
            http.postForEntity("/api/v1/virtual",
                    Map.of("sessionId", "drift-metrics-test", "protocol", "HTTP",
                            "method", "GET", "path", "/api/v1/accounts",
                            "headers", Map.of()),
                    Map.class);
        }

        ResponseEntity<Map> metrics = http.getForEntity("/api/v1/drift/metrics", Map.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) metrics.getBody().get("totalRequests")).longValue()).isGreaterThanOrEqualTo(5);
        assertThat(((Number) metrics.getBody().get("errorsInjected")).longValue()).isGreaterThanOrEqualTo(5);

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void driftReset_clearsConfig() {
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 100, "jitterMs", 50, "errorRate", 0.5), Map.class);

        http.delete("/api/v1/drift/config");

        ResponseEntity<Map> config = http.getForEntity("/api/v1/drift/config", Map.class);
        Map<String, Object> global = (Map<String, Object>) config.getBody().get("global");
        assertThat(((Number) global.get("baseLatencyMs")).longValue()).isEqualTo(0);
        assertThat(((Number) global.get("jitterMs")).longValue()).isEqualTo(0);
        assertThat(((Number) global.get("errorRate")).doubleValue()).isEqualTo(0.0);
    }

    // ═══ TRAFFIC API ═══

    @Test
    @SuppressWarnings("unchecked")
    void trafficRecent_returnsList() {
        // Generate some traffic first
        http.postForEntity("/api/v1/virtual",
                Map.of("sessionId", "traffic-test", "protocol", "HTTP",
                        "method", "GET", "path", "/api/v1/accounts/ACC-NONE",
                        "headers", Map.of()),
                Map.class);

        ResponseEntity<List> res = http.getForEntity("/api/v1/traffic/recent?limit=10", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty();
    }
}
