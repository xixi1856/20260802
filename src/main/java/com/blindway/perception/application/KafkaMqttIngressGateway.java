package com.blindway.perception.application;

import com.blindway.common.EventTopics;
import com.blindway.perception.RawMqttIngressEvent;
import com.blindway.perception.infrastructure.MqttProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "blindway.mqtt", name = "ingress-mode", havingValue = "kafka")
public class KafkaMqttIngressGateway implements MqttIngressGateway {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MqttProperties properties;
    private final Duration sendTimeout;
    private final Semaphore inFlight;
    private final Counter accepted;
    private final Counter rejected;
    private final Counter failed;
    private final Timer publishTimer;

    public KafkaMqttIngressGateway(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            MqttProperties properties,
            MeterRegistry meterRegistry,
            @Value("${blindway.mqtt.kafka-send-timeout:30s}") Duration sendTimeout,
            @Value("${blindway.mqtt.kafka-max-in-flight:1000}") int maxInFlight) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.sendTimeout = sendTimeout;
        this.inFlight = new Semaphore(maxInFlight);
        this.accepted = meterRegistry.counter("blindway.mqtt.kafka.ingress", "result", "accepted");
        this.rejected = meterRegistry.counter("blindway.mqtt.kafka.ingress", "result", "rejected");
        this.failed = meterRegistry.counter("blindway.mqtt.kafka.ingress", "result", "failed");
        this.publishTimer = meterRegistry.timer("blindway.mqtt.kafka.ingress.publish");
    }

    @Override
    public CompletionStage<MqttIngressService.Result> accept(String topic, byte[] payload) {
        if (payload.length > properties.maxPayloadBytes()) {
            rejected.increment();
            return CompletableFuture.completedFuture(MqttIngressService.Result.REJECTED);
        }
        Matcher matcher = MqttIngressService.TOPIC.matcher(topic);
        if (!matcher.matches()) {
            rejected.increment();
            return CompletableFuture.completedFuture(MqttIngressService.Result.REJECTED);
        }

        String record;
        try {
            record = objectMapper.writeValueAsString(
                    new RawMqttIngressEvent(topic, new String(payload, StandardCharsets.UTF_8), Instant.now()));
        } catch (JacksonException exception) {
            failed.increment();
            throw new MqttIngressUnavailableException("Unable to serialize raw MQTT event", exception);
        }

        acquirePermit();
        Timer.Sample sample = Timer.start();
        try {
            String key = UUID.fromString(matcher.group(1)).toString();
            return kafkaTemplate
                    .send(EventTopics.MQTT_INGRESS, key, record)
                    .orTimeout(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .handle((result, exception) -> {
                        inFlight.release();
                        sample.stop(publishTimer);
                        if (exception != null) {
                            failed.increment();
                            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                            throw new CompletionException(new MqttIngressUnavailableException(
                                    "Kafka MQTT ingress failed: " + safeMessage(cause), cause));
                        }
                        accepted.increment();
                        return MqttIngressService.Result.ACCEPTED;
                    });
        } catch (RuntimeException exception) {
            inFlight.release();
            sample.stop(publishTimer);
            failed.increment();
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return CompletableFuture.failedFuture(
                    new MqttIngressUnavailableException("Kafka MQTT ingress failed: " + safeMessage(cause), cause));
        }
    }

    private void acquirePermit() {
        try {
            if (!inFlight.tryAcquire(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new MqttIngressUnavailableException("Kafka MQTT ingress capacity timeout", null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MqttIngressUnavailableException("Interrupted while awaiting Kafka ingress capacity", exception);
        }
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
