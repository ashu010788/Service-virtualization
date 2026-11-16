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

    @PostMapping("/config/{route}")
    public ResponseEntity<Map<String, Object>> setRouteConfig(@PathVariable String route,
                                                                @RequestBody DriftConfig config) {
        driftSimulator.configureRoute(route, config);
        return ResponseEntity.ok(Map.of("message", "Route drift configured",
                "route", route, "config", config));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "global", driftSimulator.getGlobalConfig(),
                "routes", driftSimulator.getRouteConfigs()
        ));
    }

    @DeleteMapping("/config")
    public ResponseEntity<Map<String, String>> resetConfig() {
        driftSimulator.configure(DriftConfig.NONE);
        driftSimulator.clearRouteConfigs();
        return ResponseEntity.ok(Map.of("message", "Drift reset to zero"));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(driftSimulator.getMetrics().getSnapshot());
    }
}
