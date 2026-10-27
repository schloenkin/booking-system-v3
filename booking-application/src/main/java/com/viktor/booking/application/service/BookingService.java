package com.viktor.booking.application.service;

import com.viktor.booking.domain.exception.BookingCannotBeConfirmedException;
import com.viktor.booking.domain.exception.InvalidBookingTimeException;
import com.viktor.booking.application.repository.BookingRepository;
import com.viktor.booking.application.exception.BookableServiceNotFoundException;
import com.viktor.booking.application.repository.BookableServiceRepository;
import com.viktor.booking.application.repository.UserRepository;
import com.viktor.booking.application.exception.UserNotFoundException;
import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.domain.model.Booking;
import org.springframework.stereotype.Service;
import com.viktor.booking.application.exception.InactiveBookableServiceException;
import com.viktor.booking.domain.model.BookableService;
import com.viktor.booking.application.exception.InvalidBookingDurationException;
import com.viktor.booking.domain.exception.BookingInPastException;
import com.viktor.booking.application.exception.BookingTimeConflictException;
import com.viktor.booking.application.cache.AvailabilityCacheInvalidator;
import org.springframework.transaction.annotation.Transactional;
import com.viktor.booking.application.exception.InvalidBookingSearchException;
import com.viktor.booking.application.query.BookingSearchCriteria;
import com.viktor.booking.application.query.PageRequestData;
import com.viktor.booking.application.query.PageResult;
import com.viktor.booking.domain.policy.BookingSchedulePolicy;
import com.viktor.booking.domain.exception.InvalidBookingScheduleException;

import java.time.LocalDate;
import java.time.ZoneId;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.Set;

@Service
public class BookingService {

    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of(
                    "id",
                    "startTime",
                    "endTime",
                    "status"
            );

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final BookableServiceRepository serviceRepository;
    private final BookingSchedulePolicy bookingSchedulePolicy =
            new BookingSchedulePolicy(); // at the moment it goes without DI. Later we can correct it.
    private final AvailabilityCacheInvalidator cacheInvalidator;

    public BookingService(
            BookingRepository bookingRepository,
            UserRepository userRepository,
            BookableServiceRepository serviceRepository,
            AvailabilityCacheInvalidator cacheInvalidator
    ) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.cacheInvalidator = cacheInvalidator;
    }

    public List<Booking> getAllBookings() {
        return bookingRepository.findAll();
    }

    public Optional<Booking> getBookingById(Long id) {
        return bookingRepository.findById(id);
    }

    public List<Booking> getBookingsByStatus(BookingStatus status) {
        return bookingRepository.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public PageResult<Booking> searchBookings(
            BookingSearchCriteria criteria,
            PageRequestData pageRequest
    ) {
        if (pageRequest.page() < 0) {
            throw new InvalidBookingSearchException(
                    "Page number must not be negative"
            );
        }

        if (pageRequest.size() < 1
                || pageRequest.size() > 100) {
            throw new InvalidBookingSearchException(
                    "Page size must be between 1 and 100"
            );
        }

        if (criteria.from() != null
                && criteria.to() != null
                && criteria.from().isAfter(criteria.to())) {
            throw new InvalidBookingSearchException(
                    "Date from must not be after date to"
            );
        }

        if (pageRequest.sortBy() == null
                || !ALLOWED_SORT_FIELDS.contains(
                pageRequest.sortBy()
        )) {
            throw new InvalidBookingSearchException(
                    "Unsupported booking sort field: "
                            + pageRequest.sortBy()
            );
        }

        if (!"asc".equalsIgnoreCase(
                pageRequest.direction()
        )
                && !"desc".equalsIgnoreCase(
                pageRequest.direction()
        )) {
            throw new InvalidBookingSearchException(
                    "Sort direction must be asc or desc"
            );
        }

        return bookingRepository.search(
                criteria,
                pageRequest
        );
    }

    @Transactional
    public Booking createBooking(Long userId, Long serviceId, LocalDateTime startTime, LocalDateTime endTime) {
        Booking booking = Booking.create(
                userId,
                serviceId,
                startTime,
                endTime
        );

        if (userRepository.findById(userId).isEmpty()) {
            throw new UserNotFoundException(userId);
        }

        BookableService service = serviceRepository
                .findByIdForUpdate(serviceId)
                .orElseThrow(() ->
                        new BookableServiceNotFoundException(serviceId)
                );

        if (!service.isActive()) {
            throw new InactiveBookableServiceException(serviceId);
        }

        LocalDate today = LocalDate.now(
                ZoneId.of("Europe/Berlin")
        );

        if (!bookingSchedulePolicy.isValid(
                startTime,
                endTime,
                today
        )) {
            throw new InvalidBookingScheduleException();
        }

        Duration bookingDuration =
                Duration.between(startTime, endTime);

        Duration requiredDuration =
                Duration.ofMinutes(service.getDurationMinutes());

        if (!bookingDuration.equals(requiredDuration)) {
            throw new InvalidBookingDurationException(
                    service.getDurationMinutes()
            );
        }

        if (bookingRepository.existsConflictingBooking(
                serviceId,
                startTime,
                endTime
        )) {
            throw new BookingTimeConflictException(serviceId);
        }

        Booking savedBooking = bookingRepository.save(booking);

        evictAvailabilityAfterCommit(serviceId, startTime.toLocalDate());

        return savedBooking;
    }

    @Transactional
    public boolean deleteBookingById(Long id) {
        return bookingRepository.findById(id)
                .map(booking -> {
                    booking.ensureCanBeDeleted();

                    bookingRepository.deleteById(id);

                    evictAvailabilityAfterCommit(
                            booking.getServiceId(),
                            booking.getStartTime().toLocalDate()
                    );

                    return true;
                })
                .orElse(
                        false
                );
    }

    @Transactional
    public Optional<Booking> cancelBookingById(Long id) {
        return bookingRepository.findById(id)
                .flatMap(existingBooking -> {

                    existingBooking.cancel();

                    Optional<Booking> updatedBooking =
                            bookingRepository.update(existingBooking);

                    if (updatedBooking.isPresent()) {
                        evictAvailabilityAfterCommit(
                                existingBooking.getServiceId(),
                                existingBooking.getStartTime().toLocalDate()
                        );
                    }

                    return updatedBooking;
                });
    }

    @Transactional
    public Optional<Booking> confirmBookingById(Long id) {
        return bookingRepository.findById(id)
                .flatMap(existingBooking -> {
                    existingBooking.confirm();
                    return bookingRepository.update(existingBooking);
                });
    }

    @Transactional
    public Optional<Booking> rescheduleBookingById(
            Long bookingId,
            LocalDateTime newStartTime,
            LocalDateTime newEndTime
    ) {
        return bookingRepository.findById(bookingId)
                .flatMap(existingBooking -> {
                    LocalDate today = LocalDate.now(
                            ZoneId.of("Europe/Berlin")
                    );

                    if (!bookingSchedulePolicy.isValid(
                            newStartTime,
                            newEndTime,
                            today
                    )) {
                        throw new InvalidBookingScheduleException();
                    }

                    if (bookingRepository.existsConflictingBookingExcludingId(
                            bookingId,
                            existingBooking.getServiceId(),
                            newStartTime,
                            newEndTime
                    )) {
                        throw new BookingTimeConflictException(
                                existingBooking.getServiceId()
                        );
                    }

                    Long serviceId = existingBooking.getServiceId();

                    // Here we are keeping the old structure BEFORE changing the booking
                    LocalDate oldDate = existingBooking.getStartTime().toLocalDate();
                    LocalDate newDate = newStartTime.toLocalDate();

                    existingBooking.reschedule(
                            newStartTime,
                            newEndTime
                    );

                    Optional<Booking> updatedBooking =
                            bookingRepository.update(existingBooking);


                    if (updatedBooking.isPresent()) {
                        evictAvailabilityAfterCommit(serviceId, oldDate);

                        if (!oldDate.equals(newDate)) {
                            evictAvailabilityAfterCommit(serviceId, newDate);
                        }
                    }

                    return updatedBooking;

                });
    }


    private void evictAvailabilityAfterCommit(
            Long serviceId,
            LocalDate date
    ) {
        cacheInvalidator.evictAfterCommit(serviceId, date);
    }


}