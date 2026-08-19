package com.blindway.accessibility.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blindway.common.api.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;

class SpatialQueryGuardTest {

    @Test
    void convertsDatabaseTimeoutToServiceUnavailable() {
        SpatialQueryGuard guard = new SpatialQueryGuard(
                new SimpleMeterRegistry(), 1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(1500));

        assertThatThrownBy(() -> guard.execute("nearby", () -> {
                    throw new QueryTimeoutException("canceling statement due to statement timeout");
                }))
                .isInstanceOf(ApiException.class)
                .hasMessage("空间查询超时，请稍后重试");
    }

    @Test
    void rejectsNestedQueryWhenAllPermitsAreInUse() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpatialQueryGuard guard =
                new SpatialQueryGuard(registry, 1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(1500));

        assertThatThrownBy(() -> guard.execute("nearby", () -> guard.execute("route", () -> "unreachable")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.code()).isEqualTo("SPATIAL_QUERY_BUSY");
                });
        assertThat(registry.counter("blindway.spatial.rejected", "query", "route")
                        .count())
                .isEqualTo(1);
    }
}
