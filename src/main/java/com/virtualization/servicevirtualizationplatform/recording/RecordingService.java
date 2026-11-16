package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orchestrates the recording → sanitization → stub generation pipeline.
 */
@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    private final PciPiiSanitizer sanitizer;
    private final StubGenerator stubGenerator;
    private final ConfidenceCalculator confidenceCalculator;
    private final StubRouter stubRouter;
    private final List<TrafficRecord> recordingHistory = new CopyOnWriteArrayList<>();

    public RecordingService(PciPiiSanitizer sanitizer, StubGenerator stubGenerator,
                             ConfidenceCalculator confidenceCalculator, StubRouter stubRouter) {
        this.sanitizer = sanitizer;
        this.stubGenerator = stubGenerator;
        this.confidenceCalculator = confidenceCalculator;
        this.stubRouter = stubRouter;
    }

    public StubGenerationResult ingest(RawTrafficLog rawLog) {
        String recordId = "rec-" + UUID.randomUUID().toString().substring(0, 8);
        TrafficRecord record = new TrafficRecord(recordId, Instant.now(), rawLog,
                TrafficRecord.Status.PROCESSING);
        recordingHistory.add(record);

        try {
            // Sanitize request body + headers
            SanitizationResult sanitizedRequest = sanitizer.sanitize(
                    rawLog.request().body());
            sanitizer.sanitizeHeaders(rawLog.request().headers());

            // Sanitize response body
            SanitizationResult sanitizedResponse = sanitizer.sanitize(
                    rawLog.response().body());

            // Calculate confidence
            double confidence = confidenceCalculator.calculate(
                    sanitizedRequest.summary(), sanitizedResponse.summary());

            // Generate stub
            StubDefinition generatedStub = stubGenerator.generate(
                    rawLog.request().method(),
                    rawLog.request().path(),
                    sanitizedRequest.sanitizedBody(),
                    sanitizedResponse.sanitizedBody(),
                    rawLog.response().statusCode()
            );

            // Decide action based on confidence
            String status;
            if (confidence >= 0.90) {
                stubRouter.register(generatedStub);
                record.setStatus(TrafficRecord.Status.AUTO_PUBLISHED);
                record.setGeneratedStubId(generatedStub.stubId());
                status = "AUTO_PUBLISHED";
                log.info("Auto-published stub: {} (confidence: {})", generatedStub.stubId(), confidence);
            } else if (confidence >= 0.70) {
                record.setStatus(TrafficRecord.Status.PENDING_REVIEW);
                status = "PENDING_REVIEW";
                log.info("Stub pending review: {} (confidence: {})", generatedStub.stubId(), confidence);
            } else {
                record.setStatus(TrafficRecord.Status.REJECTED);
                status = "REJECTED";
                log.warn("Stub rejected: {} (confidence: {})", generatedStub.stubId(), confidence);
            }

            return new StubGenerationResult(recordId, generatedStub,
                    sanitizedRequest.summary(), sanitizedResponse.summary(),
                    confidence, status);

        } catch (Exception e) {
            record.setStatus(TrafficRecord.Status.ERROR);
            log.error("Ingest failed for record {}: {}", recordId, e.getMessage());
            return StubGenerationResult.error(recordId, e.getMessage());
        }
    }

    public List<TrafficRecord> getHistory() {
        return List.copyOf(recordingHistory);
    }
}
