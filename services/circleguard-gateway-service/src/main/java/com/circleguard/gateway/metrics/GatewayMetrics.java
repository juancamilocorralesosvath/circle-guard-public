package com.circleguard.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetrics {

    private final Counter qrValidated;
    private final Counter qrRejected;
    private final Counter requestTotal;

    public GatewayMetrics(MeterRegistry registry) {
        this.qrValidated = Counter.builder("gateway.qr.validated")
                .description("Total QR codes successfully validated")
                .register(registry);

        this.qrRejected = Counter.builder("gateway.qr.rejected")
                .description("Total QR codes rejected (invalid or expired)")
                .register(registry);

        this.requestTotal = Counter.builder("gateway.request.total")
                .description("Total requests processed by the gateway")
                .register(registry);
    }

    public void recordQrValidated() {
        qrValidated.increment();
        requestTotal.increment();
    }

    public void recordQrRejected() {
        qrRejected.increment();
        requestTotal.increment();
    }

    public void recordRequest() {
        requestTotal.increment();
    }
}
