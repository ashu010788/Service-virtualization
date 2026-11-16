package com.virtualization.servicevirtualizationplatform.kafkaembedded;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualization.servicevirtualizationplatform.engine.StubMatchingEngine;
import com.virtualization.servicevirtualizationplatform.model.VirtualRequest;
import com.virtualization.servicevirtualizationplatform.model.VirtualResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Consumes messages from configured Kafka input topics and routes them
 * through the existing StubMatchingEngine — the same engine that handles
 * HTTP and gRPC protocol=KAFKA stubs via the kafkaIndex.
 *
 * This is a NEW FRONT DOOR to the matching engine, analogous to
 * VirtualController (HTTP JSON) and ForwardingProxyController (HTTP proxy).
 * It does NOT modify any existing code.
 *
 * Flow:
 *   1. Kafka message arrives on input topic (e.g. "payments")
 *   2. Extract session ID from headers/key
 *   3. Parse message value as JSON → Map<String, Object>
 *   4. Build VirtualRequest(protocol=KAFKA, method=topic, path=topic, body=parsed)
 *   5. Delegate to StubMatchingEngine.matchAndExecute()
 *   6. If matched → publish response to output topic via KafkaEventProducer
 */
@Component
@ConditionalOnProperty(name = "kafka.embedded.enabled", havingValue = "true")
public class KafkaVirtualConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaVirtualConsumer.class);

    private final StubMatchingEngine matchingEngine;
    private final KafkaEventProducer eventProducer;
    private final KafkaSessionIdExtractor sessionIdExtractor;
    private final ObjectMapper objectMapper;

    public KafkaVirtualConsumer(StubMatchingEngine matchingEngine,
                                KafkaEventProducer eventProducer,
                                KafkaSessionIdExtractor sessionIdExtractor) {
        this.matchingEngine = matchingEngine;
        this.eventProducer = eventProducer;
        this.sessionIdExtractor = sessionIdExtractor;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Listens on all configured input topics.
     * Topic list is resolved from ${kafka.embedded.input-topics} property.
     */
    @KafkaListener(
            topics = "#{'${kafka.embedded.input-topics:payments}'.split(',')}",
            groupId = "${kafka.embedded.consumer-group:svp-virtual-consumer}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        String topic = record.topic();
        String sessionId = sessionIdExtractor.extract(record);

        log.debug("Kafka message received: topic={}, partition={}, offset={}, session={}",
                topic, record.partition(), record.offset(), sessionId);

        try {
            // Parse message value — supports any JSON body
            Map<String, Object> body = parseBody(record.value());

            // Convert Kafka headers to a flat Map<String, String>
            Map<String, String> headers = extractHeaders(record);

            // Build VirtualRequest — same contract as POST /api/v1/virtual
            VirtualRequest request = new VirtualRequest(
                    sessionId,
                    "KAFKA",          // protocol
                    topic,            // method = topic name (matches kafkaIndex key)
                    topic,            // path = topic (for traffic logging)
                    headers,
                    body
            );

            // Delegate to existing matching engine — ZERO changes to engine code
            VirtualResponse response = matchingEngine.matchAndExecute(request);

            // Publish response to output topic if a stub matched
            if (response.matchedStubId() != null) {
                eventProducer.publishResponse(topic, sessionId, response.body());
                log.debug("Kafka stub matched: stubId={}, session={}, topic={}",
                        response.matchedStubId(), sessionId, topic);
            } else {
                log.debug("No Kafka stub matched for topic={}, session={}", topic, sessionId);
            }

        } catch (Exception e) {
            log.error("Failed to process Kafka message: topic={}, session={}, error={}",
                    topic, sessionId, e.getMessage(), e);
        }
    }

    private Map<String, Object> parseBody(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            // If not valid JSON, wrap raw value
            return Map.of("_raw", value);
        }
    }

    private Map<String, String> extractHeaders(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new java.util.HashMap<>();
        for (Header header : record.headers()) {
            if (header.value() != null) {
                headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return headers;
    }
}
