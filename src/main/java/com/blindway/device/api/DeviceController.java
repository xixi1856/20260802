package com.blindway.device.api;

import com.blindway.common.security.CurrentUser;
import com.blindway.device.application.DeviceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.listOwnedBy(CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ProvisionedDeviceResponse provision(@Valid @RequestBody ProvisionDeviceRequest request) {
        return service.provision(request.label());
    }

    @PostMapping("/{deviceId}/binding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void bind(
            @PathVariable UUID deviceId,
            @Valid @RequestBody BindDeviceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.bind(deviceId, CurrentUser.id(jwt), request.deviceSecret());
    }

    @PostMapping("/{deviceId}/secret-rotation")
    RotatedDeviceSecretResponse rotateSecret(@PathVariable UUID deviceId, @AuthenticationPrincipal Jwt jwt) {
        return service.rotateSecret(deviceId, CurrentUser.id(jwt));
    }
}
