package com.blindway.perception.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.EventTopics;
import com.blindway.perception.infrastructure.MqttProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class KafkaMqttIngressGatewayTest {

    private static final UUID DEVICE_ID = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaMqttIngressGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new KafkaMqttIngressGateway(
                JsonMapper.builder().findAndAddModules().build(),
                kafkaTemplate,
                properties(32768),
                new SimpleMeterRegistry(),
                Duration.ofSeconds(5),
                1000);
    }

    @Test
    void returnsOnlyAfterReplicatedKafkaSendSucceeds() {
        String topic = "blindway/v1/devices/" + DEVICE_ID + "/path-events";
        when(kafkaTemplate.send(eq(EventTopics.MQTT_INGRESS), eq(DEVICE_ID.toString()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var result = gateway.accept(topic, "{}".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();

        assertThat(result).isEqualTo(MqttIngressService.Result.ACCEPTED);
        verify(kafkaTemplate).send(eq(EventTopics.MQTT_INGRESS), eq(DEVICE_ID.toString()), anyString());
    }

    @Test
    void kafkaFailurePreventsSuccessfulReturn() {
        String topic = "blindway/v1/devices/" + DEVICE_ID + "/path-events";
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("not enough replicas"));
        when(kafkaTemplate.send(eq(EventTopics.MQTT_INGRESS), eq(DEVICE_ID.toString()), anyString()))
                .thenReturn((CompletableFuture) failed);

        assertThatThrownBy(() -> gateway.accept(topic, "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join())
                .hasCauseInstanceOf(MqttIngressUnavailableException.class)
                .hasMessageContaining("not enough replicas");
    }

    @Test
    void oversizedPayloadIsRejectedBeforeKafka() {
        gateway = new KafkaMqttIngressGateway(
                JsonMapper.builder().findAndAddModules().build(),
                kafkaTemplate,
                properties(2),
                new SimpleMeterRegistry(),
                Duration.ofSeconds(5),
                1000);

        var result = gateway.accept(
                        "blindway/v1/devices/" + DEVICE_ID + "/path-events", "{}x".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();

        assertThat(result).isEqualTo(MqttIngressService.Result.REJECTED);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    private MqttProperties properties(int maxPayloadBytes) {
        return new MqttProperties(
                false, "tcp://localhost:1883", "test", "test", "test", maxPayloadBytes, "blindway-test", true);
    }
}
