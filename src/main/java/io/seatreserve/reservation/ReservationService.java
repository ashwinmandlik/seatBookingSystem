package io.seatreserve.reservation;

import io.seatreserve.common.db.TransactionRunner;
import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.idle.IdleAwareSchedule;
import io.seatreserve.observability.ReservationMetrics;
import io.seatreserve.config.SeatReserveProperties;
import io.seatreserve.reservation.ReservationDeclines.HoldExpired;
import io.seatreserve.reservation.ReservationDeclines.IdempotencyKeyReused;
import io.seatreserve.reservation.ReservationDeclines.PerUserLimitExceeded;
import io.seatreserve.reservation.ReservationDeclines.ReservationNotActive;
import io.seatreserve.reservation.ReservationDeclines.ReservationNotFound;
import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import io.seatreserve.reservation.ReservationDeclines.UnknownSeats;
import io.seatreserve.reservation.SeatInventory.LockedSeat;
import io.seatreserve.show.SeatStatus;
import io.seatreserve.show.Show;
import io.seatreserve.show.ShowService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Decides who gets a seat.
 *
 * <p>A reserve request passes through cheap in-memory layers first, then one
 * database transaction that makes the actual decision:
 *
 * <pre>
 *   fast-fail   seat known taken by someone else?   -> 409, no database
 *               (local map, then the optional shared Redis cache)          (HotSeatGate)
 *   gate        wait in memory per seat, not on a row lock holding a connection
 *   retry       transient database failures re-run the whole transaction
 *   transaction READ COMMITTED, locks in one global order:
 *                 1. idempotency key  (user, show, key) INSERT .. ON CONFLICT
 *                 2. quota row        (show, user)      conditional upsert
 *                 3. seat rows        (show, label)     SELECT .. ORDER BY label FOR UPDATE
 *                 4. assign seats + insert reservation  one statement
 * </pre>
 *
 * Any decline throws, which rolls back everything done so far: a request
 * either takes all its seats, its quota and its key, or none of them.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    /** The outcome of a successful call. {@code replayed} means nothing new was written. */
    public record ReserveResult(Reservation reservation, boolean replayed) {
    }

    private final ShowService shows;
    private final IdempotencyRepository idempotency;
    private final QuotaRepository quotas;
    private final SeatInventory seats;
    private final ReservationRepository reservations;
    private final SeatReleaser releaser;
    private final HotSeatGate hotSeats;
    private final TransactionRunner tx;
    private final ReservationMetrics metrics;
    private final IdleAwareSchedule schedule;
    private final SeatReserveProperties props;

    public ReservationService(ShowService shows, IdempotencyRepository idempotency, QuotaRepository quotas,
                              SeatInventory seats, ReservationRepository reservations, SeatReleaser releaser,
                              HotSeatGate hotSeats, TransactionRunner tx, ReservationMetrics metrics,
                              IdleAwareSchedule schedule, SeatReserveProperties props) {
        this.shows = shows;
        this.idempotency = idempotency;
        this.quotas = quotas;
        this.seats = seats;
        this.reservations = reservations;
        this.releaser = releaser;
        this.hotSeats = hotSeats;
        this.tx = tx;
        this.metrics = metrics;
        this.schedule = schedule;
        this.props = props;
    }

    public ReserveResult reserve(ReserveCommand cmd) {
        long started = System.nanoTime();
        try {
            ReserveResult result = reserveGuarded(cmd);
            Reservation r = result.reservation();
            metrics.request(result.replayed() ? ReservationMetrics.REPLAY : r.status().json(),
                    System.nanoTime() - started);
            if (result.replayed()) {
                metrics.replayed();
            } else {
                schedule.seatsChanged();
                if (r.status() == ReservationStatus.HELD) {
                    schedule.holdCreated(r.expiresAt());
                    metrics.held();
                } else {
                    metrics.confirmed();
                }
                // One JSON line per request is the budget on a small CPU (each line costs ~0.6 ms):
                // the reservation id rides on the request's access-log line via the MDC, and
                // the full detail line is DEBUG.
                MDC.put("reservation_id", r.id().toString());
                log.atDebug().addKeyValue("reservation_id", r.id()).addKeyValue("show_id", r.showId())
                        .addKeyValue("seats", r.seats()).addKeyValue("status", r.status().json())
                        .addKeyValue("amount_paise", r.amountPaise())
                        .log("Reservation {} {}", r.id(), r.status().json());
            }
            return result;
        } catch (DomainException declined) {
            metrics.request(ReservationMetrics.outcome(declined), System.nanoTime() - started);
            metrics.declined(declined);
            throw declined;
        } catch (RuntimeException unexpected) {
            metrics.request("error", System.nanoTime() - started);
            metrics.failure(unexpected);
            throw unexpected;
        }
    }

    private ReserveResult reserveGuarded(ReserveCommand cmd) {
        Show show = shows.require(cmd.showId());
        hotSeats.declineIfKnownTaken(show.id(), cmd.seats(), cmd.userId());
        return hotSeats.withSeats(show.id(), cmd.seats(), () -> {
            // Whoever held the gate before us may have just sold the seat.
            hotSeats.declineIfKnownTakenLocally(show.id(), cmd.seats(), cmd.userId());
            try {
                ReserveResult result = tx.inTransaction(() -> reserveInTransaction(show, cmd));
                if (!result.replayed()) {
                    Map<String, String> sold = new HashMap<>();
                    result.reservation().seats().forEach(label -> sold.put(label, cmd.userId()));
                    hotSeats.markTaken(show.id(), sold);
                }
                return result;
            } catch (SeatsUnavailable declined) {
                // Learn from the decline so the next requests for these seats fail fast.
                hotSeats.markTaken(show.id(), declined.owners());
                throw declined;
            }
        });
    }

    private ReserveResult reserveInTransaction(Show show, ReserveCommand cmd) {
        UUID reservationId = UUID.randomUUID();
        int requested = cmd.seats().size();

        // 1. Idempotency: claim the key, or replay whatever it already produced.
        if (cmd.idempotencyKey() != null
                && !idempotency.claim(cmd.userId(), show.id(), cmd.idempotencyKey(), cmd.fingerprint(), reservationId)) {
            return replay(cmd);
        }

        // 2. Per-user limit, checked and reserved in one atomic statement.
        if (requested > show.perUserLimit()
                || !quotas.tryAcquire(show.id(), cmd.userId(), requested, show.perUserLimit())) {
            throw new PerUserLimitExceeded(show.perUserLimit(), quotas.held(show.id(), cmd.userId()), requested);
        }

        // 3. Seats: lock in label order, then all-or-nothing.
        List<LockedSeat> locked = seats.lockForUpdate(show.id(), cmd.seats());
        if (locked.size() != requested) {
            throw new UnknownSeats(missing(cmd.seats(), locked));
        }
        Map<String, String> takenBy = new HashMap<>();
        locked.stream()
                .filter(seat -> seat.status() != SeatStatus.AVAILABLE)
                .forEach(seat -> takenBy.put(seat.label(), seat.userId()));
        if (!takenBy.isEmpty()) {
            throw new SeatsUnavailable(takenBy.keySet().stream().sorted().toList(), takenBy, false);
        }

        // 4. Assign the seats and record the reservation in one round trip.
        Long holdSeconds = cmd.hold() ? props.holdTtlSeconds() : null;
        long amount = Math.multiplyExact(show.pricePaise(), (long) requested);
        ReservationRepository.Created created = reservations.createAssigningSeats(reservationId, show.id(),
                cmd.userId(), cmd.seats(), amount, holdSeconds);
        if (created.seatsAssigned() != requested) {
            // Unreachable while we hold the row locks; refuse rather than half-sell.
            throw new IllegalStateException("Assigned " + created.seatsAssigned() + " of " + requested + " locked seats");
        }
        return new ReserveResult(created.reservation(), false);
    }

    public Reservation get(UUID reservationId, String userId) {
        return reservations.findById(reservationId)
                .filter(r -> r.userId().equals(userId))
                .orElseThrow(() -> new ReservationNotFound(reservationId));
    }

    /**
     * HELD -> CONFIRMED. Lock order: reservation -> seats. Confirming an
     * already-confirmed reservation is a no-op success, so clients can retry.
     */
    public Reservation confirm(UUID reservationId, String userId) {
        Transition t = tx.inTransaction(() -> {
            Reservation r = lockOwnedBy(reservationId, userId);
            return switch (r.status()) {
                case CONFIRMED -> new Transition(r, false);
                case EXPIRED -> throw new HoldExpired(reservationId);
                case CANCELLED -> throw new ReservationNotActive(reservationId, r.status());
                case HELD -> {
                    // Empty if past its deadline but not yet swept; the sweeper releases it.
                    Reservation confirmed = reservations.confirmIfLive(reservationId)
                            .orElseThrow(() -> new HoldExpired(reservationId));
                    int count = r.seats().size();
                    int locked = seats.lockOwned(r.showId(), reservationId);
                    int changed = seats.confirmHeld(r.showId(), reservationId);
                    if (locked != count || changed != count) {
                        throw new IllegalStateException(
                                "Hold " + reservationId + " confirmed " + changed + " of " + count);
                    }
                    yield new Transition(confirmed, true);
                }
            };
        });
        if (t.changed()) {
            schedule.seatsChanged();
            metrics.confirmed();
            log.atInfo().addKeyValue("reservation_id", reservationId).log("Hold {} confirmed", reservationId);
        }
        return t.reservation();
    }

    /**
     * HELD or CONFIRMED -> CANCELLED; seats become re-bookable and the user's
     * quota is returned. Lock order: reservation -> quota -> seats. Cancelling
     * twice is a no-op success. An expired reservation cannot be cancelled: its
     * seats were already released and may now belong to someone else.
     */
    public Reservation cancel(UUID reservationId, String userId) {
        Transition t = tx.inTransaction(() -> {
            Reservation r = lockOwnedBy(reservationId, userId);
            return switch (r.status()) {
                case CANCELLED -> new Transition(r, false);
                case EXPIRED -> throw new ReservationNotActive(reservationId, r.status());
                case HELD, CONFIRMED -> {
                    releaser.release(r);
                    yield new Transition(reservations.updateStatus(reservationId, ReservationStatus.CANCELLED), true);
                }
            };
        });
        Reservation cancelled = t.reservation();
        if (t.changed()) {
            // After commit: the seats are free again, so stop fast-failing them.
            hotSeats.forget(cancelled.showId(), cancelled.seats());
            schedule.seatsChanged();
            metrics.cancelled();
            log.atInfo().addKeyValue("reservation_id", reservationId).addKeyValue("seats", cancelled.seats())
                    .log("Reservation {} cancelled", reservationId);
        }
        return cancelled;
    }

    /** A lifecycle call's result, and whether it changed anything (repeats are no-ops). */
    private record Transition(Reservation reservation, boolean changed) {
    }

    private Reservation lockOwnedBy(UUID reservationId, String userId) {
        return reservations.lockById(reservationId)
                .filter(r -> r.userId().equals(userId))
                .orElseThrow(() -> new ReservationNotFound(reservationId));
    }

    private ReserveResult replay(ReserveCommand cmd) {
        // The conflicting insert waited for the other transaction to commit, so
        // this read (a new statement under READ COMMITTED) sees its row.
        IdempotencyRepository.StoredKey stored = idempotency.find(cmd.userId(), cmd.showId(), cmd.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after conflict"));
        if (!stored.requestHash().equals(cmd.fingerprint())) {
            throw new IdempotencyKeyReused();
        }
        Reservation original = reservations.findById(stored.reservationId())
                .orElseThrow(() -> new IllegalStateException("Idempotency key points at a missing reservation"));
        return new ReserveResult(original, true);
    }

    private static List<String> missing(List<String> requested, List<LockedSeat> found) {
        Set<String> present = new HashSet<>();
        found.forEach(seat -> present.add(seat.label()));
        return requested.stream().filter(label -> !present.contains(label)).toList();
    }
}
