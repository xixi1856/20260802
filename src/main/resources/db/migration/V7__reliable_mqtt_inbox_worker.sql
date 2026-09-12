ALTER TABLE mqtt_inbox DROP CONSTRAINT ck_mqtt_inbox_status;

ALTER TABLE mqtt_inbox
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN lease_owner varchar(120),
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN last_error varchar(500);

UPDATE mqtt_inbox
SET process_status = 'PENDING'
WHERE process_status = 'RECEIVED';

UPDATE mqtt_inbox
SET next_attempt_at = COALESCE(processed_at, received_at)
WHERE next_attempt_at IS NULL;

ALTER TABLE mqtt_inbox
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ALTER COLUMN next_attempt_at SET DEFAULT now(),
    ADD CONSTRAINT ck_mqtt_inbox_status
        CHECK (process_status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'REJECTED', 'DEAD')),
    ADD CONSTRAINT ck_mqtt_inbox_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_mqtt_inbox_processable_payload
        CHECK (process_status NOT IN ('PENDING', 'PROCESSING') OR raw_payload IS NOT NULL);

DROP INDEX idx_mqtt_inbox_status;
CREATE INDEX idx_mqtt_inbox_claim
    ON mqtt_inbox (next_attempt_at, received_at)
    WHERE process_status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_mqtt_inbox_dead
    ON mqtt_inbox (processed_at DESC)
    WHERE process_status = 'DEAD';
