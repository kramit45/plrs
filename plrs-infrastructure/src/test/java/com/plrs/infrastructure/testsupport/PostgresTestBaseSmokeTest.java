package com.plrs.infrastructure.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class PostgresTestBaseSmokeTest extends PostgresTestBase {

    @Test
    void containerStartsAndAcceptsConnections() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (Connection connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }
}
