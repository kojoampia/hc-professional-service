package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.KafkaTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP3 gate (professional-onboarding-workflow.md § Domain events): entity
 * creation publishes an {@code entity.created} record to
 * {@code hc.professional.entity} with the documented JSON envelope, keyed by
 * entityId, and carrying identifiers only — no PII.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DomainEventsKafkaIT {

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private KafkaTestContainer kafkaTestContainer;

    @Test
    @WithMockUser(username = "doctor", authorities = { "ROLE_DOCTOR" })
    void entityCreationPublishesEnvelopeWithoutPii() throws Exception {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("hc.professional.entity"));

            restMockMvc
                .perform(
                    post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"kafka-event-probe\",\"description\":\"probe\"}")
                )
                .andExpect(status().isCreated());

            ConsumerRecord<String, String> record = pollForRecord(consumer);
            assertThat(record).as("expected an entity.created record on hc.professional.entity").isNotNull();

            JsonNode envelope = om.readTree(record.value());
            assertThat(envelope.get("eventType").asText()).isEqualTo("entity.created");
            assertThat(envelope.get("source").asText()).isEqualTo("hc-professional-service");
            assertThat(envelope.get("actor").asText()).isEqualTo("doctor");
            assertThat(envelope.get("eventId").asText()).isNotBlank();
            assertThat(envelope.get("occurredAt").asText()).isNotBlank();

            JsonNode payload = envelope.get("payload");
            assertThat(payload.get("entityType").asText()).isEqualTo("Category");
            assertThat(payload.get("entityId").asText()).isNotBlank();
            // record key = entityId for per-entity ordering
            assertThat(record.key()).isEqualTo(payload.get("entityId").asText());

            // PII minimization: identifiers only — nothing else rides along
            Iterator<String> fieldNames = payload.fieldNames();
            while (fieldNames.hasNext()) {
                assertThat(fieldNames.next()).isIn("entityType", "entityId", "accountId");
            }
        }
    }

    private ConsumerRecord<String, String> pollForRecord(KafkaConsumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        return null;
    }

    private KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaTestContainer.getKafkaContainer().getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "wp3-gate-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
