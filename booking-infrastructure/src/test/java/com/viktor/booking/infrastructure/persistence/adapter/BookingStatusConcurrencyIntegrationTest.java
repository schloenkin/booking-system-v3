package com.viktor.booking.infrastructure.persistence.adapter;

import com.viktor.booking.application.cache.AvailabilityCache;
import com.viktor.booking.application.repository.BookableServiceRepository;
import com.viktor.booking.application.repository.BookingRepository;
import com.viktor.booking.application.repository.UserRepository;
import com.viktor.booking.application.service.BookingService;
import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.domain.enums.UserRole;
import com.viktor.booking.domain.model.BookableService;
import com.viktor.booking.domain.model.Booking;
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
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

@DataJpaTest
@Testcontainers
@ActiveProfiles("jpa")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ContextConfiguration(
        classes =
                BookingStatusConcurrencyIntegrationTest
                        .TestConfiguration.class
)
class BookingStatusConcurrencyIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldRejectOneOfTwoConcurrentStatusUpdates()
            throws Exception {

        User savedUser = userRepository.save(
                new User(
                        null,
                        "status-concurrency@example.com",
                        "hashed-password",
                        UserRole.USER
                )
        );

        BookableService savedService =
                serviceRepository.save(
                        new BookableService(
                                null,
                                "Status concurrency service",
                                "Service used for status concurrency testing",
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

        Booking savedBooking =
                bookingService.createBooking(
                        savedUser.getId(),
                        savedService.getId(),
                        startTime,
                        startTime.plusHours(1)
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch =
                new CountDownLatch(2);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        Callable<AttemptResult> confirmAttempt = () ->
                changeStatus(
                        savedBooking.getId(),
                        BookingStatus.CONFIRMED,
                        readyLatch,
                        startLatch
                );

        Callable<AttemptResult> cancelAttempt = () ->
                changeStatus(
                        savedBooking.getId(),
                        BookingStatus.CANCELLED,
                        readyLatch,
                        startLatch
                );

        try {
            Future<AttemptResult> confirmFuture =
                    executor.submit(confirmAttempt);

            Future<AttemptResult> cancelFuture =
                    executor.submit(cancelAttempt);

            assertThat(
                    readyLatch.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            startLatch.countDown();

            AttemptResult confirmResult =
                    confirmFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            AttemptResult cancelResult =
                    cancelFuture.get(
                            10,
                            TimeUnit.SECONDS
                    );

            List<AttemptResult> results =
                    List.of(
                            confirmResult,
                            cancelResult
                    );

            long successfulUpdates = results
                    .stream()
                    .filter(AttemptResult::success)
                    .count();

            long optimisticLockFailures = results
                    .stream()
                    .map(AttemptResult::error)
                    .filter(this::isOptimisticLockFailure)
                    .count();

            assertThat(successfulUpdates)
                    .isEqualTo(1);

            assertThat(optimisticLockFailures)
                    .isEqualTo(1);

            Booking finalBooking = bookingRepository
                    .findById(savedBooking.getId())
                    .orElseThrow();

            assertThat(finalBooking.getStatus())
                    .isIn(
                            BookingStatus.CONFIRMED,
                            BookingStatus.CANCELLED
                    );
        } finally {
            executor.shutdownNow();
        }
    }

    private AttemptResult changeStatus(
            Long bookingId,
            BookingStatus targetStatus,
            CountDownLatch readyLatch,
            CountDownLatch startLatch
    ) {
        try {
            TransactionTemplate transactionTemplate =
                    new TransactionTemplate(
                            transactionManager
                    );

            transactionTemplate.executeWithoutResult(
                    transactionStatus -> {
                        Booking booking = bookingRepository
                                .findById(bookingId)
                                .orElseThrow();

                        readyLatch.countDown();

                        await(startLatch);

                        if (targetStatus
                                == BookingStatus.CONFIRMED) {
                            booking.confirm();
                        } else {
                            booking.cancel();
                        }

                        bookingRepository.update(booking)
                                .orElseThrow();
                    }
            );

            return new AttemptResult(
                    true,
                    null
            );
        } catch (RuntimeException exception) {
            return new AttemptResult(
                    false,
                    exception
            );
        }
    }

    private void await(
            CountDownLatch latch
    ) {
        try {
            boolean released =
                    latch.await(
                            5,
                            TimeUnit.SECONDS
                    );

            if (!released) {
                throw new IllegalStateException(
                        "Concurrent status test did not start in time"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Concurrent status test was interrupted",
                    exception
            );
        }
    }

    private boolean isOptimisticLockFailure(
            Throwable throwable
    ) {
        Throwable current = throwable;

        while (current != null) {
            if (current
                    instanceof OptimisticLockingFailureException
                    || current
                    instanceof OptimisticLockException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
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
        AvailabilityCache availabilityCache() {
            return Mockito.mock(AvailabilityCache.class);
        }
    }
}
