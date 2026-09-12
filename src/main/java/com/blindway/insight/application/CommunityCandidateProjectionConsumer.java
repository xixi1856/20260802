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
public class CommunityCandidateProjectionConsumer {

    private static final String CONSUMER = "community-candidate-v1";
    private final InsightMapper mapper;
    private final ObjectMapper objectMapper;

    public CommunityCandidateProjectionConsumer(InsightMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = EventTopics.PERCEPTION_RECORDED, groupId = CONSUMER)
    @Transactional
    public void consume(String payload) throws JacksonException {
        PerceptionRecordedEvent event = objectMapper.readValue(payload, PerceptionRecordedEvent.class);
        if (mapper.recordConsumption(CONSUMER, event.eventId(), event.occurredAt()) == 0) {
            return;
        }
        if (!isCandidate(event)) {
            return;
        }
        mapper.insertCommunityCandidate(
                event.eventId(),
                event.deviceId(),
                event.tripId(),
                "STATIC_OBJECT",
                event.longitude(),
                event.latitude(),
                event.locationQuality(),
                event.occurredAt());
    }

    private boolean isCandidate(PerceptionRecordedEvent event) {
        return "OBSTACLE".equals(event.eventType())
                && event.longitude() != null
                && event.latitude() != null
                && ("HIGH".equals(event.locationQuality()) || "MEDIUM".equals(event.locationQuality()))
                && event.obstacleCategories() != null
                && event.obstacleCategories().contains("STATIC_OBJECT");
    }
}
