package com.virtualization.servicevirtualizationplatform.kafkaembedded;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka configuration — activated only when kafka.embedded.enabled=true.
 *
 * All topic names, group IDs, and session-ID header names are configurable
 * via application.properties. This entire module is opt-in and does NOT
 * affect existing functionality when disabled.
 */
@Configuration
@ConditionalOnProperty(name = "kafka.embedded.enabled", havingValue = "true")
public class KafkaEmbeddedConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.embedded.consumer-group:svp-virtual-consumer}")
    private String consumerGroup;

    @Value("#{'${kafka.embedded.input-topics:payments}'.split(',')}")
    private List<String> inputTopics;

    @Value("${kafka.embedded.output-topic-suffix:-completed}")
    private String outputTopicSuffix;

    @Value("${kafka.embedded.session-id-header:X-Session-ID}")
    private String sessionIdHeader;

    @Value("${kafka.embedded.default-session-id:kafka-default}")
    private String defaultSessionId;

    @Bean
    public ProducerFactory<String, String> kafkaProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(kafkaProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, String> kafkaConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaConsumerFactory());
        return factory;
    }

    public List<String> getInputTopics() {
        return inputTopics;
    }

    public String getOutputTopicSuffix() {
        return outputTopicSuffix;
    }

    public String getSessionIdHeader() {
        return sessionIdHeader;
    }

    public String getDefaultSessionId() {
        return defaultSessionId;
    }
}
