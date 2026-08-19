package com.blindway.perception.infrastructure;

import com.blindway.perception.application.MqttIngressService;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "blindway.mqtt", name = "enabled", havingValue = "true")
public class MqttClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttClientConfig.class);

    @Bean(destroyMethod = "close")
    MqttAsyncClient mqttClient(MqttProperties properties, MqttIngressService ingress) throws Exception {
        MqttAsyncClient client =
                new MqttAsyncClient(properties.brokerUri(), properties.clientId(), new MemoryPersistence());
        client.setCallback(new IngressCallback(client, ingress));

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(20);
        options.setUserName(properties.username());
        options.setPassword(properties.password().toCharArray());
        client.connect(options).waitForCompletion(5000);
        subscribe(client);
        return client;
    }

    private static void subscribe(MqttAsyncClient client) throws Exception {
        client.subscribe("blindway/v1/devices/+/heartbeat", 0).waitForCompletion(5000);
        client.subscribe("blindway/v1/devices/+/path-events", 1).waitForCompletion(5000);
        client.subscribe("blindway/v1/devices/+/obstacle-events", 1).waitForCompletion(5000);
    }

    private static final class IngressCallback implements MqttCallbackExtended {
        private final MqttAsyncClient client;
        private final MqttIngressService ingress;

        private IngressCallback(MqttAsyncClient client, MqttIngressService ingress) {
            this.client = client;
            this.ingress = ingress;
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            if (reconnect) {
                try {
                    // This callback runs on Paho's callback thread. Waiting for
                    // subscription completion here blocks the same thread that
                    // must dispatch the completion token and causes reconnect
                    // timeouts under load.
                    client.subscribe("blindway/v1/devices/+/heartbeat", 0);
                    client.subscribe("blindway/v1/devices/+/path-events", 1);
                    client.subscribe("blindway/v1/devices/+/obstacle-events", 1);
                } catch (Exception exception) {
                    log.error("Unable to restore MQTT subscriptions", exception);
                }
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            try {
                ingress.accept(topic, message.getPayload());
            } catch (RuntimeException exception) {
                // Do not let one malformed or temporarily unpersistable event
                // terminate Paho's callback thread and disconnect the client.
                log.error("Unable to process MQTT message from topic {}", topic, exception);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Backend does not publish v1 perception messages.
        }
    }
}
