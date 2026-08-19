CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    display_name varchar(80) NOT NULL,
    role varchar(20) NOT NULL DEFAULT 'USER',
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'VOLUNTEER', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE refresh_token (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    token_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_token_user_expiry ON refresh_token (user_id, expires_at);

CREATE TABLE device (
    id uuid PRIMARY KEY,
    owner_user_id uuid REFERENCES app_user(id),
    label varchar(80) NOT NULL,
    secret_hash char(64) NOT NULL,
    status varchar(20) NOT NULL,
    last_seen_at timestamptz,
    software_version varchar(64),
    model_version varchar(128),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_device_status CHECK (status IN ('PROVISIONED', 'ACTIVE', 'DISABLED'))
);
CREATE INDEX idx_device_owner ON device (owner_user_id, created_at DESC);

CREATE TABLE device_heartbeat (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    device_id uuid NOT NULL REFERENCES device(id),
    boot_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    software_version varchar(64) NOT NULL,
    model_version varchar(128) NOT NULL,
    config_version varchar(64),
    uptime_seconds bigint NOT NULL,
    cpu_temperature_c double precision,
    cpu_usage_percent double precision NOT NULL,
    memory_usage_percent double precision NOT NULL,
    disk_usage_percent double precision NOT NULL,
    inference_fps double precision,
    average_inference_latency_ms integer,
    network_type varchar(20),
    signal_dbm integer,
    camera_status varchar(20) NOT NULL,
    stereo_status varchar(20) NOT NULL,
    inference_status varchar(20) NOT NULL,
    clock_synchronized boolean NOT NULL,
    pending_event_count integer NOT NULL DEFAULT 0,
    dropped_event_count integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_heartbeat_camera CHECK (camera_status IN ('UP', 'DEGRADED', 'DOWN')),
    CONSTRAINT ck_heartbeat_stereo CHECK (stereo_status IN ('UP', 'DEGRADED', 'DOWN')),
    CONSTRAINT ck_heartbeat_inference CHECK (inference_status IN ('UP', 'DEGRADED', 'DOWN'))
);
CREATE INDEX idx_heartbeat_device_time ON device_heartbeat (device_id, occurred_at DESC);

CREATE TABLE trip (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    device_id uuid NOT NULL REFERENCES device(id),
    status varchar(20) NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_trip_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
);
CREATE UNIQUE INDEX uk_trip_active_device ON trip (device_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_trip_user_time ON trip (user_id, started_at DESC);

CREATE TABLE trip_track_point (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trip_id uuid NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    recorded_at timestamptz NOT NULL,
    location geography(Point, 4326) NOT NULL,
    accuracy_meters double precision NOT NULL,
    speed_meters_per_second double precision,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_trip_track_time UNIQUE (trip_id, recorded_at),
    CONSTRAINT ck_track_accuracy CHECK (accuracy_meters >= 0),
    CONSTRAINT ck_track_speed CHECK (speed_meters_per_second IS NULL OR speed_meters_per_second >= 0)
);
CREATE INDEX idx_track_trip_time ON trip_track_point (trip_id, recorded_at);
CREATE INDEX idx_track_location_gist ON trip_track_point USING gist (location);

CREATE TABLE mqtt_inbox (
    event_id uuid PRIMARY KEY,
    device_id uuid NOT NULL,
    trip_id uuid,
    topic varchar(200) NOT NULL,
    schema_version varchar(16) NOT NULL,
    boot_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    sent_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    raw_payload jsonb NOT NULL,
    process_status varchar(20) NOT NULL,
    error_code varchar(80),
    processed_at timestamptz,
    CONSTRAINT ck_mqtt_inbox_status CHECK (process_status IN ('RECEIVED', 'PROCESSED', 'REJECTED'))
);
CREATE INDEX idx_mqtt_inbox_device_time ON mqtt_inbox (device_id, occurred_at DESC);
CREATE INDEX idx_mqtt_inbox_status ON mqtt_inbox (process_status, received_at);

CREATE TABLE path_observation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE REFERENCES mqtt_inbox(event_id),
    device_id uuid NOT NULL REFERENCES device(id),
    trip_id uuid REFERENCES trip(id),
    matched_track_point_id bigint REFERENCES trip_track_point(id),
    location geography(Point, 4326),
    location_match_status varchar(30) NOT NULL,
    state varchar(20) NOT NULL,
    confidence double precision NOT NULL,
    nearest_distance_meters double precision,
    lateral_offset_meters double precision,
    measurement_quality varchar(20) NOT NULL,
    processing_latency_ms integer NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    CONSTRAINT ck_path_state CHECK (state IN ('LEFT', 'CENTER', 'RIGHT', 'NOT_DETECTED')),
    CONSTRAINT ck_path_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_path_measurement_quality CHECK (measurement_quality IN ('HIGH', 'MEDIUM', 'LOW', 'UNAVAILABLE'))
);
CREATE INDEX idx_path_trip_time ON path_observation (trip_id, occurred_at DESC);
CREATE INDEX idx_path_location_gist ON path_observation USING gist (location);

CREATE TABLE obstacle_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE REFERENCES mqtt_inbox(event_id),
    device_id uuid NOT NULL REFERENCES device(id),
    trip_id uuid REFERENCES trip(id),
    matched_track_point_id bigint REFERENCES trip_track_point(id),
    location geography(Point, 4326),
    location_match_status varchar(30) NOT NULL,
    tactile_corridor_confidence double precision NOT NULL,
    warning_threshold_meters double precision NOT NULL,
    minimum_distance_meters double precision NOT NULL,
    processing_latency_ms integer NOT NULL,
    obstacles jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    CONSTRAINT ck_obstacle_corridor_confidence CHECK (tactile_corridor_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_obstacle_distance CHECK (minimum_distance_meters > 0 AND minimum_distance_meters <= warning_threshold_meters)
);
CREATE INDEX idx_obstacle_trip_time ON obstacle_event (trip_id, occurred_at DESC);
CREATE INDEX idx_obstacle_location_gist ON obstacle_event USING gist (location);

CREATE TABLE tactile_path_segment (
    id uuid PRIMARY KEY,
    path geometry(LineString, 4326) NOT NULL,
    source varchar(30) NOT NULL,
    verification_status varchar(20) NOT NULL,
    created_by uuid REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_tactile_path_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);
CREATE INDEX idx_tactile_path_gist ON tactile_path_segment USING gist (path);

CREATE TABLE accessibility_issue (
    id uuid PRIMARY KEY,
    reporter_user_id uuid NOT NULL REFERENCES app_user(id),
    tactile_path_segment_id uuid REFERENCES tactile_path_segment(id),
    type varchar(50) NOT NULL,
    description varchar(1000) NOT NULL,
    status varchar(20) NOT NULL,
    location geography(Point, 4326) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by uuid REFERENCES app_user(id),
    CONSTRAINT ck_issue_type CHECK (type IN ('TACTILE_PAVING_DAMAGED', 'TACTILE_PAVING_MISSING', 'TACTILE_PAVING_INTERRUPTED', 'LONG_TERM_OCCUPATION', 'CONSTRUCTION', 'ACCESSIBLE_RAMP', 'AUDIBLE_SIGNAL', 'ACCESSIBLE_PLACE')),
    CONSTRAINT ck_issue_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'RESOLVED'))
);
CREATE INDEX idx_issue_location_gist ON accessibility_issue USING gist (location);
CREATE INDEX idx_issue_status_time ON accessibility_issue (status, created_at DESC);

CREATE TABLE media_asset (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES app_user(id),
    object_key varchar(300) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    privacy_processed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_media_size CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

CREATE TABLE issue_evidence (
    id uuid PRIMARY KEY,
    issue_id uuid NOT NULL REFERENCES accessibility_issue(id) ON DELETE CASCADE,
    media_asset_id uuid NOT NULL REFERENCES media_asset(id),
    submitted_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_issue_evidence_media UNIQUE (issue_id, media_asset_id)
);

CREATE TABLE issue_verification (
    id uuid PRIMARY KEY,
    issue_id uuid NOT NULL REFERENCES accessibility_issue(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id),
    decision varchar(20) NOT NULL,
    note varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_issue_verification_user UNIQUE (issue_id, user_id),
    CONSTRAINT ck_issue_verification_decision CHECK (decision IN ('CONFIRM', 'REJECT'))
);

CREATE TABLE issue_status_history (
    id uuid PRIMARY KEY,
    issue_id uuid NOT NULL REFERENCES accessibility_issue(id) ON DELETE CASCADE,
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    changed_by uuid NOT NULL REFERENCES app_user(id),
    reason varchar(500),
    changed_at timestamptz NOT NULL
);
CREATE INDEX idx_issue_history_issue_time ON issue_status_history (issue_id, changed_at);

CREATE TABLE route_plan (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    origin_wgs84 geography(Point, 4326) NOT NULL,
    destination_wgs84 geography(Point, 4326) NOT NULL,
    distance_meters integer,
    duration_seconds integer,
    provider varchar(20) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_route_provider CHECK (provider IN ('AMAP'))
);
