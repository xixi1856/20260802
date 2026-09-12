package com.blindway.perception.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.blindway.perception.infrastructure.MqttProperties;
import com.blindway.perception.infrastructure.PerceptionMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MqttIngressServiceTest {

    @Mock
    private PerceptionMapper mapper;

    private MqttIngressService service;

    @BeforeEach
    void setUp() {
        service = new MqttIngressService(
                JsonMapper.builder().findAndAddModules().build(),
                mapper,
                new MqttProperties(false, "tcp://localhost:1883", "test", "test", "test", 32768, "blindway-test", true),
                new SimpleMeterRegistry());
    }

    @Test
    void commitsValidEventToInboxWithoutExecutingBusinessWork() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(1);

        var result = service.accept("blindway/v1/devices/" + deviceId + "/path-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.ACCEPTED);
        verify(mapper).insertInbox(any(), anyString(), any(), anyString());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void duplicateIsSafeToAcknowledgeWithoutCreatingAnotherInboxRow() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString())).thenReturn(0);

        var result = service.accept("blindway/v1/devices/" + deviceId + "/path-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.DUPLICATE);
    }

    @Test
    void rejectsMalformedEnvelopeBeforePersistence() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.invalid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");

        var result = service.accept("blindway/v1/devices/" + deviceId + "/path-events", payload);

        assertThat(result).isEqualTo(MqttIngressService.Result.REJECTED);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void propagatesDatabaseFailureSoCallbackCannotAcknowledge() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("contracts/examples/mqtt/path-event.valid.json"));
        UUID deviceId = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
        when(mapper.insertInbox(any(), anyString(), any(), anyString()))
                .thenThrow(new TransientDataAccessResourceException("database unavailable"));

        assertThatThrownBy(() -> service.accept("blindway/v1/devices/" + deviceId + "/path-events", payload))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }
}
