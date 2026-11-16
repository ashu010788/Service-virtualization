package com.virtualization.servicevirtualizationplatform.api;

import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionStateStore stateStore;

    public SessionController(SessionStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, Object> request) {
        String sessionId;
        int ttlMinutes = 30;

        if (request != null) {
            sessionId = (String) request.getOrDefault("session_id",
                    "sess-" + UUID.randomUUID().toString().substring(0, 8));
            ttlMinutes = request.containsKey("ttl_minutes")
                    ? ((Number) request.get("ttl_minutes")).intValue() : 30;
        } else {
            sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        }

        stateStore.createSession(sessionId, (long) ttlMinutes * 60_000);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "session_id", sessionId,
                "ttl_minutes", ttlMinutes,
                "status", "ACTIVE"
        ));
    }

    @GetMapping
    public ResponseEntity<Object> listSessions() {
        var sessions = stateStore.listSessions().stream().map(id -> {
            long now = System.currentTimeMillis();
            long lastAccess = stateStore.getLastAccessedAt(id);
            long ttl = stateStore.getTtlForSession(id);
            long idleMs = now - lastAccess;
            long remainingMs = Math.max(0, ttl - idleMs);

            return Map.of(
                    "session_id", id,
                    "entity_count", stateStore.getEntityCount(id),
                    "idle_ms", idleMs,
                    "ttl_remaining_ms", remainingMs,
                    "created_at", stateStore.getCreatedAt(id)
            );
        }).toList();

        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/{id}/state")
    public ResponseEntity<Map<String, Object>> getSessionState(@PathVariable String id) {
        if (!stateStore.sessionExists(id)) {
            return ResponseEntity.ok(Map.of());
        }
        return ResponseEntity.ok(Map.of(
                "session_id", id,
                "collections", stateStore.getSessionState(id),
                "entity_count", stateStore.getEntityCount(id)
        ));
    }

    @PostMapping("/{id}/clear")
    public ResponseEntity<Void> clearState(@PathVariable String id) {
        stateStore.clearState(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> destroySession(@PathVariable String id) {
        stateStore.destroySession(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}/{collection}/{key}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String sessionId,
                                              @PathVariable String collection,
                                              @PathVariable String key) {
        stateStore.delete(sessionId, collection, key);
        return ResponseEntity.noContent().build();
    }
}
