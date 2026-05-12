package com.circleguard.form.service;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Question;
import com.circleguard.form.model.QuestionType;
import com.circleguard.form.model.Questionnaire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test 2: SymptomMapper – Comprehensive Symptom Detection Logic
 *
 * WHY CRITICAL:
 *   SymptomMapper is the decision gate that determines whether a submitted health
 *   survey triggers a Kafka "survey.submitted" event with hasSymptoms=true, which
 *   in turn promotes the user to SUSPECT and restricts campus access.
 *   A false-negative (undetected symptoms) is a direct public-health failure.
 *   A false-positive (false alarm) causes unnecessary disruption.
 *
 * WHAT IS VALIDATED:
 *   1. YES_NO: "YES" answer to a fever question → symptomatic detected.
 *   2. YES_NO: "NO" answer to same question → not symptomatic.
 *   3. MULTI_CHOICE: non-empty symptom selection → symptomatic detected.
 *   4. MULTI_CHOICE: empty selection ("[]") → NOT symptomatic.
 *   5. Mixed questionnaire: cough YES + unrelated question NO → overall symptomatic.
 *   6. Null responses map → safely returns false (no NullPointerException).
 *   7. Question whose text does NOT contain "fever/cough/breathing" with YES → NOT symptomatic.
 */
class SymptomMapperExtendedTest {

    private SymptomMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SymptomMapper();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private Question buildQuestion(String text, QuestionType type) {
        return Question.builder()
                .id(UUID.randomUUID())
                .text(text)
                .type(type)
                .build();
    }

    private Questionnaire buildQuestionnaire(Question... questions) {
        return Questionnaire.builder()
                .questions(List.of(questions))
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.1 — YES_NO "YES" on fever question → symptomatic
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("YES to fever question is detected as symptomatic")
    void yesNo_FeverYes_IsSymptom() {
        Question q = buildQuestion("Do you have a fever today?", QuestionType.YES_NO);
        Questionnaire questionnaire = buildQuestionnaire(q);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(q.getId().toString(), "YES"))
                .build();

        assertTrue(mapper.hasSymptoms(survey, questionnaire),
                "YES answer to fever question must be detected as symptomatic");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.2 — YES_NO "NO" on fever question → not symptomatic
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("NO to fever question is NOT detected as symptomatic")
    void yesNo_FeverNo_IsNotSymptom() {
        Question q = buildQuestion("Do you have a fever today?", QuestionType.YES_NO);
        Questionnaire questionnaire = buildQuestionnaire(q);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(q.getId().toString(), "NO"))
                .build();

        assertFalse(mapper.hasSymptoms(survey, questionnaire),
                "NO answer to fever question must NOT be detected as symptomatic");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.3 — MULTI_CHOICE: non-empty selection for symptom question → symptomatic
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("MULTI_CHOICE non-empty selection for symptoms question is detected as symptomatic")
    void multiChoice_NonEmptySymptomSelection_IsSymptom() {
        Question q = buildQuestion("Which symptoms do you have?", QuestionType.MULTI_CHOICE);
        Questionnaire questionnaire = buildQuestionnaire(q);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(q.getId().toString(), "[\"cough\",\"fatigue\"]"))
                .build();

        assertTrue(mapper.hasSymptoms(survey, questionnaire),
                "Non-empty MULTI_CHOICE symptom selection must be symptomatic");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.4 — MULTI_CHOICE: empty selection ("[]") → not symptomatic
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("MULTI_CHOICE empty selection ([]) is NOT detected as symptomatic")
    void multiChoice_EmptySelection_IsNotSymptom() {
        Question q = buildQuestion("Which symptoms do you have?", QuestionType.MULTI_CHOICE);
        Questionnaire questionnaire = buildQuestionnaire(q);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(q.getId().toString(), "[]"))
                .build();

        assertFalse(mapper.hasSymptoms(survey, questionnaire),
                "Empty MULTI_CHOICE selection must NOT be symptomatic");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.5 — Mixed questionnaire: cough YES → overall symptomatic
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Mixed questionnaire: cough YES triggers symptomatic even if other answers are NO")
    void mixedQuestionnaire_CoughYes_OtherNo_IsSymptom() {
        Question q1 = buildQuestion("Do you have a cough?", QuestionType.YES_NO);
        Question q2 = buildQuestion("Have you traveled recently?", QuestionType.YES_NO);
        Questionnaire questionnaire = buildQuestionnaire(q1, q2);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(
                        q1.getId().toString(), "YES",
                        q2.getId().toString(), "NO"
                ))
                .build();

        assertTrue(mapper.hasSymptoms(survey, questionnaire),
                "Cough YES among multiple questions must be detected as symptomatic");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.6 — Null responses → returns false safely (no NPE)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Null responses map returns false without NullPointerException")
    void nullResponses_ReturnsFalse_WithoutException() {
        Question q = buildQuestion("Do you have a fever?", QuestionType.YES_NO);
        Questionnaire questionnaire = buildQuestionnaire(q);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(null)
                .build();

        assertDoesNotThrow(() -> mapper.hasSymptoms(survey, questionnaire),
                "hasSymptoms() must not throw when responses map is null");
        assertFalse(mapper.hasSymptoms(survey, questionnaire),
                "Null responses must return false");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2.7 — Unrelated question with YES → not symptomatic
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("YES to a question unrelated to fever/cough/breathing is NOT symptomatic")
    void yesTo_UnrelatedQuestion_IsNotSymptom() {
        Question q = buildQuestion("Did you register your visitor badge today?", QuestionType.YES_NO);
        Questionnaire questionnaire = buildQuestionnaire(q);
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(q.getId().toString(), "YES"))
                .build();

        assertFalse(mapper.hasSymptoms(survey, questionnaire),
                "YES to a non-medical question must NOT be treated as symptomatic");
    }
}
