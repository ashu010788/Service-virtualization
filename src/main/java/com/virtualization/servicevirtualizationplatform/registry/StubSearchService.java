package com.virtualization.servicevirtualizationplatform.registry;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class StubSearchService {

    private final StubRouter stubRouter;

    public StubSearchService(StubRouter stubRouter) {
        this.stubRouter = stubRouter;
    }

    public List<StubDefinition> search(String query, String protocol, String method,
                                        String source, Boolean enabled, String sortBy, int limit) {
        return stubRouter.getAllStubs().stream()
                .filter(s -> matchesQuery(s, query))
                .filter(s -> protocol == null || s.requestMatcher().protocol().equalsIgnoreCase(protocol))
                .filter(s -> method == null || s.requestMatcher().method().equalsIgnoreCase(method))
                .filter(s -> source == null || source.equals(s.source()))
                .filter(s -> enabled == null || enabled == s.isEnabled())
                .sorted(comparator(sortBy))
                .limit(limit)
                .toList();
    }

    private boolean matchesQuery(StubDefinition stub, String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        return stub.stubId().toLowerCase().contains(q) ||
                stub.requestMatcher().pathPattern().toLowerCase().contains(q) ||
                stub.requestMatcher().method().toLowerCase().contains(q);
    }

    private Comparator<StubDefinition> comparator(String sortBy) {
        return switch (sortBy != null ? sortBy : "matchCount") {
            case "id" -> Comparator.comparing(StubDefinition::stubId);
            case "priority" -> Comparator.comparingInt(StubDefinition::priority).reversed();
            case "created" -> Comparator.comparing(StubDefinition::createdAt).reversed();
            case "lastUsed" -> Comparator.comparing(
                    StubDefinition::lastMatchedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparingLong(StubDefinition::matchCount).reversed();
        };
    }
}
