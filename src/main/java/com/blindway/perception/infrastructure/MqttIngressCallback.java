package com.blindway.perception.infrastructure;

import com.blindway.perception.application.MqttIngressGateway;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MqttIngressCallback implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttIngressCallback.class);

    private final MqttAsyncClient client;
    private final MqttIngressGateway gateway;
    private final String subscriptionGroup;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    MqttIngressCallback(MqttAsyncClient client, MqttIngressGateway gateway, String subscriptionGroup) {
        this.client = client;
        this.gateway = gateway;
        this.subscriptionGroup = subscriptionGroup;
    }

    static void subscribe(MqttAsyncClient client, String subscriptionGroup, boolean wait) throws Exception {
        String prefix = "$share/" + subscriptionGroup + "/";
        var heartbeat = client.subscribe(prefix + "blindway/v1/devices/+/heartbeat", 1);
        var path = client.subscribe(prefix + "blindway/v1/devices/+/path-events", 1);
        var obstacle = client.subscribe(prefix + "blindway/v1/devices/+/obstacle-events", 1);
        if (wait) {
            heartbeat.waitForCompletion(5000);
            path.waitForCompletion(5000);
            obstacle.waitForCompletion(5000);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            // Explicit reconnect() is not consistently reported as an automatic
            // reconnect by every Paho path. Re-register idempotently after every
            // successful connection so a live process can never stay unsubscribed.
            // Never wait here: this callback thread also dispatches token completion.
            subscribe(client, subscriptionGroup, false);
            log.info("MQTT subscriptions registered; reconnect={} server={}", reconnect, serverURI);
        } catch (Exception exception) {
            log.error("Unable to restore MQTT subscriptions", exception);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        try {
            int messageId = message.getId();
            int qos = message.getQos();
            gateway.accept(topic, message.getPayload()).whenComplete((result, exception) -> {
                if (exception == null) {
                    acknowledge(messageId, qos);
                } else {
                    log.error("Unable to durably accept MQTT message from topic {}; ACK withheld", topic, exception);
                    scheduleReconnect();
                }
            });
        } catch (RuntimeException exception) {
            log.error("Unable to durably accept MQTT message from topic {}; ACK withheld", topic, exception);
            throw exception;
        }
    }

    private void acknowledge(int messageId, int qos) {
        if (qos <= 0) {
            return;
        }
        try {
            client.messageArrivedComplete(messageId, qos);
        } catch (Exception exception) {
            log.error("Kafka accepted MQTT message but MQTT ACK failed; duplicate delivery is expected", exception);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> {
            try {
                client.disconnectForcibly(0, 0, false);
                client.reconnect();
            } catch (Exception exception) {
                log.error("Unable to reconnect MQTT client after withheld ACK", exception);
            } finally {
                reconnectScheduled.set(false);
            }
        });
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Backend does not publish v1 perception messages over MQTT.
    }
}
