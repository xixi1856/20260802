package com.blindway.common.application;

import com.blindway.common.infrastructure.EventOutboxMapper;
import com.blindway.common.infrastructure.OutboxRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blindway.kafka", name = "enabled", havingValue = "true")
public class KafkaOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 30_000;

    private final EventOutboxMapper mapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String owner;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration sendTimeout;
    private final int maxAttempts;
    private final Counter published;
    private final Counter failed;
    private final Counter dead;
    private final Counter pollFailed;
    private final Timer publishTimer;
    private final AtomicLong nextFailureLogAt = new AtomicLong();

    public KafkaOutboxPublisher(
            EventOutboxMapper mapper,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${blindway.kafka.publisher.owner:${HOSTNAME:blindway-local}}") String owner,
            @Value("${blindway.kafka.publisher.batch-size:50}") int batchSize,
            @Value("${blindway.kafka.publisher.lease-duration:30s}") Duration leaseDuration,
            @Value("${blindway.kafka.publisher.send-timeout:5s}") Duration sendTimeout,
            @Value("${blindway.kafka.publisher.max-attempts:10}") int maxAttempts) {
        this.mapper = mapper;
        this.kafkaTemplate = kafkaTemplate;
        this.owner = owner;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.sendTimeout = sendTimeout;
        this.maxAttempts = maxAttempts;
        this.published = meterRegistry.counter("blindway.kafka.outbox", "result", "published");
        this.failed = meterRegistry.counter("blindway.kafka.outbox", "result", "failed");
        this.dead = meterRegistry.counter("blindway.kafka.outbox", "result", "dead");
        this.pollFailed = meterRegistry.counter("blindway.kafka.outbox", "result", "poll_failed");
        this.publishTimer = meterRegistry.timer("blindway.kafka.outbox.publish");
    }

    @Scheduled(fixedDelayString = "${blindway.kafka.publisher.poll-interval:100ms}")
    public void publishAvailable() {
        try {
            Instant now = Instant.now();
            mapper.claimBatch(owner, now, now.plus(leaseDuration), batchSize).forEach(this::publish);
        } catch (RuntimeException exception) {
            pollFailed.increment();
            long now = System.currentTimeMillis();
            long next = nextFailureLogAt.get();
            if (now >= next && nextFailureLogAt.compareAndSet(next, now + FAILURE_LOG_INTERVAL_MILLIS)) {
                log.warn("Kafka Outbox polling unavailable; publisher will retry: {}", safeMessage(exception));
            }
        }
    }

    private void publish(OutboxRow row) {
        try {
            publishTimer.recordCallable(() -> {
                kafkaTemplate
                        .send(row.topic(), row.partitionKey(), row.payload())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                return null;
            });
            mapper.markPublished(row.id(), owner, Instant.now());
            published.increment();
        } catch (Exception exception) {
            int nextAttempt = row.attemptCount() + 1;
            String status = nextAttempt >= maxAttempts ? "DEAD" : "PENDING";
            Duration backoff = retryBackoff(nextAttempt);
            String message = exception.getCause() == null
                    ? exception.getMessage()
                    : exception.getCause().getMessage();
            mapper.markFailed(
                    row.id(),
                    owner,
                    nextAttempt,
                    status,
                    safeMessage(message),
                    Instant.now().plus(backoff));
            failed.increment();
            if ("DEAD".equals(status)) {
                dead.increment();
            }
        }
    }

    private Duration retryBackoff(int attempt) {
        long seconds = Math.min(300, 1L << Math.min(attempt - 1, 8));
        return Duration.ofSeconds(seconds);
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Kafka publish failed" : message;
    }

    private String safeMessage(Throwable exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return safeMessage(cause.getMessage());
    }
}
