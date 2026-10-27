package com.viktor.booking.application.service;

import com.viktor.booking.application.cache.AvailabilityCache;
import com.viktor.booking.application.repository.BookingRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.viktor.booking.application.query.AvailabilitySlot;
import com.viktor.booking.domain.model.BookableService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class BookingAvailabilityServiceTest {

    @Mock
    private BookableServiceService serviceService;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private AvailabilityCache availabilityCache;

    @InjectMocks
    private BookingAvailabilityService bookingAvailabilityService;

    @Test
    void shouldReturnAvailabilitySlotsForActiveService() {

        // Arrange
        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);
        when(service.getDurationMinutes()).thenReturn(30);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        // Act
        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(serviceId, date);

        // Assert
        assertEquals(18, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).time());
        assertEquals(LocalTime.of(17, 30), slots.get(17).time());
        assertFalse(slots.get(8).available());
        assertFalse(slots.get(9).available());

        verify(bookingRepository, times(16))
                .existsConflictingBooking(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );

        verify(availabilityCache, times(1)).get(serviceId, date);

        verify(availabilityCache, times(1))
                .put(serviceId, date, slots);
    }

    @Test
    void shouldMarkConflictingSlotAsUnavailable() {

        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);
        when(service.getDurationMinutes()).thenReturn(30);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        LocalDateTime bookedStart = LocalDateTime.of(
                date,
                LocalTime.of(10, 0)
        );

        LocalDateTime bookedEnd = bookedStart.plusMinutes(30);

        when(bookingRepository.existsConflictingBooking(
                eq(serviceId),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenAnswer(invocation -> {

            LocalDateTime start = invocation.getArgument(1);
            LocalDateTime end = invocation.getArgument(2);

            return start.equals(bookedStart)
                    && end.equals(bookedEnd);
        });

        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(serviceId, date);

        AvailabilitySlot bookedSlot = slots.stream()
                .filter(slot -> slot.time().equals(LocalTime.of(10, 0)))
                .findFirst()
                .orElseThrow();

        assertFalse(bookedSlot.available());

        verify(bookingRepository).existsConflictingBooking(
                serviceId,
                bookedStart,
                bookedEnd
        );

        verify(availabilityCache, times(1)).get(serviceId, date);
    }

    @Test
    void shouldReturnAvailabilityWhenRedisIsUnavailable() {

        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);
        when(service.getDurationMinutes()).thenReturn(30);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        when(availabilityCache.get(serviceId, date))
                .thenThrow(new RuntimeException("Redis is unavailable"));

        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(serviceId, date);

        assertEquals(18, slots.size());

        verify(bookingRepository, times(16))
                .existsConflictingBooking(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );
    }

    @Test
    void shouldReturnCachedAvailabilityWithoutQueryingRepository() {

        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        List<AvailabilitySlot> cachedSlots = List.of(
                new AvailabilitySlot(LocalTime.of(9, 0), true),
                new AvailabilitySlot(LocalTime.of(9, 30), false)
        );

        when(availabilityCache.get(serviceId, date))
                .thenReturn(Optional.of(cachedSlots));

        List<AvailabilitySlot> result =
                bookingAvailabilityService.getAvailability(serviceId, date);

        assertEquals(cachedSlots, result);

        verify(availabilityCache).get(serviceId, date);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void shouldReturnAvailabilityWhenCacheWriteFails() {

        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);
        when(service.getDurationMinutes()).thenReturn(30);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        doThrow(new RuntimeException("Redis write failed"))
                .when(availabilityCache)
                .put(eq(serviceId), eq(date), anyList());

        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(serviceId, date);

        assertEquals(18, slots.size());

        verify(availabilityCache).get(serviceId, date);
        verify(availabilityCache).put(eq(serviceId), eq(date), eq(slots));

        verify(bookingRepository, times(16))
                .existsConflictingBooking(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );
    }


}
