package com.circleguard.form.controller;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.ValidationStatus;
import com.circleguard.form.service.HealthSurveyService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CertificateValidationControllerTest {

    @Test
    void shouldReturnPendingSurveys() {
        HealthSurveyService service = mock(HealthSurveyService.class);
        List<HealthSurvey> pending = List.of(HealthSurvey.builder().attachmentPath("proof.pdf").build());
        when(service.getPendingSurveys()).thenReturn(pending);

        var response = new CertificateValidationController(service).getPending();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(pending, response.getBody());
    }

    @Test
    void shouldValidateSurvey() {
        HealthSurveyService service = mock(HealthSurveyService.class);
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        var response = new CertificateValidationController(service)
                .validate(surveyId, ValidationStatus.APPROVED, adminId);

        assertEquals(200, response.getStatusCode().value());
        verify(service).validateSurvey(surveyId, ValidationStatus.APPROVED, adminId);
    }
}
