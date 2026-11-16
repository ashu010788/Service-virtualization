package com.virtualization.servicevirtualizationplatform.recording;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test mirroring the exact scenario from the task specification.
 * Validates EVERY field in the API response — request summary, response summary,
 * and the generated stub — ensuring zero sensitive data leaks anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskSpecIngestionTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void fullPipeline_everyFieldChecked() {
        Map<String, Object> rawTraffic = Map.of(
                "request", Map.of(
                        "method", "POST",
                        "path", "/api/v1/payments",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "merchant_id", "MERCH_1092",
                                "credit_card", "4111111111111111",
                                "cvv", "123",
                                "customer_email", "user_john@example.com",
                                "amount", 150.00,
                                "currency", "USD",
                                "custom_auth_token", "sk_live_a8f29c3d7e1b4x6z9q0w"
                        )
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "transaction_id", "TXN-881920",
                                "card_last_four", "1111",
                                "card_type", "VISA",
                                "customer_email", "user_john@example.com",
                                "amount", 150.00,
                                "status", "SUCCESS"
                        )
                )
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", rawTraffic, Map.class);

        // ═══ TOP-LEVEL ═══
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> result = res.getBody();
        assertThat(result).containsKey("recordId");
        assertThat(result).containsKey("generatedStub");
        assertThat(result).containsKey("requestSummary");
        assertThat(result).containsKey("responseSummary");
        assertThat(result).containsKey("confidence");
        assertThat(result).containsKey("status");

        double confidence = ((Number) result.get("confidence")).doubleValue();
        assertThat(confidence).isGreaterThanOrEqualTo(0.70);
        assertThat((String) result.get("status")).isIn("AUTO_PUBLISHED", "PENDING_REVIEW");

        // ════════════════════════════════════════
        // REQUEST SUMMARY — every field
        // ════════════════════════════════════════
        Map<String, Object> reqSummary = (Map<String, Object>) result.get("requestSummary");
        assertThat(((Number) reqSummary.get("totalFieldsProcessed")).intValue()).isEqualTo(7);
        assertThat(((Number) reqSummary.get("pciFieldsMasked")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(((Number) reqSummary.get("piiFieldsMasked")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) reqSummary.get("unknownFields")).intValue()).isEqualTo(0);

        List<Map<String, Object>> reqDetails = (List<Map<String, Object>>) reqSummary.get("fieldDetails");
        assertThat(reqDetails).hasSize(7);

        // No "action" field in response (removed from serialization)
        assertThat(reqDetails.get(0)).doesNotContainKey("action");

        // credit_card → ****1111
        Map<String, Object> creditCard = findField(reqDetails, "credit_card");
        assertThat(creditCard.get("classification")).isEqualTo("PCI_CARD");
        assertThat(creditCard.get("originalValue")).isEqualTo("****1111");

        // cvv → ****
        Map<String, Object> cvv = findField(reqDetails, "cvv");
        assertThat(cvv.get("classification")).isEqualTo("PCI_CVV");
        assertThat(cvv.get("originalValue")).isEqualTo("****");

        // customer_email → ****@masked.com
        Map<String, Object> reqEmail = findField(reqDetails, "customer_email");
        assertThat(reqEmail.get("classification")).isEqualTo("PII_EMAIL");
        assertThat(reqEmail.get("originalValue")).isEqualTo("****@masked.com");

        // custom_auth_token → ****
        Map<String, Object> authToken = findField(reqDetails, "custom_auth_token");
        assertThat(authToken.get("originalValue")).isEqualTo("****");

        // merchant_id → kept as-is
        Map<String, Object> merchantId = findField(reqDetails, "merchant_id");
        assertThat(merchantId.get("classification")).isEqualTo("SAFE_ENUM");
        assertThat(merchantId.get("originalValue")).isEqualTo("MERCH_1092");

        // amount → kept as-is
        Map<String, Object> reqAmount = findField(reqDetails, "amount");
        assertThat(((Number) reqAmount.get("originalValue")).doubleValue()).isEqualTo(150.0);

        // currency → kept as-is
        Map<String, Object> currency = findField(reqDetails, "currency");
        assertThat(currency.get("originalValue")).isEqualTo("USD");

        // ════════════════════════════════════════
        // RESPONSE SUMMARY — every field
        // ════════════════════════════════════════
        Map<String, Object> resSummary = (Map<String, Object>) result.get("responseSummary");
        assertThat(((Number) resSummary.get("totalFieldsProcessed")).intValue()).isEqualTo(6);
        assertThat(((Number) resSummary.get("unknownFields")).intValue()).isEqualTo(0);

        List<Map<String, Object>> resDetails = (List<Map<String, Object>>) resSummary.get("fieldDetails");
        assertThat(resDetails).hasSize(6);

        // transaction_id → kept
        assertThat(findField(resDetails, "transaction_id").get("originalValue")).isEqualTo("TXN-881920");

        // card_last_four → ****1111
        assertThat(findField(resDetails, "card_last_four").get("originalValue")).isEqualTo("****1111");

        // card_type → kept
        assertThat(findField(resDetails, "card_type").get("originalValue")).isEqualTo("VISA");

        // customer_email → ****@masked.com
        assertThat(findField(resDetails, "customer_email").get("originalValue")).isEqualTo("****@masked.com");

        // amount → kept
        assertThat(((Number) findField(resDetails, "amount").get("originalValue")).doubleValue()).isEqualTo(150.0);

        // status → kept
        assertThat(findField(resDetails, "status").get("originalValue")).isEqualTo("SUCCESS");

        // ════════════════════════════════════════
        // ENTIRE RESPONSE — zero raw sensitive data
        // ════════════════════════════════════════
        String fullResponse = result.toString();
        assertThat(fullResponse).doesNotContain("4111111111111111");
        assertThat(fullResponse).doesNotContain("user_john@example.com");
        assertThat(fullResponse).doesNotContain("sk_live");
        assertThat(fullResponse).doesNotContain("\"123\"");

        // ════════════════════════════════════════
        // GENERATED STUB — every field sanitized
        // ════════════════════════════════════════
        Map<String, Object> stub = (Map<String, Object>) result.get("generatedStub");

        // requestMatcher
        Map<String, Object> requestMatcher = (Map<String, Object>) stub.get("requestMatcher");
        assertThat(requestMatcher.get("protocol")).isEqualTo("HTTP");
        assertThat(requestMatcher.get("method")).isEqualTo("POST");
        assertThat(requestMatcher.get("pathPattern")).isEqualTo("/api/v1/payments");

        Map<String, String> bodyJsonPath = (Map<String, String>) requestMatcher.get("bodyJsonPath");
        assertThat(bodyJsonPath).doesNotContainKey("$.credit_card");
        assertThat(bodyJsonPath).doesNotContainKey("$.cvv");
        assertThat(bodyJsonPath).doesNotContainKey("$.customer_email");
        assertThat(bodyJsonPath).doesNotContainKey("$.custom_auth_token");
        assertThat(bodyJsonPath).containsKey("$.currency");

        // responseTemplate body
        Map<String, Object> responseTemplate = (Map<String, Object>) stub.get("responseTemplate");
        assertThat(((Number) responseTemplate.get("statusCode")).intValue()).isEqualTo(200);

        Map<String, Object> stubBody = (Map<String, Object>) responseTemplate.get("body");
        assertThat(stubBody.get("transaction_id")).isEqualTo("{{generate.id:TXN}}");
        assertThat(String.valueOf(stubBody.get("card_last_four"))).startsWith("****");
        assertThat(stubBody.get("card_type")).isEqualTo("VISA");
        assertThat(stubBody.get("customer_email")).isEqualTo("****@masked.com");
        assertThat(((Number) stubBody.get("amount")).doubleValue()).isEqualTo(150.0);
        assertThat(stubBody.get("status")).isEqualTo("SUCCESS");

        // No raw data in stub
        String stubJson = stub.toString();
        assertThat(stubJson).doesNotContain("4111111111111111");
        assertThat(stubJson).doesNotContain("user_john");
        assertThat(stubJson).doesNotContain("sk_live");

        assertThat(stub.get("source")).isEqualTo("auto-generated");
        assertThat(stub.get("enabled")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void highEntropyUnknown_flaggedAsHighRisk() {
        Map<String, Object> rawTraffic = Map.of(
                "request", Map.of(
                        "method", "POST",
                        "path", "/api/v1/auth",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "username", "admin",
                                "mystery_field", "a8f29c3d7e1b4x6z9q0w5m8n2p4r6t1v"
                        )
                ),
                "response", Map.of(
                        "statusCode", 200,
                        "headers", Map.of(),
                        "body", Map.of("session", "OK")
                )
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", rawTraffic, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> reqSummary = (Map<String, Object>) res.getBody().get("requestSummary");
        assertThat(((Number) reqSummary.get("highRiskUnknowns")).intValue())
                .isGreaterThanOrEqualTo(1);

        // No raw data in stub
        Map<String, Object> stub = (Map<String, Object>) res.getBody().get("generatedStub");
        assertThat(stub.toString()).doesNotContain("a8f29c3d7e1b4x6z9q0w5m8n2p4r6t1v");
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchIngest_processesMultipleRecords() {
        List<Map<String, Object>> batch = List.of(
                Map.of(
                        "request", Map.of(
                                "method", "POST", "path", "/api/v1/payments",
                                "headers", Map.of("Content-Type", "application/json"),
                                "body", Map.of("credit_card", "5500000000000004", "amount", 200.0, "currency", "USD")
                        ),
                        "response", Map.of(
                                "statusCode", 200, "headers", Map.of(),
                                "body", Map.of("payment_id", "PAY-1001", "status", "SUCCESS")
                        )
                ),
                Map.of(
                        "request", Map.of(
                                "method", "POST", "path", "/api/v1/refunds",
                                "headers", Map.of("Content-Type", "application/json"),
                                "body", Map.of("payment_id", "PAY-1001", "reason", "customer_request")
                        ),
                        "response", Map.of(
                                "statusCode", 200, "headers", Map.of(),
                                "body", Map.of("refund_id", "REF-5001", "status", "PROCESSED")
                        )
                )
        );

        ResponseEntity<List> res = http.postForEntity("/api/v1/recording/ingest/batch", batch, List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> results = res.getBody();
        assertThat(results).hasSize(2);

        // First: card masked
        Map<String, Object> first = results.get(0);
        Map<String, Object> firstReqSummary = (Map<String, Object>) first.get("requestSummary");
        assertThat(((Number) firstReqSummary.get("pciFieldsMasked")).intValue()).isGreaterThanOrEqualTo(1);

        // No raw card in stub
        assertThat(first.get("generatedStub").toString()).doesNotContain("5500000000000004");

        // Masked value in field details
        List<Map<String, Object>> firstDetails = (List<Map<String, Object>>) firstReqSummary.get("fieldDetails");
        assertThat(findField(firstDetails, "credit_card").get("originalValue")).isEqualTo("****0004");

        // Second: no PCI
        Map<String, Object> secondReqSummary = (Map<String, Object>) results.get(1).get("requestSummary");
        assertThat(((Number) secondReqSummary.get("pciFieldsMasked")).intValue()).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedSensitiveData_maskedInStub() {
        Map<String, Object> rawTraffic = Map.of(
                "request", Map.of(
                        "method", "POST", "path", "/api/v1/orders",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "payment", Map.of(
                                        "card_number", "4111111111111111",
                                        "cvv", "456",
                                        "amount", 99.0
                                ),
                                "customer", Map.of(
                                        "email", "secret@corp.com",
                                        "name", "Bob"
                                )
                        )
                ),
                "response", Map.of(
                        "statusCode", 201, "headers", Map.of(),
                        "body", Map.of("order_id", "ORD-999", "status", "CREATED")
                )
        );

        ResponseEntity<Map> res = http.postForEntity("/api/v1/recording/ingest", rawTraffic, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> reqSummary = (Map<String, Object>) res.getBody().get("requestSummary");
        assertThat(((Number) reqSummary.get("pciFieldsMasked")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(((Number) reqSummary.get("piiFieldsMasked")).intValue()).isGreaterThanOrEqualTo(1);

        // Zero raw data in entire response
        String fullResponse = res.getBody().toString();
        assertThat(fullResponse).doesNotContain("4111111111111111");
        assertThat(fullResponse).doesNotContain("secret@corp.com");
        assertThat(fullResponse).doesNotContain("456");

        // Safe fields preserved
        assertThat(fullResponse).contains("Bob");
        assertThat(fullResponse).contains("ORD-999");
    }

    private Map<String, Object> findField(List<Map<String, Object>> details, String fieldName) {
        return details.stream()
                .filter(d -> fieldName.equals(d.get("fieldName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + fieldName));
    }
}
