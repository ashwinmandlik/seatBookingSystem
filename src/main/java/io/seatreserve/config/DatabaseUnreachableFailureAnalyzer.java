package io.seatreserve.config;

import java.sql.SQLException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

/**
 * Turns "no database at startup" into a short message with the ways to get one,
 * instead of a long stack trace: the usual first-run mistake is
 * {@code ./gradlew bootRun} with nothing listening on localhost:5432.
 */
class DatabaseUnreachableFailureAnalyzer implements FailureAnalyzer {

    private final Environment environment;

    DatabaseUnreachableFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        SQLException cannotConnect = findCannotConnect(failure);
        if (cannotConnect == null) {
            return null;
        }
        String where = redact(environment.getProperty("spring.datasource.url", "the configured database"));
        return new FailureAnalysis(
                "Could not connect to PostgreSQL at " + where + ": " + cannotConnect.getMessage(),
                """
                Start a database first. Any one of:
                  ./gradlew dev                 app + a throwaway embedded Postgres, one command, nothing to install
                  docker compose up --build     app, Postgres and Redis in containers
                  ./gradlew devDb               embedded Postgres only, then ./gradlew bootRun in another terminal
                  DATABASE_URL=postgresql://user:pass@host:5432/db ./gradlew bootRun   your own Postgres""",
                cannotConnect);
    }

    /** SQLState class 08 is "connection exception": refused, unknown host, timed out. */
    static SQLException findCannotConnect(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** Keeps host, port and database; drops credentials and query parameters. */
    static String redact(String jdbcUrl) {
        String url = jdbcUrl.replaceFirst("//[^/@]*@", "//");
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
