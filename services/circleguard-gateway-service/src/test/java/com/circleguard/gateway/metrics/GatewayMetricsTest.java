package com.circleguard.gateway.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayMetricsTest {

    @Test
    void shouldIncrementGatewayCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry);

        metrics.recordQrValidated();
        metrics.recordQrRejected();
        metrics.recordRequest();

        assertEquals(1.0, registry.counter("gateway.qr.validated").count());
        assertEquals(1.0, registry.counter("gateway.qr.rejected").count());
        assertEquals(3.0, registry.counter("gateway.request.total").count());
    }
}
