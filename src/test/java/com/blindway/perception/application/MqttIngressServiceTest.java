package com.blindway.perception.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.device.DeviceAccess;
import com.blindway.perception.infrastructure.MqttProperties;
import com.blindway.perception.infrastructure.PerceptionMapper;
import com.blindway.trip.TripLocationAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MqttIngressServiceTest {

    @Mock
    private DeviceAccess devices;

    @Mock
    private TripLocationAccess trips;

    @Mock
    private PerceptionMapper mapper;

    private MqttIngressService service;

    @BeforeEach
    void setUp() {
        service = new MqttIngressService(
                JsonMapper.builder().findAndAddModules().build(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                devices,
                trips,
                mapper,
                new MqttProperties(false, "tcp://localhost:1883", "test", "test", "test", 32768),
                new SimpleMeterRegistry());
    }

    @Test
    void storesValidPathEventAndMatchesTrip() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(1);
        when(devices.existsAndEnabled(deviceId)).thenReturn(true);
        when(trips.resolve(any(), any(), any()))
                .thenReturn(new TripLocationAccess.LocationMatch(
                        UUID.randomUUID(), 1L, 116.397128, 39.916527, "INTERPOLATED", "HIGH", 1000L));

        var result = service.accept("blindway/v1/devices/" + deviceId + "/path-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.PROCESSED);
        verify(mapper).insertPathObservation(any(), any(), any(), any());
    }

    @Test
    void duplicateEventDoesNotCreateBusinessRecord() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(0);

        var result = service.accept("blindway/v1/devices/" + deviceId + "/path-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.DUPLICATE);
    }

    @Test
    void rejectsTopicPayloadDeviceMismatch() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.valid.json"));

        var result = service.accept("blindway/v1/devices/" + UUID.randomUUID() + "/path-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.REJECTED);
    }

    @Test
    void acceptsHeartbeatContractExample() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/heartbeat.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(1);
        when(devices.existsAndEnabled(deviceId)).thenReturn(true);

        var result = service.accept("blindway/v1/devices/" + deviceId + "/heartbeat", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.PROCESSED);
        verify(mapper).insertHeartbeat(any(), any(), any());
    }

    @Test
    void acceptsObstacleContractExample() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/obstacle-event.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(1);
        when(devices.existsAndEnabled(deviceId)).thenReturn(true);
        when(trips.resolve(any(), any(), any()))
                .thenReturn(new TripLocationAccess.LocationMatch(
                        UUID.randomUUID(), 1L, 116.397128, 39.916527, "INTERPOLATED", "HIGH", 1000L));

        var result = service.accept("blindway/v1/devices/" + deviceId + "/obstacle-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.PROCESSED);
        verify(mapper).insertObstacleEvent(any(), any(), anyString(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "path-event", "obstacle-event"})
    void rejectsInvalidContractExamples(String eventType) throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/" + eventType + ".invalid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        if (!"path-event".equals(eventType)) {
            when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(1);
            when(devices.existsAndEnabled(deviceId)).thenReturn(true);
        }
        String topicType =
                switch (eventType) {
                    case "heartbeat" -> "heartbeat";
                    case "path-event" -> "path-events";
                    case "obstacle-event" -> "obstacle-events";
                    default -> throw new IllegalArgumentException(eventType);
                };

        var result = service.accept("blindway/v1/devices/" + deviceId + "/" + topicType, payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.REJECTED);
    }
}
