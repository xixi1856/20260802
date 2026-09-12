package com.blindway.common.infrastructure;

import com.blindway.common.EventTopics;
import java.sql.SQLTransientException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(prefix = "blindway.kafka", name = "enabled", havingValue = "true")
public class KafkaEventConfig {

    @Bean
    NewTopic mqttIngressTopic() {
        return replicatedTopic(EventTopics.MQTT_INGRESS, "604800000");
    }

    @Bean
    NewTopic mqttIngressDeadLetterTopic() {
        return replicatedTopic(EventTopics.MQTT_INGRESS_DLT, "2592000000");
    }

    @Bean
    NewTopic perceptionRecordedTopic() {
        return replicatedTopic(EventTopics.PERCEPTION_RECORDED, "604800000");
    }

    @Bean
    NewTopic perceptionDeadLetterTopic() {
        return replicatedTopic(EventTopics.PERCEPTION_RECORDED_DLT, "2592000000");
    }

    private NewTopic replicatedTopic(String name, String retentionMs) {
        return TopicBuilder.name(name)
                .partitions(6)
                .replicas(3)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                .config(TopicConfig.RETENTION_MS_CONFIG, retentionMs)
                .build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, exception) -> {
            String dlt = EventTopics.MQTT_INGRESS.equals(record.topic())
                    ? EventTopics.MQTT_INGRESS_DLT
                    : EventTopics.PERCEPTION_RECORDED_DLT;
            return new TopicPartition(dlt, record.partition());
        });
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, poisonMessageBackOff());
        handler.setBackOffFunction((record, exception) -> kafkaBackOffFor(exception));
        return handler;
    }

    static FixedBackOff kafkaBackOffFor(Exception exception) {
        return isTransientInfrastructureFailure(exception)
                ? new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS)
                : poisonMessageBackOff();
    }

    private static boolean isTransientInfrastructureFailure(Throwable exception) {
        return hasCause(exception, DataAccessException.class)
                || hasCause(exception, CannotCreateTransactionException.class)
                || hasCause(exception, SQLTransientException.class);
    }

    private static FixedBackOff poisonMessageBackOff() {
        return new FixedBackOff(1000, 3);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
