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

    /**
     * Bounded, same reasoning as TrafficLogger's fixed ring buffer: recording ingestion ran
     * unbounded before this (every ingest()'d raw log + generated stub kept forever), which
     * leaks memory over a long-lived instance. Ingestion is a low-frequency, manual/batch
     * action — not a 20k RPS hot path — so a simple capped List with O(n) trims is fine; it
     * doesn't need TrafficLogger's lock-free array-ring-buffer treatment.
     */
    private static final int MAX_HISTORY = 1_000;

    private final PciPiiSanitizer sanitizer;
    private final StubGenerator stubGenerator;
    private final ConfidenceCalculator confidenceCalculator;
    private final StubRouter stubRouter;
    private final CriticalPathClassifier criticalPathClassifier;
    private final List<TrafficRecord> recordingHistory = new CopyOnWriteArrayList<>();

    public RecordingService(PciPiiSanitizer sanitizer, StubGenerator stubGenerator,
                             ConfidenceCalculator confidenceCalculator, StubRouter stubRouter,
                             CriticalPathClassifier criticalPathClassifier) {
        this.sanitizer = sanitizer;
        this.stubGenerator = stubGenerator;
        this.confidenceCalculator = confidenceCalculator;
        this.stubRouter = stubRouter;
        this.criticalPathClassifier = criticalPathClassifier;
    }

    public StubGenerationResult ingest(RawTrafficLog rawLog) {
        String recordId = "rec-" + UUID.randomUUID().toString().substring(0, 8);
        TrafficRecord record = new TrafficRecord(recordId, Instant.now(), rawLog,
                TrafficRecord.Status.PROCESSING);
        recordingHistory.add(record);
        trimToCapacity();

        try {
            GeneratedStubBundle bundle = generateStub(rawLog);
            StubDefinition generatedStub = bundle.stub();
            double confidence = bundle.confidence();

            // Decide action based on confidence. Payment/fraud/compliance paths are a hard
            // guardrail (CUSTOMER_VALUE.md): they must always go through human review, never
            // auto-publish, regardless of how high the confidence score is.
            boolean requiresReview = criticalPathClassifier.isCritical(rawLog.request().path());

            String status;
            if (confidence >= 0.90 && !requiresReview) {
                // registerOrReplaceByRequestShape: if an earlier ingestion already produced a
                // stub with an IDENTICAL requestMatcher (same path/method/body values), replace
                // it instead of piling up an unreachable duplicate. Safe here specifically
                // because ingestion never intentionally creates same-matcher candidate chains
                // (unlike the manual stub API) — see StubRouter#registerOrReplaceByRequestShape.
                List<String> replacedStubIds = stubRouter.registerOrReplaceByRequestShape(generatedStub);
                record.setStatus(TrafficRecord.Status.AUTO_PUBLISHED);
                record.setGeneratedStubId(generatedStub.stubId());
                status = "AUTO_PUBLISHED";
                if (replacedStubIds.isEmpty()) {
                    log.info("Auto-published stub: {} (confidence: {})", generatedStub.stubId(), confidence);
                } else {
                    log.info("Auto-published stub: {} (confidence: {}), replacing identical-matcher stub(s): {}",
                            generatedStub.stubId(), confidence, replacedStubIds);
                }
            } else if (confidence >= 0.70 || requiresReview) {
                // NOT registered in StubRouter yet — intentionally. A PENDING_REVIEW stub must
                // never match live /api/v1/virtual traffic or show up in Stub Management until a
                // human calls approveRecord() below. We still keep the generatedStub reference
                // around (via rawLog, re-generated on approve) so review has something to show.
                record.setStatus(TrafficRecord.Status.PENDING_REVIEW);
                status = "PENDING_REVIEW";
                if (requiresReview) {
                    log.info("Stub pending review: {} (confidence: {}) - payment/fraud/compliance path requires human review",
                            generatedStub.stubId(), confidence);
                } else {
                    log.info("Stub pending review: {} (confidence: {})", generatedStub.stubId(), confidence);
                }
            } else {
                record.setStatus(TrafficRecord.Status.REJECTED);
                status = "REJECTED";
                log.warn("Stub rejected: {} (confidence: {})", generatedStub.stubId(), confidence);
            }

            return new StubGenerationResult(recordId, generatedStub,
                    bundle.requestSummary(), bundle.responseSummary(),
                    confidence, status);

        } catch (Exception e) {
            record.setStatus(TrafficRecord.Status.ERROR);
            log.error("Ingest failed for record {}: {}", recordId, e.getMessage());
            return StubGenerationResult.error(recordId, e.getMessage());
        }
    }

    /**
     * Sanitize + generate pipeline shared by ingest() (preview/decide) and approveRecord()
     * (re-run at approval time, since PENDING_REVIEW never persisted the actual StubDefinition
     * object — TrafficRecord only keeps the raw, unsanitized rawLog plus a status). Generation
     * is a pure function of rawLog's content (aside from the stub's auto-generated ID/timestamp),
     * so re-running it here reproduces the same requestMatcher/responseTemplate shown at ingest.
     */
    private GeneratedStubBundle generateStub(RawTrafficLog rawLog) {
        SanitizationResult sanitizedRequest = sanitizer.sanitize(rawLog.request().body());
        sanitizer.sanitizeHeaders(rawLog.request().headers());
        SanitizationResult sanitizedResponse = sanitizer.sanitize(rawLog.response().body());

        double confidence = confidenceCalculator.calculate(
                sanitizedRequest.summary(), sanitizedResponse.summary());

        StubDefinition generatedStub = stubGenerator.generate(
                rawLog.request().effectiveProtocol(),
                rawLog.request().method(),
                rawLog.request().path(),
                sanitizedRequest.sanitizedBody(),
                sanitizedResponse.sanitizedBody(),
                rawLog.response().statusCode()
        );

        return new GeneratedStubBundle(generatedStub, sanitizedRequest.summary(),
                sanitizedResponse.summary(), confidence);
    }

    private record GeneratedStubBundle(StubDefinition stub, SanitizationSummary requestSummary,
                                        SanitizationSummary responseSummary, double confidence) {}

    /**
     * Newest-first (matches TrafficLogger's convention and the UI's expectation), optionally
     * capped via {@code limit}. Previously returned raw insertion order (oldest-first) with no
     * limit — inconsistent with the Traffic page and a growing payload on every poll.
     */
    public List<TrafficRecord> getHistory(int limit) {
        List<TrafficRecord> snapshot = List.copyOf(recordingHistory);
        List<TrafficRecord> newestFirst = new java.util.ArrayList<>(snapshot);
        java.util.Collections.reverse(newestFirst);
        return newestFirst.size() > limit ? newestFirst.subList(0, limit) : newestFirst;
    }

    public List<TrafficRecord> getHistory() {
        return getHistory(MAX_HISTORY);
    }

    /**
     * Approve a pending record: regenerate the stub from the record's raw traffic and actually
     * register it in StubRouter.
     *
     * Confirmed bug fixed here: this used to only flip the record's status label to
     * AUTO_PUBLISHED and log a message — it never called stubRouter.register*() at all, and
     * generatedStubId was never set for PENDING_REVIEW records in the first place (only the
     * AUTO_PUBLISHED branch of ingest() sets it). So clicking "Approve" looked like it worked
     * (badge turned green) but the stub was NEVER actually matchable by /api/v1/virtual and
     * NEVER appeared in Stub Management — a human approving a stub published nothing.
     * PENDING_REVIEW never persists the generated StubDefinition object itself (only the raw,
     * unsanitized rawLog), so we re-run the same sanitize+generate pipeline ingest() used and
     * publish unconditionally — a human already reviewed it, so the confidence/critical-path
     * gate that applies at ingest time doesn't apply again here.
     */
    public void approveRecord(String recordId) {
        TrafficRecord record = recordingHistory.stream()
                .filter(r -> r.id().equals(recordId))
                .findFirst()
                .orElse(null);

        if (record == null) {
            log.warn("Record not found: {}", recordId);
            return;
        }

        if (record.status() == TrafficRecord.Status.PENDING_REVIEW) {
            StubDefinition stub = generateStub(record.rawLog()).stub();
            List<String> replacedStubIds = stubRouter.registerOrReplaceByRequestShape(stub);
            record.setGeneratedStubId(stub.stubId());
            record.setStatus(TrafficRecord.Status.AUTO_PUBLISHED);
            if (replacedStubIds.isEmpty()) {
                log.info("Record {} approved — published stub {}", recordId, stub.stubId());
            } else {
                log.info("Record {} approved — published stub {}, replacing identical-matcher stub(s): {}",
                        recordId, stub.stubId(), replacedStubIds);
            }
        }
    }

    /**
     * Reject a pending record: update status to rejected.
     */
    public void rejectRecord(String recordId) {
        TrafficRecord record = recordingHistory.stream()
                .filter(r -> r.id().equals(recordId))
                .findFirst()
                .orElse(null);

        if (record == null) {
            log.warn("Record not found: {}", recordId);
            return;
        }

        if (record.status() == TrafficRecord.Status.PENDING_REVIEW) {
            record.setStatus(TrafficRecord.Status.REJECTED);
            log.info("Record {} rejected", recordId);
        }
    }

    private void trimToCapacity() {
        while (recordingHistory.size() > MAX_HISTORY) {
            try {
                recordingHistory.remove(0);
            } catch (IndexOutOfBoundsException e) {
                break; // another thread already trimmed past this point
            }
        }
    }
}
