package io.seatreserve.dev;

import io.seatreserve.SeatReserveApplication;
import java.io.IOException;
import java.net.ServerSocket;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * One command to run the app locally with nothing installed but a JDK:
 * {@code ./gradlew bootRun} (with no DATABASE_URL set) starts a throwaway Postgres 16 and the app in the same
 * process, and Ctrl+C stops both. Uses port 5432 when it's free, otherwise any
 * free port, so it never collides with a Postgres you already run.
 */
public final class LocalDev {

    private LocalDev() {
    }

    public static void main(String[] args) throws Exception {
        LocalPostgres.stopLeftoverFromPreviousRun();
        int port = isFree(5432) ? 5432 : anyFreePort();
        LocalPostgres.start(port);
        System.out.println("Postgres 16 started (embedded, port " + port + ", data discarded on exit)");

        String[] appArgs = new String[args.length + 3];
        appArgs[0] = "--spring.datasource.url=" + LocalPostgres.jdbcUrl(port);
        appArgs[1] = "--spring.datasource.username=" + LocalPostgres.USER;
        appArgs[2] = "--spring.datasource.password=" + LocalPostgres.PASSWORD;
        System.arraycopy(args, 0, appArgs, 3, args.length);
        ConfigurableApplicationContext app = SpringApplication.run(SeatReserveApplication.class, appArgs);

        String httpPort = app.getEnvironment().getProperty("local.server.port", "8080");
        System.out.println();
        System.out.println("Seat reserve running on http://localhost:" + httpPort
                + "   admin key: local-admin-key   Ctrl+C stops the app and Postgres");
    }

    private static boolean isFree(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int anyFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
