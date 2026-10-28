package com.viktor.booking.infrastructure.persistence.repository;

import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.infrastructure.persistence.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;


public interface BookingJpaRepository
        extends JpaRepository<BookingEntity, Long>,
        JpaSpecificationExecutor<BookingEntity> {


    List<BookingEntity> findByStatus(BookingStatus status);

    boolean existsByService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
            Long serviceId,
            BookingStatus excludedStatus,
            LocalDateTime endTime,
            LocalDateTime startTime
    );
    boolean existsByIdNotAndService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
            Long bookingId,
            Long serviceId,
            BookingStatus excludedStatus,
            LocalDateTime endTime,
            LocalDateTime startTime
    );

    List<BookingEntity>
    findByService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
            Long serviceId,
            BookingStatus excludedStatus,
            LocalDateTime endTime,
            LocalDateTime startTime
    );
}
