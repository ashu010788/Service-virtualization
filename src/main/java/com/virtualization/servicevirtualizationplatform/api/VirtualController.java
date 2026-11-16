package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.engine.StubMatchingEngine;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class VirtualController {

    private final StubMatchingEngine engine;

    public VirtualController(StubMatchingEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/virtual")
    public ResponseEntity<VirtualResponse> handleVirtualRequest(@RequestBody VirtualRequest request) {
        VirtualResponse response = engine.matchAndExecute(request);
        return ResponseEntity.status(response.statusCode() == 404 ? 200 : 200).body(response);
    }
}
