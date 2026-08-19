package com.blindway.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("blindway.security")
public record SecurityProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes().length < 32) {
            throw new IllegalArgumentException("blindway.security.jwt-secret must contain at least 32 bytes");
        }
    }
}
