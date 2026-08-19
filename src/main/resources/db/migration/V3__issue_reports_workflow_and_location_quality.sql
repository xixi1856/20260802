CREATE TABLE issue_report (
    id uuid PRIMARY KEY,
    issue_id uuid NOT NULL REFERENCES accessibility_issue(id) ON DELETE CASCADE,
    reporter_user_id uuid REFERENCES app_user(id),
    source varchar(30) NOT NULL,
    type varchar(50) NOT NULL,
    description varchar(1000) NOT NULL,
    severity smallint NOT NULL,
    location geography(Point, 4326) NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_issue_report_source CHECK (source IN ('USER', 'DEVICE_CANDIDATE', 'ADMIN_IMPORT')),
    CONSTRAINT ck_issue_report_severity CHECK (severity BETWEEN 1 AND 5)
);
CREATE INDEX idx_issue_report_issue_time ON issue_report (issue_id, created_at DESC);
CREATE INDEX idx_issue_report_location_gist ON issue_report USING gist (location);

INSERT INTO issue_report
    (id, issue_id, reporter_user_id, source, type, description, severity, location, occurred_at, created_at)
SELECT gen_random_uuid(), id, reporter_user_id, 'USER', type, description, severity, location, created_at, created_at
FROM accessibility_issue;

ALTER TABLE accessibility_issue
    ADD COLUMN version integer NOT NULL DEFAULT 0,
    ADD COLUMN processing_by uuid REFERENCES app_user(id),
    ADD COLUMN processing_at timestamptz,
    ADD COLUMN closed_at timestamptz;

ALTER TABLE path_observation
    ADD COLUMN location_quality varchar(20) NOT NULL DEFAULT 'UNMATCHED',
    ADD COLUMN location_time_offset_ms bigint;
ALTER TABLE obstacle_event
    ADD COLUMN location_quality varchar(20) NOT NULL DEFAULT 'UNMATCHED',
    ADD COLUMN location_time_offset_ms bigint;

ALTER TABLE path_observation
    ADD CONSTRAINT ck_path_location_quality CHECK (location_quality IN ('HIGH', 'MEDIUM', 'LOW', 'UNMATCHED'));
ALTER TABLE obstacle_event
    ADD CONSTRAINT ck_obstacle_location_quality CHECK (location_quality IN ('HIGH', 'MEDIUM', 'LOW', 'UNMATCHED'));
