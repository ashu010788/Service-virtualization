package com.virtualization.servicevirtualizationplatform.model;

import java.util.Map;

public record AsyncEventConfig(
        String targetProtocol,
        String targetTopic,
        long delayMs,
        long jitterMs,
        Map<String, Object> eventBody,
        StateAction stateUpdate
) {}
