package com.virtualization.servicevirtualizationplatform.kafkaembedded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Publishes stubbed responses to Kafka output topics.
 *
 * Output topic = input topic + configurable suffix (default: "-completed").
 * e.g., input "payments" → output "payments-completed"
 *
 * The session ID is propagated as a Kafka header on the outbound message
 * so downstream consumers (the app-under-test) can correlate responses.
 */
@Component
@ConditionalOnProperty(name = "kafka.embedded.enabled", havingValue = "true")
public class KafkaEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.embedded.output-topic-suffix:-completed}")
    private String outputTopicSuffix;

    @Value("${kafka.embedded.session-id-header:X-Session-ID}")
    private String sessionIdHeader;

    public KafkaEventProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Publish a stubbed response to the output topic.
     *
     * @param inputTopic  the topic the original message was consumed from
     * @param sessionId   session ID to propagate as a header
     * @param responseBody the stub's resolved response body
     */
    public void publishResponse(String inputTopic, String sessionId, Map<String, Object> responseBody) {
        String outputTopic = inputTopic + outputTopicSuffix;
        try {
            String payload = objectMapper.writeValueAsString(responseBody);
            ProducerRecord<String, String> record = new ProducerRecord<>(outputTopic, sessionId, payload);
            record.headers().add(sessionIdHeader, sessionId.getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish to topic={}: {}", outputTopic, ex.getMessage());
                } else {
                    log.debug("Published to topic={}, partition={}, offset={}, session={}",
                            outputTopic, result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(), sessionId);
                }
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response body for topic={}: {}", outputTopic, e.getMessage());
        }
    }

    /**
     * Publish a raw string payload to a specific topic.
     */
    public void publish(String topic, String key, String value) {
        kafkaTemplate.send(topic, key, value).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to topic={}: {}", topic, ex.getMessage());
            } else {
                log.debug("Published to topic={}, offset={}", topic, result.getRecordMetadata().offset());
            }
        });
    }
}
