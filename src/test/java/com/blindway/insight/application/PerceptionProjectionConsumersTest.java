package com.blindway.insight.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
class PerceptionProjectionConsumersTest {

    @Mock
    private InsightMapper mapper;

    @Test
    void eachConsumerMaintainsAnIndependentIdempotencyCheckpoint() throws Exception {
        PerceptionRecordedEvent event = obstacleEvent();
        String payload = JsonMapper.builder().findAndAddModules().build().writeValueAsString(event);
        when(mapper.recordConsumption("trip-risk-v1", event.eventId(), event.occurredAt()))
                .thenReturn(1);
        TripRiskProjectionConsumer consumer = new TripRiskProjectionConsumer(mapper, objectMapper());

        consumer.consume(payload);

        verify(mapper).incrementTripRisk(eq(event.tripId()), eq(1), eq(0), eq(event.occurredAt()));
        verify(mapper).recordConsumption("trip-risk-v1", event.eventId(), event.occurredAt());
    }

    @Test
    void duplicateDeliveryDoesNotApplyProjectionTwice() throws Exception {
        PerceptionRecordedEvent event = obstacleEvent();
        String payload = objectMapper().writeValueAsString(event);
        when(mapper.recordConsumption("device-analytics-v1", event.eventId(), event.occurredAt()))
                .thenReturn(0);
        DeviceAnalyticsProjectionConsumer consumer = new DeviceAnalyticsProjectionConsumer(mapper, objectMapper());

        consumer.consume(payload);

        verify(mapper, never()).incrementDeviceDaily(any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void onlyStaticWellLocatedObstaclesBecomeCommunityCandidates() throws Exception {
        PerceptionRecordedEvent event = obstacleEvent();
        String payload = objectMapper().writeValueAsString(event);
        when(mapper.recordConsumption("community-candidate-v1", event.eventId(), event.occurredAt()))
                .thenReturn(1);
        CommunityCandidateProjectionConsumer consumer =
                new CommunityCandidateProjectionConsumer(mapper, objectMapper());

        consumer.consume(payload);

        verify(mapper)
                .insertCommunityCandidate(
                        eq(event.eventId()),
                        eq(event.deviceId()),
                        eq(event.tripId()),
                        eq("STATIC_OBJECT"),
                        eq(event.longitude()),
                        eq(event.latitude()),
                        eq(event.locationQuality()),
                        eq(event.occurredAt()));
    }

    private PerceptionRecordedEvent obstacleEvent() {
        return new PerceptionRecordedEvent(
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
    }

    private tools.jackson.databind.ObjectMapper objectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
