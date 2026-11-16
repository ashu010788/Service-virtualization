package com.virtualization.servicevirtualizationplatform.engine.matcher;

import java.util.Map;

/**
 * Stateless pure function — checks if required headers are present with correct values.
 * Case-insensitive key matching (HTTP headers are case-insensitive).
 * No locks needed — no shared mutable state.
 */
public final class HeaderMatcherEvaluator {

    private HeaderMatcherEvaluator() {}

    public static boolean matches(Map<String, String> required, Map<String, String> actual) {
        if (required == null || required.isEmpty()) return true;
        if (actual == null || actual.isEmpty()) return false;

        for (Map.Entry<String, String> entry : required.entrySet()) {
            String requiredKey = entry.getKey();
            String requiredValue = entry.getValue();

            String actualValue = findHeaderValue(actual, requiredKey);
            if (actualValue == null || !requiredValue.equals(actualValue)) {
                return false;
            }
        }
        return true;
    }

    private static String findHeaderValue(Map<String, String> headers, String key) {
        // Direct lookup first (fast path)
        String direct = headers.get(key);
        if (direct != null) return direct;

        // Case-insensitive fallback
        String lowerKey = key.toLowerCase();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().toLowerCase().equals(lowerKey)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
