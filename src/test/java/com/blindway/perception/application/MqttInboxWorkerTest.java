package com.blindway.perception.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.perception.infrastructure.MqttInboxRow;
import com.blindway.perception.infrastructure.PerceptionMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MqttInboxWorkerTest {

    @Mock
    private PerceptionMapper mapper;

    @Mock
    private MqttInboxProcessor processor;

    private MqttInboxWorker worker;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        worker = new MqttInboxWorker(mapper, processor, registry, "worker-a", 50, Duration.ofSeconds(30), 5);
    }

    @Test
    void transientFailureReturnsClaimToPendingWithBackoff() {
        MqttInboxRow row = row(1);
        when(mapper.claimInboxBatch(eq("worker-a"), any(), any(), eq(50))).thenReturn(List.of(row));
        when(processor.process(row, "worker-a")).thenThrow(new IllegalStateException("temporary database failure"));

        worker.processAvailable();

        verify(mapper)
                .markInboxFailed(
                        eq(row.eventId()), eq("worker-a"), eq("PENDING"), eq("temporary database failure"), any());
    }

    @Test
    void finalFailureMovesMessageToDead() {
        MqttInboxRow row = row(5);
        when(mapper.claimInboxBatch(eq("worker-a"), any(), any(), eq(50))).thenReturn(List.of(row));
        when(processor.process(row, "worker-a")).thenThrow(new IllegalStateException("poison"));

        worker.processAvailable();

        verify(mapper).markInboxFailed(eq(row.eventId()), eq("worker-a"), eq("DEAD"), eq("poison"), any());
    }

    @Test
    void successfulProcessingDoesNotScheduleRetry() {
        MqttInboxRow row = row(1);
        when(mapper.claimInboxBatch(eq("worker-a"), any(), any(), eq(50))).thenReturn(List.of(row));
        when(processor.process(row, "worker-a")).thenReturn(MqttInboxProcessor.Outcome.PROCESSED);

        worker.processAvailable();

        verify(mapper, never()).markInboxFailed(any(), any(), any(), any(), any());
    }

    @Test
    void databaseOutageIsCountedWithoutTerminatingTheScheduledWorker() {
        when(mapper.claimInboxBatch(eq("worker-a"), any(), any(), eq(50)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(worker::processAvailable).doesNotThrowAnyException();

        assertThat(registry.counter("blindway.mqtt.inbox", "result", "poll_failed")
                        .count())
                .isEqualTo(1);
    }

    private MqttInboxRow row(int attemptCount) {
        return new MqttInboxRow(
                UUID.randomUUID(),
                "blindway/v1/devices/cb94d02f-3843-41a4-8312-b549690ba21d/path-events",
                "{}",
                Instant.now(),
                attemptCount);
    }
}
