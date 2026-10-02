package io.seatreserve.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void convertsAProviderStyleUrlKeepingSslParameters() {
        Map<String, Object> props = DatabaseUrlEnvironmentPostProcessor.convert(
                "postgresql://app_user:s3cr%40t@ep-cool-name.ap-southeast-1.aws.neon.tech/seatreserve?sslmode=require");

        assertThat(props).containsEntry("spring.datasource.url",
                        "jdbc:postgresql://ep-cool-name.ap-southeast-1.aws.neon.tech/seatreserve?sslmode=require")
                .containsEntry("spring.datasource.username", "app_user")
                .containsEntry("spring.datasource.password", "s3cr@t");
    }

    @Test
    void keepsAnExplicitPort() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.convert("postgres://u:p@db.internal:5433/app"))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.internal:5433/app");
    }

    @Test
    void leavesJdbcUrlsAndMissingValuesAlone() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.convert("jdbc:postgresql://localhost/x")).isEmpty();
        assertThat(DatabaseUrlEnvironmentPostProcessor.convert(null)).isEmpty();
    }
}
