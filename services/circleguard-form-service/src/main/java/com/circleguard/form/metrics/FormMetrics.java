package com.circleguard.form.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class FormMetrics {

    private final Counter surveysSubmitted;

    public FormMetrics(MeterRegistry registry) {
        this.surveysSubmitted = Counter.builder("form.surveys.submitted")
                .description("Total health surveys submitted")
                .register(registry);
    }

    public void recordSurveySubmitted() {
        surveysSubmitted.increment();
    }
}
