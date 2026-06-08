package com.circleguard.form.service;

import com.circleguard.form.model.Question;
import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.repository.QuestionnaireRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionnaireServiceTest {

    @Test
    void shouldReadQuestionnairesFromRepository() {
        QuestionnaireRepository repository = mock(QuestionnaireRepository.class);
        Questionnaire questionnaire = Questionnaire.builder().title("Daily").build();
        when(repository.findAll()).thenReturn(List.of(questionnaire));
        when(repository.findFirstByIsActiveTrueOrderByVersionDesc()).thenReturn(Optional.of(questionnaire));
        QuestionnaireService service = new QuestionnaireService(repository);

        assertEquals(List.of(questionnaire), service.getAllQuestionnaires());
        assertEquals(Optional.of(questionnaire), service.getActiveQuestionnaire());
    }

    @Test
    void shouldAttachQuestionsBeforeSavingQuestionnaire() {
        QuestionnaireRepository repository = mock(QuestionnaireRepository.class);
        Question question = Question.builder().text("Fever?").build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(question)).build();
        when(repository.save(questionnaire)).thenReturn(questionnaire);

        Questionnaire saved = new QuestionnaireService(repository).saveQuestionnaire(questionnaire);

        assertSame(questionnaire, saved);
        assertSame(questionnaire, question.getQuestionnaire());
    }

    @Test
    void shouldActivateTargetQuestionnaireAndDeactivateCurrentActiveOnes() {
        QuestionnaireRepository repository = mock(QuestionnaireRepository.class);
        UUID targetId = UUID.randomUUID();
        Questionnaire active = Questionnaire.builder().id(UUID.randomUUID()).isActive(true).build();
        Questionnaire inactive = Questionnaire.builder().id(UUID.randomUUID()).isActive(false).build();
        Questionnaire target = Questionnaire.builder().id(targetId).isActive(false).build();
        when(repository.findAll()).thenReturn(List.of(active, inactive));
        when(repository.findById(targetId)).thenReturn(Optional.of(target));

        new QuestionnaireService(repository).activateQuestionnaire(targetId);

        assertFalse(active.getIsActive());
        assertTrue(target.getIsActive());
        verify(repository).save(active);
        verify(repository).save(target);
    }
}
