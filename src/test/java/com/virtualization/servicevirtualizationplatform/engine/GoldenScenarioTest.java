package com.virtualization.servicevirtualizationplatform.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parametrized golden-file test that loads 30 labeled scenarios from
 * stub_match_scenarios.json and validates stub matching + status codes.
 */
@SpringBootTest
class GoldenScenarioTest {

    @Autowired
    private StubMatchingEngine engine;

    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    @SuppressWarnings("unchecked")
    Collection<DynamicTest> goldenScenarios() throws Exception {
        InputStream is = getClass().getResourceAsStream("/stub_match_scenarios.json");
        List<Map<String, Object>> scenarios = mapper.readValue(is, new TypeReference<>() {});

        return scenarios.stream().map(sc -> {
            String id = (String) sc.get("id");
            String label = (String) sc.get("label");
            Map<String, Object> req = (Map<String, Object>) sc.get("request");
            String expectedStubId = (String) sc.get("expectedStubId");
            int expectedStatus = ((Number) sc.get("expectedStatus")).intValue();

            return DynamicTest.dynamicTest(id + ": " + label, () -> {
                Map<String, String> headers = req.get("headers") != null
                        ? ((Map<String, Object>) req.get("headers")).entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())))
                        : Map.of();

                Map<String, Object> body = req.get("body") != null
                        ? (Map<String, Object>) req.get("body")
                        : null;

                VirtualRequest vr = new VirtualRequest(
                        (String) req.get("sessionId"),
                        (String) req.get("protocol"),
                        (String) req.get("method"),
                        (String) req.get("path"),
                        headers,
                        body
                );

                VirtualResponse res = engine.matchAndExecute(vr);

                assertThat(res.statusCode())
                        .as("Status code for %s", id)
                        .isEqualTo(expectedStatus);

                if (expectedStubId != null) {
                    assertThat(res.matchedStubId())
                            .as("Matched stub for %s", id)
                            .isEqualTo(expectedStubId);
                } else {
                    assertThat(res.matchedStubId())
                            .as("Should not match for %s", id)
                            .isNull();
                }
            });
        }).collect(Collectors.toList());
    }
}
