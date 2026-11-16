package com.virtualization.servicevirtualizationplatform.model;

import java.util.List;
import java.util.Map;

public record MatchCandidates(
        List<StubDefinition> stubs,
        Map<String, String> pathVariables
) {
    public static MatchCandidates empty() {
        return new MatchCandidates(List.of(), Map.of());
    }

    public static MatchCandidates of(List<StubDefinition> stubs) {
        return new MatchCandidates(stubs, Map.of());
    }

    public boolean isEmpty() {
        return stubs == null || stubs.isEmpty();
    }
}
