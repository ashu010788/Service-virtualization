package com.virtualization.servicevirtualizationplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Output contract for the /api/v1/virtual endpoint.
 *
 * JSON field names follow the assignment specification (snake_case).
 *
 * Example JSON:
 * {
 *   "matched_stub_id":      "stub_account_create_v1",
 *   "status_code":          201,
 *   "headers":              { "Content-Type": "application/json" },
 *   "body":                 { "account_id": "ACC-9921", "status": "ACTIVE" },
 *   "state_mutated":        true,
 *   "execution_latency_ms": 2.4,
 *   "matched_rules":        ["path_pattern", "jsonpath_body_check"]
 * }
 */
public record VirtualResponse(
        @JsonProperty("matched_stub_id")      String matchedStubId,
        @JsonProperty("status_code")          int statusCode,
        Map<String, String> headers,
        Map<String, Object> body,
        @JsonProperty("state_mutated")        boolean stateMutated,
        @JsonProperty("execution_latency_ms") double executionLatencyMs,
        @JsonProperty("matched_rules")        List<String> matchedRules
) {}
