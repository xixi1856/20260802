package com.blindway.insight.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.blindway.insight.infrastructure.InsightMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ApplicationLogArchiveConsumerTest {

    @Mock
    private InsightMapper mapper;

    @Test
    void redactsCredentialsAndPreciseLocationBeforeArchiving() throws Exception {
        var event = new ApplicationLogArchiveEvent(
                UUID.randomUUID(),
                Instant.parse("2026-09-15T02:00:00Z"),
                "ERROR",
                "example.Logger",
                "token=abc123 location=116.397128,39.916527",
                "blindway-backend",
                "backend-1",
                "trace-1");
        var objectMapper = JsonMapper.builder().findAndAddModules().build();

        new ApplicationLogArchiveConsumer(mapper, objectMapper).consume(objectMapper.writeValueAsString(event));

        verify(mapper)
                .archiveApplicationLog(
                        eq(event.logId()),
                        eq(event.occurredAt()),
                        eq("ERROR"),
                        eq("example.Logger"),
                        eq("token=[REDACTED] location=[LOCATION_REDACTED]"),
                        eq("blindway-backend"),
                        eq("backend-1"),
                        eq("trace-1"));
    }
}
