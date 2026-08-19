ALTER TABLE accessibility_issue
    ADD COLUMN severity smallint NOT NULL DEFAULT 3,
    ADD COLUMN report_count integer NOT NULL DEFAULT 1,
    ADD COLUMN confirmation_count integer NOT NULL DEFAULT 0,
    ADD COLUMN rejection_count integer NOT NULL DEFAULT 0,
    ADD COLUMN confidence_score smallint NOT NULL DEFAULT 20,
    ADD COLUMN last_reported_at timestamptz;

UPDATE accessibility_issue
SET last_reported_at = created_at
WHERE last_reported_at IS NULL;

ALTER TABLE accessibility_issue
    ALTER COLUMN last_reported_at SET NOT NULL,
    ADD CONSTRAINT ck_issue_severity CHECK (severity BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_issue_report_count CHECK (report_count >= 1),
    ADD CONSTRAINT ck_issue_confirmation_count CHECK (confirmation_count >= 0),
    ADD CONSTRAINT ck_issue_rejection_count CHECK (rejection_count >= 0),
    ADD CONSTRAINT ck_issue_confidence_score CHECK (confidence_score BETWEEN 0 AND 100);

ALTER TABLE accessibility_issue DROP CONSTRAINT ck_issue_status;
ALTER TABLE accessibility_issue
    ADD CONSTRAINT ck_issue_status
    CHECK (status IN ('PENDING', 'VERIFIED', 'PROCESSING', 'REJECTED', 'RESOLVED', 'CLOSED', 'REOPENED'));

CREATE INDEX idx_issue_dedup
    ON accessibility_issue (type, status, last_reported_at DESC);
CREATE INDEX idx_issue_risk_feed
    ON accessibility_issue (status, severity DESC, confidence_score DESC, last_reported_at DESC);
