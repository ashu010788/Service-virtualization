package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.engine.drift.DriftSimulator;
import com.virtualization.servicevirtualizationplatform.model.DriftConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/drift")
public class DriftController {

    private final DriftSimulator driftSimulator;

    public DriftController(DriftSimulator driftSimulator) {
        this.driftSimulator = driftSimulator;
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> setGlobalConfig(@RequestBody DriftConfig config) {
        driftSimulator.configure(config);
        return ResponseEntity.ok(Map.of("message", "Drift configured", "config", config));
    }

    /**
     * Route is a QUERY PARAM, not a path variable — confirmed bug fixed here: a real API route
     * always contains slashes ("/api/v1/accounts"), and Spring's {route} path-variable matching
     * only captures a single path segment. The literal route was being swallowed by
     * ForwardingProxyController's catch-all /** mapping (404, "Not a virtual endpoint"), and the
     * UI's own encodeURIComponent(route) workaround produced a %2F-encoded slash that Tomcat
     * rejects outright (400 Bad Request) unless ALLOW_ENCODED_SLASH is explicitly enabled. A
     * query parameter has no such restriction.
     */
    @PostMapping("/config/route")
    public ResponseEntity<Map<String, Object>> setRouteConfig(@RequestParam String route,
                                                                @RequestBody DriftConfig config) {
        driftSimulator.configureRoute(route, config);
        return ResponseEntity.ok(Map.of("message", "Route drift configured",
                "route", route, "config", config));
    }

    @DeleteMapping("/config/route")
    public ResponseEntity<Map<String, Object>> deleteRouteConfig(@RequestParam String route) {
        boolean removed = driftSimulator.removeRouteConfig(route);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Route drift override removed", "route", route));
    }

    /**
     * Protocol-level override: applies to every request of the given protocol (HTTP/GRPC/KAFKA)
     * that does NOT have a more specific exact-route override. Precedence: route > protocol > global.
     */
    @PostMapping("/config/protocol")
    public ResponseEntity<Map<String, Object>> setProtocolConfig(@RequestParam String protocol,
                                                                   @RequestBody DriftConfig config) {
        driftSimulator.configureProtocol(protocol, config);
        return ResponseEntity.ok(Map.of("message", "Protocol drift configured",
                "protocol", protocol.toUpperCase(), "config", config));
    }

    @DeleteMapping("/config/protocol")
    public ResponseEntity<Map<String, Object>> deleteProtocolConfig(@RequestParam String protocol) {
        boolean removed = driftSimulator.removeProtocolConfig(protocol);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Protocol drift override removed",
                "protocol", protocol.toUpperCase()));
    }

    /**
     * Method/Topic override: targets a single gRPC method name or Kafka topic exactly.
     * This exists because HTTP has a real path to key a route override on, but GRPC/KAFKA
     * requests carry their identity in VirtualRequest.method() instead (e.g. a Kafka topic
     * like "payment-events") — a route override can never match those. Precedence:
     * route (HTTP path) &gt; method/topic &gt; protocol &gt; global.
     */
    @PostMapping("/config/method")
    public ResponseEntity<Map<String, Object>> setMethodConfig(@RequestParam String method,
                                                                 @RequestBody DriftConfig config) {
        driftSimulator.configureMethod(method, config);
        return ResponseEntity.ok(Map.of("message", "Method/topic drift configured",
                "method", method, "config", config));
    }

    @DeleteMapping("/config/method")
    public ResponseEntity<Map<String, Object>> deleteMethodConfig(@RequestParam String method) {
        boolean removed = driftSimulator.removeMethodConfig(method);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Method/topic drift override removed", "method", method));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "global", driftSimulator.getGlobalConfig(),
                "protocols", driftSimulator.getProtocolConfigs(),
                "routes", driftSimulator.getRouteConfigs(),
                "methods", driftSimulator.getMethodConfigs()
        ));
    }

    @DeleteMapping("/config")
    public ResponseEntity<Map<String, String>> resetConfig() {
        driftSimulator.configure(DriftConfig.NONE);
        driftSimulator.clearRouteConfigs();
        driftSimulator.clearProtocolConfigs();
        driftSimulator.clearMethodConfigs();
        return ResponseEntity.ok(Map.of("message", "Drift reset to zero"));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(driftSimulator.getMetrics().getSnapshot());
    }

    /**
     * "Would drift actually apply to this request?" diagnostic — pass whichever of
     * route/method/protocol you care about (all optional) and get back which override tier
     * (ROUTE &gt; METHOD &gt; PROTOCOL &gt; GLOBAL &gt; NONE) would win and the resolved config.
     * Read-only, no side effects.
     */
    @GetMapping("/check")
    public ResponseEntity<DriftSimulator.DriftResolution> checkDrift(
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String protocol) {
        return ResponseEntity.ok(driftSimulator.resolve(route, method, protocol));
    }
}
