package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.model.RawTrafficLog;
import com.virtualization.servicevirtualizationplatform.model.StubGenerationResult;
import com.virtualization.servicevirtualizationplatform.model.TrafficRecord;
import com.virtualization.servicevirtualizationplatform.recording.RecordingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<List<TrafficRecord>> getHistory() {
        return ResponseEntity.ok(recordingService.getHistory());
    }
}
