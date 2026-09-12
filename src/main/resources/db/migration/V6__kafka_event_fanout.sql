CREATE TABLE integration_event_outbox (
    id uuid PRIMARY KEY,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    topic varchar(200) NOT NULL,
    partition_key varchar(200) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(120),
    lease_until timestamptz,
    last_error varchar(500),
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT ck_event_outbox_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD'))
);
CREATE INDEX idx_event_outbox_pending
    ON integration_event_outbox (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PUBLISHING');

CREATE TABLE kafka_consumed_event (
    consumer_name varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    consumed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE community_candidate_projection (
    event_id uuid PRIMARY KEY,
    device_id uuid NOT NULL REFERENCES device(id),
    trip_id uuid REFERENCES trip(id),
    category varchar(40) NOT NULL,
    location geography(Point, 4326) NOT NULL,
    location_quality varchar(20) NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_candidate_location_quality CHECK (location_quality IN ('HIGH', 'MEDIUM'))
);
CREATE INDEX idx_community_candidate_location
    ON community_candidate_projection USING gist (location);

CREATE TABLE trip_risk_projection (
    trip_id uuid PRIMARY KEY REFERENCES trip(id) ON DELETE CASCADE,
    obstacle_event_count bigint NOT NULL DEFAULT 0,
    path_lost_event_count bigint NOT NULL DEFAULT 0,
    latest_event_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE device_event_daily_projection (
    device_id uuid NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    event_day date NOT NULL,
    heartbeat_count bigint NOT NULL DEFAULT 0,
    path_event_count bigint NOT NULL DEFAULT 0,
    obstacle_event_count bigint NOT NULL DEFAULT 0,
    latest_event_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (device_id, event_day)
);
