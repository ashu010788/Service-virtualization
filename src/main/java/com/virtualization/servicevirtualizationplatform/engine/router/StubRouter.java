package com.virtualization.servicevirtualizationplatform.engine.router;

import com.virtualization.servicevirtualizationplatform.engine.trie.PathTrie;
import com.virtualization.servicevirtualizationplatform.model.MatchCandidates;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Routes incoming requests to candidate stubs by protocol.
 *
 * ─── Concurrency model ─────────────────────────────────────────────────────
 *
 * HTTP  → Copy-on-Write PathTrie
 *   • Root reference is volatile; readers take a snapshot in one volatile-read.
 *   • Writers deep-copy the entire trie, mutate the copy, then CAS-swap the root.
 *   • Zero locks on the read path; readers in-flight during a write see the
 *     previous consistent snapshot.
 *
 * gRPC / Kafka  → ConcurrentHashMap<methodKey, CopyOnWriteArrayList<StubDefinition>>
 *
 *   READ path (findCandidates — called 20,000 times/sec):
 *     CHM.get() is lock-free.
 *     Iterating a CopyOnWriteArrayList always reads a stable internal array
 *     snapshot → never throws ConcurrentModificationException regardless of
 *     concurrent adds/removes.
 *
 *   WRITE path (register/remove — called rarely):
 *     Both operations use CHM.compute() / computeIfPresent() which holds the
 *     CHM segment lock for that key.  Inside the lambda a NEW CopyOnWriteArrayList
 *     is built from scratch, so the previously visible list is never mutated
 *     in-place (true copy-on-write at both levels).
 *
 *   Isolation guarantee:
 *     grpcIndex["CREATEACCOUNT"] is physically separate from
 *     grpcIndex["GETBALANCE"].  A request for CreateAccount receives ONLY the
 *     list stored under that key — it is structurally impossible for it to
 *     receive stubs registered for a different method.  Same for Kafka topics.
 *
 * ─── Why CopyOnWriteArrayList is the correct type here ────────────────────
 *
 *   ArrayList.removeIf() is NOT thread-safe: it modifies the backing array
 *   while another thread may be iterating the same reference returned by
 *   findCandidates().  Using COWAL eliminates this race:
 *
 *     • COWAL.removeIf()  — acquires an internal lock, copies the array,
 *                           removes matching elements, publishes the new array.
 *     • Iteration          — always reads the array reference published at
 *                           iterator-creation time (snapshot).
 *
 *   Additionally, we always build a fresh COWAL inside compute/computeIfPresent,
 *   so the list visible to readers before the lambda completes is never touched.
 */
@Service
public class StubRouter {

    private final PathTrie httpTrie = new PathTrie();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StubDefinition>>
            grpcIndex  = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StubDefinition>>
            kafkaIndex = new ConcurrentHashMap<>();

    /**
     * LinkedHashMap, not ConcurrentHashMap — confirmed bug fixed here. compactHttpTrie()
     * rebuilds the trie from stubsById.values(), and the trie's tie-break for equal-specificity
     * stubs relies on INSERTION ORDER being preserved (e.g. seed_stubs.json registers
     * stub_account_get_v1 — a state-READ precondition stub with specificity 0 — BEFORE
     * stub_account_not_found, its specificity-0 fallback; the matching engine tries candidates
     * in trie order and falls through to "not found" only if the state read misses). A plain
     * ConcurrentHashMap gives zero ordering guarantee on .values() iteration — ordering by hash
     * bucket instead of registration order silently put fallback-before-primary for every tied
     * pair after the first compaction, turning every "create then get" round trip into a 404.
     * Wrapped with Collections.synchronizedMap for thread safety; stubsById is never on the
     * 20k RPS hot path (findCandidates() only ever touches httpTrie/grpcIndex/kafkaIndex), so
     * trading CHM's lock striping for a single synchronized lock here costs nothing that matters.
     */
    private final Map<String, StubDefinition> stubsById =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Collections.synchronizedMap's Javadoc requires the CALLER to synchronize manually when
     * iterating any of its views (.values()/.keySet()/.entrySet()) — unlike the old
     * ConcurrentHashMap, which gave lock-free, always-safe weakly-consistent iteration for
     * free. Every stream/for-each over stubsById.values() goes through this helper instead of
     * touching .values() directly, so that contract is honored in exactly one place rather than
     * re-derived (and potentially gotten wrong) at every call site.
     */
    private List<StubDefinition> snapshotStubs() {
        synchronized (stubsById) {
            return new java.util.ArrayList<>(stubsById.values());
        }
    }

    // ─── Registration ───────────────────────────────────────────────────────

    /** Runtime registration — CoW trie insert for HTTP; atomic bucket update for gRPC/Kafka. */
    public void register(StubDefinition stub) {
        stubsById.put(stub.stubId(), stub);
        registerToIndex(stub, false);
    }

    /**
     * Upsert by stubId: if a stub with this exact stubId already exists, replace it;
     * otherwise register as new. Used by the MANUAL stub-authoring API (POST /api/v1/stubs).
     *
     * Deliberately does NOT treat "different stubId, identical requestMatcher" as an override.
     * This engine supports intentional ordered-candidate chains with IDENTICAL matchers —
     * e.g. a "found" stub with a state-READ precondition followed by a "not found" fallback
     * stub with no precondition (see seed_stubs.json's stub_account_get_v1 +
     * stub_account_not_found, or StubMatchingEngine's "state not found → continue to next
     * candidate" logic). Auto-replacing on matcher equality alone would silently delete one
     * half of every such pair the moment the second stub is registered. Only an EXPLICIT
     * same-stubId re-registration is treated as "the same stub, just updated" here.
     *
     * @return the replaced stubId as a single-element list, or empty if this was new.
     */
    public List<String> registerOrReplace(StubDefinition stub) {
        List<String> replaced = stubsById.containsKey(stub.stubId())
                ? List.of(stub.stubId())
                : List.of();
        replaced.forEach(this::remove);
        register(stub);
        return replaced;
    }

    /**
     * Upsert by REQUEST SHAPE: replaces any existing stub whose stubId matches OR whose
     * requestMatcher is identical (protocol + method + pathPattern + headers + bodyJsonPath,
     * value-for-value). Used by record ingestion (POST /api/v1/recording/ingest) ONLY —
     * auto-generated stubIds are always unique (timestamp-based), so stubId can never signal
     * "this is an update to an earlier ingestion"; matcher equality is the only available
     * signal. Unlike the manual API, ingestion never deliberately produces intentional
     * same-matcher candidate chains (each ingested sample yields exactly one stub), so
     * collapsing exact-matcher duplicates here is always safe: re-ingesting the identical
     * request shape with a different recorded response is "the same logical stub, updated",
     * not a new coexisting one. If the request body values differ even slightly, the matcher
     * differs too — requestMatcher().equals() returns false and the new stub is simply added
     * alongside the old one, both reachable independently based on which body the live
     * request actually satisfies.
     *
     * @return stubIds that were replaced — empty if this was a genuinely new request shape.
     */
    public List<String> registerOrReplaceByRequestShape(StubDefinition stub) {
        Set<String> toReplace = new LinkedHashSet<>();

        for (StubDefinition existing : snapshotStubs()) {
            boolean sameId      = existing.stubId().equals(stub.stubId());
            boolean sameMatcher = existing.requestMatcher().equals(stub.requestMatcher());
            if (sameId || sameMatcher) {
                toReplace.add(existing.stubId());
            }
        }
        toReplace.forEach(this::remove);

        register(stub);
        return List.copyOf(toReplace);
    }

    /** Startup-only — mutates trie root directly (no readers yet). */
    public void registerDirect(StubDefinition stub) {
        stubsById.put(stub.stubId(), stub);
        registerToIndex(stub, true);
    }

    private void registerToIndex(StubDefinition stub, boolean direct) {
        switch (stub.requestMatcher().protocol().toUpperCase()) {
            case "HTTP" -> {
                if (direct) httpTrie.insertDirect(stub);
                else        httpTrie.insert(stub);
            }
            case "GRPC"  -> addToIndex(grpcIndex,  stub);
            case "KAFKA" -> addToIndex(kafkaIndex, stub);
        }
    }

    /**
     * Atomically insert a stub into the per-method bucket.
     *
     * compute() holds the CHM segment lock for this key while the lambda runs.
     * We build a BRAND-NEW CopyOnWriteArrayList so no currently-iterating
     * reader ever sees an in-progress mutation.
     */
    private void addToIndex(ConcurrentHashMap<String, CopyOnWriteArrayList<StubDefinition>> index,
                            StubDefinition stub) {
        index.compute(stub.requestMatcher().method().toUpperCase(), (key, existing) -> {
            CopyOnWriteArrayList<StubDefinition> next =
                    existing != null
                    ? new CopyOnWriteArrayList<>(existing)   // copy current list
                    : new CopyOnWriteArrayList<>();
            next.add(stub);
            next.sort(Comparator.comparingInt(StubDefinition::specificity).reversed());
            return next;                                     // atomic swap in CHM
        });
    }

    // ─── Removal ────────────────────────────────────────────────────────────

    /**
     * Remove a stub by ID.
     *
     * For gRPC/Kafka we build a NEW list (copy-on-write) so readers holding the
     * old reference continue iterating it safely — they just see a snapshot
     * that includes the now-removed stub, which is harmless: the engine's
     * enabled-check or body-match will simply not select a disabled/removed stub
     * on the next call after the swap completes.
     */
    public void remove(String stubId) {
        StubDefinition stub = stubsById.remove(stubId);
        if (stub == null) return;

        switch (stub.requestMatcher().protocol().toUpperCase()) {
            case "HTTP"  -> httpTrie.remove(stubId);
            case "GRPC"  -> removeFromIndex(grpcIndex,  stub.requestMatcher().method(), stubId);
            case "KAFKA" -> removeFromIndex(kafkaIndex, stub.requestMatcher().method(), stubId);
        }
    }

    private void removeFromIndex(ConcurrentHashMap<String, CopyOnWriteArrayList<StubDefinition>> index,
                                 String method, String stubId) {
        index.computeIfPresent(method.toUpperCase(), (key, existing) -> {
            // Build a NEW list — never mutate `existing` in-place
            CopyOnWriteArrayList<StubDefinition> next = new CopyOnWriteArrayList<>();
            for (StubDefinition s : existing) {           // safe snapshot iteration
                if (!s.stubId().equals(stubId)) next.add(s);
            }
            return next.isEmpty() ? null : next;           // null removes the key
        });
    }

    /**
     * Rebuilds the HTTP trie from the current contents of stubsById, discarding any TrieNodes
     * left over from deleted stubs (PathTrie#remove only ever clears the StubDefinition entries
     * stored on a node, it never prunes the node itself). gRPC/Kafka don't need this: their
     * ConcurrentHashMap buckets already self-clean via removeFromIndex's computeIfPresent
     * returning null when a bucket empties out.
     *
     * The stubsById filter/snapshot is passed as a Supplier, evaluated INSIDE PathTrie's own
     * lock — not computed here and handed over as a finished List. See PathTrie#rebuild's
     * Javadoc for the lost-update race that would otherwise exist between this snapshot and a
     * concurrent register()/remove() call.
     *
     * Not on the hot path — intended to be called periodically by StubGovernanceScheduler,
     * not per-request.
     */
    public void compactHttpTrie() {
        httpTrie.rebuild(() -> snapshotStubs().stream()
                .filter(s -> "HTTP".equalsIgnoreCase(s.requestMatcher().protocol()))
                .toList());
    }

    /** TrieNode count — exposed for governance/observability and tests, not the hot path. */
    public int getHttpTrieNodeCount() {
        return httpTrie.nodeCount();
    }

    // ─── Routing (hot path) ─────────────────────────────────────────────────

    /**
     * Find candidate stubs for the given request.
     *
     * For gRPC/Kafka the returned List is a CopyOnWriteArrayList snapshot:
     * the engine can iterate it freely even if another thread concurrently
     * registers or removes stubs in the same bucket.
     *
     * Routing isolation:
     *   • HTTP: Trie returns only stubs whose pathPattern matches the request path.
     *   • gRPC: returns only stubs registered under method.toUpperCase() key.
     *   • Kafka: returns only stubs registered under topic.toUpperCase() key.
     *   A request can NEVER receive stubs from a different path, method, or topic.
     */
    public MatchCandidates findCandidates(String protocol, String method, String path) {
        return switch (protocol.toUpperCase()) {
            case "HTTP"  -> httpTrie.search(method, path);
            case "GRPC"  -> {
                CopyOnWriteArrayList<StubDefinition> bucket = grpcIndex.get(method.toUpperCase());
                yield bucket != null ? MatchCandidates.of(bucket) : MatchCandidates.empty();
            }
            case "KAFKA" -> {
                CopyOnWriteArrayList<StubDefinition> bucket = kafkaIndex.get(method.toUpperCase());
                yield bucket != null ? MatchCandidates.of(bucket) : MatchCandidates.empty();
            }
            default -> MatchCandidates.empty();
        };
    }

    // ─── Admin helpers ───────────────────────────────────────────────────────

    public StubDefinition getById(String stubId) {
        return stubsById.get(stubId);
    }

    public Collection<StubDefinition> getAllStubs() {
        return snapshotStubs();
    }

    public void toggleStub(String stubId) {
        StubDefinition stub = stubsById.get(stubId);
        if (stub != null) stub.setEnabled(!stub.isEnabled());
    }

    public int getActiveCount() {
        return (int) snapshotStubs().stream().filter(StubDefinition::isEnabled).count();
    }

    public int getDisabledCount() {
        return (int) snapshotStubs().stream().filter(s -> !s.isEnabled()).count();
    }

    public int totalSize() {
        return stubsById.size();
    }
}
