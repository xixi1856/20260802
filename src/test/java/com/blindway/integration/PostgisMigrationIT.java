package com.blindway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PostgisMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("blindway")
            .withUsername("blindway")
            .withPassword("blindway");

    @Test
    void migrationCreatesPostgisSchema() throws Exception {
        migrate();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT postgis_version(), to_regclass('public.obstacle_event') IS NOT NULL, "
                                + "to_regclass('public.idx_issue_active_risk_score') IS NOT NULL, "
                                + "to_regclass('public.integration_event_outbox') IS NOT NULL, "
                                + "to_regclass('public.kafka_consumed_event') IS NOT NULL, "
                                + "to_regclass('public.trip_risk_projection') IS NOT NULL, "
                                + "to_regclass('public.application_log_archive') IS NOT NULL, "
                                + "to_regclass('public.idx_mqtt_inbox_claim') IS NOT NULL, "
                                + "EXISTS (SELECT 1 FROM information_schema.columns "
                                + "WHERE table_name = 'mqtt_inbox' AND column_name = 'lease_until'), "
                                + "EXISTS (SELECT 1 FROM information_schema.columns "
                                + "WHERE table_name = 'accessibility_issue' AND column_name = 'verified_risk_level')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNotBlank();
            assertThat(result.getBoolean(2)).isTrue();
            assertThat(result.getBoolean(3)).isTrue();
            assertThat(result.getBoolean(4)).isTrue();
            assertThat(result.getBoolean(5)).isTrue();
            assertThat(result.getBoolean(6)).isTrue();
            assertThat(result.getBoolean(7)).isTrue();
            assertThat(result.getBoolean(8)).isTrue();
            assertThat(result.getBoolean(9)).isTrue();
            assertThat(result.getBoolean(10)).isTrue();
        }
    }

    @Test
    void inboxClaimSkipsRowsLockedByAnotherWorker() throws Exception {
        migrate();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertInbox(first, 1);
        insertInbox(second, 2);

        try (var workerA = connection();
                var workerB = connection()) {
            workerA.setAutoCommit(false);
            workerB.setAutoCommit(false);
            try (var lock = workerA.prepareStatement(
                    "SELECT event_id FROM mqtt_inbox WHERE event_id IN (?, ?) ORDER BY event_id FOR UPDATE LIMIT 1")) {
                lock.setObject(1, first);
                lock.setObject(2, second);
                try (var ignored = lock.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }

            try (var claim = workerB.prepareStatement(
                    """
                            WITH candidates AS (
                                SELECT event_id FROM mqtt_inbox
                                WHERE process_status = 'PENDING' AND next_attempt_at <= now()
                                ORDER BY next_attempt_at, received_at
                                FOR UPDATE SKIP LOCKED LIMIT 2
                            )
                            UPDATE mqtt_inbox inbox
                            SET process_status = 'PROCESSING', lease_owner = 'worker-b',
                                lease_until = now() + interval '30 seconds',
                                attempt_count = inbox.attempt_count + 1
                            FROM candidates
                            WHERE inbox.event_id = candidates.event_id
                            RETURNING inbox.event_id
                            """)) {
                try (var claimed = claim.executeQuery()) {
                    assertThat(claimed.next()).isTrue();
                    assertThat(claimed.getObject(1, UUID.class)).isEqualTo(second);
                    assertThat(claimed.next()).isFalse();
                }
            }
            workerB.commit();
            workerA.rollback();
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertInbox(UUID eventId, long sequenceNo) throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement(
                        """
                        INSERT INTO mqtt_inbox
                            (event_id, device_id, topic, schema_version, boot_id, sequence_no,
                             occurred_at, sent_at, received_at, raw_payload, process_status, next_attempt_at)
                        VALUES (?, ?, ?, '1.0', ?, ?, ?, ?, ?, '{}'::jsonb, 'PENDING', ?)
                        """)) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            statement.setObject(1, eventId);
            statement.setObject(2, UUID.randomUUID());
            statement.setString(3, "blindway/v1/devices/00000000-0000-0000-0000-000000000010/path-events");
            statement.setObject(4, UUID.randomUUID());
            statement.setLong(5, sequenceNo);
            statement.setObject(6, now);
            statement.setObject(7, now);
            statement.setObject(8, now);
            statement.setObject(9, now);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
}
