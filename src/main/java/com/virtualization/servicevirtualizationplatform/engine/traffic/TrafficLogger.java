package com.virtualization.servicevirtualizationplatform.engine.traffic;

import com.virtualization.servicevirtualizationplatform.model.TrafficEntry;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free ring buffer for traffic logging.
 * Fixed size (10,000 entries). Oldest entries auto-overwritten.
 * ~0.001ms per log call — negligible hot-path impact.
 */
@Service
public class TrafficLogger {

    private static final int BUFFER_SIZE = 10_000;

    private final TrafficEntry[] buffer = new TrafficEntry[BUFFER_SIZE];
    private final AtomicInteger writeIndex = new AtomicInteger(0);
    private final AtomicLong totalLogged = new AtomicLong(0);

    public void log(VirtualRequest request, VirtualResponse response) {
        TrafficEntry entry = new TrafficEntry(
                System.currentTimeMillis(),
                request.sessionId(),
                request.protocol(),
                request.method(),
                request.path(),
                response.matchedStubId(),
                response.statusCode(),
                response.executionLatencyMs(),
                response.matchedRules()
        );

        int index = writeIndex.getAndIncrement() % BUFFER_SIZE;
        buffer[index] = entry;
        totalLogged.incrementAndGet();
    }

    public List<TrafficEntry> getRecentEntries(int limit) {
        List<TrafficEntry> recent = new ArrayList<>(Math.min(limit, BUFFER_SIZE));
        int currentIndex = writeIndex.get();

        for (int i = 0; i < limit && i < BUFFER_SIZE; i++) {
            int idx = Math.floorMod(currentIndex - 1 - i, BUFFER_SIZE);
            TrafficEntry entry = buffer[idx];
            if (entry != null) {
                recent.add(entry);
            }
        }
        return recent;
    }

    /**
     * Blank query params (e.g. {@code ?method=&protocol=}, which a "Method/Topic: All" filter
     * submits as empty strings rather than omitting) are treated the same as absent — same fix
     * as StubSearchService. Previously an empty string never equalsIgnoreCase'd a real value,
     * so every entry was excluded and the UI's filter form silently returned zero rows.
     */
    public List<TrafficEntry> search(String sessionId, String method, String path,
                                      String stubId, Integer statusCode, String protocol, int limit) {
        String sessionFilter = blankToNull(sessionId);
        String methodFilter = blankToNull(method);
        String pathFilter = blankToNull(path);
        String stubIdFilter = blankToNull(stubId);
        String protocolFilter = blankToNull(protocol);

        List<TrafficEntry> results = new ArrayList<>();
        int currentIndex = writeIndex.get();

        for (int i = 0; i < BUFFER_SIZE; i++) {
            int idx = Math.floorMod(currentIndex - 1 - i, BUFFER_SIZE);
            TrafficEntry entry = buffer[idx];
            if (entry == null) continue;

            boolean matches = true;
            if (sessionFilter != null && !entry.sessionId().contains(sessionFilter)) matches = false;
            // Partial, case-insensitive match — "method" doubles as a Kafka topic / gRPC method
            // field in the UI now (free text, not a GET/POST dropdown), so exact equality would
            // make it impossible to search for part of a topic name.
            if (methodFilter != null && !entry.method().toLowerCase().contains(methodFilter.toLowerCase())) matches = false;
            if (pathFilter != null && !entry.path().contains(pathFilter)) matches = false;
            if (stubIdFilter != null && (entry.matchedStubId() == null ||
                    !entry.matchedStubId().contains(stubIdFilter))) matches = false;
            if (statusCode != null && entry.statusCode() != statusCode) matches = false;
            if (protocolFilter != null && (entry.protocol() == null ||
                    !entry.protocol().equalsIgnoreCase(protocolFilter))) matches = false;

            if (matches) {
                results.add(entry);
                if (results.size() >= limit) break;
            }
        }
        return results;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    public void clear() {
        Arrays.fill(buffer, null);
        writeIndex.set(0);
    }

    public long getTotalLogged() {
        return totalLogged.get();
    }
}
