package com.blindway.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Concurrent MQTT publisher for repeatable local ingestion load tests. */
public final class MqttLoadSimulator {

    private MqttLoadSimulator() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: <brokerUri> <devices.tsv> [messagesPerDevice=100] [intervalMs=200]");
            System.exit(2);
        }

        String broker = args[0];
        List<Device> devices = readDevices(Path.of(args[1]));
        int messagesPerDevice = args.length >= 3 ? Integer.parseInt(args[2]) : 100;
        long intervalMs = args.length >= 4 ? Long.parseLong(args[3]) : 200;
        if (devices.isEmpty() || messagesPerDevice < 1 || intervalMs < 0) {
            throw new IllegalArgumentException("At least one device and one message are required; interval must be non-negative");
        }

        AtomicInteger published = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(devices.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(devices.size());
        Instant startedAt = Instant.now();

        for (Device device : devices) {
            workers.submit(() -> publishForDevice(
                    broker, device, messagesPerDevice, intervalMs, ready, start, published, failed));
        }

        if (!ready.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("MQTT clients did not become ready within 30 seconds");
        }
        start.countDown();
        workers.shutdown();
        long timeoutSeconds = Math.max(60, (messagesPerDevice * intervalMs / 1000) + 60);
        if (!workers.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            throw new IllegalStateException("MQTT publishers did not finish within the timeout");
        }

        double elapsedSeconds = Math.max(0.001, Duration.between(startedAt, Instant.now()).toMillis() / 1000.0);
        System.out.printf(
                "devices=%d attempted=%d published=%d failed=%d elapsedSeconds=%.3f publishRate=%.2f msg/s%n",
                devices.size(), devices.size() * messagesPerDevice, published.get(), failed.get(), elapsedSeconds,
                published.get() / elapsedSeconds);
        if (failed.get() > 0) {
            System.exit(1);
        }
    }

    private static void publishForDevice(
            String broker,
            Device device,
            int messagesPerDevice,
            long intervalMs,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicInteger published,
            AtomicInteger failed) {
        UUID bootId = UUID.randomUUID();
        try (MqttClient client = new MqttClient(broker, device.deviceId().toString())) {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(device.deviceId().toString());
            options.setPassword(device.secret().toCharArray());
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(20);
            client.connect(options);
            ready.countDown();
            start.await();

            for (int sequence = 1; sequence <= messagesPerDevice; sequence++) {
                Instant occurredAt = Instant.now();
                String json = envelope(device, bootId, sequence, occurredAt);
                MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                client.publish("blindway/v1/devices/" + device.deviceId() + "/path-events", message);
                published.incrementAndGet();
                if (intervalMs > 0 && sequence < messagesPerDevice) {
                    Thread.sleep(intervalMs);
                }
            }
            client.disconnect();
        } catch (Exception exception) {
            failed.incrementAndGet();
            System.err.printf("publisher failed for device %s: %s%n", device.deviceId(), exception.getMessage());
        } finally {
            ready.countDown();
        }
    }

    private static String envelope(Device device, UUID bootId, int sequence, Instant occurredAt) {
        return """
                {"schemaVersion":"1.0","eventId":"%s","deviceId":"%s","tripId":"%s",\
                "bootId":"%s","sequenceNo":%d,"occurredAt":"%s","sentAt":"%s",\
                "payload":{"state":"CENTER","confidence":0.92,"nearestDistanceMeters":1.4,\
                "lateralOffsetMeters":0.02,"measurementQuality":"MEDIUM","processingLatencyMs":86}}
                """.formatted(
                        UUID.randomUUID(), device.deviceId(), device.tripId(), bootId, sequence, occurredAt, Instant.now());
    }

    private static List<Device> readDevices(Path path) throws Exception {
        List<Device> devices = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("deviceId\t")) {
                continue;
            }
            String[] fields = line.split("\\t");
            if (fields.length != 3) {
                throw new IllegalArgumentException("Invalid device TSV row");
            }
            devices.add(new Device(UUID.fromString(fields[0]), fields[1], UUID.fromString(fields[2])));
        }
        return devices;
    }

    private record Device(UUID deviceId, String secret, UUID tripId) {}
}
