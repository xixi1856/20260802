package com.blindway.perception.application;

import java.util.concurrent.CompletionStage;

public interface MqttIngressGateway {

    CompletionStage<MqttIngressService.Result> accept(String topic, byte[] payload);
}
