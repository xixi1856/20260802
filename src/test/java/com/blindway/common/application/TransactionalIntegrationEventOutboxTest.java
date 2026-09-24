package com.blindway.common.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.blindway.common.infrastructure.EventOutboxMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class TransactionalIntegrationEventOutboxTest {

    @Mock
    private EventOutboxMapper mapper;

    @Test
    void kafkaDisabledDoesNotAppendUnpublishableEvent() {
        var outbox = new TransactionalIntegrationEventOutbox(
                mapper, JsonMapper.builder().build(), false);

        outbox.append(UUID.randomUUID(), "DEVICE", UUID.randomUUID(), "PERCEPTION_RECORDED", "topic", "key", "body");

        verifyNoInteractions(mapper);
    }

    @Test
    void kafkaEnabledAppendsEventForPublisher() {
        var outbox = new TransactionalIntegrationEventOutbox(
                mapper, JsonMapper.builder().build(), true);
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        outbox.append(eventId, "DEVICE", deviceId, "PERCEPTION_RECORDED", "topic", "key", "body");

        verify(mapper)
                .insert(
                        eq(eventId),
                        eq("DEVICE"),
                        eq(deviceId),
                        eq("PERCEPTION_RECORDED"),
                        eq("topic"),
                        eq("key"),
                        eq("\"body\""),
                        any());
    }
}
