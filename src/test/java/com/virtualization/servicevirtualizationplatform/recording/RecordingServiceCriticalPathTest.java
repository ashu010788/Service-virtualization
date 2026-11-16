package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.RawTrafficLog;
import com.virtualization.servicevirtualizationplatform.model.StubGenerationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail from CUSTOMER_VALUE.md: payment endpoints must always go to
 * human review, never auto-publish, regardless of confidence score.
 * Fraud and compliance paths follow normal confidence-based routing.
 */
class RecordingServiceCriticalPathTest {

    private final RecordingService recordingService = new RecordingService(
            new PciPiiSanitizer(), new StubGenerator(), new ConfidenceCalculator(),
            new StubRouter(), new CriticalPathClassifier());

    private RawTrafficLog squeakyCleanLog(String path) {
        // Zero PCI/PII, zero unknown fields — would normally score confidence 1.0.
        RawTrafficLog.RequestData request = new RawTrafficLog.RequestData(
                "HTTP", "POST", path, Map.of("Content-Type", "application/json"),
                Map.of("quantity", 3));
        RawTrafficLog.ResponseData response = new RawTrafficLog.ResponseData(
                200, Map.of(), Map.of("status", "CONFIRMED"));
        return new RawTrafficLog(request, response);
    }

    @Test
    void paymentPath_highConfidence_autoPublishes() {
        StubGenerationResult result = recordingService.ingest(squeakyCleanLog("/api/v1/payments"));

        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.status()).isEqualTo("AUTO_PUBLISHED");
    }

    @Test
    void fraudPath_highConfidence_autoPublishes() {
        StubGenerationResult result = recordingService.ingest(squeakyCleanLog("/api/v1/fraud-checks"));

        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.status()).isEqualTo("AUTO_PUBLISHED");
    }

    @Test
    void compliancePath_highConfidence_autoPublishes() {
        StubGenerationResult result = recordingService.ingest(squeakyCleanLog("/api/v1/compliance/kyc"));

        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.status()).isEqualTo("AUTO_PUBLISHED");
    }

    @Test
    void nonCriticalPath_highConfidence_autoPublishesAsBefore() {
        StubGenerationResult result = recordingService.ingest(squeakyCleanLog("/api/v1/widget-orders"));

        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.status()).isEqualTo("AUTO_PUBLISHED");
    }
}
