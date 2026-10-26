package com.viktor.booking.application.cache;

import com.viktor.booking.application.query.AvailabilitySlot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AvailabilityCache {

    Optional<List<AvailabilitySlot>> get(
            Long serviceId,
            LocalDate date
    );

    void put(
            Long serviceId,
            LocalDate date,
            List<AvailabilitySlot> slots
    );

    void evict(
            Long serviceId,
            LocalDate date
    );
}
