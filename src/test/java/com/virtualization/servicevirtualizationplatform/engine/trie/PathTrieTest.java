package com.virtualization.servicevirtualizationplatform.engine.trie;

import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathTrieTest {

    private PathTrie trie;

    @BeforeEach
    void setUp() {
        trie = new PathTrie();
    }

    @Test
    void exactPathMatch() {
        trie.insert(createStub("s1", "POST", "/api/v1/accounts"));

        MatchCandidates result = trie.search("POST", "/api/v1/accounts");

        assertThat(result.stubs()).hasSize(1);
        assertThat(result.stubs().get(0).stubId()).isEqualTo("s1");
        assertThat(result.pathVariables()).isEmpty();
    }

    @Test
    void singlePathVariableExtraction() {
        trie.insert(createStub("s2", "GET", "/api/v1/accounts/{id}"));

        MatchCandidates result = trie.search("GET", "/api/v1/accounts/ACC-100");

        assertThat(result.stubs()).hasSize(1);
        assertThat(result.pathVariables()).containsEntry("id", "ACC-100");
    }

    @Test
    void multiplePathVariableExtraction() {
        trie.insert(createStub("s3", "GET", "/api/v1/accounts/{accId}/transactions/{txId}"));

        MatchCandidates result = trie.search("GET", "/api/v1/accounts/ACC-1/transactions/TX-99");

        assertThat(result.stubs()).hasSize(1);
        assertThat(result.pathVariables())
                .containsEntry("accId", "ACC-1")
                .containsEntry("txId", "TX-99");
    }

    @Test
    void noMatch_returnsEmpty() {
        trie.insert(createStub("s4", "GET", "/api/v1/accounts"));

        MatchCandidates result = trie.search("GET", "/api/v1/unknown");

        assertThat(result.stubs()).isEmpty();
    }

    @Test
    void specificityOrdering_moreConditionsFirst() {
        // No body conditions → specificity=0 (fallback)
        trie.insert(createStub("fallback", "GET", "/api/v1/accounts/{id}", Map.of(), Map.of()));
        // One body condition → specificity=10 (more specific)
        trie.insert(createStub("specific", "GET", "/api/v1/accounts/{id}",
                Map.of(), Map.of("$.status", "ACTIVE")));

        MatchCandidates result = trie.search("GET", "/api/v1/accounts/ACC-1");

        assertThat(result.stubs()).hasSize(2);
        // More specific stub must come first (checked first by engine)
        assertThat(result.stubs().get(0).stubId()).isEqualTo("specific");   // specificity=10
        assertThat(result.stubs().get(1).stubId()).isEqualTo("fallback");   // specificity=0
    }

    @Test
    void differentMethods_samePath() {
        trie.insert(createStub("get-stub", "GET", "/api/v1/accounts"));
        trie.insert(createStub("post-stub", "POST", "/api/v1/accounts"));

        MatchCandidates getResult = trie.search("GET", "/api/v1/accounts");
        MatchCandidates postResult = trie.search("POST", "/api/v1/accounts");

        assertThat(getResult.stubs()).hasSize(1);
        assertThat(getResult.stubs().get(0).stubId()).isEqualTo("get-stub");
        assertThat(postResult.stubs()).hasSize(1);
        assertThat(postResult.stubs().get(0).stubId()).isEqualTo("post-stub");
    }

    @Test
    void removeStub() {
        trie.insert(createStub("to-remove", "GET", "/api/v1/test"));

        assertThat(trie.search("GET", "/api/v1/test").stubs()).hasSize(1);

        trie.remove("to-remove");

        assertThat(trie.search("GET", "/api/v1/test").stubs()).isEmpty();
    }

    @Test
    void removeStub_leavesDeadNodesBehind_documentingWhyRebuildExists() {
        // Documents the exact limitation that motivated PathTrie#rebuild: remove() only clears
        // the StubDefinition entries stored on a node, it never prunes the TrieNode itself.
        trie.insert(createStub("only-stub-here", "GET", "/api/v1/deep/nested/path"));
        int nodesWithStub = trie.nodeCount(); // root + api + v1 + deep + nested + path = 6

        trie.remove("only-stub-here");

        assertThat(trie.search("GET", "/api/v1/deep/nested/path").stubs())
                .as("removed stub must no longer match").isEmpty();
        assertThat(trie.nodeCount())
                .as("remove() does NOT prune nodes — they're still allocated even though empty")
                .isEqualTo(nodesWithStub);
    }

    @Test
    void rebuild_pronesDeadNodes_andPreservesLiveStubMatching() {
        trie.insert(createStub("keep-me", "GET", "/api/v1/accounts"));
        trie.insert(createStub("delete-me", "GET", "/api/v1/deep/nested/path/to/delete"));
        trie.remove("delete-me"); // leaves dead nodes for deep/nested/path/to/delete behind

        int nodesBeforeRebuild = trie.nodeCount();

        // Rebuild from only the stubs that are actually still live (mirrors how
        // StubRouter#compactHttpTrie filters stubsById.values() down to HTTP stubs)
        trie.rebuild(() -> java.util.List.of(trie.search("GET", "/api/v1/accounts").stubs().get(0)));

        assertThat(trie.nodeCount())
                .as("rebuild must discard the dead deep/nested/path/to/delete branch")
                .isLessThan(nodesBeforeRebuild);
        // root + api + v1 + accounts = 4 nodes, nothing more
        assertThat(trie.nodeCount()).isEqualTo(4);

        // The still-live stub must keep matching correctly after rebuild
        assertThat(trie.search("GET", "/api/v1/accounts").stubs()).hasSize(1);
        assertThat(trie.search("GET", "/api/v1/accounts").stubs().get(0).stubId()).isEqualTo("keep-me");
        // The dead branch must be genuinely gone, not just stub-less
        assertThat(trie.search("GET", "/api/v1/deep/nested/path/to/delete").stubs()).isEmpty();
    }

    @Test
    void rebuild_withMultipleLiveStubs_preservesPathVariablesAndSpecificityOrder() {
        trie.insert(createStub("fallback", "GET", "/api/v1/orders/{id}", Map.of(), Map.of()));
        trie.insert(createStub("specific", "GET", "/api/v1/orders/{id}",
                Map.of(), Map.of("$.status", "PAID")));

        trie.rebuild(() -> java.util.List.of(
                trie.search("GET", "/api/v1/orders/ORD-1").stubs().get(1), // fallback (specificity=0)
                trie.search("GET", "/api/v1/orders/ORD-1").stubs().get(0)  // specific (specificity=10)
        ));

        MatchCandidates result = trie.search("GET", "/api/v1/orders/ORD-1");
        assertThat(result.pathVariables()).containsEntry("id", "ORD-1");
        assertThat(result.stubs()).hasSize(2);
        assertThat(result.stubs().get(0).stubId()).isEqualTo("specific"); // higher specificity first
        assertThat(result.stubs().get(1).stubId()).isEqualTo("fallback");
    }

    @Test
    void rebuild_withEmptyList_producesEmptyTrie() {
        trie.insert(createStub("will-be-gone", "GET", "/api/v1/anything"));

        trie.rebuild(java.util.List::of);

        assertThat(trie.nodeCount()).isEqualTo(1); // just the root
        assertThat(trie.search("GET", "/api/v1/anything").stubs()).isEmpty();
    }

    @Test
    void copyOnWrite_concurrentReadDuringWrite() throws Exception {
        // Insert initial stubs
        for (int i = 0; i < 100; i++) {
            trie.insert(createStub("stub-" + i, "GET", "/api/v1/resource/" + i));
        }

        // Read in parallel while writing
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 10_000; i++) {
                MatchCandidates result = trie.search("GET", "/api/v1/resource/50");
                // Should never throw, never return corrupted data
                assertThat(result).isNotNull();
            }
        });

        Thread writer = new Thread(() -> {
            for (int i = 100; i < 200; i++) {
                trie.insert(createStub("new-" + i, "POST", "/api/v1/new/" + i));
            }
        });

        reader.start();
        writer.start();
        reader.join(10_000);
        writer.join(10_000);

        // After concurrent operations, trie should be consistent
        assertThat(trie.size()).isGreaterThanOrEqualTo(100);
    }

    private StubDefinition createStub(String id, String method, String path) {
        return createStub(id, method, path, Map.of(), Map.of());
    }

    private StubDefinition createStub(String id, String method, String path,
                                       Map<String, String> headers,
                                       Map<String, String> bodyJsonPath) {
        return new StubDefinition(id,
                new RequestMatcher("HTTP", method, path, headers, bodyJsonPath),
                new ResponseTemplate(200, Map.of(), Map.of()),
                null);
    }
}
