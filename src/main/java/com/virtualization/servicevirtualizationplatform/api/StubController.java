package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.model.*;
import com.virtualization.servicevirtualizationplatform.registry.StubRegistry;
import com.virtualization.servicevirtualizationplatform.registry.StubSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stubs")
public class StubController {

    private final StubRegistry registry;
    private final StubSearchService searchService;

    public StubController(StubRegistry registry, StubSearchService searchService) {
        this.registry = registry;
        this.searchService = searchService;
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> createStub(@RequestBody Map<String, Object> raw) {
        StubDefinition stub = parseStubFromRawMap(raw);
        List<Map<String, String>> conflicts = registry.checkConflicts(stub);
        List<String> replacedStubIds = registry.registerOrReplace(stub);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "stubId", stub.stubId(),
                "status", replacedStubIds.isEmpty() ? "registered" : "replaced",
                "replacedStubIds", replacedStubIds,
                "conflicts", conflicts
        ));
    }

    /**
     * StubDefinition cannot be deserialized directly by Jackson (it's a plain class with
     * final fields and no no-arg constructor or @JsonCreator) — both the create and full-update
     * endpoints accept a raw Map and build the StubDefinition by hand instead. Confirmed bug
     * fixed here: PUT previously bound @RequestBody StubDefinition directly and threw a 500
     * (HttpMessageConversionException) on every call, since nothing could ever construct it.
     */
    @SuppressWarnings("unchecked")
    private StubDefinition parseStubFromRawMap(Map<String, Object> raw) {
        String stubId = (String) raw.get("stubId");

        Map<String, Object> rmRaw = (Map<String, Object>) raw.getOrDefault("requestMatcher", Map.of());
        RequestMatcher rm = new RequestMatcher(
                (String) rmRaw.getOrDefault("protocol", "HTTP"),
                (String) rmRaw.getOrDefault("method", "GET"),
                (String) rmRaw.getOrDefault("pathPattern", "/"),
                (Map<String, String>) rmRaw.getOrDefault("headers", Map.of()),
                (Map<String, String>) rmRaw.getOrDefault("bodyJsonPath", Map.of())
        );

        Map<String, Object> rtRaw = (Map<String, Object>) raw.getOrDefault("responseTemplate", Map.of());
        ResponseTemplate rt = new ResponseTemplate(
                rtRaw.containsKey("statusCode") ? ((Number) rtRaw.get("statusCode")).intValue() : 200,
                (Map<String, String>) rtRaw.getOrDefault("headers", Map.of("Content-Type", "application/json")),
                rtRaw.getOrDefault("body", Map.of())
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

        java.time.Instant expiresAt = null;
        if (raw.containsKey("expiresAt") && raw.get("expiresAt") != null) {
            expiresAt = java.time.Instant.parse((String) raw.get("expiresAt"));
        }

        return new StubDefinition(stubId, rm, rt, sa, null,
                "manual", (String) raw.getOrDefault("apiVersion", null), expiresAt);
    }

    @GetMapping
    public ResponseEntity<List<StubDefinition>> searchStubs(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "matchCount") String sortBy,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(searchService.search(query, protocol, method, path,
                source, enabled, sortBy, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StubDefinition> getStub(@PathVariable String id) {
        StubDefinition stub = registry.getById(id);
        if (stub == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stub);
    }

    @PutMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> updateStub(@PathVariable String id,
                                                           @RequestBody Map<String, Object> raw) {
        if (registry.getById(id) == null) return ResponseEntity.notFound().build();
        raw.putIfAbsent("stubId", id);
        StubDefinition stub = parseStubFromRawMap(raw);
        registry.remove(id);
        registry.register(stub);
        return ResponseEntity.ok(Map.of(
                "stubId", stub.stubId(),
                "status", "updated"
        ));
    }

    @PatchMapping("/{id}/response")
    public ResponseEntity<Map<String, Object>> updateResponse(@PathVariable String id,
                                                                @RequestBody ResponseTemplate newResponse) {
        StubDefinition stub = registry.getById(id);
        if (stub == null) return ResponseEntity.notFound().build();
        registry.updateResponseTemplate(id, newResponse);
        return ResponseEntity.ok(Map.of("stubId", id, "message", "Response template updated"));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleStub(@PathVariable String id) {
        StubDefinition stub = registry.getById(id);
        if (stub == null) return ResponseEntity.notFound().build();
        registry.toggleStub(id);
        return ResponseEntity.ok(Map.of(
                "stubId", id,
                "enabled", stub.isEnabled(),
                "message", stub.isEnabled() ? "Stub enabled" : "Stub disabled"
        ));
    }

    /**
     * Sets or clears a stub's governance TTL. {expiresAt: "<ISO-8601 instant>"} sets it;
     * {expiresAt: null} (or omitted/blank) clears it, making the stub non-expiring again.
     * StubGovernanceScheduler's hourly sweep is what actually acts on this value.
     */
    @PatchMapping("/{id}/expiry")
    public ResponseEntity<Map<String, Object>> updateExpiry(@PathVariable String id,
                                                             @RequestBody Map<String, String> body) {
        if (registry.getById(id) == null) return ResponseEntity.notFound().build();

        String raw = body.get("expiresAt");
        java.time.Instant expiresAt;
        try {
            expiresAt = (raw == null || raw.isBlank()) ? null : java.time.Instant.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "expiresAt must be a valid ISO-8601 instant, e.g. 2026-12-01T00:00:00Z"));
        }

        registry.updateExpiry(id, expiresAt);
        return ResponseEntity.ok(Map.of(
                "stubId", id,
                "expiresAt", expiresAt == null ? "none" : expiresAt.toString(),
                "message", expiresAt == null ? "Expiry cleared" : "Expiry updated"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStub(@PathVariable String id) {
        if (registry.getById(id) == null) return ResponseEntity.notFound().build();
        registry.remove(id);
        return ResponseEntity.noContent().build();
    }
}
