package com.virtualization.servicevirtualizationplatform.registry;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.RequestMatcher;
import com.virtualization.servicevirtualizationplatform.model.ResponseTemplate;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class StubRegistry {

    private final StubRouter stubRouter;

    public StubRegistry(StubRouter stubRouter) {
        this.stubRouter = stubRouter;
    }

    public void register(StubDefinition stub) {
        stubRouter.register(stub);
    }

    public void remove(String stubId) {
        stubRouter.remove(stubId);
    }

    public StubDefinition getById(String stubId) {
        return stubRouter.getById(stubId);
    }

    public Collection<StubDefinition> getAll() {
        return stubRouter.getAllStubs();
    }

    public void toggleStub(String stubId) {
        stubRouter.toggleStub(stubId);
    }

    public void updateResponseTemplate(String stubId, ResponseTemplate newResponse) {
        StubDefinition existing = stubRouter.getById(stubId);
        if (existing == null) return;

        StubDefinition updated = new StubDefinition(
                existing.stubId(), existing.priority(),
                existing.requestMatcher(), newResponse,
                existing.stateAction(), existing.asyncEvents(),
                existing.source(), existing.apiVersion(), existing.expiresAt()
        );
        stubRouter.remove(stubId);
        stubRouter.register(updated);
    }

    /**
     * Check for conflicts when registering a new stub.
     * Returns list of existing stubs that overlap.
     */
    public List<Map<String, String>> checkConflicts(StubDefinition newStub) {
        List<Map<String, String>> conflicts = new ArrayList<>();

        for (StubDefinition existing : stubRouter.getAllStubs()) {
            if (existing.stubId().equals(newStub.stubId())) continue;

            RequestMatcher em = existing.requestMatcher();
            RequestMatcher nm = newStub.requestMatcher();

            if (em.protocol().equalsIgnoreCase(nm.protocol()) &&
                    em.method().equalsIgnoreCase(nm.method()) &&
                    em.pathPattern().equals(nm.pathPattern())) {
                conflicts.add(Map.of(
                        "existingStubId", existing.stubId(),
                        "message", "Same path and method. Old stub (v" +
                                existing.apiVersion() + ") may be obsolete.",
                        "suggestedAction", "REPLACE"
                ));
            }
        }
        return conflicts;
    }
}
