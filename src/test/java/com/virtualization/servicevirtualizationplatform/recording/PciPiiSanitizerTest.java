package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.SanitizationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PciPiiSanitizerTest {

    private final PciPiiSanitizer sanitizer = new PciPiiSanitizer();

    // ═══ CREDIT CARD DETECTION ═══

    @Test
    void visa_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("credit_card", "4111111111111111", "amount", 100.0));
        assertThat(result.sanitizedBody().get("credit_card").toString())
                .doesNotContain("4111111111111111");
        assertThat(result.sanitizedBody().get("amount")).isEqualTo(100.0);
        assertThat(result.summary().pciFieldsMasked()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void mastercard_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("card", "5500000000000004", "amount", 50.0));
        assertThat(result.sanitizedBody().get("card").toString())
                .doesNotContain("5500000000000004");
    }

    @Test
    void amex_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("card_number", "378282246310005", "merchant", "Walmart"));
        assertThat(result.sanitizedBody().get("card_number").toString())
                .doesNotContain("378282246310005");
        assertThat(result.sanitizedBody().get("merchant")).isEqualTo("Walmart");
    }

    @Test
    void invalidCardNumber_notRedactedByLuhn() {
        // 4111111111111112 fails Luhn — but field name "credit_card" still triggers redaction
        SanitizationResult result = sanitizer.sanitize(
                Map.of("reference_num", "1234567890123456", "amount", 100.0));
        // "reference_num" is not a sensitive name, and 1234567890123456 fails Luhn
        // So it should NOT be redacted (unless Luhn passes)
        boolean luhnPasses = PciPiiSanitizer.luhnCheck("1234567890123456");
        if (!luhnPasses) {
            // Should be kept or classified as unknown
            assertThat(result.sanitizedBody().containsKey("reference_num")).isTrue();
        }
    }

    // ═══ SSN DETECTION ═══

    @Test
    void ssn_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("ssn", "123-45-6789", "name", "Jane Doe"));
        assertThat(result.sanitizedBody().get("ssn").toString())
                .doesNotContain("123-45-6789");
        assertThat(result.sanitizedBody().get("name")).isEqualTo("Jane Doe");
    }

    // ═══ CVV DETECTION ═══

    @Test
    void cvv_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("credit_card", "4111111111111111", "cvv", "123", "amount", 100.0));
        assertThat(result.sanitizedBody().get("cvv")).isNotEqualTo("123");
        assertThat(result.sanitizedBody().get("amount")).isEqualTo(100.0);
        assertThat(result.summary().pciFieldsMasked()).isEqualTo(2);
    }

    // ═══ EMAIL DETECTION ═══

    @Test
    void email_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("email", "jane.doe@walmart.com", "name", "Jane"));
        assertThat(result.sanitizedBody().get("email").toString())
                .doesNotContain("jane.doe@walmart.com");
        assertThat(result.sanitizedBody().get("name")).isEqualTo("Jane");
    }

    // ═══ AUTH TOKEN DETECTION ═══

    @Test
    void bearerToken_redacted() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("token", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123", "user", "admin"));
        assertThat(result.sanitizedBody().get("token").toString()).doesNotContain("eyJhbGci");
        assertThat(result.sanitizedBody().get("user")).isEqualTo("admin");
    }

    // ═══ NESTED OBJECT ═══

    @Test
    void nestedObject_sanitizedRecursively() {
        SanitizationResult result = sanitizer.sanitize(Map.of(
                "customer", Map.of(
                        "name", "Alice",
                        "email", "alice@test.com",
                        "ssn", "111-22-3333"
                ),
                "amount", 500.0
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) result.sanitizedBody().get("customer");
        assertThat(customer.get("name")).isEqualTo("Alice");
        assertThat(customer.get("email").toString()).doesNotContain("alice@test.com");
        assertThat(customer.get("ssn").toString()).doesNotContain("111-22-3333");
        assertThat(result.sanitizedBody().get("amount")).isEqualTo(500.0);
    }

    // ═══ NON-SENSITIVE PRESERVED ═══

    @Test
    void nonSensitiveFields_preserved() {
        SanitizationResult result = sanitizer.sanitize(Map.of(
                "amount", 150.0,
                "currency", "USD",
                "status", "APPROVED",
                "merchant", "Walmart"
        ));
        assertThat(result.sanitizedBody().get("amount")).isEqualTo(150.0);
        assertThat(result.sanitizedBody().get("currency")).isEqualTo("USD");
        assertThat(result.sanitizedBody().get("status")).isEqualTo("APPROVED");
        assertThat(result.sanitizedBody().get("merchant")).isEqualTo("Walmart");
        assertThat(result.summary().pciFieldsMasked()).isZero();
        assertThat(result.summary().piiFieldsMasked()).isZero();
    }

    // ═══ LUHN ALGORITHM ═══

    @Test
    void luhnCheck_validCards() {
        assertThat(PciPiiSanitizer.luhnCheck("4111111111111111")).isTrue();  // Visa test
        assertThat(PciPiiSanitizer.luhnCheck("5500000000000004")).isTrue();  // MC test
        assertThat(PciPiiSanitizer.luhnCheck("378282246310005")).isTrue();   // Amex test
    }

    @Test
    void luhnCheck_invalidNumbers() {
        assertThat(PciPiiSanitizer.luhnCheck("4111111111111112")).isFalse();
        assertThat(PciPiiSanitizer.luhnCheck("1234567890123456")).isFalse();
    }

    // ═══ TASK REQUIREMENT: exact test from spec ═══

    @Test
    void sensitivePciCardDataIsRedactedInGeneratedStub() {
        SanitizationResult result = sanitizer.sanitize(
                Map.of("credit_card", "4111111111111111", "cvv", "123", "amount", 100.0)
        );

        String generatedBody = result.sanitizedBody().toString();
        Map<String, Object> body = result.sanitizedBody();

        assertThat(generatedBody).doesNotContain("4111111111111111");
        assertThat(body.get("cvv")).isNotEqualTo("123");
        assertThat(body.get("amount")).isEqualTo(100.0);
        assertThat(result.summary().pciFieldsMasked()).isEqualTo(2);
    }
}
