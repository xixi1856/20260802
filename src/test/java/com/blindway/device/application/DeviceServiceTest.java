package com.blindway.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.api.ApiException;
import com.blindway.device.domain.DeviceStatus;
import com.blindway.device.infrastructure.DeviceMapper;
import com.blindway.device.infrastructure.DevicePresence;
import com.blindway.device.infrastructure.DeviceRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceMapper mapper;

    @Mock
    private DevicePresence presence;

    @Test
    void rotatesOwnedActiveDeviceSecretAndInvalidatesOldSecret() {
        UUID deviceId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        when(mapper.rotateSecret(eq(deviceId), eq(ownerUserId), any(), any())).thenReturn(1);
        DeviceService service = new DeviceService(mapper, presence);

        var response = service.rotateSecret(deviceId, ownerUserId);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).rotateSecret(eq(deviceId), eq(ownerUserId), hash.capture(), any(Instant.class));
        assertThat(response.deviceId()).isEqualTo(deviceId);
        assertThat(response.deviceSecret()).hasSize(43).doesNotContain(hash.getValue());
        assertThat(hash.getValue()).matches("[0-9a-f]{64}");

        DeviceRow updated = new DeviceRow(
                deviceId,
                ownerUserId,
                "树莓派-001",
                hash.getValue(),
                DeviceStatus.ACTIVE,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
        when(mapper.findById(deviceId)).thenReturn(Optional.of(updated));
        assertThat(service.authenticate(deviceId, deviceId.toString(), response.deviceSecret()))
                .isTrue();
        assertThat(service.authenticate(deviceId, deviceId.toString(), "old-device-secret-value"))
                .isFalse();
    }

    @Test
    void refusesRotationForUnownedOrInactiveDevice() {
        UUID deviceId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        when(mapper.rotateSecret(eq(deviceId), eq(ownerUserId), any(), any())).thenReturn(0);
        DeviceService service = new DeviceService(mapper, presence);

        assertThatThrownBy(() -> service.rotateSecret(deviceId, ownerUserId))
                .isInstanceOf(ApiException.class)
                .hasMessage("设备不存在或不属于当前用户");
    }
}
