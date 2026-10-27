package com.viktor.booking.infrastructure.persistence.adapter;

import com.viktor.booking.application.exception.BookingTimeConflictException;
import com.viktor.booking.application.repository.BookableServiceRepository;
import com.viktor.booking.application.repository.BookingRepository;
import com.viktor.booking.application.repository.UserRepository;
import com.viktor.booking.application.service.BookingService;
import com.viktor.booking.domain.enums.UserRole;
import com.viktor.booking.domain.model.BookableService;
import com.viktor.booking.domain.model.User;
import com.viktor.booking.infrastructure.persistence.entity.BookableServiceEntity;
import com.viktor.booking.infrastructure.persistence.entity.BookingEntity;
import com.viktor.booking.infrastructure.persistence.entity.UserEntity;
import com.viktor.booking.infrastructure.persistence.mapper.BookableServiceMapper;
import com.viktor.booking.infrastructure.persistence.mapper.BookingMapper;
import com.viktor.booking.infrastructure.persistence.mapper.UserMapper;
import com.viktor.booking.infrastructure.persistence.repository.BookableServiceJpaRepository;
import com.viktor.booking.infrastructure.persistence.repository.BookingJpaRepository;
import com.viktor.booking.infrastructure.persistence.repository.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.viktor.booking.domain.policy.BookingSchedulePolicy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import com.viktor.booking.application.cache.AvailabilityCacheInvalidator;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;


@DataJpaTest
@Testcontainers
@ActiveProfiles("jpa")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ContextConfiguration(
        classes = BookingConcurrencyIntegrationTest.TestConfiguration.class
)
class BookingConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookableServiceRepository serviceRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void shouldCreateOnlyOneBookingWhenTwoRequestsCompeteForSameTime()
            throws Exception {

        User savedUser = userRepository.save(
                new User(
                        null,
                        "concurrency-test@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableService savedService =
                serviceRepository.save(
                        new BookableService(
                                null,
                                "Concurrent booking service",
                                "Service used for concurrency testing",
                                60,
                                true
                        )
                );

        LocalDate today = LocalDate.now(
                ZoneId.of("Europe/Berlin")
        );

        LocalDate bookingDate = new BookingSchedulePolicy()
                .nextWorkingDay(today);

        LocalDateTime startTime = LocalDateTime.of(
                bookingDate,
                LocalTime.of(10, 0)
        );

        LocalDateTime endTime =
                startTime.plusMinutes(60);

        long bookingCountBefore = bookingRepository
                .findAll()
                .stream()
                .filter(booking ->
                        booking.getServiceId()
                                .equals(savedService.getId())
                )
                .count();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch =
                new CountDownLatch(2);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        Callable<AttemptResult> bookingAttempt = () -> {
            readyLatch.countDown();

            boolean started =
                    startLatch.await(5, TimeUnit.SECONDS);

            if (!started) {
                return new AttemptResult(
                        false,
                        new IllegalStateException(
                                "Concurrent test did not start in time"
                        )
                );
            }

            try {
                bookingService.createBooking(
                        savedUser.getId(),
                        savedService.getId(),
                        startTime,
                        endTime
                );

                return new AttemptResult(true, null);
            } catch (RuntimeException exception) {
                return new AttemptResult(false, exception);
            }
        };

        try {
            Future<AttemptResult> firstFuture =
                    executor.submit(bookingAttempt);

            Future<AttemptResult> secondFuture =
                    executor.submit(bookingAttempt);

            assertThat(
                    readyLatch.await(5, TimeUnit.SECONDS)
            ).isTrue();

            startLatch.countDown();

            AttemptResult firstResult =
                    firstFuture.get(10, TimeUnit.SECONDS);

            AttemptResult secondResult =
                    secondFuture.get(10, TimeUnit.SECONDS);

            List<AttemptResult> results =
                    List.of(firstResult, secondResult);

            long successfulRequests = results
                    .stream()
                    .filter(AttemptResult::success)
                    .count();

            long conflictingRequests = results
                    .stream()
                    .map(AttemptResult::error)
                    .filter(
                            BookingTimeConflictException.class::isInstance
                    )
                    .count();

            assertThat(successfulRequests)
                    .isEqualTo(1);

            assertThat(conflictingRequests)
                    .isEqualTo(1);

            assertThat(results)
                    .allMatch(result ->
                            result.success()
                                    || result.error()
                                    instanceof BookingTimeConflictException
                    );

            long bookingCountAfter = bookingRepository
                    .findAll()
                    .stream()
                    .filter(booking ->
                            booking.getServiceId()
                                    .equals(savedService.getId())
                    )
                    .count();

            assertThat(bookingCountAfter)
                    .isEqualTo(bookingCountBefore + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    private record AttemptResult(
            boolean success,
            Throwable error
    ) {
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
            basePackageClasses = {
                    BookingJpaRepository.class,
                    UserJpaRepository.class,
                    BookableServiceJpaRepository.class
            }
    )
    @Import({
            BookingService.class,
            JpaBookingRepositoryAdapter.class,
            JpaUserRepositoryAdapter.class,
            JpaBookableServiceRepositoryAdapter.class,
            BookingMapper.class,
            UserMapper.class,
            BookableServiceMapper.class
    })

    static class TestConfiguration {
        @Bean
        AvailabilityCacheInvalidator availabilityCacheInvalidator() {
            return Mockito.mock(AvailabilityCacheInvalidator.class);
        }
    }

}