package com.blindway.perception.infrastructure;

import com.blindway.perception.application.MqttIngressGateway;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "blindway.mqtt", name = "enabled", havingValue = "true")
public class MqttClientConfig {

    @Bean(destroyMethod = "close")
    MqttAsyncClient mqttClient(MqttProperties properties, MqttIngressGateway gateway) throws Exception {
        MqttAsyncClient client =
                new MqttAsyncClient(properties.brokerUri(), properties.clientId(), new MemoryPersistence());
        client.setManualAcks(true);
        client.setCallback(new MqttIngressCallback(client, gateway, properties.subscriptionGroup()));

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(properties.cleanSession());
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(20);
        options.setUserName(properties.username());
        options.setPassword(properties.password().toCharArray());
        client.connect(options).waitForCompletion(5000);
        MqttIngressCallback.subscribe(client, properties.subscriptionGroup(), true);
        return client;
    }
}
