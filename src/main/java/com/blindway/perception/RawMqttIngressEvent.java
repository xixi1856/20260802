package com.blindway.perception;

import java.time.Instant;

public record RawMqttIngressEvent(String topic, String rawPayload, Instant receivedAt) {}
