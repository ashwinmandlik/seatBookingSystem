# Write-up: Seat Reservation at Scale

## 1. The atomic decision

**Mechanism: a PostgreSQL row lock on each requested seat, taken in ascending label order, followed by a
state-guarded `UPDATE`, inside one `READ COMMITTED` transaction.**

```sql
SELECT label, status, user_id FROM seats
WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE;      -- lock, in a fixed order
-- all AVAILABLE?  else roll back -> 409 SEAT_TAKEN
UPDATE seats SET status = 'CONFIRMED', reservation_id = ?, user_id = ?
WHERE show_id = ? AND label = ANY(?) AND status = 'AVAILABLE';         -- guard kept anyway
-- rows affected must equal seats requested, else roll back
```

**Why it's race-free:** 500 requests for A12 queue on A12's row lock. The first finds it `AVAILABLE` and
commits. Each waiter, once the lock is released, re-reads the committed row (Postgres re-evaluates
locked rows under READ COMMITTED), sees `CONFIRMED`, and declines with a clean 409. Nobody decides from a
value they read without holding the lock. The `status = 'AVAILABLE'` guard means the `UPDATE` alone can
never overwrite a taken seat, and a schema `CHECK` ties a seat's status to its owner columns.

I checked that the tests can actually catch a race: with `FOR UPDATE` and the guard removed, the 500-way
test produced 18 winners for one seat.

**Multi-seat and deadlocks:** requests are **all-or-nothing**. If any requested seat is taken, the
transaction rolls back and nothing is held. Deadlocks are prevented by one global lock order, used by every
write path:

```
reservation (id)  ->  user quota (show, user)  ->  seats (show, label ascending)
```

Reserve takes key → quota → seats; it only *creates* a reservation row and never locks an existing one, so it
fits the order. Confirm takes reservation → seats; cancel and expiry take reservation → quota → seats. A
deadlock needs two transactions locking the same rows in opposite orders, which a single order rules out. Two
places that silently broke it were caught in design and review:
- an expiry sweeper that released many users' holds in one transaction would lock one user's seats and then
  another user's quota row (seats before quota). So it expires one hold per transaction.
- `UPDATE seats … WHERE reservation_id = ?` locks rows in scan order, not label order. So confirm and release
  lock their seats `ORDER BY label` first.

To check that the stress test would catch a mistake, I reversed the quota/seat order on purpose and got 216 "deadlock
detected" errors in one run. With the correct order there are none.

**Per-user limit:** a single conditional upsert on a `(show, user)` quota row:
`… ON CONFLICT DO UPDATE SET held_count = held_count + n WHERE held_count + n <= seat_limit`. Zero rows
affected means 409. The row lock serialises one user's parallel requests, and `CHECK (held_count <= seat_limit)`
makes over-allocation impossible even for buggy code. Ten parallel requests against a limit of 4 end with
exactly 4 seats.

**Hot seats without exhausting the pool:** without help, 500 waiters on one row would each hold a pooled
connection, starving requests for other seats. An in-memory per-seat gate (striped locks, taken in sorted
order) makes them wait in the JVM instead, and a short-TTL "known taken" cache declines the losers without a
database round trip: 499 of 500 never touch Postgres. It can only decline, never grant, and with it
switched off every correctness test still passes.

## 2. Idempotency

- **Where:** table `idempotency_keys`, primary key `(user_id, show_id, idem_key)`, holding a SHA-256 `request_hash` of
  *(show, sorted seats, hold flag)* and the `reservation_id`. Keys are scoped per user, so one user can never
  replay another's, and per show, so every row of a reservation shares `show_id` (shardable by show).
- **Exactly-once:** the key `INSERT … ON CONFLICT DO NOTHING` is the first statement of the same transaction
  that takes the seats. Key and reservation commit together or not at all. A concurrent duplicate blocks on the
  unique index until the first transaction finishes. If that one committed, the duplicate sees the conflict and
  replays; if it rolled back (e.g. seat taken), the duplicate proceeds on its own. In the tests, 1,000
  identical concurrent requests produce one reservation and 999 replays.
- **Replay** returns 200 with the original reservation and `Idempotent-Replayed: true`, not a second
  201: a replay is not a sale, so "exactly one 201 per seat" stays true.
- **Same key, different body** (hash mismatch) returns 409 `IDEMPOTENCY_KEY_REUSED`.
- Declines aren't stored, so a retry after a 409 is evaluated again. Keys are never purged yet; production
  would expire them after ~24 h.
- I chose Postgres over Redis for keys deliberately: Redis can't join the Postgres transaction, so it would be a
  dual write with crash windows (key written but sale not committed, or the reverse), lease expiry on
  slow instances, and lost keys on failover. The key insert is cheap and uncontended. The real cost is seat
  contention.

## 3. Holds and expiry

I implemented both models in the brief. `POST /reserve` confirms immediately (the contract's 201
`confirmed`), and owners can cancel. With `"hold": true` the seat is held until `now() + HOLD_TTL_SECONDS`
(300 s), using the database clock, so every instance agrees and seat and reservation deadlines are identical.
`POST /reservations/{id}/confirm` turns it into a sale only while the deadline hasn't passed.

A sweeper in every instance expires holds once a second:
`SELECT … WHERE status = 'HELD' AND expires_at <= now() ORDER BY expires_at LIMIT 1 FOR UPDATE SKIP LOCKED`,
one hold per transaction. `SKIP LOCKED` lets any number of instances split the work without leader election
or processing a hold twice. Release is guarded on `reservation_id = <this one>`, so it can never resurrect a seat
already sold to someone else, and the user's quota is returned in the same transaction. Confirm racing
expiry, cancel racing expiry, and 8 parallel sweepers are all tested. An expired hold may still show as `held`
for up to one sweep interval; it's never double-counted. I rejected lazy expiry (a reserve stealing an
expired hold), because it would lock another user's reservation and quota after its own quota, which breaks
the lock order.

## 4. Consistency vs availability under a partition

**I choose consistency.** Postgres is the single source of truth, and selling a seat twice (refunds, a
broken promise) is far worse than briefly refusing sales.

- **App can't reach Postgres:** reserve, confirm and cancel fail with `503` (+ `Retry-After`) and `/health/ready`
  turns `503` (behind the VM deployment's proxy, routing stops too). Nothing is sold from a cache or a local guess. The
  hot-seat cache and Redis can only *decline*, so even during a partition they can't grant a seat.
- **Ambiguous commit** (connection lost during `COMMIT`): the retry layer re-runs the transaction. With an
  idempotency key that's a clean replay; without one, at worst a 409 for the user's own seat. Never a second sale.
- **Redis partitioned:** a circuit breaker bypasses it for 5 s, and correctness is unchanged. It's excluded
  from readiness on purpose.
- Liveness never checks the database, so a database outage doesn't make the platform restart every instance.
- The AP alternative (accept bookings locally, reconcile later) means overbooking plus compensation. That's
  acceptable for airline economy seats, not for assigned seats.

## 5. Observability: what pages me at 2 am

| Page | Signal | Why |
|---|---|---|
| **Invariant broken** | `seats_reconciliation_drift != 0` | data corruption; nothing else matters until it's explained |
| **Any 5xx** | `rate(http_server_requests_seconds_count{status=~"5.."})` > 0 for 2 min | declines are 4xx by design, so 5xx means a real failure |
| **Not ready** | `/health/ready` failing everywhere | database unreachable, no sales possible |
| **Pool saturation** | `hikaricp_connections_pending` high for minutes | precursor of 503s at on-sale |
| **Latency** | reserve p99 (`reservation_latency_seconds`) > 2 s | buyers timing out |
| **Restarts** | `process_uptime_seconds` resets, or platform events like `dial tcp … i/o timeout` | every live failure I hit while load-testing showed up here first |
| Ticket, not page | `db_transaction_retries_total` rising, `shared_cache_breaker_open == 1`, spike in `holds_expired_total`, `bulkhead_queue_waiting` high | degraded but correct |

**What load-testing the live service taught me.** Every failure I hit was operational, not a correctness bug:
the invariant held and no seat was ever sold twice, even when the instance restarted mid-burst. I found each
failure from the signals above: `process_uptime_seconds` exposed restarts the client couldn't see, a heap
histogram showed ~115 KB per waiting connection (out of memory at ~2,000 queued), the decline `source` label
showed half the "seat taken" answers bypassing the cache, and Render's event log showed its proxy couldn't
connect (`dial tcp … i/o timeout`). The fixes are in the commit history: a write bulkhead, platform threads with
small buffers so waiting is cheap, a fast decline path, sampled decline logs, and keep-alive tuning. Together they
raised the free instance from 44 to between 87 and 99 requests per second across later runs, with no restarts.

Business counters (`reservations_confirmed_total`, `reservations_declined_total{reason,source}`) increment
only after commit, never inside the retried transaction, so they reconcile exactly with API responses.
The burst script checks this, and checks that server-side taken seats equal the seats clients were told they
got. Logs are JSON with `request_id` (also in the response header and every error body) and the token's
`user_id`, plus one access line per request with its outcome code. Readiness uses its own database connection
so a saturated pool isn't mistaken for a dead database.

## 6. AI usage: directed vs decided

I built the base myself: the Spring Boot project, the PostgreSQL schema with Flyway, and the core design. Postgres
is the single source of truth, the SQL is written by hand (JdbcTemplate, no ORM) so the atomic statements stay
visible, multi-seat requests are all-or-nothing, and a reserve confirms immediately, with cancel and optional holds
on top. From there I used Claude Code (Anthropic) as a pair programmer to make it better. It proposed designs and
explained the trade-offs, I questioned them and made the calls, and it implemented and tested each step once I had
approved it. Most of the code was typed by the AI; the direction and the decisions were mine. I also asked ChatGPT
for an independent spec and used it as a checklist against what we had built.

**Where AI made it better**
- **Correctness under concurrency.** The global lock order, the conditional quota upsert, the deferred foreign key
  that lets the idempotency key be the first lock taken, and the expiry sweeper (one hold per transaction,
  `SKIP LOCKED`) came out of these sessions. I asked for each to be justified, and had the AI break the code on
  purpose to show the tests would notice: 18 double-sells without the row lock, 216 deadlocks with the lock order
  reversed.
- **Latency on a tiny free instance.** When the free Render instance (about 0.1 of a CPU) started restarting under
  load, I chose to keep it free and make it faster rather than pay. We measured first, CPU per request and one
  change at a time, then cut work: one log line per request, a fast decline path for hot-seat losers, a write
  bulkhead, platform threads with small buffers, and a longer-lived hot-seat cache. The instance went from 44 to
  87–99 requests per second with no restarts. One earlier recommendation, virtual threads, turned out wrong once
  Render's event log disproved its premise, and we reversed it.
- **Better options than my first idea.** I wanted Redis; the AI argued for adding it as an optional second-level
  cache instead of replacing the in-memory layer, and for keeping idempotency keys in Postgres, because Redis can't
  take part in the Postgres transaction. I asked for the multi-instance failure cases before agreeing to both.

**What I decided**
- Spring Boot and Java, PostgreSQL, explicit SQL over JPA.
- The reservation lifecycle above, and the hold TTL as one global setting rather than per show, after checking
  what the brief asks for.
- Never shed load with `429`: the brief says losing buyers get `409`, so requests wait instead.
- $0 hosting: an Oracle Always Free VM first; Render and Neon when Oracle had no free capacity, with a keep-warm
  monitor and idle-aware background jobs so Neon's free compute hours last the month.
- From the ChatGPT spec: adopt `/health/live` and `/health/ready`, request ids in errors and more metrics; reject
  what contradicted the brief (holds by default, unsigned `Bearer <user-id>` tokens).
- Making it easy to review: a bulk token endpoint, instructions for reviewers' own load tools, and one-command
  ways to run the service and the burst.

**How I checked the work:** every concurrency property has a test against real Postgres, and every claim about the
live service comes from running the burst against it. Mistakes were caught along the way, including the AI's own:
an unsafe 30-second Redis TTL, a burst client whose HTTP/2 use made 98% of requests look dropped, a gauge exported
under the wrong name, and a timing-sensitive test that failed on Windows CI. All of it is in the commit history.

## 7. What I'd do next

- **Real identity provider** (OIDC, RS256/JWKS, short-lived tokens) instead of the demo token issuer.
- **Admission control** for on-sales: per-user rate limiting and a virtual waiting room in front of reserve.
- **High availability:** 2+ app instances behind a load balancer and managed HA Postgres (the app is already
  stateless and the sweeper/gate are multi-instance safe); purge idempotency keys after 24 h.
- **Scale-out:** shard by `show_id`. Every transaction touches exactly one show, so there are no cross-shard
  transactions. Read replicas for `GET /shows/{id}`; PgBouncer once instance count × pool size outgrows
  `max_connections`.
- **Tracing** (OpenTelemetry) and dashboards/alerts for the table above; load tests from multiple regions
  to find the real ceiling of one Postgres primary.
