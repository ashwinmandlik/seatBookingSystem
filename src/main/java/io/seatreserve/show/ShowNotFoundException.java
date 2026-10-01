package io.seatreserve.show;

import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ShowNotFoundException extends DomainException {

    public ShowNotFoundException(UUID showId) {
        super(HttpStatus.NOT_FOUND, ErrorCode.SHOW_NOT_FOUND, "Show " + showId + " does not exist");
    }
}
