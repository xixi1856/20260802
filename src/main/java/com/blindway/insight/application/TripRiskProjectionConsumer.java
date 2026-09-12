package com.blindway.insight.application;

import com.blindway.common.EventTopics;
import com.blindway.insight.infrastructure.InsightMapper;
import com.blindway.perception.PerceptionRecordedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "blindway.kafka", name = "enabled", havingValue = "true")
public class TripRiskProjectionConsumer {

    private static final String CONSUMER = "trip-risk-v1";
    private final InsightMapper mapper;
    private final ObjectMapper objectMapper;

    public TripRiskProjectionConsumer(InsightMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = EventTopics.PERCEPTION_RECORDED, groupId = CONSUMER)
    @Transactional
    public void consume(String payload) throws JacksonException {
        PerceptionRecordedEvent event = objectMapper.readValue(payload, PerceptionRecordedEvent.class);
        if (mapper.recordConsumption(CONSUMER, event.eventId(), event.occurredAt()) == 0 || event.tripId() == null) {
            return;
        }
        int obstacleDelta = "OBSTACLE".equals(event.eventType()) ? 1 : 0;
        int pathLostDelta = "PATH".equals(event.eventType()) && "NOT_DETECTED".equals(event.pathState()) ? 1 : 0;
        if (obstacleDelta != 0 || pathLostDelta != 0) {
            mapper.incrementTripRisk(event.tripId(), obstacleDelta, pathLostDelta, event.occurredAt());
        }
    }
}
