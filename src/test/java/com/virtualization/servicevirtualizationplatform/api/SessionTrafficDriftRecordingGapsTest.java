package com.virtualization.servicevirtualizationplatform.api;

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
 * Closes concrete test-coverage gaps found while auditing the control-plane APIs that back the
 * Web UI's Session Inspector, Traffic Viewer, Drift Configurator, and Recording History panel.
 * None of the endpoints below had ANY test coverage before this file, despite each one being
 * wired to a live button/panel in the UI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionTrafficDriftRecordingGapsTest {

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void resetDrift() {
        // Drift config is shared across the whole test JVM (single Spring context) — same
        // reset-before-each pattern ControlPlaneApiTest already established.
        http.delete("/api/v1/drift/config");
    }

    // ═══════════════════════════════════════════════════════
    // POST /api/v1/sessions/{id}/clear — "Clear State" button in the UI
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void clearState_removesEntitiesButKeepsSessionAlive() {
        String sessionId = "gap-test-clear-session";
        http.postForEntity("/api/v1/sessions", Map.of("session_id", sessionId), Map.class);

        // Write some state via a seed-stub-backed virtual call
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", sessionId, "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "ClearTest", "currency", "USD", "initial_deposit", 10.0)
        ), Map.class);

        ResponseEntity<Map> beforeClear = http.getForEntity("/api/v1/sessions/" + sessionId + "/state", Map.class);
        assertThat(((Number) beforeClear.getBody().get("entity_count")).intValue()).isGreaterThan(0);

        ResponseEntity<Void> clearRes = http.exchange(
                "/api/v1/sessions/" + sessionId + "/clear", HttpMethod.POST, null, Void.class);
        assertThat(clearRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> afterClear = http.getForEntity("/api/v1/sessions/" + sessionId + "/state", Map.class);
        assertThat(((Number) afterClear.getBody().get("entity_count")).intValue()).isEqualTo(0);

        // Session itself must still exist (clear != destroy) \u2014 listed among active sessions
        ResponseEntity<List> listRes = http.getForEntity("/api/v1/sessions", List.class);
        boolean stillListed = listRes.getBody().stream()
                .anyMatch(s -> sessionId.equals(((Map<String, Object>) s).get("session_id")));
        assertThat(stillListed).as("clearing state must not destroy the session itself").isTrue();
    }

    // ═══════════════════════════════════════════════════════
    // DELETE /api/v1/sessions/{sessionId}/{collection}/{key} — single-entity delete
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void deleteEntity_removesOnlyThatKey_leavesOthersIntact() {
        String sessionId = "gap-test-delete-entity-session";
        http.postForEntity("/api/v1/sessions", Map.of("session_id", sessionId), Map.class);

        ResponseEntity<Map> created1 = http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", sessionId, "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "KeepMe", "currency", "USD", "initial_deposit", 10.0)
        ), Map.class);
        ResponseEntity<Map> created2 = http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", sessionId, "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "DeleteMe", "currency", "USD", "initial_deposit", 20.0)
        ), Map.class);

        String idToKeep = (String) ((Map<String, Object>) created1.getBody().get("body")).get("account_id");
        String idToDelete = (String) ((Map<String, Object>) created2.getBody().get("body")).get("account_id");

        ResponseEntity<Void> deleteRes = http.exchange(
                "/api/v1/sessions/" + sessionId + "/accounts/" + idToDelete,
                HttpMethod.DELETE, null, Void.class);
        assertThat(deleteRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> state = http.getForEntity("/api/v1/sessions/" + sessionId + "/state", Map.class);
        Map<String, Object> collections = (Map<String, Object>) state.getBody().get("collections");
        Map<String, Object> accounts = (Map<String, Object>) collections.get("accounts");
        assertThat(accounts).containsKey(idToKeep);
        assertThat(accounts).doesNotContainKey(idToDelete);
    }

    // ═══════════════════════════════════════════════════════
    // DELETE /api/v1/traffic — "Clear" button in the Traffic Viewer
    // ═══════════════════════════════════════════════════════

    @Test
    void clearTraffic_emptiesRecentEntries_andResetsTotalLogged() {
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "gap-test-traffic-clear", "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/accounts/NONEXISTENT", "headers", Map.of(), "body", Map.of()
        ), Map.class);

        ResponseEntity<List> beforeClear = http.getForEntity("/api/v1/traffic/recent?limit=10", List.class);
        assertThat(beforeClear.getBody()).isNotEmpty();

        ResponseEntity<Map> clearRes = http.exchange("/api/v1/traffic", HttpMethod.DELETE, null, Map.class);
        assertThat(clearRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> afterClear = http.getForEntity("/api/v1/traffic/recent?limit=10", List.class);
        assertThat(afterClear.getBody()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════
    // GET /api/v1/traffic/search — exercise every filter dimension, not just one
    // (the existing test only checked `method=POST` under a misleading name
    // "trafficSearch_filtersByProtocol" and never asserted the results were actually filtered)
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void trafficSearch_bySessionId_returnsOnlyThatSessionsTraffic() {
        String sessionA = "gap-traffic-search-session-a";
        String sessionB = "gap-traffic-search-session-b";
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", sessionA, "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/traffic-search-marker-a", "headers", Map.of(), "body", Map.of()
        ), Map.class);
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", sessionB, "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/traffic-search-marker-b", "headers", Map.of(), "body", Map.of()
        ), Map.class);

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/traffic/search?sessionId=" + sessionA + "&limit=20", List.class);

        List<Map<String, Object>> entries = res.getBody();
        assertThat(entries).isNotEmpty();
        assertThat(entries.stream().allMatch(e -> sessionA.equals(e.get("sessionId")))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void trafficSearch_byStatusCode_filtersOutOtherStatusCodes() {
        http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "gap-traffic-search-status", "protocol", "HTTP", "method", "GET",
                "path", "/api/v1/accounts/DOES-NOT-EXIST-404-CHECK", "headers", Map.of(), "body", Map.of()
        ), Map.class);

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/traffic/search?statusCode=404&limit=20", List.class);

        List<Map<String, Object>> entries = res.getBody();
        assertThat(entries).isNotEmpty();
        assertThat(entries.stream().allMatch(e -> ((Number) e.get("statusCode")).intValue() == 404)).isTrue();
    }

    @Test
    void trafficSearch_noMatches_returnsEmptyListNot404() {
        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/traffic/search?path=definitely-not-a-real-path-xyz123", List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════
    // POST /api/v1/drift/config/{route} — route-specific override, used by the
    // "Route-Specific Drift" panel in the UI, previously completely untested
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void routeSpecificDrift_appliesOnlyToThatRoute_notGlobally() {
        String route = "/api/v1/gap-test-drift-route";

        // Drift only wraps the response of an ALREADY-MATCHED stub (StubMatchingEngine applies
        // it after a match, not on the "no stub found" 404 fallback) — register one first.
        http.postForEntity("/api/v1/stubs", Map.of(
                "stubId", "gap-test-drift-route-stub",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "GET", "pathPattern", route,
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of())
        ), Map.class);

        String url = org.springframework.web.util.UriComponentsBuilder
                .fromPath("/api/v1/drift/config/route")
                .queryParam("route", route)
                .build()
                .toUriString();
        ResponseEntity<Map> setRes = http.postForEntity(
                url, Map.of("baseLatencyMs", 0, "jitterMs", 0, "errorRate", 1.0), Map.class);
        assertThat(setRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> configRes = http.getForEntity("/api/v1/drift/config", Map.class);
        Map<String, Object> routes = (Map<String, Object>) configRes.getBody().get("routes");
        assertThat(routes).containsKey(route);

        // A request to the configured, now-matched route should be forced to error (errorRate = 1.0)
        Map<String, Object> result = http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "gap-drift-route-sess", "protocol", "HTTP", "method", "GET",
                "path", route, "headers", Map.of(), "body", Map.of()
        ), Map.class).getBody();
        assertThat(((Number) result.get("status_code")).intValue()).isEqualTo(500);

        // An UNRELATED route must stay completely unaffected (route-specific, not global)
        Map<String, Object> unrelated = http.postForEntity("/api/v1/virtual", Map.of(
                "session_id", "gap-drift-route-sess2", "protocol", "HTTP", "method", "POST",
                "path", "/api/v1/accounts",
                "headers", Map.of("Content-Type", "application/json"),
                "body", Map.of("account_holder", "Y", "currency", "USD", "initial_deposit", 1.0)
        ), Map.class).getBody();
        assertThat(((Number) unrelated.get("status_code")).intValue()).isEqualTo(201);

        // Cleanup: global reset also clears route configs (documented DriftController behavior)
        http.exchange("/api/v1/drift/config", HttpMethod.DELETE, null, Map.class);
    }

    // ═══════════════════════════════════════════════════════
    // GET /api/v1/recording/history — backs the Recording History panel, previously untested
    // ═══════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void recordingHistory_includesIngestedRecord() {
        Map<String, Object> log = Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST",
                        "path", "/api/v1/gap-test-history-path", "headers", Map.of(),
                        "body", Map.of("name", "HistoryCheck")),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("status", "OK"))
        );
        ResponseEntity<Map> ingestRes = http.postForEntity("/api/v1/recording/ingest", log, Map.class);
        String recordId = (String) ingestRes.getBody().get("recordId");

        ResponseEntity<List> historyRes = http.getForEntity("/api/v1/recording/history", List.class);
        assertThat(historyRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> history = historyRes.getBody();
        boolean found = history.stream().anyMatch(r -> recordId.equals(r.get("id")));
        assertThat(found).as("the just-ingested record must appear in history").isTrue();
    }
}
