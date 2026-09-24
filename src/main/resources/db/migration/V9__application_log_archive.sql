CREATE TABLE application_log_archive (
    log_id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    level varchar(10) NOT NULL,
    logger varchar(200) NOT NULL,
    message varchar(2000) NOT NULL,
    service varchar(80) NOT NULL,
    instance varchar(120),
    trace_id varchar(64),
    archived_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_application_log_level CHECK (level IN ('WARN', 'ERROR'))
);
CREATE INDEX idx_application_log_time ON application_log_archive (occurred_at DESC);
