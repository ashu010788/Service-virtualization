package com.virtualization.servicevirtualizationplatform.engine.matcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BodyMatcherEvaluatorTest {

    @Test
    void simplePath_matches() {
        Map<String, String> conditions = Map.of("$.currency", "USD");
        Map<String, Object> body = Map.of("currency", "USD", "amount", 100.0);

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void simplePath_mismatch() {
        Map<String, String> conditions = Map.of("$.currency", "USD");
        Map<String, Object> body = Map.of("currency", "EUR", "amount", 100.0);

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isFalse();
    }

    @Test
    void nestedPath_matches() {
        Map<String, String> conditions = Map.of("$.address.country", "US");
        Map<String, Object> body = Map.of("name", "Alice",
                "address", Map.of("city", "NYC", "country", "US"));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void arrayPath_matches() {
        Map<String, String> conditions = Map.of("$.items[0].sku", "SKU-001");
        Map<String, Object> body = Map.of("items",
                List.of(Map.of("sku", "SKU-001", "qty", 2), Map.of("sku", "SKU-002", "qty", 1)));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void multipleConditions_allMustMatch() {
        Map<String, String> conditions = Map.of("$.currency", "USD", "$.type", "SAVINGS");
        Map<String, Object> body = Map.of("currency", "USD", "type", "SAVINGS", "amount", 100.0);

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();

        // One condition fails
        Map<String, Object> body2 = Map.of("currency", "USD", "type", "CHECKING");
        assertThat(BodyMatcherEvaluator.matches(conditions, body2)).isFalse();
    }

    @Test
    void missingField_returnsNoMatch() {
        Map<String, String> conditions = Map.of("$.nonexistent", "value");
        Map<String, Object> body = Map.of("currency", "USD");

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isFalse();
    }

    @Test
    void emptyConditions_alwaysMatch() {
        assertThat(BodyMatcherEvaluator.matches(Map.of(), Map.of("a", "b"))).isTrue();
        assertThat(BodyMatcherEvaluator.matches(null, Map.of("a", "b"))).isTrue();
    }

    @Test
    void nullBody_noMatch() {
        Map<String, String> conditions = Map.of("$.currency", "USD");

        assertThat(BodyMatcherEvaluator.matches(conditions, null)).isFalse();
    }

    @Test
    void resolvePath_deeplyNested() {
        Map<String, Object> body = Map.of("a", Map.of("b", Map.of("c", "deep")));

        Object result = BodyMatcherEvaluator.resolvePath(body, "$.a.b.c");

        assertThat(result).isEqualTo("deep");
    }

    @Test
    void resolvePath_arrayOutOfBounds_returnsNull() {
        Map<String, Object> body = Map.of("items", List.of(Map.of("x", 1)));

        Object result = BodyMatcherEvaluator.resolvePath(body, "$.items[5].x");

        assertThat(result).isNull();
    }
}
