package com.circleguard.form.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormMetricsTest {

    @Test
    void shouldCountSubmittedSurveys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FormMetrics metrics = new FormMetrics(registry);

        metrics.recordSurveySubmitted();

        assertEquals(1.0, registry.counter("form.surveys.submitted").count());
    }
}
