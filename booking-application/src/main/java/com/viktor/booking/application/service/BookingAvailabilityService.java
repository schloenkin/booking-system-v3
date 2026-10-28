package com.viktor.booking.application.service;

import com.viktor.booking.application.exception.BookableServiceNotFoundException;
import com.viktor.booking.application.exception.InactiveBookableServiceException;
import com.viktor.booking.application.query.AvailabilitySlot;
import com.viktor.booking.application.repository.BookingRepository;
import com.viktor.booking.domain.model.BookableService;
import com.viktor.booking.domain.model.Booking;
import com.viktor.booking.domain.policy.BookingSchedulePolicy;
import com.viktor.booking.application.cache.AvailabilityCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BookingAvailabilityService {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Europe/Berlin");

    private static final LocalTime FIRST_SLOT =
            LocalTime.of(9, 0);

    private static final LocalTime CLOSING =
            LocalTime.of(18, 0);

    private static final int SLOT_STEP_MINUTES = 30;

    private final BookableServiceService serviceService;
    private final BookingRepository bookingRepository;
    private final AvailabilityCache availabilityCache;

    private final BookingSchedulePolicy schedulePolicy =
            new BookingSchedulePolicy();

    private static final Logger log =
            LoggerFactory.getLogger(BookingAvailabilityService.class);

    public BookingAvailabilityService(
            BookableServiceService serviceService,
            BookingRepository bookingRepository,
            AvailabilityCache availabilityCache
    ) {
        this.serviceService = serviceService;
        this.bookingRepository = bookingRepository;
        this.availabilityCache = availabilityCache;
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlot> getAvailability(
            Long serviceId,
            LocalDate date
    ) {
        BookableService service = serviceService
                .getServiceById(serviceId)
                .orElseThrow(() ->
                        new BookableServiceNotFoundException(serviceId)
                );

        if (!service.isActive()) {
            throw new InactiveBookableServiceException(serviceId);
        }

        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        if (date.isBefore(schedulePolicy.nextWorkingDay(today))) {
            return createUnavailableSlots();
        }

        try {
            Optional<List<AvailabilitySlot>> cachedSlots =
                    availabilityCache.get(serviceId, date);

            if (cachedSlots.isPresent()) {
                return cachedSlots.get();
            }

        } catch (RuntimeException ex) {
            log.warn(
                    "Availability cache read failed. Falling back to database. serviceId={}, date={}",
                    serviceId,
                    date,
                    ex
            );
        }

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        List<Booking> existingBookings =
                bookingRepository.findOverlappingBookings(
                        serviceId,
                        dayStart,
                        dayEnd
                );

        List<AvailabilitySlot> slots = new ArrayList<>();

        LocalTime slotTime = FIRST_SLOT;

        while (slotTime.isBefore(CLOSING)) {

            LocalDateTime start = LocalDateTime.of(
                    date,
                    slotTime
            );

            LocalDateTime end = start.plusMinutes(
                    service.getDurationMinutes()
            );

            boolean validSchedule = schedulePolicy.isValid(
                    start,
                    end,
                    today
            );

            boolean available = false;

            if (validSchedule) {
                boolean hasConflict = existingBookings.stream()
                        .anyMatch(booking ->
                                booking.getStartTime().isBefore(end)
                                        && booking.getEndTime().isAfter(start)
                        );

                available = !hasConflict;
            }

            slots.add(
                    new AvailabilitySlot(slotTime, available)
            );

            slotTime = slotTime.plusMinutes(
                    SLOT_STEP_MINUTES
            );
        }

        try {
            availabilityCache.put(serviceId, date, slots);
        } catch (RuntimeException ex) {
            log.warn(
                    "Availability cache write failed. Returning calculated availability. serviceId={}, date={}",
                    serviceId,
                    date,
                    ex
            );
        }
        return slots;
    }
    private List<AvailabilitySlot> createUnavailableSlots() {
        List<AvailabilitySlot> slots = new ArrayList<>();

        LocalTime slotTime = FIRST_SLOT;

        while (slotTime.isBefore(CLOSING)) {
            slots.add(new AvailabilitySlot(slotTime, false));

            slotTime = slotTime.plusMinutes(SLOT_STEP_MINUTES);
        }

        return slots;
    }
}
