package io.seatreserve.reservation;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public HoldExpiry(ReservationRepository reservations, SeatReleaser releaser) {
        this.reservations = reservations;
        this.releaser = releaser;
    }

    /** Expires the oldest expired hold, if any. Safe to call from many instances at once. */
    @Transactional
    public Optional<Reservation> expireOne() {
        return reservations.lockNextExpired().map(hold -> {   // 1. reservation (SKIP LOCKED)
            releaser.release(hold);                            // 2. quota, 3. seats
            return reservations.updateStatus(hold.id(), ReservationStatus.EXPIRED);
        });
    }
}
