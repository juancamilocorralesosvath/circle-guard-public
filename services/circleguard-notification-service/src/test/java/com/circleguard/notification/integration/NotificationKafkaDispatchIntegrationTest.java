package com.circleguard.notification.integration;

import com.circleguard.notification.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration Test 4: Kafka Status-Change Event → Notification Multi-Channel Dispatch
 *
 * WHY CRITICAL:
 *   When promotion-service changes a user's health status, it publishes a
 *   "promotion.status.changed" event.  notification-service must consume this
 *   event and dispatch alerts via all three channels: Email, SMS, and Push.
 *   If any channel silently drops the event (wrong topic name, wrong deserializer,
 *   missing @KafkaListener groupId), affected users are never notified — a direct
 *   public-health communication failure.
 *
 * WHAT IS VALIDATED:
 *   1. SUSPECT status event → all three channels (email, SMS, push) are invoked.
 *   2. ACTIVE status event → NO notification channels are invoked (suppress noise).
 *   3. PROBABLE status event → all three channels are invoked.
 *   4. Event with unknown status → no exception thrown, channels NOT invoked.
 *   5. Multiple rapid events → each is processed independently (no dropped events).
 *
 * APPROACH:
 *   - @EmbeddedKafka for in-process Kafka.
 *   - @MockBean for EmailService, SmsService, PushService (all in mock mode).
 *   - Publish JSON strings with StringSerializer (matches StringDeserializer + listener(String)).
 *   - Mockito timeout() to handle async delivery within a 5-second window.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {"promotion.status.changed"},
        brokerProperties = {"auto.create.topics.enable=true"}
)
class NotificationKafkaDispatchIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmailService emailService;

    @MockBean
    private SmsService smsService;

    @MockBean
    private PushService pushService;

    @MockBean
    private JavaMailSender mailSender; // prevent real SMTP attempts

    private static final String TOPIC = "promotion.status.changed";

    @BeforeEach
    void resetMocks() {
        reset(emailService, smsService, pushService);
        // All channels respond with completed futures by default
        when(emailService.sendAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(smsService.sendAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(pushService.sendAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(pushService.sendAsync(anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private Map<String, Object> statusEvent(String anonymousId, String status) {
        return Map.of(
                "anonymousId", anonymousId,
                "status", status,
                "timestamp", System.currentTimeMillis()
        );
    }

    /** Match production: consumer uses StringDeserializer + JSON; use StringSerializer on the producer. */
    private void sendStatusEvent(String anonymousId, String status) {
        try {
            String json = objectMapper.writeValueAsString(statusEvent(anonymousId, status));
            kafkaTemplate.send(TOPIC, anonymousId, json);
            kafkaTemplate.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * IT-4.1 — SUSPECT event → all three channels dispatched
     */
    @Test
    @DisplayName("IT-4.1: SUSPECT status event triggers email, SMS, and push dispatch")
    void suspectEvent_TriggersAllThreeChannels() {
        String anonymousId = "user-suspect-" + System.nanoTime();

        sendStatusEvent(anonymousId, "SUSPECT");

        // Use Mockito timeout() to allow async Kafka consumption up to 5 s
        verify(emailService, timeout(5_000).times(1)).sendAsync(eq(anonymousId), anyString());
        verify(smsService,   timeout(5_000).times(1)).sendAsync(eq(anonymousId), anyString());
        verify(pushService,  timeout(5_000).times(1)).sendAsync(eq(anonymousId), anyString(), anyMap());
    }

    /**
     * IT-4.2 — ACTIVE status event → NO channels dispatched (suppress routine updates)
     */
    @Test
    @DisplayName("IT-4.2: ACTIVE status event does NOT trigger any notification channel")
    void activeEvent_DoesNotTriggerNotifications() throws InterruptedException {
        String anonymousId = "user-active-" + System.nanoTime();

        sendStatusEvent(anonymousId, "ACTIVE");

        // Wait a generous window for any spurious invocation
        TimeUnit.SECONDS.sleep(3);

        verify(emailService, never()).sendAsync(anyString(), anyString());
        verify(smsService,   never()).sendAsync(anyString(), anyString());
        verify(pushService,  never()).sendAsync(anyString(), anyString(), anyMap());
    }

    /**
     * IT-4.3 — PROBABLE status event → all three channels dispatched
     */
    @Test
    @DisplayName("IT-4.3: PROBABLE status event triggers all three notification channels")
    void probableEvent_TriggersAllThreeChannels() {
        String anonymousId = "user-probable-" + System.nanoTime();

        sendStatusEvent(anonymousId, "PROBABLE");

        verify(emailService, timeout(5_000).times(1)).sendAsync(eq(anonymousId), anyString());
        verify(smsService,   timeout(5_000).times(1)).sendAsync(eq(anonymousId), anyString());
        verify(pushService,  timeout(5_000).times(1)).sendAsync(eq(anonymousId), anyString(), anyMap());
    }

    /**
     * IT-4.4 — Unknown/arbitrary status → no channels dispatched, no exception
     */
    @Test
    @DisplayName("IT-4.4: Unknown status value does not cause exception and does not dispatch")
    void unknownStatus_NoDispatch_NoException() throws InterruptedException {
        String anonymousId = "user-unknown-" + System.nanoTime();

        // This must not crash the listener
        sendStatusEvent(anonymousId, "UNKNOWN");

        TimeUnit.SECONDS.sleep(3);

        verify(emailService, never()).sendAsync(anyString(), anyString());
        verify(smsService,   never()).sendAsync(anyString(), anyString());
    }

    /**
     * IT-4.5 — Two rapid SUSPECT events → each processed independently (no dropped events)
     */
    @Test
    @DisplayName("IT-4.5: Two consecutive SUSPECT events are each dispatched independently")
    void twoRapidSuspectEvents_BothProcessed() {
        String id1 = "user-rapid-1-" + System.nanoTime();
        String id2 = "user-rapid-2-" + System.nanoTime();

        sendStatusEvent(id1, "SUSPECT");
        sendStatusEvent(id2, "SUSPECT");

        // Each user must receive their own notification (2 total calls per channel)
        verify(emailService, timeout(8_000).times(2)).sendAsync(anyString(), anyString());
        verify(smsService,   timeout(8_000).times(2)).sendAsync(anyString(), anyString());
        verify(pushService,  timeout(8_000).times(2)).sendAsync(anyString(), anyString(), anyMap());
    }
}
