package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

public record RequestMatcher(
        String protocol,
        String method,
        String pathPattern,
        Map<String, String> headers,
        Map<String, String> bodyJsonPath
) {
    public RequestMatcher {
        if (protocol == null) protocol = "HTTP";
        if (headers == null) headers = Map.of();
        if (bodyJsonPath == null) bodyJsonPath = Map.of();
    }
}
