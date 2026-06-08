package com.circleguard.auth.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthMetricsTest {

    @Test
    void shouldRecordAuthCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthMetrics metrics = new AuthMetrics(registry);

        metrics.recordLoginSuccess();
        metrics.recordLoginFailure();
        metrics.recordTokenIssued();
        metrics.recordQrGenerated();

        assertEquals(1.0, registry.counter("auth.login.success").count());
        assertEquals(1.0, registry.counter("auth.login.failure").count());
        assertEquals(1.0, registry.counter("auth.token.issued").count());
        assertEquals(1.0, registry.counter("auth.qr.generated").count());
    }
}
