package com.blindway.device.infrastructure;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DevicePresence {

    private static final Logger log = LoggerFactory.getLogger(DevicePresence.class);
    private static final Duration ONLINE_TTL = Duration.ofSeconds(30);
    private final StringRedisTemplate redis;

    public DevicePresence(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void markOnline(UUID deviceId) {
        try {
            redis.opsForValue().set(key(deviceId), "1", ONLINE_TTL);
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable while updating device presence");
        }
    }

    public boolean isOnline(UUID deviceId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(deviceId)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String key(UUID deviceId) {
        return "blindway:device:online:" + deviceId;
    }
}
