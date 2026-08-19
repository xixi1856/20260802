CREATE INDEX IF NOT EXISTS idx_issue_active_risk_score
    ON accessibility_issue (((severity * confidence_score / 10)::integer) DESC)
    WHERE status IN ('PENDING', 'VERIFIED', 'PROCESSING');
