package io.seatreserve.dev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A throwaway Postgres 16 for running the app locally without Docker:
 * {@code ./gradlew devDb}, e.g. to run SeatReserveApplication from an IDE
 * ({@code ./gradlew bootRun} starts one together with the app).
 * Creates the same database and user as docker-compose. Data is discarded on exit.
 */
public final class LocalPostgres {

    static final String DATABASE = "seatreserve";
    static final String USER = "seatreserve";
    static final String PASSWORD = "seatreserve";

    /**
     * A fixed data directory, so a Postgres left running by a hard-killed JVM (a
     * shutdown hook doesn't run then, e.g. Ctrl+C under Gradle on Windows) can be
     * found through its postmaster.pid and stopped on the next start.
     */
    private static final Path DATA_DIR = Path.of("build", "dev-postgres");

    private LocalPostgres() {
    }

    public static void main(String[] args) throws Exception {
        stopLeftoverFromPreviousRun();
        int port = Integer.parseInt(System.getProperty("port", "5432"));
        start(port);
        System.out.println("Postgres 16 ready: " + jdbcUrl(port)
                + " (user/password: seatreserve). Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    /** Starts Postgres on the port with the app's database and user; stopped when the JVM exits. */
    static EmbeddedPostgres start(int port) throws IOException, SQLException {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(DATA_DIR)
                .setCleanDataDirectory(true)
                .start();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + USER + " LOGIN PASSWORD '" + PASSWORD + "'");
            s.execute("CREATE DATABASE " + DATABASE + " OWNER " + USER);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // exiting anyway
            }
        }));
        return postgres;
    }

    /** Stops a Postgres an earlier, hard-killed run left behind, and removes its data. */
    static void stopLeftoverFromPreviousRun() throws IOException {
        Path pidFile = DATA_DIR.resolve("postmaster.pid");
        if (Files.isReadable(pidFile)) {
            List<String> lines = Files.readAllLines(pidFile);
            if (!lines.isEmpty() && lines.get(0).strip().matches("\\d+")) {
                ProcessHandle.of(Long.parseLong(lines.get(0).strip()))
                        .filter(p -> p.info().command().orElse("").toLowerCase().contains("postgres"))
                        .ifPresent(LocalPostgres::stop);
            }
        }
        if (Files.exists(DATA_DIR)) {
            try (Stream<Path> paths = Files.walk(DATA_DIR)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void stop(ProcessHandle postmaster) {
        System.out.println("Stopping a Postgres left over from a previous run (pid " + postmaster.pid() + ")");
        postmaster.descendants().forEach(ProcessHandle::destroyForcibly);
        postmaster.destroyForcibly();
        try {
            postmaster.onExit().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort: start() reports a busy port or locked directory if it is still there
        }
    }

    static String jdbcUrl(int port) {
        return "jdbc:postgresql://localhost:" + port + "/" + DATABASE;
    }
}
