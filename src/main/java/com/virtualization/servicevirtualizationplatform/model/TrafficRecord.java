package com.virtualization.servicevirtualizationplatform.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.time.Instant;

/**
 * Plain class (not a record) because status/generatedStubId mutate after construction.
 *
 * @JsonAutoDetect is REQUIRED here — confirmed bug fixed alongside this comment: without it,
 * Jackson has no JavaBean getters (id()/timestamp()/etc. don't follow the getXxx() convention)
 * and no record components to introspect, so GET /api/v1/recording/history threw
 * InvalidDefinitionException ("no properties discovered") the moment any record existed.
 * Same fix StubDefinition already uses for the identical reason.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                getterVisibility = JsonAutoDetect.Visibility.NONE,
                isGetterVisibility = JsonAutoDetect.Visibility.NONE)
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
