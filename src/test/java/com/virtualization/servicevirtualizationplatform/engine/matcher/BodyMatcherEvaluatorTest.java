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

    // ═══ DEEP MULTI-LEVEL NESTING (3+ levels) ═══

    @Test
    void threeLevel_nested_matches() {
        Map<String, String> conditions = Map.of("$.order.payment.method", "CARD");
        Map<String, Object> body = Map.of("order", Map.of(
                "id",      "ORD-001",
                "payment", Map.of("method", "CARD", "amount", 100.0)
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void fourLevel_nested_matches() {
        Map<String, String> conditions = Map.of("$.customer.address.billing.country", "US");
        Map<String, Object> body = Map.of("customer", Map.of("address", Map.of("billing", Map.of("country", "US", "zip", "10001"))));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void threeLevel_nested_mismatch() {
        Map<String, String> conditions = Map.of("$.order.payment.method", "CARD");
        Map<String, Object> body = Map.of("order", Map.of("payment", Map.of("method", "CASH")));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isFalse();
    }

    @Test
    void threeLevel_middleNodeMissing_noMatch() {
        Map<String, String> conditions = Map.of("$.order.payment.method", "CARD");
        Map<String, Object> body = Map.of("order", Map.of("id", "ORD-001")); // payment key missing

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isFalse();
    }

    // ═══ ARRAY INDEXING ═══

    @Test
    void arrayIndex_firstElement_matches() {
        Map<String, String> conditions = Map.of("$.items[0].sku", "SKU-001");
        Map<String, Object> body = Map.of("items", List.of(
                Map.of("sku", "SKU-001", "qty", 2),
                Map.of("sku", "SKU-002", "qty", 1)
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void arrayIndex_secondElement_matches() {
        Map<String, String> conditions = Map.of("$.items[1].sku", "SKU-002");
        Map<String, Object> body = Map.of("items", List.of(
                Map.of("sku", "SKU-001", "qty", 2),
                Map.of("sku", "SKU-002", "qty", 1)
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void arrayIndex_wrongElement_noMatch() {
        Map<String, String> conditions = Map.of("$.items[0].sku", "SKU-999");
        Map<String, Object> body = Map.of("items", List.of(
                Map.of("sku", "SKU-001", "qty", 2)
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isFalse();
    }

    @Test
    void arrayIndex_outOfBounds_noMatch() {
        Map<String, String> conditions = Map.of("$.items[5].sku", "SKU-001");
        Map<String, Object> body = Map.of("items", List.of(Map.of("sku", "SKU-001")));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isFalse();
    }

    // ═══ ARRAY OF ARRAYS (2D) ═══

    @Test
    void arrayOfArrays_firstElement() {
        Map<String, String> conditions = Map.of("$.matrix[0][1]", "B");
        Map<String, Object> body = Map.of("matrix", List.of(
                List.of("A", "B", "C"),
                List.of("D", "E", "F")
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void arrayOfArrays_secondRow() {
        Map<String, String> conditions = Map.of("$.matrix[1][0]", "D");
        Map<String, Object> body = Map.of("matrix", List.of(
                List.of("A", "B", "C"),
                List.of("D", "E", "F")
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    // ═══ MIXED NESTING: ARRAY INSIDE OBJECT INSIDE ARRAY ═══

    @Test
    void mixed_arrayInsideObject_insideArray() {
        // $.orders[0].items[1].sku
        Map<String, String> conditions = Map.of("$.orders[0].items[1].sku", "SKU-B");
        Map<String, Object> body = Map.of("orders", List.of(
                Map.of("order_id", "ORD-1", "items", List.of(
                        Map.of("sku", "SKU-A", "qty", 1),
                        Map.of("sku", "SKU-B", "qty", 3)   // ← [0][1]
                )),
                Map.of("order_id", "ORD-2", "items", List.of(
                        Map.of("sku", "SKU-C", "qty", 2)
                ))
        ));

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void mixed_deepNested_withArray_multipleConditions() {
        // Multiple conditions: nested + array
        Map<String, String> conditions = new java.util.HashMap<>();
        conditions.put("$.customer.tier",         "GOLD");
        conditions.put("$.items[0].category",     "ELECTRONICS");

        Map<String, Object> body = Map.of(
                "customer", Map.of("tier", "GOLD", "id", "CUS-001"),
                "items",    List.of(
                        Map.of("category", "ELECTRONICS", "price", 299.99),
                        Map.of("category", "CLOTHING",   "price", 49.99)
                )
        );

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();

        // Change tier → both conditions must pass, so fails
        Map<String, Object> body2 = Map.of(
                "customer", Map.of("tier", "SILVER"),
                "items",    List.of(Map.of("category", "ELECTRONICS"))
        );
        assertThat(BodyMatcherEvaluator.matches(conditions, body2)).isFalse();
    }

    // ═══ NUMERIC AND BOOLEAN VALUE MATCHING ═══

    @Test
    void numericValue_matchedAsString() {
        // bodyJsonPath values are always strings; engine coerces for comparison
        Map<String, String> conditions = Map.of("$.amount", "100");
        Map<String, Object> body = Map.of("amount", 100);  // Integer in body

        // String "100" vs Integer 100 → matched via toString coercion
        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }

    @Test
    void booleanValue_matchedAsString() {
        Map<String, String> conditions = Map.of("$.active", "true");
        Map<String, Object> body = Map.of("active", true);

        assertThat(BodyMatcherEvaluator.matches(conditions, body)).isTrue();
    }
}
