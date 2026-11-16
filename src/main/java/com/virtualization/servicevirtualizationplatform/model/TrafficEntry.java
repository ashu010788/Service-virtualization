package com.virtualization.servicevirtualizationplatform.model;

import java.util.List;

public record TrafficEntry(
        long timestamp,
        String sessionId,
        String protocol,
        String method,
        String path,
        String matchedStubId,
        int statusCode,
        double executionLatencyMs,
        List<String> matchedRules
) {}
