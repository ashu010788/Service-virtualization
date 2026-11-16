package com.virtualization.servicevirtualizationplatform.engine.matcher;

import java.util.List;
import java.util.Map;

/**
 * Manual JSONPath body matcher — no external library.
 * Supports: $.field, $.nested.field, $.array[0].field
 * Stateless pure function — no locks needed.
 */
public final class BodyMatcherEvaluator {

    private BodyMatcherEvaluator() {}

    /**
     * Check if all JSONPath conditions match the request body.
     * All conditions must pass (AND logic).
     */
    public static boolean matches(Map<String, String> conditions, Map<String, Object> body) {
        if (conditions == null || conditions.isEmpty()) return true;
        if (body == null || body.isEmpty()) return false;

        for (Map.Entry<String, String> condition : conditions.entrySet()) {
            String path = condition.getKey();
            String expected = condition.getValue();

            Object actual = resolvePath(body, path);
            if (actual == null) return false;
            if (!expected.equals(String.valueOf(actual))) return false;
        }
        return true;
    }

    /**
     * Resolve a JSONPath expression against a Map body.
     * Supports: $.currency, $.address.city, $.items[0].sku, $.data[2].nested.value
     */
    @SuppressWarnings("unchecked")
    public static Object resolvePath(Map<String, Object> body, String jsonPath) {
        if (body == null || jsonPath == null) return null;

        String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
        String[] parts = path.split("\\.");
        Object current = body;

        for (String part : parts) {
            if (current == null) return null;

            int bracketStart = part.indexOf('[');
            if (bracketStart != -1) {
                // Array access: "items[0]"
                String fieldName = part.substring(0, bracketStart);
                int bracketEnd = part.indexOf(']');
                if (bracketEnd == -1) return null;
                int arrayIndex;
                try {
                    arrayIndex = Integer.parseInt(part.substring(bracketStart + 1, bracketEnd));
                } catch (NumberFormatException e) {
                    return null;
                }

                if (current instanceof Map<?, ?> map) {
                    current = map.get(fieldName);
                } else {
                    return null;
                }

                if (current instanceof List<?> list) {
                    if (arrayIndex >= 0 && arrayIndex < list.size()) {
                        current = list.get(arrayIndex);
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                // Simple field access: "currency"
                if (current instanceof Map<?, ?> map) {
                    current = map.get(part);
                } else {
                    return null;
                }
            }
        }
        return current;
    }
}
