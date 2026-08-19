package com.blindway.device.api;

import com.blindway.device.application.DeviceService;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/emqx")
public class EmqxAuthController {

    private final DeviceService service;

    public EmqxAuthController(DeviceService service) {
        this.service = service;
    }

    @PostMapping("/authentication")
    Map<String, Object> authenticate(@RequestBody EmqxAuthRequest request) {
        try {
            UUID deviceId = UUID.fromString(request.username());
            boolean allowed = service.authenticate(deviceId, request.clientid(), request.password());
            return Map.of("result", allowed ? "allow" : "deny", "is_superuser", false);
        } catch (IllegalArgumentException exception) {
            return Map.of("result", "deny", "is_superuser", false);
        }
    }

    @PostMapping("/authorization")
    Map<String, String> authorize(@RequestBody EmqxAuthorizationRequest request) {
        try {
            UUID deviceId = UUID.fromString(request.username());
            String expectedPrefix = "blindway/v1/devices/" + request.username() + "/";
            boolean ownPublish = "publish".equalsIgnoreCase(request.action())
                    && Objects.equals(request.clientid(), request.username())
                    && request.topic() != null
                    && request.topic().startsWith(expectedPrefix)
                    && (request.topic().endsWith("/heartbeat")
                            || request.topic().endsWith("/path-events")
                            || request.topic().endsWith("/obstacle-events"))
                    && service.existsAndEnabled(deviceId);
            return Map.of("result", ownPublish ? "allow" : "deny");
        } catch (IllegalArgumentException exception) {
            return Map.of("result", "deny");
        }
    }

    record EmqxAuthRequest(String username, String clientid, String password) {}

    record EmqxAuthorizationRequest(String username, String clientid, String action, String topic) {}
}
