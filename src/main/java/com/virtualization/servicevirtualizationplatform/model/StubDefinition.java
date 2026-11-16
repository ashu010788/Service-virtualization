package com.virtualization.servicevirtualizationplatform.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StubDefinition is a CLASS (not record) because it has mutable fields:
 * enabled (toggle on/off), matchCount, lastMatchedAt (governance tracking).
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                getterVisibility = JsonAutoDetect.Visibility.NONE,
                isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class StubDefinition {

    private final String stubId;
    private final int priority;
    private final RequestMatcher requestMatcher;
    private final ResponseTemplate responseTemplate;
    private final StateAction stateAction;
    private final List<AsyncEventConfig> asyncEvents;

    // Governance fields
    private final Instant createdAt;
    private final String source;          // "manual" or "auto-generated"
    private final String apiVersion;      // nullable
    private volatile Instant expiresAt;   // nullable — TTL for stub sprawl governance

    // Mutable tracking fields — thread-safe
    private volatile boolean enabled;
    private final AtomicLong matchCount;
    private volatile Instant lastMatchedAt;

    public StubDefinition(String stubId, int priority, RequestMatcher requestMatcher,
                          ResponseTemplate responseTemplate, StateAction stateAction,
                          List<AsyncEventConfig> asyncEvents,
                          String source, String apiVersion, Instant expiresAt) {
        this.stubId = stubId;
        this.priority = priority;
        this.requestMatcher = requestMatcher;
        this.responseTemplate = responseTemplate;
        this.stateAction = stateAction;
        this.asyncEvents = asyncEvents;
        this.createdAt = Instant.now();
        this.source = source != null ? source : "manual";
        this.apiVersion = apiVersion;
        this.expiresAt = expiresAt;
        this.enabled = true;
        this.matchCount = new AtomicLong(0);
        this.lastMatchedAt = null;
    }

    // Convenience constructor for simple stubs
    public StubDefinition(String stubId, int priority, RequestMatcher requestMatcher,
                          ResponseTemplate responseTemplate, StateAction stateAction) {
        this(stubId, priority, requestMatcher, responseTemplate, stateAction,
                null, "manual", null, null);
    }

    // --- Getters ---

    public String stubId() { return stubId; }
    public int priority() { return priority; }
    public RequestMatcher requestMatcher() { return requestMatcher; }
    public ResponseTemplate responseTemplate() { return responseTemplate; }
    public StateAction stateAction() { return stateAction; }
    public List<AsyncEventConfig> asyncEvents() { return asyncEvents; }
    public Instant createdAt() { return createdAt; }
    public String source() { return source; }
    public String apiVersion() { return apiVersion; }
    public Instant expiresAt() { return expiresAt; }

    // --- Mutable state ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long matchCount() { return matchCount.get(); }
    public void recordMatch() {
        matchCount.incrementAndGet();
        lastMatchedAt = Instant.now();
    }

    public Instant lastMatchedAt() { return lastMatchedAt; }

    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean hasStateRead() {
        return stateAction != null && stateAction.op() == StateOp.READ;
    }

    public boolean hasStateWrite() {
        return stateAction != null && stateAction.op() == StateOp.WRITE;
    }

    public boolean hasStateDelete() {
        return stateAction != null && stateAction.op() == StateOp.DELETE;
    }

    public boolean hasAsyncEvents() {
        return asyncEvents != null && !asyncEvents.isEmpty();
    }

    public boolean hasBodyConditions() {
        return requestMatcher.bodyJsonPath() != null && !requestMatcher.bodyJsonPath().isEmpty();
    }

    public boolean hasHeaderConditions() {
        return requestMatcher.headers() != null && !requestMatcher.headers().isEmpty();
    }
}
