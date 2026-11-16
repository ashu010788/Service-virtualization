package com.virtualization.servicevirtualizationplatform.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Input contract for the /api/v1/virtual endpoint.
 *
 * JSON field names follow the assignment specification (snake_case).
 * @JsonAlias keeps backward compatibility for clients sending camelCase.
 *
 * Example JSON:
 * {
 *   "session_id": "cicd-runner-9842",
 *   "protocol":   "HTTP",
 *   "method":     "POST",
 *   "path":       "/api/v1/accounts",
 *   "headers":    { "Content-Type": "application/json" },
 *   "body":       { "account_holder": "Jane Doe", "currency": "USD" }
 * }
 *
 * For path parameters, pass the ACTUAL value in the path:
 *   "path": "/api/v1/employees/EMP-3243"
 * The Trie matches this against the stub's pathPattern "/api/v1/employees/{id}"
 * and captures "EMP-3243" as pathVar.id automatically.
 */
public record VirtualRequest(
        @JsonProperty("session_id") @JsonAlias("sessionId") String sessionId,
        String protocol,
        String method,
        String path,
        Map<String, String> headers,
        Map<String, Object> body
) {
    public VirtualRequest {
        // sessionId deliberately NOT defaulted — VirtualController validates it.
        if (protocol == null) protocol = "HTTP";
        if (method == null)   method   = "GET";
        if (path == null)     path     = "/";
        if (headers == null)  headers  = Map.of();
        if (body == null)     body     = Map.of();
    }
}
