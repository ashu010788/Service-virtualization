package com.virtualization.servicevirtualizationplatform.engine;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for HTTP path parameter matching via the CoW Trie.
 *
 * How it works:
 *   1. Stub pathPattern uses {placeholder} syntax: "/api/v1/employees/{id}"
 *   2. The Trie stores a wildcardChild node at that position and remembers the name ("id")
 *   3. When /api/v1/virtual is called with the ACTUAL path "/api/v1/employees/EMP-3243",
 *      the Trie walks segment-by-segment:
 *        "api" → exact child
 *        "v1"  → exact child
 *        "employees" → exact child
 *        "EMP-3243"  → no exact child → wildcardChild matches → pathVars["id"] = "EMP-3243"
 *   4. Engine passes pathVars to TemplateContext
 *   5. {{request.pathVar.id}} in response template resolves to "EMP-3243"
 *
 * Convention: all stubs at the same path position must use the SAME placeholder name.
 * The Trie stores ONE wildcardName per node; last-registered name wins.
 */
@SpringBootTest
class PathParamTest {

    @Autowired StubMatchingEngine engine;
    @Autowired StubRouter         stubRouter;

    private final List<String> cleanup = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanup.forEach(stubRouter::remove);
        cleanup.clear();
    }

    private void register(StubDefinition stub) {
        stubRouter.register(stub);
        cleanup.add(stub.stubId());
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test A: Wildcard matches ANY value — different URLs, same stub
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Stub pathPattern:  /api/v1/workers/{id}
     * Virtual API call:  "path": "/api/v1/workers/EMP-3243"   ← actual value
     *
     * Trie matches the literal value "EMP-3243" against the {id} wildcard node.
     * The captured value is then available as {{request.pathVar.id}} in the template.
     */
    @Test
    void pathParam_trieMatchesAnyValue_andEchoesInResponse() {
        register(new StubDefinition(
                "stub_worker_get",
                new RequestMatcher("HTTP", "GET", "/api/v1/workers/{id}", Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), Map.of(
                        "worker_id", "{{request.pathVar.id}}",           // whole token echoed
                        "found",      true,
                        "label",      "Worker {{request.pathVar.id}} found"  // interpolated
                )),
                null
        ));

        // path contains the actual value — Trie matches {id} wildcard → "EMP-3243"
        VirtualResponse res = engine.matchAndExecute(new VirtualRequest(
                "sess", "HTTP", "GET", "/api/v1/workers/EMP-3243", Map.of(), null
        ));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.matchedStubId()).isEqualTo("stub_worker_get");
        assertThat(res.body().get("worker_id")).isEqualTo("EMP-3243");   // captured from path
        assertThat(res.body().get("found")).isEqualTo(true);
        assertThat(res.body().get("label")).isEqualTo("Worker EMP-3243 found");

        // Different value → same stub still matches (wildcard accepts anything)
        VirtualResponse res2 = engine.matchAndExecute(new VirtualRequest(
                "sess", "HTTP", "GET", "/api/v1/workers/9999", Map.of(), null
        ));
        assertThat(res2.statusCode()).isEqualTo(200);
        assertThat(res2.body().get("worker_id")).isEqualTo("9999");
        assertThat(res2.body().get("label")).isEqualTo("Worker 9999 found");

        // Extra segment → no match (deeper path not registered)
        VirtualResponse noMatch = engine.matchAndExecute(new VirtualRequest(
                "sess", "HTTP", "GET", "/api/v1/workers/EMP-3243/details", Map.of(), null
        ));
        assertThat(noMatch.statusCode()).isEqualTo(404);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test B: POST creates resource, GET/{id} reads it back using path var as state key
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Full stateful round-trip:
     *
     *  POST /api/v1/employees
     *    → generates employee_id via {{generate.id:EMP}}
     *    → writes to state["employees"][employee_id]
     *
     *  GET /api/v1/employees/EMP-0001   ← actual ID in path
     *    → Trie captures "EMP-0001" into pathVar.id
     *    → stateAction READ: key = {{request.pathVar.id}} = "EMP-0001"
     *    → returns stored employee record
     *
     *  GET /api/v1/employees/EMP-0001 from DIFFERENT session → 404 (session isolated)
     */
    @Test
    @SuppressWarnings("unchecked")
    void pathParam_post_thenGet_statefulRoundTrip() {
        // POST: creates employee, state WRITE
        register(new StubDefinition(
                "stub_emp_create_pp",
                new RequestMatcher("HTTP", "POST", "/api/v1/employees", Map.of(), Map.of()),
                new ResponseTemplate(201, Map.of(), Map.of(
                        "employee_id", "{{generate.id:EMP}}",
                        "name",        "{{request.body.name}}",
                        "department",  "{{request.body.department}}",
                        "salary",      "{{request.body.salary}}",
                        "status",      "ACTIVE"
                )),
                new StateAction(StateOp.WRITE, "employees",
                        "{{response.body.employee_id}}", "{{response.body}}")
        ));

        // GET: reads by path variable, state READ
        register(new StubDefinition(
                "stub_emp_get_pp",
                new RequestMatcher("HTTP", "GET", "/api/v1/employees/{id}", Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(), "{{state.result}}"),
                new StateAction(StateOp.READ, "employees", "{{request.pathVar.id}}", null)
        ));

        // 404 fallback when READ returns null (not in this session)
        register(new StubDefinition(
                "stub_emp_404_pp",
                new RequestMatcher("HTTP", "GET", "/api/v1/employees/{id}", Map.of(), Map.of()),
                new ResponseTemplate(404, Map.of(), Map.of("error", "Not found")),
                null
        ));

        // ── Step 1: Create employee ──
        VirtualResponse created = engine.matchAndExecute(new VirtualRequest(
                "emp-sess-A", "HTTP", "POST", "/api/v1/employees",
                Map.of(), Map.of("name", "Alice", "department", "ENGINEERING", "salary", 95000.0)
        ));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.stateMutated()).isTrue();
        Map<String, Object> createdBody = (Map<String, Object>) created.body();
        String empId = (String) createdBody.get("employee_id");
        assertThat(empId).startsWith("EMP-");
        assertThat(createdBody.get("name")).isEqualTo("Alice");
        assertThat(((Number) createdBody.get("salary")).doubleValue()).isEqualTo(95000.0);

        // ── Step 2: GET by actual ID in path — same session → 200 ──
        // Virtual API call: "path": "/api/v1/employees/EMP-0001"
        // Trie: {id} wildcard captures "EMP-0001" → pathVars["id"] = "EMP-0001"
        // stateAction READ key: {{request.pathVar.id}} → "EMP-0001" → reads state
        VirtualResponse read = engine.matchAndExecute(new VirtualRequest(
                "emp-sess-A", "HTTP", "GET",
                "/api/v1/employees/" + empId,   // actual ID value in path
                Map.of(), null
        ));

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.matchedStubId()).isEqualTo("stub_emp_get_pp");
        assertThat(read.matchedRules()).contains("path_pattern", "state_lookup");
        Map<String, Object> readBody = (Map<String, Object>) read.body();
        assertThat(readBody.get("employee_id")).isEqualTo(empId);
        assertThat(readBody.get("name")).isEqualTo("Alice");
        assertThat(((Number) readBody.get("salary")).doubleValue()).isEqualTo(95000.0);
        assertThat(readBody.get("department")).isEqualTo("ENGINEERING");

        // ── Step 3: GET same ID from different session → 404 (session isolated) ──
        VirtualResponse crossSession = engine.matchAndExecute(new VirtualRequest(
                "emp-sess-B", "HTTP", "GET",
                "/api/v1/employees/" + empId,   // same path, different session
                Map.of(), null
        ));
        assertThat(crossSession.statusCode()).isEqualTo(404);  // not in session B
        assertThat(crossSession.matchedStubId()).isEqualTo("stub_emp_404_pp");

        // ── Step 4: Non-existent ID in same session → 404 ──
        VirtualResponse notFound = engine.matchAndExecute(new VirtualRequest(
                "emp-sess-A", "HTTP", "GET", "/api/v1/employees/EMP-9999", Map.of(), null
        ));
        assertThat(notFound.statusCode()).isEqualTo(404);

        // ── Step 5: Create second employee — counter increments per session ──
        VirtualResponse created2 = engine.matchAndExecute(new VirtualRequest(
                "emp-sess-A", "HTTP", "POST", "/api/v1/employees",
                Map.of(), Map.of("name", "Bob", "department", "FINANCE", "salary", 85000.0)
        ));
        String empId2 = (String) ((Map<String, Object>) created2.body()).get("employee_id");
        assertThat(empId2).isNotEqualTo(empId);  // unique IDs per session

        // ── Step 6: Both employees readable in session A ──
        VirtualResponse readBob = engine.matchAndExecute(new VirtualRequest(
                "emp-sess-A", "HTTP", "GET", "/api/v1/employees/" + empId2, Map.of(), null
        ));
        assertThat(readBob.statusCode()).isEqualTo(200);
        assertThat(((Map<String, Object>) readBob.body()).get("name")).isEqualTo("Bob");
    }
}
