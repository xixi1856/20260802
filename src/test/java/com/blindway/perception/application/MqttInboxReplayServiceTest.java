package com.blindway.perception.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.api.ApiException;
import com.blindway.perception.infrastructure.PerceptionMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MqttInboxReplayServiceTest {

    @Mock
    private PerceptionMapper mapper;

    @InjectMocks
    private MqttInboxReplayService service;

    @Test
    void requeuesDeadOrRejectedMessage() {
        UUID eventId = UUID.randomUUID();
        when(mapper.replayInbox(eventId, Instant.EPOCH)).thenReturn(1);

        service.replay(eventId, Instant.EPOCH);

        verify(mapper).replayInbox(eventId, Instant.EPOCH);
    }

    @Test
    void refusesReplayForNonTerminalMessage() {
        UUID eventId = UUID.randomUUID();
        when(mapper.replayInbox(eventId, Instant.EPOCH)).thenReturn(0);

        assertThatThrownBy(() -> service.replay(eventId, Instant.EPOCH))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DEAD or REJECTED");
    }
}
