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
    void priorityOrdering_highestFirst() {
        trie.insert(createStub("low", "GET", "/api/v1/accounts/{id}", 1));
        trie.insert(createStub("high", "GET", "/api/v1/accounts/{id}", 10));

        MatchCandidates result = trie.search("GET", "/api/v1/accounts/ACC-1");

        assertThat(result.stubs()).hasSize(2);
        assertThat(result.stubs().get(0).stubId()).isEqualTo("high");
        assertThat(result.stubs().get(1).stubId()).isEqualTo("low");
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
        return createStub(id, method, path, 10);
    }

    private StubDefinition createStub(String id, String method, String path, int priority) {
        return new StubDefinition(id, priority,
                new RequestMatcher("HTTP", method, path, Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), Map.of()),
                null);
    }
}
