# Seat Reservation at Scale

[![ci](https://github.com/ashwinmandlik/seatBookingSystem/actions/workflows/ci.yml/badge.svg)](https://github.com/ashwinmandlik/seatBookingSystem/actions/workflows/ci.yml)

A JSON API that sells assigned seats for a show and stays correct under an on-sale stampede: a seat is
never sold twice, a user never exceeds their limit, and a retried request never reserves twice. Every
decision is made by PostgreSQL (row locks taken in one global order, conditional updates, and
constraints), with Prometheus metrics, structured logs, and a one-command burst test that checks all of this against
the live URL.

| | |
|---|---|
| **Live URL** | **https://seat-reserve-lrvt.onrender.com** (Render free tier, Singapore) |
| Health | [`/health/live`](https://seat-reserve-lrvt.onrender.com/health/live) · [`/health/ready`](https://seat-reserve-lrvt.onrender.com/health/ready) (also `/livez`, `/readyz`) |
| Metrics | [`/actuator/prometheus`](https://seat-reserve-lrvt.onrender.com/actuator/prometheus) |
| Logs | JSON on stdout (Render log viewer); screen recording of live logs under a burst: *(link in submission)* |
| Burst | `ADMIN_KEY='<admin key>' ./burst.sh https://seat-reserve-lrvt.onrender.com` (see [Run the burst from your laptop](#run-the-burst-from-your-laptop)) |
| Run it locally | `./gradlew dev` (`.\gradlew dev` on Windows): one command, needs only a JDK. Or `docker compose up --build`. In an IDE, run `LocalDev`. See [Run it](#run-it) |
| Design write-up | [WRITEUP.md](WRITEUP.md) |
| Clean-clone CI | [`ci`](.github/workflows/ci.yml) on every push, from a fresh checkout: `./gradlew build` and `./gradlew dev` + the Postman suite on Linux, macOS (Apple Silicon) and Windows; `docker compose up --build`, then the Postman suite, `./burst.sh --wait-for-expiry` with 15 s holds (all 18 checks) and readiness failing closed with Postgres stopped |

### Run the burst from your laptop

You need **Java 21 or newer** (`java -version` to check) **or Docker**, and the admin key from the submission email.
Clone the repo, then from its folder:

```bash
# macOS / Linux (or Git Bash on Windows)
git clone https://github.com/ashwinmandlik/seatBookingSystem.git && cd seatBookingSystem
ADMIN_KEY='<admin key>' ./burst.sh https://seat-reserve-lrvt.onrender.com
```
```powershell
# Windows PowerShell
git clone https://github.com/ashwinmandlik/seatBookingSystem.git; cd seatBookingSystem
java burst/Burst.java https://seat-reserve-lrvt.onrender.com --admin-key '<admin key>'
```

- This fires about **5,900 requests**, including a 1,000-user storm on one seat, and takes **1–2 minutes** on the
  free instance. Add `--scale 4` for about 23,500 requests (4–5 minutes).
- It ends with the outcome counts, a reconciliation against the server, and a list of checks. Success is
  `RESULT: PASS`, exit code `0`.
- No Java 21? `./burst.sh` uses Docker automatically. On Windows without Java or bash, see
  [all the ways to run it](#burst-test) (including running it without cloning).

### For reviewers: pointing your own load tool at it

Tokens are **signed JWTs**: a raw user id such as `Bearer alice` is rejected with `401`. Prepare tokens once
(they're valid for 12 hours), then fire:

```bash
URL=https://seat-reserve-lrvt.onrender.com; ADMIN_KEY=<from the submission email>

# 1. admin token, then a fresh show
ADMIN=$(curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
        -d '{"user_id":"admin"}' | jq -r .access_token)
SHOW=$(curl -s -X POST $URL/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
        -d '{"name":"review","seats":["A1","A2","A3","A12","A13"],"price_paise":25000}' | jq -r .id)

# 2. 20,000 user tokens in ONE call  ->  {"tokens": {"user-1": "eyJ…", …, "user-20000": "eyJ…"}}
curl -s -X POST $URL/auth/tokens -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"count":20000}' > tokens.json          # or {"user_ids":["alice","bob"]}, or add "prefix":"buyer-"

# 3. each request: Authorization: Bearer <tokens[user]>
#    POST $URL/shows/$SHOW/reserve   {"seats":["A12"],"idempotency_key":"…"}   (or Idempotency-Key header)

# 4. verify
curl -s $URL/shows/$SHOW | jq .counts
curl -s $URL/actuator/prometheus | grep -E '^reservations_(confirmed|declined)_total|^seats_available'
```
Expected answers: `201` winner, `409 SEAT_TAKEN` / `PER_USER_LIMIT` / `IDEMPOTENCY_KEY_REUSED` declines,
`200` + `Idempotent-Replayed: true` for a retry with the same key. Or run the included burst test: `./burst.sh $URL`.

**Postman:** import [`postman/SeatReserve.postman_collection.json`](postman/SeatReserve.postman_collection.json). One collection works against both targets: set the `target` variable to `live` (paste the admin key into `liveAdminKey`) or `local` (`localhost:8080`, works as is). A collection script picks the URL and admin key and clears saved tokens when you switch. Run it with the Collection Runner: 25 requests in order (tokens, a fresh show, then every rule: seat taken, idempotent replay, key reuse, per-user limit, token identity, hold → confirm, cancel) with 42 tests on the responses.

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · JdbcTemplate (explicit SQL,
no ORM, so the atomic statements are visible) · Micrometer/Prometheus · Docker Compose · Caddy.

---

## Run it

**Quickest way: one command, needs only a JDK (17 or newer).** Clone the repo, then from its folder:

```bash
./gradlew dev          # macOS, Linux, Git Bash
.\gradlew dev          # Windows PowerShell or cmd
```

This starts the app on <http://localhost:8080> together with a throwaway Postgres 16, with nothing else to
install: Gradle downloads itself and, if needed, JDK 21. When it prints `Seat reserve running on
http://localhost:8080`, try `curl localhost:8080/health/ready`. The admin key is `local-admin-key`.
Ctrl+C stops the app and the database; their data is discarded.

**In an IDE** (IntelliJ, Eclipse, VS Code): open the folder as a Gradle project and run
`io.seatreserve.dev.LocalDev` (in `src/test/java`). It is the same as `./gradlew dev`: app plus embedded
Postgres in one click. Running `SeatReserveApplication` itself needs a database already running (below); without
one it stops with a short message listing the ways to start one.

**With Docker**, exactly as deployed (app, Postgres 16 and Redis in containers, JSON logs):

```bash
docker compose up --build
```

**Other options**

| To… | Run | Needs |
|---|---|---|
| Use the database separately, e.g. to run `SeatReserveApplication` from an IDE | `./gradlew devDb` (Postgres on localhost:5432), then start the app | a JDK |
| Use your own Postgres | `DATABASE_URL=postgresql://user:pass@host:5432/db ./gradlew bootRun` | a JDK |
| Run the tests | `./gradlew test` | a JDK |
| Call the live URL | curl or any load tool | nothing |
| Run the burst test | `./burst.sh <URL>`, or `java burst/Burst.java <URL>` | Java 21+ or Docker ([Burst test](#burst-test)) |

Logs are readable text when run locally and JSON inside the Docker image (`LOG_FORMAT=ecs` shows JSON locally).
Only port 8080 is published by docker compose, so a Postgres you already run on 5432 doesn't conflict.

### Tests

```bash
./gradlew test
```

The 100 tests run against **real PostgreSQL 16 and Redis binaries** started in-process. No Docker is needed, so
they run the same on Linux, macOS (Intel or Apple Silicon) and Windows. They include real concurrent races:
1000 users on one seat, 1000 identical retries, per-user-limit floods, cancel vs reserve, confirm vs
expiry, multiple sweepers, and a mixed 100-thread stress test that would surface any deadlock.

---

## API

All bodies are JSON in `snake_case`. Money is integer **paise**.

### Authentication

Identity comes **only** from a signed JWT (HS256); the user is the token's `sub`. Request bodies have no
user field, so a spoofed `"user_id"` is ignored. For evaluation the service issues its own tokens; in production
this would be a real identity provider and nothing else would change.

```bash
# user token
curl -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
# admin token (required for POST /shows)
curl -X POST $URL/auth/token -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
     -d '{"user_id":"admin"}'
```
Response: `{"access_token":"…","token_type":"Bearer","expires_in":43200,"user_id":"alice","scope":"user"}`.
Send it as `Authorization: Bearer <access_token>`.

**Many users at once** (for load testing; admin key required, up to 25,000 per call):
```bash
curl -X POST $URL/auth/tokens -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"count":20000}'                      # users user-1..user-20000 (optional "prefix")
curl -X POST $URL/auth/tokens -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"user_ids":["alice","bob"]}'
```
Response: `{"token_type":"Bearer","expires_in":43200,"count":20000,"tokens":{"user-1":"eyJ…", …}}`.

### Create a show (admin)

```bash
curl -X POST $URL/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'
```
`per_user_limit` is optional (default **4**). Returns `201` with the show, every seat `available`.

### Reserve

```bash
curl -X POST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $ALICE" \
     -H 'Content-Type: application/json' -H 'Idempotency-Key: 7f1c…' \
     -d '{"seats":["A1","A2"]}'
```
```json
201 {"reservation_id":"…","show_id":"…","user_id":"alice","seats":["A1","A2"],
     "amount_paise":50000,"status":"confirmed","created_at":"…"}
```

| Behaviour | Rule |
|---|---|
| **Partial requests** | **All-or-nothing.** If any requested seat is taken, nothing is reserved: `409 SEAT_TAKEN` with `details.unavailable_seats`. |
| **Idempotency key** | `Idempotency-Key` header **or** `idempotency_key` in the body (if both, they must match). Scoped to *(user, show)*. |
| Retry, same key + same request | `200` with the **original** reservation and `Idempotent-Replayed: true`. Nothing new is written. A replay is not a second sale, so it is not a second `201`. |
| Same key + different seats | `409 IDEMPOTENCY_KEY_REUSED` |
| Per-user limit | Seats held **plus** confirmed per user per show; over the limit is `409 PER_USER_LIMIT`. |
| Optional hold | `"hold": true` → `201` with `"status":"held"` and `expires_at` (default 300 s, `HOLD_TTL_SECONDS`). |

### Confirm, cancel, look up (owner only)

```bash
curl -X POST $URL/reservations/$ID/confirm -H "Authorization: Bearer $ALICE"   # held -> confirmed
curl -X POST $URL/reservations/$ID/cancel  -H "Authorization: Bearer $ALICE"   # held|confirmed -> cancelled
curl         $URL/reservations/$ID         -H "Authorization: Bearer $ALICE"
```
Anyone else gets `404` (a `403` would confirm the id exists). Confirm and cancel are idempotent: repeating
them returns the same result. A cancelled or expired reservation's seats are immediately re-bookable; an
expired reservation can't be cancelled or confirmed, because its seats may already belong to someone else.

### Show state

```bash
curl $URL/shows/$SHOW
```
```json
{"id":"…","name":"friday-night","price_paise":25000,"per_user_limit":4,"total_seats":3,
 "counts":{"available":1,"held":0,"confirmed":2,"total":3},
 "seats":[{"label":"A1","status":"confirmed"},{"label":"A2","status":"confirmed"},{"label":"A3","status":"available"}]}
```
`available + held + confirmed == total` always: each seat row has exactly one status and the counts come
from a single snapshot.

### Errors

Every error has the same shape, with the `request_id` that also appears in the `X-Request-Id` header and in every log line:
```json
{"error":{"code":"SEAT_TAKEN","message":"Seats already taken: A1","details":{"unavailable_seats":["A1"]}},
 "request_id":"0b6f…"}
```

| Status | Codes |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNKNOWN_SEATS` |
| 401 / 403 | `UNAUTHORIZED` (missing/invalid token), `FORBIDDEN` (not admin) |
| 404 | `SHOW_NOT_FOUND`, `RESERVATION_NOT_FOUND`, `NOT_FOUND` |
| **409** | **`SEAT_TAKEN`, `PER_USER_LIMIT`, `IDEMPOTENCY_KEY_REUSED`**, `RESERVATION_NOT_ACTIVE`, `HOLD_EXPIRED` |
| 503 | `SERVICE_UNAVAILABLE` (database unreachable, `Retry-After: 1`) |

Declines are domain outcomes (4xx); a 5xx means a real server failure.

---

## How it stays correct

Short version (full reasoning in [WRITEUP.md](WRITEUP.md)):

```
reserve:  1. INSERT idempotency key ... ON CONFLICT DO NOTHING        (concurrent duplicate waits, then replays)
          2. quota upsert: held_count + n <= limit                     (a user's requests serialise on one row)
          3. SELECT seats ... ORDER BY label FOR UPDATE                (deterministic lock order: no deadlock)
          4. UPDATE seats ... WHERE status = 'AVAILABLE' + INSERT reservation, one statement
          one READ COMMITTED transaction; any decline rolls back all of it
```

- **Global lock order:** reservation → quota → seats (ascending label), on every path: reserve, confirm,
  cancel and expiry. Expiry handles one hold per transaction, using `FOR UPDATE SKIP LOCKED`.
- **Schema-level guards:** `CHECK (held_count <= seat_limit)`, a seat/owner consistency `CHECK`, and unique
  `(user_id, show_id, idem_key)`.
- **Hot-seat gate:** an in-memory per-seat queue plus a "known taken" cache, so 499 of 500 losers are
  declined without a database round trip. It can only **decline**, never grant: switched off, every
  correctness test still passes.
- **Fast decline path:** a servlet filter answers known hot-seat losers *before* the framework stack: an HMAC
  check of the JWT, a read of the small body, a cache lookup. If every requested seat is known taken by someone
  else it returns the same `409 SEAT_TAKEN`; anything uncertain falls through unchanged. Measured: losers were
  as expensive as winners (~3 ms CPU); this cut CPU per request 19%.
- **Write bulkhead:** at most 32 writes processed at once, in a fair queue; reads, health and metrics bypass it.
  It never rejects (no 503s, no 429s); excess requests wait.
- Optional **Redis** second-level cache shared across instances (`REDIS_ENABLED=true`). It fails open with a
  circuit breaker, and the service is fully correct without it.

---

## Observability

**Health:** `/health/live` checks only that the process is up and never depends on the database, so a database outage doesn't
restart the app. `/health/ready` checks Postgres on a **dedicated connection** (so a saturated pool isn't
mistaken for a dead database) and returns **503, failing closed**, when it's unreachable. Traffic is routed only while ready.

**Metrics** (`/actuator/prometheus`):

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | reservations that became confirmed |
| `reservations_declined_total{reason,source}` | `reason`: `seat-taken`, `per-user-limit`, `idempotent-replay`, `idempotency-key-reused`, … ; `source`: `cache`\|`database` |
| `seats_available` / `seats_held` / `seats_confirmed` / `seats_capacity` `{show_id}` | gauges computed from the seats table |
| `seats_reconciliation_drift{show_id}` | `total − (available + held + confirmed)`, which **must be 0** |
| `reservation_requests_total{outcome}`, `reservation_latency_seconds{outcome}` | request rate and latency histogram |
| `reservations_held_total`, `reservations_cancelled_total`, `holds_expired_total` | lifecycle |
| `db_transaction_retries_total`, `database_errors_total`, `reservation_failures_total` | trouble |
| `hikaricp_connections_pending`, `http_server_requests_seconds` | pool saturation, HTTP latency and status |

Counters are incremented only **after commit**, so they reconcile exactly with what the API answered (the burst checks this).

**Logs:** JSON (Elastic Common Schema) on stdout on the live service and anywhere the Docker image runs (it sets
`LOG_FORMAT=ecs`); plain text when run locally with `./gradlew dev` or from an IDE. Every line carries `request_id` and, once authenticated, `user_id`, and every request
ends with one access line showing its outcome:
```json
{"log":{"level":"INFO","logger":"access"},"message":"POST /shows/…/reserve -> 409","request_id":"…",
 "user_id":"bob","status":409,"outcome":"SEAT_TAKEN","duration_ms":10}
```
**Live logs:** on the Render deployment, logs are in Render's per-account log viewer, so a screen recording of
them under a burst is linked in the submission. Locally: `docker compose logs -f app`. On the self-hosted VM
setup ([below](#alternative-any-docker-host-same-containers-as-local)), Dozzle serves them at `/logs` (basic auth).

---

## Burst test

The burst is one self-contained Java file, [`burst/Burst.java`](burst/Burst.java), with no dependencies.
`./burst.sh` is a small bash wrapper around it. It needs **Java 21+**, or **Docker** if Java isn't installed.

**Options**

| Option | Default | |
|---|---|---|
| `<BASE_URL>` (first argument) | required | e.g. `https://seat-reserve-lrvt.onrender.com` or `http://localhost:8080` |
| `--admin-key KEY` | `ADMIN_KEY` env var, else `local-admin-key` | needed to create the show and tokens; the live key is in the submission email |
| `--scale N` | `1` | multiplies every scenario: 1 ≈ 5,900 requests, 4 ≈ 23,500 |
| `--concurrency N` | `2000` (`200` on Windows against localhost) | maximum requests open at the same time; Windows refuses connections beyond ~200 waiting at once |
| `--timeout SECONDS` | `100` | per request; Cloudflare in front of Render also gives up after ~100 s |
| `--wait-for-expiry` | off | also wait for unconfirmed holds to expire (about 5 min on the live service) |

**Ways to run it**

Each command is on one line. Copy the block for your terminal: bash commands don't work in PowerShell, and the
reverse.

1. **bash**: macOS, Linux, or Git Bash / WSL on Windows. From the repo folder:
   ```bash
   # bash
   ADMIN_KEY='<admin key>' ./burst.sh https://seat-reserve-lrvt.onrender.com --scale 4
   ./burst.sh http://localhost:8080          # against a local stack (admin key: local-admin-key)
   ```

2. **Any terminal with Java 21+**: PowerShell, cmd, bash, zsh. From the repo folder:
   ```powershell
   # PowerShell, bash, zsh  (in Windows cmd, put the key in double quotes)
   java burst/Burst.java https://seat-reserve-lrvt.onrender.com --scale 4 --admin-key '<admin key>'
   ```

3. **From any folder:** give the file's full path, replacing `<repo>` with wherever you cloned it.
   ```bash
   # bash
   java <repo>/burst/Burst.java https://seat-reserve-lrvt.onrender.com --admin-key '<admin key>'
   ```
   ```powershell
   # PowerShell (it doesn't expand ~ for java; use $HOME, e.g. $HOME\projects\seatBookingSystem)
   java <repo>\burst\Burst.java https://seat-reserve-lrvt.onrender.com --admin-key '<admin key>'
   ```
   If Java answers `ClassNotFoundException: …Burst.java`, it didn't find the file at that path (Java then treats
   the argument as a class name). Check the path, or `cd` into the repo and use `burst/Burst.java`.

4. **Without cloning the repo:** download the one file, then run it.
   ```bash
   # bash
   curl -fsSLO https://raw.githubusercontent.com/ashwinmandlik/seatBookingSystem/main/burst/Burst.java
   java Burst.java https://seat-reserve-lrvt.onrender.com --scale 4 --admin-key '<admin key>'
   ```
   ```powershell
   # PowerShell
   Invoke-WebRequest https://raw.githubusercontent.com/ashwinmandlik/seatBookingSystem/main/burst/Burst.java -OutFile Burst.java
   java Burst.java https://seat-reserve-lrvt.onrender.com --scale 4 --admin-key '<admin key>'
   ```

5. **Docker only, no Java installed:** `./burst.sh` switches to Docker by itself (Git Bash included). To call
   Docker directly, from the repo folder:
   ```bash
   # bash (macOS, Linux)
   docker run --rm -v "$PWD/burst:/burst:ro" eclipse-temurin:21-jdk java /burst/Burst.java https://seat-reserve-lrvt.onrender.com --admin-key '<admin key>'
   ```
   ```powershell
   # PowerShell
   docker run --rm -v "${PWD}\burst:/burst:ro" eclipse-temurin:21-jdk java /burst/Burst.java https://seat-reserve-lrvt.onrender.com --admin-key '<admin key>'
   ```
   For a service running on your own machine, add `--network host` on Linux. On Docker Desktop, use `./burst.sh`
   (next paragraph).

**Setting the admin key as a variable** instead of `--admin-key`: `export ADMIN_KEY='…'` (bash, zsh),
`$env:ADMIN_KEY='…'` (PowerShell), `set ADMIN_KEY=…` (cmd). Note that `ADMIN_KEY=… command` on one line works
only in bash and zsh, not in PowerShell.

**On Docker Desktop (Windows, macOS)** with a `localhost` target, it fires from inside the stack's Docker network
(and says so). Docker Desktop forwards published ports through a userspace proxy that refuses connections
when thousands open at once: fired from the host, ~1,100–1,500 of 5,770 requests fail to connect, never
reach the app, and fail the run, while every answer that did arrive is correct. From inside the network,
every check passes. `BURST_FROM_HOST=1` forces the host path. Linux Docker forwards in the kernel and is unaffected.

It creates a fresh show and fires everything at the same instant:

| Storm | Checks (the brief's correctness bar) |
|---|---|
| 1000 users → **A1**, 200 users each → A2–A6 | exactly one `201` per hot seat, every other request a clean `409` |
| all requests | **zero 5xx**; dropped connections reported separately |
| polls `GET /shows/{id}` *while firing* and after | `available + held + confirmed == total` during and after |
| 100 users × same request ×4 at once; 50 users reuse a key with other seats | one reservation per key; reuse → `409` |
| 25 users × 10 parallel reserves, limit 4 | never more than 4 seats each |
| spoofed `"user_id"` in the body | identity is always the token's |
| 100 users with `"hold": true` | every one `201 held` with a deadline; the show reports them as `held` |
| 3000 general buyers | realistic contention |

Then it reconciles: server-side taken seats == seats the client was told it got, and metric deltas ==
responses per reason (confirmed, held, every decline).

**Hold lifecycle**, after the burst: a third of the holds are confirmed and a third cancelled, all at once
(checked: each answer, the show's `held`/`confirmed`/`available` deltas, the metrics). The last third is left to
expire. With **`--wait-for-expiry`** the burst waits out the server's hold TTL and checks the sweeper freed them
(`held → 0`, seats available again) and that confirming an expired hold is `409 HOLD_EXPIRED`. The TTL is 300 s on
the live service; locally, shorten it to watch expiry in ~20 s:
```bash
HOLD_TTL_SECONDS=15 docker compose up --build -d
./burst.sh http://localhost:8080 --wait-for-expiry      # 18 checks
```

**Live counts:** while firing, confirming/cancelling and waiting, it shows the show's seat counts updating in
place, e.g. `available 480 + held 34 + confirmed 1,562 = 2,076 ok`. The three numbers come from one response,
which the server computes from a single read of the seat rows, so they always add up; every poll is also an
invariant check. (A line every 5 s when the output isn't a terminal.)

It exits `0` only if every check passes: 16, or 18 with `--wait-for-expiry`.

**Live run** (`./burst.sh https://seat-reserve-lrvt.onrender.com --scale 4 --concurrency 2000 --timeout 100`, Render free instance, ~0.1 CPU / 512 MB, Neon free Postgres; client on a home internet connection):
```
== Seat reservation burst ==
target      https://seat-reserve-lrvt.onrender.com  (ready)
show        a43c6ad8-52bf-46f9-aacf-5fff3edba8ba  (7886 seats, per_user_limit 4)
tokens      20780 users minted in 6.3s
firing      23080 requests at once (up to 2000 concurrently)...

done        23080 requests in 265.60s  ->  87 req/s
latency     p50 19389ms  p95 46536ms  p99 59986ms  max 68452ms

Outcomes
  seat-taken                     15787
  confirmed                       5293
  idempotent-replay               1200
  per-user-limit                   600
  idempotency-key-reused           200
  5xx                                0

By scenario
  hot-seat storm (A1)                  {confirmed=1, seat-taken=3999}
  hot handful (A2-A6)                  {confirmed=5, seat-taken=3995}
  idempotent retries (same key x4)     {confirmed=400, idempotent-replay=1200}
  same key, different seats            {confirmed=200, idempotency-key-reused=200}
  per-user limit flood (10 x limit 4)  {confirmed=400, per-user-limit=600}
  spoofed user_id in body              {confirmed=80}
  general buyers                       {confirmed=4207, seat-taken=7793}

Scorecard
  HOT SEAT (A1)                requests   4000   Confirmed      1   Seat taken   3999   other 4xx 0   5xx 0   dropped 0
  HOT HANDFUL (A2-A6)          requests   4000   Confirmed      5   Seat taken   3995   other 4xx 0   5xx 0   dropped 0
  USER LIMIT (limit 4)         requests   1000   Confirmed    400   Limit exceeded    600   other 4xx 0   5xx 0   dropped 0
  IDEMPOTENCY (same key x4)    requests   1600   Created    400   Replayed   1200   other 4xx 0   5xx 0   dropped 0
  SAME KEY, DIFFERENT BODY     requests    400   Created    200   Key reused    200   other 4xx 0   5xx 0   dropped 0
  GENERAL BUYERS               requests  12000   Confirmed   4207   Seat taken   7793   other 4xx 0   5xx 0   dropped 0

Reconciliation
  server     available 1636 + held 0 + confirmed 6250 = 7886   (total_seats 7886)
  observed   6250 seats in 5293 successful reservations seen by this client
  metrics    reservations_confirmed_total +5293   declined: {(of which answered from cache)=15787, idempotency-key-reused=200, idempotent-replay=1200, per-user-limit=600, seat-taken=15787}

Checks
  PASS  exactly one 201 per hot seat, every other request a clean 409 (A1-A6)
  PASS  no seat confirmed to two reservations (6250 seats sold)
  PASS  zero 5xx across the burst
  PASS  no dropped requests (timeouts / connection errors)
  PASS  same key retried 4x at once: one 201 + three 200 replays, one reservation (400 keys)
  PASS  same key + different seats -> exactly one 201 and one 409 (200 users)
  PASS  per-user limit holds under parallel requests (max held 4/4; 100 flooders capped at exactly 4)
  PASS  identity comes from the token, never the body (spoofed user_id ignored)
  PASS  invariant after the burst: available + held + confirmed == total_seats
  PASS  invariant during the burst (14 polls while firing)
  PASS  server's taken seats == seats the client was told it got (6250 == 6250)
  PASS  metrics reconcile with responses (confirmed and every decline reason)

RESULT: PASS
```
Throughput is bounded by the free instance's CPU share, not by the design: the same build does ~680 req/s on a laptop with the client competing for the same CPU (below). The live run shows the correctness checks holding on real infrastructure, behind a real proxy, with every request answered.

**Local run** (laptop, app + Postgres + client on one machine, 200 concurrent requests):
```
done        5770 requests in 8.53s  ->  676 req/s
latency     p50 227ms  p95 677ms  p99 912ms  max 1516ms

Outcomes
  seat-taken                      3937
  confirmed                       1333
  idempotent-replay                300
  per-user-limit                   150
  idempotency-key-reused            50
  5xx                                0

Scorecard
  HOT SEAT (A1)                requests   1000   Confirmed      1   Seat taken    999   other 4xx 0   5xx 0   dropped 0
  HOT HANDFUL (A2-A6)          requests   1000   Confirmed      5   Seat taken    995   other 4xx 0   5xx 0   dropped 0
  USER LIMIT (limit 4)         requests    250   Confirmed    100   Limit exceeded    150   other 4xx 0   5xx 0   dropped 0
  IDEMPOTENCY (same key x4)    requests    400   Created    100   Replayed    300   other 4xx 0   5xx 0   dropped 0
  SAME KEY, DIFFERENT BODY     requests    100   Created     50   Key reused     50   other 4xx 0   5xx 0   dropped 0
  GENERAL BUYERS               requests   3000   Confirmed   1057   Seat taken   1943   other 4xx 0   5xx 0   dropped 0

Reconciliation
  server     available 408 + held 0 + confirmed 1568 = 1976   (total_seats 1976)
  observed   1568 seats in 1333 successful reservations seen by this client
  metrics    reservations_confirmed_total +1333   declined: {(of which answered from cache)=3202,
             idempotency-key-reused=50, idempotent-replay=300, per-user-limit=150, seat-taken=3937}

Checks
  PASS  exactly one 201 per hot seat, every other request a clean 409 (A1-A6)
  PASS  no seat confirmed to two reservations (1568 seats sold)
  PASS  zero 5xx across the burst
  PASS  no dropped requests (timeouts / connection errors)
  PASS  same key retried 4x at once: one 201 + three 200 replays, one reservation (100 keys)
  PASS  same key + different seats -> exactly one 201 and one 409 (50 users)
  PASS  per-user limit holds under parallel requests (max held 4/4; 25 flooders capped at exactly 4)
  PASS  identity comes from the token, never the body (spoofed user_id ignored)
  PASS  invariant after the burst: available + held + confirmed == total_seats
  PASS  invariant during the burst (12 polls while firing)
  PASS  server's taken seats == seats the client was told it got (1568 == 1568)
  PASS  metrics reconcile with responses (confirmed and every decline reason)

RESULT: PASS
```

---

## Deployment

### Live: Render (free) + Neon (free Postgres), both in Singapore

`render.yaml` is a Render Blueprint, so the deploy is a few clicks and no hand-written config:

1. **Neon** ([neon.com](https://neon.com)): create a project in **AWS Asia Pacific (Singapore)** and copy the
   **direct** connection string (not the "pooled" one: Flyway's migration lock and server-side prepared
   statements need a real session). It looks like
   `postgresql://user:pass@ep-….ap-southeast-1.aws.neon.tech/neondb?sslmode=require` and is used as-is.
2. **Render** ([render.com](https://render.com)): **New → Blueprint** → select this repo → paste the Neon string
   when prompted for `DATABASE_URL`. `JWT_SECRET` and `ADMIN_KEY` are generated; the admin key is under
   the service's **Environment** tab. Render builds the `Dockerfile` and redeploys on every push.
3. **Keep-warm:** a free external uptime monitor (UptimeRobot, HTTP check every 5 minutes) pings `/health/live` so
   the free instance never reaches its 15-minute sleep. Backup: the [`keep-warm`](.github/workflows/keep-warm.yml)
   GitHub Action (set the Actions variable **`LIVE_URL`** to the Render URL). It isn't the primary because GitHub
   runs schedules best-effort and can skip them for longer than 15 minutes.

**How the free tiers are handled** (each row was found by load-testing the live service; the full story is in
the commit history):

| Constraint | Handling |
|---|---|
| Render free sleeps after 15 idle minutes (~1 min to wake) | An uptime monitor pings `/health/live` every 5 min (GitHub Action as backup). Render's 750 free hours/month cover running all month. |
| ~0.1–0.25 of one CPU | Measured CPU per request, one change at a time, and cut what cost most: one JSON log line per request (logging was 24% of CPU), decline-log sampling under load, the fast decline path, C1-only JIT (full C2 compilation *cost* 42% more during a burst). |
| 512 MB RAM | Serial GC, heap 65%, small socket buffers (2 KB). With virtual threads every accepted connection was parsed at once and parked holding ~115 KB of buffers, and a burst exhausted the heap. A fixed pool of **32 platform threads** keeps waiting connections as tiny queued tasks instead. |
| Render restarts an instance its proxy can't connect to (`dial tcp … i/o timeout`) | Accept up to **8,000** connections so the proxy can always connect; waiting is cheap (above). **No platform health-check path**: Render allows 5 s per check, and on this CPU a check can queue longer than that behind buyers. `/health/live` and `/health/ready` are still served. |
| Cloudflare in front of Render: a request waiting > 100 s becomes a `524` | Throughput work keeps the tail under it (live runs: max latency 36–68 s). Upstream keep-alive tuned so Render's proxy, not Tomcat, closes idle connections (avoids `520`s). |
| Neon free pauses after 5 idle minutes and allows 100 CU-hours/month | **Idle-aware mode** (`IDLE_AWARE_ENABLED=true`): the hold sweeper queries only when a hold can be due, seat gauges only after seats change (plus hourly), and the pool shrinks to zero. An idle service lets Neon pause; `/health/live` never touches the database. |
| Hot-seat cache staleness | A single instance clears entries on cancel/expiry, so sold seats stay cached for the whole sale (10 min TTL); at 2 s, half the losers fell back to the database path. |
| Logs | Render's log viewer is per-account, so a screen recording of the live logs under a burst is linked in the submission. Lines are JSON with `request_id`/`user_id`. |

A cold start (first deploy, a restart, or a sleep the pinger missed) takes about a minute on the free
instance, then serves normally; the first request after Neon has paused takes a few hundred ms more while it wakes.

### Alternative: any Docker host (same containers as local)

For a VM, production is **`docker-compose.yml` plus `docker-compose.prod.yml`**: the same containers as local,
plus Caddy (automatic HTTPS, absorbs connection spikes, routes only to a ready app) and Dozzle (log viewer at
`/logs`), with Postgres on the same host and never exposed. On a fresh Ubuntu 24.04 VM with ports 80/443 open:
```bash
curl -fsSLO https://raw.githubusercontent.com/<you>/<repo>/main/deploy/setup-vm.sh
bash setup-vm.sh https://github.com/<you>/<repo>.git
```
The script installs Docker, opens 80/443, raises kernel connection limits, generates secrets once into `.env`,
builds and starts the stack, waits for `/health/ready` over HTTPS, and prints the URL, admin key and log login.
Containers restart automatically, so a reboot comes back healthy. (I first targeted an Oracle Cloud Always Free
ARM VM, which never sleeps and has more headroom, but Oracle had no free capacity in my region, so the live
deployment is Render.)

### Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/seatreserve` | `jdbc:` or provider-style `postgres://user:pass@host/db` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `seatreserve` | not needed if the URL carries credentials |
| `PORT` | `8080` | Render sets this |
| `JWT_SECRET` / `ADMIN_KEY` | dev values | **set in production** (≥ 32 chars for the secret) |
| `HOLD_TTL_SECONDS` | `300` | hold lifetime |
| `HOLD_SWEEP_INTERVAL_MS` / `HOLD_SWEEPER_ENABLED` | `1000` / `true` | expiry sweeper |
| `IDLE_AWARE_ENABLED` / `IDLE_AWARE_MAX_IDLE_MINUTES` | `false` / `60` | leave an idle, pause-when-idle database alone |
| `DB_POOL_SIZE` / `DB_CONNECTION_TIMEOUT_MS` | `20` / `60000` | caps concurrent database work |
| `DB_MIN_IDLE` / `DB_IDLE_TIMEOUT_MS` | pool size / `600000` | `0` / `60000` lets the pool shrink to zero when idle |
| `HOT_SEATS_ENABLED` / `HOT_SEATS_CACHE_TTL_MS` | `true` / `2000` | in-memory hot-seat gate; the 2 s default bounds staleness across instances (Render, a single instance, uses `600000`) |
| `HOT_SEATS_MAX_ENTRIES` | `500000` | cap on cached taken seats (Render: `100000`, ~20 MB) |
| `REDIS_ENABLED` / `REDIS_URL` | `false` / `redis://localhost:6379` | optional shared cache |
| `SERVER_MAX_CONNECTIONS` / `SERVER_ACCEPT_COUNT` | `20000` / `2000` | Tomcat connection limits |
| `LOG_FORMAT` | text (the Docker image sets `ecs`) | `ecs` for JSON, or `logstash` |

---

## Project layout

```
src/main/java/io/seatreserve/
  SeatReserveApplication.java
  reservation/            reserve / confirm / cancel / holds (the core)
    api/                  controller, request/response, FastDeclineFilter (409 before the framework stack)
    service/              ReservationService (the transaction), declines, SeatReleaser
    repository/           reservations, seats (row locks), quota, idempotency keys
    model/                Reservation, ReservationStatus
    cache/                HotSeatGate (striped locks + known-taken L1), optional Redis L2
    expiry/               HoldSweeper (scheduler) + HoldExpiry (one hold per transaction)
  show/
    api/  service/  repository/  model/
  auth/                   JWT resource server, token issuer (single + bulk), FastJwtVerifier
  observability/
    filter/               request id + user MDC, access log sampling, write bulkhead
    metrics/              reservation metrics, per-show seat gauges
    health/               DB readiness indicator, /health/live and /health/ready
  config/                 typed properties, Tomcat tuning, DATABASE_URL parsing
  common/
    error/                ApiError, ErrorCode, GlobalExceptionHandler
    db/                   TransactionRunner (retries transient errors)
    idle/                 IdleAwareSchedule (lets a pause-when-idle database sleep)
src/main/resources/db/migration/   Flyway: V1 schema, V2 hold lifecycle
burst/Burst.java          one-file load generator behind ./burst.sh
deploy/                   Caddyfile, setup-vm.sh (self-hosted VM alternative)
render.yaml               Render Blueprint (the live deployment)
```

Packages are **by feature first, then by layer**: everything about reservations lives under `reservation/`, so a change
to the reserve flow touches one folder, and helpers that only one feature needs stay package-private there.
