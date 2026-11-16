package com.virtualization.servicevirtualizationplatform.proxy;

import com.virtualization.servicevirtualizationplatform.model.RawTrafficLog;
import com.virtualization.servicevirtualizationplatform.model.StubGenerationResult;
import com.virtualization.servicevirtualizationplatform.recording.RecordingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live Outbound Forwarding + Auto-Record via Transparent Proxy.
 *
 * On a stub miss (404 from StubMatchingEngine), this service:
 *   1. Forwards the original request to the configured real upstream
 *   2. Captures the upstream's response (including 4xx/5xx — never swallowed)
 *   3. Calls RecordingService.ingest() to sanitize → score → generate → register a stub
 *   4. Returns the real upstream response to the caller
 *
 * After step 3, the next identical request hits the auto-generated stub
 * and never reaches the upstream again.
 *
 * This service is opt-in: disabled unless proxy.upstream.record-on-miss=true
 * AND proxy.upstream.base-url is configured. When disabled, stub misses
 * continue to return 404 as before — zero change to existing behavior.
 *
 * Key fix over the earlier version: RestTemplate now uses a NoOp error handler
 * so 4xx/5xx upstream responses are captured and returned as-is (instead of
 * being swallowed into a generic 502). Query parameters are also forwarded.
 */
@Service
public class OutboundForwardingService {

    private static final Logger log = LoggerFactory.getLogger(OutboundForwardingService.class);
    private static final int MAX_FORWARD_LOG = 500;

    private final OutboundForwardingConfig config;
    private final RecordingService recordingService;
    private final RestTemplate restTemplate;

    // ── Proxy stats (proxy-only, no changes to common code) ──
    private final AtomicLong totalForwarded = new AtomicLong();
    private final AtomicLong totalRecorded = new AtomicLong();
    private final AtomicLong totalUpstreamErrors = new AtomicLong();
    private final AtomicLong totalConnectionFailures = new AtomicLong();
    private final List<ForwardLogEntry> forwardLog = new CopyOnWriteArrayList<>();

    public OutboundForwardingService(OutboundForwardingConfig config,
                                     RecordingService recordingService) {
        this.config = config;
        this.recordingService = recordingService;
        this.restTemplate = buildRestTemplate();
    }

    /**
     * Build a RestTemplate that does NOT throw on 4xx/5xx responses.
     * Default RestTemplate throws HttpClientErrorException / HttpServerErrorException,
     * which means the caller never sees the actual upstream response body on error codes.
     * For a recording proxy we need to capture EVERY response.
     */
    private static RestTemplate buildRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false; // never treat any status as an error — let us handle it
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                // no-op: we handle every status code ourselves
            }
        });
        return rt;
    }

    /**
     * @return true when outbound forwarding is active (both base-url and record-on-miss set)
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /** Expose config for the proxy config API (read-only — mutations go through config). */
    public OutboundForwardingConfig getConfig() {
        return config;
    }

    // ── Stats accessors (for ProxyConfigController) ──

    public long getTotalForwarded() { return totalForwarded.get(); }
    public long getTotalRecorded() { return totalRecorded.get(); }
    public long getTotalUpstreamErrors() { return totalUpstreamErrors.get(); }
    public long getTotalConnectionFailures() { return totalConnectionFailures.get(); }
    public List<ForwardLogEntry> getRecentForwards(int limit) {
        List<ForwardLogEntry> snapshot = List.copyOf(forwardLog);
        // newest-first
        List<ForwardLogEntry> reversed = new java.util.ArrayList<>(snapshot);
        java.util.Collections.reverse(reversed);
        return reversed.size() > limit ? reversed.subList(0, limit) : reversed;
    }

    /**
     * Forward the request to the real upstream, capture the response, auto-ingest it,
     * and return the upstream response wrapped in a Spring ResponseEntity.
     *
     * Now correctly handles 4xx/5xx upstream responses (captures and returns them
     * instead of converting to 502). Query parameters are forwarded too.
     *
     * @param raw       the original HttpServletRequest (for method, path, headers, query string)
     * @param body      parsed request body (may be null)
     * @param sessionId the X-Session-Id header value (for logging, not sent upstream)
     * @return the upstream's response, or a 502 if the upstream is unreachable
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> forwardAndRecord(HttpServletRequest raw,
                                                    Map<String, Object> body,
                                                    String sessionId) {
        // Forward query parameters too — raw.getRequestURI() doesn't include them
        String pathWithQuery = raw.getRequestURI();
        if (raw.getQueryString() != null && !raw.getQueryString().isBlank()) {
            pathWithQuery += "?" + raw.getQueryString();
        }
        String upstreamUrl = config.getBaseUrl() + pathWithQuery;
        String method = raw.getMethod();

        log.info("[OutboundProxy] Stub miss for session={}, forwarding {} {} → {}",
                sessionId, method, pathWithQuery, upstreamUrl);

        totalForwarded.incrementAndGet();

        try {
            // Build upstream request — forward safe original headers, skip platform-internal ones
            HttpHeaders upstreamHeaders = new HttpHeaders();
            var headerNames = raw.getHeaderNames();
            if (headerNames != null) {
                while (headerNames.hasMoreElements()) {
                    String name = headerNames.nextElement();
                    String lower = name.toLowerCase();
                    // Skip platform-internal and hop-by-hop headers
                    if ("x-session-id".equals(lower) || "host".equals(lower)
                            || "connection".equals(lower) || "content-length".equals(lower)) {
                        continue;
                    }
                    upstreamHeaders.set(name, raw.getHeader(name));
                }
            }
            if (!upstreamHeaders.containsKey(HttpHeaders.CONTENT_TYPE) && body != null) {
                upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);
            }

            HttpEntity<Map<String, Object>> upstreamRequest = new HttpEntity<>(body, upstreamHeaders);

            ResponseEntity<Map> upstreamResponse = restTemplate.exchange(
                    upstreamUrl,
                    HttpMethod.valueOf(method.toUpperCase()),
                    upstreamRequest,
                    Map.class
            );

            Map<String, Object> responseBody = upstreamResponse.getBody() != null
                    ? upstreamResponse.getBody()
                    : Map.of();

            int upstreamStatus = upstreamResponse.getStatusCode().value();

            if (upstreamStatus >= 500) {
                totalUpstreamErrors.incrementAndGet();
            }

            // Build RawTrafficLog from the captured request+response
            RawTrafficLog trafficLog = new RawTrafficLog(
                    new RawTrafficLog.RequestData(
                            "HTTP",
                            method,
                            raw.getRequestURI(),
                            extractRequestHeaders(raw),
                            body != null ? body : Map.of()
                    ),
                    new RawTrafficLog.ResponseData(
                            upstreamStatus,
                            extractResponseHeaders(upstreamResponse),
                            responseBody
                    )
            );

            // Auto-ingest: sanitize → score → generate → (maybe) register
            StubGenerationResult result = recordingService.ingest(trafficLog);
            totalRecorded.incrementAndGet();
            log.info("[OutboundProxy] Auto-ingested {} {} → recordId={}, status={}",
                    method, raw.getRequestURI(), result.recordId(), result.status());

            // Log the forward for the proxy UI
            addForwardLog(method, raw.getRequestURI(), upstreamStatus,
                    result.recordId(), result.status(), null);

            // Return the real upstream response (with its original status code) to the caller
            HttpHeaders responseHeaders = new HttpHeaders();
            upstreamResponse.getHeaders().forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    // Skip hop-by-hop headers
                    String lower = key.toLowerCase();
                    if (!"transfer-encoding".equals(lower) && !"connection".equals(lower)) {
                        responseHeaders.put(key, values);
                    }
                }
            });

            return ResponseEntity.status(upstreamResponse.getStatusCode())
                    .headers(responseHeaders)
                    .body(responseBody);

        } catch (Exception e) {
            totalConnectionFailures.incrementAndGet();
            log.error("[OutboundProxy] Upstream call failed for {} {}: {}",
                    method, upstreamUrl, e.getMessage());

            addForwardLog(method, raw.getRequestURI(), 502, null, "CONNECTION_FAILED", e.getMessage());

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "Upstream forwarding failed",
                            "upstream", upstreamUrl,
                            "cause", e.getMessage() != null ? e.getMessage() : "unknown"
                    ));
        }
    }

    private void addForwardLog(String method, String path, int upstreamStatus,
                                String recordId, String ingestStatus, String error) {
        forwardLog.add(new ForwardLogEntry(
                Instant.now(), method, path, upstreamStatus, recordId, ingestStatus, error));
        while (forwardLog.size() > MAX_FORWARD_LOG) {
            try { forwardLog.remove(0); } catch (IndexOutOfBoundsException e) { break; }
        }
    }

    private Map<String, String> extractRequestHeaders(HttpServletRequest raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = raw.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                // Don't include session header in the traffic log — it's platform-internal
                if (!"X-Session-Id".equalsIgnoreCase(name)) {
                    headers.put(name, raw.getHeader(name));
                }
            }
        }
        return headers;
    }

    private Map<String, String> extractResponseHeaders(ResponseEntity<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaders().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                headers.put(key, values.get(0));
            }
        });
        return headers;
    }

    /**
     * Immutable log entry for proxy forwarding activity — displayed in the Proxy UI page.
     * Kept in the proxy layer only (no changes to common TrafficRecord/TrafficLogger).
     */
    public record ForwardLogEntry(
            Instant timestamp,
            String method,
            String path,
            int upstreamStatus,
            String recordId,
            String ingestStatus,
            String error
    ) {}
}
