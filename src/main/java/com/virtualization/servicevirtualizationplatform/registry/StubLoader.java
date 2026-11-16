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
     * Ingests a sample loan application with PCI data at startup.
     * Confidence scores ~0.86 due to unknown fields → PENDING_REVIEW.
     */
    private void seedPendingReviewRecord() {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("applicant", "John Smith");
            requestBody.put("ssn", "123-45-6789");
            requestBody.put("credit_card", "4111111111111111");
            requestBody.put("cvv", "456");
            requestBody.put("loan_amount", 25000.00);
            requestBody.put("term_months", 36);
            requestBody.put("purpose", "HOME_IMPROVEMENT");

            Map<String, Object> responseBody = new LinkedHashMap<>();
            responseBody.put("loan_id", "LOAN-5001");
            responseBody.put("applicant", "John Smith");
            responseBody.put("loan_amount", 25000.00);
            responseBody.put("term_months", 36);
            responseBody.put("purpose", "HOME_IMPROVEMENT");
            responseBody.put("status", "APPROVED");
            responseBody.put("monthly_payment", 740.12);

            RawTrafficLog seedLog = new RawTrafficLog(
                    new RawTrafficLog.RequestData("HTTP", "POST", "/api/v1/loans",
                            Map.of("Content-Type", "application/json"), requestBody),
                    new RawTrafficLog.ResponseData(201,
                            Map.of("Content-Type", "application/json"), responseBody)
            );

            StubGenerationResult result = recordingService.ingest(seedLog);
            log.info("Seeded recording record: id={}, status={}, confidence={}",
                    result.recordId(), result.status(), result.confidence());
        } catch (Exception e) {
            log.warn("Failed to seed pending review record: {}", e.getMessage());
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
