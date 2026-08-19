BEGIN;

SELECT setseed(0.20260817);

CREATE TEMP TABLE generated_load_issue ON COMMIT DROP AS
WITH seed_user AS (
    SELECT id
    FROM app_user
    WHERE email = :'seed_email'
),
generated AS (
    SELECT
        number,
        gen_random_uuid() AS id,
        CURRENT_TIMESTAMP - random() * interval '90 days' AS occurred_at,
        116.397128
            + CASE
                WHEN number % 10 < 7 THEN (random() - 0.5) * 0.01
                ELSE (random() - 0.5) * 0.20
            END AS longitude,
        39.916527
            + CASE
                WHEN number % 10 < 7 THEN (random() - 0.5) * 0.01
                ELSE (random() - 0.5) * 0.20
            END AS latitude
    FROM generate_series(1, :issue_count::integer) AS number
)
SELECT
    generated.*,
    seed_user.id AS reporter_user_id,
    (ARRAY[
        'TACTILE_PAVING_DAMAGED',
        'TACTILE_PAVING_MISSING',
        'TACTILE_PAVING_INTERRUPTED',
        'LONG_TERM_OCCUPATION',
        'CONSTRUCTION',
        'ACCESSIBLE_RAMP',
        'AUDIBLE_SIGNAL',
        'ACCESSIBLE_PLACE'
    ])[1 + number % 8] AS issue_type,
    (ARRAY['PENDING', 'VERIFIED', 'PROCESSING'])[1 + number % 3] AS issue_status
FROM generated
CROSS JOIN seed_user;

INSERT INTO accessibility_issue (
    id,
    reporter_user_id,
    tactile_path_segment_id,
    type,
    description,
    status,
    location,
    created_at,
    updated_at,
    severity,
    report_count,
    confirmation_count,
    rejection_count,
    confidence_score,
    last_reported_at
)
SELECT
    id,
    reporter_user_id,
    NULL,
    issue_type,
    format('LOAD_SEED_%s_%s', :'run_id', number),
    issue_status,
    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
    occurred_at,
    occurred_at,
    1 + number % 5,
    1 + number % 10,
    number % 5,
    number % 2,
    20 + number % 81,
    occurred_at
FROM generated_load_issue;

INSERT INTO issue_report (
    id,
    issue_id,
    reporter_user_id,
    source,
    type,
    description,
    severity,
    location,
    occurred_at,
    created_at
)
SELECT
    gen_random_uuid(),
    id,
    reporter_user_id,
    'ADMIN_IMPORT',
    issue_type,
    format('LOAD_SEED_%s_%s', :'run_id', number),
    1 + number % 5,
    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
    occurred_at,
    occurred_at
FROM generated_load_issue;

ANALYZE accessibility_issue;
ANALYZE issue_report;

COMMIT;
