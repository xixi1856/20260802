package com.blindway.insight.application;

import com.blindway.common.EventTopics;
import com.blindway.insight.infrastructure.InsightMapper;
import com.blindway.perception.PerceptionRecordedEvent;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "blindway.kafka", name = "enabled", havingValue = "true")
public class DeviceAnalyticsProjectionConsumer {

    private static final String CONSUMER = "device-analytics-v1";
    private final InsightMapper mapper;
    private final ObjectMapper objectMapper;

    public DeviceAnalyticsProjectionConsumer(InsightMapper mapper, ObjectMapper objectMapper) {
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
        mapper.incrementDeviceDaily(
                event.deviceId(),
                event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate(),
                "HEARTBEAT".equals(event.eventType()) ? 1 : 0,
                "PATH".equals(event.eventType()) ? 1 : 0,
                "OBSTACLE".equals(event.eventType()) ? 1 : 0,
                event.occurredAt());
    }
}
