package com.virtualization.servicevirtualizationplatform.model;

public record DriftConfig(
        long baseLatencyMs,
        long jitterMs,
        double errorRate
) {
    public static final DriftConfig NONE = new DriftConfig(0, 0, 0.0);

    public boolean isActive() {
        return baseLatencyMs > 0 || jitterMs > 0 || errorRate > 0;
    }
}
