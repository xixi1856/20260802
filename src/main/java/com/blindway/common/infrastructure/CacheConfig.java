package com.blindway.common.infrastructure;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String NEARBY_ISSUES = "nearbyIssues";
    public static final String ROUTE_RISKS = "routeRisks";

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(
                NEARBY_ISSUES,
                Caffeine.newBuilder()
                        .maximumSize(2_000)
                        .expireAfterWrite(Duration.ofSeconds(3))
                        .recordStats()
                        .build());
        manager.registerCustomCache(
                ROUTE_RISKS,
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfterWrite(Duration.ofSeconds(5))
                        .recordStats()
                        .build());
        return manager;
    }
}
