package com.viktor.booking.application.cache;

import java.time.LocalDate;

public interface AvailabilityCacheInvalidator {

    void evictAfterCommit(
            Long serviceId,
            LocalDate date
    );
}