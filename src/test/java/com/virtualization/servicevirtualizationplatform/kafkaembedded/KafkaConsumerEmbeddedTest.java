package com.virtualization.servicevirtualizationplatform.kafkaembedded;

import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.model.*;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests the KafkaVirtualConsumer — verifies that messages published to input
 * topics are consumed, routed through StubMatchingEngine, and responses are
 * published to output topics.
 *
 * Full round-trip: publish → consume → stub match → state mutate → produce response.
 *
 * Uses @EmbeddedKafka — no Docker required.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"payments", "payments-completed", "orders", "orders-completed"},
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
class KafkaConsumerEmbeddedTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StubRouter stubRouter;

    @Autowired
    private SessionStateStore stateStore;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, String> outputConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-consumer-group-" + System.nanoTime(), "true", embeddedKafka);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        outputConsumer = cf.createConsumer();
    }

    @AfterEach
    void tearDown() {
        if (outputConsumer != null) {
            outputConsumer.close();
        }
    }

    @Test
    @DisplayName("Consumer receives Kafka message, matches stub, publishes response to output topic")
    void fullRoundTripKafkaStubMatch() throws Exception {
        // Register a Kafka stub — uses existing StubRouter, no new API needed
        stubRouter.register(new StubDefinition(
                "stub_kafka_payment_embedded_v1",
                new RequestMatcher("KAFKA", "payments", "payments",
                        Map.of(), Map.of("$.currency", "USD")),
                new ResponseTemplate(200, Map.of("Content-Type", "application/json"),
                        Map.of("payment_id", "PAY-EMB-001", "status", "ACCEPTED", "currency", "USD")),
                null // no state action for this test
        ));

        // Subscribe to output topic BEFORE publishing
        outputConsumer.subscribe(List.of("payments-completed"));
        // Drain any pre-existing messages from other tests so we only see ours
        outputConsumer.poll(Duration.ofSeconds(2));

        // Publish a real Kafka message to input topic
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payments", null, "session-kafka-1",
                "{\"amount\":250.00,\"currency\":\"USD\",\"merchant\":\"ACME Corp\"}");
        record.headers().add("X-Session-ID", "session-kafka-1".getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

        // Consume from output topic and verify stubbed response — filter by our session key
        ConsumerRecords<String, String> outputRecords =
                KafkaTestUtils.getRecords(outputConsumer, Duration.ofSeconds(15));

        assertThat(outputRecords.count()).isGreaterThanOrEqualTo(1);

        // Find OUR response by session key to avoid cross-test pollution
        ConsumerRecord<String, String> response = null;
        for (ConsumerRecord<String, String> r : outputRecords) {
            if ("session-kafka-1".equals(r.key()) && r.value().contains("PAY-EMB-001")) {
                response = r;
                break;
            }
        }
        assertThat(response).as("Expected response for session-kafka-1 on payments-completed").isNotNull();
        assertThat(response.topic()).isEqualTo("payments-completed");
        assertThat(response.value()).contains("PAY-EMB-001");
        assertThat(response.value()).contains("ACCEPTED");

        // Clean up
        stubRouter.remove("stub_kafka_payment_embedded_v1");
    }

    @Test
    @DisplayName("Consumer extracts session ID from Kafka header and mutates session state")
    void sessionStateIsolationViaKafka() throws Exception {
        // Register a Kafka stub WITH state mutation
        stubRouter.register(new StubDefinition(
                "stub_kafka_order_state_v1",
                new RequestMatcher("KAFKA", "orders", "orders",
                        Map.of(), Map.of()),
                new ResponseTemplate(201, Map.of(),
                        Map.of("order_id", "ORD-K-001", "status", "CREATED")),
                new StateAction(StateOp.WRITE, "orders", "ORD-K-001",
                        "{{response.body}}")
        ));

        // Publish message for session-A
        ProducerRecord<String, String> recordA = new ProducerRecord<>(
                "orders", null, "session-A",
                "{\"item\":\"Widget\",\"quantity\":5}");
        recordA.headers().add("X-Session-ID", "session-A".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(recordA).get(5, TimeUnit.SECONDS);

        // Wait for consumer to process and mutate state
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> stateA = stateStore.get("session-A", "orders", "ORD-K-001");
            assertThat(stateA).isNotNull();
            assertThat(stateA.get("status")).isEqualTo("CREATED");
        });

        // Session B should NOT see session A's state
        Map<String, Object> stateB = stateStore.get("session-B", "orders", "ORD-K-001");
        assertThat(stateB).isNull();

        // Clean up
        stubRouter.remove("stub_kafka_order_state_v1");
    }

    @Test
    @DisplayName("Consumer uses message key as session ID when header is absent")
    void sessionIdFallbackToMessageKey() throws Exception {
        stubRouter.register(new StubDefinition(
                "stub_kafka_key_session_v1",
                new RequestMatcher("KAFKA", "payments", "payments",
                        Map.of(), Map.of()),
                new ResponseTemplate(200, Map.of(),
                        Map.of("result", "matched-via-key")),
                new StateAction(StateOp.WRITE, "key-test", "entry-1",
                        "{{response.body}}")
        ));

        // Publish WITHOUT X-Session-ID header — key "session-from-key" becomes session ID
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payments", "session-from-key", "{\"test\":true}");
        // No X-Session-ID header added
        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

        // State should be stored under session "session-from-key"
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> state = stateStore.get("session-from-key", "key-test", "entry-1");
            assertThat(state).isNotNull();
        });

        // Clean up
        stubRouter.remove("stub_kafka_key_session_v1");
    }

    @Test
    @DisplayName("Unmatched Kafka message does NOT produce to output topic")
    void unmatchedMessageNoOutput() throws Exception {
        // No stubs registered for this specific body shape
        outputConsumer.subscribe(List.of("payments-completed"));

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payments", "session-X",
                "{\"unknown_field\":\"no-stub-matches-this\"}");
        record.headers().add("X-Session-ID", "session-X".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

        // Give consumer time to process, then verify no output was produced
        Thread.sleep(3000);
        ConsumerRecords<String, String> outputRecords =
                KafkaTestUtils.getRecords(outputConsumer, Duration.ofSeconds(3));

        // Filter for messages with session-X key only (other tests may have produced messages)
        long matchingRecords = 0;
        for (ConsumerRecord<String, String> r : outputRecords) {
            if ("session-X".equals(r.key())) {
                matchingRecords++;
            }
        }
        assertThat(matchingRecords).isZero();
    }
}
