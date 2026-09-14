package com.blindway.perception.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MqttIngressModeGuardTest {

    @Test
    void databaseModeWorksWithAndWithoutKafkaFanout() {
        assertThatCode(() -> new MqttIngressModeGuard("database", false)).doesNotThrowAnyException();
        assertThatCode(() -> new MqttIngressModeGuard("database", true)).doesNotThrowAnyException();
    }

    @Test
    void legacyKafkaIngressRequiresKafkaEnabled() {
        assertThatThrownBy(() -> new MqttIngressModeGuard("kafka", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires blindway.kafka.enabled=true");
        assertThatCode(() -> new MqttIngressModeGuard("kafka", true)).doesNotThrowAnyException();
    }

    @Test
    void unknownModeIsRejectedAtStartup() {
        assertThatThrownBy(() -> new MqttIngressModeGuard("unexpected", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported MQTT ingress mode");
    }
}
