package com.blindway.perception.application;

import com.blindway.common.EventTopics;
import com.blindway.perception.RawMqttIngressEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "blindway.mqtt", name = "ingress-mode", havingValue = "kafka")
public class KafkaMqttIngressConsumer {

    static final String CONSUMER = "perception-ingress-v1";

    private final ObjectMapper objectMapper;
    private final MqttIngressService ingress;

    public KafkaMqttIngressConsumer(ObjectMapper objectMapper, MqttIngressService ingress) {
        this.objectMapper = objectMapper;
        this.ingress = ingress;
    }

    @KafkaListener(topics = EventTopics.MQTT_INGRESS, groupId = CONSUMER)
    public void consume(String payload) {
        RawMqttIngressEvent event;
        try {
            event = objectMapper.readValue(payload, RawMqttIngressEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid raw MQTT ingress record", exception);
        }
        ingress.accept(event.topic(), event.rawPayload().getBytes(StandardCharsets.UTF_8), event.receivedAt());
    }
}
