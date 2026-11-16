package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates StubDefinitions from sanitized traffic logs.
 * Infers match conditions from request, builds response templates from response.
 */
@Service
public class StubGenerator {

    public StubDefinition generate(String method, String path,
                                    Map<String, Object> sanitizedRequestBody,
                                    Map<String, Object> sanitizedResponseBody,
                                    int statusCode) {

        String stubId = "auto_" + path.replaceAll("[^a-zA-Z0-9]", "_")
                + "_" + method + "_" + System.currentTimeMillis();

        // Infer match conditions from request body
        Map<String, String> bodyConditions = new LinkedHashMap<>();
        if (sanitizedRequestBody != null) {
            for (Map.Entry<String, Object> field : sanitizedRequestBody.entrySet()) {
                if (isGoodMatchCandidate(field.getKey(), field.getValue())) {
                    bodyConditions.put("$." + field.getKey(), String.valueOf(field.getValue()));
                }
            }
        }

        // Build response template from sanitized response
        Map<String, Object> responseTemplate = new LinkedHashMap<>();
        if (sanitizedResponseBody != null) {
            for (Map.Entry<String, Object> field : sanitizedResponseBody.entrySet()) {
                Object value = field.getValue();
                if (looksLikeId(field.getKey(), value)) {
                    String prefix = extractPrefix(String.valueOf(value));
                    responseTemplate.put(field.getKey(), "{{generate.id:" + prefix + "}}");
                } else {
                    responseTemplate.put(field.getKey(), value);
                }
            }
        }

        RequestMatcher rm = new RequestMatcher("HTTP", method, path, Map.of(), bodyConditions);
        ResponseTemplate rt = new ResponseTemplate(statusCode,
                Map.of("Content-Type", "application/json"), responseTemplate);

        // Auto-generated stubs get state WRITE action if it's a POST
        StateAction sa = null;
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            String collection = inferCollection(path);
            sa = new StateAction(StateOp.WRITE, collection,
                    "{{response.body." + findIdField(responseTemplate) + "}}",
                    "{{response.body}}");
        }

        return new StubDefinition(stubId, 5, rm, rt, sa, null,
                "auto-generated", null, Instant.now().plus(7, ChronoUnit.DAYS));
    }

    private boolean isGoodMatchCandidate(String key, Object value) {
        if (value == null) return false;
        if (!(value instanceof String s)) return false;
        if (s.contains("****") || s.contains("@masked.com")) return false;
        if (s.length() > 50) return false;
        if (s.matches("\\d+\\.?\\d*")) return false;  // pure numbers vary
        return true;
    }

    private boolean looksLikeId(String key, Object value) {
        if (key.endsWith("_id") || key.equals("id")) return true;
        if (value instanceof String s && s.matches("[A-Z]{2,5}-\\d+")) return true;
        return false;
    }

    private String extractPrefix(String value) {
        int dashIdx = value.indexOf('-');
        return dashIdx > 0 ? value.substring(0, dashIdx) : "ID";
    }

    private String inferCollection(String path) {
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isEmpty() && !segments[i].startsWith("{")) {
                return segments[i];
            }
        }
        return "data";
    }

    private String findIdField(Map<String, Object> template) {
        for (String key : template.keySet()) {
            if (key.endsWith("_id") || key.equals("id")) return key;
        }
        return template.keySet().iterator().hasNext() ? template.keySet().iterator().next() : "id";
    }
}
