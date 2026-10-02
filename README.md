# Seat Reservation at Scale

A JSON API that sells assigned seats for a show and stays correct under an on-sale stampede: a seat is
never sold twice, a user never exceeds their limit, and a retried request never reserves twice. Every
decision is made by PostgreSQL (row locks taken in one global order, conditional updates, and
constraints), with Prometheus metrics, structured logs, and a one-command burst that proves it against
the live URL.

| | |
|---|---|
| **Live URL** | `https://<LIVE-HOST>` *(filled in after deploy)* |
| Health | [`/health/live`](https://<LIVE-HOST>/health/live) · [`/health/ready`](https://<LIVE-HOST>/health/ready) (also `/livez`, `/readyz`) |
| Metrics | [`/actuator/prometheus`](https://<LIVE-HOST>/actuator/prometheus) |
| Live logs | [`/logs`](https://<LIVE-HOST>/logs), user `grader`, password in the submission email |
| Burst | `./burst.sh https://<LIVE-HOST>` (needs the admin key, see [Burst test](#burst-test)) |
| Design write-up | [WRITEUP.md](WRITEUP.md) |

**Stack:** Java 21 (virtual threads) · Spring Boot 3.5 · PostgreSQL 16 · Flyway · JdbcTemplate (explicit SQL,
no ORM, so the atomic statements are visible) · Micrometer/Prometheus · Docker Compose · Caddy.

---

## Run it

### With Docker (same as production)

```bash
docker compose up --build
curl localhost:8080/health/ready
```

Starts the app, Postgres 16 and (optional) Redis. The admin key is `local-admin-key`.

### Without Docker

```bash
./gradlew devDb      # terminal 1: throwaway Postgres 16 on :5432 (embedded binaries)
./gradlew bootRun    # terminal 2: the app on :8080
```

### Tests

```bash
./gradlew test
```

The 72 tests run against **real PostgreSQL 16 and Redis binaries** started in-process. No Docker is needed, so
they run the same on Linux, macOS (Intel or Apple Silicon) and Windows. They include genuinely concurrent races:
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

Declines are domain outcomes (4xx); 5xx is reserved for genuine server failure.

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
| `seats_available` / `seats_held` / `seats_confirmed` / `seats_total` `{show_id}` | gauges computed from the seats table |
| `seats_reconciliation_drift{show_id}` | `total − (available + held + confirmed)`, which **must be 0** |
| `reservation_requests_total{outcome}`, `reservation_latency_seconds{outcome}` | request rate and latency histogram |
| `reservations_held_total`, `reservations_cancelled_total`, `holds_expired_total` | lifecycle |
| `db_transaction_retries_total`, `database_errors_total`, `reservation_failures_total` | trouble |
| `hikaricp_connections_pending`, `http_server_requests_seconds` | pool saturation, HTTP latency and status |

Counters are incremented only **after commit**, so they reconcile exactly with what the API answered (the burst checks this).

**Logs:** JSON (Elastic Common Schema) on stdout. Every line carries `request_id` and, once authenticated, `user_id`, and every request
ends with one access line showing its outcome:
```json
{"log":{"level":"INFO","logger":"access"},"message":"POST /shows/…/reserve -> 409","request_id":"…",
 "user_id":"bob","status":409,"outcome":"SEAT_TAKEN","duration_ms":10}
```
Live view: **`/logs`** on the deployment (Dozzle, basic auth).

---

## Burst test

```bash
ADMIN_KEY=<admin key> ./burst.sh <BASE_URL> [--scale N] [--concurrency N]

./burst.sh http://localhost:8080                                   # local stack (admin key: local-admin-key)
ADMIN_KEY=… ./burst.sh https://<LIVE-HOST> --scale 4               # ~23,000 requests against the live URL
```

It needs **Java 21+** (a single-file program, `burst/Burst.java`, no dependencies), or falls back to **Docker**.
It creates a fresh show and fires everything at the same instant:

| Storm | Checks (the brief's correctness bar) |
|---|---|
| 1000 users → **A1**, 200 users each → A2–A6 | exactly one `201` per hot seat, every other request a clean `409` |
| all requests | **zero 5xx**; dropped connections reported separately |
| polls `GET /shows/{id}` *while firing* and after | `available + held + confirmed == total` during and after |
| 100 users × same request ×4 at once; 50 users reuse a key with other seats | one reservation per key; reuse → `409` |
| 25 users × 10 parallel reserves, limit 4 | never more than 4 seats each |
| spoofed `"user_id"` in the body | identity is always the token's |
| 3000 general buyers | realistic contention |

Then it reconciles: server-side taken seats == seats the client was told it got, and metric deltas ==
responses per reason. It exits `0` only if every check passes.

**Live run** (`--scale 4`, against the deployment): *(output pasted here after deploy)*

**Local run** (laptop, app + Postgres + client on one machine, 200 in flight):
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

Production is **this repo's `docker-compose.yml` plus `docker-compose.prod.yml`**: the same containers as
local, plus Caddy (automatic HTTPS, connection absorption, readiness-gated routing) and Dozzle (log viewer).
Postgres runs on the same host and is never exposed.

It's deployed on an **Oracle Cloud Always Free** ARM VM (4 OCPU / 24 GB). I chose it over free PaaS tiers because
it never sleeps (Render's free tier sleeps after 15 minutes and wakes a JVM in 30–60 s, which would fail
"survive a cold start"), has headroom for a 20k burst, and lets production be exactly `docker compose up`.

**Deploy to any fresh Ubuntu 24.04 VM** (ports 80/443 open in the cloud firewall):
```bash
curl -fsSLO https://raw.githubusercontent.com/<you>/<repo>/main/deploy/setup-vm.sh
bash setup-vm.sh https://github.com/<you>/<repo>.git
```
The script installs Docker, opens 80/443 in iptables, raises kernel connection limits, generates secrets
once into `.env`, builds and starts the stack, waits for `/health/ready` over HTTPS, and prints the URL,
admin key and log login. Re-run it to deploy a new version. Containers restart automatically, so a VM
reboot comes back healthy without intervention.

Any other Docker host works with `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`
and a `.env` like [.env.example](.env.example). On a PaaS, set `DATABASE_URL` to the provider's
`postgres://user:pass@host/db` URL as given; it is converted to JDBC automatically.

### Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/seatreserve` | `jdbc:` or provider-style `postgres://user:pass@host/db` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `seatreserve` | not needed if the URL carries credentials |
| `PORT` | `8080` | |
| `JWT_SECRET` / `ADMIN_KEY` | dev values | **set in production** (≥ 32 chars for the secret) |
| `HOLD_TTL_SECONDS` | `300` | hold lifetime |
| `HOLD_SWEEP_INTERVAL_MS` / `HOLD_SWEEPER_ENABLED` | `1000` / `true` | expiry sweeper |
| `DB_POOL_SIZE` / `DB_CONNECTION_TIMEOUT_MS` | `20` / `60000` | the pool is the backpressure valve |
| `HOT_SEATS_ENABLED` / `HOT_SEATS_CACHE_TTL_MS` | `true` / `2000` | in-memory hot-seat gate |
| `REDIS_ENABLED` / `REDIS_URL` | `false` / `redis://localhost:6379` | optional shared cache |
| `LOG_FORMAT` | `ecs` | or `logstash` |

---

## Project layout

```
src/main/java/io/seatreserve/
  auth/            JWT resource server, demo token issuer
  show/            shows, seats, counts
  reservation/     reserve / confirm / cancel, quota, idempotency, hold sweeper, hot-seat gate, Redis L2
  observability/   metrics, seat gauges, readiness probe, request-id + user MDC filters
  common/          error model, transaction retry
src/main/resources/db/migration/   Flyway: V1 schema, V2 hold lifecycle
burst/Burst.java   one-file load generator behind ./burst.sh
deploy/            Caddyfile, setup-vm.sh
```
