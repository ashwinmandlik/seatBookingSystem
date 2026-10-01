package io.seatreserve.reservation;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who gets a seat. Every write runs in one READ COMMITTED transaction
 * and takes locks in one global order, which is what makes it both race-free
 * and deadlock-free:
 *
 * <pre>
 *   1. idempotency key  (user, show, key) INSERT .. ON CONFLICT
 *   2. quota row        (show, user)     conditional upsert
 *   3. seat rows        (show, label)    SELECT .. ORDER BY label FOR UPDATE
 * </pre>
 *
 * Any decline throws, which rolls back everything done so far: a request
 * either takes all its seats, its quota and its key, or none of them.
 */
@Service
public class ReservationService {

    /** The outcome of a successful call. {@code replayed} means nothing new was written. */
    public record ReserveResult(Reservation reservation, boolean replayed) {
    }

    private final ShowService shows;
    private final IdempotencyRepository idempotency;
    private final QuotaRepository quotas;
    private final SeatInventory seats;
    private final ReservationRepository reservations;
    private final SeatReleaser releaser;
    private final SeatReserveProperties props;

    public ReservationService(ShowService shows, IdempotencyRepository idempotency, QuotaRepository quotas,
                              SeatInventory seats, ReservationRepository reservations, SeatReleaser releaser,
                              SeatReserveProperties props) {
        this.shows = shows;
        this.idempotency = idempotency;
        this.quotas = quotas;
        this.seats = seats;
        this.reservations = reservations;
        this.releaser = releaser;
        this.props = props;
    }

    @Transactional
    public ReserveResult reserve(ReserveCommand cmd) {
        Show show = shows.require(cmd.showId());
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
        List<String> taken = locked.stream()
                .filter(seat -> seat.status() != SeatStatus.AVAILABLE)
                .map(LockedSeat::label)
                .toList();
        if (!taken.isEmpty()) {
            throw new SeatsUnavailable(taken);
        }

        Long holdSeconds = cmd.hold() ? props.holdTtlSeconds() : null;
        int assigned = seats.assign(show.id(), cmd.seats(), reservationId, cmd.userId(), holdSeconds);
        if (assigned != requested) {
            // Unreachable while we hold the row locks; refuse rather than half-sell.
            throw new IllegalStateException("Assigned " + assigned + " of " + requested + " locked seats");
        }

        long amount = Math.multiplyExact(show.pricePaise(), (long) requested);
        Reservation reservation = reservations.insert(reservationId, show.id(), cmd.userId(), cmd.seats(), amount,
                holdSeconds);
        return new ReserveResult(reservation, false);
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
    @Transactional
    public Reservation confirm(UUID reservationId, String userId) {
        Reservation r = lockOwnedBy(reservationId, userId);
        return switch (r.status()) {
            case CONFIRMED -> r;
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
                    throw new IllegalStateException("Hold " + reservationId + " confirmed " + changed + " of " + count);
                }
                yield confirmed;
            }
        };
    }

    /**
     * HELD or CONFIRMED -> CANCELLED; seats become re-bookable and the user's
     * quota is returned. Lock order: reservation -> quota -> seats. Cancelling
     * twice is a no-op success. An expired reservation cannot be cancelled: its
     * seats were already released and may now belong to someone else.
     */
    @Transactional
    public Reservation cancel(UUID reservationId, String userId) {
        Reservation r = lockOwnedBy(reservationId, userId);
        return switch (r.status()) {
            case CANCELLED -> r;
            case EXPIRED -> throw new ReservationNotActive(reservationId, r.status());
            case HELD, CONFIRMED -> {
                releaser.release(r);
                yield reservations.updateStatus(reservationId, ReservationStatus.CANCELLED);
            }
        };
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
