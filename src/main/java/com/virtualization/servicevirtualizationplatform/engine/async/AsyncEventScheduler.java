package com.virtualization.servicevirtualizationplatform.engine.async;

import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.engine.template.TemplateContext;
import com.virtualization.servicevirtualizationplatform.engine.template.TemplateResolver;
import com.virtualization.servicevirtualizationplatform.model.AsyncEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Schedules async events triggered by HTTP stubs.
 * e.g., POST /payments → 500ms later → Kafka event "payment-completed".
 *
 * Uses ScheduledExecutorService with 4 daemon threads.
 * Events write to session state so clients can poll for them.
 */
@Service
public class AsyncEventScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncEventScheduler.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "async-event-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final SessionStateStore stateStore;
    private final TemplateResolver templateResolver;

    public AsyncEventScheduler(SessionStateStore stateStore, TemplateResolver templateResolver) {
        this.stateStore = stateStore;
        this.templateResolver = templateResolver;
    }

    @SuppressWarnings("unchecked")
    public void scheduleEvents(String sessionId, List<AsyncEventConfig> events, TemplateContext context) {
        if (events == null) return;

        for (AsyncEventConfig event : events) {
            long delay = event.delayMs();
            if (event.jitterMs() > 0) {
                delay += ThreadLocalRandom.current().nextLong(-event.jitterMs(), event.jitterMs() + 1);
                delay = Math.max(0, delay);
            }

            scheduler.schedule(() -> {
                try {
                    // Resolve event body template
                    Map<String, Object> eventBody;
                    if (event.eventBody() != null) {
                        eventBody = templateResolver.resolveBody(event.eventBody(), context);
                    } else {
                        eventBody = Map.of();
                    }

                    // Write event to session state
                    String eventId = "evt-" + UUID.randomUUID().toString().substring(0, 8);
                    String collection = "events:" + event.targetTopic();
                    stateStore.put(sessionId, collection, eventId, eventBody);

                    // Optional: update related state
                    if (event.stateUpdate() != null) {
                        String key = templateResolver.resolveToString(
                                event.stateUpdate().keyExpr(), context);
                        Object value = templateResolver.resolveValue(
                                event.stateUpdate().valueExpr(), context);
                        stateStore.put(sessionId, event.stateUpdate().collection(), key, value);
                    }

                    log.debug("Async event delivered: session={}, topic={}, eventId={}",
                            sessionId, event.targetTopic(), eventId);
                } catch (Exception e) {
                    log.error("Failed to deliver async event for session={}: {}",
                            sessionId, e.getMessage());
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }
}
