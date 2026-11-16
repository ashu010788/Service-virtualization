package com.virtualization.servicevirtualizationplatform.engine.governance;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Prevents stub sprawl by cleaning up expired and unused stubs.
 * Runs every hour. Not in the hot path.
 */
@Component
public class StubGovernanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(StubGovernanceScheduler.class);
    private static final long AUTO_GEN_TTL_DAYS = 7;
    private static final long MANUAL_INACTIVITY_DAYS = 30;

    private final StubRouter stubRouter;

    public StubGovernanceScheduler(StubRouter stubRouter) {
        this.stubRouter = stubRouter;
    }

    @Scheduled(fixedRate = 3600_000) // every hour
    public void cleanupStubs() {
        Instant now = Instant.now();
        int removed = 0;

        for (StubDefinition stub : stubRouter.getAllStubs()) {
            // Rule 1: Hard expiry (expiresAt set explicitly)
            if (stub.expiresAt() != null && now.isAfter(stub.expiresAt())) {
                stubRouter.remove(stub.stubId());
                log.info("Stub expired (TTL): {}", stub.stubId());
                removed++;
                continue;
            }

            // Rule 2: Auto-generated stubs unused for 7 days
            if ("auto-generated".equals(stub.source()) && stub.matchCount() == 0 &&
                    Duration.between(stub.createdAt(), now).toDays() > AUTO_GEN_TTL_DAYS) {
                stubRouter.remove(stub.stubId());
                log.info("Stub expired (auto-gen, unused 7d): {}", stub.stubId());
                removed++;
                continue;
            }

            // Rule 3: Manual stubs with no matches in 30 days
            if (stub.lastMatchedAt() != null &&
                    Duration.between(stub.lastMatchedAt(), now).toDays() > MANUAL_INACTIVITY_DAYS) {
                stubRouter.remove(stub.stubId());
                log.info("Stub expired (inactive 30d): {}", stub.stubId());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Governance cleanup: removed {} stubs, {} remaining",
                    removed, stubRouter.totalSize());
        }

        // Approach 2 from the trie-pruning discussion: PathTrie#remove only clears the
        // StubDefinition entries on a node, never the node itself, so deleted stubs leave
        // dead TrieNodes behind forever. Rebuilding from the authoritative stubsById map here
        // (already "not hot path, runs hourly" by design) throws that dead structure away for
        // free — a freshly built tree only has nodes for segments with at least one live stub.
        // Runs every cycle, not just when removed > 0 above, because stubs can also be deleted
        // throughout the day via DELETE /api/v1/stubs/{id} — this is the only place that
        // periodically cleans up after those too.
        int nodesBefore = stubRouter.getHttpTrieNodeCount();
        stubRouter.compactHttpTrie();
        int nodesAfter = stubRouter.getHttpTrieNodeCount();
        if (nodesBefore != nodesAfter) {
            log.info("Trie compaction: {} -> {} nodes ({} pruned)",
                    nodesBefore, nodesAfter, nodesBefore - nodesAfter);
        }
    }
}
