package com.circleguard.form.integration;

import com.circleguard.form.model.*;
import com.circleguard.form.service.HealthSurveyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test 2: Survey Submission → Kafka Event → Promotion Trigger
 *
 * WHY CRITICAL:
 *   This is the primary async health-fencing pipeline.  A student submits a
 *   symptom report → form-service saves it → form-service publishes a Kafka
 *   "survey.submitted" event → promotion-service consumes it and changes the
 *   user's status to SUSPECT.  If the Kafka event is malformed, missing, or
 *   never published, the promotion-service never fires and the student is NOT
 *   fenced — a direct public-health failure.
 *
 * WHAT IS VALIDATED:
 *   1. Symptomatic survey (hasFever=true) → Kafka event is published to "survey.submitted".
 *   2. Published event contains anonymousId and hasSymptoms=true fields.
 *   3. Asymptomatic survey (hasFever=false) → event published with hasSymptoms=false.
 *   4. Event is published within a reasonable time window (< 5 s).
 *   5. Survey with attachment → event still published (attachment upload is separate).
 *
 * APPROACH:
 *   - PostgreSQL Testcontainer for persistence.
 *   - @EmbeddedKafka for in-process Kafka broker (no Docker Kafka needed).
 *   - An in-process KafkaListener captures the published events for assertions.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"survey.submitted"}, brokerProperties = {
        "listeners=PLAINTEXT://localhost:${kafka.port:0}",
        "auto.create.topics.enable=true"
})
@Testcontainers
class SurveyKafkaCascadeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_form")
            .withUsername("admin")
            .withPassword("password");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private HealthSurveyService surveyService;

    // ── In-process event capture ──────────────────────────────────────────────

    /**
     * Inner Spring component that captures Kafka messages published by form-service
     * during the test. Uses a BlockingQueue so tests can await() with a timeout.
     */
    @Component
    static class SurveyEventCapture {
        private final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "survey.submitted", groupId = "integration-test-group")
        void capture(Map<String, Object> event) {
            events.add(event);
        }

        Map<String, Object> poll(long timeoutSeconds) throws InterruptedException {
            return events.poll(timeoutSeconds, TimeUnit.SECONDS);
        }

        void clear() { events.clear(); }
    }

    @Autowired
    private SurveyEventCapture capture;

    @BeforeEach
    void clearCapture() {
        capture.clear();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * IT-2.1 — Symptomatic survey publishes event with hasSymptoms=true
     */
    @Test
    @DisplayName("IT-2.1: Symptomatic survey submission publishes Kafka event with hasSymptoms=true")
    void symptomaticSurvey_PublishesEvent_WithHasSymptomsTrue() throws InterruptedException {
        UUID anonymousId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(anonymousId)
                .hasFever(true)
                .hasCough(false)
                .build();

        surveyService.submitSurvey(survey);

        Map<String, Object> event = capture.poll(5);
        assertThat(event).isNotNull();
        assertThat(event.get("anonymousId")).isEqualTo(anonymousId.toString());
        assertThat(event.get("hasSymptoms")).isEqualTo(true);
    }

    /**
     * IT-2.2 — Asymptomatic survey publishes event with hasSymptoms=false
     */
    @Test
    @DisplayName("IT-2.2: Asymptomatic survey publishes Kafka event with hasSymptoms=false")
    void asymptomaticSurvey_PublishesEvent_WithHasFalse() throws InterruptedException {
        UUID anonymousId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(anonymousId)
                .hasFever(false)
                .hasCough(false)
                .build();

        surveyService.submitSurvey(survey);

        Map<String, Object> event = capture.poll(5);
        assertThat(event).isNotNull();
        assertThat(event.get("hasSymptoms")).isEqualTo(false);
    }

    /**
     * IT-2.3 — Event published within 5-second SLA window
     */
    @Test
    @DisplayName("IT-2.3: Survey event is published and received within 5 seconds")
    void surveyEvent_PublishedWithin5Seconds() throws InterruptedException {
        UUID anonymousId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(anonymousId)
                .hasFever(true)
                .build();

        long start = System.currentTimeMillis();
        surveyService.submitSurvey(survey);
        Map<String, Object> event = capture.poll(5);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(event).isNotNull();
        assertThat(elapsed).isLessThan(5_000L);
    }

    /**
     * IT-2.4 — Event payload includes a timestamp field
     */
    @Test
    @DisplayName("IT-2.4: Kafka event payload includes a timestamp field")
    void surveyEvent_ContainsTimestampField() throws InterruptedException {
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(UUID.randomUUID())
                .hasFever(true)
                .build();

        surveyService.submitSurvey(survey);

        Map<String, Object> event = capture.poll(5);
        assertThat(event).isNotNull();
        assertThat(event).containsKey("timestamp");
        assertThat(event.get("timestamp")).isNotNull();
    }

    /**
     * IT-2.5 — Survey with attachment path → event is still published
     */
    @Test
    @DisplayName("IT-2.5: Survey with an attachment path still publishes the Kafka event")
    void surveyWithAttachment_StillPublishesEvent() throws InterruptedException {
        UUID anonymousId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(anonymousId)
                .hasFever(true)
                .attachmentPath("cert-" + anonymousId + ".pdf")
                .validationStatus(ValidationStatus.PENDING)
                .build();

        surveyService.submitSurvey(survey);

        Map<String, Object> event = capture.poll(5);
        assertThat(event).isNotNull();
        assertThat(event.get("anonymousId")).isEqualTo(anonymousId.toString());
    }
}
