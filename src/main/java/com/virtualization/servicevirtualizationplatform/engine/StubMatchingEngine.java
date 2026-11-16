package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.engine.async.AsyncEventScheduler;
import com.virtualization.servicevirtualizationplatform.engine.drift.DriftSimulator;
import com.virtualization.servicevirtualizationplatform.engine.matcher.BodyMatcherEvaluator;
import com.virtualization.servicevirtualizationplatform.engine.matcher.HeaderMatcherEvaluator;
import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.engine.template.TemplateContext;
import com.virtualization.servicevirtualizationplatform.engine.template.TemplateResolver;
import com.virtualization.servicevirtualizationplatform.engine.traffic.TrafficLogger;
import com.virtualization.servicevirtualizationplatform.model.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core orchestrator — matches incoming requests to stubs and executes them.
 *
 * This is a PURE FUNCTION with no shared mutable state.
 * All concurrency is delegated to components that handle their own thread safety:
 *   - StubRouter (Copy-on-Write Trie)
 *   - SessionStateStore (ConcurrentHashMap)
 *   - DriftSimulator (volatile config)
 *   - TrafficLogger (ring buffer with AtomicInteger)
 *
 * No locks needed in this class.
 */
@Service
public class StubMatchingEngine {

    private final StubRouter stubRouter;
    private final SessionStateStore stateStore;
    private final TemplateResolver templateResolver;
    private final DriftSimulator driftSimulator;
    private final AsyncEventScheduler asyncEventScheduler;
    private final TrafficLogger trafficLogger;

    public StubMatchingEngine(StubRouter stubRouter,
                               SessionStateStore stateStore,
                               TemplateResolver templateResolver,
                               DriftSimulator driftSimulator,
                               AsyncEventScheduler asyncEventScheduler,
                               TrafficLogger trafficLogger) {
        this.stubRouter = stubRouter;
        this.stateStore = stateStore;
        this.templateResolver = templateResolver;
        this.driftSimulator = driftSimulator;
        this.asyncEventScheduler = asyncEventScheduler;
        this.trafficLogger = trafficLogger;
    }

    public VirtualResponse matchAndExecute(VirtualRequest request) {
        long startNanos = System.nanoTime();
        List<String> matchedRules = new ArrayList<>();

        // STEP 1: Find candidates via Trie (HTTP) or HashMap (gRPC/Kafka)
        MatchCandidates match = stubRouter.findCandidates(
                request.protocol(), request.method(), request.path());

        // STEP 2: Evaluate each candidate
        for (StubDefinition stub : match.stubs()) {

            matchedRules.clear();
            matchedRules.add("path_pattern");

            // 2b: Header matching
            if (stub.hasHeaderConditions()) {
                if (!HeaderMatcherEvaluator.matches(
                        stub.requestMatcher().headers(), request.headers())) {
                    continue;
                }
                matchedRules.add("header_match");
            }

            // 2c: Body JSONPath matching
            if (stub.hasBodyConditions()) {
                if (!BodyMatcherEvaluator.matches(
                        stub.requestMatcher().bodyJsonPath(), request.body())) {
                    continue;
                }
                matchedRules.add("jsonpath_body_check");
            }

            // 2d: State precondition (READ action)
            TemplateContext ctx = new TemplateContext(request, match.pathVariables());
            Map<String, Object> stateResult = null;

            if (stub.hasStateRead()) {
                String key = templateResolver.resolveToString(
                        stub.stateAction().keyExpr(), ctx);
                stateResult = stateStore.get(
                        request.sessionId(), stub.stateAction().collection(), key);
                if (stateResult == null) {
                    continue; // State not found → stub doesn't match → try next
                }
                matchedRules.add("state_lookup");
                ctx.setStateResult(stateResult);
            }

            // 2e: Enabled check — deliberately AFTER all content/state matching, not before.
            // Confirmed behavior change: a disabled stub whose conditions DO match this request
            // must NOT silently fall through to the next, less-specific candidate (e.g. a broad
            // catch-all stub). Specificity ordering means this IS "the" stub for this exact
            // request shape; if an operator has intentionally disabled it, the request should
            // get a clear "this is disabled" signal, not a quietly substituted different
            // response that masks the fact anything changed. A disabled stub whose conditions
            // DON'T match still falls through as before (the `continue`s above already handle
            // that — this check never even runs for them).
            if (!stub.isEnabled()) {
                double disabledLatencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                VirtualResponse disabledResponse = new VirtualResponse(
                        stub.stubId(), 404,
                        Map.of("Content-Type", "application/json"),
                        Map.of("error", "Matched stub is disabled",
                                "stubId", stub.stubId(),
                                "path", request.path(),
                                "method", request.method()),
                        false, disabledLatencyMs, List.copyOf(matchedRules)
                );
                trafficLogger.log(request, disabledResponse);
                return disabledResponse;
            }

            // STEP 3: MATCH FOUND — resolve response template
            Map<String, Object> responseBody;
            Object bodyTemplate = stub.responseTemplate().body();
            if (bodyTemplate instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = (Map<String, Object>) bodyTemplate;
                responseBody = templateResolver.resolveBody(bodyMap, ctx);
            } else if (bodyTemplate instanceof String s) {
                // Single placeholder like "{{state.result}}" → resolves to a Map
                Object resolved = templateResolver.resolveValue(s, ctx);
                if (resolved instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resolvedMap = (Map<String, Object>) resolved;
                    responseBody = resolvedMap;
                } else {
                    responseBody = Map.of("_value", resolved != null ? resolved : "");
                }
            } else {
                responseBody = Map.of();
            }

            // STEP 4: Execute state WRITE if present
            boolean stateMutated = false;
            if (stub.hasStateWrite()) {
                ctx.setResponseBody(responseBody);
                String key = templateResolver.resolveToString(
                        stub.stateAction().keyExpr(), ctx);
                Object value = templateResolver.resolveValue(
                        stub.stateAction().valueExpr(), ctx);
                stateStore.put(request.sessionId(), stub.stateAction().collection(), key, value);
                stateMutated = true;
            }

            // Execute state DELETE if present
            if (stub.hasStateDelete()) {
                ctx.setResponseBody(responseBody);
                String key = templateResolver.resolveToString(
                        stub.stateAction().keyExpr(), ctx);
                stateStore.delete(request.sessionId(), stub.stateAction().collection(), key);
                stateMutated = true;
            }

            // STEP 5: Schedule async events if present
            if (stub.hasAsyncEvents()) {
                ctx.setResponseBody(responseBody);
                asyncEventScheduler.scheduleEvents(
                        request.sessionId(), stub.asyncEvents(), ctx);
            }

            // Record match stats (governance)
            stub.recordMatch();

            double latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;

            VirtualResponse response = new VirtualResponse(
                    stub.stubId(),
                    stub.responseTemplate().statusCode(),
                    stub.responseTemplate().headers(),
                    responseBody,
                    stateMutated,
                    latencyMs,
                    List.copyOf(matchedRules)
            );

            // STEP 6: Apply drift (latency/jitter/error injection)
            response = driftSimulator.applyDrift(response, request.path(), request.method(), request.protocol());

            // STEP 7: Log to traffic ring buffer
            trafficLogger.log(request, response);

            return response;
        }

        // STEP 8: No match found
        double latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        VirtualResponse notFound = new VirtualResponse(
                null, 404,
                Map.of("Content-Type", "application/json"),
                Map.of("error", "No matching stub found",
                        "path", request.path(),
                        "method", request.method()),
                false, latencyMs, List.of()
        );

        trafficLogger.log(request, notFound);
        return notFound;
    }
}
