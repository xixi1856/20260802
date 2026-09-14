package com.blindway.common.application;

import com.blindway.common.IntegrationEventOutbox;
import com.blindway.common.infrastructure.EventOutboxMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class TransactionalIntegrationEventOutbox implements IntegrationEventOutbox {

    private final EventOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final boolean kafkaEnabled;

    public TransactionalIntegrationEventOutbox(
            EventOutboxMapper mapper,
            ObjectMapper objectMapper,
            @Value("${blindway.kafka.enabled:false}") boolean kafkaEnabled) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.kafkaEnabled = kafkaEnabled;
    }

    @Override
    public void append(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            Object payload) {
        if (!kafkaEnabled) {
            return;
        }
        try {
            mapper.insert(
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    topic,
                    partitionKey,
                    objectMapper.writeValueAsString(payload),
                    Instant.now());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize integration event", exception);
        }
    }
}
