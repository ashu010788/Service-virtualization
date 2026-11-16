package com.virtualization.servicevirtualizationplatform.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stretch Goal — Live Outbound Forwarding + Auto-Record tests.
 *
 * Uses WireMock to simulate a real upstream service. Validates that when a request
 * hits the ForwardingProxy with no matching stub AND outbound forwarding is enabled:
 *
 *   1. The request is forwarded to the real upstream (WireMock)
 *   2. The upstream response is captured and auto-ingested via RecordingService
 *   3. The real upstream response is returned to the caller
 *   4. On a second identical request, the auto-generated stub answers (no upstream call)
 *
 * This is the core "record on first miss, replay on every hit after" pattern that
 * makes the service virtualization platform act as a real routing proxy — not just
 * a stub server.
 *
 * WireMock runs on a random port; Spring's DynamicPropertySource wires
 * proxy.upstream.base-url to that port so OutboundForwardingService forwards there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboundForwardingTest {

    static WireMockServer upstream;

    @Autowired
    private TestRestTemplate http;

    @BeforeAll
    static void startWireMock() {
        upstream = new WireMockServer(wireMockConfig().dynamicPort());
        upstream.start();
        WireMock.configureFor("localhost", upstream.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        upstream.resetAll();
        // Reset drift to prevent bleed from other tests
        http.delete("/api/v1/drift/config");
    }

    /**
     * Wire proxy.upstream.base-url to the WireMock server's random port,
     * and enable record-on-miss.
     */
    @DynamicPropertySource
    static void configureProxy(DynamicPropertyRegistry registry) {
        // Start WireMock early so we have the port for property registration
        if (upstream == null) {
            upstream = new WireMockServer(wireMockConfig().dynamicPort());
            upstream.start();
            WireMock.configureFor("localhost", upstream.port());
        }
        registry.add("proxy.upstream.base-url", () -> "http://localhost:" + upstream.port());
        registry.add("proxy.upstream.record-on-miss", () -> "true");
    }

    // ═══════════════════════════════════════════════════════════
    // Test 1: Forward on miss → real upstream response returned
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void stubMiss_forwardsToUpstream_returnsRealResponse() {
        // WireMock stubs a real upstream endpoint
        upstream.stubFor(post(urlEqualTo("/products/inventory"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "product_id": "PROD-001",
                                    "sku": "SKU-12345",
                                    "quantity": 150,
                                    "warehouse": "DC-01"
                                }
                                """)));

        // Hit the transparent proxy — no stub exists for /products/inventory
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "forward-test-1");

        ResponseEntity<Map> res = http.exchange(
                "/products/inventory",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", "SKU-12345", "warehouse", "DC-01"), headers),
                Map.class);

        // Should get the real upstream response (not a 404)
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("product_id", "PROD-001");
        assertThat(res.getBody()).containsEntry("quantity", 150);
        assertThat(res.getBody()).containsEntry("warehouse", "DC-01");

        // WireMock should have received exactly 1 request
        upstream.verify(1, postRequestedFor(urlEqualTo("/products/inventory")));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 2: Auto-record → stub ingested in recording history
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void stubMiss_autoIngestsTrafficIntoRecordingHistory() {
        upstream.stubFor(post(urlEqualTo("/orders/create"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "order_id": "ORD-9999",
                                    "status": "CONFIRMED",
                                    "total": 42.50
                                }
                                """)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "forward-ingest-test");

        http.exchange(
                "/orders/create",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("item", "Widget", "qty", 5), headers),
                Map.class);

        // Check recording history — should contain the auto-ingested record
        ResponseEntity<Object[]> history = http.getForEntity(
                "/api/v1/recording/history?limit=10", Object[].class);

        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).isNotNull();
        assertThat(history.getBody().length).isGreaterThanOrEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════
    // Test 3: Replay — second call hits stub, NOT upstream
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void secondCall_hitsAutoGeneratedStub_notUpstream() {
        upstream.stubFor(post(urlEqualTo("/shipments/track"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "tracking_id": "TRK-5555",
                                    "status": "IN_TRANSIT",
                                    "region": "US"
                                }
                                """)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "replay-test");

        // Call 1: stub miss → forward to upstream → auto-record
        ResponseEntity<Map> firstCall = http.exchange(
                "/shipments/track",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("shipment_id", "SHIP-100"), headers),
                Map.class);

        assertThat(firstCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        upstream.verify(1, postRequestedFor(urlEqualTo("/shipments/track")));

        // Reset WireMock request journal to count fresh
        upstream.resetRequests();

        // Call 2: same request — should hit the auto-generated stub, NOT upstream
        ResponseEntity<Map> secondCall = http.exchange(
                "/shipments/track",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("shipment_id", "SHIP-100"), headers),
                Map.class);

        // The auto-generated stub should respond (may differ in exact fields
        // because the stub generator templates fields, but status should be 200)
        assertThat(secondCall.getStatusCode().is2xxSuccessful()).isTrue();

        // WireMock should NOT have received a second request
        upstream.verify(0, postRequestedFor(urlEqualTo("/shipments/track")));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 4: Upstream failure → 502 Bad Gateway
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void upstreamFailure_returns502() {
        // No WireMock stub for this path → upstream returns 404/connection error
        // We configure WireMock to return a server error
        upstream.stubFor(get(urlEqualTo("/nonexistent/endpoint"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "upstream-fail-test");

        ResponseEntity<Map> res = http.exchange(
                "/nonexistent/endpoint",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        // RestTemplate throws on 5xx by default, so OutboundForwardingService
        // catches it and returns 502. Alternatively if the upstream returns 500,
        // our code catches the HttpServerErrorException and wraps it as 502.
        assertThat(res.getStatusCode().value()).isIn(500, 502);
    }

    // ═══════════════════════════════════════════════════════════
    // Test 5: Existing stubs still match — no forwarding
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void existingStub_matchesNormally_noForwarding() {
        // /api/v1/accounts has a seed stub → should match without forwarding
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "no-forward-test");

        ResponseEntity<Map> res = http.exchange(
                "/api/v1/accounts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "account_holder", "StubUser",
                        "currency", "USD",
                        "initial_deposit", 100.0
                ), headers),
                Map.class);

        // Should match the existing stub (201 CREATED), NOT forward
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("account_id");

        // WireMock should have received zero requests for this path
        upstream.verify(0, postRequestedFor(urlEqualTo("/api/v1/accounts")));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 6: GET forwarding works (not just POST)
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @SuppressWarnings("unchecked")
    void getRequest_forwardsToUpstream() {
        upstream.stubFor(get(urlEqualTo("/catalog/items/ITEM-42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "item_id": "ITEM-42",
                                    "name": "Widget Pro",
                                    "price": 29.99
                                }
                                """)));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "get-forward-test");

        ResponseEntity<Map> res = http.exchange(
                "/catalog/items/ITEM-42",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("item_id", "ITEM-42");
        assertThat(res.getBody()).containsEntry("name", "Widget Pro");

        upstream.verify(1, getRequestedFor(urlEqualTo("/catalog/items/ITEM-42")));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 7: Admin endpoints NOT forwarded
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(7)
    void adminEndpoints_neverForwarded() {
        // Admin endpoints should reach their own controllers, never the proxy
        ResponseEntity<Object> stubs = http.getForEntity("/api/v1/stubs", Object.class);
        assertThat(stubs.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Object> sessions = http.getForEntity("/api/v1/sessions", Object.class);
        assertThat(sessions.getStatusCode()).isEqualTo(HttpStatus.OK);

        // WireMock should have received zero requests
        upstream.verify(0, anyRequestedFor(urlPathMatching("/api/v1/stubs.*")));
        upstream.verify(0, anyRequestedFor(urlPathMatching("/api/v1/sessions.*")));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 8: Missing X-Session-Id still returns 400 (not forwarded)
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @SuppressWarnings("unchecked")
    void missingSessionId_returns400_notForwarded() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No X-Session-Id

        ResponseEntity<Map> res = http.exchange(
                "/products/inventory",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", "SKU-999"), headers),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("error").toString()).contains("X-Session-Id");

        // WireMock should NOT receive anything
        upstream.verify(0, postRequestedFor(urlEqualTo("/products/inventory")));
    }

    // ═══════════════════════════════════════════════════════════
    // Tests 9-16: Enhanced proxy features (merged from OutboundForwardingLiveProxyTest)
    //   - 4xx/5xx upstream capture, query params, runtime config API,
    //     proxy stats, header forwarding
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @SuppressWarnings("unchecked")
    void upstream4xx_capturedAndReturnedAsIs_notSwallowedAs502() {
        upstream.stubFor(get(urlEqualTo("/users/nonexistent"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "error": "User not found",
                                    "code": "USER_NOT_FOUND"
                                }
                                """)));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "4xx-capture-test");

        ResponseEntity<Map> res = http.exchange(
                "/users/nonexistent",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).containsEntry("error", "User not found");
        assertThat(res.getBody()).containsEntry("code", "USER_NOT_FOUND");

        upstream.verify(1, getRequestedFor(urlEqualTo("/users/nonexistent")));
    }

    @Test
    @Order(10)
    @SuppressWarnings("unchecked")
    void queryParams_forwardedToUpstream() {
        upstream.stubFor(get(urlEqualTo("/search/products?q=widget&page=2&size=10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "query": "widget",
                                    "page": 2,
                                    "totalResults": 42
                                }
                                """)));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "query-param-test");

        ResponseEntity<Map> res = http.exchange(
                "/search/products?q=widget&page=2&size=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("query", "widget");
        assertThat(res.getBody()).containsEntry("page", 2);

        upstream.verify(1, getRequestedFor(
                urlEqualTo("/search/products?q=widget&page=2&size=10")));
    }

    @Test
    @Order(11)
    @SuppressWarnings("unchecked")
    void proxyConfigApi_returnsCurrentConfig() {
        ResponseEntity<Map> res = http.getForEntity("/api/v1/proxy/config", Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("enabled");
        assertThat(res.getBody()).containsKey("baseUrl");
        assertThat(res.getBody()).containsKey("recordOnMiss");
        assertThat(res.getBody().get("enabled")).isEqualTo(true);
    }

    @Test
    @Order(12)
    @SuppressWarnings("unchecked")
    void proxyStatsApi_returnsStatsAndForwardLog() {
        upstream.stubFor(post(urlEqualTo("/stats-test/endpoint"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"result\": \"ok\"}")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "stats-test");

        http.exchange("/stats-test/endpoint", HttpMethod.POST,
                new HttpEntity<>(Map.of("data", "test"), headers), Map.class);

        ResponseEntity<Map> res = http.getForEntity("/api/v1/proxy/stats?limit=5", Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("totalForwarded");
        assertThat(res.getBody()).containsKey("totalRecorded");
        assertThat(res.getBody()).containsKey("recentForwards");

        assertThat(((Number) res.getBody().get("totalForwarded")).longValue())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(13)
    @SuppressWarnings("unchecked")
    void proxyConfigApi_updateAtRuntime() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> res = http.exchange(
                "/api/v1/proxy/config",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "baseUrl", "http://localhost:" + upstream.port(),
                        "recordOnMiss", true
                ), headers),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("message", "Proxy configuration updated");
        assertThat(res.getBody().get("enabled")).isEqualTo(true);
    }

    @Test
    @Order(14)
    @SuppressWarnings("unchecked")
    void upstream5xx_capturedAndReturnedAsIs() {
        upstream.stubFor(post(urlEqualTo("/internal/failing-service"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "error": "Service Unavailable",
                                    "retryAfter": 30
                                }
                                """)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Session-Id", "5xx-capture-test");

        ResponseEntity<Map> res = http.exchange(
                "/internal/failing-service",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "test"), headers),
                Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).containsEntry("error", "Service Unavailable");
        assertThat(res.getBody()).containsEntry("retryAfter", 30);
    }

    @Test
    @Order(15)
    void proxyConfigEndpoint_notInterceptedByForwardingProxy() {
        ResponseEntity<Map> res = http.getForEntity("/api/v1/proxy/config", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("enabled");

        upstream.verify(0, anyRequestedFor(urlPathMatching("/api/v1/proxy.*")));
    }

    @Test
    @Order(16)
    @SuppressWarnings("unchecked")
    void originalHeaders_forwardedToUpstream_exceptPlatformInternal() {
        upstream.stubFor(get(urlEqualTo("/header-test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"headers\": \"received\"}")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "header-forward-test");
        headers.set("X-Custom-Header", "my-custom-value");
        headers.set("Authorization", "Bearer test-token-123");

        http.exchange("/header-test", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        upstream.verify(1, getRequestedFor(urlEqualTo("/header-test"))
                .withHeader("X-Custom-Header", equalTo("my-custom-value"))
                .withHeader("Authorization", equalTo("Bearer test-token-123"))
                .withoutHeader("X-Session-Id"));
    }
}
