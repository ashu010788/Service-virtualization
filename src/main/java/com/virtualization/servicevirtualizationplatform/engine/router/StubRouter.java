package com.virtualization.servicevirtualizationplatform.engine.router;

import com.virtualization.servicevirtualizationplatform.engine.trie.PathTrie;
import com.virtualization.servicevirtualizationplatform.model.MatchCandidates;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes requests to matching stubs based on protocol.
 * HTTP: Copy-on-Write Trie (path matching with variables).
 * gRPC: HashMap by method name (flat namespace).
 * Kafka: HashMap by topic name (flat namespace).
 */
@Service
public class StubRouter {

    private final PathTrie httpTrie = new PathTrie();
    private final ConcurrentHashMap<String, List<StubDefinition>> grpcIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<StubDefinition>> kafkaIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StubDefinition> stubsById = new ConcurrentHashMap<>();

    /**
     * Runtime registration — uses Copy-on-Write trie for HTTP stubs.
     * Safe for concurrent readers on the hot path.
     */
    public void register(StubDefinition stub) {
        stubsById.put(stub.stubId(), stub);
        registerToIndex(stub, false);
    }

    /**
     * Startup-only registration — mutates trie directly (no deep copy).
     * Only safe when no readers exist yet (e.g., @PostConstruct).
     */
    public void registerDirect(StubDefinition stub) {
        stubsById.put(stub.stubId(), stub);
        registerToIndex(stub, true);
    }

    private void registerToIndex(StubDefinition stub, boolean direct) {
        switch (stub.requestMatcher().protocol().toUpperCase()) {
            case "HTTP" -> {
                if (direct) {
                    httpTrie.insertDirect(stub);
                } else {
                    httpTrie.insert(stub);
                }
            }
            case "GRPC" -> grpcIndex.compute(
                    stub.requestMatcher().method().toUpperCase(), (k, list) -> {
                        List<StubDefinition> updated = list != null ? new ArrayList<>(list) : new ArrayList<>();
                        updated.add(stub);
                        updated.sort(Comparator.comparingInt(StubDefinition::priority).reversed());
                        return updated;
                    });
            case "KAFKA" -> kafkaIndex.compute(
                    stub.requestMatcher().method().toUpperCase(), (k, list) -> {
                        List<StubDefinition> updated = list != null ? new ArrayList<>(list) : new ArrayList<>();
                        updated.add(stub);
                        updated.sort(Comparator.comparingInt(StubDefinition::priority).reversed());
                        return updated;
                    });
        }
    }

    public void remove(String stubId) {
        StubDefinition stub = stubsById.remove(stubId);
        if (stub == null) return;

        switch (stub.requestMatcher().protocol().toUpperCase()) {
            case "HTTP" -> httpTrie.remove(stubId);
            case "GRPC" -> grpcIndex.computeIfPresent(
                    stub.requestMatcher().method().toUpperCase(), (k, list) -> {
                        list.removeIf(s -> s.stubId().equals(stubId));
                        return list.isEmpty() ? null : list;
                    });
            case "KAFKA" -> kafkaIndex.computeIfPresent(
                    stub.requestMatcher().method().toUpperCase(), (k, list) -> {
                        list.removeIf(s -> s.stubId().equals(stubId));
                        return list.isEmpty() ? null : list;
                    });
        }
    }

    public MatchCandidates findCandidates(String protocol, String method, String path) {
        return switch (protocol.toUpperCase()) {
            case "HTTP" -> httpTrie.search(method, path);
            case "GRPC" -> MatchCandidates.of(
                    grpcIndex.getOrDefault(method.toUpperCase(), List.of()));
            case "KAFKA" -> MatchCandidates.of(
                    kafkaIndex.getOrDefault(method.toUpperCase(), List.of()));
            default -> MatchCandidates.empty();
        };
    }

    public StubDefinition getById(String stubId) {
        return stubsById.get(stubId);
    }

    public Collection<StubDefinition> getAllStubs() {
        return stubsById.values();
    }

    public void toggleStub(String stubId) {
        StubDefinition stub = stubsById.get(stubId);
        if (stub != null) {
            stub.setEnabled(!stub.isEnabled());
        }
    }

    public int getActiveCount() {
        return (int) stubsById.values().stream().filter(StubDefinition::isEnabled).count();
    }

    public int getDisabledCount() {
        return (int) stubsById.values().stream().filter(s -> !s.isEnabled()).count();
    }

    public int totalSize() {
        return stubsById.size();
    }
}
