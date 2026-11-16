package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StubMatchingEngineTest {

    @Autowired
    private StubMatchingEngine matchingEngine;

    @Autowired
    private StubRouter stubRouter;

    // ═══ BASIC MATCHING ═══

    @Test
    void staticHttpHeaderAndBodyMatch() {
        VirtualRequest request = new VirtualRequest(
                "session-001", "HTTP", "POST", "/api/v1/accounts",
                Map.of("Content-Type", "application/json"),
                Map.of("account_holder", "Jane Doe", "initial_deposit", 500.00, "currency", "USD")
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.executionLatencyMs()).isLessThan(10.0);
        assertThat(response.matchedStubId()).isEqualTo("stub_account_create_v1");
        assertThat(response.matchedRules()).contains("path_pattern", "jsonpath_body_check");
        assertThat(response.stateMutated()).isTrue();
    }

    @Test
    void postAccountWithEurCurrency_matchesEurStub() {
        VirtualRequest request = new VirtualRequest(
                "session-eur", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Hans", "initial_deposit", 100.0, "currency", "EUR")
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.matchedStubId()).isEqualTo("stub_account_create_eur_v1");
        assertThat(response.body().get("currency")).isEqualTo("EUR");
    }

    @Test
    void deleteAccount_returns204() {
        VirtualRequest request = new VirtualRequest(
                "session-del", "HTTP", "DELETE", "/api/v1/accounts/ACC-999",
                Map.of(), null
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.matchedStubId()).isEqualTo("stub_account_delete_v1");
        assertThat(response.stateMutated()).isTrue();
    }

    @Test
    void putAccountUpdate_returns200() {
        VirtualRequest request = new VirtualRequest(
                "session-upd", "HTTP", "PUT", "/api/v1/accounts/ACC-001",
                Map.of(), Map.of("account_holder", "Updated Name", "balance", 2000.0)
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.matchedStubId()).isEqualTo("stub_account_update_v1");
    }

    // ═══ PATH VARIABLE EXTRACTION ═══

    @Test
    void getAccountById_extractsPathVariableAndReturnsState() {
        // First create an account
        matchingEngine.matchAndExecute(new VirtualRequest(
                "session-pv", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "PathVarUser", "initial_deposit", 750.0, "currency", "USD")
        ));

        // The created account has a generated ID — we need to find it
        // For this test, we'll create a known state manually via another POST
        VirtualResponse createRes = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-pv2", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Bob", "initial_deposit", 500.0, "currency", "USD")
        ));

        String accountId = (String) createRes.body().get("account_id");
        assertThat(accountId).isNotNull();

        // Now GET with the generated ID
        VirtualResponse getRes = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-pv2", "HTTP", "GET", "/api/v1/accounts/" + accountId,
                Map.of(), null
        ));

        assertThat(getRes.statusCode()).isEqualTo(200);
        assertThat(getRes.body().get("account_holder")).isEqualTo("Bob");
    }

    // ═══ JSONPATH BODY MATCHING ═══

    @Test
    void jsonPathCurrencyMismatch_fallsToGenericStub() {
        VirtualRequest request = new VirtualRequest(
                "session-jpm", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Test", "initial_deposit", 100.0, "currency", "JPY")
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        // No USD/EUR stub matches JPY — falls to generic fallback stub
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.matchedStubId()).isEqualTo("stub_account_create_fallback_v1");
    }

    @Test
    void nestedJsonPath_addressCountryMatch() {
        VirtualRequest request = new VirtualRequest(
                "session-nested", "HTTP", "POST", "/api/v1/customers",
                Map.of(),
                Map.of("name", "Eve", "address", Map.of("street", "123 Main", "city", "NYC", "country", "US"))
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.matchedStubId()).isEqualTo("stub_customer_create_us_v1");
    }

    @Test
    void multipleJsonPathConditions_savingsAccountHigherPriority() {
        VirtualRequest request = new VirtualRequest(
                "session-multi", "HTTP", "POST", "/api/v1/accounts",
                Map.of(),
                Map.of("account_holder", "Frank", "currency", "USD", "type", "SAVINGS", "initial_deposit", 5000.0)
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        // Savings stub has priority 15, regular USD has priority 10
        assertThat(response.matchedStubId()).isEqualTo("stub_savings_account_create_v1");
        assertThat(response.body().get("type")).isEqualTo("SAVINGS");
    }

    // ═══ STATEFUL BEHAVIOR ═══

    @Test
    void dynamicStateIsIsolatedToSession() {
        // Step 1: Create entity in Session A
        VirtualRequest createReqSessionA = new VirtualRequest(
                "session-A", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Alice", "initial_deposit", 1000.0, "currency", "USD")
        );
        VirtualResponse createRes = matchingEngine.matchAndExecute(createReqSessionA);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String accId = (String) createRes.body().get("account_id");

        // Step 2: Query entity in Session A -> Exists
        VirtualRequest getReqSessionA = new VirtualRequest(
                "session-A", "HTTP", "GET", "/api/v1/accounts/" + accId, Map.of(), null
        );
        VirtualResponse resA = matchingEngine.matchAndExecute(getReqSessionA);
        assertThat(resA.statusCode()).isEqualTo(200);
        assertThat(resA.body().get("account_holder")).isEqualTo("Alice");

        // Step 3: Query entity in Session B -> Isolated (404 Not Found)
        VirtualRequest getReqSessionB = new VirtualRequest(
                "session-B", "HTTP", "GET", "/api/v1/accounts/" + accId, Map.of(), null
        );
        VirtualResponse resB = matchingEngine.matchAndExecute(getReqSessionB);
        assertThat(resB.statusCode()).isEqualTo(404);
    }

    @Test
    void postThenDeleteThenGet_returns404() {
        // Create
        VirtualResponse createRes = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-del2", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "ToDelete", "initial_deposit", 100.0, "currency", "USD")
        ));
        String accId = (String) createRes.body().get("account_id");

        // Delete
        matchingEngine.matchAndExecute(new VirtualRequest(
                "session-del2", "HTTP", "DELETE", "/api/v1/accounts/" + accId,
                Map.of(), null
        ));

        // Get should return 404
        VirtualResponse getRes = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-del2", "HTTP", "GET", "/api/v1/accounts/" + accId,
                Map.of(), null
        ));
        assertThat(getRes.statusCode()).isEqualTo(404);
    }

    // ═══ MULTI-PROTOCOL ═══

    @Test
    void grpcCreateAccount_routesToGrpcStub() {
        VirtualRequest request = new VirtualRequest(
                "grpc-session", "GRPC", "CreateAccount", "",
                Map.of(), Map.of("account_holder", "Luna", "currency", "USD")
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.matchedStubId()).isEqualTo("stub_grpc_create_account");
        assertThat(response.body().get("account_holder")).isEqualTo("Luna");
    }

    @Test
    void kafkaPaymentEvent_routesToKafkaStub() {
        VirtualRequest request = new VirtualRequest(
                "kafka-session", "KAFKA", "payment-events", "",
                Map.of(), Map.of("event_type", "PAYMENT_COMPLETED", "amount", 500.0)
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.matchedStubId()).isEqualTo("stub_kafka_payment_completed");
    }

    // ═══ FALLBACK ═══

    @Test
    void unknownPath_returns404() {
        VirtualRequest request = new VirtualRequest(
                "session-404", "HTTP", "GET", "/api/v1/nonexistent/resource",
                Map.of(), null
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.matchedStubId()).isNull();
    }

    // ═══ TEMPLATE RESOLUTION ═══

    @Test
    void generatedIdFollowsFormat_responseEchoesRequestFields() {
        VirtualRequest request = new VirtualRequest(
                "session-tmpl", "HTTP", "POST", "/api/v1/accounts",
                Map.of(), Map.of("account_holder", "Quincy", "currency", "USD", "initial_deposit", 42.0)
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) response.body().get("account_id")).matches("ACC-\\d{4}");
        assertThat(response.body().get("account_holder")).isEqualTo("Quincy");
        assertThat(response.body().get("status")).isEqualTo("ACTIVE");
    }

    // ═══ PAYMENT WITH ASYNC EVENT ═══

    @Test
    void paymentCreate_returnsResponseAndTriggersAsyncEvent() throws InterruptedException {
        VirtualRequest request = new VirtualRequest(
                "session-async", "HTTP", "POST", "/api/v1/payments",
                Map.of(), Map.of("amount", 99.99, "currency", "USD")
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.matchedStubId()).isEqualTo("stub_payment_create_v1");
        assertThat(response.body().get("status")).isEqualTo("COMPLETED");

        // Async event should arrive after ~500ms
        Thread.sleep(800);

        // The payment should be in state
        String payId = (String) response.body().get("payment_id");
        VirtualResponse getRes = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-async", "HTTP", "GET", "/api/v1/payments/" + payId,
                Map.of(), null
        ));
        assertThat(getRes.statusCode()).isEqualTo(200);
    }

    // ═══ BODY MISMATCH — URL matches but body fails → 404 ═══

    @Test
    void urlMatchesButBodyMismatch_returns404() {
        // POST /api/v1/payments requires $.currency = "USD"
        // Send currency = "BTC" — no fallback for payments path
        VirtualRequest request = new VirtualRequest(
                "session-body-miss", "HTTP", "POST", "/api/v1/payments",
                Map.of(), Map.of("amount", 100.0, "currency", "BTC")
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        // URL /api/v1/payments matches the trie, but body condition $.currency=USD fails
        // No fallback stub for payments → 404
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.matchedStubId()).isNull();
        assertThat(response.body()).containsKey("error");
        assertThat(response.body().get("path")).isEqualTo("/api/v1/payments");
        assertThat(response.body().get("method")).isEqualTo("POST");
    }

    @Test
    void urlMatchesButMissingRequiredBodyField_returns404() {
        // POST /api/v1/payments requires $.currency but body has no currency at all
        VirtualRequest request = new VirtualRequest(
                "session-no-currency", "HTTP", "POST", "/api/v1/payments",
                Map.of(), Map.of("amount", 50.0)
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.matchedStubId()).isNull();
    }

    @Test
    void urlMatchesButNullBody_returns404() {
        // POST /api/v1/payments requires body conditions, but body is null
        VirtualRequest request = new VirtualRequest(
                "session-null-body", "HTTP", "POST", "/api/v1/payments",
                Map.of(), null
        );

        VirtualResponse response = matchingEngine.matchAndExecute(request);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.matchedStubId()).isNull();
    }

    // ═══ 500 INTERNAL SERVER ERROR ═══

    @Test
    void stub_with500StatusCode_returnsServerError() {
        // Register a stub that returns 500
        StubDefinition errorStub = new StubDefinition(
                "stub_error_500", 20,
                new RequestMatcher("HTTP", "GET", "/api/v1/health/failing", Map.of(), Map.of()),
                new ResponseTemplate(500, Map.of("Content-Type", "application/json"),
                        Map.of("error", "Internal Server Error", "code", "SERVICE_UNAVAILABLE")),
                null
        );
        stubRouter.register(errorStub);

        VirtualResponse response = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-500", "HTTP", "GET", "/api/v1/health/failing",
                Map.of(), null
        ));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.matchedStubId()).isEqualTo("stub_error_500");
        assertThat(response.body().get("error")).isEqualTo("Internal Server Error");
        assertThat(response.body().get("code")).isEqualTo("SERVICE_UNAVAILABLE");

        // Cleanup
        stubRouter.remove("stub_error_500");
    }

    @Test
    void stub_with503_returnsServiceUnavailable() {
        StubDefinition stub503 = new StubDefinition(
                "stub_error_503", 20,
                new RequestMatcher("HTTP", "POST", "/api/v1/payments/process", Map.of(), Map.of()),
                new ResponseTemplate(503, Map.of("Content-Type", "application/json"),
                        Map.of("error", "Service Unavailable", "retry_after_seconds", 30)),
                null
        );
        stubRouter.register(stub503);

        VirtualResponse response = matchingEngine.matchAndExecute(new VirtualRequest(
                "session-503", "HTTP", "POST", "/api/v1/payments/process",
                Map.of(), Map.of("amount", 100.0)
        ));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body().get("error")).isEqualTo("Service Unavailable");
        assertThat(((Number) response.body().get("retry_after_seconds")).intValue()).isEqualTo(30);

        stubRouter.remove("stub_error_503");
    }
}
