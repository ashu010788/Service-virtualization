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
        // Build StubDefinition from raw map (Jackson can't deserialize our custom class directly)
        String stubId = (String) raw.get("stubId");
        int priority = raw.containsKey("priority") ? ((Number) raw.get("priority")).intValue() : 10;

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

        // Parse optional expiresAt for TTL-based governance
        java.time.Instant expiresAt = null;
        if (raw.containsKey("expiresAt") && raw.get("expiresAt") != null) {
            expiresAt = java.time.Instant.parse((String) raw.get("expiresAt"));
        }

        StubDefinition stub = new StubDefinition(stubId, priority, rm, rt, null, null,
                "manual", (String) raw.getOrDefault("apiVersion", null), expiresAt);
        List<Map<String, String>> conflicts = registry.checkConflicts(stub);
        registry.register(stub);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "stubId", stub.stubId(),
                "status", "registered",
                "conflicts", conflicts
        ));
    }

    @GetMapping
    public ResponseEntity<List<StubDefinition>> searchStubs(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "matchCount") String sortBy,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(searchService.search(query, protocol, method,
                source, enabled, sortBy, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StubDefinition> getStub(@PathVariable String id) {
        StubDefinition stub = registry.getById(id);
        if (stub == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stub);
    }

    @PutMapping("/{id}")
    public ResponseEntity<StubDefinition> updateStub(@PathVariable String id,
                                                      @RequestBody StubDefinition stub) {
        if (registry.getById(id) == null) return ResponseEntity.notFound().build();
        registry.remove(id);
        registry.register(stub);
        return ResponseEntity.ok(stub);
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStub(@PathVariable String id) {
        if (registry.getById(id) == null) return ResponseEntity.notFound().build();
        registry.remove(id);
        return ResponseEntity.noContent().build();
    }
}
