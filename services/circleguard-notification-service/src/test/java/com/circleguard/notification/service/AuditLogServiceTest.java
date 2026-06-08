package com.circleguard.notification.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditLogServiceTest {

    @Test
    void shouldPublishAuditEventWithProvidedCorrelationId() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);

        new AuditLogService(kafkaTemplate).logDelivery("user-1", "EMAIL", "SUCCESS", "corr-1");

        verify(kafkaTemplate).send(eq("notification.audit"), eq("user-1"), event.capture());
        assertEquals("user-1", event.getValue().get("userId"));
        assertEquals("EMAIL", event.getValue().get("channel"));
        assertEquals("SUCCESS", event.getValue().get("status"));
        assertEquals("corr-1", event.getValue().get("correlationId"));
        assertNotNull(event.getValue().get("eventId"));
    }

    @Test
    void shouldGenerateCorrelationIdWhenMissing() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);

        new AuditLogService(kafkaTemplate).logDelivery("user-1", "PUSH", "FAILED", null);

        verify(kafkaTemplate).send(eq("notification.audit"), eq("user-1"), event.capture());
        assertNotNull(event.getValue().get("correlationId"));
    }
}
