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

    /**
     * Confirmed bug fixed here: UI select/input filters whose value is "All"/empty still
     * submit as an empty-string query param (e.g. {@code ?protocol=&method=}), not an absent
     * one. {@code protocol == null} never matched that, so every stub got compared against an
     * empty string and the filter silently returned zero results no matter what the user typed
     * elsewhere (e.g. in the query box). Blank is now treated the same as absent.
     */
    public List<StubDefinition> search(String query, String protocol, String method, String path,
                                        String source, Boolean enabled, String sortBy, int limit) {
        String protocolFilter = blankToNull(protocol);
        String methodFilter = blankToNull(method);
        String pathFilter = blankToNull(path);
        String sourceFilter = blankToNull(source);

        return stubRouter.getAllStubs().stream()
                .filter(s -> matchesQuery(s, query))
                .filter(s -> protocolFilter == null || s.requestMatcher().protocol().equalsIgnoreCase(protocolFilter))
                .filter(s -> methodFilter == null || s.requestMatcher().method().toLowerCase().contains(methodFilter.toLowerCase()))
                .filter(s -> pathFilter == null || matchesPath(s, pathFilter))
                .filter(s -> sourceFilter == null || sourceFilter.equals(s.source()))
                .filter(s -> enabled == null || enabled == s.isEnabled())
                .sorted(comparator(sortBy))
                .limit(limit)
                .toList();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // Backward-compatible overload (no path filter)
    public List<StubDefinition> search(String query, String protocol, String method,
                                        String source, Boolean enabled, String sortBy, int limit) {
        return search(query, protocol, method, null, source, enabled, sortBy, limit);
    }

    private boolean matchesQuery(StubDefinition stub, String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        return stub.stubId().toLowerCase().contains(q) ||
                stub.requestMatcher().pathPattern().toLowerCase().contains(q) ||
                stub.requestMatcher().method().toLowerCase().contains(q);
    }

    /**
     * Path filter — matches the stub's pathPattern (HTTP) or method/topic name (GRPC/KAFKA).
     * Supports partial match so "/api/v1/employees" finds both
     * "/api/v1/employees" and "/api/v1/employees/{id}".
     */
    private boolean matchesPath(StubDefinition stub, String path) {
        String p = path.toLowerCase();
        // HTTP: match against pathPattern
        if ("HTTP".equalsIgnoreCase(stub.requestMatcher().protocol())) {
            return stub.requestMatcher().pathPattern().toLowerCase().contains(p);
        }
        // GRPC / KAFKA: match against method (rpc method name or topic name)
        return stub.requestMatcher().method().toLowerCase().contains(p);
    }

    private Comparator<StubDefinition> comparator(String sortBy) {
        return switch (sortBy != null ? sortBy : "matchCount") {
            case "id" -> Comparator.comparing(StubDefinition::stubId);
            case "specificity" -> Comparator.comparingInt(StubDefinition::specificity).reversed();
            case "created" -> Comparator.comparing(StubDefinition::createdAt).reversed();
            case "lastUsed" -> Comparator.comparing(
                    StubDefinition::lastMatchedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparingLong(StubDefinition::matchCount).reversed();
        };
    }
}
