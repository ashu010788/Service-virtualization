package com.virtualization.servicevirtualizationplatform.model;

import java.time.Instant;

public class TrafficRecord {

    public enum Status {
        PROCESSING, AUTO_PUBLISHED, PENDING_REVIEW, REJECTED, ERROR
    }

    private final String id;
    private final Instant timestamp;
    private final RawTrafficLog rawLog;
    private volatile Status status;
    private volatile String generatedStubId;

    public TrafficRecord(String id, Instant timestamp, RawTrafficLog rawLog, Status status) {
        this.id = id;
        this.timestamp = timestamp;
        this.rawLog = rawLog;
        this.status = status;
    }

    public String id() { return id; }
    public Instant timestamp() { return timestamp; }
    public RawTrafficLog rawLog() { return rawLog; }
    public Status status() { return status; }
    public String generatedStubId() { return generatedStubId; }

    public void setStatus(Status status) { this.status = status; }
    public void setGeneratedStubId(String id) { this.generatedStubId = id; }
}
