package com.blindway.perception.api;

import com.blindway.perception.application.MqttInboxReplayService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/mqtt-inbox")
public class MqttInboxAdminController {

    private final MqttInboxReplayService service;

    public MqttInboxAdminController(MqttInboxReplayService service) {
        this.service = service;
    }

    @PostMapping("/{eventId}/replay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void replay(@PathVariable UUID eventId) {
        service.replay(eventId, Instant.now());
    }
}
