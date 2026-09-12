package com.blindway.perception.application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blindway.mqtt", name = "ingress-mode", havingValue = "database", matchIfMissing = true)
public class DatabaseMqttIngressGateway implements MqttIngressGateway {

    private final MqttIngressService ingress;

    public DatabaseMqttIngressGateway(MqttIngressService ingress) {
        this.ingress = ingress;
    }

    @Override
    public CompletionStage<MqttIngressService.Result> accept(String topic, byte[] payload) {
        return CompletableFuture.completedFuture(ingress.accept(topic, payload));
    }
}
