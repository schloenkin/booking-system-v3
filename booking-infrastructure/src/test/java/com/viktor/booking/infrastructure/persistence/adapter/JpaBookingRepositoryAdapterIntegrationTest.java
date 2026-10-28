package com.viktor.booking.infrastructure.persistence.adapter;

import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.domain.enums.UserRole;
import com.viktor.booking.domain.model.Booking;
import com.viktor.booking.infrastructure.persistence.entity.BookableServiceEntity;
import com.viktor.booking.infrastructure.persistence.entity.BookingEntity;
import com.viktor.booking.infrastructure.persistence.entity.UserEntity;
import com.viktor.booking.infrastructure.persistence.mapper.BookingMapper;
import com.viktor.booking.infrastructure.persistence.repository.BookingJpaRepository;
import com.viktor.booking.application.query.BookingSearchCriteria;
import com.viktor.booking.application.query.PageRequestData;
import com.viktor.booking.application.query.PageResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@ActiveProfiles("jpa")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ContextConfiguration(
        classes = JpaBookingRepositoryAdapterIntegrationTest.TestConfiguration.class
)
class JpaBookingRepositoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JpaBookingRepositoryAdapter bookingRepositoryAdapter;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void shouldSaveAndFindBookingThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "adapter-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Backend consultation",
                        "Individual backend consultation",
                        60,
                        true
                )
        );

        Long userId = user.getId();
        Long serviceId = service.getId();

        LocalDateTime startTime =
                LocalDateTime.of(2035, 8, 5, 10, 0);

        LocalDateTime endTime =
                startTime.plusMinutes(60);

        Booking bookingToSave = Booking.create(
                userId,
                serviceId,
                startTime,
                endTime
        );

        Booking savedBooking =
                bookingRepositoryAdapter.save(bookingToSave);

        assertThat(savedBooking.getId())
                .isNotNull();

        entityManager.flush();
        entityManager.clear();

        Booking foundBooking = bookingRepositoryAdapter
                .findById(savedBooking.getId())
                .orElseThrow();

        assertThat(foundBooking.getId())
                .isEqualTo(savedBooking.getId());

        assertThat(foundBooking.getUserId())
                .isEqualTo(userId);

        assertThat(foundBooking.getServiceId())
                .isEqualTo(serviceId);

        assertThat(foundBooking.getStartTime())
                .isEqualTo(startTime);

        assertThat(foundBooking.getEndTime())
                .isEqualTo(endTime);

        assertThat(foundBooking.getStatus())
                .isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void shouldUpdateBookingStatusThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "adapter-update-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "JPA consultation",
                        "Individual JPA consultation",
                        60,
                        true
                )
        );

        LocalDateTime startTime =
                LocalDateTime.of(2035, 8, 6, 10, 0);

        Booking bookingToSave = Booking.create(
                user.getId(),
                service.getId(),
                startTime,
                startTime.plusMinutes(60)
        );

        Booking savedBooking =
                bookingRepositoryAdapter.save(bookingToSave);

        entityManager.flush();
        entityManager.clear();

        savedBooking.cancel();

        Booking updatedBooking = bookingRepositoryAdapter
                .update(savedBooking)
                .orElseThrow();

        assertThat(updatedBooking.getStatus())
                .isEqualTo(BookingStatus.CANCELLED);

        entityManager.flush();
        entityManager.clear();

        Booking bookingFromDatabase = bookingRepositoryAdapter
                .findById(savedBooking.getId())
                .orElseThrow();

        assertThat(bookingFromDatabase.getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void shouldPersistRescheduledBookingTimeThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "adapter-reschedule-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Reschedule consultation",
                        "Service for reschedule persistence test",
                        60,
                        true
                )
        );

        LocalDateTime originalStartTime =
                LocalDateTime.of(2035, 8, 7, 10, 0);

        LocalDateTime originalEndTime =
                originalStartTime.plusMinutes(60);

        Booking booking = Booking.create(
                user.getId(),
                service.getId(),
                originalStartTime,
                originalEndTime
        );

        Booking savedBooking =
                bookingRepositoryAdapter.save(booking);

        entityManager.flush();
        entityManager.clear();

        LocalDateTime newStartTime =
                LocalDateTime.of(2035, 8, 8, 14, 0);

        LocalDateTime newEndTime =
                newStartTime.plusMinutes(60);

        savedBooking.reschedule(
                newStartTime,
                newEndTime
        );

        bookingRepositoryAdapter
                .update(savedBooking)
                .orElseThrow();

        entityManager.flush();
        entityManager.clear();

        Booking bookingFromDatabase =
                bookingRepositoryAdapter
                        .findById(savedBooking.getId())
                        .orElseThrow();

        assertThat(bookingFromDatabase.getStartTime())
                .isEqualTo(newStartTime);

        assertThat(bookingFromDatabase.getEndTime())
                .isEqualTo(newEndTime);
    }

    @Test
    void shouldThrowExceptionWhenUserDoesNotExist() {
        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Testing consultation",
                        "Individual testing consultation",
                        60,
                        true
                )
        );

        LocalDateTime startTime =
                LocalDateTime.of(2035, 8, 7, 10, 0);

        Booking booking = Booking.create(
                999999L,
                service.getId(),
                startTime,
                startTime.plusMinutes(60)
        );

        assertThatThrownBy(() ->
                bookingRepositoryAdapter.save(booking)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User not found with id: 999999");
    }

    @Test
    void shouldThrowExceptionWhenServiceDoesNotExist() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "missing-service-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        LocalDateTime startTime =
                LocalDateTime.of(2035, 8, 8, 10, 0);

        Booking booking = Booking.create(
                user.getId(),
                999999L,
                startTime,
                startTime.plusMinutes(60)
        );

        assertThatThrownBy(() ->
                bookingRepositoryAdapter.save(booking)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Bookable service not found with id: 999999"
                );
    }

    @Test
    void shouldFindBookingsByStatusThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "adapter-status-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Spring Boot consultation",
                        "Individual Spring Boot consultation",
                        60,
                        true
                )
        );

        LocalDateTime startTime =
                LocalDateTime.of(2035, 8, 9, 10, 0);

        Booking pendingBooking = Booking.create(
                user.getId(),
                service.getId(),
                startTime,
                startTime.plusMinutes(60)
        );

        Booking cancelledBooking = Booking.create(
                user.getId(),
                service.getId(),
                startTime.plusHours(2),
                startTime.plusHours(3)
        );

        cancelledBooking.cancel();

        Booking savedPendingBooking =
                bookingRepositoryAdapter.save(pendingBooking);

        bookingRepositoryAdapter.save(cancelledBooking);

        entityManager.flush();
        entityManager.clear();

        var pendingBookings =
                bookingRepositoryAdapter.findByStatus(
                        BookingStatus.PENDING
                );

        assertThat(pendingBookings)
                .hasSize(1);

        assertThat(pendingBookings.get(0).getId())
                .isEqualTo(savedPendingBooking.getId());

        assertThat(pendingBookings.get(0).getStatus())
                .isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void shouldDeleteBookingThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "adapter-delete-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Docker consultation",
                        "Individual Docker consultation",
                        60,
                        true
                )
        );

        LocalDateTime startTime =
                LocalDateTime.of(2035, 8, 10, 10, 0);

        Booking booking = Booking.create(
                user.getId(),
                service.getId(),
                startTime,
                startTime.plusMinutes(60)
        );

        Booking savedBooking =
                bookingRepositoryAdapter.save(booking);

        entityManager.flush();
        entityManager.clear();

        bookingRepositoryAdapter.deleteById(
                savedBooking.getId()
        );

        entityManager.flush();
        entityManager.clear();

        assertThat(
                bookingRepositoryAdapter.findById(
                        savedBooking.getId()
                )
        ).isEmpty();
    }

    @Test
    void shouldDetectConflictingBookingThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "conflict-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Conflict test service",
                        "Service for testing booking conflicts",
                        60,
                        true
                )
        );

        LocalDateTime existingStartTime =
                LocalDateTime.of(2030, 1, 10, 10, 0);

        Booking existingBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime,
                existingStartTime.plusMinutes(60)
        );

        bookingRepositoryAdapter.save(existingBooking);

        entityManager.flush();
        entityManager.clear();

        boolean conflict =
                bookingRepositoryAdapter.existsConflictingBooking(
                        service.getId(),
                        existingStartTime.plusMinutes(30),
                        existingStartTime.plusMinutes(90)
                );

        assertThat(conflict)
                .isTrue();
    }

    @Test
    void shouldNotDetectConflictForAdjacentBooking() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "adjacent-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Adjacent test service",
                        "Service for testing adjacent bookings",
                        60,
                        true
                )
        );

        LocalDateTime existingStartTime =
                LocalDateTime.of(2030, 1, 11, 10, 0);

        Booking confirmedBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime,
                existingStartTime.plusMinutes(60)
        );

        confirmedBooking.confirm();

        bookingRepositoryAdapter.save(
                confirmedBooking
        );

        entityManager.flush();
        entityManager.clear();

        boolean conflict =
                bookingRepositoryAdapter.existsConflictingBooking(
                        service.getId(),
                        existingStartTime.plusMinutes(60),
                        existingStartTime.plusMinutes(120)
                );

        assertThat(conflict)
                .isFalse();
    }

    @Test
    void shouldNotDetectConflictForCancelledBooking() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "cancelled-conflict-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Cancelled booking test service",
                        "Service for testing cancelled booking conflicts",
                        60,
                        true
                )
        );

        LocalDateTime existingStartTime =
                LocalDateTime.of(2030, 1, 12, 10, 0);

        Booking cancelledBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime,
                existingStartTime.plusMinutes(60)
        );

        cancelledBooking.cancel();

        bookingRepositoryAdapter.save(
                cancelledBooking
        );

        entityManager.flush();
        entityManager.clear();

        boolean conflict =
                bookingRepositoryAdapter.existsConflictingBooking(
                        service.getId(),
                        existingStartTime.plusMinutes(30),
                        existingStartTime.plusMinutes(90)
                );

        assertThat(conflict)
                .isFalse();
    }

    @Test
    void shouldFilterBookingsBySearchCriteria() {
        UserEntity firstUser = entityManager.persistAndFlush(
                new UserEntity(
                        "search-user-one@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        UserEntity secondUser = entityManager.persistAndFlush(
                new UserEntity(
                        "search-user-two@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity firstService =
                entityManager.persistAndFlush(
                        new BookableServiceEntity(
                                "Search service one",
                                "First search test service",
                                60,
                                true
                        )
                );

        BookableServiceEntity secondService =
                entityManager.persistAndFlush(
                        new BookableServiceEntity(
                                "Search service two",
                                "Second search test service",
                                60,
                                true
                        )
                );

        LocalDateTime matchingStartTime =
                LocalDateTime.of(2030, 2, 10, 10, 0);

        Booking matchingBooking =
                bookingRepositoryAdapter.save(
                        Booking.create(
                                firstUser.getId(),
                                firstService.getId(),
                                matchingStartTime,
                                matchingStartTime.plusMinutes(60)
                        )
                );

        Booking confirmedBooking = Booking.create(
                firstUser.getId(),
                firstService.getId(),
                matchingStartTime.plusDays(1),
                matchingStartTime.plusDays(1)
                        .plusMinutes(60)
        );

        confirmedBooking.confirm();

        bookingRepositoryAdapter.save(
                confirmedBooking
        );

        bookingRepositoryAdapter.save(
                Booking.create(
                        secondUser.getId(),
                        secondService.getId(),
                        matchingStartTime.plusDays(2),
                        matchingStartTime.plusDays(2)
                                .plusMinutes(60)
                )
        );

        entityManager.flush();
        entityManager.clear();

        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        BookingStatus.PENDING,
                        firstUser.getId(),
                        firstService.getId(),
                        LocalDateTime.of(2030, 2, 1, 0, 0),
                        LocalDateTime.of(2030, 2, 28, 23, 59)
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        0,
                        10,
                        "startTime",
                        "asc"
                );

        PageResult<Booking> result =
                bookingRepositoryAdapter.search(
                        criteria,
                        pageRequest
                );

        assertThat(result.content())
                .hasSize(1);

        assertThat(result.content().get(0).getId())
                .isEqualTo(matchingBooking.getId());

        assertThat(result.content().get(0).getStatus())
                .isEqualTo(BookingStatus.PENDING);

        assertThat(result.content().get(0).getUserId())
                .isEqualTo(firstUser.getId());

        assertThat(result.content().get(0).getServiceId())
                .isEqualTo(firstService.getId());

        assertThat(result.totalElements())
                .isEqualTo(1);

        assertThat(result.totalPages())
                .isEqualTo(1);

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();
    }

    @Test
    void shouldSortAndPaginateBookings() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "pagination-user@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service =
                entityManager.persistAndFlush(
                        new BookableServiceEntity(
                                "Pagination service",
                                "Service for pagination test",
                                60,
                                true
                        )
                );

        LocalDateTime firstStartTime =
                LocalDateTime.of(2030, 3, 1, 10, 0);

        bookingRepositoryAdapter.save(
                Booking.create(
                        user.getId(),
                        service.getId(),
                        firstStartTime,
                        firstStartTime.plusMinutes(60)
                )
        );

        Booking secondBooking =
                bookingRepositoryAdapter.save(
                        Booking.create(
                                user.getId(),
                                service.getId(),
                                firstStartTime.plusDays(1),
                                firstStartTime.plusDays(1)
                                        .plusMinutes(60)
                        )
                );

        Booking thirdBooking =
                bookingRepositoryAdapter.save(
                        Booking.create(
                                user.getId(),
                                service.getId(),
                                firstStartTime.plusDays(2),
                                firstStartTime.plusDays(2)
                                        .plusMinutes(60)
                        )
                );

        bookingRepositoryAdapter.save(
                Booking.create(
                        user.getId(),
                        service.getId(),
                        firstStartTime.plusDays(3),
                        firstStartTime.plusDays(3)
                                .plusMinutes(60)
                )
        );

                bookingRepositoryAdapter.save(
                        Booking.create(
                                user.getId(),
                                service.getId(),
                                firstStartTime.plusDays(4),
                                firstStartTime.plusDays(4)
                                        .plusMinutes(60)
                        )
                );

        entityManager.flush();
        entityManager.clear();

        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        null,
                        user.getId(),
                        service.getId(),
                        null,
                        null
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        1,
                        2,
                        "startTime",
                        "desc"
                );

        PageResult<Booking> result =
                bookingRepositoryAdapter.search(
                        criteria,
                        pageRequest
                );

        assertThat(result.content())
                .extracting(Booking::getId)
                .containsExactly(
                        thirdBooking.getId(),
                        secondBooking.getId()
                );

        assertThat(result.page())
                .isEqualTo(1);

        assertThat(result.size())
                .isEqualTo(2);

        assertThat(result.totalElements())
                .isEqualTo(5);

        assertThat(result.totalPages())
                .isEqualTo(3);

        assertThat(result.first())
                .isFalse();

        assertThat(result.last())
                .isFalse();
    }

    @Test
    void shouldNotDetectConflictWhenConflictingBookingIsExcluded() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "conflict-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Conflict test service",
                        "Service for testing booking conflicts",
                        60,
                        true
                )
        );

        LocalDateTime existingStartTime =
                LocalDateTime.of(2030, 1, 10, 10, 0);

        Booking existingBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime,
                existingStartTime.plusMinutes(60)
        );

        Booking savedBooking =
                bookingRepositoryAdapter.save(existingBooking);

        entityManager.flush();
        entityManager.clear();

        boolean conflict =
                bookingRepositoryAdapter.existsConflictingBookingExcludingId(
                        savedBooking.getId(),
                        service.getId(),
                        existingStartTime.plusMinutes(30),
                        existingStartTime.plusMinutes(90)
                );

        assertThat(conflict)
                .isFalse();
    }

    @Test
    void shouldDetectConflictWhenAnotherBookingOverlaps() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "conflict-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Conflict test service",
                        "Service for testing booking conflicts",
                        60,
                        true
                )
        );

        LocalDateTime existingStartTime =
                LocalDateTime.of(2030, 1, 10, 10, 0);

        Booking firstBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime,
                existingStartTime.plusMinutes(60)
        );

        Booking secondBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime.plusMinutes(30),
                existingStartTime.plusMinutes(90)
        );

        Booking savedFirstBooking =
                bookingRepositoryAdapter.save(firstBooking);

        bookingRepositoryAdapter.save(secondBooking);

        entityManager.flush();
        entityManager.clear();

        boolean conflict =
                bookingRepositoryAdapter.existsConflictingBookingExcludingId(
                        savedFirstBooking.getId(),
                        service.getId(),
                        existingStartTime.plusMinutes(30),
                        existingStartTime.plusMinutes(90)
                );

        assertThat(conflict)
                .isTrue();
    }

    @Test
    void shouldFindOverlappingBookingsThroughAdapter() {
        UserEntity user = entityManager.persistAndFlush(
                new UserEntity(
                        "conflict-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableServiceEntity service = entityManager.persistAndFlush(
                new BookableServiceEntity(
                        "Conflict test service",
                        "Service for testing booking conflicts",
                        60,
                        true
                )
        );

        LocalDateTime existingStartTime =
                LocalDateTime.of(2035, 8, 5, 10, 0);

        Booking existingBooking = Booking.create(
                user.getId(),
                service.getId(),
                existingStartTime,
                existingStartTime.plusMinutes(60)
        );

        Booking savedBooking =
                bookingRepositoryAdapter.save(existingBooking);

        entityManager.flush();
        entityManager.clear();

        List<Booking> bookings =
                bookingRepositoryAdapter.findOverlappingBookings(
                        service.getId(),
                        LocalDateTime.of(2035, 8, 5, 9, 0),
                        LocalDateTime.of(2035, 8, 5, 18, 0)
                );
        assertThat(bookings)
                .hasSize(1);

        assertThat(bookings.get(0).getId())
                .isEqualTo(savedBooking.getId());

    }


    @Configuration
    @EnableAutoConfiguration
    @EntityScan(
            basePackageClasses = {
                    BookingEntity.class,
                    UserEntity.class,
                    BookableServiceEntity.class
            }
    )
    @EnableJpaRepositories(
            basePackageClasses = BookingJpaRepository.class
    )
    @Import({
            JpaBookingRepositoryAdapter.class,
            BookingMapper.class
    })
    static class TestConfiguration {
    }
}
