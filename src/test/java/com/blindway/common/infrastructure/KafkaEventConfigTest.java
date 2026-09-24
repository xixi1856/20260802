package com.blindway.common.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.blindway.common.EventTopics;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

class KafkaEventConfigTest {

    @Test
    void declaresApplicationLogArchiveAndDeadLetterTopics() {
        KafkaEventConfig config = new KafkaEventConfig();

        assertThat(config.applicationLogArchiveTopic().name()).isEqualTo(EventTopics.APPLICATION_LOG_ARCHIVE);
        assertThat(config.applicationLogArchiveDeadLetterTopic().name())
                .isEqualTo(EventTopics.APPLICATION_LOG_ARCHIVE_DLT);
    }

    @Test
    void databaseOutageUsesUnlimitedRetrySoKafkaRetainsBacklog() {
        Exception wrappedDatabaseFailure = new ListenerExecutionFailedException(
                "listener failed", new TransientDataAccessResourceException("postgres unavailable"));

        FixedBackOff backOff = KafkaEventConfig.kafkaBackOffFor(wrappedDatabaseFailure);

        assertThat(backOff.getInterval()).isEqualTo(1000);
        assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);
    }

    @Test
    void transactionCreationFailureUsesUnlimitedRetryInsteadOfDeadLettering() {
        Exception wrappedDatabaseFailure = new ListenerExecutionFailedException(
                "listener failed", new CannotCreateTransactionException("Could not open JDBC connection"));

        FixedBackOff backOff = KafkaEventConfig.kafkaBackOffFor(wrappedDatabaseFailure);

        assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);
    }

    @Test
    void poisonMessageHasBoundedRetriesBeforeDeadLettering() {
        FixedBackOff backOff = KafkaEventConfig.kafkaBackOffFor(new IllegalArgumentException("invalid payload"));

        assertThat(backOff.getInterval()).isEqualTo(1000);
        assertThat(backOff.getMaxAttempts()).isEqualTo(3);
    }
}
