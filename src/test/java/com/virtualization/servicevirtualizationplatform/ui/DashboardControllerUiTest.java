package com.virtualization.servicevirtualizationplatform.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders every UI page end-to-end and asserts on the actual HTML. This class didn't exist
 * before this audit \u2014 which is exactly how /ui/stubs shipped a 500 (stub.priority() called a
 * method that had been renamed to specificity()) with zero tests catching it. These tests render
 * real Thymeleaf templates against a real Spring context, the same way a browser would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardControllerUiTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void dashboard_rendersWithoutError() {
        ResponseEntity<String> res = http.getForEntity("/dashboard", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("Dashboard", "Total Stubs");
    }

    @Test
    void stubsPage_rendersWithoutError_andShowsSeedStubs() {
        // Regression test for the stub.priority() \u2192 specificity() 500 bug: if the template
        // calls a method that doesn't exist on StubDefinition, Thymeleaf throws and this fails.
        ResponseEntity<String> res = http.getForEntity("/ui/stubs", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("stub_account_create_v1");
        assertThat(res.getBody()).contains("Specificity");
    }

    @Test
    void stubsPage_queryFilter_narrowsResults() {
        ResponseEntity<String> res = http.getForEntity(
                "/ui/stubs?query=stub_account_create_v1", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("stub_account_create_v1");
        assertThat(res.getBody()).doesNotContain("stub_payment_create_v1");
    }

    @Test
    void stubsPage_sortByIdAlphabetical_rendersInAscendingOrder() {
        ResponseEntity<String> res = http.getForEntity(
                "/ui/stubs?query=stub_account&sortBy=id", String.class);
        String body = res.getBody();
        int createIdx = body.indexOf("stub_account_create_v1");
        int getIdx = body.indexOf("stub_account_get_v1");
        assertThat(createIdx).isGreaterThan(-1);
        assertThat(getIdx).isGreaterThan(-1);
        assertThat(createIdx).as("alphabetical: create < get").isLessThan(getIdx);
    }

    @Test
    void stubsPage_showsDeleteAndToggleButtons() {
        // Regression coverage for per-row action buttons. Expiry modal was removed in
        // an earlier UI cleanup; toggle and delete remain.
        ResponseEntity<String> res = http.getForEntity("/ui/stubs", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("deleteStubRow(");
        assertThat(res.getBody()).contains("toggleStubRow(");
    }

    @Test
    void sessionsPage_rendersWithoutError() {
        ResponseEntity<String> res = http.getForEntity("/ui/sessions", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("Session Inspector");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionsPage_queryFilter_showsOnlyMatchingSession() {
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "ui-search-match-session"), Map.class);
        http.postForEntity("/api/v1/sessions",
                Map.of("session_id", "ui-search-nomatch-session-xyz"), Map.class);

        ResponseEntity<String> res = http.getForEntity(
                "/ui/sessions?query=ui-search-match", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("ui-search-match-session");
        assertThat(res.getBody()).doesNotContain("ui-search-nomatch-session-xyz");
    }

    @Test
    void sessionsPage_noMatches_showsEmptyStateNotError() {
        ResponseEntity<String> res = http.getForEntity(
                "/ui/sessions?query=definitely-no-such-session-exists-xyz", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("No sessions matching");
    }

    @Test
    void trafficPage_rendersWithoutError() {
        ResponseEntity<String> res = http.getForEntity("/ui/traffic", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void driftPage_rendersWithoutError() {
        ResponseEntity<String> res = http.getForEntity("/ui/drift", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordingPage_rendersWithoutError_andShowsHistoryNewestFirst() {
        // Regression test for the TrafficRecord @JsonAutoDetect / 500 bug would have been caught
        // on the API side; this confirms the Thymeleaf-rendered page also survives, and that the
        // page shows the most-recently-ingested record first (matches Traffic page's convention).
        http.postForEntity("/api/v1/recording/ingest", Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST",
                        "path", "/api/v1/ui-history-order-first", "headers", Map.of(), "body", Map.of()),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of())
        ), Map.class);
        http.postForEntity("/api/v1/recording/ingest", Map.of(
                "request", Map.of("protocol", "HTTP", "method", "POST",
                        "path", "/api/v1/ui-history-order-second", "headers", Map.of(), "body", Map.of()),
                "response", Map.of("statusCode", 200, "headers", Map.of(), "body", Map.of())
        ), Map.class);

        ResponseEntity<String> res = http.getForEntity("/ui/recording", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = res.getBody();
        int firstIdx = body.indexOf("/api/v1/ui-history-order-first");
        int secondIdx = body.indexOf("/api/v1/ui-history-order-second");
        assertThat(firstIdx).isGreaterThan(-1);
        assertThat(secondIdx).isGreaterThan(-1);
        assertThat(secondIdx).as("the later ingest should render first (newest-first)")
                .isLessThan(firstIdx);
    }
}
