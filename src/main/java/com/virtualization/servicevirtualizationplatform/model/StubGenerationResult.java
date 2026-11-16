package com.virtualization.servicevirtualizationplatform.model;

public record StubGenerationResult(
        String recordId,
        StubDefinition generatedStub,
        SanitizationSummary requestSummary,
        SanitizationSummary responseSummary,
        double confidence,
        String status
) {
    public static StubGenerationResult error(String recordId, String message) {
        return new StubGenerationResult(recordId, null, null, null, 0.0, "ERROR: " + message);
    }
}
