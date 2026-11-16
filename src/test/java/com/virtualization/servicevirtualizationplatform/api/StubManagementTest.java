package com.virtualization.servicevirtualizationplatform.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combined stub management tests covering:
 *
 *   1. Stub override rules (same stubId = replace, different stubId = coexist)
 *      — for both manual API and ingest API
 *   2. Stub search, filter, sort (StubSearchService)
 *   3. Stub update (PUT), toggle, patch response, expiry management
 *
 * Merged from StubOverrideTest + StubSearchAndUpdateTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StubManagementTest {

    @Autowired
    private TestRestTemplate http;

    @SuppressWarnings("unchecked")
    private Map<String, Object> callVirtual(String sessionId, String method, String path, Map<String, Object> body) {
        Map<String, Object> req = Map.of(
                "session_id", sessionId, "protocol", "HTTP", "method", method,
                "path", path, "headers", Map.of(), "body", body
        );
        return http.postForEntity("/api/v1/virtual", req, Map.class).getBody();
    }

    private void registerStub(String id, String protocol, String method, String path,
                               Map<String, String> bodyJsonPath, boolean enabled, Object responseBody) {
        Map<String, Object> stub = Map.of(
                "stubId", id,
                "requestMatcher", Map.of(
                        "protocol", protocol, "method", method, "pathPattern", path,
                        "headers", Map.of(), "bodyJsonPath", bodyJsonPath),
                "responseTemplate", Map.of(
                        "statusCode", 200, "headers", Map.of(), "body", responseBody)
        );
        http.postForEntity("/api/v1/stubs", stub, Map.class);
        if (!enabled) {
            http.postForEntity("/api/v1/stubs/" + id + "/toggle", null, Map.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SECTION 1: Stub Override Rules (from StubOverrideTest)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void sameStubId_reRegistered_overridesResponse_notDuplicate() {
        String path = "/api/v1/override-test/same-id";
        Map<String, Object> matcher = Map.of(
                "protocol", "HTTP", "method", "POST", "pathPattern", path,
                "headers", Map.of(), "bodyJsonPath", Map.of()
        );

        Map<String, Object> v1 = Map.of(
                "stubId", "stub_override_same_id",
                "requestMatcher", matcher,
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("version", "v1"))
        );
        ResponseEntity<Map> firstRes = http.postForEntity("/api/v1/stubs", v1, Map.class);
        assertThat(firstRes.getBody().get("status")).isEqualTo("registered");
        assertThat((List<String>) firstRes.getBody().get("replacedStubIds")).isEmpty();

        Map<String, Object> v2 = Map.of(
                "stubId", "stub_override_same_id",
                "requestMatcher", matcher,
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("version", "v2"))
        );
        ResponseEntity<Map> secondRes = http.postForEntity("/api/v1/stubs", v2, Map.class);
        assertThat(secondRes.getBody().get("status")).isEqualTo("replaced");
        assertThat((List<String>) secondRes.getBody().get("replacedStubIds")).containsExactly("stub_override_same_id");

        ResponseEntity<Map> stored = http.getForEntity("/api/v1/stubs/stub_override_same_id", Map.class);
        Map<String, Object> storedBody = (Map<String, Object>)
                ((Map<String, Object>) stored.getBody().get("responseTemplate")).get("body");
        assertThat(storedBody.get("version")).isEqualTo("v2");

        Map<String, Object> result = callVirtual("ovr-s1", "POST", path, Map.of());
        assertThat(result.get("matched_stub_id")).isEqualTo("stub_override_same_id");
        assertThat((Map<String, Object>) result.get("body")).containsEntry("version", "v2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void identicalMatcher_differentStubId_viaManualApi_bothCoexist_byDesign() {
        String path = "/api/v1/override-test/found-notfound-pair";
        Map<String, Object> matcher = Map.of(
                "protocol", "HTTP", "method", "GET", "pathPattern", path,
                "headers", Map.of(), "bodyJsonPath", Map.of()
        );

        Map<String, Object> foundStub = Map.of(
                "stubId", "stub_override_found",
                "requestMatcher", matcher,
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", "{{state.result}}"),
                "stateAction", Map.of("op", "READ", "collection", "override_items", "keyExpr", "widget-1")
        );
        Map<String, Object> notFoundStub = Map.of(
                "stubId", "stub_override_not_found",
                "requestMatcher", matcher,
                "responseTemplate", Map.of("statusCode", 404, "headers", Map.of(), "body", Map.of("error", "not found"))
        );

        ResponseEntity<Map> foundRes = http.postForEntity("/api/v1/stubs", foundStub, Map.class);
        ResponseEntity<Map> notFoundRes = http.postForEntity("/api/v1/stubs", notFoundStub, Map.class);

        assertThat(foundRes.getBody().get("status")).isEqualTo("registered");
        assertThat(notFoundRes.getBody().get("status")).isEqualTo("registered");
        assertThat((List<String>) notFoundRes.getBody().get("replacedStubIds")).isEmpty();
        assertThat(http.getForEntity("/api/v1/stubs/stub_override_found", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/stubs/stub_override_not_found", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> result = callVirtual("ovr-chain-1", "GET", path, Map.of());
        assertThat(result.get("matched_stub_id")).isEqualTo("stub_override_not_found");
        assertThat(((Number) result.get("status_code")).intValue()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void differentBodyValue_isADifferentStub_bothCoexistAndRouteCorrectly() {
        String path = "/api/v1/override-test/different-values-coexist";

        Map<String, Object> stubA = Map.of(
                "stubId", "stub_override_type_gold",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "POST", "pathPattern", path,
                        "headers", Map.of(), "bodyJsonPath", Map.of("$.tier", "GOLD")),
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("discount", 20))
        );
        Map<String, Object> stubB = Map.of(
                "stubId", "stub_override_type_silver",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "POST", "pathPattern", path,
                        "headers", Map.of(), "bodyJsonPath", Map.of("$.tier", "SILVER")),
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("discount", 10))
        );

        ResponseEntity<Map> resA = http.postForEntity("/api/v1/stubs", stubA, Map.class);
        ResponseEntity<Map> resB = http.postForEntity("/api/v1/stubs", stubB, Map.class);

        assertThat(resA.getBody().get("status")).isEqualTo("registered");
        assertThat(resB.getBody().get("status")).isEqualTo("registered");
        assertThat((List<String>) resA.getBody().get("replacedStubIds")).isEmpty();
        assertThat((List<String>) resB.getBody().get("replacedStubIds")).isEmpty();

        Map<String, Object> goldResult = callVirtual("ovr-s3", "POST", path, Map.of("tier", "GOLD"));
        assertThat(goldResult.get("matched_stub_id")).isEqualTo("stub_override_type_gold");
        assertThat((Map<String, Object>) goldResult.get("body")).containsEntry("discount", 20);

        Map<String, Object> silverResult = callVirtual("ovr-s3", "POST", path, Map.of("tier", "SILVER"));
        assertThat(silverResult.get("matched_stub_id")).isEqualTo("stub_override_type_silver");
        assertThat((Map<String, Object>) silverResult.get("body")).containsEntry("discount", 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingest_sameRequestValuesTwice_differentResponse_overridesNotDuplicates() {
        String path = "/api/v1/override-ingest/same-request";

        Map<String, Object> log1 = Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST", "path", path,
                        "headers", Map.of(), "body", Map.of("name", "Widget-Fixed")),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("price", 100))
        );
        ResponseEntity<Map> ingest1 = http.postForEntity("/api/v1/recording/ingest", log1, Map.class);
        assertThat(ingest1.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");
        String firstStubId = (String) ((Map<String, Object>) ingest1.getBody().get("generatedStub")).get("stubId");

        Map<String, Object> log2 = Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST", "path", path,
                        "headers", Map.of(), "body", Map.of("name", "Widget-Fixed")),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("price", 150))
        );
        ResponseEntity<Map> ingest2 = http.postForEntity("/api/v1/recording/ingest", log2, Map.class);
        assertThat(ingest2.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");
        String secondStubId = (String) ((Map<String, Object>) ingest2.getBody().get("generatedStub")).get("stubId");

        assertThat(secondStubId).isNotEqualTo(firstStubId);

        ResponseEntity<Map> oldLookup = http.getForEntity("/api/v1/stubs/" + firstStubId, Map.class);
        assertThat(oldLookup.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> result = callVirtual("ingest-ovr-1", "POST", path, Map.of("name", "Widget-Fixed"));
        assertThat(result.get("matched_stub_id")).isEqualTo(secondStubId);
        assertThat((Map<String, Object>) result.get("body")).containsEntry("price", 150);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingest_differentRequestValues_createsDistinctCoexistingStubs() {
        String path = "/api/v1/override-ingest/different-request";

        Map<String, Object> log1 = Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST", "path", path,
                        "headers", Map.of(), "body", Map.of("name", "Widget-A")),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("price", 100))
        );
        Map<String, Object> log2 = Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST", "path", path,
                        "headers", Map.of(), "body", Map.of("name", "Widget-B")),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of("price", 200))
        );

        ResponseEntity<Map> ingest1 = http.postForEntity("/api/v1/recording/ingest", log1, Map.class);
        ResponseEntity<Map> ingest2 = http.postForEntity("/api/v1/recording/ingest", log2, Map.class);
        assertThat(ingest1.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");
        assertThat(ingest2.getBody().get("status")).isEqualTo("AUTO_PUBLISHED");
        String stubA = (String) ((Map<String, Object>) ingest1.getBody().get("generatedStub")).get("stubId");
        String stubB = (String) ((Map<String, Object>) ingest2.getBody().get("generatedStub")).get("stubId");

        assertThat(http.getForEntity("/api/v1/stubs/" + stubA, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/stubs/" + stubB, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> resultA = callVirtual("ingest-ovr-2", "POST", path, Map.of("name", "Widget-A"));
        assertThat(resultA.get("matched_stub_id")).isEqualTo(stubA);
        assertThat((Map<String, Object>) resultA.get("body")).containsEntry("price", 100);

        Map<String, Object> resultB = callVirtual("ingest-ovr-2", "POST", path, Map.of("name", "Widget-B"));
        assertThat(resultB.get("matched_stub_id")).isEqualTo(stubB);
        assertThat((Map<String, Object>) resultB.get("body")).containsEntry("price", 200);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SECTION 2: Search, Filter, Sort (from StubSearchAndUpdateTest)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void search_byQuery_matchesStubIdOrPathOrMethod() {
        registerStub("search-query-widget-1", "HTTP", "GET", "/api/v1/search-query-widgets",
                Map.of(), true, Map.of("ok", true));

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-query-widget", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = res.getBody();
        assertThat(results).isNotEmpty();
        assertThat(results.stream().allMatch(s -> ((String) s.get("stubId")).contains("search-query-widget")))
                .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_byProtocolAndMethod_filtersCorrectly() {
        registerStub("search-proto-get", "HTTP", "GET", "/api/v1/search-proto-item", Map.of(), true, Map.of());
        registerStub("search-proto-post", "HTTP", "POST", "/api/v1/search-proto-item", Map.of(), true, Map.of());

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-proto&protocol=HTTP&method=GET", List.class);

        List<Map<String, Object>> results = res.getBody();
        assertThat(results).extracting(s -> s.get("stubId")).contains("search-proto-get");
        assertThat(results).extracting(s -> s.get("stubId")).doesNotContain("search-proto-post");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_byPath_matchesPartialPathAcrossVariants() {
        registerStub("search-path-base", "HTTP", "GET", "/api/v1/search-path-employees", Map.of(), true, Map.of());
        registerStub("search-path-withid", "HTTP", "GET", "/api/v1/search-path-employees/{id}", Map.of(), true, Map.of());

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?path=search-path-employees", List.class);

        List<String> ids = res.getBody().stream().map(s -> (String) ((Map<String, Object>) s).get("stubId")).toList();
        assertThat(ids).contains("search-path-base", "search-path-withid");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_byEnabled_excludesDisabledStubs() {
        registerStub("search-enabled-on", "HTTP", "GET", "/api/v1/search-enabled-on-path", Map.of(), true, Map.of());
        registerStub("search-enabled-off", "HTTP", "GET", "/api/v1/search-enabled-off-path", Map.of(), false, Map.of());

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-enabled&enabled=false", List.class);

        List<String> ids = res.getBody().stream().map(s -> (String) ((Map<String, Object>) s).get("stubId")).toList();
        assertThat(ids).contains("search-enabled-off");
        assertThat(ids).doesNotContain("search-enabled-on");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_bySource_findsOnlyManualStubs() {
        registerStub("search-source-manual", "HTTP", "GET", "/api/v1/search-source-manual-path", Map.of(), true, Map.of());

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-source-manual&source=manual", List.class);

        assertThat(res.getBody()).isNotEmpty();
        List<Map<String, Object>> results = res.getBody();
        assertThat(results.stream().allMatch(s -> "manual".equals(s.get("source")))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_sortBySpecificity_mostConditionsFirst() {
        registerStub("search-sort-plain", "HTTP", "POST", "/api/v1/search-sort-path",
                Map.of(), true, Map.of());
        registerStub("search-sort-specific", "HTTP", "POST", "/api/v1/search-sort-path",
                Map.of("$.tier", "GOLD"), true, Map.of());

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-sort&sortBy=specificity", List.class);

        List<String> ids = res.getBody().stream().map(s -> (String) ((Map<String, Object>) s).get("stubId")).toList();
        int specificIdx = ids.indexOf("search-sort-specific");
        int plainIdx = ids.indexOf("search-sort-plain");
        assertThat(specificIdx).as("higher-specificity stub should sort first").isLessThan(plainIdx);
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_sortById_alphabetical() {
        registerStub("search-sort-id-zebra", "HTTP", "GET", "/api/v1/search-sort-id-path-1", Map.of(), true, Map.of());
        registerStub("search-sort-id-apple", "HTTP", "GET", "/api/v1/search-sort-id-path-2", Map.of(), true, Map.of());

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-sort-id&sortBy=id", List.class);

        List<String> ids = res.getBody().stream().map(s -> (String) ((Map<String, Object>) s).get("stubId")).toList();
        assertThat(ids.indexOf("search-sort-id-apple")).isLessThan(ids.indexOf("search-sort-id-zebra"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_limitParam_capsResultCount() {
        for (int i = 0; i < 5; i++) {
            registerStub("search-limit-stub-" + i, "HTTP", "GET", "/api/v1/search-limit-path-" + i,
                    Map.of(), true, Map.of());
        }

        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=search-limit-stub&limit=2", List.class);

        assertThat(res.getBody()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void search_noMatches_returnsEmptyListNot404() {
        ResponseEntity<List> res = http.getForEntity(
                "/api/v1/stubs?query=definitely-does-not-exist-anywhere-xyz", List.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // SECTION 3: Update, Expiry (from StubSearchAndUpdateTest)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void putStub_fullyReplacesDefinition_andIsReachableAfterward() {
        registerStub("put-update-target", "HTTP", "GET", "/api/v1/put-update-path",
                Map.of(), true, Map.of("version", "v1"));

        Map<String, Object> replacement = Map.of(
                "stubId", "put-update-target",
                "requestMatcher", Map.of(
                        "protocol", "HTTP", "method", "GET", "pathPattern", "/api/v1/put-update-path",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of(
                        "statusCode", 201, "headers", Map.of(), "body", Map.of("version", "v2-via-put"))
        );

        ResponseEntity<Map> putRes = http.exchange(
                "/api/v1/stubs/put-update-target", HttpMethod.PUT,
                new HttpEntity<>(replacement), Map.class);

        assertThat(putRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> getRes = http.getForEntity("/api/v1/stubs/put-update-target", Map.class);
        Map<String, Object> rt = (Map<String, Object>) getRes.getBody().get("responseTemplate");
        assertThat(((Number) rt.get("statusCode")).intValue()).isEqualTo(201);
        assertThat((Map<String, Object>) rt.get("body")).containsEntry("version", "v2-via-put");
    }

    @Test
    void putStub_unknownId_returns404() {
        Map<String, Object> body = Map.of(
                "stubId", "does-not-exist-put-test",
                "requestMatcher", Map.of("protocol", "HTTP", "method", "GET", "pathPattern", "/x",
                        "headers", Map.of(), "bodyJsonPath", Map.of()),
                "responseTemplate", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of())
        );

        ResponseEntity<Map> res = http.exchange(
                "/api/v1/stubs/does-not-exist-put-test", HttpMethod.PUT,
                new HttpEntity<>(body), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateExpiry_setsExpiresAt_andPersists() {
        registerStub("expiry-set-test-stub", "HTTP", "GET", "/api/v1/expiry-set-test",
                Map.of(), true, Map.of());

        String future = java.time.Instant.now().plusSeconds(3600).toString();
        ResponseEntity<Map> patchRes = http.exchange(
                "/api/v1/stubs/expiry-set-test-stub/expiry", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expiresAt", future)), Map.class);

        assertThat(patchRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchRes.getBody()).containsEntry("expiresAt", future);

        ResponseEntity<Map> getRes = http.getForEntity("/api/v1/stubs/expiry-set-test-stub", Map.class);
        assertThat(getRes.getBody()).containsEntry("expiresAt", future);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateExpiry_nullClearsExistingExpiry() {
        registerStub("expiry-clear-test-stub", "HTTP", "GET", "/api/v1/expiry-clear-test",
                Map.of(), true, Map.of());
        String future = java.time.Instant.now().plusSeconds(3600).toString();
        http.exchange("/api/v1/stubs/expiry-clear-test-stub/expiry", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expiresAt", future)), Map.class);

        ResponseEntity<Map> clearRes = http.exchange(
                "/api/v1/stubs/expiry-clear-test-stub/expiry", HttpMethod.PATCH,
                new HttpEntity<>(java.util.Collections.singletonMap("expiresAt", null)), Map.class);

        assertThat(clearRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clearRes.getBody()).containsEntry("expiresAt", "none");

        ResponseEntity<Map> getRes = http.getForEntity("/api/v1/stubs/expiry-clear-test-stub", Map.class);
        assertThat(getRes.getBody().get("expiresAt")).isNull();
    }

    @Test
    void updateExpiry_unknownStub_returns404() {
        ResponseEntity<Map> res = http.exchange(
                "/api/v1/stubs/does-not-exist-expiry-test/expiry", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expiresAt", java.time.Instant.now().toString())), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateExpiry_malformedInstant_returns400() {
        registerStub("expiry-bad-format-stub", "HTTP", "GET", "/api/v1/expiry-bad-format",
                Map.of(), true, Map.of());

        ResponseEntity<Map> res = http.exchange(
                "/api/v1/stubs/expiry-bad-format-stub/expiry", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expiresAt", "not-a-real-timestamp")), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
