package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.engine.StubMatchingEngine;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import com.virtualization.servicevirtualizationplatform.proxy.OutboundForwardingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Transparent HTTP proxy — routes any non-admin request through the stub matching engine.
 *
 * Clients call actual URLs (e.g. POST /api/v1/accounts) instead of wrapping
 * requests in JSON via /api/v1/virtual. Session ID comes from X-Session-Id header.
 *
 * Admin endpoints (stubs, sessions, recording, traffic, drift, virtual, UI, swagger)
 * are explicitly excluded — Spring MVC routes them to their specific controllers first,
 * and this controller double-checks via ADMIN_PREFIXES as a safety net.
 *
 * Stretch Goal — Live Outbound Forwarding: When no stub matches AND outbound
 * forwarding is enabled (proxy.upstream.record-on-miss=true), the request is
 * forwarded to the real upstream, the response is auto-ingested as a stub, and
 * the real response is returned. Subsequent identical requests hit the stub.
 */
@RestController
public class ForwardingProxyController {

    private static final Set<String> ADMIN_PREFIXES = Set.of(
            "/api/v1/stubs",
            "/api/v1/sessions",
            "/api/v1/virtual",
            "/api/v1/recording",
            "/api/v1/traffic",
            "/api/v1/drift",
            "/api/v1/proxy",
            "/ui/",
            "/dashboard",
            "/swagger-ui",
            "/v3/api-docs"
    );

    private final StubMatchingEngine engine;
    private final OutboundForwardingService forwardingService;

    public ForwardingProxyController(StubMatchingEngine engine,
                                     OutboundForwardingService forwardingService) {
        this.engine = engine;
        this.forwardingService = forwardingService;
    }

    @RequestMapping("/**")
    public ResponseEntity<Object> handleAll(HttpServletRequest raw,
                                            @RequestBody(required = false) Map<String, Object> body) {
        String path = raw.getRequestURI();

        // Safety net: never intercept admin/control-plane endpoints
        for (String prefix : ADMIN_PREFIXES) {
            if (path.startsWith(prefix)) {
                return ResponseEntity.status(404)
                        .body(Map.of("error", "Not a virtual endpoint", "path", path));
            }
        }

        // Also skip root path (dashboard)
        if ("/".equals(path)) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Not a virtual endpoint", "path", path));
        }

        String sessionId = raw.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Missing required header: X-Session-Id",
                    "hint", "Every HTTP virtual request must include X-Session-Id to ensure session isolation"
            ));
        }

        VirtualRequest request = new VirtualRequest(
                sessionId,
                "HTTP",
                raw.getMethod(),
                path,
                extractHeaders(raw),
                body != null ? body : Map.of()
        );

        VirtualResponse response = engine.matchAndExecute(request);

        // Stretch Goal: on stub miss, forward to real upstream + auto-record
        if (response.statusCode() == 404 && forwardingService.isEnabled()) {
            return forwardingService.forwardAndRecord(raw, body, sessionId);
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        if (response.headers() != null) {
            response.headers().forEach(responseHeaders::set);
        }

        return ResponseEntity.status(response.statusCode())
                .headers(responseHeaders)
                .body(response.body());
    }

    private Map<String, String> extractHeaders(HttpServletRequest raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = raw.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, raw.getHeader(name));
            }
        }
        return headers;
    }

}
