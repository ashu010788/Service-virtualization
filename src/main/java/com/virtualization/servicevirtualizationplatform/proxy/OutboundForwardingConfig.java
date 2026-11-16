package com.virtualization.servicevirtualizationplatform.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stretch Goal — Live Outbound Forwarding Proxy.
 *
 * When a request hits ForwardingProxyController and NO stub matches (404),
 * the platform can optionally forward it to a real upstream service, capture
 * the response, auto-ingest it via RecordingService (sanitize → generate → register),
 * and return the real response to the caller. On the next identical request the
 * auto-generated stub answers instead — no upstream call needed.
 *
 * This is the "record on first miss, replay on every hit after" pattern used
 * by production service-virtualization proxies.
 *
 * Disabled by default (record-on-miss=false). Set both properties to enable:
 *   proxy.upstream.base-url=http://real-service:8080
 *   proxy.upstream.record-on-miss=true
 */
@Component
@ConfigurationProperties(prefix = "proxy.upstream")
public class OutboundForwardingConfig {

    private String baseUrl = "";
    private boolean recordOnMiss = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isRecordOnMiss() {
        return recordOnMiss;
    }

    public void setRecordOnMiss(boolean recordOnMiss) {
        this.recordOnMiss = recordOnMiss;
    }

    /**
     * Feature is enabled only when both base-url is set AND record-on-miss is true.
     */
    public boolean isEnabled() {
        return recordOnMiss && baseUrl != null && !baseUrl.isBlank();
    }
}
