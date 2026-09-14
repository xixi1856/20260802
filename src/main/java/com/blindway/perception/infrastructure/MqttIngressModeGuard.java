package com.blindway.perception.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MqttIngressModeGuard {

    public MqttIngressModeGuard(
            @Value("${blindway.mqtt.ingress-mode:database}") String ingressMode,
            @Value("${blindway.kafka.enabled:false}") boolean kafkaEnabled) {
        if (!"database".equals(ingressMode) && !"kafka".equals(ingressMode)) {
            throw new IllegalArgumentException("Unsupported MQTT ingress mode: " + ingressMode);
        }
        if ("kafka".equals(ingressMode) && !kafkaEnabled) {
            throw new IllegalArgumentException("Kafka MQTT ingress requires blindway.kafka.enabled=true");
        }
    }
}
