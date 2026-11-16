package com.virtualization.servicevirtualizationplatform.kafkaembedded;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Extracts session ID from a Kafka ConsumerRecord.
 *
 * Strategy (in priority order):
 *   1. Kafka header matching configured header name (default: "X-Session-ID")
 *   2. Message key (used directly as session ID)
 *   3. Configurable default session ID (default: "kafka-default")
 */
@Component
@ConditionalOnProperty(name = "kafka.embedded.enabled", havingValue = "true")
public class KafkaSessionIdExtractor {

    @Value("${kafka.embedded.session-id-header:X-Session-ID}")
    private String sessionIdHeader;

    @Value("${kafka.embedded.default-session-id:kafka-default}")
    private String defaultSessionId;

    public String extract(ConsumerRecord<String, String> record) {
        // 1. Check Kafka header
        Header header = record.headers().lastHeader(sessionIdHeader);
        if (header != null && header.value() != null) {
            String value = new String(header.value(), StandardCharsets.UTF_8);
            if (!value.isBlank()) {
                return value;
            }
        }

        // 2. Use message key
        if (record.key() != null && !record.key().isBlank()) {
            return record.key();
        }

        // 3. Fallback to default
        return defaultSessionId;
    }
}
