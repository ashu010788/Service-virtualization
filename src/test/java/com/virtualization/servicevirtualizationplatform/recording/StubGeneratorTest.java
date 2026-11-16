package com.virtualization.servicevirtualizationplatform.recording;

import com.virtualization.servicevirtualizationplatform.model.StateOp;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StubGeneratorTest {

    private final StubGenerator generator = new StubGenerator();

    // ═══ POST response body building ═══

    @Test
    void idFieldsNotInRequest_keepStaticRecordedValue() {
        // payment_id was never sent by the client — record/replay means replay the
        // exact recorded value, not mint a fresh generated ID on every invocation.
        StubDefinition stub = generator.generate("POST", "/api/v1/payments",
                Map.of("currency", "USD"),
                Map.of("payment_id", "PAY-99281", "status", "APPROVED", "amount", 100.0),
                200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("payment_id")).isEqualTo("PAY-99281");
    }

    @Test
    void staticFields_keptAsIs_whenNotInRequest() {
        // "status" is NOT a field in the request body → stays static
        StubDefinition stub = generator.generate("POST", "/api/v1/payments",
                Map.of("currency", "USD"),
                Map.of("status", "APPROVED"),
                200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void responseFieldMatchingRequestField_becomesTemplate() {
        // "name" and "salary" appear in BOTH request and response
        // → response uses {{request.body.name}} and {{request.body.salary}}
        StubDefinition stub = generator.generate("POST", "/api/v1/employees",
                Map.of("name", "Jane Doe", "salary", 95000.0, "department", "ENGINEERING"),
                Map.of("employee_id", "EMP-0001", "name", "Jane Doe",
                        "salary", 95000.0, "status", "ACTIVE"),
                201);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();

        // employee_id was never sent by the client → stays the static recorded value
        assertThat(body.get("employee_id")).isEqualTo("EMP-0001");
        // name in both request and response → dynamic echo
        assertThat(body.get("name")).isEqualTo("{{request.body.name}}");
        // salary in both request and response → dynamic echo
        assertThat(body.get("salary")).isEqualTo("{{request.body.salary}}");
        // status only in response → stays static
        assertThat(body.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void categoricalFields_becomeMatchConditions() {
        StubDefinition stub = generator.generate("POST", "/api/v1/accounts",
                Map.of("currency", "USD", "amount", 100.0),
                Map.of("account_id", "ACC-001"),
                201);

        // currency is a good match candidate (short string, not redacted)
        assertThat(stub.requestMatcher().bodyJsonPath()).containsKey("$.currency");
        // amount is a Number → also a good match candidate (numeric values included since CASE-1 requirement)
        assertThat(stub.requestMatcher().bodyJsonPath()).containsKey("$.amount");
        assertThat(stub.requestMatcher().bodyJsonPath().get("$.amount")).isEqualTo("100.0");
    }

    @Test
    void redactedFields_excludedFromMatchers() {
        StubDefinition stub = generator.generate("POST", "/api/v1/payments",
                Map.of("credit_card", "****1111", "currency", "USD"),
                Map.of("payment_id", "PAY-001"),
                200);

        // Redacted field should not be a matcher
        assertThat(stub.requestMatcher().bodyJsonPath()).doesNotContainKey("$.credit_card");
        assertThat(stub.requestMatcher().bodyJsonPath()).containsKey("$.currency");
    }

    @Test
    void post_stateAction_writesToCollection() {
        StubDefinition stub = generator.generate("POST", "/api/v1/employees",
                Map.of("name", "Jane"),
                Map.of("employee_id", "EMP-001"),
                201);

        assertThat(stub.stateAction()).isNotNull();
        assertThat(stub.stateAction().op()).isEqualTo(StateOp.WRITE);
        assertThat(stub.stateAction().collection()).isEqualTo("employees");
        assertThat(stub.stateAction().keyExpr()).isEqualTo("{{response.body.employee_id}}");
        assertThat(stub.stateAction().valueExpr()).isEqualTo("{{response.body}}");
    }

    // ═══ GET stub generation ═══
    //
    // New behavior (CASE-2 and CASE-3):
    //   • Path with {param}  → Trie wildcard; static body from recording; NO stateAction
    //   • Path literal       → exact Trie match; static body from recording; NO stateAction
    //   • Never throws — both cases succeed

    @Test
    void get_withPathParam_createsStaticStub_noStateAction() {
        // Case 2: Path has {id} → Trie wildcard, STATIC response from recording, no stateAction READ
        StubDefinition stub = generator.generate("GET", "/api/v1/employees/{id}",
                null,
                Map.of("employee_id", "EMP-001", "name", "Jane"),
                200);

        assertThat(stub.requestMatcher().method()).isEqualTo("GET");
        assertThat(stub.requestMatcher().pathPattern()).isEqualTo("/api/v1/employees/{id}");

        // NO stateAction — pure static replay
        assertThat(stub.stateAction()).isNull();

        // Response body = static values copied verbatim from recording
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("employee_id")).isEqualTo("EMP-001");
        assertThat(body.get("name")).isEqualTo("Jane");
    }

    @Test
    void get_withDeepPathParam_staticBodyFromRecording() {
        // Deep path with two {params} — still static replay; path preserved as-is for Trie
        StubDefinition stub = generator.generate("GET", "/api/v1/departments/{deptId}/employees/{empId}",
                null,
                Map.of("employee_id", "EMP-001", "dept", "ENGINEERING"),
                200);

        // No stateAction
        assertThat(stub.stateAction()).isNull();

        // Static body from recording
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("employee_id")).isEqualTo("EMP-001");
        assertThat(body.get("dept")).isEqualTo("ENGINEERING");

        // pathPattern preserved for Trie wildcard matching
        assertThat(stub.requestMatcher().pathPattern())
                .isEqualTo("/api/v1/departments/{deptId}/employees/{empId}");
    }

    @Test
    void get_withoutPathParam_succeedsWithLiteralPathAndStaticBody() {
        // Case 3: Literal path (no {param}) → exact Trie match, static response, no error
        StubDefinition stub = generator.generate("GET", "/api/v1/employees",
                null,
                Map.of("employee_id", "EMP-001"),
                200);

        // No exception thrown, no stateAction
        assertThat(stub.stateAction()).isNull();
        assertThat(stub.requestMatcher().pathPattern()).isEqualTo("/api/v1/employees");

        // Static body from recording
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();
        assertThat(body.get("employee_id")).isEqualTo("EMP-001");
    }

    @Test
    void get_noBodyConditions_added() {
        // GET stubs must have empty bodyJsonPath (GET has no request body)
        StubDefinition stub = generator.generate("GET", "/api/v1/accounts/{id}",
                null, Map.of("balance", 500.0), 200);

        assertThat(stub.requestMatcher().bodyJsonPath()).isEmpty();
    }

    // ═══ gRPC + Kafka ═══

    @Test
    void grpc_responseFieldMatchingRequestField_becomesTemplate() {
        StubDefinition stub = generator.generate("GRPC", "GetEmployee", "",
                Map.of("account_id", "ACC-001"),
                Map.of("account_id", "ACC-001", "balance", 1000.0, "status", "ACTIVE"),
                200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) stub.responseTemplate().body();

        // account_id in both → echo from request body
        assertThat(body.get("account_id")).isEqualTo("{{request.body.account_id}}");
        // balance not in request → static
        assertThat(body.get("balance")).isEqualTo(1000.0);
        // No stateAction for gRPC
        assertThat(stub.stateAction()).isNull();
    }

    @Test
    void kafka_protocol_correctlySet() {
        StubDefinition stub = generator.generate("KAFKA", "payment-events",
                null, Map.of("event_type", "PAYMENT_COMPLETED"),
                Map.of("ack", true, "event_type", "PAYMENT_COMPLETED"),
                200);

        assertThat(stub.requestMatcher().protocol()).isEqualTo("KAFKA");
        assertThat(stub.requestMatcher().method()).isEqualTo("payment-events");
        assertThat(stub.requestMatcher().pathPattern()).isEqualTo("");
        assertThat(stub.stateAction()).isNull();
    }
}
