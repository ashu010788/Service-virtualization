package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

public record VirtualRequest(
        String sessionId,
        String protocol,
        String method,
        String path,
        Map<String, String> headers,
        Map<String, Object> body
) {
    public VirtualRequest {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        if (protocol == null) protocol = "HTTP";
        if (method == null) method = "GET";
        if (path == null) path = "/";
        if (headers == null) headers = Map.of();
    }
}
