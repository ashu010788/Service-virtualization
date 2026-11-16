package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

/**
 * Body can be a Map (normal JSON object) or a String (single placeholder like "{{state.result}}").
 */
public record ResponseTemplate(
        int statusCode,
        Map<String, String> headers,
        Object body
) {
    public ResponseTemplate {
        if (headers == null) headers = Map.of("Content-Type", "application/json");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> bodyAsMap() {
        if (body instanceof Map) return (Map<String, Object>) body;
        return Map.of();
    }
}
