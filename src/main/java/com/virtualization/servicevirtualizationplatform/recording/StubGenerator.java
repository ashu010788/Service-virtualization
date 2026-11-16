package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates StubDefinitions from sanitized traffic logs.
 * Supports HTTP, gRPC, and Kafka protocols.
 *
 * ── HTTP GET stub generation ──────────────────────────────────────────────────
 *
 *   Case A — Path contains {paramName}  (e.g. /api/v1/department/{departmentId})
 *     • pathPattern kept as-is (Trie wildcard matches any real value)
 *     • Response body = STATIC values copied verbatim from the recorded response
 *     • No stateAction — pure static replay regardless of path variable value
 *     • Virtual API call: GET /api/v1/department/434 → returns recorded static body
 *
 *   Case B — Path is a literal  (e.g. /api/v1/department/234)
 *     • pathPattern is the literal path (exact Trie match)
 *     • Response body = STATIC values copied verbatim from the recorded response
 *     • No stateAction — pure static replay
 *
 * ── HTTP POST/PUT stub generation ─────────────────────────────────────────────
 *
 *   • pathPattern is the literal path as recorded (e.g. /api/v1/employees/123)
 *   • bodyJsonPath conditions derived from request body fields (string + numeric values)
 *   • Response fields: in-request (unmasked) → {{request.body.FIELD}} dynamic echo, else the
 *     static value copied verbatim from the recorded response (including ID-shaped fields —
 *     record/replay means replay exactly what was captured, not mint a fresh ID each time)
 *   • stateAction WRITE to inferred collection
 *
 * ── gRPC / Kafka ───────────────────────────────────────────────────────────────
 *   • No stateAction (stateless match-and-reply)
 *   • Response fields echoed from request where names match
 */
@Service
public class StubGenerator {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");

    /**
     * Generate a stub with explicit protocol support.
     *
     * HTTP GET — always succeeds:
     *   • Path with {paramName} → Trie wildcard, static response from recording
     *   • Path without {paramName} → exact Trie match, static response from recording
     *
     * HTTP POST/PUT — literal pathPattern + bodyJsonPath conditions + dynamic echo response
     */
    public StubDefinition generate(String protocol, String method, String path,
                                   Map<String, Object> sanitizedRequestBody,
                                   Map<String, Object> sanitizedResponseBody,
                                   int statusCode) {

        String effectiveProtocol = (protocol == null || protocol.isBlank()) ? "HTTP" : protocol.toUpperCase();

        // Build stub ID
        String idSuffix = "HTTP".equals(effectiveProtocol)
                ? path.replaceAll("[^a-zA-Z0-9]", "_")
                : method.replaceAll("[^a-zA-Z0-9]", "_");
        String stubId = "auto_" + idSuffix + "_" + method + "_" + System.currentTimeMillis();

        // Body match conditions from request (not applicable to GET — no request body)
        Map<String, String> bodyConditions = new LinkedHashMap<>();
        if (sanitizedRequestBody != null && !"GET".equalsIgnoreCase(method)) {
            for (Map.Entry<String, Object> field : sanitizedRequestBody.entrySet()) {
                if (isGoodMatchCandidate(field.getKey(), field.getValue())) {
                    bodyConditions.put("$." + field.getKey(), String.valueOf(field.getValue()));
                }
            }
        }

        String pathPattern = "HTTP".equals(effectiveProtocol) ? path : "";
        RequestMatcher rm = new RequestMatcher(effectiveProtocol, method, pathPattern, Map.of(), bodyConditions);

        // ── HTTP GET: static replay from recorded response — no stateAction ──
        // Case A: path has {paramName} → Trie wildcard matches any real value
        // Case B: path is literal        → exact Trie match
        // Either way: return the recorded static response body verbatim
        if ("HTTP".equals(effectiveProtocol) && "GET".equalsIgnoreCase(method)) {
            Map<String, Object> staticBody = sanitizedResponseBody != null
                    ? new LinkedHashMap<>(sanitizedResponseBody)
                    : new LinkedHashMap<>();
            ResponseTemplate rt = new ResponseTemplate(statusCode,
                    Map.of("Content-Type", "application/json"), staticBody);
            return new StubDefinition(stubId, rm, rt, null, null,
                    "auto-generated", null, Instant.now().plus(7, ChronoUnit.DAYS));
        }

        // ── POST / PUT / gRPC / Kafka: build response body ──
        Map<String, Object> responseBody = buildResponseBody(sanitizedResponseBody, sanitizedRequestBody);

        ResponseTemplate rt = new ResponseTemplate(statusCode,
                Map.of("Content-Type", "application/json"), responseBody);

        // stateAction WRITE for HTTP POST/PUT only
        StateAction sa = null;
        if ("HTTP".equals(effectiveProtocol)
                && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
            String collection = inferCollection(path);
            sa = new StateAction(StateOp.WRITE, collection,
                    "{{response.body." + findIdField(responseBody) + "}}",
                    "{{response.body}}");
        }

        return new StubDefinition(stubId, rm, rt, sa, null,
                "auto-generated", null, Instant.now().plus(7, ChronoUnit.DAYS));
    }

    /**
     * Backward-compatible overload — defaults to HTTP protocol.
     */
    public StubDefinition generate(String method, String path,
                                   Map<String, Object> sanitizedRequestBody,
                                   Map<String, Object> sanitizedResponseBody,
                                   int statusCode) {
        return generate("HTTP", method, path, sanitizedRequestBody, sanitizedResponseBody, statusCode);
    }

    // ── Response body builder ──────────────────────────────────────────────────

    /**
     * Builds the response body template map.
     *
     * Priority order per field:
     *   1. Field name also exists in request body (and isn't masked) → {{request.body.FIELD}} (dynamic echo)
     *   2. Otherwise → static value from the recorded example, verbatim
     *
     * NOTE: we deliberately do NOT auto-generate a fresh ID for ID-shaped fields that are
     * absent from the request (e.g. "employee_id"). Record/replay semantics means "replay
     * exactly what was recorded unless the caller supplied the value themselves" — silently
     * minting a new ID here would mean every replay returns a different ID than what was
     * actually captured, which breaks the "record once, replay exactly" contract this
     * auto-generated stub exists to provide. If you want a fresh ID per invocation, register
     * a manual stub with an explicit {{generate.id:PREFIX}} expression instead.
     */
    private Map<String, Object> buildResponseBody(Map<String, Object> sanitizedResponseBody,
                                                   Map<String, Object> sanitizedRequestBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sanitizedResponseBody == null) return result;

        for (Map.Entry<String, Object> entry : sanitizedResponseBody.entrySet()) {
            String key   = entry.getKey();
            Object value = entry.getValue();

            if (sanitizedRequestBody != null && sanitizedRequestBody.containsKey(key)
                    && !isRedacted(sanitizedRequestBody.get(key))) {
                // Field exists in request body and is not masked → echo dynamically.
                result.put(key, "{{request.body." + key + "}}");
            } else {
                // Not in request (or masked there) → static value from the recorded example
                result.put(key, value);
            }
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true if the sanitized value was masked by the PCI/PII sanitizer.
     * Masked values must NOT be echoed dynamically from the request body.
     */
    private boolean isRedacted(Object value) {
        if (value == null) return true;
        if (value instanceof String s) {
            return s.contains("****") || s.contains("@masked.com");
        }
        return false; // Numbers, booleans — not redacted, safe to echo
    }

    private boolean isGoodMatchCandidate(String key, Object value) {
        if (value == null) return false;
        // Numeric values (Integer, Long, Double) are good conditions — include them
        if (value instanceof Number) return true;
        if (!(value instanceof String s)) return false;
        if (s.contains("****") || s.contains("@masked.com")) return false;
        if (s.length() > 50) return false;
        return true;
    }

    /**
     * Returns the LAST path parameter name from paths like /api/v1/employees/{id}.
     * Returns null if no {param} found.
     */
    private String extractLastPathParam(String path) {
        if (path == null) return null;
        Matcher m = PATH_PARAM_PATTERN.matcher(path);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /**
     * Infers the state collection name from the path.
     * Uses the last non-empty, non-{param} segment.
     * e.g. /api/v1/employees/{id} → "employees"
     */
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
