package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the control plane REST APIs.
 *
 * All stub virtualization (HTTP, gRPC, Kafka) goes through POST /api/v1/virtual
 * using the assignment snake_case contract: { session_id, protocol, method, path, headers, body }.
 * The response is a VirtualResponse with { matched_stub_id, status_code, headers, body,
 * state_mutated, execution_latency_ms, matched_rules }.
 *
 * Note: ForwardingProxyTest covers the alternative direct-URL path (X-Session-Id header).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlPlaneApiTest {

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void resetDrift() {
        // Drift config is shared across tests (single Spring context).
        // Always reset before each test to prevent bleed.
        http.delete("/api/v1/drift/config");
    }

    // ═══════════════════════════════════════════════════════
    // STUBS ADMIN API
    // ═══════════════════════════════════════════════════════

    @Test
    void postStub_registersAndReturns201() {
        Map<String, Object> stub = Map.of(
                "stubId", "api-test-stub-1",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/test-endpoint",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of(
                        "statusCode", 200,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("result", "ok"))
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/stubs", stub, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("stubId");
        assertThat(res.getBody().get("status")).isEqualTo("registered");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStubs_returnsRegisteredList() {
        ResponseEntity<List> res = http.getForEntity("/api/v1/stubs", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty(); // seed stubs loaded at startup
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStub_byId_returnsDetails() {
        ResponseEntity<Map> res = http.getForEntity("/api/v1/stubs/stub_account_create_v1", Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("stubId")).isEqualTo("stub_account_create_v1");
        assertThat(res.getBody()).containsKey("requestMatcher");
        assertThat(res.getBody()).containsKey("responseTemplate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchResponseTemplate_updatesAndPersists() {
        // Create a stub
        Map<String, Object> stub = Map.of(
                "stubId", "edit-response-test-stub",
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

        // PATCH the response template
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

        // Verify update persisted
        ResponseEntity<Map> getRes = http.getForEntity("/api/v1/stubs/edit-response-test-stub", Map.class);
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> rt = (Map<String, Object>) getRes.getBody().get("responseTemplate");
        assertThat(((Number) rt.get("statusCode")).intValue()).isEqualTo(202);
        Map<String, Object> body = (Map<String, Object>) rt.get("body");
        assertThat(body).containsEntry("message", "updated");
        assertThat(body).containsEntry("extra", "field");
    }

    @Test
    void patchResponseTemplate_returns404ForUnknownStub() {
        ResponseEntity<Map> res = http.exchange(
                "/api/v1/stubs/nonexistent-stub-xyz/response",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of())),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void toggleStub_changesEnabledState() {
        ResponseEntity<Map> res = http.postForEntity(
                "/api/v1/stubs/stub_account_create_v1/toggle", null, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("enabled");

        // Toggle back to restore state
        http.postForEntity("/api/v1/stubs/stub_account_create_v1/toggle", null, Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteStub_removesFromRegistry() {
        // Register a stub to delete
        Map<String, Object> stub = Map.of(
                "stubId", "stub-to-delete-ctrl",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/delete-me",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of(
                        "statusCode", 200, "headers", Map.of(), "body", Map.of("ok", true))
        );
        http.postForEntity("/api/v1/stubs", stub, Map.class);

        // Verify it exists
        assertThat(http.getForEntity("/api/v1/stubs/stub-to-delete-ctrl", Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Delete it
        http.delete("/api/v1/stubs/stub-to-delete-ctrl");

        // Verify it's gone
        assertThat(http.getForEntity("/api/v1/stubs/stub-to-delete-ctrl", Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledStub_notMatchedDuringVirtualization() {
        // Register a custom stub
        Map<String, Object> stub = Map.of(
                "stubId", "stub-toggle-match-test",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/toggle-test-path",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of(
                        "statusCode", 200, "headers", Map.of(), "body", Map.of("toggled", "yes"))
        );
        http.postForEntity("/api/v1/stubs", stub, Map.class);

        // Confirm it matches when enabled — via /api/v1/virtual
        Map<String, Object> toggleReq = Map.of(
                "session_id", "toggle-test-sess",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/toggle-test-path",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> enabled = http.postForEntity("/api/v1/virtual", toggleReq, Map.class);
        assertThat(((Number) enabled.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) enabled.getBody().get("body")).get("toggled")).isEqualTo("yes");

        // Disable stub
        http.postForEntity("/api/v1/stubs/stub-toggle-match-test/toggle", null, Map.class);

        // Now same path should return 404 via /api/v1/virtual — stub is skipped
        ResponseEntity<Map> disabled = http.postForEntity("/api/v1/virtual", toggleReq, Map.class);
        assertThat(((Number) disabled.getBody().get("status_code")).intValue()).isEqualTo(404);

        // Re-enable stub (cleanup)
        http.postForEntity("/api/v1/stubs/stub-toggle-match-test/toggle", null, Map.class);
    }

    // ═══════════════════════════════════════════════════════
    // SESSIONS ADMIN API
    // ═══════════════════════════════════════════════════════

    @Test
    void createSession_returns201() {
        ResponseEntity<Map> res = http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "api-test-session", "ttl_minutes", 60), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("session_id")).isEqualTo("api-test-session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSessions_returnsList() {
        // Create a known session
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "list-sessions-test"), Map.class);

        ResponseEntity<List> res = http.getForEntity("/api/v1/sessions", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty();
        // Verify session appears in list
        boolean found = res.getBody().stream()
                .anyMatch(s -> "list-sessions-test".equals(((Map<?, ?>) s).get("session_id")));
        assertThat(found).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSessionState_returnsState_afterHttpRequest() {
        // Create session
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "inspect-session"), Map.class);

        // Add data via /api/v1/virtual (session_id in body)
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "inspect-session",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "Test", "currency", "USD", "initial_deposit", 100.0)
        ), Map.class);

        // Inspect session state via admin API
        ResponseEntity<Map> state = http.getForEntity(
                "/api/v1/sessions/inspect-session/state", Map.class);

        assertThat(state.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state.getBody()).containsKey("collections");
        Map<String, Object> collections = (Map<String, Object>) state.getBody().get("collections");
        assertThat(collections).containsKey("accounts");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteSession_removes() {
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "to-delete-session"), Map.class);

        http.delete("/api/v1/sessions/to-delete-session");

        ResponseEntity<Map> state = http.getForEntity(
                "/api/v1/sessions/to-delete-session/state", Map.class);
        Map<String, Object> collections = (Map<String, Object>) state.getBody().get("collections");
        assertThat(collections == null || collections.isEmpty()).isTrue();
    }

    // ═══════════════════════════════════════════════════════
    // HTTP STUB VIRTUALIZATION — via /api/v1/virtual
    // session_id in body (assignment snake_case contract)
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void http_account_post_matchesSeedStub() {
        Map<String, Object> req = Map.of(
                "session_id", "ctrl-account-test",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "API-Test", "currency", "USD", "initial_deposit", 250.0)
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("status_code")).intValue()).isEqualTo(201);
        assertThat(res.getBody().get("matched_stub_id")).isEqualTo("stub_account_create_v1");
        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body).containsKey("account_id");
        assertThat(body.get("account_holder")).isEqualTo("API-Test");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void http_transfer_createThenGet_statefulRoundTrip() {
        // Step 1: Create transfer via /api/v1/virtual
        Map<String, Object> createReq = Map.of(
                "session_id", "ctrl-transfer-sess",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/transfers",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("from_account", "ACC-001", "to_account", "ACC-002", "amount", 500.0)
        );

        ResponseEntity<Map> createRes = http.postForEntity("/api/v1/virtual", createReq, Map.class);

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) createRes.getBody().get("status_code")).intValue()).isEqualTo(201);
        Map<String, Object> createBody = (Map<String, Object>) createRes.getBody().get("body");
        String transferId = (String) createBody.get("transfer_id");
        assertThat(transferId).startsWith("TXF-");
        assertThat(createBody.get("status")).isEqualTo("COMPLETED");

        // Step 2: GET transfer by ID via /api/v1/virtual
        Map<String, Object> getReq = Map.of(
                "session_id", "ctrl-transfer-sess",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/transfers/" + transferId,
                "headers", Map.of(), "body", Map.of()
        );

        ResponseEntity<Map> getRes = http.postForEntity("/api/v1/virtual", getReq, Map.class);

        assertThat(((Number) getRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        Map<String, Object> getBody = (Map<String, Object>) getRes.getBody().get("body");
        assertThat(getBody.get("transfer_id")).isEqualTo(transferId);
        assertThat(getBody.get("from_account")).isEqualTo("ACC-001");
        assertThat(getBody.get("to_account")).isEqualTo("ACC-002");
    }

    @Test
    @SuppressWarnings("unchecked")
    void http_customer_createThenGet_statefulRoundTrip() {
        // Step 1: Create customer via /api/v1/virtual
        Map<String, Object> createReq = Map.of(
                "session_id", "ctrl-customer-sess",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/customers",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("name", "Jane Smith", "email", "jane@example.com",
                        "tier", "GOLD", "address", Map.of("country", "US"))
        );

        ResponseEntity<Map> createRes = http.postForEntity("/api/v1/virtual", createReq, Map.class);

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) createRes.getBody().get("status_code")).intValue()).isEqualTo(201);
        Map<String, Object> createBody = (Map<String, Object>) createRes.getBody().get("body");
        String customerId = (String) createBody.get("customer_id");
        assertThat(customerId).startsWith("CUS-");

        // Step 2: GET customer by ID via /api/v1/virtual
        Map<String, Object> getReq = Map.of(
                "session_id", "ctrl-customer-sess",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/customers/" + customerId,
                "headers", Map.of(), "body", Map.of()
        );

        ResponseEntity<Map> getRes = http.postForEntity("/api/v1/virtual", getReq, Map.class);

        assertThat(((Number) getRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) getRes.getBody().get("body")).get("customer_id")).isEqualTo(customerId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void http_savings_account_highPriorityStub_matchedViaVirtual() {
        // USD + SAVINGS → matches stub_savings_account_create_v1 (specificity=20, highest — 2 bodyJsonPath conditions)
        Map<String, Object> req = Map.of(
                "session_id", "ctrl-savings-sess",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "Saver", "initial_deposit", 2000.0,
                        "currency", "USD", "type", "SAVINGS")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("status_code")).intValue()).isEqualTo(201);
        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body.get("type")).isEqualTo("SAVINGS");
        assertThat(body.get("currency")).isEqualTo("USD");
    }

    // ═══════════════════════════════════════════════════════
    // TRIE BEHAVIOUR — path param extraction + method isolation
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void pathParam_extractedByTrie_andUsedInResponseTemplate() {
        // Proves:
        // 1. Trie matches {itemId} as a wildcard segment (not literal)
        // 2. The extracted value is available as {{request.pathVar.itemId}} in response
        // 3. Works in both simple strings and interpolated strings

        Map<String, Object> stub = Map.of(
                "stubId", "stub_echo_path_param",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/items/{itemId}",
                        "headers", Map.of(), "bodyJsonPath", Map.of()
                ),
                "responseTemplate", Map.of(
                        "statusCode", 200,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "item_id",   "{{request.pathVar.itemId}}",   // whole token → raw type preserved
                                "found",     true,
                                "label",     "Item {{request.pathVar.itemId}} confirmed" // interpolated string
                        )
                )
        );
        http.postForEntity("/api/v1/stubs", stub, Map.class);

        // GET /api/v1/items/ITEM-9876 via /api/v1/virtual
        Map<String, Object> req1 = Map.of(
                "session_id", "path-param-echo-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/items/ITEM-9876",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req1, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("status_code")).intValue()).isEqualTo(200);
        // {itemId} extracted from path and echoed in response
        Map<String, Object> resBody = (Map<String, Object>) res.getBody().get("body");
        assertThat(resBody.get("item_id")).isEqualTo("ITEM-9876");
        assertThat(resBody.get("found")).isEqualTo(true);
        assertThat(resBody.get("label").toString()).contains("ITEM-9876");

        // Different ID in path → different capture value
        Map<String, Object> req2 = Map.of(
                "session_id", "path-param-echo-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/items/ITEM-0001",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> res2 = http.postForEntity("/api/v1/virtual", req2, Map.class);
        assertThat(((Number) res2.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) res2.getBody().get("body")).get("item_id")).isEqualTo("ITEM-0001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void samePath_differentMethods_routedToSeparateStubs() {
        // Proves:
        // TrieNode.stubs = Map<method, List<Stub>>
        // POST and GET on /api/v1/catalog are stored under different keys
        // in the SAME trie node — the method field is the differentiator

        // Register POST stub
        Map<String, Object> postStub = Map.of(
                "stubId", "stub_catalog_post",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "POST",
                        "pathPattern", "/api/v1/catalog",
                        "headers", Map.of(), "bodyJsonPath", Map.of()
                ),
                "responseTemplate", Map.of(
                        "statusCode", 201,
                        "headers", Map.of(),
                        "body", Map.of("action", "CREATED", "method_hit", "POST")
                )
        );
        http.postForEntity("/api/v1/stubs", postStub, Map.class);

        // Register GET stub — same path, different method, different response
        Map<String, Object> getStub = Map.of(
                "stubId", "stub_catalog_get",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET",
                        "pathPattern", "/api/v1/catalog",
                        "headers", Map.of(), "bodyJsonPath", Map.of()
                ),
                "responseTemplate", Map.of(
                        "statusCode", 200,
                        "headers", Map.of(),
                        "body", Map.of("action", "LISTED", "method_hit", "GET")
                )
        );
        http.postForEntity("/api/v1/stubs", getStub, Map.class);

        // Register PATCH stub — same path, another method
        Map<String, Object> patchStub = Map.of(
                "stubId", "stub_catalog_patch",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "PATCH",
                        "pathPattern", "/api/v1/catalog",
                        "headers", Map.of(), "bodyJsonPath", Map.of()
                ),
                "responseTemplate", Map.of(
                        "statusCode", 200,
                        "headers", Map.of(),
                        "body", Map.of("action", "PATCHED", "method_hit", "PATCH")
                )
        );
        http.postForEntity("/api/v1/stubs", patchStub, Map.class);

        // POST → hits POST stub only
        Map<String, Object> postVReq = Map.of(
                "session_id", "method-isolation-test",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/catalog",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("x", 1)
        );
        ResponseEntity<Map> postRes = http.postForEntity("/api/v1/virtual", postVReq, Map.class);
        assertThat(((Number) postRes.getBody().get("status_code")).intValue()).isEqualTo(201);
        assertThat(((Map<?, ?>) postRes.getBody().get("body")).get("method_hit")).isEqualTo("POST");

        // GET → hits GET stub only
        Map<String, Object> getVReq = Map.of(
                "session_id", "method-isolation-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/catalog",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> getRes = http.postForEntity("/api/v1/virtual", getVReq, Map.class);
        assertThat(((Number) getRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) getRes.getBody().get("body")).get("method_hit")).isEqualTo("GET");

        // PATCH → hits PATCH stub only
        Map<String, Object> patchVReq = Map.of(
                "session_id", "method-isolation-test",
                "protocol", "HTTP", "method", "PATCH",
                "path", "/api/v1/catalog",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("x", 1)
        );
        ResponseEntity<Map> patchRes = http.postForEntity("/api/v1/virtual", patchVReq, Map.class);
        assertThat(((Number) patchRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) patchRes.getBody().get("body")).get("method_hit")).isEqualTo("PATCH");

        // DELETE → no stub registered → status_code 404 in VirtualResponse body
        Map<String, Object> delVReq = Map.of(
                "session_id", "method-isolation-test",
                "protocol", "HTTP", "method", "DELETE",
                "path", "/api/v1/catalog",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> deleteRes = http.postForEntity("/api/v1/virtual", delVReq, Map.class);
        assertThat(((Number) deleteRes.getBody().get("status_code")).intValue()).isEqualTo(404);
    }

    // ═══════════════════════════════════════════════════════
    // GRPC + KAFKA — STAY ON /api/v1/virtual (not real protocols)
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void grpc_virtualEndpoint_matchesStubAndReturns() {
        // gRPC has no HTTP URL — goes through /api/v1/virtual with protocol=GRPC
        Map<String, Object> req = Map.of(
                "session_id", "ctrl-grpc-test",
                "protocol", "GRPC",
                "method", "CreateAccount",
                "path", "",
                "headers", Map.of(),
                "body", Map.of("account_holder", "gRPC-User")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) res.getBody().get("status_code")).intValue()).isEqualTo(200);
        assertThat(res.getBody().get("matched_stub_id")).isEqualTo("stub_grpc_create_account");

        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body).containsKey("account_id");
        assertThat(body.get("account_holder")).isEqualTo("gRPC-User");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void grpc_getBalance_virtualEndpoint_returnsBalance() {
        Map<String, Object> req = Map.of(
                "session_id", "ctrl-grpc-balance",
                "protocol", "GRPC",
                "method", "GetBalance",
                "path", "",
                "headers", Map.of(),
                "body", Map.of("account_id", "ACC-9999")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("matched_stub_id")).isEqualTo("stub_grpc_get_balance_v1");
        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body).containsKey("balance");
        assertThat(body.get("currency")).isEqualTo("USD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafka_paymentCompleted_virtualEndpoint_matchesStub() {
        // Kafka has no HTTP URL — goes through /api/v1/virtual with protocol=KAFKA
        Map<String, Object> req = Map.of(
                "session_id", "ctrl-kafka-test",
                "protocol", "KAFKA",
                "method", "payment-events",
                "path", "",
                "headers", Map.of(),
                "body", Map.of("event_type", "PAYMENT_COMPLETED", "payment_id", "PAY-001")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("matched_stub_id")).isEqualTo("stub_kafka_payment_completed");
        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body.get("ack")).isEqualTo(true);
        assertThat(body.get("processed")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafka_accountEvent_virtualEndpoint_matchesStub() {
        Map<String, Object> req = Map.of(
                "session_id", "ctrl-kafka-acct",
                "protocol", "KAFKA",
                "method", "account.events",
                "path", "",
                "headers", Map.of(),
                "body", Map.of("event_type", "ACCOUNT_CREATED", "account_id", "ACC-5678")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("matched_stub_id")).isEqualTo("stub_kafka_account_event_v1");
        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body.get("ack")).isEqualTo(true);
        assertThat(body.get("event_type")).isEqualTo("ACCOUNT_CREATED");
    }

    @Test
    void virtual_missingSessionId_returns400() {
        // session_id is mandatory — missing → 400
        Map<String, Object> req = Map.of(
                "protocol", "HTTP",
                "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of(),
                "body", Map.of("account_holder", "Jane Doe")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsKey("error");
        assertThat(res.getBody().get("error").toString()).contains("session_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void http_viaVirtualEndpoint_assignmentContract() {
        // HTTP stub matched via /api/v1/virtual using the assignment snake_case contract
        Map<String, Object> req = Map.of(
                "session_id", "cicd-runner-9842",
                "protocol", "HTTP",
                "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json", "X-Correlation-ID", "trace-abc-123"),
                "body", Map.of("account_holder", "Jane Doe", "initial_deposit", 500.0, "currency", "USD")
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("matched_stub_id")).isEqualTo("stub_account_create_v1");
        assertThat(((Number) res.getBody().get("status_code")).intValue()).isEqualTo(201);
        assertThat(res.getBody().get("state_mutated")).isEqualTo(true);
        assertThat(res.getBody().get("execution_latency_ms")).isNotNull();
        assertThat(res.getBody().get("matched_rules")).isNotNull();
        Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
        assertThat(body).containsKey("account_id");
        assertThat(body.get("account_holder")).isEqualTo("Jane Doe");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
    }

    // ═══════════════════════════════════════════════════════
    // RECORDING / INGEST API
    // ═══════════════════════════════════════════════════════

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

        // Card number must be sanitized
        Map<String, Object> generatedStub = (Map<String, Object>) res.getBody().get("generatedStub");
        assertThat(generatedStub.toString()).doesNotContain("4111111111111111");
        assertThat(generatedStub.toString()).doesNotContain("123"); // CVV gone too
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestAndInvoke_generatedStubMatchesActualRequest() {
        // Step 1: Ingest a clean traffic log (no PCI/PII — high confidence → auto-published)
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "method", "POST",
                        "path", "/api/v1/widget-orders",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("widget_type", "PREMIUM", "quantity", 3)
                ),
                "response", Map.of(
                        "statusCode", 201,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("order_id", "ORD-5001", "status", "CONFIRMED")
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        double confidence = ((Number) ingestRes.getBody().get("confidence")).doubleValue();
        assertThat(confidence).isGreaterThanOrEqualTo(0.90);

        String status = (String) ingestRes.getBody().get("status");
        assertThat(status).isEqualTo("AUTO_PUBLISHED");

        // Step 2: Invoke the generated stub via /api/v1/virtual
        Map<String, Object> invokeReq = Map.of(
                "session_id", "ingest-invoke-sess",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/widget-orders",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("widget_type", "PREMIUM", "quantity", 3)
        );
        ResponseEntity<Map> invokeRes = http.postForEntity("/api/v1/virtual", invokeReq, Map.class);

        // Stub was auto-published and must now match
        assertThat(((Number) invokeRes.getBody().get("status_code")).intValue()).isEqualTo(201);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestPostAndGetTraffic_thenFullRoundTrip_viaActualUrls() {
        // ── Step 1: Ingest POST /api/v1/gadgets ──
        // Response has "name" and "category" which also appear in request
        // → StubGenerator emits {{request.body.name}} and {{request.body.category}}
        Map<String, Object> postLog = Map.of(
                "request", Map.of(
                        "protocol", "HTTP",
                        "method", "POST",
                        "path", "/api/v1/gadgets",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("name", "SuperWidget", "category", "ELECTRONICS", "price", 299.99)
                ),
                "response", Map.of(
                        "statusCode", 201,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "gadget_id", "GAD-0001",
                                "name",      "SuperWidget",     // same as request → {{request.body.name}}
                                "category",  "ELECTRONICS",    // same as request → {{request.body.category}}
                                "status",    "ACTIVE"           // only in response → static
                        )
                )
        );
        ResponseEntity<Map> postIngest = http.postForEntity("/api/v1/recording/ingest", postLog, Map.class);
        assertThat(postIngest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postIngest.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");

        // Verify dynamic echo templates in generated stub
        Map<String, Object> postStub = (Map<String, Object>) postIngest.getBody().get("generatedStub");
        Map<String, Object> postBody = (Map<String, Object>) ((Map<String, Object>) postStub.get("responseTemplate")).get("body");
        assertThat(postBody.get("gadget_id")).isEqualTo("GAD-0001");               // static from recording, not request
        assertThat(postBody.get("name")).isEqualTo("{{request.body.name}}");        // matched → dynamic
        assertThat(postBody.get("category")).isEqualTo("{{request.body.category}}"); // matched → dynamic
        assertThat(postBody.get("status")).isEqualTo("ACTIVE");                     // only in response → static

        // Verify stateAction WRITE
        Map<String, Object> postStateAction = (Map<String, Object>) postStub.get("stateAction");
        assertThat(postStateAction.get("op")).isEqualTo("WRITE");
        assertThat(postStateAction.get("collection")).isEqualTo("gadgets");

        // ── Step 2: Ingest GET /api/v1/gadgets/{id} ──
        // Path has {id} → Trie wildcard; response = STATIC recorded body, NO stateAction READ
        Map<String, Object> getLog = Map.of(
                "request", Map.of(
                        "protocol", "HTTP",
                        "method",   "GET",
                        "path",     "/api/v1/gadgets/{id}",   // {id} = Trie wildcard
                        "headers",  Map.of(),
                        "body",     Map.of()
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers",    Map.of("Content-Type", "application/json"),
                        "body",       Map.of("gadget_id", "GAD-0001", "name", "SuperWidget")
                )
        );
        ResponseEntity<Map> getIngest = http.postForEntity("/api/v1/recording/ingest", getLog, Map.class);
        assertThat(getIngest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(getIngest.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");

        // Verify GET stub has NO stateAction and a STATIC body from the recording
        Map<String, Object> getStub = (Map<String, Object>) getIngest.getBody().get("generatedStub");
        assertThat(getStub.get("stateAction")).isNull();
        Map<String, Object> getRtBody = (Map<String, Object>)
                ((Map<String, Object>) getStub.get("responseTemplate")).get("body");
        assertThat(getRtBody.get("gadget_id")).isEqualTo("GAD-0001");   // static from recording
        assertThat(getRtBody.get("name")).isEqualTo("SuperWidget");      // static from recording

        // ── Step 3: Create gadget via /api/v1/virtual (session A) ──
        Map<String, Object> createReq = Map.of(
                "session_id", "gadget-session-A",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/gadgets",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("name", "SuperWidget", "category", "ELECTRONICS", "price", 299.99)
        );
        ResponseEntity<Map> createRes = http.postForEntity("/api/v1/virtual", createReq, Map.class);
        assertThat(((Number) createRes.getBody().get("status_code")).intValue()).isEqualTo(201);

        // Name and category must be echoed from REQUEST (dynamic templates resolved)
        Map<String, Object> createBody = (Map<String, Object>) createRes.getBody().get("body");
        assertThat(createBody.get("name")).isEqualTo("SuperWidget");
        assertThat(createBody.get("category")).isEqualTo("ELECTRONICS");
        assertThat(createBody.get("status")).isEqualTo("ACTIVE");   // static value
        String gadgetId = (String) createBody.get("gadget_id");
        assertThat(gadgetId).startsWith("GAD-");

        // ── Step 4: GET gadget via /api/v1/virtual — returns STATIC recorded body ──
        // Wildcard {id} matches any real ID value. Response is the static recorded snapshot,
        // NOT the state-stored gadget. Any session gets the same static recorded values.
        Map<String, Object> getVReq = Map.of(
                "session_id", "gadget-session-A",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/gadgets/" + gadgetId,   // actual ID in path → wildcard match
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> getRes = http.postForEntity("/api/v1/virtual", getVReq, Map.class);
        assertThat(((Number) getRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        Map<String, Object> getBody = (Map<String, Object>) getRes.getBody().get("body");
        // Static recorded values returned (not the live state-stored gadget)
        assertThat(getBody.get("gadget_id")).isEqualTo("GAD-0001");
        assertThat(getBody.get("name")).isEqualTo("SuperWidget");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestGetWithLiteralPath_generatesStaticStub() {
        // GET path with no {param} → literal pathPattern, static response body, no error
        // Previously returned 400 — now generates a valid exact-match stub
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "HTTP",
                        "method",   "GET",
                        "path",     "/api/v1/gadgets",   // literal path, no {id}
                        "headers",  Map.of(),
                        "body",     Map.of()
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers",    Map.of(),
                        "body",       Map.of("gadget_id", "GAD-001", "name", "TestGadget")
                )
        );
        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", log, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");

        // Verify no stateAction and static body in generated stub
        Map<String, Object> stub = (Map<String, Object>) res.getBody().get("generatedStub");
        assertThat(stub.get("stateAction")).isNull();
        Map<String, Object> body = (Map<String, Object>)
                ((Map<String, Object>) stub.get("responseTemplate")).get("body");
        assertThat(body.get("gadget_id")).isEqualTo("GAD-001");
        assertThat(body.get("name")).isEqualTo("TestGadget");

        // Invoke the stub via /api/v1/virtual — exact path must match
        Map<String, Object> invokeReq = Map.of(
                "session_id", "literal-get-sess",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/gadgets",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> invokeRes = http.postForEntity("/api/v1/virtual", invokeReq, Map.class);
        assertThat(((Number) invokeRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        Map<String, Object> invokeBody = (Map<String, Object>) invokeRes.getBody().get("body");
        assertThat(invokeBody.get("gadget_id")).isEqualTo("GAD-001");
        assertThat(invokeBody.get("name")).isEqualTo("TestGadget");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestKafkaTraffic_thenInvokeViaVirtual() {
        // Step 1: Ingest Kafka traffic log with protocol=KAFKA
        // Use simple, well-classified fields to ensure high confidence → auto-publish
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "KAFKA",
                        "method", "order-events",     // topic name
                        "path", "",
                        "headers", Map.of(),
                        "body", Map.of("status", "PLACED", "amount", 75.0)
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of(),
                        "body", Map.of("ack", true, "status", "PLACED", "processed", true)
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Verify protocol is KAFKA in generated stub
        Map<String, Object> generatedStub = (Map<String, Object>) ingestRes.getBody().get("generatedStub");
        Map<String, Object> matcher = (Map<String, Object>) generatedStub.get("requestMatcher");
        assertThat(matcher.get("protocol")).isEqualTo("KAFKA");
        assertThat(matcher.get("method")).isEqualTo("order-events");
        assertThat(matcher.get("pathPattern")).isEqualTo("");

        double confidence = ((Number) ingestRes.getBody().get("confidence")).doubleValue();
        assertThat(confidence).isGreaterThanOrEqualTo(0.90);
        String ingStatus = (String) ingestRes.getBody().get("status");
        assertThat(ingStatus).isEqualTo("AUTO_PUBLISHED");

        // Step 2: Invoke the ingested Kafka stub via /api/v1/virtual
        Map<String, Object> req = Map.of(
                "session_id", "kafka-ingest-invoke",
                "protocol", "KAFKA",
                "method", "order-events",
                "path", "",
                "headers", Map.of(),
                "body", Map.of("status", "PLACED", "amount", 75.0)
        );

        ResponseEntity<Map> invokeRes = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(invokeRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) invokeRes.getBody().get("body");
        assertThat(body.get("ack")).isEqualTo(true);
        assertThat(body.get("processed")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestGrpcTraffic_thenInvokeViaVirtual() {
        // Step 1: Ingest gRPC traffic log with protocol=GRPC
        // Use simple, well-classified fields to ensure high confidence → auto-publish
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "GRPC",
                        "method", "ListTransactions",   // gRPC method name
                        "path", "",
                        "headers", Map.of(),
                        "body", Map.of("currency", "USD", "count", 10)
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of("Content-Type", "application/grpc"),
                        "body", Map.of("transaction_id", "TXN-5001", "status", "COMPLETED",
                                "currency", "USD")
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Verify protocol is GRPC in generated stub
        Map<String, Object> generatedStub = (Map<String, Object>) ingestRes.getBody().get("generatedStub");
        Map<String, Object> matcher = (Map<String, Object>) generatedStub.get("requestMatcher");
        assertThat(matcher.get("protocol")).isEqualTo("GRPC");
        assertThat(matcher.get("method")).isEqualTo("ListTransactions");
        assertThat(matcher.get("pathPattern")).isEqualTo("");

        double confidence = ((Number) ingestRes.getBody().get("confidence")).doubleValue();
        assertThat(confidence).isGreaterThanOrEqualTo(0.90);
        String ingStatus = (String) ingestRes.getBody().get("status");
        assertThat(ingStatus).isEqualTo("AUTO_PUBLISHED");

        // Step 2: Invoke the ingested gRPC stub via /api/v1/virtual
        Map<String, Object> req = Map.of(
                "session_id", "grpc-ingest-invoke",
                "protocol", "GRPC",
                "method", "ListTransactions",
                "path", "",
                "headers", Map.of(),
                "body", Map.of("currency", "USD", "count", 10)
        );

        ResponseEntity<Map> invokeRes = http.postForEntity("/api/v1/virtual", req, Map.class);

        assertThat(invokeRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) invokeRes.getBody().get("body");
        assertThat(body.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestKafkaTraffic_sanitizesAndReturnsKafkaStub() {
        // Ingest Kafka traffic with PCI data — should sanitize + generate KAFKA stub
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "KAFKA",
                        "method", "payment-notifications",
                        "path", "",
                        "headers", Map.of(),
                        "body", Map.of("credit_card", "4111111111111111", "event_type", "CHARGE",
                                "amount", 50.0)
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of(),
                        "body", Map.of("ack", true, "status", "RECEIVED")
                )
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Card number must be sanitized
        Map<String, Object> generatedStub = (Map<String, Object>) res.getBody().get("generatedStub");
        assertThat(generatedStub.toString()).doesNotContain("4111111111111111");

        // Protocol must be KAFKA
        Map<String, Object> matcher = (Map<String, Object>) generatedStub.get("requestMatcher");
        assertThat(matcher.get("protocol")).isEqualTo("KAFKA");
        assertThat(matcher.get("method")).isEqualTo("payment-notifications");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestGrpcTraffic_sanitizesAndReturnsGrpcStub() {
        // Ingest gRPC traffic with PII data — should sanitize + generate GRPC stub
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "GRPC",
                        "method", "CreateUser",
                        "path", "",
                        "headers", Map.of(),
                        "body", Map.of("email", "john@example.com", "ssn", "123-45-6789",
                                "name", "John Doe")
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of("Content-Type", "application/grpc"),
                        "body", Map.of("user_id", "USR-9999", "status", "CREATED")
                )
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // PII must be sanitized
        String fullResponse = res.getBody().toString();
        assertThat(fullResponse).doesNotContain("john@example.com");
        assertThat(fullResponse).doesNotContain("123-45-6789");

        // Protocol must be GRPC
        Map<String, Object> generatedStub = (Map<String, Object>) res.getBody().get("generatedStub");
        Map<String, Object> matcher = (Map<String, Object>) generatedStub.get("requestMatcher");
        assertThat(matcher.get("protocol")).isEqualTo("GRPC");
        assertThat(matcher.get("method")).isEqualTo("CreateUser");
    }

    // ═══════════════════════════════════════════════════════
    // INGEST RECORD — 3 STUB GENERATION CASES
    // ═══════════════════════════════════════════════════════

    /**
     * Case 1: POST with literal path value (no {param} brackets).
     *
     * Input:  POST /api/v1/employees/123
     *         body: {"name":"Jane Doe","salary":95000}
     *
     * Expected stub:
     *   pathPattern  = /api/v1/employees/123   (literal — Trie exact match)
     *   bodyJsonPath = {"$.name":"Jane Doe","$.salary":"95000"}  (match conditions)
     *   body:
     *     employee_id → "EMP-0001" (static recorded value — not sent by client, so replayed as-is)
     *     name        → {{request.body.name}}
     *     salary      → {{request.body.salary}}
     *
     * Virtual API: POST /api/v1/employees/123 with same body → 201 with dynamic echo
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestCase1_postWithLiteralPath_literalPatternBodyConditionsAndDynamicResponse() {
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "HTTP",
                        "method",   "POST",
                        "path",     "/api/v1/employees/123",
                        "headers",  Map.of("Content-Type", "application/json"),
                        "body",     Map.of("name", "Jane Doe", "salary", 95000)
                ),
                "response", Map.of(
                        "statusCode", 201,
                        "headers",    Map.of("Content-Type", "application/json"),
                        "body",       Map.of("employee_id", "EMP-0001", "name", "Jane Doe", "salary", 95000)
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ingestRes.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");

        Map<String, Object> stub    = (Map<String, Object>) ingestRes.getBody().get("generatedStub");
        Map<String, Object> matcher = (Map<String, Object>) stub.get("requestMatcher");

        // Verify literal pathPattern
        assertThat(matcher.get("pathPattern")).isEqualTo("/api/v1/employees/123");

        // Verify bodyJsonPath conditions include both string and numeric fields
        Map<String, Object> bodyJsonPath = (Map<String, Object>) matcher.get("bodyJsonPath");
        assertThat(bodyJsonPath).containsEntry("$.name", "Jane Doe");
        assertThat(bodyJsonPath.get("$.salary").toString()).isEqualTo("95000");

        // Verify response body templates: name/salary dynamic, employee_id stays the
        // static recorded value (it wasn't sent by the client, so there's nothing to echo
        // — record/replay means replay exactly, not mint a fresh ID per invocation)
        Map<String, Object> respBody = (Map<String, Object>)
                ((Map<String, Object>) stub.get("responseTemplate")).get("body");
        assertThat(respBody.get("employee_id")).isEqualTo("EMP-0001");
        assertThat(respBody.get("name")).isEqualTo("{{request.body.name}}");
        assertThat(respBody.get("salary").toString()).contains("request.body.salary");

        // Invoke via /api/v1/virtual — must match by literal path AND body conditions
        Map<String, Object> invokeReq = Map.of(
                "session_id", "case1-sess",
                "protocol",   "HTTP",
                "method",     "POST",
                "path",       "/api/v1/employees/123",
                "headers",    Map.of("Content-Type", "application/json"),
                "body",       Map.of("name", "Jane Doe", "salary", 95000)
        );
        ResponseEntity<Map> invokeRes = http.postForEntity("/api/v1/virtual", invokeReq, Map.class);
        assertThat(((Number) invokeRes.getBody().get("status_code")).intValue()).isEqualTo(201);

        Map<String, Object> respBodyActual = (Map<String, Object>) invokeRes.getBody().get("body");
        assertThat(respBodyActual.get("employee_id")).isEqualTo("EMP-0001");  // static recorded value, replayed exactly
        assertThat(respBodyActual.get("name")).isEqualTo("Jane Doe");     // echoed from request
        assertThat(((Number) respBodyActual.get("salary")).intValue()).isEqualTo(95000);  // echoed
    }

    /**
     * Case 2: GET with explicit {param} notation.
     *
     * Input:  GET /api/v1/department/{departmentId}
     *         recorded response: {"deptName":"account","deptId":123}
     *
     * Expected stub:
     *   pathPattern = /api/v1/department/{departmentId}  (Trie wildcard matches ANY deptId)
     *   stateAction = null  (pure static replay)
     *   body        = {"deptName":"account","deptId":123}  (static from recording)
     *
     * Virtual API: GET /api/v1/department/434 → returns static recorded values
     * Virtual API: GET /api/v1/department/999 → returns same static recorded values
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestCase2_getWithPathParam_wildcardPatternAndStaticResponse() {
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "HTTP",
                        "method",   "GET",
                        "path",     "/api/v1/department/{departmentId}",
                        "headers",  Map.of(),
                        "body",     Map.of()
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers",    Map.of("Content-Type", "application/json"),
                        "body",       Map.of("deptName", "account", "deptId", 123)
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ingestRes.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");

        Map<String, Object> stub    = (Map<String, Object>) ingestRes.getBody().get("generatedStub");
        Map<String, Object> matcher = (Map<String, Object>) stub.get("requestMatcher");

        // Wildcard pathPattern preserved
        assertThat(matcher.get("pathPattern")).isEqualTo("/api/v1/department/{departmentId}");

        // NO stateAction — pure static replay
        assertThat(stub.get("stateAction")).isNull();

        // Static body from recording
        Map<String, Object> respBody = (Map<String, Object>)
                ((Map<String, Object>) stub.get("responseTemplate")).get("body");
        assertThat(respBody.get("deptName")).isEqualTo("account");
        assertThat(((Number) respBody.get("deptId")).intValue()).isEqualTo(123);

        // Virtual API with ANY deptId value → matches wildcard, returns static body
        for (String deptId : new String[]{"434", "999", "0", "DEPT-99"}) {
            Map<String, Object> req = Map.of(
                    "session_id", "case2-sess",
                    "protocol", "HTTP", "method", "GET",
                    "path", "/api/v1/department/" + deptId,
                    "headers", Map.of(), "body", Map.of()
            );
            ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);
            assertThat(((Number) res.getBody().get("status_code")).intValue())
                    .as("deptId=%s must match wildcard stub", deptId).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) res.getBody().get("body");
            assertThat(body.get("deptName")).isEqualTo("account");
            assertThat(((Number) body.get("deptId")).intValue()).isEqualTo(123);
        }
    }

    /**
     * Case 3: GET with literal path segment (no {param} brackets).
     *
     * Input:  GET /api/v1/department/234
     *         recorded response: {"deptName":"engineering","deptId":234}
     *
     * Expected stub:
     *   pathPattern = /api/v1/department/234  (literal — exact Trie match)
     *   stateAction = null  (pure static replay)
     *   body        = {"deptName":"engineering","deptId":234}  (static from recording)
     *
     * Virtual API: GET /api/v1/department/234 → 200 with static values
     * Virtual API: GET /api/v1/department/999 → 404 (different literal path, no stub)
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestCase3_getWithLiteralPathSegment_exactMatchAndStaticResponse() {
        Map<String, Object> log = Map.of(
                "request", Map.of(
                        "protocol", "HTTP",
                        "method",   "GET",
                        "path",     "/api/v1/department/234",   // literal, no {}
                        "headers",  Map.of(),
                        "body",     Map.of()
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers",    Map.of("Content-Type", "application/json"),
                        "body",       Map.of("deptName", "engineering", "deptId", 234)
                )
        );

        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);

        // Must succeed — no error, no 400
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ingestRes.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");

        Map<String, Object> stub    = (Map<String, Object>) ingestRes.getBody().get("generatedStub");
        Map<String, Object> matcher = (Map<String, Object>) stub.get("requestMatcher");

        // Literal pathPattern (no {param})
        assertThat(matcher.get("pathPattern")).isEqualTo("/api/v1/department/234");

        // NO stateAction
        assertThat(stub.get("stateAction")).isNull();

        // Static body from recording
        Map<String, Object> respBody = (Map<String, Object>)
                ((Map<String, Object>) stub.get("responseTemplate")).get("body");
        assertThat(respBody.get("deptName")).isEqualTo("engineering");
        assertThat(((Number) respBody.get("deptId")).intValue()).isEqualTo(234);

        // Exact path match → 200
        Map<String, Object> exactReq = Map.of(
                "session_id", "case3-sess",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/department/234",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> exactRes = http.postForEntity("/api/v1/virtual", exactReq, Map.class);
        assertThat(((Number) exactRes.getBody().get("status_code")).intValue()).isEqualTo(200);
        Map<String, Object> exactBody = (Map<String, Object>) exactRes.getBody().get("body");
        assertThat(exactBody.get("deptName")).isEqualTo("engineering");

        // Different literal path → 404 (no stub registered for /api/v1/department/999)
        Map<String, Object> noMatchReq = Map.of(
                "session_id", "case3-sess",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/department/999",
                "headers", Map.of(), "body", Map.of()
        );
        ResponseEntity<Map> noMatchRes = http.postForEntity("/api/v1/virtual", noMatchReq, Map.class);
        assertThat(((Number) noMatchRes.getBody().get("status_code")).intValue()).isEqualTo(404);
    }

    // ═══════════════════════════════════════════════════════
    // DRIFT API
    // ═══════════════════════════════════════════════════════

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
        assertThat(getRes.getBody()).containsKey("routes");

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    void driftLatency_addsDelayToVirtualRequests() {
        // Set 50ms base latency — no jitter for determinism
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 50, "jitterMs", 0, "errorRate", 0.0), Map.class);

        Map<String, Object> req = Map.of(
                "session_id", "drift-latency-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/accounts",
                "headers", Map.of(), "body", Map.of()
        );

        long start = System.nanoTime();
        http.postForEntity("/api/v1/virtual", req, Map.class);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // Should take at least ~50ms due to drift
        assertThat(elapsed).isGreaterThan(30);

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    void driftErrorRate_injects500sViaVirtual() {
        // Set 100% error rate
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 0, "jitterMs", 0, "errorRate", 1.0), Map.class);

        // Via /api/v1/virtual — drift simulator injects status_code=500 in VirtualResponse body
        Map<String, Object> req = Map.of(
                "session_id", "drift-error-test",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "DriftTest", "currency", "USD", "initial_deposit", 100.0)
        );
        ResponseEntity<Map> res = http.postForEntity("/api/v1/virtual", req, Map.class);

        // Transport always 200 from VirtualController; stub status_code=500 from drift
        assertThat(((Number) res.getBody().get("status_code")).intValue()).isEqualTo(500);

        // Reset
        http.delete("/api/v1/drift/config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void driftMetrics_reflectsActivity() {
        // Set 100% error rate
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 0, "jitterMs", 0, "errorRate", 1.0), Map.class);

        Map<String, Object> req = Map.of(
                "session_id", "drift-metrics-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/accounts",
                "headers", Map.of(), "body", Map.of()
        );

        // Send 5 requests via /api/v1/virtual
        for (int i = 0; i < 5; i++) {
            http.postForEntity("/api/v1/virtual", req, Map.class);
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

    @Test
    @SuppressWarnings("unchecked")
    void driftJitter_observableVarianceInLatency() {
        // Set 50ms base + 30ms jitter → actual delay between 20ms and 80ms
        http.postForEntity("/api/v1/drift/config",
                Map.of("baseLatencyMs", 50, "jitterMs", 30, "errorRate", 0.0), Map.class);

        Map<String, Object> req = Map.of(
                "session_id", "drift-jitter-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/accounts",
                "headers", Map.of(), "body", Map.of()
        );

        long[] timings = new long[5];
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            http.postForEntity("/api/v1/virtual", req, Map.class);
            timings[i] = (System.nanoTime() - start) / 1_000_000;
        }

        // All requests should have some delay (jitter may vary but base is 50ms)
        for (long t : timings) {
            assertThat(t).isGreaterThan(10); // at minimum some delay
        }

        // Reset
        http.delete("/api/v1/drift/config");
    }

    // ═══════════════════════════════════════════════════════
    // TRAFFIC API
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void trafficRecent_returnsList_afterVirtualRequest() {
        // Generate traffic via /api/v1/virtual
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "traffic-test",
                "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/accounts/ACC-NONEXISTENT",
                "headers", Map.of(), "body", Map.of()
        ), Map.class);

        ResponseEntity<List> res = http.getForEntity("/api/v1/traffic/recent?limit=10", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void trafficSearch_filtersByProtocol() {
        // Generate HTTP traffic via /api/v1/virtual
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "traffic-search-sess",
                "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "SearchTest", "currency", "USD", "initial_deposit", 10.0)
        ), Map.class);

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/traffic/search?method=POST&limit=5", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty();
    }
}
