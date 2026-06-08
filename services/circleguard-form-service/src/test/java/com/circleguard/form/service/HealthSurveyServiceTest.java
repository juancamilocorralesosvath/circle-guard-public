package com.circleguard.form.service;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.model.ValidationStatus;
import com.circleguard.form.repository.HealthSurveyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthSurveyServiceTest {

    private final HealthSurveyRepository repository = mock(HealthSurveyRepository.class);
    private final QuestionnaireService questionnaireService = mock(QuestionnaireService.class);
    private final SymptomMapper symptomMapper = mock(SymptomMapper.class);
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final HealthSurveyService service =
            new HealthSurveyService(repository, questionnaireService, symptomMapper, kafkaTemplate);

    @Test
    void shouldSubmitSurveyUsingActiveQuestionnaireSymptoms() {
        UUID anonymousId = UUID.randomUUID();
        Questionnaire questionnaire = Questionnaire.builder().title("Daily").build();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(anonymousId)
                .responses(Map.of("fever", true))
                .attachmentPath("certificate.pdf")
                .build();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.of(questionnaire));
        when(symptomMapper.hasSymptoms(survey, questionnaire)).thenReturn(true);
        when(repository.save(survey)).thenReturn(survey);

        HealthSurvey saved = service.submitSurvey(survey);

        assertSame(survey, saved);
        assertEquals(Boolean.TRUE, survey.getHasFever());
        assertEquals(Boolean.TRUE, survey.getHasCough());
        assertEquals(ValidationStatus.PENDING, survey.getValidationStatus());
        verify(kafkaTemplate).send(eq("survey.submitted"), eq(anonymousId.toString()), any(Map.class));
    }

    @Test
    void shouldSubmitSurveyUsingLegacySymptomsWhenNoActiveQuestionnaireExists() {
        UUID anonymousId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(anonymousId)
                .hasFever(false)
                .hasCough(true)
                .build();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.empty());
        when(repository.save(survey)).thenReturn(survey);

        service.submitSurvey(survey);

        verify(symptomMapper, never()).hasSymptoms(any(), any());
        verify(kafkaTemplate).send(eq("survey.submitted"), eq(anonymousId.toString()), any(Map.class));
    }

    @Test
    void shouldReturnPendingSurveys() {
        List<HealthSurvey> pending = List.of(HealthSurvey.builder().attachmentPath("cert.pdf").build());
        when(repository.findByAttachmentPathIsNotNullAndValidationStatus(ValidationStatus.PENDING)).thenReturn(pending);

        assertEquals(pending, service.getPendingSurveys());
    }

    @Test
    void shouldValidateSurveyAndEmitApprovedCertificateEvent() {
        UUID surveyId = UUID.randomUUID();
        UUID anonymousId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder().id(surveyId).anonymousId(anonymousId).build();
        when(repository.findById(surveyId)).thenReturn(Optional.of(survey));

        service.validateSurvey(surveyId, ValidationStatus.APPROVED, adminId);

        assertEquals(ValidationStatus.APPROVED, survey.getValidationStatus());
        assertEquals(adminId, survey.getValidatedBy());
        verify(repository).save(survey);
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);
        verify(kafkaTemplate).send(eq("certificate.validated"), eq(anonymousId.toString()), event.capture());
        assertEquals("APPROVED", event.getValue().get("status"));
    }

    @Test
    void shouldValidateSurveyWithoutEmittingEventWhenRejected() {
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        HealthSurvey survey = HealthSurvey.builder().id(surveyId).anonymousId(UUID.randomUUID()).build();
        when(repository.findById(surveyId)).thenReturn(Optional.of(survey));

        service.validateSurvey(surveyId, ValidationStatus.REJECTED, adminId);

        assertEquals(ValidationStatus.REJECTED, survey.getValidationStatus());
        verify(kafkaTemplate, never()).send(eq("certificate.validated"), any(), any());
    }

    @Test
    void shouldThrowWhenSurveyDoesNotExist() {
        UUID surveyId = UUID.randomUUID();
        when(repository.findById(surveyId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.validateSurvey(surveyId, ValidationStatus.APPROVED, UUID.randomUUID()));

        assertTrue(exception.getMessage().contains("Survey not found"));
    }
}
