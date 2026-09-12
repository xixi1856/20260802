package com.blindway.perception.infrastructure;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.perception.application.MqttIngressGateway;
import com.blindway.perception.application.MqttIngressService;
import java.util.concurrent.CompletableFuture;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;

@ExtendWith(MockitoExtension.class)
class MqttIngressCallbackTest {

    @Mock
    private MqttAsyncClient client;

    @Mock
    private MqttIngressGateway gateway;

    private MqttIngressCallback callback;

    @BeforeEach
    void setUp() {
        callback = new MqttIngressCallback(client, gateway, "blindway-ingress");
    }

    @Test
    void acknowledgesQosOneOnlyAfterDurableFutureCompletes() throws Exception {
        MqttMessage message = qosOneMessage();
        CompletableFuture<MqttIngressService.Result> durable = new CompletableFuture<>();
        when(gateway.accept("topic", message.getPayload())).thenReturn(durable);

        callback.messageArrived("topic", message);

        verify(client, never()).messageArrivedComplete(message.getId(), 1);
        durable.complete(MqttIngressService.Result.ACCEPTED);
        verify(client).messageArrivedComplete(message.getId(), 1);
    }

    @Test
    void failedDurableFutureDoesNotAcknowledge() throws Exception {
        MqttMessage message = qosOneMessage();
        CompletableFuture<MqttIngressService.Result> failed = new CompletableFuture<>();
        when(gateway.accept("topic", message.getPayload())).thenReturn(failed);

        callback.messageArrived("topic", message);
        failed.completeExceptionally(new TransientDataAccessResourceException("durable boundary unavailable"));

        verify(client, never()).messageArrivedComplete(message.getId(), 1);
    }

    @Test
    void restoresEverySharedSubscriptionAfterAnyConnectionCompletion() throws Exception {
        callback.connectComplete(false, "tcp://emqx:1883");

        verify(client).subscribe("$share/blindway-ingress/blindway/v1/devices/+/heartbeat", 1);
        verify(client).subscribe("$share/blindway-ingress/blindway/v1/devices/+/path-events", 1);
        verify(client).subscribe("$share/blindway-ingress/blindway/v1/devices/+/obstacle-events", 1);
    }

    private MqttMessage qosOneMessage() {
        MqttMessage message = new MqttMessage("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        message.setQos(1);
        message.setId(42);
        return message;
    }
}
