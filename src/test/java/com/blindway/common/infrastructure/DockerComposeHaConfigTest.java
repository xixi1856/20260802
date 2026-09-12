package com.blindway.common.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockerComposeHaConfigTest {

    private final String compose = readCompose();

    @Test
    void kafkaUsesNamedVolumesAtTheImageDataDirectory() {
        assertThat(compose)
                .contains("kafka-1-data:/var/lib/kafka/data")
                .contains("kafka-2-data:/var/lib/kafka/data")
                .contains("kafka-3-data:/var/lib/kafka/data")
                .doesNotContain("kafka-1-data:/tmp/kraft-combined-logs")
                .doesNotContain("kafka-2-data:/tmp/kraft-combined-logs")
                .doesNotContain("kafka-3-data:/tmp/kraft-combined-logs");
    }

    @Test
    void kafkaRequiresThreeReplicasTwoInSyncAndDockerSafeQuorumTimeouts() {
        assertThat(compose)
                .contains("KAFKA_DEFAULT_REPLICATION_FACTOR: 3")
                .contains("KAFKA_MIN_INSYNC_REPLICAS: 2")
                .contains("KAFKA_CONTROLLER_QUORUM_ELECTION_TIMEOUT_MS: 10000")
                .contains("KAFKA_CONTROLLER_QUORUM_FETCH_TIMEOUT_MS: 20000")
                .contains("test: [\"CMD-SHELL\", \"nc -z localhost 19092\"]")
                .doesNotContain("kafka-broker-api-versions.sh");
    }

    @Test
    void mqttBurstBufferIsFiniteAndSizedAboveTheAcceptanceBurst() {
        assertThat(compose).contains("EMQX_MQTT__MAX_INFLIGHT: 128").contains("EMQX_MQTT__MAX_MQUEUE_LEN: 10000");
    }

    private static String readCompose() {
        try {
            return Files.readString(Path.of("docker-compose.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read docker-compose.yml", exception);
        }
    }
}
