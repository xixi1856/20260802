package com.blindway.perception.application;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.blindway.perception.RawMqttIngressEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class KafkaMqttIngressConsumerTest {

    @Mock
    private MqttIngressService ingress;

    @InjectMocks
    private KafkaMqttIngressConsumer consumer;

    @Test
    void persistsKafkaRecordToInboxBeforeConsumerOffsetCanAdvance() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        RawMqttIngressEvent event = new RawMqttIngressEvent("topic", "{\"schemaVersion\":\"1.0\"}", Instant.EPOCH);
        consumer = new KafkaMqttIngressConsumer(mapper, ingress);

        consumer.consume(mapper.writeValueAsString(event));

        verify(ingress)
                .accept(eq("topic"), aryEq(event.rawPayload().getBytes(StandardCharsets.UTF_8)), eq(Instant.EPOCH));
    }
}
