package com.virtualization.servicevirtualizationplatform.engine.matcher;

import java.util.List;
import java.util.Map;

/**
 * Manual JSONPath body matcher — no external library.
 *
 * Supported expressions:
 *   $.field                              simple field
 *   $.nested.field                       multi-level object nesting
 *   $.array[0].field                     array element → field
 *   $.matrix[0][1]                       array of arrays (multiple indices)
 *   $.orders[0].items[1].sku             mixed: array → object → array → field
 *   $.a.b.c.d                            deep nesting (4+ levels)
 *
 * All conditions are AND-ed: every listed JSONPath must match.
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
            Object actual = resolvePath(body, condition.getKey());
            if (actual == null) return false;
            if (!condition.getValue().equals(String.valueOf(actual))) return false;
        }
        return true;
    }

    /**
     * Resolve a JSONPath expression against a Map body.
     *
     * Algorithm:
     * 1. Strip leading "$." prefix.
     * 2. Split on "." to get path segments (e.g., "orders[0]", "items[1]", "sku").
     * 3. For each segment, extract an optional field name and zero or more array indices.
     *    - "field"        → map lookup by key
     *    - "field[2]"     → map lookup then list index
     *    - "[3]"          → list index on current node (array-of-arrays case)
     *    - "field[0][1]"  → map lookup then two sequential list indices
     * 4. Return null on any missing key / out-of-bounds index.
     */
    @SuppressWarnings("unchecked")
    public static Object resolvePath(Map<String, Object> body, String jsonPath) {
        if (body == null || jsonPath == null) return null;

        // Strip "$." prefix
        String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
        // Split on "." — but not inside brackets (brackets don't contain dots in our grammar)
        String[] parts = path.split("\\.");
        Object current = body;

        for (String part : parts) {
            if (current == null) return null;
            current = resolveSegment(current, part);
        }
        return current;
    }

    /**
     * Resolve one dot-separated segment, which may contain multiple array indices.
     * Examples:
     *   "currency"       → plain field
     *   "items[0]"       → field + one index
     *   "matrix[0][1]"   → field + two indices (array of arrays)
     *   "[2]"            → index-only (current is already a list)
     */
    @SuppressWarnings("unchecked")
    private static Object resolveSegment(Object current, String segment) {
        if (current == null) return null;

        int firstBracket = segment.indexOf('[');

        // ── Plain field (no brackets) ──
        if (firstBracket == -1) {
            return current instanceof Map<?, ?> m ? m.get(segment) : null;
        }

        // ── Field name before first bracket ──
        String fieldName = segment.substring(0, firstBracket);
        if (!fieldName.isEmpty()) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(fieldName);
            if (current == null) return null;
        }

        // ── Apply all bracket-index pairs ──
        // e.g., "[0][1]" → indices [0, 1]
        String remainder = segment.substring(firstBracket);
        while (!remainder.isEmpty()) {
            if (!remainder.startsWith("[")) return null;
            int close = remainder.indexOf(']');
            if (close == -1) return null;

            int idx;
            try {
                idx = Integer.parseInt(remainder.substring(1, close));
            } catch (NumberFormatException e) {
                return null;
            }

            if (!(current instanceof List<?> list)) return null;
            if (idx < 0 || idx >= list.size()) return null;
            current = list.get(idx);
            remainder = remainder.substring(close + 1);
        }
        return current;
    }
}
