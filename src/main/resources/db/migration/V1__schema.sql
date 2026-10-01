-- Seat reservation schema.
--
-- Design rule: every correctness property is enforced by the database, either
-- by a constraint (cannot be violated even by a buggy caller) or by a single
-- atomic, row-locked statement. The application never decides based on a value
-- it read earlier without holding a lock on it.
--
-- Global lock order (deadlock freedom) for every write transaction:
--   idempotency_keys(user, key) -> user_show_quota(show, user)
--     -> reservations(id) -> seats(show, label) in ascending label order

CREATE TABLE shows (
    id               UUID        PRIMARY KEY,
    name             TEXT        NOT NULL,
    price_paise      BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit   INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats      INT         NOT NULL CHECK (total_seats > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per physical seat. The status lives here and nowhere else, so
-- available + held + confirmed == total_seats holds by construction.
CREATE TABLE seats (
    show_id        UUID        NOT NULL REFERENCES shows (id),
    label          TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'AVAILABLE'
                               CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    reservation_id UUID,
    user_id        TEXT,
    -- Deadline of this seat's hold: now() + the configured hold TTL, set when held.
    held_until     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, label),
    -- An available seat has no owner; a taken seat always has one.
    CONSTRAINT seat_owner_consistent CHECK (
        (status = 'AVAILABLE' AND reservation_id IS NULL AND user_id IS NULL AND held_until IS NULL)
     OR (status = 'HELD'      AND reservation_id IS NOT NULL AND user_id IS NOT NULL AND held_until IS NOT NULL)
     OR (status = 'CONFIRMED' AND reservation_id IS NOT NULL AND user_id IS NOT NULL AND held_until IS NULL)
    )
);

CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservations (
    id           UUID        PRIMARY KEY,
    show_id      UUID        NOT NULL REFERENCES shows (id),
    user_id      TEXT        NOT NULL,
    seats        TEXT[]      NOT NULL CHECK (cardinality(seats) > 0),
    amount_paise BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status       TEXT        NOT NULL
                             CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT hold_has_expiry CHECK ((status = 'HELD') = (expires_at IS NOT NULL))
);

-- Drives the expiry sweeper: only live holds are indexed.
CREATE INDEX reservations_expiring_idx ON reservations (expires_at) WHERE status = 'HELD';
CREATE INDEX reservations_user_idx ON reservations (show_id, user_id);

-- Per-user seat count for a show. The limit is copied in so a CHECK constraint
-- can enforce it: even a buggy code path cannot push a user over the limit.
CREATE TABLE user_show_quota (
    show_id    UUID NOT NULL REFERENCES shows (id),
    user_id    TEXT NOT NULL,
    held_count INT  NOT NULL CHECK (held_count >= 0),
    seat_limit INT  NOT NULL CHECK (seat_limit > 0),
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT within_limit CHECK (held_count <= seat_limit)
);

-- Idempotency keys are scoped per user, so one user can never collide with
-- (or replay) another user's key. Inserted in the same transaction as the
-- reservation: a concurrent duplicate blocks on the unique index until the
-- first transaction commits (-> replay) or rolls back (-> it proceeds itself).
CREATE TABLE idempotency_keys (
    user_id        TEXT        NOT NULL,
    idem_key       TEXT        NOT NULL,
    request_hash   TEXT        NOT NULL,
    show_id        UUID        NOT NULL REFERENCES shows (id),
    -- Deferred: the key is claimed first (it is the first lock taken) and the
    -- reservation row it points to is inserted later in the same transaction.
    reservation_id UUID        NOT NULL REFERENCES reservations (id) DEFERRABLE INITIALLY DEFERRED,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);
