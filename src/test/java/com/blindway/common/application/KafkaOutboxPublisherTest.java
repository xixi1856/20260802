package com.blindway.common.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.infrastructure.EventOutboxMapper;
import com.blindway.common.infrastructure.OutboxRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxPublisherTest {

    @Mock
    private EventOutboxMapper mapper;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void marksEventPublishedOnlyAfterKafkaAcknowledgesIt() {
        UUID eventId = UUID.randomUUID();
        OutboxRow row = new OutboxRow(
                eventId,
                "blindway.perception.recorded.v1",
                "device-1",
                "PERCEPTION_RECORDED",
                "{\"eventId\":\"" + eventId + "\"}",
                0);
        when(mapper.claimBatch(any(), any(), any(), eq(50))).thenReturn(List.of(row));
        when(kafkaTemplate.send(row.topic(), row.partitionKey(), row.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaOutboxPublisher publisher = publisher();

        publisher.publishAvailable();

        verify(mapper).markPublished(eq(eventId), any(), any());
        verify(mapper, never()).markFailed(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void schedulesRetryWhenKafkaDoesNotAcknowledgeEvent() {
        UUID eventId = UUID.randomUUID();
        OutboxRow row =
                new OutboxRow(eventId, "blindway.perception.recorded.v1", "device-1", "PERCEPTION_RECORDED", "{}", 2);
        when(mapper.claimBatch(any(), any(), any(), eq(50))).thenReturn(List.of(row));
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(row.topic(), row.partitionKey(), row.payload())).thenReturn((CompletableFuture) failed);
        KafkaOutboxPublisher publisher = publisher();

        publisher.publishAvailable();

        verify(mapper, never()).markPublished(any(), any(), any());
        verify(mapper).markFailed(eq(eventId), any(), eq(3), any(), eq("broker unavailable"), any());
    }

    @Test
    void databaseOutageIsCountedWithoutTerminatingTheScheduledPublisher() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.claimBatch(any(), any(), any(), eq(50)))
                .thenThrow(new IllegalStateException("database unavailable"));
        KafkaOutboxPublisher publisher = publisher(registry);

        assertThatCode(publisher::publishAvailable).doesNotThrowAnyException();

        assertThat(registry.counter("blindway.kafka.outbox", "result", "poll_failed")
                        .count())
                .isEqualTo(1);
    }

    private KafkaOutboxPublisher publisher() {
        return publisher(new SimpleMeterRegistry());
    }

    private KafkaOutboxPublisher publisher(SimpleMeterRegistry registry) {
        return new KafkaOutboxPublisher(
                mapper, kafkaTemplate, registry, "test-worker", 50, Duration.ofSeconds(30), Duration.ofSeconds(5), 10);
    }
}
