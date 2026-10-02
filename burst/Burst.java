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
 *   java burst/Burst.java BASE_URL [--scale N] [--concurrency N] [--admin-key KEY]
 * </pre>
 *
 * Creates a fresh show, then fires every scenario at the same instant: a
 * hot-seat storm, a hot handful, idempotent retries, idempotency-key reuse,
 * per-user-limit floods, spoofed identities and general buyers. While the
 * burst runs it polls the show to check the invariant; afterwards it prints
 * the outcome distribution, verifies the correctness bar from the client's
 * point of view, and reconciles against the server's state and metrics.
 *
 * Exit code: 0 all checks passed, 1 a check failed, 2 setup failed.
 * Plain JDK 21, no dependencies: runs with `java Burst.java`.
 */
public class Burst {

    // ------------------------------------------------------------------ config

    static String base;
    static String adminKey = env("ADMIN_KEY", "local-admin-key");
    static int scale = 1;
    static int concurrency = 2000;
    static int perUserLimit = 4;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static HttpClient http;

    enum Scenario {
        HOT_STORM("hot-seat storm (A1)"),
        HOT_HANDFUL("hot handful (A2-A6)"),
        RETRY("idempotent retries (same key x4)"),
        KEY_REUSE("same key, different seats"),
        LIMIT("per-user limit flood (10 x limit 4)"),
        SPOOF("spoofed user_id in body"),
        GENERAL("general buyers");

        final String label;

        Scenario(String label) {
            this.label = label;
        }
    }

    record Req(Scenario scenario, String user, List<String> seats, String key, boolean keyInHeader, String spoofAs) {
    }

    record Res(Req req, int status, String code, String reservationId, String userId, List<String> seats,
               boolean replayed, long micros, String transportError) {

        boolean success() {
            return status == 200 || status == 201;
        }

        String outcome() {
            if (transportError != null) return "transport-error";
            if (status == 201) return "confirmed";
            if (status == 200) return "idempotent-replay";
            if (status >= 500) return "5xx";
            return code == null ? String.valueOf(status) : code.toLowerCase().replace('_', '-');
        }
    }

    // -------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)   // multiplexed over TLS; falls back to 1.1 on plain http
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        line("== Seat reservation burst ==");
        Http ready = send("GET", "/readyz", null, null, Map.of());
        if (ready.status != 200) {
            fail("Service not ready: GET /readyz -> " + ready.status + " " + ready.body);
        }
        line("target      %s  (ready)", base);

        // Plan the burst ---------------------------------------------------
        List<Req> plan = new ArrayList<>();
        List<String> seats = new ArrayList<>();
        int hotStorm = 1000 * scale, perHandful = 200 * scale, retryUsers = 100 * scale, reuseUsers = 50 * scale;
        int limitUsers = 25 * scale, spoofs = 20 * scale, general = 3000 * scale, generalSeats = 1500 * scale;

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
        Http created = send("POST", "/shows", adminToken, json(Map.of(
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
        Map<String, String> tokens = mintTokens(users);
        line("tokens      %d users minted in %.1fs", tokens.size(), (System.nanoTime() - mintStart) / 1e9);

        Map<String, Double> metricsBefore = scrape();

        // Fire --------------------------------------------------------------
        AtomicBoolean bursting = new AtomicBoolean(true);
        List<String> invariantViolations = new CopyOnWriteArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        Thread poller = Thread.ofVirtual().start(() -> {
            while (bursting.get()) {
                try {
                    Http s = send("GET", "/shows/" + showId, null, null, Map.of());
                    if (s.status == 200) {
                        polls.incrementAndGet();
                        long a = num(s.body, "available"), h = num(s.body, "held"), c = num(s.body, "confirmed"),
                                t = num(s.body, "total");
                        if (a + h + c != t) {
                            invariantViolations.add(a + "+" + h + "+" + c + "!=" + t);
                        }
                    }
                    Thread.sleep(250);
                } catch (Exception ignored) {
                    // a failed poll is not an invariant violation
                }
            }
        });

        line("firing      %d requests at once (max %d in flight)...", plan.size(), concurrency);
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
        bursting.set(false);
        poller.join();

        // Let the seat gauges refresh, then read the server's view -----------
        Thread.sleep(3000);
        Http finalShow = send("GET", "/shows/" + showId, null, null, Map.of());
        Map<String, Double> metricsAfter = scrape();

        report(showId, seats.size(), results, seconds, finalShow.body, invariantViolations, polls.get(),
                metricsBefore, metricsAfter);
    }

    // ------------------------------------------------------------------ report

    static void report(String showId, int totalSeats, List<Res> results, double seconds, String showBody,
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
            long seenConfirmed = results.stream().filter(r -> r.status() == 201).count();
            line("  metrics    reservations_confirmed_total +%.0f   declined: %s", dConfirmed, declineDelta(before, after));
            Map<String, Long> seenDeclines = new TreeMap<>();
            results.stream().filter(r -> r.status() == 200).forEach(r -> seenDeclines.merge("idempotent-replay", 1L, Long::sum));
            results.stream().filter(r -> r.status() == 409 && r.code() != null)
                    .forEach(r -> seenDeclines.merge(r.code().toLowerCase().replace('_', '-'), 1L, Long::sum));
            List<String> metricMismatches = new ArrayList<>();
            if (Math.round(dConfirmed) != seenConfirmed) {
                metricMismatches.add("confirmed +" + Math.round(dConfirmed) + " vs " + seenConfirmed + " seen");
            }
            seenDeclines.forEach((reason, n) -> {
                long d = Math.round(after.getOrDefault("declined:" + reason, 0.0) - before.getOrDefault("declined:" + reason, 0.0));
                if (d != n) metricMismatches.add(reason + " +" + d + " vs " + n + " seen");
            });
            check(checks, metricMismatches.isEmpty(),
                    "metrics reconcile with responses (confirmed and every decline reason)",
                    String.join("; ", metricMismatches) + " (other traffic or several instances behind the LB also move these)");
        }

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
        Map<String, String> headers = r.keyInHeader() ? Map.of("Idempotency-Key", r.key()) : Map.of();
        try {
            Http h = send("POST", "/shows/" + showId + "/reserve", token, json(body), headers);
            String code = h.status >= 400 ? str(h.body, "code") : null;
            return new Res(r, h.status, code, h.status < 300 ? str(h.body, "reservation_id") : null,
                    h.status < 300 ? str(h.body, "user_id") : null, h.status < 300 ? seats(h.body) : List.of(),
                    h.replayed, h.micros, null);
        } catch (Exception e) {
            return new Res(r, -1, null, null, null, List.of(), false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
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
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        long micros = (System.nanoTime() - t0) / 1000;
        boolean replayed = res.headers().firstValue("Idempotent-Replayed").map("true"::equals).orElse(false);
        return new Http(res.statusCode(), res.body(), replayed, micros);
    }

    static String token(String user, boolean admin) throws Exception {
        Http h = send("POST", "/auth/token", null, json(Map.of("user_id", user)),
                admin ? Map.of("X-Admin-Key", adminKey) : Map.of());
        if (h.status != 200) {
            fail("Could not get a " + (admin ? "admin " : "") + "token: " + h.status + " " + h.body
                    + (admin ? "  (set ADMIN_KEY or pass --admin-key)" : ""));
        }
        return str(h.body, "access_token");
    }

    static Map<String, String> mintTokens(Set<String> users) throws Exception {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        Semaphore limit = new Semaphore(256);
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
            System.err.println("usage: java burst/Burst.java BASE_URL [--scale N] [--concurrency N] [--admin-key KEY]");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--scale" -> scale = Integer.parseInt(args[++i]);
                case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                case "--admin-key" -> adminKey = args[++i];
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(2);
                }
            }
        }
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
