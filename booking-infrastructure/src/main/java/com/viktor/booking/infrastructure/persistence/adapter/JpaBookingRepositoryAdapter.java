package com.viktor.booking.infrastructure.persistence.adapter;

import com.viktor.booking.application.repository.BookingRepository;
import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.domain.model.Booking;
import com.viktor.booking.infrastructure.persistence.entity.BookableServiceEntity;
import com.viktor.booking.infrastructure.persistence.entity.BookingEntity;
import com.viktor.booking.infrastructure.persistence.entity.UserEntity;
import com.viktor.booking.infrastructure.persistence.mapper.BookingMapper;
import com.viktor.booking.infrastructure.persistence.repository.BookableServiceJpaRepository;
import com.viktor.booking.infrastructure.persistence.repository.BookingJpaRepository;
import com.viktor.booking.infrastructure.persistence.repository.UserJpaRepository;
import com.viktor.booking.application.query.BookingSearchCriteria;
import com.viktor.booking.application.query.PageRequestData;
import com.viktor.booking.application.query.PageResult;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
@Profile("jpa")
@Transactional
public class JpaBookingRepositoryAdapter implements BookingRepository {

    private final BookingJpaRepository bookingJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final BookableServiceJpaRepository serviceJpaRepository;
    private final BookingMapper bookingMapper;

    public JpaBookingRepositoryAdapter(
            BookingJpaRepository bookingJpaRepository,
            UserJpaRepository userJpaRepository,
            BookableServiceJpaRepository serviceJpaRepository,
            BookingMapper bookingMapper
    ) {
        this.bookingJpaRepository = bookingJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.serviceJpaRepository = serviceJpaRepository;
        this.bookingMapper = bookingMapper;
    }

    @Override
    public List<Booking> findAll() {
        return bookingJpaRepository
                .findAll()
                .stream()
                .map(bookingMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Booking> findById(Long id) {
        return bookingJpaRepository
                .findById(id)
                .map(bookingMapper::toDomain);
    }

    @Override
    public List<Booking> findByStatus(BookingStatus status) {
        return bookingJpaRepository
                .findByStatus(status)
                .stream()
                .map(bookingMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Booking> search(
            BookingSearchCriteria criteria,
            PageRequestData pageRequest
    ) {
        Specification<BookingEntity> specification =
                createSearchSpecification(criteria);

        Sort.Direction direction =
                Sort.Direction.fromString(
                        pageRequest.direction()
                );

        Sort sort = Sort.by(
                direction,
                pageRequest.sortBy()
        );

        if (!"id".equals(pageRequest.sortBy())) {
            sort = sort.and(
                    Sort.by(Sort.Direction.ASC, "id")
            );
        }

        Pageable pageable = PageRequest.of(
                pageRequest.page(),
                pageRequest.size(),
                sort
        );

        Page<BookingEntity> entityPage =
                bookingJpaRepository.findAll(
                        specification,
                        pageable
                );

        List<Booking> content = entityPage
                .getContent()
                .stream()
                .map(bookingMapper::toDomain)
                .toList();

        return new PageResult<>(
                content,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isFirst(),
                entityPage.isLast()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsConflictingBooking(
            Long serviceId,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        return bookingJpaRepository
                .existsByService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
                        serviceId,
                        BookingStatus.CANCELLED,
                        endTime,
                        startTime
                );
    }
    @Override
    @Transactional(readOnly = true)
    public boolean existsConflictingBookingExcludingId(
            Long bookingId,
            Long serviceId,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        return bookingJpaRepository
                .existsByIdNotAndService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
                        bookingId,
                        serviceId,
                        BookingStatus.CANCELLED,
                        endTime,
                        startTime
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Booking> findOverlappingBookings(
            Long serviceId,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        return bookingJpaRepository.findByService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
                        serviceId,
                        BookingStatus.CANCELLED,
                        endTime,
                        startTime)
                .stream()
                .map(bookingMapper::toDomain)
                .toList();
    }

    @Override
    public Booking save(Booking booking) {
        UserEntity userEntity = userJpaRepository
                .findById(booking.getUserId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found with id: " + booking.getUserId()
                ));

        BookableServiceEntity serviceEntity = serviceJpaRepository
                .findById(booking.getServiceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Bookable service not found with id: "
                                + booking.getServiceId()
                ));

        BookingEntity newEntity = bookingMapper.toNewEntity(
                booking,
                userEntity,
                serviceEntity
        );

        BookingEntity savedEntity =
                bookingJpaRepository.save(newEntity);

        return bookingMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Booking> update(
            Booking booking
    ) {
        if (booking.getId() == null) {
            throw new IllegalArgumentException(
                    "Booking id must not be null for update"
            );
        }

        return bookingJpaRepository
                .findById(booking.getId())
                .map(entity -> {

                    entity.setStatus(
                            booking.getStatus()
                    );

                    entity.reschedule(
                            booking.getStartTime(),
                            booking.getEndTime()
                    );

                    BookingEntity savedEntity =
                            bookingJpaRepository.save(entity);

                    return bookingMapper.toDomain(
                            savedEntity
                    );
                });
    }

    @Override
    public void deleteById(Long id) {
        bookingJpaRepository.deleteById(id);
    }

    private Specification<BookingEntity>
    createSearchSpecification(
            BookingSearchCriteria criteria
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates =
                    new ArrayList<>();

            if (criteria.status() != null) {
                predicates.add(
                        criteriaBuilder.equal(
                                root.get("status"),
                                criteria.status()
                        )
                );
            }

            if (criteria.userId() != null) {
                predicates.add(
                        criteriaBuilder.equal(
                                root.get("user").get("id"),
                                criteria.userId()
                        )
                );
            }

            if (criteria.serviceId() != null) {
                predicates.add(
                        criteriaBuilder.equal(
                                root.get("service").get("id"),
                                criteria.serviceId()
                        )
                );
            }

            if (criteria.from() != null) {
                predicates.add(
                        criteriaBuilder.greaterThanOrEqualTo(
                                root.<LocalDateTime>get(
                                        "startTime"
                                ),
                                criteria.from()
                        )
                );
            }

            if (criteria.to() != null) {
                predicates.add(
                        criteriaBuilder.lessThanOrEqualTo(
                                root.<LocalDateTime>get(
                                        "startTime"
                                ),
                                criteria.to()
                        )
                );
            }

            return criteriaBuilder.and(
                    predicates.toArray(Predicate[]::new)
            );
        };
    }

}
