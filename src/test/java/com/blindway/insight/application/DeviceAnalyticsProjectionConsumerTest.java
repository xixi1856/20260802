package com.blindway.insight.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.insight.infrastructure.InsightMapper;
import com.blindway.perception.PerceptionRecordedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DeviceAnalyticsProjectionConsumerTest {

    @Mock
    private InsightMapper mapper;

    @Test
    void duplicateDeliveryDoesNotApplyProjectionTwice() throws Exception {
        var event = new PerceptionRecordedEvent(
                UUID.randomUUID(),
                "OBSTACLE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-08-20T08:00:00Z"),
                Instant.parse("2026-08-20T08:00:01Z"),
                "HIGH",
                116.397128,
                39.916527,
                null,
                List.of("STATIC_OBJECT"));
        var objectMapper = JsonMapper.builder().findAndAddModules().build();
        when(mapper.recordConsumption("device-analytics-v1", event.eventId(), event.occurredAt()))
                .thenReturn(0);

        new DeviceAnalyticsProjectionConsumer(mapper, objectMapper).consume(objectMapper.writeValueAsString(event));

        verify(mapper, never()).incrementDeviceDaily(any(), any(), anyInt(), anyInt(), anyInt(), any());
    }
}
