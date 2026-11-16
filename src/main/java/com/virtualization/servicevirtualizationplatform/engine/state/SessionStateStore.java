package com.virtualization.servicevirtualizationplatform.engine.state;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-isolated state store using nested ConcurrentHashMaps.
 *
 * Level 1: sessionId → session's private state
 * Level 2: collection name → collection's private entries
 * Level 3: entity key → entity data
 *
 * Physical isolation: different sessions use different CHM objects in memory.
 * Zero cross-session contention. Lock-free reads. O(1) session teardown.
 */
@Service
public class SessionStateStore {

    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000; // 30 minutes

    private final ConcurrentHashMap<String,
            ConcurrentHashMap<String,
                    ConcurrentHashMap<String, Object>>> store = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> lastAccessedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> createdAtMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> customTtl = new ConcurrentHashMap<>();

    // ═══ SESSION LIFECYCLE ═══

    public void createSession(String sessionId, Long ttlMs) {
        store.putIfAbsent(sessionId, new ConcurrentHashMap<>());
        createdAtMap.putIfAbsent(sessionId, System.currentTimeMillis());
        touch(sessionId);
        if (ttlMs != null) {
            customTtl.put(sessionId, ttlMs);
        }
    }

    public void destroySession(String sessionId) {
        store.remove(sessionId);
        lastAccessedAt.remove(sessionId);
        createdAtMap.remove(sessionId);
        customTtl.remove(sessionId);
    }

    public boolean sessionExists(String sessionId) {
        return store.containsKey(sessionId);
    }

    public Set<String> listSessions() {
        return Set.copyOf(store.keySet());
    }

    /**
     * Alias for listSessions() — returns all active session IDs.
     */
    public Set<String> getActiveSessions() {
        return listSessions();
    }

    // ═══ STATE OPERATIONS ═══

    public void put(String sessionId, String collection, String key, Object value) {
        touch(sessionId);
        store.computeIfAbsent(sessionId, k -> {
                    createdAtMap.putIfAbsent(sessionId, System.currentTimeMillis());
                    return new ConcurrentHashMap<>();
                })
                .computeIfAbsent(collection, k -> new ConcurrentHashMap<>())
                .put(key, value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String sessionId, String collection, String key) {
        touch(sessionId);
        var sessionState = store.get(sessionId);
        if (sessionState == null) return null;
        var collectionMap = sessionState.get(collection);
        if (collectionMap == null) return null;
        Object value = collectionMap.get(key);
        if (value == null) return null;
        if (value instanceof Map) return (Map<String, Object>) value;
        return Map.of("_value", value);
    }

    public boolean delete(String sessionId, String collection, String key) {
        touch(sessionId);
        var sessionState = store.get(sessionId);
        if (sessionState == null) return false;
        var collectionMap = sessionState.get(collection);
        if (collectionMap == null) return false;
        return collectionMap.remove(key) != null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> list(String sessionId, String collection) {
        touch(sessionId);
        var sessionState = store.get(sessionId);
        if (sessionState == null) return Map.of();
        var collectionMap = sessionState.get(collection);
        if (collectionMap == null) return Map.of();
        return new LinkedHashMap<>(collectionMap);
    }

    public long increment(String sessionId, String counterCollection, String counterKey) {
        touch(sessionId);
        Object counter = store
                .computeIfAbsent(sessionId, k -> {
                    createdAtMap.putIfAbsent(sessionId, System.currentTimeMillis());
                    return new ConcurrentHashMap<>();
                })
                .computeIfAbsent(counterCollection, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(counterKey, k -> new AtomicLong(0));
        if (counter instanceof AtomicLong al) {
            return al.incrementAndGet();
        }
        return 0;
    }

    // ═══ INSPECTION ═══

    public Map<String, Map<String, Object>> getSessionState(String sessionId) {
        var sessionState = store.get(sessionId);
        if (sessionState == null) return Map.of();
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        sessionState.forEach((collection, entries) -> {
            Map<String, Object> copy = new LinkedHashMap<>();
            entries.forEach((key, value) -> {
                if (value instanceof AtomicLong al) {
                    copy.put(key, al.get());
                } else {
                    copy.put(key, value);
                }
            });
            snapshot.put(collection, copy);
        });
        return snapshot;
    }

    public int getEntityCount(String sessionId) {
        var sessionState = store.get(sessionId);
        if (sessionState == null) return 0;
        return sessionState.entrySet().stream()
                .filter(e -> !e.getKey().equals("_counters"))
                .mapToInt(e -> e.getValue().size())
                .sum();
    }

    // ═══ CLEAR OPERATIONS ═══

    public void clearState(String sessionId) {
        var sessionState = store.get(sessionId);
        if (sessionState != null) {
            sessionState.clear();
        }
        touch(sessionId);
    }

    public void clearCollection(String sessionId, String collection) {
        var sessionState = store.get(sessionId);
        if (sessionState != null) {
            sessionState.remove(collection);
        }
        touch(sessionId);
    }

    // ═══ TTL SUPPORT ═══

    private void touch(String sessionId) {
        lastAccessedAt.put(sessionId, System.currentTimeMillis());
    }

    public long getLastAccessedAt(String sessionId) {
        return lastAccessedAt.getOrDefault(sessionId, 0L);
    }

    public long getCreatedAt(String sessionId) {
        return createdAtMap.getOrDefault(sessionId, 0L);
    }

    public long getTtlForSession(String sessionId) {
        return customTtl.getOrDefault(sessionId, DEFAULT_TTL_MS);
    }

    public ConcurrentHashMap<String, Long> getLastAccessedMap() {
        return lastAccessedAt;
    }
}
