package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.model.RawTrafficLog;
import com.virtualization.servicevirtualizationplatform.model.StubGenerationResult;
import com.virtualization.servicevirtualizationplatform.model.TrafficRecord;
import com.virtualization.servicevirtualizationplatform.recording.RecordingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recording")
public class RecordingController {

    private final RecordingService recordingService;

    public RecordingController(RecordingService recordingService) {
        this.recordingService = recordingService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<StubGenerationResult> ingest(@RequestBody RawTrafficLog log) {
        StubGenerationResult result = recordingService.ingest(log);
        // RecordingService may set status = "ERROR: <message>" for truly invalid inputs
        if (result.status() != null && result.status().startsWith("ERROR")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/ingest/batch")
    public ResponseEntity<List<StubGenerationResult>> ingestBatch(@RequestBody List<RawTrafficLog> logs) {
        List<StubGenerationResult> results = logs.stream()
                .map(recordingService::ingest)
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    @GetMapping("/history")
    public ResponseEntity<List<TrafficRecord>> getHistory(
            @RequestParam(required = false, defaultValue = "200") int limit) {
        return ResponseEntity.ok(recordingService.getHistory(limit));
    }

    @PostMapping("/{recordId}/approve")
    public ResponseEntity<Map<String, String>> approveRecord(@PathVariable String recordId) {
        recordingService.approveRecord(recordId);
        return ResponseEntity.ok(Map.of("message", "Record approved and stub published", "recordId", recordId));
    }

    @PostMapping("/{recordId}/reject")
    public ResponseEntity<Map<String, String>> rejectRecord(@PathVariable String recordId) {
        recordingService.rejectRecord(recordId);
        return ResponseEntity.ok(Map.of("message", "Record rejected", "recordId", recordId));
    }
}
