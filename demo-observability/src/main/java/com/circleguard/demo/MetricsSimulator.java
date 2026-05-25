package com.circleguard.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MetricsSimulator {

    private static final Logger log = LoggerFactory.getLogger(MetricsSimulator.class);
    private final Random random = new Random();

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer operationTimer;
    private final AtomicInteger activeUsers = new AtomicInteger(0);

    public MetricsSimulator(MeterRegistry registry) {
        this.successCounter = Counter.builder("demo.operations.success")
                .description("Number of successful demo operations")
                .register(registry);

        this.failureCounter = Counter.builder("demo.operations.failed")
                .description("Number of failed demo operations")
                .register(registry);

        this.operationTimer = Timer.builder("demo.operation.duration")
                .description("Duration of demo operations")
                .register(registry);

        Gauge.builder("demo.active.users", activeUsers, AtomicInteger::get)
                .description("Number of simulated active users")
                .register(registry);
    }

    @Scheduled(fixedDelay = 5000)
    public void simulateSuccessfulOperation() {
        long start = System.nanoTime();
        int users = random.nextInt(50) + 10;
        activeUsers.set(users);

        // Simulate processing time between 50ms and 200ms
        try {
            Thread.sleep(random.nextInt(150) + 50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = System.nanoTime() - start;
        operationTimer.record(duration, TimeUnit.NANOSECONDS);
        successCounter.increment();
        log.info("Operation completed successfully — active users: {}, duration: {}ms",
                users, TimeUnit.NANOSECONDS.toMillis(duration));
    }

    @Scheduled(fixedDelay = 15000)
    public void simulateOccasionalFailure() {
        // 40% chance of failure on each tick
        if (random.nextDouble() < 0.4) {
            failureCounter.increment();
            log.error("Operation failed — simulated error for observability demo [error_code=SIM_{}]",
                    random.nextInt(1000));
        } else {
            successCounter.increment();
            log.info("Scheduled batch check passed — no failures detected");
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void logSummary() {
        log.info("Metrics summary — success_total: {}, failed_total: {}, active_users: {}",
                (long) successCounter.count(),
                (long) failureCounter.count(),
                activeUsers.get());
    }
}
