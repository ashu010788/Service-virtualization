package com.virtualization.servicevirtualizationplatform.model;

import java.util.List;
import java.util.Map;

public record VirtualResponse(
        String matchedStubId,
        int statusCode,
        Map<String, String> headers,
        Map<String, Object> body,
        boolean stateMutated,
        double executionLatencyMs,
        List<String> matchedRules
) {}
