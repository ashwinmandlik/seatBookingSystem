package io.seatreserve.dev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;

/**
 * A throwaway Postgres 16 for running the app locally without Docker:
 * {@code ./gradlew devDb}, then {@code ./gradlew bootRun} in another terminal.
 * Creates the same database and user as docker-compose. Data is discarded on exit.
 */
public final class LocalPostgres {

    private LocalPostgres() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("port", "5432"));
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(port).start();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE seatreserve LOGIN PASSWORD 'seatreserve'");
            s.execute("CREATE DATABASE seatreserve OWNER seatreserve");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // exiting anyway
            }
        }));
        System.out.println("Postgres 16 ready: jdbc:postgresql://localhost:" + port
                + "/seatreserve (user/password: seatreserve). Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
