package com.viktor.booking.application.service;

import com.viktor.booking.domain.exception.InvalidBookingTimeException;
import com.viktor.booking.application.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import com.viktor.booking.application.exception.UserNotFoundException;
import com.viktor.booking.application.exception.BookableServiceNotFoundException;
import com.viktor.booking.application.repository.UserRepository;
import com.viktor.booking.application.repository.BookableServiceRepository;
import com.viktor.booking.domain.enums.UserRole;
import com.viktor.booking.domain.model.User;
import com.viktor.booking.application.exception.InactiveBookableServiceException;
import com.viktor.booking.domain.model.BookableService;
import com.viktor.booking.application.exception.InvalidBookingDurationException;
import com.viktor.booking.domain.exception.BookingInPastException;
import com.viktor.booking.application.exception.BookingTimeConflictException;
import com.viktor.booking.domain.policy.BookingSchedulePolicy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThat;
import com.viktor.booking.domain.enums.BookingStatus;
import com.viktor.booking.domain.model.Booking;
import com.viktor.booking.domain.exception.BookingCannotBeCancelledException;
import com.viktor.booking.domain.exception.BookingCannotBeConfirmedException;
import com.viktor.booking.domain.exception.BookingCannotBeDeletedException;
import com.viktor.booking.application.exception.InvalidBookingSearchException;
import com.viktor.booking.application.query.BookingSearchCriteria;
import com.viktor.booking.application.query.PageRequestData;
import com.viktor.booking.application.query.PageResult;
import com.viktor.booking.application.cache.AvailabilityCacheInvalidator;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.Optional;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BookableServiceRepository serviceRepository;

    @Mock
    private AvailabilityCacheInvalidator cacheInvalidator;

    @InjectMocks
    private BookingService bookingService;

    @Test
    void shouldRejectBookingWhenEndTimeEqualsStartTime() {
        LocalDateTime startTime = futureStartTime();

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        1L,
                        1L,
                        startTime,
                        startTime
                )
        )
                .isInstanceOf(InvalidBookingTimeException.class)
                .hasMessage(
                        "End time must be after start time"
                );

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldRejectBookingWhenUserDoesNotExist() {
        Long missingUserId = 999999L;

        LocalDateTime startTime = futureStartTime();

        when(userRepository.findById(missingUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        missingUserId,
                        1L,
                        startTime,
                        startTime.plusHours(1)
                )
        )
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found with id: 999999");

        verify(userRepository)
                .findById(missingUserId);

        verifyNoInteractions(bookingRepository);
    }
    @Test
    void shouldRejectBookingWhenServiceDoesNotExist() {
        Long userId = 1L;
        Long missingServiceId = 999999L;

        LocalDateTime startTime = futureStartTime();

        User existingUser = new User(
                userId,
                "booking-user@example.com",
                "hashed-password",
                UserRole.USER
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(existingUser));

        when(serviceRepository.findByIdForUpdate(missingServiceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        userId,
                        missingServiceId,
                        startTime,
                        startTime.plusHours(1)
                )
        )
                .isInstanceOf(
                        BookableServiceNotFoundException.class
                )
                .hasMessage(
                        "Bookable service not found with id: 999999"
                );

        verify(userRepository)
                .findById(userId);

        verify(serviceRepository)
                .findByIdForUpdate(missingServiceId);

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldRejectBookingWhenServiceIsInactive() {
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime startTime = futureStartTime();

        User existingUser = new User(
                userId,
                "booking-user@example.com",
                "hashed-password",
                UserRole.USER
        );

        BookableService inactiveService =
                new BookableService(
                        serviceId,
                        "Inactive consultation",
                        "This service is currently unavailable",
                        60,
                        false
                );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(existingUser));

        when(serviceRepository.findByIdForUpdate(serviceId))
                .thenReturn(Optional.of(inactiveService));

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        userId,
                        serviceId,
                        startTime,
                        startTime.plusHours(1)
                )
        )
                .isInstanceOf(
                        InactiveBookableServiceException.class
                )
                .hasMessage(
                        "Bookable service is inactive with id: 2"
                );

        verify(userRepository)
                .findById(userId);

        verify(serviceRepository)
                .findByIdForUpdate(serviceId);

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldRejectBookingWhenDurationDoesNotMatchServiceDuration() {
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime startTime = futureStartTime();

        User existingUser = new User(
                userId,
                "booking-user@example.com",
                "hashed-password",
                UserRole.USER
        );

        BookableService service = new BookableService(
                serviceId,
                "Java consultation",
                "Individual Java consultation",
                60,
                true
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(existingUser));

        when(serviceRepository.findByIdForUpdate(serviceId))
                .thenReturn(Optional.of(service));

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        userId,
                        serviceId,
                        startTime,
                        startTime.plusMinutes(30)
                )
        )
                .isInstanceOf(
                        InvalidBookingDurationException.class
                )
                .hasMessage(
                        "Booking duration must be exactly 60 minutes"
                );

        verify(userRepository)
                .findById(userId);

        verify(serviceRepository)
                .findByIdForUpdate(serviceId);

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldRejectBookingWhenStartTimeIsInPast() {
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime startTime =
                LocalDateTime.now().minusDays(1);

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        userId,
                        serviceId,
                        startTime,
                        startTime.plusMinutes(60)
                )
        )
                .isInstanceOf(BookingInPastException.class)
                .hasMessage(
                        "Booking start time must be in the future"
                );

        verifyNoInteractions(
                userRepository,
                serviceRepository,
                bookingRepository
        );
    }

    @Test
    void shouldRejectBookingWhenRequestedTimeConflicts() {
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime startTime = futureStartTime();
        LocalDateTime endTime = startTime.plusMinutes(60);

        User existingUser = new User(
                userId,
                "booking-user@example.com",
                "hashed-password",
                UserRole.USER
        );

        BookableService service = new BookableService(
                serviceId,
                "Java consultation",
                "Individual Java consultation",
                60,
                true
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(existingUser));

        when(serviceRepository.findByIdForUpdate(serviceId))
                .thenReturn(Optional.of(service));

        when(bookingRepository.existsConflictingBooking(
                serviceId,
                startTime,
                endTime
        )).thenReturn(true);

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        userId,
                        serviceId,
                        startTime,
                        endTime
                )
        )
                .isInstanceOf(BookingTimeConflictException.class)
                .hasMessage(
                        "Bookable service is already booked "
                                + "for the requested time, service id: 2"
                );

        verify(userRepository)
                .findById(userId);

        verify(serviceRepository)
                .findByIdForUpdate(serviceId);

        verify(bookingRepository)
                .existsConflictingBooking(
                        serviceId,
                        startTime,
                        endTime
                );

        verifyNoMoreInteractions(bookingRepository);
    }

    @Test
    void shouldRejectCancellationWhenBookingIsAlreadyCancelled() {
        Long bookingId = 5L;
        LocalDateTime startTime = futureStartTime();

        Booking cancelledBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.CANCELLED
        );

        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(cancelledBooking));

        assertThatThrownBy(() ->
                bookingService.cancelBookingById(bookingId)
        )
                .isInstanceOf(
                        BookingCannotBeCancelledException.class
                )
                .hasMessage(
                        "Booking cannot be cancelled from status: CANCELLED"
                );

        assertThat(cancelledBooking.getStatus())
                .isEqualTo(BookingStatus.CANCELLED);

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository, never())
                .update(any(Booking.class));
    }

    @Test
    void shouldCancelPendingBooking() {
        Long bookingId = 6L;
        LocalDateTime startTime = futureStartTime();

        Booking pendingBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.PENDING
        );

        Booking cancelledBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.CANCELLED
        );

        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(pendingBooking));

        when(bookingRepository.update(
                pendingBooking
        )).thenReturn(Optional.of(cancelledBooking));

        Optional<Booking> result =
                bookingService.cancelBookingById(bookingId);

        assertThat(result)
                .containsSame(cancelledBooking);

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository)
                .update(pendingBooking);
    }

    @Test
    void shouldCancelConfirmedBooking() {
        Long bookingId = 7L;
        LocalDateTime startTime = futureStartTime();

        Booking confirmedBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.CONFIRMED
        );

        Booking cancelledBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.CANCELLED
        );

        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(confirmedBooking));

        when(bookingRepository.update(
                confirmedBooking
        )).thenReturn(Optional.of(cancelledBooking));

        Optional<Booking> result =
                bookingService.cancelBookingById(bookingId);

        assertThat(result)
                .containsSame(cancelledBooking);

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository)
                .update(confirmedBooking);
    }

    @Test
    void shouldCreateBookingWhenAllBusinessRulesAreSatisfied() {
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime startTime = futureStartTime();
        LocalDateTime endTime = startTime.plusMinutes(60);

        User existingUser = new User(
                userId,
                "booking-user@example.com",
                "hashed-password",
                UserRole.USER
        );

        BookableService activeService = new BookableService(
                serviceId,
                "Java consultation",
                "Individual Java consultation",
                60,
                true
        );

        Booking savedBooking = Booking.restore(
                10L,
                userId,
                serviceId,
                startTime,
                endTime,
                BookingStatus.PENDING
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(existingUser));

        when(serviceRepository.findByIdForUpdate(serviceId))
                .thenReturn(Optional.of(activeService));

        when(bookingRepository.existsConflictingBooking(
                serviceId,
                startTime,
                endTime
        )).thenReturn(false);

        ArgumentCaptor<Booking> bookingCaptor =
                ArgumentCaptor.forClass(Booking.class);

        when(bookingRepository.save(bookingCaptor.capture()))
                .thenReturn(savedBooking);

        Booking result = bookingService.createBooking(
                userId,
                serviceId,
                startTime,
                endTime
        );

        Booking bookingPassedToRepository =
                bookingCaptor.getValue();

        assertThat(bookingPassedToRepository.getId())
                .isNull();

        assertThat(bookingPassedToRepository.getUserId())
                .isEqualTo(userId);

        assertThat(bookingPassedToRepository.getServiceId())
                .isEqualTo(serviceId);

        assertThat(bookingPassedToRepository.getStartTime())
                .isEqualTo(startTime);

        assertThat(bookingPassedToRepository.getEndTime())
                .isEqualTo(endTime);

        assertThat(bookingPassedToRepository.getStatus())
                .isEqualTo(BookingStatus.PENDING);

        assertThat(result)
                .isSameAs(savedBooking);
    }

    @Test
    void shouldConfirmPendingBooking() {
        Long bookingId = 8L;
        LocalDateTime startTime = futureStartTime();

        Booking pendingBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.PENDING
        );

        Booking confirmedBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.CONFIRMED
        );

        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(pendingBooking));

        when(bookingRepository.update(
                pendingBooking
        )).thenReturn(Optional.of(confirmedBooking));

        Optional<Booking> result =
                bookingService.confirmBookingById(bookingId);

        assertThat(pendingBooking.getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);

        assertThat(result)
                .containsSame(confirmedBooking);

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository)
                .update(pendingBooking);
    }

    @Test
    void shouldRejectConfirmationWhenBookingIsCancelled() {
        Long bookingId = 9L;
        LocalDateTime startTime = futureStartTime();

        Booking cancelledBooking = Booking.restore(
                bookingId,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.CANCELLED
        );

        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(cancelledBooking));

        assertThatThrownBy(() ->
                bookingService.confirmBookingById(bookingId)
        )
                .isInstanceOf(
                        BookingCannotBeConfirmedException.class
                )
                .hasMessage(
                        "Booking cannot be confirmed from status: CANCELLED"
                );

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository, never())
                .update(any(Booking.class));
    }

    @Test
    void shouldRejectBookingSearchWhenPageIsNegative() {
        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        -1,
                        20,
                        "startTime",
                        "asc"
                );

        assertThatThrownBy(() ->
                bookingService.searchBookings(
                        criteria,
                        pageRequest
                )
        )
                .isInstanceOf(
                        InvalidBookingSearchException.class
                )
                .hasMessage(
                        "Page number must not be negative"
                );

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldRejectBookingSearchWhenPageSizeIsInvalid() {
        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        0,
                        101,
                        "startTime",
                        "asc"
                );

        assertThatThrownBy(() ->
                bookingService.searchBookings(
                        criteria,
                        pageRequest
                )
        )
                .isInstanceOf(
                        InvalidBookingSearchException.class
                )
                .hasMessage(
                        "Page size must be between 1 and 100"
                );

        verifyNoInteractions(bookingRepository);
    }
    @Test
    void shouldRejectBookingSearchWhenFromIsAfterTo() {
        LocalDateTime from =
                LocalDateTime.of(2030, 2, 1, 0, 0);

        LocalDateTime to =
                LocalDateTime.of(2030, 1, 1, 0, 0);

        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        null,
                        null,
                        null,
                        from,
                        to
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        0,
                        20,
                        "startTime",
                        "asc"
                );

        assertThatThrownBy(() ->
                bookingService.searchBookings(
                        criteria,
                        pageRequest
                )
        )
                .isInstanceOf(
                        InvalidBookingSearchException.class
                )
                .hasMessage(
                        "Date from must not be after date to"
                );

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldReturnFalseWhenBookingToDeleteDoesNotExist() {
        Long bookingId = 70L;

        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.empty());

        boolean result =
                bookingService.deleteBookingById(
                        bookingId
                );

        assertThat(result)
                .isFalse();

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository, never())
                .deleteById(bookingId);
    }

    @Test
    void shouldDeleteCancelledBooking() {
        Long bookingId = 70L;

        Booking cancelledBooking =
                bookingWithStatus(
                        bookingId,
                        BookingStatus.CANCELLED
                );

        when(bookingRepository.findById(bookingId))
                .thenReturn(
                        Optional.of(
                                cancelledBooking
                        )
                );

        boolean result =
                bookingService.deleteBookingById(
                        bookingId
                );

        assertThat(result)
                .isTrue();

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository)
                .deleteById(bookingId);
    }

    @Test
    void shouldRejectDeletingPendingBooking() {
        Long bookingId = 70L;

        Booking pendingBooking =
                bookingWithStatus(
                        bookingId,
                        BookingStatus.PENDING
                );

        when(bookingRepository.findById(bookingId))
                .thenReturn(
                        Optional.of(
                                pendingBooking
                        )
                );

        assertThatThrownBy(() ->
                bookingService.deleteBookingById(
                        bookingId
                )
        )
                .isInstanceOf(
                        BookingCannotBeDeletedException.class
                )
                .hasMessage(
                        "Booking cannot be deleted from status: PENDING"
                );

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository, never())
                .deleteById(bookingId);
    }

    @Test
    void shouldRejectDeletingConfirmedBooking() {
        Long bookingId = 70L;

        Booking confirmedBooking =
                bookingWithStatus(
                        bookingId,
                        BookingStatus.CONFIRMED
                );

        when(bookingRepository.findById(bookingId))
                .thenReturn(
                        Optional.of(
                                confirmedBooking
                        )
                );

        assertThatThrownBy(() ->
                bookingService.deleteBookingById(
                        bookingId
                )
        )
                .isInstanceOf(
                        BookingCannotBeDeletedException.class
                )
                .hasMessage(
                        "Booking cannot be deleted from status: CONFIRMED"
                );

        verify(bookingRepository)
                .findById(bookingId);

        verify(bookingRepository, never())
                .deleteById(bookingId);
    }

    @Test
    void shouldRejectBookingSearchWhenSortFieldIsUnsupported() {
        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        0,
                        20,
                        "passwordHash",
                        "asc"
                );

        assertThatThrownBy(() ->
                bookingService.searchBookings(
                        criteria,
                        pageRequest
                )
        )
                .isInstanceOf(
                        InvalidBookingSearchException.class
                )
                .hasMessage(
                        "Unsupported booking sort field: passwordHash"
                );

        verifyNoInteractions(bookingRepository);
    }
    @Test
    void shouldRejectBookingSearchWhenDirectionIsUnsupported() {
        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        0,
                        20,
                        "startTime",
                        "sideways"
                );

        assertThatThrownBy(() ->
                bookingService.searchBookings(
                        criteria,
                        pageRequest
                )
        )
                .isInstanceOf(
                        InvalidBookingSearchException.class
                )
                .hasMessage(
                        "Sort direction must be asc or desc"
                );

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldSearchBookingsWhenParametersAreValid() {
        LocalDateTime startTime =
                LocalDateTime.of(2030, 1, 15, 10, 0);

        Booking booking = Booking.restore(
                1L,
                1L,
                2L,
                startTime,
                startTime.plusMinutes(60),
                BookingStatus.PENDING
        );

        BookingSearchCriteria criteria =
                new BookingSearchCriteria(
                        BookingStatus.PENDING,
                        1L,
                        2L,
                        LocalDateTime.of(2030, 1, 1, 0, 0),
                        LocalDateTime.of(2030, 1, 31, 23, 59)
                );

        PageRequestData pageRequest =
                new PageRequestData(
                        0,
                        20,
                        "startTime",
                        "asc"
                );

        PageResult<Booking> repositoryResult =
                new PageResult<>(
                        List.of(booking),
                        0,
                        20,
                        1,
                        1,
                        true,
                        true
                );

        when(bookingRepository.search(
                criteria,
                pageRequest
        )).thenReturn(repositoryResult);

        PageResult<Booking> result =
                bookingService.searchBookings(
                        criteria,
                        pageRequest
                );

        assertThat(result)
                .isSameAs(repositoryResult);

        verify(bookingRepository)
                .search(
                        criteria,
                        pageRequest
                );
    }

    @Test
    void shouldRescheduleBookingWhenNewTimeDoesNotConflict() {

        Long bookingId = 1L;
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime oldStartTime = futureStartTime();
        LocalDateTime oldEndTime = oldStartTime.plusMinutes(60);

        LocalDateTime newStartTime = oldStartTime.plusMinutes(30);
        LocalDateTime newEndTime = newStartTime.plusMinutes(60);

        Booking booking = Booking.restore(
                bookingId,
                userId,
                serviceId,
                oldStartTime,
                oldEndTime,
                BookingStatus.PENDING
        );
        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(booking));

        when(bookingRepository.existsConflictingBookingExcludingId(
                bookingId,
                serviceId,
                newStartTime,
                newEndTime
        )).thenReturn(false);

        when(bookingRepository.update(booking))
                .thenReturn(Optional.of(booking));

        Optional<Booking> result =
                bookingService.rescheduleBookingById(
                        bookingId,
                        newStartTime,
                        newEndTime
                );

        assertThat(result).isPresent();

        assertEquals(newStartTime, result.get().getStartTime());
        assertEquals(newEndTime, result.get().getEndTime());

        verify(bookingRepository).findById(bookingId);

        verify(bookingRepository)
                .existsConflictingBookingExcludingId(
                        bookingId,
                        serviceId,
                        newStartTime,
                        newEndTime
                );

        verify(bookingRepository).update(booking);
    }

    @Test
    void shouldRejectRescheduleWhenNewTimeConflicts(){
        Long bookingId = 1L;
        Long userId = 1L;
        Long serviceId = 2L;

        LocalDateTime oldStartTime = futureStartTime();
        LocalDateTime oldEndTime = oldStartTime.plusMinutes(60);

        LocalDateTime newStartTime = oldStartTime.plusMinutes(30);
        LocalDateTime newEndTime = newStartTime.plusMinutes(60);

        Booking booking = Booking.restore(
                bookingId,
                userId,
                serviceId,
                oldStartTime,
                oldEndTime,
                BookingStatus.PENDING
        );
        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(booking));

        when(bookingRepository.existsConflictingBookingExcludingId(
                bookingId,
                serviceId,
                newStartTime,
                newEndTime
        )).thenReturn(true);

        assertEquals(oldStartTime, booking.getStartTime());
        assertEquals(oldEndTime, booking.getEndTime());

        assertThatThrownBy(() ->
                bookingService.rescheduleBookingById(
                        bookingId,
                        newStartTime,
                        newEndTime)
        )
                .isInstanceOf(BookingTimeConflictException.class)
                        .hasMessage(
                                "Bookable service is already booked "
                                + "for the requested time, service id: 2"
                        );

        verify(bookingRepository).findById(bookingId);

        verify(bookingRepository)
                .existsConflictingBookingExcludingId(
                        bookingId,
                        serviceId,
                        newStartTime,
                        newEndTime
                );
        verifyNoMoreInteractions(bookingRepository);
    }

    @Test
    void shouldRejectInvalidTimeBeforeCheckingPastStartTime() {
        LocalDateTime startTime =
                LocalDateTime.now().minusDays(1);

        LocalDateTime endTime = startTime;

        assertThatThrownBy(() ->
                bookingService.createBooking(
                        1L,
                        2L,
                        startTime,
                        endTime
                )
        )
                .isInstanceOf(InvalidBookingTimeException.class)
                .hasMessage(
                        "End time must be after start time"
                );

        verifyNoInteractions(
                userRepository,
                serviceRepository,
                bookingRepository
        );
    }

    private Booking bookingWithStatus(
            Long bookingId,
            BookingStatus status
    ) {
        LocalDateTime startTime =
                LocalDateTime.of(
                        2030,
                        2,
                        1,
                        10,
                        0
                );

        return Booking.restore(
                bookingId,
                7L,
                20L,
                startTime,
                startTime.plusHours(1),
                status
        );
    }

    private LocalDateTime futureStartTime() {
        LocalDate today = LocalDate.now(
                ZoneId.of("Europe/Berlin")
        );

        BookingSchedulePolicy schedulePolicy =
                new BookingSchedulePolicy();

        LocalDate nextWorkingDay =
                schedulePolicy.nextWorkingDay(today);

        return LocalDateTime.of(
                nextWorkingDay,
                LocalTime.of(10, 0)
        );
    }

}
