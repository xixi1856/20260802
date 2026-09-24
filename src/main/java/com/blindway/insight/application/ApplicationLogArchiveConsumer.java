package com.blindway.insight.application;

import com.blindway.common.EventTopics;
import com.blindway.insight.infrastructure.InsightMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "blindway.log-archive", name = "enabled", havingValue = "true")
public class ApplicationLogArchiveConsumer {

    private final InsightMapper mapper;
    private final ObjectMapper objectMapper;

    public ApplicationLogArchiveConsumer(InsightMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = EventTopics.APPLICATION_LOG_ARCHIVE, groupId = "application-log-archive-v1")
    public void consume(String payload) throws JacksonException {
        ApplicationLogArchiveEvent event = objectMapper.readValue(payload, ApplicationLogArchiveEvent.class);
        if (event.logId() == null
                || event.occurredAt() == null
                || !isAllowedLevel(event.level())
                || event.logger() == null
                || event.service() == null) {
            throw new IllegalArgumentException("Invalid application log archive event");
        }
        mapper.archiveApplicationLog(
                event.logId(),
                event.occurredAt(),
                event.level(),
                clipped(event.logger(), 200),
                redact(clipped(event.message(), 2000)),
                clipped(event.service(), 80),
                clipped(event.instance(), 120),
                clipped(event.traceId(), 64));
    }

    private boolean isAllowedLevel(String level) {
        return "WARN".equals(level) || "ERROR".equals(level);
    }

    private String clipped(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String redact(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)(password|token|secret|authorization)=\\S+", "$1=[REDACTED]")
                .replaceAll("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,2}\\.\\d{4,}", "[LOCATION_REDACTED]");
    }
}
