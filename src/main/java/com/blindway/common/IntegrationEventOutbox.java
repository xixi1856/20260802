package com.blindway.common;

import java.util.UUID;

public interface IntegrationEventOutbox {

    void append(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            Object payload);
}
