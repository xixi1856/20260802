package com.blindway.map.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("blindway.amap")
public record AmapProperties(String baseUrl, String webKey, Duration connectTimeout, Duration readTimeout) {}
