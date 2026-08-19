package com.blindway.device.application;

import com.blindway.common.api.ApiException;
import com.blindway.device.DeviceAccess;
import com.blindway.device.api.DeviceResponse;
import com.blindway.device.api.ProvisionedDeviceResponse;
import com.blindway.device.api.RotatedDeviceSecretResponse;
import com.blindway.device.domain.DeviceStatus;
import com.blindway.device.infrastructure.DeviceMapper;
import com.blindway.device.infrastructure.DevicePresence;
import com.blindway.device.infrastructure.DeviceRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService implements DeviceAccess {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(30);
    private final DeviceMapper mapper;
    private final DevicePresence presence;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceService(DeviceMapper mapper, DevicePresence presence) {
        this.mapper = mapper;
        this.presence = presence;
    }

    @Transactional
    public ProvisionedDeviceResponse provision(String label) {
        Instant now = Instant.now();
        String secret = newSecret();
        DeviceRow row = new DeviceRow(
                UUID.randomUUID(),
                null,
                label.strip(),
                hash(secret),
                DeviceStatus.PROVISIONED,
                null,
                null,
                null,
                now,
                now);
        mapper.insert(row);
        return new ProvisionedDeviceResponse(row.id(), row.label(), row.status().name(), false, null, now, secret);
    }

    @Transactional
    public void bind(UUID deviceId, UUID ownerUserId, String rawSecret) {
        DeviceRow device = mapper.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在"));
        if (!MessageDigest.isEqual(
                device.secretHash().getBytes(StandardCharsets.US_ASCII),
                hash(rawSecret).getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_DEVICE_SECRET", "设备密钥错误");
        }
        if (mapper.bind(deviceId, ownerUserId, Instant.now()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_ALREADY_BOUND", "设备已绑定或不可用");
        }
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> listOwnedBy(UUID ownerUserId) {
        Instant onlineCutoff = Instant.now().minus(ONLINE_WINDOW);
        return mapper.findByOwner(ownerUserId).stream()
                .map(row -> new DeviceResponse(
                        row.id(),
                        row.label(),
                        row.status().name(),
                        presence.isOnline(row.id())
                                || (row.lastSeenAt() != null && row.lastSeenAt().isAfter(onlineCutoff)),
                        row.lastSeenAt(),
                        row.createdAt()))
                .toList();
    }

    @Transactional
    public RotatedDeviceSecretResponse rotateSecret(UUID deviceId, UUID ownerUserId) {
        String secret = newSecret();
        Instant rotatedAt = Instant.now();
        if (mapper.rotateSecret(deviceId, ownerUserId, hash(secret), rotatedAt) != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在或不属于当前用户");
        }
        log.info("device_secret_rotated deviceId={} ownerUserId={}", deviceId, ownerUserId);
        return new RotatedDeviceSecretResponse(deviceId, secret, rotatedAt);
    }

    @Transactional(readOnly = true)
    public boolean authenticate(UUID deviceId, String clientId, String rawSecret) {
        if (!deviceId.toString().equals(clientId)) {
            return false;
        }
        return mapper.findById(deviceId)
                .filter(device -> device.status() != DeviceStatus.DISABLED)
                .map(device -> MessageDigest.isEqual(
                        device.secretHash().getBytes(StandardCharsets.US_ASCII),
                        hash(rawSecret).getBytes(StandardCharsets.US_ASCII)))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsAndEnabled(UUID deviceId) {
        return mapper.findById(deviceId)
                .map(row -> row.status() != DeviceStatus.DISABLED)
                .orElse(false);
    }

    @Override
    @Transactional
    public void markSeen(UUID deviceId, Instant occurredAt, String softwareVersion, String modelVersion) {
        mapper.markSeen(deviceId, occurredAt, softwareVersion, modelVersion, Instant.now());
        presence.markOnline(deviceId);
    }

    private String newSecret() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
