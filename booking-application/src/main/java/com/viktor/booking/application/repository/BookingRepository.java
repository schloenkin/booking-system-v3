package com.viktor.booking.application.repository;

import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.domain.model.Booking;
import com.viktor.booking.application.query.BookingSearchCriteria;
import com.viktor.booking.application.query.PageRequestData;
import com.viktor.booking.application.query.PageResult;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository {

    List<Booking> findAll();

    Optional<Booking> findById(Long id);

    List<Booking> findByStatus(BookingStatus status);

    PageResult<Booking> search(
            BookingSearchCriteria criteria,
            PageRequestData pageRequest
    );

    boolean existsConflictingBooking(
            Long serviceId,
            LocalDateTime startTime,
            LocalDateTime endTime
    );

    List<Booking> findOverlappingBookings(
            Long serviceId,
            LocalDateTime startTime,
            LocalDateTime endTime
    );

    boolean existsConflictingBookingExcludingId(
            Long bookingId,
            Long serviceId,
            LocalDateTime startTime,
            LocalDateTime endTime
    );

    Booking save(Booking booking);

    Optional<Booking> update(
            Booking booking
    );


    void deleteById(Long id);
}