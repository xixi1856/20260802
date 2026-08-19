package com.blindway.tools;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public final class PiSimulator {

    private PiSimulator() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: <brokerUri> <deviceId> <deviceSecret> [tripId]");
            System.exit(2);
        }
        String broker = args[0];
        UUID deviceId = UUID.fromString(args[1]);
        String secret = args[2];
        String trip = args.length >= 4 ? "\"" + UUID.fromString(args[3]) + "\"" : "null";
        UUID bootId = UUID.randomUUID();
        Instant now = Instant.now();

        MqttClient client = new MqttClient(broker, deviceId.toString());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(deviceId.toString());
        options.setPassword(secret.toCharArray());
        client.connect(options);

        String json = """
                {
                  "schemaVersion":"1.0",
                  "eventId":"%s",
                  "deviceId":"%s",
                  "tripId":%s,
                  "bootId":"%s",
                  "sequenceNo":1,
                  "occurredAt":"%s",
                  "sentAt":"%s",
                  "payload":{
                    "state":"CENTER",
                    "confidence":0.92,
                    "nearestDistanceMeters":1.4,
                    "lateralOffsetMeters":0.02,
                    "measurementQuality":"MEDIUM",
                    "processingLatencyMs":86
                  }
                }
                """.formatted(UUID.randomUUID(), deviceId, trip, bootId, now, Instant.now());
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        client.publish("blindway/v1/devices/" + deviceId + "/path-events", message);
        client.disconnect();
        client.close();
        System.out.println("Published path event for device " + deviceId);
    }
}
