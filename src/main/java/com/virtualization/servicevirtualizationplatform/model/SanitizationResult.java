package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

public record SanitizationResult(
        Map<String, Object> sanitizedBody,
        SanitizationSummary summary
) {}
