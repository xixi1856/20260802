ALTER TABLE mqtt_inbox
    ALTER COLUMN raw_payload DROP NOT NULL,
    ADD COLUMN kafka_partition integer,
    ADD COLUMN kafka_offset bigint;

COMMENT ON COLUMN mqtt_inbox.raw_payload IS
    'Legacy raw payload storage; Kafka is authoritative for messages received after V5.';
COMMENT ON COLUMN mqtt_inbox.kafka_partition IS 'Source Kafka partition for operational traceability.';
COMMENT ON COLUMN mqtt_inbox.kafka_offset IS 'Source Kafka offset for operational traceability.';
