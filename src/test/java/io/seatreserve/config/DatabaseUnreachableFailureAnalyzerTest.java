package io.seatreserve.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUnreachableFailureAnalyzerTest {

    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/seatreserve");
    private final DatabaseUnreachableFailureAnalyzer analyzer = new DatabaseUnreachableFailureAnalyzer(environment);

    @Test
    void explainsAConnectionRefusedBuriedInTheStartupFailure() {
        SQLException refused = new SQLException("Connection to localhost:5432 refused.", "08001");
        Exception startup = new IllegalStateException("Error creating bean 'flywayInitializer'",
                new RuntimeException("Unable to obtain connection from database", refused));

        FailureAnalysis analysis = analyzer.analyze(startup);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("jdbc:postgresql://localhost:5432/seatreserve")
                .contains("Connection to localhost:5432 refused.");
        assertThat(analysis.getAction()).contains("./gradlew dev").contains("docker compose up");
        assertThat(analysis.getCause()).isSameAs(refused);
    }

    @Test
    void leavesOtherFailuresToSpring() {
        assertThat(analyzer.analyze(new IllegalStateException("port 8080 in use"))).isNull();
        // a SQL error that is not a connection problem (here: syntax error)
        assertThat(analyzer.analyze(new RuntimeException(new SQLException("bad sql", "42601")))).isNull();
    }

    @Test
    void neverPrintsCredentials() {
        assertThat(DatabaseUnreachableFailureAnalyzer.redact(
                "jdbc:postgresql://db.example.com:5432/app?user=app&password=s3cret&sslmode=require"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/app");
        assertThat(DatabaseUnreachableFailureAnalyzer.redact("jdbc:postgresql://app:s3cret@db.example.com/app"))
                .isEqualTo("jdbc:postgresql://db.example.com/app");
    }
}
