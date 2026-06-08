package com.circleguard.form.controller;

import com.circleguard.form.metrics.FormMetrics;
import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.service.HealthSurveyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/surveys")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class HealthSurveyController {
    private final HealthSurveyService surveyService;
    private final FormMetrics formMetrics;

    @PostMapping
    public ResponseEntity<HealthSurvey> submit(@RequestBody HealthSurvey survey) {
        if (survey == null || survey.getAnonymousId() == null) {
            throw new IllegalArgumentException("anonymousId is required");
        }
        HealthSurvey saved = surveyService.submitSurvey(survey);
        formMetrics.recordSurveySubmitted();
        return ResponseEntity.ok(saved);
    }
}
