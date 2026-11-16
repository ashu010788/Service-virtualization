package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StubGeneratorTest {

    private final StubGenerator generator = new StubGenerator();

    @Test
    void idFields_getGenerateTemplate() {
        StubDefinition stub = generator.generate("POST", "/api/v1/payments",
                Map.of("currency", "USD"),
                Map.of("payment_id", "PAY-99281", "status", "APPROVED", "amount", 100.0),
                200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("payment_id")).isEqualTo("{{generate.id:PAY}}");
    }

    @Test
    void staticFields_keptAsIs() {
        StubDefinition stub = generator.generate("POST", "/api/v1/payments",
                Map.of("currency", "USD"),
                Map.of("status", "APPROVED"),
                200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void categoricalFields_becomeMatchConditions() {
        StubDefinition stub = generator.generate("POST", "/api/v1/accounts",
                Map.of("currency", "USD", "amount", 100.0),
                Map.of("account_id", "ACC-001"),
                201);

        // currency is a good match candidate (short string, not redacted)
        assertThat(stub.requestMatcher().bodyJsonPath()).containsKey("$.currency");
        // amount is a number — should NOT be a match condition
        assertThat(stub.requestMatcher().bodyJsonPath()).doesNotContainKey("$.amount");
    }

    @Test
    void redactedFields_excludedFromMatchers() {
        StubDefinition stub = generator.generate("POST", "/api/v1/payments",
                Map.of("credit_card", "****1111", "currency", "USD"),
                Map.of("payment_id", "PAY-001"),
                200);

        // Redacted field should not be a matcher
        assertThat(stub.requestMatcher().bodyJsonPath()).doesNotContainKey("$.credit_card");
        assertThat(stub.requestMatcher().bodyJsonPath()).containsKey("$.currency");
    }
}
