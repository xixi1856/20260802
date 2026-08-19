package com.blindway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgisMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgis/postgis:18-3.6")
            .withDatabaseName("blindway")
            .withUsername("blindway")
            .withPassword("blindway");

    @Test
    void migrationCreatesPostgisSchema() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT postgis_version(), to_regclass('public.obstacle_event') IS NOT NULL")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNotBlank();
            assertThat(result.getBoolean(2)).isTrue();
        }
    }
}
