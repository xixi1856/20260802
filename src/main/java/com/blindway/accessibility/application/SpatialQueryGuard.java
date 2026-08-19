package com.blindway.accessibility.application;

import com.blindway.common.api.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SpatialQueryGuard {

    private final Semaphore permits;
    private final int maxConcurrent;
    private final Duration acquireTimeout;
    private final Duration nearbyStatementTimeout;
    private final Duration routeStatementTimeout;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger waiting = new AtomicInteger();

    public SpatialQueryGuard(
            MeterRegistry meterRegistry,
            @Value("${blindway.spatial.max-concurrent:5}") int maxConcurrent,
            @Value("${blindway.spatial.acquire-timeout:50ms}") Duration acquireTimeout,
            @Value("${blindway.spatial.nearby-statement-timeout:1s}") Duration nearbyStatementTimeout,
            @Value("${blindway.spatial.route-statement-timeout:1500ms}") Duration routeStatementTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        this.meterRegistry = meterRegistry;
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = acquireTimeout;
        this.nearbyStatementTimeout = nearbyStatementTimeout;
        this.routeStatementTimeout = routeStatementTimeout;
        Gauge.builder("blindway.spatial.active", this, guard -> guard.maxConcurrent - guard.permits.availablePermits())
                .register(meterRegistry);
        Gauge.builder("blindway.spatial.waiting", waiting, AtomicInteger::get).register(meterRegistry);
    }

    public String statementTimeout(String query) {
        Duration timeout = "nearby".equals(query) ? nearbyStatementTimeout : routeStatementTimeout;
        return timeout.toMillis() + "ms";
    }

    public <T> T execute(String query, Supplier<T> supplier) {
        boolean acquired;
        waiting.incrementAndGet();
        try {
            acquired = permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SPATIAL_QUERY_INTERRUPTED", "空间查询已中断，请稍后重试");
        } finally {
            waiting.decrementAndGet();
        }

        if (!acquired) {
            counter("blindway.spatial.rejected", query).increment();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SPATIAL_QUERY_BUSY", "空间查询繁忙，请稍后重试");
        }

        try {
            return Timer.builder("blindway.spatial.query")
                    .tag("query", query)
                    .register(meterRegistry)
                    .record(supplier);
        } catch (QueryTimeoutException exception) {
            counter("blindway.spatial.timeouts", query).increment();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SPATIAL_QUERY_TIMEOUT", "空间查询超时，请稍后重试");
        } finally {
            permits.release();
        }
    }

    private Counter counter(String name, String query) {
        return Counter.builder(name).tag("query", query).register(meterRegistry);
    }
}
