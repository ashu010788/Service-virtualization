package com.virtualization.servicevirtualizationplatform.kafkaembedded;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the KafkaEventProducer — verifies that the producer can publish
 * messages to real Kafka topics and that a consumer can read them back.
 *
 * Uses @EmbeddedKafka to spin up a real KRaft broker in-process.
 * No Docker required.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"payments-completed", "orders-completed", "test-produce-topic"},
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:0",
                "port=0"
        }
)
@TestPropertySource(properties = {
        "kafka.embedded.enabled=true",
        "kafka.embedded.input-topics=payments,orders",
        "kafka.embedded.output-topic-suffix=-completed",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaProducerEmbeddedTest {

    @Autowired
    private KafkaEventProducer eventProducer;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-producer-group", "true", embeddedKafka);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        testConsumer = cf.createConsumer();
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    @Test
    @DisplayName("KafkaEventProducer publishes response to output topic (inputTopic + suffix)")
    void publishResponseToOutputTopic() {
        testConsumer.subscribe(List.of("payments-completed"));

        // Publish a stubbed response via producer
        Map<String, Object> responseBody = Map.of(
                "payment_id", "PAY-001",
                "status", "COMPLETED",
                "amount", 100.0
        );
        eventProducer.publishResponse("payments", "session-A", responseBody);

        // Consume and verify
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(10));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> received = records.iterator().next();
        assertThat(received.topic()).isEqualTo("payments-completed");
        assertThat(received.key()).isEqualTo("session-A");
        assertThat(received.value()).contains("PAY-001");
        assertThat(received.value()).contains("COMPLETED");
        assertThat(received.value()).contains("100.0");

        // Verify session ID header is propagated
        String sessionHeader = new String(
                received.headers().lastHeader("X-Session-ID").value(), StandardCharsets.UTF_8);
        assertThat(sessionHeader).isEqualTo("session-A");
    }

    @Test
    @DisplayName("KafkaEventProducer.publish sends raw payload to any topic")
    void publishRawPayloadToTopic() {
        testConsumer.subscribe(List.of("test-produce-topic"));

        eventProducer.publish("test-produce-topic", "key-1", "{\"event\":\"test\"}");

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(10));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> received = records.iterator().next();
        assertThat(received.topic()).isEqualTo("test-produce-topic");
        assertThat(received.key()).isEqualTo("key-1");
        assertThat(received.value()).isEqualTo("{\"event\":\"test\"}");
    }

    @Test
    @DisplayName("Producer handles multiple messages to same output topic")
    void multipleMessagesToSameTopic() {
        testConsumer.subscribe(List.of("orders-completed"));

        eventProducer.publishResponse("orders", "session-B",
                Map.of("order_id", "ORD-001", "status", "SHIPPED"));
        eventProducer.publishResponse("orders", "session-B",
                Map.of("order_id", "ORD-002", "status", "DELIVERED"));

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(10));
        assertThat(records.count()).isGreaterThanOrEqualTo(2);

        boolean foundShipped = false;
        boolean foundDelivered = false;
        for (ConsumerRecord<String, String> r : records) {
            if (r.value().contains("SHIPPED")) foundShipped = true;
            if (r.value().contains("DELIVERED")) foundDelivered = true;
        }
        assertThat(foundShipped).isTrue();
        assertThat(foundDelivered).isTrue();
    }
}
