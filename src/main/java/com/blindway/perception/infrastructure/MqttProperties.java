package com.blindway.perception.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("blindway.mqtt")
public record MqttProperties(
        boolean enabled,
        String brokerUri,
        String clientId,
        String username,
        String password,
        int maxPayloadBytes,
        String subscriptionGroup,
        boolean cleanSession) {}
