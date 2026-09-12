package com.blindway.perception.application;

import com.blindway.common.api.ApiException;
import com.blindway.perception.infrastructure.PerceptionMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MqttInboxReplayService {

    private final PerceptionMapper mapper;

    public MqttInboxReplayService(PerceptionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void replay(UUID eventId, Instant replayedAt) {
        if (mapper.replayInbox(eventId, replayedAt) != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MQTT_INBOX_NOT_REPLAYABLE",
                    "Only DEAD or REJECTED MQTT Inbox messages can be replayed");
        }
    }
}
