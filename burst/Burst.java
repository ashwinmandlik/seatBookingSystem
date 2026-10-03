import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-sale stampede against a running seat-reserve service.
 *
 * <pre>
 *   java burst/Burst.java BASE_URL [--scale N] [--concurrency N] [--admin-key KEY] [--wait-for-expiry]
 * </pre>
 *
 * Creates a fresh show, then fires every scenario at the same instant: a
 * hot-seat storm, a hot handful, idempotent retries, idempotency-key reuse,
 * per-user-limit floods, spoofed identities, holds and general buyers. While
 * the burst runs it polls the show to check the invariant and shows the seat
 * counts live; afterwards it prints the outcome distribution, verifies the
 * correctness bar from the client's point of view, and reconciles against the
 * server's state and metrics. Then it walks the holds through their lifecycle:
 * a third confirmed, a third cancelled, a third left to expire (watched with
 * --wait-for-expiry, which waits out the server's hold TTL).
 *
 * Exit code: 0 all checks passed, 1 a check failed, 2 setup failed.
 * Plain JDK 21, no dependencies: runs with `java Burst.java`.
 */
public class Burst {

    // ------------------------------------------------------------------ config

    static String base;
    static String adminKey = unquote(env("ADMIN_KEY", "local-admin-key"));
    // 4 = about 23,500 requests, the brief's ~20,000-request burst. --scale 1 for a quick ~5,900.
    static int scale = 4;
    static int concurrency = 2000;
    static boolean concurrencySet = false;
    /**
     * Windows queues only ~200 not-yet-accepted connections per listening socket and refuses the rest, so
     * a burst from Windows at a server on the same machine would count refused connects as drops even
     * though the service answered everything that reached it (measured: 200 passes, 500 drops ~80).
     */
    static final int WINDOWS_LOCAL_CONCURRENCY = 200;
    static int perUserLimit = 4;
    // Matches the ~100 s after which Cloudflare (in front of Render) gives up on a request anyway; a
    // shorter client timeout would count slow but successful answers from a small instance as dropped.
    static Duration REQUEST_TIMEOUT = Duration.ofSeconds(100);
    static boolean waitForExpiry = false;
    static HttpClient http;
    static final List<String> SERVER_ERRORS = new CopyOnWriteArrayList<>();

    enum Scenario {
        HOT_STORM("hot-seat storm (A1)"),
        HOT_HANDFUL("hot handful (A2-A6)"),
        RETRY("idempotent retries (same key x4)"),
        KEY_REUSE("same key, different seats"),
        LIMIT("per-user limit flood (10 x limit 4)"),
        SPOOF("spoofed user_id in body"),
        HOLD("holds (\"hold\": true)"),
        GENERAL("general buyers");

        final String label;

        Scenario(String label) {
            this.label = label;
        }
    }

    record Req(Scenario scenario, String user, List<String> seats, String key, boolean keyInHeader, String spoofAs) {
    }

    /** {@code state} is the reservation's status on success ("confirmed" or "held"); {@code ttlSeconds} a hold's length. */
    record Res(Req req, int status, String code, String reservationId, String userId, List<String> seats,
               String state, long ttlSeconds, boolean replayed, long micros, String transportError) {

        boolean success() {
            return status == 200 || status == 201;
        }

        boolean newHold() {
            return status == 201 && "held".equals(state);
        }

        String outcome() {
            if (transportError != null) return "transport-error";
            if (status == 201) return newHold() ? "held" : "confirmed";
            if (status == 200) return "idempotent-replay";
            if (status >= 500) return "5xx";
            return code == null ? String.valueOf(status) : code.toLowerCase().replace('_', '-');
        }
    }

    // -------------------------------------------------------------------- main

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                    ? e.getCause() : e;
            fail(cause instanceof java.net.ConnectException
                    ? "cannot connect to " + base + " (is the service running and the URL right?)"
                    : cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    static void run(String[] args) throws Exception {
        parseArgs(args);
        http = HttpClient.newBuilder()
                // HTTP/1.1: one connection per in-flight request, like most load tools. Java's
                // HTTP/2 client multiplexes everything over one connection and fails with "too many
                // concurrent streams" once the edge proxy's per-connection limit (~100) is reached.
                .version(HttpClient.Version.HTTP_1_1)
                // Opening thousands of TLS connections at once can take a while on the client side;
                // a request that never connects is counted as dropped, so give the connect real room.
                .connectTimeout(Duration.ofSeconds(30))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        line("== Seat reservation burst ==");
        Http ready = sendWithRetry("GET", "/readyz", null, null, Map.of());
        if (ready.status != 200) {
            fail("Service not ready: GET /readyz -> " + ready.status + " " + ready.body);
        }
        line("target      %s  (ready)", base);
        if (!concurrencySet && isLocalTarget() && System.getProperty("os.name", "").startsWith("Windows")) {
            concurrency = WINDOWS_LOCAL_CONCURRENCY;
            line("note        Windows refuses connections beyond ~200 waiting at once, so this local run uses"
                    + " --concurrency %d (pass --concurrency to override)", concurrency);
        }

        // Plan the burst ---------------------------------------------------
        List<Req> plan = new ArrayList<>();
        List<String> seats = new ArrayList<>();
        int hotStorm = 1000 * scale, perHandful = 200 * scale, retryUsers = 100 * scale, reuseUsers = 50 * scale;
        int limitUsers = 25 * scale, spoofs = 20 * scale, holders = 100 * scale;
        int general = 3000 * scale, generalSeats = 1500 * scale;

        seats.add("A1");
        for (int i = 0; i < hotStorm; i++) {
            plan.add(new Req(Scenario.HOT_STORM, "storm-" + i, List.of("A1"), key(), false, null));
        }
        for (int s = 2; s <= 6; s++) {
            seats.add("A" + s);
            for (int i = 0; i < perHandful; i++) {
                plan.add(new Req(Scenario.HOT_HANDFUL, "hot" + s + "-" + i, List.of("A" + s), key(), i % 2 == 0, null));
            }
        }
        for (int u = 0; u < retryUsers; u++) {
            seats.add("R" + u);
            String k = key();
            for (int copy = 0; copy < 4; copy++) {
                plan.add(new Req(Scenario.RETRY, "retry-" + u, List.of("R" + u), k, true, null));
            }
        }
        for (int u = 0; u < reuseUsers; u++) {
            seats.add("K" + (2 * u));
            seats.add("K" + (2 * u + 1));
            String k = key();
            plan.add(new Req(Scenario.KEY_REUSE, "reuse-" + u, List.of("K" + (2 * u)), k, false, null));
            plan.add(new Req(Scenario.KEY_REUSE, "reuse-" + u, List.of("K" + (2 * u + 1)), k, false, null));
        }
        for (int u = 0; u < limitUsers; u++) {
            for (int i = 0; i < 10; i++) {
                String seat = "L" + (u * 10 + i);
                seats.add(seat);
                plan.add(new Req(Scenario.LIMIT, "greedy-" + u, List.of(seat), key(), false, null));
            }
        }
        for (int i = 0; i < spoofs; i++) {
            seats.add("S" + i);
            plan.add(new Req(Scenario.SPOOF, "spoofer-" + i, List.of("S" + i), key(), false, "victim"));
        }
        // Holds: one seat each, uncontended, so every one must come back 201 "held". After the
        // burst they are walked through their lifecycle (confirm / cancel / expire).
        for (int i = 0; i < holders; i++) {
            seats.add("H" + i);
            plan.add(new Req(Scenario.HOLD, "holder-" + i, List.of("H" + i), key(), false, null));
        }
        for (int i = 0; i < generalSeats; i++) {
            seats.add("G" + i);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < general; i++) {
            int a = rnd.nextInt(generalSeats);
            List<String> want = rnd.nextInt(3) == 0
                    ? List.of("G" + a, "G" + ((a + 1) % generalSeats))
                    : List.of("G" + a);
            plan.add(new Req(Scenario.GENERAL, "buyer-" + i, want, key(), rnd.nextBoolean(), null));
        }
        Collections.shuffle(plan);

        // Create the show and mint tokens ----------------------------------
        String adminToken = token("burst-admin", true);
        Http created = sendWithRetry("POST", "/shows", adminToken, json(Map.of(
                "name", "burst-" + System.currentTimeMillis(),
                "seats", seats,
                "price_paise", 25000,
                "per_user_limit", perUserLimit)), Map.of());
        if (created.status != 201) {
            fail("Could not create show: " + created.status + " " + created.body);
        }
        String showId = str(created.body, "id");
        line("show        %s  (%d seats, per_user_limit %d)", showId, seats.size(), perUserLimit);

        Set<String> users = new HashSet<>();
        plan.forEach(r -> users.add(r.user()));
        long mintStart = System.nanoTime();
        Map<String, String> bulk = mintTokensInBulk(users);
        Map<String, String> tokens = bulk != null ? bulk : mintTokens(users);
        line("tokens      %d users minted in %.1fs", tokens.size(), (System.nanoTime() - mintStart) / 1e9);

        Map<String, Double> metricsBefore = scrape();

        // Fire --------------------------------------------------------------
        AtomicInteger answered = new AtomicInteger();
        line("firing      %d requests at once (up to %d concurrently)...", plan.size(), concurrency);
        Live live = new Live(showId, 250, () -> String.format("responses %,d/%,d", answered.get(), plan.size()));
        Semaphore inFlight = new Semaphore(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>(plan.size());
        long started;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Req r : plan) {
                futures.add(pool.submit(() -> {
                    go.await();
                    inFlight.acquire();
                    try {
                        return reserve(showId, r, tokens.get(r.user()));
                    } finally {
                        inFlight.release();
                        answered.incrementAndGet();
                    }
                }));
            }
            started = System.nanoTime();
            go.countDown();
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        List<Res> results = new ArrayList<>(plan.size());
        for (Future<Res> f : futures) {
            results.add(f.get());
        }
        live.stop();

        // Let the seat gauges refresh, then read the server's view -----------
        Thread.sleep(3000);
        Http finalShow = send("GET", "/shows/" + showId, null, null, Map.of());
        Map<String, Double> metricsAfter = scrape();

        List<String[]> checks = report(showId, seats.size(), results, seconds, finalShow.body, live.violations,
                live.polls.get(), metricsBefore, metricsAfter);

        // Holds: confirm / cancel / expire, after the reconciliation snapshot so it stays exact.
        line("");
        line("Hold lifecycle");
        checks.addAll(holdLifecycle(showId, results, tokens, finalShow.body));

        printChecks(checks);
    }

    // ------------------------------------------------------------ live view

    static final boolean TTY = isTerminal();

    /**
     * Polls the show while something runs. Every poll checks the invariant, and the counts
     * are shown as one line updated in place (a line every ~5 s when output isn't a terminal).
     * The three counts in a line come from one response, which the server computes from a
     * single read of the seat rows: they are one moment's numbers and always sum to the total.
     */
    static final class Live {
        final List<String> violations = new CopyOnWriteArrayList<>();
        final AtomicInteger polls = new AtomicInteger();
        volatile long available = -1, held = -1, confirmed = -1;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        Live(String showId, long intervalMillis, java.util.function.Supplier<String> progress) {
            thread = Thread.ofVirtual().start(() -> {
                long lastLine = 0;
                while (running.get()) {
                    try {
                        Http s = send("GET", "/shows/" + showId, null, null, Map.of());
                        if (s.status == 200) {
                            polls.incrementAndGet();
                            long a = num(s.body, "available"), h = num(s.body, "held"), c = num(s.body, "confirmed"),
                                    t = num(s.body, "total");
                            if (a + h + c != t) {
                                violations.add(a + "+" + h + "+" + c + "!=" + t);
                            }
                            available = a;
                            held = h;
                            confirmed = c;
                            String text = String.format("  live       available %,d + held %,d + confirmed %,d = %,d %s   %s",
                                    a, h, c, a + h + c, a + h + c == t ? "ok" : "!= " + t, progress.get());
                            if (TTY) {
                                System.out.print("\r" + String.format("%-110s", text));
                                System.out.flush();
                            } else if (System.nanoTime() - lastLine > 5_000_000_000L) {
                                line(text);
                                lastLine = System.nanoTime();
                            }
                        }
                        Thread.sleep(intervalMillis);
                    } catch (Exception ignored) {
                        // a failed poll is not an invariant violation
                    }
                }
            });
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.join();
            if (TTY && polls.get() > 0) {
                System.out.println();
            }
        }
    }

    static boolean isTerminal() {
        java.io.Console c = System.console();
        if (c == null) {
            return false;
        }
        try {
            // Java 22+ returns a Console even when output is redirected; ask it.
            return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(c);
        } catch (ReflectiveOperationException e) {
            return true;   // Java 21: a non-null console is a terminal
        }
    }

    // ---------------------------------------------------------- hold lifecycle

    /**
     * Walks the burst's holds through their lifecycle, all at once: a third confirmed, a third
     * cancelled, a third left to expire. With --wait-for-expiry it waits out the server's hold
     * TTL and checks the sweeper freed them. Every step is checked against the show's counts
     * and the metrics.
     */
    static List<String[]> holdLifecycle(String showId, List<Res> results, Map<String, String> tokens, String showAfterBurst)
            throws Exception {
        List<String[]> checks = new ArrayList<>();
        List<Res> requested = results.stream().filter(r -> r.req().scenario() == Scenario.HOLD).toList();
        List<Res> held = requested.stream().filter(Res::newHold).toList();
        long ttl = held.isEmpty() ? -1 : held.getFirst().ttlSeconds();
        check(checks, held.size() == requested.size() && ttl > 0,
                "\"hold\": true -> 201 held with a deadline (" + held.size() + "/" + requested.size() + ")",
                requested.size() - held.size() + " hold requests were not 201 held");
        line(String.format("  placed     %d holds during the burst (server hold TTL %ds); show said held %d",
                held.size(), ttl, num(showAfterBurst, "held")));
        if (held.isEmpty()) {
            return checks;
        }

        int third = held.size() / 3;
        List<Res> toConfirm = held.subList(0, third);
        List<Res> toCancel = held.subList(third, 2 * third);
        List<Res> toExpire = held.subList(2 * third, held.size());

        // Confirm and cancel fire together; the show is polled live the whole time.
        Map<String, Double> before = scrape();
        Http s0 = send("GET", "/shows/" + showId, null, null, Map.of());
        AtomicInteger done = new AtomicInteger();
        int total = toConfirm.size() + toCancel.size();
        Live live = new Live(showId, 250, () -> String.format("confirm/cancel %d/%d", done.get(), total));
        List<Future<Http>> confirms = new ArrayList<>(), cancels = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Res r : toConfirm) confirms.add(pool.submit(() -> transition(r, "confirm", tokens, done)));
            for (Res r : toCancel) cancels.add(pool.submit(() -> transition(r, "cancel", tokens, done)));
        }
        live.stop();
        Http s1 = send("GET", "/shows/" + showId, null, null, Map.of());

        long confirmedOk = countOk(confirms, "confirmed"), cancelledOk = countOk(cancels, "cancelled");
        line(String.format("  confirm    %d of %d -> 200 confirmed      cancel %d of %d -> 200 cancelled   (fired at once)",
                confirmedOk, toConfirm.size(), cancelledOk, toCancel.size()));
        long dHeld = num(s1.body, "held") - num(s0.body, "held");
        long dConfirmed = num(s1.body, "confirmed") - num(s0.body, "confirmed");
        long dAvailable = num(s1.body, "available") - num(s0.body, "available");
        line(String.format("  show       held %+d   confirmed %+d   available %+d   -> held %d",
                dHeld, dConfirmed, dAvailable, num(s1.body, "held")));
        check(checks, confirmedOk == toConfirm.size() && cancelledOk == toCancel.size(),
                "confirm -> 200 confirmed, cancel -> 200 cancelled (" + toConfirm.size() + " + " + toCancel.size() + " at once)",
                confirmedOk + "/" + toConfirm.size() + " confirmed, " + cancelledOk + "/" + toCancel.size() + " cancelled");
        check(checks, dHeld == -total && dConfirmed == toConfirm.size() && dAvailable == toCancel.size()
                        && live.violations.isEmpty(),
                "show follows: held -" + total + ", confirmed +" + toConfirm.size() + ", available +" + toCancel.size()
                        + "; invariant on every poll (" + live.polls.get() + ")",
                "held " + dHeld + ", confirmed " + dConfirmed + ", available " + dAvailable + " " + live.violations);

        if (!waitForExpiry) {
            line(String.format("  expiry     %d holds left to expire on their own ~%ds after the burst"
                    + " (--wait-for-expiry watches it)", toExpire.size(), ttl));
            Map<String, Double> after = scrape();
            metricCheck(checks, before, after, toConfirm.size(), toCancel.size(), 0);
            return checks;
        }

        // Wait for the sweeper: the holds were placed during the burst, so ttl from now is an upper
        // bound on their deadline; allow a minute on top for the sweeper and a slow poll.
        long deadline = System.nanoTime() + (ttl + 60) * 1_000_000_000L;
        long waitStart = System.nanoTime();
        line("waiting     for %d unconfirmed holds to expire (TTL %ds)...", toExpire.size(), ttl);
        Live wait = new Live(showId, 1000, () -> String.format("waited %ds", (System.nanoTime() - waitStart) / 1_000_000_000L));
        while (System.nanoTime() < deadline && wait.held != 0) {
            Thread.sleep(500);
        }
        wait.stop();
        long waited = (System.nanoTime() - waitStart) / 1_000_000_000L;
        Http s2 = send("GET", "/shows/" + showId, null, null, Map.of());
        long freed = num(s2.body, "available") - num(s1.body, "available");
        line(String.format("  expiry     held %d -> %d after %ds; available %+d", num(s1.body, "held"),
                num(s2.body, "held"), waited, freed));
        check(checks, num(s2.body, "held") == 0 && freed == toExpire.size() && wait.violations.isEmpty(),
                "unconfirmed holds expire on their own: held -> 0, seats available again (" + toExpire.size()
                        + "); invariant on every poll (" + wait.polls.get() + ")",
                "held " + num(s2.body, "held") + ", available +" + freed + " " + wait.violations);

        // An expired hold can't be confirmed any more.
        List<Future<Http>> late = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Res r : toExpire) late.add(pool.submit(() -> transition(r, "confirm", tokens, new AtomicInteger())));
        }
        long refused = 0;
        for (Future<Http> f : late) {
            Http h = f.get();
            if (h.status == 409 && "HOLD_EXPIRED".equals(str(h.body, "code"))) refused++;
        }
        line(String.format("  late       confirm of an expired hold -> 409 HOLD_EXPIRED: %d of %d", refused, toExpire.size()));
        check(checks, refused == toExpire.size(), "confirming an expired hold -> 409 HOLD_EXPIRED (" + toExpire.size() + ")",
                refused + "/" + toExpire.size() + " refused");
        Map<String, Double> after = scrape();
        metricCheck(checks, before, after, toConfirm.size(), toCancel.size(), toExpire.size());
        return checks;
    }

    static Http transition(Res r, String action, Map<String, String> tokens, AtomicInteger done) {
        try {
            return send("POST", "/reservations/" + r.reservationId() + "/" + action, tokens.get(r.req().user()), null, Map.of());
        } catch (Exception e) {
            return new Http(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), false, 0);
        } finally {
            done.incrementAndGet();
        }
    }

    static long countOk(List<Future<Http>> fs, String state) throws Exception {
        long ok = 0;
        for (Future<Http> f : fs) {
            Http h = f.get();
            if (h.status == 200 && state.equals(str(h.body, "status"))) ok++;
        }
        return ok;
    }

    static void metricCheck(List<String[]> checks, Map<String, Double> before, Map<String, Double> after,
                            long confirmed, long cancelled, long expired) {
        if (before.isEmpty() || after.isEmpty()) {
            return;
        }
        long dc = delta(before, after, "confirmed"), dx = delta(before, after, "cancelled"), de = delta(before, after, "expired");
        check(checks, dc == confirmed && dx == cancelled && de == expired,
                "hold metrics reconcile (confirmed +" + confirmed + ", cancelled +" + cancelled + ", expired +" + expired + ")",
                "confirmed +" + dc + ", cancelled +" + dx + ", expired +" + de
                        + " (other traffic or several instances behind the LB also move these)");
    }

    static long delta(Map<String, Double> before, Map<String, Double> after, String key) {
        return Math.round(after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0));
    }

    // ------------------------------------------------------------------ report

    /** Prints the burst's report and returns its checks (printed at the very end, after the hold lifecycle). */
    static List<String[]> report(String showId, int totalSeats, List<Res> results, double seconds, String showBody,
                                 List<String> invariantViolations, int polls,
                                 Map<String, Double> before, Map<String, Double> after) {
        List<Long> latencies = results.stream().filter(r -> r.transportError() == null)
                .map(Res::micros).sorted().toList();
        line("");
        line("done        %d requests in %.2fs  ->  %.0f req/s", results.size(), seconds, results.size() / seconds);
        if (!latencies.isEmpty()) {
            line("latency     p50 %s  p95 %s  p99 %s  max %s", ms(pct(latencies, 50)), ms(pct(latencies, 95)),
                    ms(pct(latencies, 99)), ms(latencies.getLast()));
        }

        // Outcome distribution
        Map<String, Integer> outcomes = new TreeMap<>();
        results.forEach(r -> outcomes.merge(r.outcome(), 1, Integer::sum));
        int fiveXX = outcomes.getOrDefault("5xx", 0);
        int transport = outcomes.getOrDefault("transport-error", 0);
        line("");
        line("Outcomes");
        outcomes.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .forEach(e -> line("  %-28s %7d", e.getKey(), e.getValue()));
        if (!outcomes.containsKey("5xx")) line("  %-28s %7d", "5xx", 0);
        // Which 5xx exactly, and who sent it (our app's error JSON, or the platform's edge page)?
        Map<Integer, Integer> byStatus = new TreeMap<>();
        results.stream().filter(r -> r.status() >= 500).forEach(r -> byStatus.merge(r.status(), 1, Integer::sum));
        byStatus.forEach((status, n) -> line("    5xx status %d: %d", status, n));
        SERVER_ERRORS.forEach(e -> line("    5xx example: %s", e));

        line("");
        line("By scenario");
        for (Scenario s : Scenario.values()) {
            Map<String, Integer> byOutcome = new TreeMap<>();
            results.stream().filter(r -> r.req().scenario() == s)
                    .forEach(r -> byOutcome.merge(r.outcome(), 1, Integer::sum));
            line("  %-36s %s", s.label, byOutcome);
        }
        results.stream().filter(r -> r.transportError() != null).limit(3)
                .forEach(r -> line("  transport error example: %s", r.transportError()));

        scorecard(results);

        // Client-side correctness checks
        List<String[]> checks = new ArrayList<>();
        Map<String, Set<String>> reservationsBySeat = new HashMap<>();
        Map<String, Set<String>> reservationsByUser = new HashMap<>();
        Map<String, Set<String>> seatsByReservation = new HashMap<>();
        for (Res r : results) {
            if (r.success() && r.reservationId() != null) {
                for (String seat : r.seats()) {
                    reservationsBySeat.computeIfAbsent(seat, k -> new HashSet<>()).add(r.reservationId());
                }
                reservationsByUser.computeIfAbsent(r.req().user(), k -> new HashSet<>()).add(r.reservationId());
                seatsByReservation.put(r.reservationId(), new HashSet<>(r.seats()));
            }
        }

        List<String> badHot = new ArrayList<>();
        for (int s = 1; s <= 6; s++) {
            String seat = "A" + s;
            long winners = results.stream().filter(r -> r.req().seats().contains(seat) && r.status() == 201).count();
            long cleanLosers = results.stream().filter(r -> r.req().seats().contains(seat) && r.status() == 409
                    && "SEAT_TAKEN".equals(r.code())).count();
            long total = results.stream().filter(r -> r.req().seats().contains(seat)).count();
            if (winners != 1 || winners + cleanLosers != total) {
                badHot.add(seat + ": " + winners + " winners, " + cleanLosers + "/" + (total - winners) + " clean 409s");
            }
        }
        check(checks, badHot.isEmpty(), "exactly one 201 per hot seat, every other request a clean 409 (A1-A6)",
                String.join("; ", badHot));

        List<String> doubleSold = reservationsBySeat.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey).toList();
        check(checks, doubleSold.isEmpty(), "no seat confirmed to two reservations (" + reservationsBySeat.size()
                + " seats sold)", "double-sold: " + doubleSold);

        check(checks, fiveXX == 0, "zero 5xx across the burst", fiveXX + " responses were 5xx");
        check(checks, transport == 0, "no dropped requests (timeouts / connection errors)",
                transport + " requests failed in transport");

        List<String> badRetry = new ArrayList<>();
        Map<String, List<Res>> byKey = new HashMap<>();
        results.stream().filter(r -> r.req().scenario() == Scenario.RETRY)
                .forEach(r -> byKey.computeIfAbsent(r.req().key(), k -> new ArrayList<>()).add(r));
        byKey.forEach((k, rs) -> {
            long created = rs.stream().filter(r -> r.status() == 201).count();
            long replays = rs.stream().filter(r -> r.status() == 200).count();
            long ids = rs.stream().map(Res::reservationId).filter(java.util.Objects::nonNull).distinct().count();
            if (created != 1 || replays != rs.size() - 1 || ids != 1) {
                badRetry.add(rs.getFirst().req().user() + ": " + created + "x201 " + replays + "x200 " + ids + " ids");
            }
        });
        check(checks, badRetry.isEmpty(), "same key retried 4x at once: one 201 + three 200 replays, one reservation ("
                + byKey.size() + " keys)", String.join("; ", badRetry.subList(0, Math.min(3, badRetry.size()))));

        List<String> badReuse = new ArrayList<>();
        Map<String, List<Res>> byReuser = new HashMap<>();
        results.stream().filter(r -> r.req().scenario() == Scenario.KEY_REUSE)
                .forEach(r -> byReuser.computeIfAbsent(r.req().user(), k -> new ArrayList<>()).add(r));
        byReuser.forEach((u, rs) -> {
            long created = rs.stream().filter(r -> r.status() == 201).count();
            long reused = rs.stream().filter(r -> r.status() == 409 && "IDEMPOTENCY_KEY_REUSED".equals(r.code())).count();
            if (created != 1 || reused != 1) {
                badReuse.add(u + ": " + created + "x201 " + reused + "x409");
            }
        });
        check(checks, badReuse.isEmpty(), "same key + different seats -> exactly one 201 and one 409 ("
                + byReuser.size() + " users)", String.join("; ", badReuse.subList(0, Math.min(3, badReuse.size()))));

        int maxHeld = reservationsByUser.entrySet().stream()
                .mapToInt(e -> e.getValue().stream().mapToInt(id -> seatsByReservation.get(id).size()).sum())
                .max().orElse(0);
        long greedyAtLimit = reservationsByUser.entrySet().stream().filter(e -> e.getKey().startsWith("greedy-"))
                .filter(e -> e.getValue().size() == perUserLimit).count();
        check(checks, maxHeld <= perUserLimit, "per-user limit holds under parallel requests (max held "
                + maxHeld + "/" + perUserLimit + "; " + greedyAtLimit + " flooders capped at exactly " + perUserLimit + ")",
                "a user holds " + maxHeld + " seats");

        List<String> spoofed = results.stream().filter(r -> r.success() && r.userId() != null
                        && !r.userId().equals(r.req().user()))
                .map(r -> r.req().user() + " acted as " + r.userId()).toList();
        check(checks, spoofed.isEmpty(), "identity comes from the token, never the body (spoofed user_id ignored)",
                String.join("; ", spoofed));

        // Reconciliation with the server
        long available = num(showBody, "available"), held = num(showBody, "held"), confirmed = num(showBody, "confirmed"),
                total = num(showBody, "total");
        int observedSeats = seatsByReservation.values().stream().mapToInt(Set::size).sum();
        line("");
        line("Reconciliation");
        line("  server     available %d + held %d + confirmed %d = %d   (total_seats %d)",
                available, held, confirmed, available + held + confirmed, totalSeats);
        line("  observed   %d seats in %d successful reservations seen by this client", observedSeats,
                seatsByReservation.size());
        check(checks, available + held + confirmed == total && total == totalSeats,
                "invariant after the burst: available + held + confirmed == total_seats", "");
        check(checks, invariantViolations.isEmpty() && polls > 0, "invariant during the burst (" + polls
                + " polls while firing)", invariantViolations.toString());
        check(checks, confirmed + held == observedSeats,
                "server's taken seats == seats the client was told it got (" + (confirmed + held) + " == "
                        + observedSeats + ")",
                transport > 0 ? "transport errors may hide successful responses" : "");

        if (!before.isEmpty() && !after.isEmpty()) {
            double dConfirmed = after.getOrDefault("confirmed", 0.0) - before.getOrDefault("confirmed", 0.0);
            long dHeld = delta(before, after, "held");
            long seenConfirmed = results.stream().filter(r -> r.status() == 201 && !r.newHold()).count();
            long seenHeld = results.stream().filter(Res::newHold).count();
            line("  metrics    reservations_confirmed_total +%.0f   reservations_held_total +%d   declined: %s",
                    dConfirmed, dHeld, declineDelta(before, after));
            Map<String, Long> seenDeclines = new TreeMap<>();
            results.stream().filter(r -> r.status() == 200).forEach(r -> seenDeclines.merge("idempotent-replay", 1L, Long::sum));
            results.stream().filter(r -> r.status() == 409 && r.code() != null)
                    .forEach(r -> seenDeclines.merge(r.code().toLowerCase().replace('_', '-'), 1L, Long::sum));
            List<String> metricMismatches = new ArrayList<>();
            if (Math.round(dConfirmed) != seenConfirmed) {
                metricMismatches.add("confirmed +" + Math.round(dConfirmed) + " vs " + seenConfirmed + " seen");
            }
            if (dHeld != seenHeld) {
                metricMismatches.add("held +" + dHeld + " vs " + seenHeld + " seen");
            }
            seenDeclines.forEach((reason, n) -> {
                long d = Math.round(after.getOrDefault("declined:" + reason, 0.0) - before.getOrDefault("declined:" + reason, 0.0));
                if (d != n) metricMismatches.add(reason + " +" + d + " vs " + n + " seen");
            });
            check(checks, metricMismatches.isEmpty(),
                    "metrics reconcile with responses (confirmed, held and every decline reason)",
                    String.join("; ", metricMismatches) + " (other traffic or several instances behind the LB also move these)");
        }

        return checks;
    }

    static void printChecks(List<String[]> checks) {
        line("");
        line("Checks");
        boolean allPass = true;
        for (String[] c : checks) {
            line("  %s  %s%s", c[0], c[1], c[0].equals("PASS") || c[2].isBlank() ? "" : "\n          -> " + c[2]);
            allPass &= c[0].equals("PASS");
        }
        line("");
        line("RESULT: %s", allPass ? "PASS" : "FAIL");
        System.exit(allPass ? 0 : 1);
    }

    /** The headline numbers per storm, in the shape a reviewer scans first. */
    static void scorecard(List<Res> results) {
        line("");
        line("Scorecard");
        card(results, "HOT SEAT (A1)", r -> r.req().scenario() == Scenario.HOT_STORM,
                "Confirmed", r -> r.status() == 201, "Seat taken", r -> "SEAT_TAKEN".equals(r.code()));
        card(results, "HOT HANDFUL (A2-A6)", r -> r.req().scenario() == Scenario.HOT_HANDFUL,
                "Confirmed", r -> r.status() == 201, "Seat taken", r -> "SEAT_TAKEN".equals(r.code()));
        card(results, "USER LIMIT (limit " + perUserLimit + ")", r -> r.req().scenario() == Scenario.LIMIT,
                "Confirmed", r -> r.status() == 201, "Limit exceeded", r -> "PER_USER_LIMIT".equals(r.code()));
        card(results, "IDEMPOTENCY (same key x4)", r -> r.req().scenario() == Scenario.RETRY,
                "Created", r -> r.status() == 201, "Replayed", r -> r.status() == 200);
        card(results, "SAME KEY, DIFFERENT BODY", r -> r.req().scenario() == Scenario.KEY_REUSE,
                "Created", r -> r.status() == 201, "Key reused", r -> "IDEMPOTENCY_KEY_REUSED".equals(r.code()));
        card(results, "HOLDS (\"hold\": true)", r -> r.req().scenario() == Scenario.HOLD,
                "Held", Res::newHold, "Seat taken", r -> "SEAT_TAKEN".equals(r.code()));
        card(results, "GENERAL BUYERS", r -> r.req().scenario() == Scenario.GENERAL,
                "Confirmed", r -> r.status() == 201, "Seat taken", r -> "SEAT_TAKEN".equals(r.code()));
    }

    static void card(List<Res> results, String title, java.util.function.Predicate<Res> in,
                     String goodLabel, java.util.function.Predicate<Res> good,
                     String declineLabel, java.util.function.Predicate<Res> declined) {
        List<Res> rs = results.stream().filter(in).toList();
        long other = rs.stream().filter(good.negate().and(declined.negate()))
                .filter(r -> r.status() < 500 && r.transportError() == null).count();
        line("  %-28s requests %6d   %s %6d   %s %6d   other 4xx %d   5xx %d   dropped %d", title, rs.size(),
                goodLabel, rs.stream().filter(good).count(), declineLabel, rs.stream().filter(declined).count(),
                other, rs.stream().filter(r -> r.status() >= 500).count(),
                rs.stream().filter(r -> r.transportError() != null).count());
    }

    static void check(List<String[]> checks, boolean ok, String what, String detail) {
        checks.add(new String[] {ok ? "PASS" : "FAIL", what, detail == null ? "" : detail});
    }

    static String declineDelta(Map<String, Double> before, Map<String, Double> after) {
        Map<String, Long> delta = new TreeMap<>();
        after.forEach((k, v) -> {
            if (k.startsWith("declined:")) {
                long d = Math.round(v - before.getOrDefault(k, 0.0));
                if (d > 0) delta.put(k.substring("declined:".length()), d);
            }
        });
        after.forEach((k, v) -> {
            if (k.startsWith("source:cache")) {
                long d = Math.round(v - before.getOrDefault(k, 0.0));
                if (d > 0) delta.put("(of which answered from cache)", d);
            }
        });
        return delta.toString();
    }

    // -------------------------------------------------------------------- HTTP

    record Http(int status, String body, boolean replayed, long micros) {
    }

    static Res reserve(String showId, Req r, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seats", r.seats());
        if (!r.keyInHeader()) body.put("idempotency_key", r.key());
        if (r.spoofAs() != null) body.put("user_id", r.spoofAs());
        if (r.scenario() == Scenario.HOLD) body.put("hold", true);
        Map<String, String> headers = r.keyInHeader() ? Map.of("Idempotency-Key", r.key()) : Map.of();
        try {
            Http h = send("POST", "/shows/" + showId + "/reserve", token, json(body), headers);
            if (h.status >= 500 && SERVER_ERRORS.size() < 3) {
                String snippet = h.body == null ? "" : h.body.replaceAll("\s+", " ");
                SERVER_ERRORS.add("HTTP " + h.status + " " + snippet.substring(0, Math.min(200, snippet.length())));
            }
            String code = h.status >= 400 ? str(h.body, "code") : null;
            boolean ok = h.status < 300;
            return new Res(r, h.status, code, ok ? str(h.body, "reservation_id") : null,
                    ok ? str(h.body, "user_id") : null, ok ? seats(h.body) : List.of(),
                    ok ? str(h.body, "status") : null, ok ? holdSeconds(h.body) : -1, h.replayed, h.micros, null);
        } catch (Exception e) {
            return new Res(r, -1, null, null, null, List.of(), null, -1, false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** A hold's length (expires_at - created_at, both from the server's clock), or -1 if it isn't a hold. */
    static long holdSeconds(String body) {
        String expires = str(body, "expires_at"), created = str(body, "created_at");
        if (expires == null || created == null) return -1;
        try {
            return Math.round(Duration.between(java.time.Instant.parse(created), java.time.Instant.parse(expires)).toMillis() / 1000.0);
        } catch (Exception e) {
            return -1;
        }
    }

    static Http send(String method, String path, String token, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "burst-" + UUID.randomUUID());
        if (token != null) b.header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        long t0 = System.nanoTime();
        // HttpClient's timeout only covers waiting for response headers; a connection that dies
        // mid-response (e.g. the server restarting) could otherwise hang the burst forever.
        // An absolute deadline guarantees every request ends and the report is printed.
        HttpResponse<String> res;
        try {
            res = http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .orTimeout(REQUEST_TIMEOUT.toSeconds() + 30, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new java.net.http.HttpTimeoutException("no complete response within "
                        + (REQUEST_TIMEOUT.toSeconds() + 30) + "s");
            }
            if (cause instanceof java.io.IOException io) {
                throw io;
            }
            throw e;
        }
        long micros = (System.nanoTime() - t0) / 1000;
        boolean replayed = res.headers().firstValue("Idempotent-Replayed").map("true"::equals).orElse(false);
        return new Http(res.statusCode(), res.body(), replayed, micros);
    }

    static String token(String user, boolean admin) throws Exception {
        Http h = sendWithRetry("POST", "/auth/token", null, json(Map.of("user_id", user)),
                admin ? Map.of("X-Admin-Key", adminKey) : Map.of());
        if (h.status != 200) {
            fail("Could not get a " + (admin ? "admin " : "") + "token: " + h.status + " " + h.body
                    + (admin ? "  (set ADMIN_KEY or pass --admin-key; the live service needs its own key, a local"
                            + " run uses local-admin-key)" : ""));
        }
        return str(h.body, "access_token");
    }

    /**
     * Setup calls (tokens, show creation) retry transient connection failures:
     * a refused connect while minting thousands of tokens is a client-side
     * hiccup, not something the burst should crash on.
     */
    static Http sendWithRetry(String method, String path, String token, String body, Map<String, String> headers)
            throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return send(method, path, token, body, headers);
            } catch (java.io.IOException e) {
                if (attempt == 5) throw e;
                Thread.sleep(100L * attempt);
            }
        }
    }

    /**
     * One call to POST /auth/tokens (admin) instead of one call per user; null
     * if the service doesn't offer it, so the caller falls back to minting one by one.
     */
    static Map<String, String> mintTokensInBulk(Set<String> users) throws Exception {
        Map<String, String> tokens = new HashMap<>();
        List<String> all = new ArrayList<>(users);
        for (int from = 0; from < all.size(); from += 20_000) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + 20_000));
            Http h = sendWithRetry("POST", "/auth/tokens", null, json(Map.of("user_ids", batch)),
                    Map.of("X-Admin-Key", adminKey));
            if (h.status != 200) {
                return null;
            }
            Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"(eyJ[^\"]+)\"").matcher(h.body);
            while (m.find()) {
                tokens.put(m.group(1), m.group(2));
            }
        }
        return tokens.keySet().containsAll(users) ? tokens : null;
    }

    static Map<String, String> mintTokens(Set<String> users) throws Exception {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        Semaphore limit = new Semaphore(64);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (String u : users) {
                fs.add(pool.submit(() -> {
                    limit.acquire();
                    try {
                        tokens.put(u, token(u, false));
                    } finally {
                        limit.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        return tokens;
    }

    /** Business counters from /actuator/prometheus, summed across label sets. Empty if not reachable. */
    static Map<String, Double> scrape() {
        Map<String, Double> m = new HashMap<>();
        try {
            Http h = send("GET", "/actuator/prometheus", null, null, Map.of());
            if (h.status != 200) return m;
            Pattern reason = Pattern.compile("reason=\"([^\"]+)\"");
            for (String l : h.body.split("\n")) {
                if (l.startsWith("#")) continue;
                double v;
                try {
                    v = Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1));
                } catch (Exception e) {
                    continue;
                }
                if (l.startsWith("reservations_confirmed_total")) m.merge("confirmed", v, Double::sum);
                if (l.startsWith("reservations_held_total")) m.merge("held", v, Double::sum);
                if (l.startsWith("reservations_cancelled_total")) m.merge("cancelled", v, Double::sum);
                if (l.startsWith("holds_expired_total")) m.merge("expired", v, Double::sum);
                if (l.startsWith("reservations_declined_total")) {
                    Matcher rm = reason.matcher(l);
                    if (rm.find()) m.merge("declined:" + rm.group(1), v, Double::sum);
                    if (l.contains("source=\"cache\"")) m.merge("source:cache", v, Double::sum);
                }
            }
        } catch (Exception ignored) {
            // metrics are a bonus check; the burst still reports without them
        }
        return m;
    }

    // ------------------------------------------------------------- tiny JSON

    static String json(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(k).append("\":").append(value(v));
        });
        return sb.append('}').toString();
    }

    static String value(Object v) {
        if (v instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : list) {
                if (sb.length() > 1) sb.append(',');
                sb.append(value(o));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    static String str(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long num(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static List<String> seats(String json) {
        Matcher m = Pattern.compile("\"seats\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        if (!m.find() || m.group(1).isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : m.group(1).split(",")) out.add(s.trim().replace("\"", ""));
        return out;
    }

    // ------------------------------------------------------------------- misc

    static void parseArgs(String[] args) {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.println("usage: java burst/Burst.java BASE_URL [--scale N] [--concurrency N] [--timeout SECONDS]"
                    + " [--admin-key KEY] [--wait-for-expiry]");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--scale" -> scale = Integer.parseInt(args[++i]);
                case "--concurrency" -> {
                    concurrency = Integer.parseInt(args[++i]);
                    concurrencySet = true;
                }
                case "--admin-key" -> adminKey = unquote(args[++i]);
                case "--timeout" -> REQUEST_TIMEOUT = Duration.ofSeconds(Long.parseLong(args[++i]));
                case "--wait-for-expiry" -> waitForExpiry = true;
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(2);
                }
            }
        }
    }

    /**
     * Drops one pair of surrounding quotes. Windows cmd doesn't treat '…' as quoting, so
     * --admin-key 'abc' arrives with the quotes as part of the key and the server answers 403.
     */
    static String unquote(String value) {
        if (value != null && value.length() >= 2) {
            char first = value.charAt(0), last = value.charAt(value.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    static boolean isLocalTarget() {
        String host = URI.create(base).getHost();
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    static String ms(long micros) {
        return micros >= 10_000 ? (micros / 1000) + "ms" : String.format("%.1fms", micros / 1000.0);
    }

    static void line(String fmt, Object... args) {
        System.out.println(args.length == 0 ? fmt : String.format(fmt, args));
    }

    static void fail(String message) {
        System.err.println("SETUP FAILED: " + message);
        System.exit(2);
    }
}
