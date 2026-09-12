package com.blindway.perception.application;

import com.blindway.perception.infrastructure.MqttInboxRow;
import com.blindway.perception.infrastructure.PerceptionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MqttInboxWorker {

    private static final Logger log = LoggerFactory.getLogger(MqttInboxWorker.class);
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 30_000;

    private final PerceptionMapper mapper;
    private final MqttInboxProcessor processor;
    private final String owner;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Counter retried;
    private final Counter dead;
    private final Counter pollFailed;
    private final AtomicLong nextFailureLogAt = new AtomicLong();
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong processingCount = new AtomicLong();
    private final AtomicLong deadCount = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public MqttInboxWorker(
            PerceptionMapper mapper,
            MqttInboxProcessor processor,
            MeterRegistry meterRegistry,
            @Value("${blindway.mqtt.worker.owner:${HOSTNAME:blindway-local}}") String owner,
            @Value("${blindway.mqtt.worker.batch-size:50}") int batchSize,
            @Value("${blindway.mqtt.worker.lease-duration:30s}") Duration leaseDuration,
            @Value("${blindway.mqtt.worker.max-attempts:5}") int maxAttempts) {
        this.mapper = mapper;
        this.processor = processor;
        this.owner = owner;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        this.retried = meterRegistry.counter("blindway.mqtt.inbox", "result", "retried");
        this.dead = meterRegistry.counter("blindway.mqtt.inbox", "result", "dead");
        this.pollFailed = meterRegistry.counter("blindway.mqtt.inbox", "result", "poll_failed");
        Gauge.builder("blindway.mqtt.inbox.pending", pendingCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("blindway.mqtt.inbox.processing", processingCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("blindway.mqtt.inbox.dead", deadCount, AtomicLong::get).register(meterRegistry);
        Gauge.builder("blindway.mqtt.inbox.oldest.age.seconds", oldestPendingAgeSeconds, AtomicLong::get)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${blindway.mqtt.worker.poll-interval:100ms}")
    public void processAvailable() {
        try {
            Instant now = Instant.now();
            for (MqttInboxRow row : mapper.claimInboxBatch(owner, now, now.plus(leaseDuration), batchSize)) {
                try {
                    processor.process(row, owner);
                } catch (RuntimeException exception) {
                    handleFailure(row, exception);
                }
            }
        } catch (RuntimeException exception) {
            recordPollFailure("claim/process", exception);
        }
    }

    @Scheduled(fixedDelayString = "${blindway.mqtt.worker.metrics-interval:5s}")
    public void refreshMetrics() {
        try {
            pendingCount.set(mapper.countInboxByStatus("PENDING"));
            processingCount.set(mapper.countInboxByStatus("PROCESSING"));
            deadCount.set(mapper.countInboxByStatus("DEAD"));
            oldestPendingAgeSeconds.set(mapper.oldestPendingAgeSeconds(Instant.now()));
        } catch (RuntimeException exception) {
            recordPollFailure("metrics", exception);
        }
    }

    private void handleFailure(MqttInboxRow row, RuntimeException exception) {
        boolean exhausted = row.attemptCount() >= maxAttempts;
        String status = exhausted ? "DEAD" : "PENDING";
        String message = safeMessage(exception);
        mapper.markInboxFailed(
                row.eventId(), owner, status, message, Instant.now().plus(retryBackoff(row.attemptCount())));
        if (exhausted) {
            dead.increment();
        } else {
            retried.increment();
        }
    }

    private Duration retryBackoff(int attempt) {
        return Duration.ofSeconds(Math.min(60, 1L << Math.min(Math.max(attempt - 1, 0), 6)));
    }

    private String safeMessage(Throwable exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private void recordPollFailure(String operation, RuntimeException exception) {
        pollFailed.increment();
        long now = System.currentTimeMillis();
        long next = nextFailureLogAt.get();
        if (now >= next && nextFailureLogAt.compareAndSet(next, now + FAILURE_LOG_INTERVAL_MILLIS)) {
            log.warn("MQTT Inbox {} unavailable; worker will retry: {}", operation, safeMessage(exception));
        }
    }
}
