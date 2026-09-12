package com.blindway.common;

public final class EventTopics {

    public static final String MQTT_INGRESS = "blindway.mqtt.ingress.v1";
    public static final String MQTT_INGRESS_DLT = MQTT_INGRESS + ".DLT";
    public static final String PERCEPTION_RECORDED = "blindway.perception.recorded.v1";
    public static final String PERCEPTION_RECORDED_DLT = PERCEPTION_RECORDED + ".DLT";

    private EventTopics() {}
}
