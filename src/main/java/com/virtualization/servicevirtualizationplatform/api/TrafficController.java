package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.engine.traffic.TrafficLogger;
import com.virtualization.servicevirtualizationplatform.model.TrafficEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/traffic")
public class TrafficController {

    private final TrafficLogger trafficLogger;

    public TrafficController(TrafficLogger trafficLogger) {
        this.trafficLogger = trafficLogger;
    }

    @GetMapping("/recent")
    public ResponseEntity<List<TrafficEntry>> getRecent(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(trafficLogger.getRecentEntries(limit));
    }

    @GetMapping("/search")
    public ResponseEntity<List<TrafficEntry>> search(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String stubId,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(trafficLogger.search(sessionId, method, path,
                stubId, statusCode, limit));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> clearTraffic() {
        trafficLogger.clear();
        return ResponseEntity.ok(Map.of("message", "Traffic log cleared"));
    }
}
