package com.circleguard.auth.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AuthMetrics {

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter tokenIssued;

    public AuthMetrics(MeterRegistry registry) {
        this.loginSuccess = Counter.builder("auth.login.success")
                .description("Total successful login attempts")
                .register(registry);

        this.loginFailure = Counter.builder("auth.login.failure")
                .description("Total failed login attempts")
                .register(registry);

        this.tokenIssued = Counter.builder("auth.token.issued")
                .description("Total JWT tokens issued")
                .register(registry);
    }

    public void recordLoginSuccess() {
        loginSuccess.increment();
    }

    public void recordLoginFailure() {
        loginFailure.increment();
    }

    public void recordTokenIssued() {
        tokenIssued.increment();
    }
}
