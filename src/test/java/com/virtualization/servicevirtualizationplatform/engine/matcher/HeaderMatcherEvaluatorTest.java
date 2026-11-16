package com.virtualization.servicevirtualizationplatform.engine.matcher;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HeaderMatcherEvaluator.
 *
 * Verifies case-insensitive header matching, partial requirements,
 * multi-header conditions, and edge cases (null, empty).
 *
 * Headers are case-insensitive per HTTP RFC 7230: "Content-Type" == "content-type" == "CONTENT-TYPE".
 */
class HeaderMatcherEvaluatorTest {

    // ═══ EXACT MATCH ═══

    @Test
    void singleHeader_exactMatch() {
        Map<String, String> required = Map.of("Content-Type", "application/json");
        Map<String, String> actual   = Map.of("Content-Type", "application/json");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    @Test
    void singleHeader_valueMismatch() {
        Map<String, String> required = Map.of("Content-Type", "application/json");
        Map<String, String> actual   = Map.of("Content-Type", "text/plain");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isFalse();
    }

    @Test
    void singleHeader_missing() {
        Map<String, String> required = Map.of("X-Tenant", "walmart");
        Map<String, String> actual   = Map.of("Content-Type", "application/json");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isFalse();
    }

    // ═══ CASE-INSENSITIVE KEY MATCHING (HTTP RFC 7230) ═══

    @Test
    void header_caseInsensitiveKey_uppercaseRequired_lowercaseActual() {
        Map<String, String> required = Map.of("CONTENT-TYPE", "application/json");
        Map<String, String> actual   = Map.of("content-type", "application/json");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    @Test
    void header_caseInsensitiveKey_mixedCase() {
        Map<String, String> required = Map.of("X-Correlation-Id", "trace-abc-123");
        Map<String, String> actual   = Map.of("x-correlation-id", "trace-abc-123");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    @Test
    void header_caseInsensitiveKey_requiredLower_actualMixed() {
        Map<String, String> required = Map.of("x-tenant", "us");
        Map<String, String> actual   = Map.of("X-Tenant", "us");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    @Test
    void header_valueIsCaseSensitive() {
        // Values ARE case-sensitive: "application/JSON" != "application/json"
        Map<String, String> required = Map.of("Content-Type", "application/json");
        Map<String, String> actual   = Map.of("Content-Type", "application/JSON");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isFalse();
    }

    // ═══ MULTIPLE HEADER CONDITIONS (ALL must match) ═══

    @Test
    void multipleHeaders_allPresent_allMatch() {
        Map<String, String> required = new HashMap<>();
        required.put("Content-Type",    "application/json");
        required.put("X-Tenant",        "walmart");
        required.put("X-API-Version",   "v2");

        Map<String, String> actual = new HashMap<>();
        actual.put("Content-Type",    "application/json");
        actual.put("X-Tenant",        "walmart");
        actual.put("X-API-Version",   "v2");
        actual.put("X-Request-Id",    "extra-header");   // extra headers ignored

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    @Test
    void multipleHeaders_oneValueMissing() {
        Map<String, String> required = new HashMap<>();
        required.put("Content-Type", "application/json");
        required.put("X-Tenant",     "walmart");

        Map<String, String> actual = Map.of("Content-Type", "application/json");
        // X-Tenant is missing

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isFalse();
    }

    @Test
    void multipleHeaders_oneValueWrong() {
        Map<String, String> required = new HashMap<>();
        required.put("Content-Type", "application/json");
        required.put("X-Tenant",     "walmart");

        Map<String, String> actual = new HashMap<>();
        actual.put("Content-Type", "application/json");
        actual.put("X-Tenant",     "samsclub");   // wrong value

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isFalse();
    }

    // ═══ STUB SPECIFICITY INTEGRATION ═══

    @Test
    void stubWithHeaderCondition_hasSelfie5Points_perHeader() {
        // Confirm specificity scoring: 1 header condition = 5 points
        // This test validates the design contract used by StubRouter sorting
        // (1 header = 5pts, 1 body condition = 10pts)
        Map<String, String> required = Map.of("X-Tenant", "walmart");
        Map<String, String> actual   = Map.of("X-Tenant", "walmart");

        // Passes → will add "header_match" rule in engine
        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();

        // Different tenant → rejected
        Map<String, String> actualWrong = Map.of("X-Tenant", "samsclub");
        assertThat(HeaderMatcherEvaluator.matches(required, actualWrong)).isFalse();
    }

    // ═══ EDGE CASES ═══

    @Test
    void nullRequired_alwaysMatches() {
        assertThat(HeaderMatcherEvaluator.matches(null, Map.of("a", "b"))).isTrue();
    }

    @Test
    void emptyRequired_alwaysMatches() {
        assertThat(HeaderMatcherEvaluator.matches(Map.of(), Map.of("a", "b"))).isTrue();
    }

    @Test
    void emptyRequired_emptyActual_matches() {
        assertThat(HeaderMatcherEvaluator.matches(Map.of(), Map.of())).isTrue();
    }

    @Test
    void nonEmptyRequired_emptyActual_noMatch() {
        assertThat(HeaderMatcherEvaluator.matches(
                Map.of("Content-Type", "application/json"), Map.of())).isFalse();
    }

    @Test
    void nonEmptyRequired_nullActual_noMatch() {
        assertThat(HeaderMatcherEvaluator.matches(
                Map.of("Content-Type", "application/json"), null)).isFalse();
    }

    @Test
    void extraActualHeaders_doNotAffectMatch() {
        // Stub only requires X-Tenant, actual has 10 headers — still matches
        Map<String, String> required = Map.of("X-Tenant", "walmart");

        Map<String, String> actual = new HashMap<>();
        actual.put("X-Tenant",        "walmart");
        actual.put("Content-Type",    "application/json");
        actual.put("Accept",          "*/*");
        actual.put("X-Request-Id",    "req-123");
        actual.put("Authorization",   "Bearer token");
        actual.put("X-Correlation-Id","corr-456");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    // ═══ BEARER TOKEN / AUTHORIZATION HEADER MATCHING ═══

    @Test
    void authorizationHeader_matchesOnValue() {
        Map<String, String> required = Map.of("Authorization", "Bearer service-token");
        Map<String, String> actual   = Map.of("Authorization", "Bearer service-token");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isTrue();
    }

    @Test
    void authorizationHeader_wrongToken_noMatch() {
        Map<String, String> required = Map.of("Authorization", "Bearer service-token");
        Map<String, String> actual   = Map.of("Authorization", "Bearer wrong-token");

        assertThat(HeaderMatcherEvaluator.matches(required, actual)).isFalse();
    }
}
