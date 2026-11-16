package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.proxy.OutboundForwardingConfig;
import com.virtualization.servicevirtualizationplatform.proxy.OutboundForwardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime configuration & stats for the Live Outbound Forwarding Proxy.
 *
 * New controller — does NOT touch RecordingController, RecordingService, or any
 * common code. Uses only the proxy-layer classes (OutboundForwardingConfig,
 * OutboundForwardingService).
 *
 * Endpoints:
 *   GET  /api/v1/proxy/config  — current proxy configuration & status
 *   PUT  /api/v1/proxy/config  — update base-url and/or record-on-miss at runtime
 *   GET  /api/v1/proxy/stats   — forwarding stats (counts + recent log)
 */
@RestController
@RequestMapping("/api/v1/proxy")
public class ProxyConfigController {

    private final OutboundForwardingConfig config;
    private final OutboundForwardingService forwardingService;

    public ProxyConfigController(OutboundForwardingConfig config,
                                  OutboundForwardingService forwardingService) {
        this.config = config;
        this.forwardingService = forwardingService;
    }

    /**
     * GET /api/v1/proxy/config — returns current proxy configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("baseUrl", config.getBaseUrl());
        result.put("recordOnMiss", config.isRecordOnMiss());
        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/v1/proxy/config — update proxy settings at runtime.
     *
     * Accepts JSON with optional fields:
     *   { "baseUrl": "http://real-service:8080", "recordOnMiss": true }
     *
     * Only provided fields are updated; omitted fields keep their current value.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> updates) {
        if (updates.containsKey("baseUrl")) {
            config.setBaseUrl(String.valueOf(updates.get("baseUrl")));
        }
        if (updates.containsKey("recordOnMiss")) {
            config.setRecordOnMiss(Boolean.parseBoolean(String.valueOf(updates.get("recordOnMiss"))));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Proxy configuration updated");
        result.put("enabled", config.isEnabled());
        result.put("baseUrl", config.getBaseUrl());
        result.put("recordOnMiss", config.isRecordOnMiss());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/proxy/stats — forwarding stats + recent forward log.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false, defaultValue = "20") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("baseUrl", config.getBaseUrl());
        result.put("totalForwarded", forwardingService.getTotalForwarded());
        result.put("totalRecorded", forwardingService.getTotalRecorded());
        result.put("totalUpstreamErrors", forwardingService.getTotalUpstreamErrors());
        result.put("totalConnectionFailures", forwardingService.getTotalConnectionFailures());
        result.put("recentForwards", forwardingService.getRecentForwards(limit));
        return ResponseEntity.ok(result);
    }
}
