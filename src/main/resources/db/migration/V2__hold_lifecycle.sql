-- Hold lifecycle (confirm / cancel / expire) and shard-ready idempotency keys.
--
-- Global lock order, revised now that existing reservations get locked too.
-- Every write transaction acquires row locks in this order, never backwards:
--
--   reservation(id) -> user_show_quota(show, user) -> seats(show, label) ascending
--
-- reserve:  idempotency key -> quota -> seats   (it only creates a new
--           reservation row, never locks an existing one, so it fits the order)
-- confirm:  reservation -> seats
-- cancel:   reservation -> quota -> seats
-- expire:   reservation (SKIP LOCKED) -> quota -> seats, one reservation per
--           transaction; batching several users' holds would break the order.

-- Scope keys to the show as well as the user: a reservation, its quota row,
-- its seats and its key then all share show_id, so sharding by show keeps
-- every transaction on a single shard.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (user_id, show_id, idem_key);

-- Keep the deadline on expired/confirmed/cancelled reservations for audit;
-- only require that a live hold has one.
ALTER TABLE reservations DROP CONSTRAINT hold_has_expiry;
ALTER TABLE reservations ADD CONSTRAINT hold_has_expiry CHECK (status <> 'HELD' OR expires_at IS NOT NULL);
