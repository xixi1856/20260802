package com.blindway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blindway.common.IntegrationEventOutbox;
import com.blindway.common.application.TransactionalIntegrationEventOutbox;
import com.blindway.common.infrastructure.EventOutboxMapper;
import com.blindway.device.DeviceAccess;
import com.blindway.perception.application.MqttInboxProcessor;
import com.blindway.perception.domain.MqttEnvelope;
import com.blindway.perception.infrastructure.MqttInboxRow;
import com.blindway.perception.infrastructure.PerceptionMapper;
import com.blindway.trip.TripLocationAccess;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(MqttInboxTransactionIT.Config.class)
class MqttInboxTransactionIT {

    private static final UUID DEVICE_ID = UUID.fromString("cb94d02f-3843-41a4-8312-b549690ba21d");
    private static final String OWNER = "worker-a";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("blindway")
            .withUsername("blindway")
            .withPassword("blindway");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PerceptionMapper mapper;

    @Autowired
    private MqttInboxProcessor processor;

    @Autowired
    private DeviceAccess devices;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AtomicBoolean failAfterOutboxInsert;

    private MqttInboxRow row;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void seedClaimedHeartbeat() throws Exception {
        when(devices.existsAndEnabled(DEVICE_ID)).thenReturn(true);
        jdbc.update(
                """
                INSERT INTO device (id, label, secret_hash, status, created_at, updated_at)
                VALUES (?, 'transaction-test', repeat('0', 64), 'ACTIVE', now(), now())
                ON CONFLICT (id) DO NOTHING
                """,
                DEVICE_ID);
        String source = Files.readString(Path.of("contracts/examples/mqtt/heartbeat.valid.json"));
        String payload = source.replace("f175ac94-04d1-4f18-a2d1-a1fb053547d2", UUID.randomUUID().toString());
        MqttEnvelope envelope = objectMapper.readValue(payload, MqttEnvelope.class);
        String topic = "blindway/v1/devices/" + DEVICE_ID + "/heartbeat";
        Instant receivedAt = Instant.now();
        assertThat(mapper.insertInbox(envelope, topic, receivedAt, payload)).isEqualTo(1);
        jdbc.update(
                """
                UPDATE mqtt_inbox SET process_status = 'PROCESSING', lease_owner = ?,
                    lease_until = now() + interval '30 minutes', attempt_count = 1
                WHERE event_id = ?
                """,
                OWNER,
                envelope.eventId());
        row = new MqttInboxRow(envelope.eventId(), topic, payload, receivedAt, 1);
    }

    @AfterEach
    void restoreOutbox() {
        failAfterOutboxInsert.set(false);
    }

    @Test
    void successfulProcessingCommitsBusinessOutboxAndInboxStatusTogether() {
        assertThat(processor.process(row, OWNER)).isEqualTo(MqttInboxProcessor.Outcome.PROCESSED);

        assertThat(count("device_heartbeat", "event_id")).isEqualTo(1);
        assertThat(count("integration_event_outbox", "id")).isEqualTo(1);
        assertThat(status()).isEqualTo("PROCESSED");
    }

    @Test
    void outboxFailureRollsBackBusinessWriteAndLeavesInboxClaimed() {
        failAfterOutboxInsert.set(true);

        assertThatThrownBy(() -> processor.process(row, OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected outbox failure");

        assertThat(count("device_heartbeat", "event_id")).isZero();
        assertThat(count("integration_event_outbox", "id")).isZero();
        assertThat(status()).isEqualTo("PROCESSING");
    }

    @Test
    void lostLeaseAfterOutboxInsertRollsBackBothWrites() {
        assertThatThrownBy(() -> processor.process(row, "another-worker"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("lease was lost");

        assertThat(count("device_heartbeat", "event_id")).isZero();
        assertThat(count("integration_event_outbox", "id")).isZero();
        assertThat(status()).isEqualTo("PROCESSING");
    }

    private int count(String table, String idColumn) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + idColumn + " = ?", Integer.class, row.eventId());
    }

    private String status() {
        return jdbc.queryForObject(
                "SELECT process_status FROM mqtt_inbox WHERE event_id = ?", String.class, row.eventId());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {PerceptionMapper.class, EventOutboxMapper.class})
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory.getObject();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        DeviceAccess deviceAccess() {
            return mock(DeviceAccess.class);
        }

        @Bean
        TripLocationAccess tripLocationAccess() {
            return mock(TripLocationAccess.class);
        }

        @Bean
        AtomicBoolean failAfterOutboxInsert() {
            return new AtomicBoolean();
        }

        @Bean
        IntegrationEventOutbox integrationEventOutbox(
                EventOutboxMapper mapper, ObjectMapper objectMapper, AtomicBoolean failAfterOutboxInsert) {
            IntegrationEventOutbox durable = new TransactionalIntegrationEventOutbox(mapper, objectMapper, true);
            return (eventId, aggregateType, aggregateId, eventType, topic, partitionKey, payload) -> {
                durable.append(eventId, aggregateType, aggregateId, eventType, topic, partitionKey, payload);
                if (failAfterOutboxInsert.get()) {
                    throw new IllegalStateException("injected outbox failure");
                }
            };
        }

        @Bean
        MqttInboxProcessor mqttInboxProcessor(
                ObjectMapper objectMapper,
                Validator validator,
                DeviceAccess devices,
                TripLocationAccess trips,
                PerceptionMapper mapper,
                IntegrationEventOutbox outbox,
                MeterRegistry meterRegistry) {
            return new MqttInboxProcessor(objectMapper, validator, devices, trips, mapper, outbox, meterRegistry);
        }
    }
}
