package com.circleguard.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final Counter requestCounter;
    private final Random random = new Random();

    public DemoController(MeterRegistry registry) {
        this.requestCounter = Counter.builder("demo.http.requests")
                .description("Manual HTTP request counter for demo endpoints")
                .register(registry);
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        requestCounter.increment();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "pong"));
    }

    @PostMapping("/operation")
    public ResponseEntity<Map<String, Object>> triggerOperation() {
        requestCounter.increment();
        boolean success = random.nextDouble() > 0.3;
        if (success) {
            return ResponseEntity.ok(Map.of("result", "success", "processed", true));
        } else {
            return ResponseEntity.internalServerError()
                    .body(Map.of("result", "error", "message", "Simulated failure"));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "application", "demo-observability",
                "version", "1.0.0",
                "metricsEndpoint", "/actuator/prometheus"
        ));
    }
}
