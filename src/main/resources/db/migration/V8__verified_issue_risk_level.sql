ALTER TABLE issue_verification
    ADD COLUMN risk_level varchar(10),
    ADD CONSTRAINT ck_verification_risk_level
        CHECK ((decision = 'CONFIRM' AND (risk_level IS NULL OR risk_level IN ('HIGH', 'MEDIUM', 'LOW')))
            OR (decision = 'REJECT' AND risk_level IS NULL));

ALTER TABLE accessibility_issue
    ADD COLUMN verified_risk_level varchar(10),
    ADD CONSTRAINT ck_verified_risk_level
        CHECK (verified_risk_level IS NULL OR verified_risk_level IN ('HIGH', 'MEDIUM', 'LOW'));

CREATE INDEX idx_issue_verified_risk_location
    ON accessibility_issue USING gist (location)
    WHERE status IN ('VERIFIED', 'PROCESSING') AND verified_risk_level IS NOT NULL;
