package com.circleguard.promotion.listener;

import com.circleguard.promotion.service.HealthStatusService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SurveyListenerExtraTest {

    @Test
    void shouldIgnoreNonSymptomaticOrMalformedSurveyEvents() {
        HealthStatusService service = mock(HealthStatusService.class);
        SurveyListener listener = new SurveyListener(service);

        listener.onSurveySubmitted(Map.of("anonymousId", "u1", "hasSymptoms", false));
        listener.onSurveySubmitted(Map.of("hasSymptoms", true));
        listener.onSurveySubmitted(Map.of("anonymousId", "u1", "hasSymptoms", "yes"));

        verify(service, never()).updateStatus("u1", "SUSPECT");
    }

    @Test
    void shouldIgnoreNonApprovedOrMalformedCertificateEvents() {
        HealthStatusService service = mock(HealthStatusService.class);
        SurveyListener listener = new SurveyListener(service);

        listener.onCertificateValidated(Map.of("anonymousId", "u1", "status", "REJECTED"));
        listener.onCertificateValidated(Map.of("status", "APPROVED"));
        listener.onCertificateValidated(Map.of("anonymousId", "u1", "status", 1));

        verify(service, never()).updateStatus("u1", "ACTIVE");
    }
}
