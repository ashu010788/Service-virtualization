package com.virtualization.servicevirtualizationplatform.engine.trie;

import com.virtualization.servicevirtualizationplatform.model.MatchCandidates;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;

import java.util.*;

/**
 * Copy-on-Write Path Trie for HTTP path routing.
 *
 * Readers access a volatile root reference — zero locking, zero overhead.
 * Writers deep-copy the entire trie, modify the copy, then atomically swap the root.
 * Readers in-flight during a write continue on the old snapshot — consistent and safe.
 */
public class PathTrie {

    private volatile TrieNode root = new TrieNode();

    // ═══ HOT PATH — called 20,000 times/sec ═══
    // Zero locks. One volatile read for root reference.
    public MatchCandidates search(String method, String path) {
        TrieNode snapshot = this.root;
        return doSearch(snapshot, method, path);
    }

    // ═══ STARTUP PATH — no concurrent readers yet, mutate root directly ═══
    // Used by StubLoader during @PostConstruct. No copy overhead.
    public void insertDirect(StubDefinition stub) {
        doInsert(this.root, stub);
    }

    // ═══ RUNTIME PATH — called rarely (stub registration via API / ingestion) ═══
    // Copy-on-Write: deep-copy → mutate copy → atomic swap.
    // Readers in-flight see the old snapshot — never blocked.
    public synchronized void insert(StubDefinition stub) {
        TrieNode newRoot = deepCopy(this.root);
        doInsert(newRoot, stub);
        this.root = newRoot; // atomic volatile write
    }

    public synchronized void remove(String stubId) {
        TrieNode newRoot = deepCopy(this.root);
        doRemove(newRoot, stubId);
        this.root = newRoot;
    }

    /**
     * Full compaction: throws away the current tree and rebuilds it from scratch using whatever
     * the supplier returns. remove() never prunes a TrieNode once created — only the
     * StubDefinition entries stored on it — so a node for "/api/v1/accounts/{id}" stays
     * allocated forever even after every stub registered there has been deleted. Calling this
     * periodically (see StubGovernanceScheduler) throws away that dead structure for free: a
     * freshly built tree only ever contains nodes for segments that have at least one live
     * stub under them, because doInsert is the only thing that ever creates a TrieNode.
     *
     * IMPORTANT — takes a Supplier, not a pre-built Collection: confirmed bug fixed here. If
     * the caller snapshots stubsById BEFORE calling this method, there's a lost-update race
     * against concurrent register()/remove() calls — a stub could finish stubsById.put(X)
     * right between that outside snapshot and this method's lock acquisition, so the rebuilt
     * trie silently excludes X even though stubsById still has it (X stops matching forever,
     * until the next compaction). Evaluating the supplier INSIDE the synchronized block means
     * it shares PathTrie's single monitor with insert()/remove(), so the snapshot can only ever
     * be taken strictly-before or strictly-after any given register()/remove() call completes
     * its own trie mutation — never lost in between. The only residual race is benign: a stub
     * registered concurrently can transiently double-insert (self-heals on its next removal,
     * since removeIf() strips every matching entry, not just one) — never silently vanish.
     */
    public synchronized void rebuild(java.util.function.Supplier<Collection<StubDefinition>> stubSupplier) {
        TrieNode newRoot = new TrieNode();
        for (StubDefinition stub : stubSupplier.get()) {
            doInsert(newRoot, stub);
        }
        this.root = newRoot;
    }

    /** Total TrieNode count in the current tree — exposed for governance/observability and tests. */
    public int nodeCount() {
        return countNodes(this.root);
    }

    private int countNodes(TrieNode node) {
        int count = 1; // this node
        for (TrieNode child : node.children.values()) {
            count += countNodes(child);
        }
        if (node.wildcardChild != null) {
            count += countNodes(node.wildcardChild);
        }
        return count;
    }

    public int size() {
        return countStubs(this.root);
    }

    // ═══ SEARCH IMPLEMENTATION ═══

    private MatchCandidates doSearch(TrieNode node, String method, String path) {
        String[] segments = path.split("/", -1);
        TrieNode current = node;
        Map<String, String> pathVars = new LinkedHashMap<>();

        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];

            // Try exact match first (more specific wins)
            TrieNode exactChild = current.children.get(seg);
            if (exactChild != null) {
                current = exactChild;
                continue;
            }

            // Try wildcard match
            if (current.wildcardChild != null) {
                pathVars.put(current.wildcardName, seg);
                current = current.wildcardChild;
                continue;
            }

            // Dead end — no match
            return MatchCandidates.empty();
        }

        List<StubDefinition> stubs = current.stubs.getOrDefault(
                method.toUpperCase(), List.of());
        return new MatchCandidates(stubs, pathVars);
    }

    // ═══ INSERT IMPLEMENTATION ═══

    private void doInsert(TrieNode node, StubDefinition stub) {
        String[] segments = stub.requestMatcher().pathPattern().split("/", -1);
        TrieNode current = node;

        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.startsWith("{") && seg.endsWith("}")) {
                if (current.wildcardChild == null) {
                    current.wildcardChild = new TrieNode();
                }
                // Always update wildcardName — convention: all stubs at the same path
                // position should use the same parameter name. Last registered wins.
                // Different names for the same position are a user-configuration error.
                current.wildcardName = seg.substring(1, seg.length() - 1);
                current = current.wildcardChild;
            } else {
                current = current.children.computeIfAbsent(seg, k -> new TrieNode());
            }
        }

        String method = stub.requestMatcher().method().toUpperCase();
        current.stubs.computeIfAbsent(method, k -> new ArrayList<>()).add(stub);
        // Stable sort: more specific (more conditions) first; insertion order breaks ties
        current.stubs.get(method).sort(
                Comparator.comparingInt(StubDefinition::specificity).reversed());
    }

    // ═══ REMOVE IMPLEMENTATION ═══

    private void doRemove(TrieNode node, String stubId) {
        removeFromNode(node, stubId);
    }

    private void removeFromNode(TrieNode node, String stubId) {
        for (List<StubDefinition> stubs : node.stubs.values()) {
            stubs.removeIf(s -> s.stubId().equals(stubId));
        }
        for (TrieNode child : node.children.values()) {
            removeFromNode(child, stubId);
        }
        if (node.wildcardChild != null) {
            removeFromNode(node.wildcardChild, stubId);
        }
    }

    // ═══ DEEP COPY ═══

    private TrieNode deepCopy(TrieNode original) {
        TrieNode copy = new TrieNode();

        for (Map.Entry<String, TrieNode> entry : original.children.entrySet()) {
            copy.children.put(entry.getKey(), deepCopy(entry.getValue()));
        }

        if (original.wildcardChild != null) {
            copy.wildcardChild = deepCopy(original.wildcardChild);
            copy.wildcardName = original.wildcardName;
        }

        for (Map.Entry<String, List<StubDefinition>> entry : original.stubs.entrySet()) {
            copy.stubs.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return copy;
    }

    private int countStubs(TrieNode node) {
        int count = 0;
        for (List<StubDefinition> stubs : node.stubs.values()) {
            count += stubs.size();
        }
        for (TrieNode child : node.children.values()) {
            count += countStubs(child);
        }
        if (node.wildcardChild != null) {
            count += countStubs(node.wildcardChild);
        }
        return count;
    }

    // ═══ TRIE NODE ═══

    static class TrieNode {
        final Map<String, TrieNode> children = new HashMap<>();
        TrieNode wildcardChild = null;
        String wildcardName = null;
        final Map<String, List<StubDefinition>> stubs = new HashMap<>();
    }
}
