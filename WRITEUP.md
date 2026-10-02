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
commits. Each waiter, once the lock is released, **re-reads the committed row** (Postgres re-evaluates
locked rows under READ COMMITTED), sees `CONFIRMED`, and declines with a clean 409. Nobody decides from a
value they read without holding the lock. The `status = 'AVAILABLE'` guard means the `UPDATE` alone can
never overwrite a taken seat, and a schema `CHECK` ties a seat's status to its owner columns.

I checked that the tests can actually catch a race: with `FOR UPDATE` and the guard removed, the 500-way
test produced **18 winners for one seat**.

**Multi-seat and deadlocks:** requests are **all-or-nothing**. If any requested seat is taken, the
transaction rolls back and nothing is held. Deadlocks are prevented by **one global lock order used by every
write path**:

```
reservation (id)  ->  user quota (show, user)  ->  seats (show, label ascending)
```

Reserve takes key → quota → seats; it only *creates* a reservation row and never locks an existing one, so it
fits the order. Confirm takes reservation → seats; cancel and expiry take reservation → quota → seats. A
deadlock needs two transactions locking the same rows in opposite orders, which a single order rules out. Two
places that silently broke it were caught in design and review:
- an expiry sweeper that released **many users' holds in one transaction** would lock one user's seats and then
  another user's quota row (seats before quota). So it expires **one hold per transaction**.
- `UPDATE seats … WHERE reservation_id = ?` locks rows in **scan order**, not label order. So confirm and release
  lock their seats `ORDER BY label` first.

To prove the stress test would catch a mistake, I reversed the quota/seat order on purpose: **216 "deadlock
detected" errors** in one run. With the correct order there are none.

**Per-user limit:** a single conditional upsert on a `(show, user)` quota row:
`… ON CONFLICT DO UPDATE SET held_count = held_count + n WHERE held_count + n <= seat_limit`. Zero rows
affected means 409. The row lock serialises one user's parallel requests, and `CHECK (held_count <= seat_limit)`
makes over-allocation impossible even for buggy code. 10 parallel requests against a limit of 4 → exactly 4.

**Hot seats without exhausting the pool:** without help, 500 waiters on one row would each hold a pooled
connection, starving requests for other seats. An in-memory per-seat gate (striped locks, taken in sorted
order) makes them wait in the JVM instead, and a short-TTL "known taken" cache declines the losers without a
database round trip: 499 of 500 never touch Postgres. It can only **decline, never grant**. With it
switched off, every correctness test still passes.

## 2. Idempotency

- **Where:** table `idempotency_keys`, primary key `(user_id, show_id, idem_key)`, holding a SHA-256 `request_hash` of
  *(show, sorted seats, hold flag)* and the `reservation_id`. Keys are scoped per user, so one user can never
  replay another's, and per show, so every row of a reservation shares `show_id` (shardable by show).
- **Exactly-once:** the key `INSERT … ON CONFLICT DO NOTHING` is the **first statement of the same transaction**
  that takes the seats. Key and reservation commit together or not at all. A concurrent duplicate **blocks on the
  unique index** until the first transaction finishes. If that one committed, the duplicate sees the conflict and
  replays; if it rolled back (e.g. seat taken), the duplicate proceeds on its own. 1000 identical concurrent
  requests → one reservation, 999 replays.
- **Replay** returns **200** with the original reservation and `Idempotent-Replayed: true`, not a second
  201: a replay is not a sale, so "exactly one 201 per seat" stays true.
- **Same key, different body** (hash mismatch) → **409 `IDEMPOTENCY_KEY_REUSED`**.
- Declines aren't stored, so a retry after a 409 is evaluated again. Keys are never purged yet; production
  would expire them after ~24 h.
- I chose Postgres over Redis for keys deliberately: Redis can't join the Postgres transaction, so it would be a
  dual write with crash windows (key written but sale not committed, or the reverse), lease expiry on
  slow instances, and lost keys on failover. The key insert is cheap and uncontended. The real cost is seat
  contention.

## 3. Holds and expiry

I implemented **both** models in the brief. `POST /reserve` confirms immediately (the contract's 201
`confirmed`), and owners can cancel. With `"hold": true` the seat is held until `now() + HOLD_TTL_SECONDS`
(300 s), using the **database clock**, so every instance agrees and seat and reservation deadlines are identical.
`POST /reservations/{id}/confirm` turns it into a sale only while the deadline hasn't passed.

A **sweeper** in every instance expires holds once a second:
`SELECT … WHERE status = 'HELD' AND expires_at <= now() ORDER BY expires_at LIMIT 1 FOR UPDATE SKIP LOCKED`,
**one hold per transaction**. `SKIP LOCKED` lets any number of instances split the work without leader election
or processing a hold twice. Release is guarded on `reservation_id = <this one>`, so it **can never resurrect a seat
already sold to someone else**, and the user's quota is returned in the same transaction. Confirm racing
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
- **Liveness never checks the database**, so a database outage doesn't make the platform restart every instance.
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
| Ticket, not page | `db_transaction_retries_total` rising, `shared_cache_breaker_open == 1`, spike in `holds_expired_total` | degraded but correct |

Business counters (`reservations_confirmed_total`, `reservations_declined_total{reason,source}`) increment
**only after commit**, never inside the retried transaction, so they reconcile exactly with API responses.
The burst script checks this, and checks that server-side taken seats equal the seats clients were told they
got. Logs are JSON with `request_id` (also in the response header and every error body) and the token's
`user_id`, plus one access line per request with its outcome code. Readiness uses its own database connection
so a saturated pool isn't mistaken for a dead database.

## 6. AI usage: directed vs decided

I used **Claude Code (Anthropic)** as a pair programmer for the whole build, working interactively: it
proposed designs and explained trade-offs, I asked questions and made the calls, and it implemented and
tested each step after I approved it. It wrote nearly all of the code, tests, scripts and docs. I also asked
ChatGPT for an independent spec and used it as a checklist against what we'd built.

**What I decided** (often after pushing back or asking for the comparison):
- Keep the existing Spring Boot/Java skeleton, PostgreSQL, explicit SQL (JdbcTemplate) over JPA.
- Lifecycle: immediate confirm plus cancel **and** optional TTL holds; all-or-nothing partial requests.
- Hold TTL as global config rather than per show, after asking "what does the brief say?".
- Postgres, not Redis, as the idempotency authority, after a comparison I asked for, including the
  multi-VM failure cases.
- The burst-hardening layers (fewer round trips, transient retry, hot-seat gate, generous timeouts) and
  explicitly *not* load-shedding with 429, because the brief says losers get 409.
- An optional, fail-open Redis L2 for the hot-seat cache. I pushed for Redis; the AI argued for adding it
  as a second level rather than replacing the in-memory layer.
- $0 hosting: first an Oracle Always Free VM (never sleeps); when Oracle had no free capacity, Render + Neon,
  with a keep-warm cron and idle-aware background jobs so Neon's free compute hours last the month.
- From the ChatGPT spec: adopt `/health/live|ready`, request ids in errors and more metrics; reject what
  contradicted the brief (holds by default, unsigned `Bearer <user-id>` tokens).

**What the AI proposed and I reviewed:** the lock order and both deadlock fixes, the conditional quota upsert,
the deferred FK that lets the key be the first lock, the sweeper design, the hot-seat gate, the metrics
design, and the embedded-Postgres test approach (so tests need no Docker).

**How I kept it honest:** every concurrency property has a test against real Postgres, and I had the AI
**break the code on purpose** to prove the tests catch it (18 double-sells without the lock, 216 deadlocks
with the order reversed, correctness unchanged with the gate disabled). The AI also caught and corrected
its own mistakes along the way (a 30 s Redis TTL that was unsafe, a burst script that crashed during
setup). All of these are in the commit history.

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
