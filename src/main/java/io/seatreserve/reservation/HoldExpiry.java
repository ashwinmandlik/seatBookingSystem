package io.seatreserve.reservation;

import io.seatreserve.common.db.TransactionRunner;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Expires holds whose deadline has passed: HELD -> EXPIRED, seats back to
 * AVAILABLE, quota returned.
 *
 * <p>Exactly one hold per transaction, never a batch. Releasing several users'
 * holds in one transaction would lock one user's seats and then another user's
 * quota row (seats before quota), the reverse of the reserve path (quota before
 * seats), and the two could deadlock.
 */
@Service
public class HoldExpiry {

    private final ReservationRepository reservations;
    private final SeatReleaser releaser;
    private final HotSeatGate hotSeats;
    private final TransactionRunner tx;

    public HoldExpiry(ReservationRepository reservations, SeatReleaser releaser, HotSeatGate hotSeats,
                      TransactionRunner tx) {
        this.reservations = reservations;
        this.releaser = releaser;
        this.hotSeats = hotSeats;
        this.tx = tx;
    }

    /** Expires the oldest expired hold, if any. Safe to call from many instances at once. */
    public Optional<Reservation> expireOne() {
        Optional<Reservation> expired = tx.inTransaction(() ->
                reservations.lockNextExpired().map(hold -> {   // 1. reservation (SKIP LOCKED)
                    releaser.release(hold);                    // 2. quota, 3. seats
                    return reservations.updateStatus(hold.id(), ReservationStatus.EXPIRED);
                }));
        // After commit: the seats are free again, so stop fast-failing them here.
        expired.ifPresent(r -> hotSeats.forget(r.showId(), r.seats()));
        return expired;
    }
}
