package com.virtualization.servicevirtualizationplatform.engine.governance;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Approach 2 from the trie-pruning discussion: StubGovernanceScheduler's hourly sweep
 * now also rebuilds the HTTP trie (StubRouter#compactHttpTrie), discarding TrieNodes left behind
 * by PathTrie#remove (which only ever clears the StubDefinition entries on a node, never the
 * node itself). Invokes cleanupStubs() directly rather than waiting for the real @Scheduled
 * timer \u2014 it's a plain public method, nothing scheduling-specific needs mocking.
 */
@SpringBootTest
class StubGovernanceSchedulerTest {

    @Autowired
    private StubRouter stubRouter;

    @Autowired
    private StubGovernanceScheduler scheduler;

    @Test
    void cleanupStubs_compactsHttpTrie_afterExpiredStubRemoved() {
        // Note: this Spring context is shared across the whole test suite, and the scheduler's
        // own @Scheduled background firing can run concurrently with other test classes also
        // registering/removing stubs — so asserting an exact "nodesBefore == nodesAfter" count
        // is inherently flaky here (unrelated concurrent activity changes the baseline). Instead
        // we assert the one thing that's robust regardless of concurrent noise: adding our own
        // uniquely-named multi-segment branch strictly increases the count, and removing it via
        // cleanup strictly decreases it back below that peak — true no matter what else is
        // happening elsewhere in the shared trie at the same time.
        int nodesBeforeAdding = stubRouter.getHttpTrieNodeCount();

        StubDefinition expiring = new StubDefinition(
                "governance-compaction-test-stub",
                new RequestMatcher("HTTP", "GET", "/api/v1/governance-compaction/deep/nested/path",
                        Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), Map.of()),
                null, null, "manual", null,
                Instant.now().minusSeconds(60)); // already expired
        stubRouter.register(expiring);

        int nodesWithDeadBranchPending = stubRouter.getHttpTrieNodeCount();
        assertThat(nodesWithDeadBranchPending)
                .as("registering a brand-new multi-segment path must grow the trie")
                .isGreaterThan(nodesBeforeAdding);

        scheduler.cleanupStubs(); // removes the expired stub AND compacts the trie

        assertThat(stubRouter.getById("governance-compaction-test-stub"))
                .as("expired stub must actually be gone").isNull();
        assertThat(stubRouter.getHttpTrieNodeCount())
                .as("compaction must prune the now-dead governance-compaction/deep/nested/path branch")
                .isLessThan(nodesWithDeadBranchPending);
    }

    @Test
    void cleanupStubs_compaction_preservesStillLiveStubMatching() {
        StubDefinition permanent = new StubDefinition(
                "governance-compaction-live-stub",
                new RequestMatcher("HTTP", "GET", "/api/v1/governance-compaction-live", Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), Map.of("ok", true)),
                null, null, "manual", null, null); // no expiry
        stubRouter.register(permanent);

        scheduler.cleanupStubs();

        MatchCandidates result = stubRouter.findCandidates(
                "HTTP", "GET", "/api/v1/governance-compaction-live");
        assertThat(result.stubs()).extracting(StubDefinition::stubId)
                .contains("governance-compaction-live-stub");
    }
}
