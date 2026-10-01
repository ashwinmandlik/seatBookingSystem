package io.seatreserve.reservation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a live reservation's seats back: shared by cancel and expiry so both
 * follow the same steps in the same lock order. The caller must already hold
 * the lock on the reservation row (step 1 of the global order).
 */
@Component
class SeatReleaser {

    private final QuotaRepository quotas;
    private final SeatInventory seats;

    SeatReleaser(QuotaRepository quotas, SeatInventory seats) {
        this.quotas = quotas;
        this.seats = seats;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void release(Reservation reservation) {
        int count = reservation.seats().size();
        // 2. quota row: the user may book these seats' worth again
        quotas.release(reservation.showId(), reservation.userId(), count);
        // 3. seat rows, in label order
        int owned = seats.lockOwned(reservation.showId(), reservation.id());
        int freed = seats.release(reservation.showId(), reservation.id());
        if (owned != count || freed != count) {
            // A live reservation always owns exactly its seats; anything else is
            // corruption, so refuse (and roll back) rather than guess.
            throw new IllegalStateException("Reservation " + reservation.id() + " owns " + owned
                    + " seats, freed " + freed + ", expected " + count);
        }
    }
}
