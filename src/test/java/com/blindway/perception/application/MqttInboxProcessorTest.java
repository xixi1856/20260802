package com.blindway.perception.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.IntegrationEventOutbox;
import com.blindway.device.DeviceAccess;
import com.blindway.perception.infrastructure.MqttInboxRow;
import com.blindway.perception.infrastructure.PerceptionMapper;
import com.blindway.trip.TripLocationAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MqttInboxProcessorTest {

    private static final String OWNER = "worker-a";
    private static final UUID DEVICE_ID = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");

    @Mock
    private DeviceAccess devices;

    @Mock
    private TripLocationAccess trips;

    @Mock
    private PerceptionMapper mapper;

    @Mock
    private IntegrationEventOutbox outbox;

    private MqttInboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MqttInboxProcessor(
                JsonMapper.builder().findAndAddModules().build(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                devices,
                trips,
                mapper,
                outbox,
                new SimpleMeterRegistry());
    }

    @Test
    void processesClaimedPathEventAndCompletesOwnedLeaseAtomically() throws Exception {
        MqttInboxRow row = row("path-events", "path-event.valid.json", 1);
        when(devices.existsAndEnabled(DEVICE_ID)).thenReturn(true);
        when(trips.resolve(any(), any(), any()))
                .thenReturn(new TripLocationAccess.LocationMatch(
                        UUID.randomUUID(), 1L, 116.397128, 39.916527, "INTERPOLATED", "HIGH", 1000L));
        when(mapper.markProcessed(any(), anyString(), any(), any())).thenReturn(1);

        var result = processor.process(row, OWNER);

        assertThat(result).isEqualTo(MqttInboxProcessor.Outcome.PROCESSED);
        verify(mapper).insertPathObservation(any(), any(), any(), any());
        verify(mapper).markProcessed(eq(row.eventId()), eq(OWNER), any(), any());
    }

    @Test
    void terminalBusinessValidationFailureIsRejectedWithoutRetry() throws Exception {
        MqttInboxRow row = row("heartbeat", "heartbeat.invalid.json", 1);
        when(devices.existsAndEnabled(DEVICE_ID)).thenReturn(true);
        when(mapper.markRejected(any(), anyString(), anyString(), any())).thenReturn(1);

        var result = processor.process(row, OWNER);

        assertThat(result).isEqualTo(MqttInboxProcessor.Outcome.REJECTED);
        verify(mapper).markRejected(eq(row.eventId()), eq(OWNER), eq("INVALID_PAYLOAD"), any());
    }

    private MqttInboxRow row(String topicSuffix, String file, int attemptCount) throws Exception {
        String payload = Files.readString(Path.of("contracts/examples/mqtt/" + file));
        UUID eventId = UUID.fromString(JsonMapper.builder()
                .findAndAddModules()
                .build()
                .readTree(payload)
                .get("eventId")
                .asString());
        return new MqttInboxRow(
                eventId,
                "blindway/v1/devices/" + DEVICE_ID + "/" + topicSuffix,
                payload,
                Instant.parse("2026-08-04T08:30:00.200Z"),
                attemptCount);
    }
}
