package com.virtualization.servicevirtualizationplatform.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.*;
import com.virtualization.servicevirtualizationplatform.recording.RecordingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StubLoader {

    private static final Logger log = LoggerFactory.getLogger(StubLoader.class);

    private final StubRouter stubRouter;
    private final ObjectMapper objectMapper;
    private final RecordingService recordingService;

    public StubLoader(StubRouter stubRouter, ObjectMapper objectMapper,
                      RecordingService recordingService) {
        this.stubRouter = stubRouter;
        this.objectMapper = objectMapper;
        this.recordingService = recordingService;
    }

    @PostConstruct
    public void loadSeedStubs() {
        try {
            InputStream is = getClass().getResourceAsStream("/seed_stubs.json");
            if (is == null) {
                log.warn("No seed_stubs.json found on classpath — starting with empty stub registry");
                return;
            }

            List<Map<String, Object>> rawStubs = objectMapper.readValue(is, new TypeReference<>() {});

            for (Map<String, Object> raw : rawStubs) {
                try {
                    StubDefinition stub = parseStub(raw);
                    stubRouter.registerDirect(stub); // startup — no readers yet, skip CoW
                    log.info("Loaded seed stub: {} ({} {} {})",
                            stub.stubId(), stub.requestMatcher().protocol(),
                            stub.requestMatcher().method(), stub.requestMatcher().pathPattern());
                } catch (Exception e) {
                    log.error("Failed to parse seed stub: {}", e.getMessage());
                }
            }

            log.info("Loaded {} seed stubs from seed_stubs.json", stubRouter.totalSize());
        } catch (Exception e) {
            log.error("Failed to load seed_stubs.json: {}", e.getMessage());
        }

        // Seed a PENDING_REVIEW record so the Pending Review page has content on first load.
        // The loan request contains PCI data (SSN, credit card, CVV) + an UNKNOWN field (purpose),
        // which drops confidence to ~0.86 (between 0.70-0.90) → naturally lands in PENDING_REVIEW.
        // No CriticalPathClassifier override needed.
        seedPendingReviewRecord();
    }

    /**
     * Loads pending review seed records from seed_pending_reviews.json and ingests them.
     * Each record's confidence score determines whether it lands in PENDING_REVIEW.
     */
    @SuppressWarnings("unchecked")
    private void seedPendingReviewRecord() {
        try {
            InputStream is = getClass().getResourceAsStream("/seed_pending_reviews.json");
            if (is == null) {
                log.info("No seed_pending_reviews.json found — skipping pending review seeding");
                return;
            }

            List<Map<String, Object>> records = objectMapper.readValue(is, new TypeReference<>() {});

            for (Map<String, Object> raw : records) {
                try {
                    Map<String, Object> reqRaw = (Map<String, Object>) raw.get("request");
                    Map<String, Object> resRaw = (Map<String, Object>) raw.get("response");

                    RawTrafficLog seedLog = new RawTrafficLog(
                            new RawTrafficLog.RequestData(
                                    (String) reqRaw.getOrDefault("protocol", "HTTP"),
                                    (String) reqRaw.get("method"),
                                    (String) reqRaw.get("path"),
                                    (Map<String, String>) reqRaw.getOrDefault("headers", Map.of()),
                                    (Map<String, Object>) reqRaw.getOrDefault("body", Map.of())
                            ),
                            new RawTrafficLog.ResponseData(
                                    ((Number) resRaw.get("statusCode")).intValue(),
                                    (Map<String, String>) resRaw.getOrDefault("headers", Map.of()),
                                    (Map<String, Object>) resRaw.getOrDefault("body", Map.of())
                            )
                    );

                    StubGenerationResult result = recordingService.ingest(seedLog);
                    log.info("Seeded recording record: id={}, status={}, confidence={}",
                            result.recordId(), result.status(), result.confidence());
                } catch (Exception e) {
                    log.warn("Failed to parse seed pending review entry: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load seed_pending_reviews.json: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private StubDefinition parseStub(Map<String, Object> raw) {
        String stubId = (String) raw.get("stubId");
        // priority field removed — ordering is automatic (specificity = more conditions first)

        Map<String, Object> rmRaw = (Map<String, Object>) raw.get("requestMatcher");
        RequestMatcher rm = new RequestMatcher(
                (String) rmRaw.getOrDefault("protocol", "HTTP"),
                (String) rmRaw.get("method"),
                (String) rmRaw.get("pathPattern"),
                (Map<String, String>) rmRaw.getOrDefault("headers", Map.of()),
                (Map<String, String>) rmRaw.getOrDefault("bodyJsonPath", Map.of())
        );

        Map<String, Object> rtRaw = (Map<String, Object>) raw.get("responseTemplate");
        Object bodyValue = rtRaw.getOrDefault("body", Map.of());
        // body can be a Map (normal) or String (single placeholder like "{{state.result}}")
        ResponseTemplate rt = new ResponseTemplate(
                ((Number) rtRaw.get("statusCode")).intValue(),
                (Map<String, String>) rtRaw.getOrDefault("headers", Map.of("Content-Type", "application/json")),
                bodyValue
        );

        StateAction sa = null;
        if (raw.containsKey("stateAction") && raw.get("stateAction") != null) {
            Map<String, Object> saRaw = (Map<String, Object>) raw.get("stateAction");
            sa = new StateAction(
                    StateOp.valueOf(((String) saRaw.get("op")).toUpperCase()),
                    (String) saRaw.get("collection"),
                    (String) saRaw.get("keyExpr"),
                    saRaw.get("valueExpr")
            );
        }

        // Parse async events if present
        List<AsyncEventConfig> asyncEvents = null;
        if (raw.containsKey("asyncEvents") && raw.get("asyncEvents") != null) {
            List<Map<String, Object>> aeList = (List<Map<String, Object>>) raw.get("asyncEvents");
            asyncEvents = aeList.stream().map(ae -> new AsyncEventConfig(
                    (String) ae.getOrDefault("targetProtocol", "KAFKA"),
                    (String) ae.get("targetTopic"),
                    ((Number) ae.getOrDefault("delayMs", 0)).longValue(),
                    ((Number) ae.getOrDefault("jitterMs", 0)).longValue(),
                    (Map<String, Object>) ae.get("eventBody"),
                    ae.containsKey("stateUpdate") ? parseStateAction((Map<String, Object>) ae.get("stateUpdate")) : null
            )).toList();
        }

        return new StubDefinition(stubId, rm, rt, sa, asyncEvents,
                "seed", (String) raw.getOrDefault("apiVersion", null), null);
    }

    private StateAction parseStateAction(Map<String, Object> raw) {
        return new StateAction(
                StateOp.valueOf(((String) raw.get("op")).toUpperCase()),
                (String) raw.get("collection"),
                (String) raw.get("keyExpr"),
                raw.get("valueExpr")
        );
    }
}
