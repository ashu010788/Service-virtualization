package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.engine.StubMatchingEngine;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Unified virtual endpoint for all three protocols: HTTP, GRPC, KAFKA.
 *
 * POST /api/v1/virtual  — assignment snake_case contract
 *
 * - session_id is MANDATORY; missing/blank → 400
 * - For HTTP, this endpoint is an alternative to the transparent proxy (/**).
 *   Both coexist:  direct URL hits → ForwardingProxyController
 *                  /api/v1/virtual → this controller
 * - Response is always HTTP 200 from the transport layer; the stub's
 *   logical status_code is embedded in the response body.
 */
@RestController
@RequestMapping("/api/v1")
public class VirtualController {

    private final StubMatchingEngine engine;

    public VirtualController(StubMatchingEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/virtual")
    public ResponseEntity<?> handleVirtualRequest(@RequestBody VirtualRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "session_id is required",
                    "hint",  "Every virtual request must include a non-blank session_id for state isolation"
            ));
        }
        VirtualResponse response = engine.matchAndExecute(request);
        return ResponseEntity.ok(response);
    }
}
