package com.blindway.common.infrastructure;

import java.util.UUID;

public record OutboxRow(
        UUID id, String topic, String partitionKey, String eventType, String payload, int attemptCount) {}
