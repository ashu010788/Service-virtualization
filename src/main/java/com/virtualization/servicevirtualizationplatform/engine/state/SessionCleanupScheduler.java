package com.virtualization.servicevirtualizationplatform.engine.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically cleans up sessions that have exceeded their TTL.
 * Runs every 60 seconds. Prevents memory leaks from abandoned CI pipelines.
 */
@Component
public class SessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final SessionStateStore stateStore;

    public SessionCleanupScheduler(SessionStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();

        stateStore.getLastAccessedMap().forEach((sessionId, lastAccess) -> {
            long ttl = stateStore.getTtlForSession(sessionId);
            long idleTime = now - lastAccess;

            if (idleTime > ttl) {
                stateStore.destroySession(sessionId);
                log.info("Session expired: {} (idle {}ms, ttl {}ms)", sessionId, idleTime, ttl);
            }
        });
    }
}
