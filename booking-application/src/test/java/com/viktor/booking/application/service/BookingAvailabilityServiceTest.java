package com.viktor.booking.application.service;

import com.viktor.booking.application.cache.AvailabilityCache;
import com.viktor.booking.application.repository.BookingRepository;
import com.viktor.booking.domain.model.Booking;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;



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

        verify(bookingRepository, times(1))
                .findOverlappingBookings(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );
        verify(bookingRepository, never())
                .existsConflictingBooking(
                        anyLong(),
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

        Booking existingBooking = mock(Booking.class);

        when(existingBooking.getStartTime()).thenReturn(bookedStart);
        when(existingBooking.getEndTime()).thenReturn(bookedEnd);

        when(bookingRepository.findOverlappingBookings(
                eq(serviceId),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(existingBooking));

        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(serviceId, date);

        AvailabilitySlot bookedSlot = slots.stream()
                .filter(slot -> slot.time().equals(LocalTime.of(10, 0)))
                .findFirst()
                .orElseThrow();

        assertFalse(bookedSlot.available());

        verify(bookingRepository, times(1))
                .findOverlappingBookings(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );

        verify(bookingRepository, never())
                .existsConflictingBooking(
                        anyLong(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
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

        verify(bookingRepository, times(1))
                .findOverlappingBookings(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );

        verify(bookingRepository, never())
                .existsConflictingBooking(
                        anyLong(),
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

        verify(bookingRepository, times(1))
                .findOverlappingBookings(
                        eq(serviceId),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );

        verify(bookingRepository, never())
                .existsConflictingBooking(
                        anyLong(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );
    }

    @Test
    void shouldBlockTwoSlotsForOneHourBooking() {
        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);
        when(service.getDurationMinutes()).thenReturn(30);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        Booking existingBooking = mock(Booking.class);

        LocalDateTime bookedStart = LocalDateTime.of(
                date, LocalTime.of(10, 0)
        );

        LocalDateTime bookedEnd = LocalDateTime.of(
                date, LocalTime.of(11, 0)
        );

        when(existingBooking.getStartTime()).thenReturn(bookedStart);
        when(existingBooking.getEndTime()).thenReturn(bookedEnd);

        when(bookingRepository.findOverlappingBookings(
                eq(serviceId),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(existingBooking));

        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(serviceId, date);

        assertFalse(findSlot(slots, LocalTime.of(10, 0)).available());
        assertFalse(findSlot(slots, LocalTime.of(10, 30)).available());

        assertTrue(findSlot(slots, LocalTime.of(9, 30)).available());
        assertTrue(findSlot(slots, LocalTime.of(11, 0)).available());

        verify(bookingRepository, times(1))
                .findOverlappingBookings(
                        eq(serviceId),
                        eq(date.atStartOfDay()),
                        eq(date.plusDays(1).atStartOfDay())
                );

        verify(bookingRepository, never())
                .existsConflictingBooking(
                        anyLong(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class)
                );
    }

    private AvailabilitySlot findSlot(
            List<AvailabilitySlot> slots,
            LocalTime time
    ) {
        return slots.stream()
                .filter(slot -> slot.time().equals(time))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldDemonstrateStaleCacheWriteAfterConcurrentEviction()
            throws Exception {

        Long serviceId = 1L;

        LocalDate date = LocalDate.now(ZoneId.of("Europe/Berlin"))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);
        when(service.getDurationMinutes()).thenReturn(30);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        AtomicReference<List<AvailabilitySlot>> cache =
                new AtomicReference<>();

        when(availabilityCache.get(serviceId, date))
                .thenAnswer(invocation ->
                        Optional.ofNullable(cache.get())
                );

        doAnswer(invocation -> {
            List<AvailabilitySlot> slots = invocation.getArgument(2);
            cache.set(slots);
            return null;
        }).when(availabilityCache).put(
                eq(serviceId),
                eq(date),
                anyList()
        );

        doAnswer(invocation -> {
            cache.set(null);
            return null;
        }).when(availabilityCache).evict(serviceId, date);

        CountDownLatch databaseReadStarted = new CountDownLatch(1);
        CountDownLatch allowCalculationToContinue = new CountDownLatch(1);

        when(bookingRepository.findOverlappingBookings(
                eq(serviceId),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenAnswer(invocation -> {

            // Simulate an old database snapshot.
            databaseReadStarted.countDown();

            if (!allowCalculationToContinue.await(
                    5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test");
            }

            return List.of();
        });

        CompletableFuture<List<AvailabilitySlot>> request =
                CompletableFuture.supplyAsync(() ->
                        bookingAvailabilityService.getAvailability(
                                serviceId, date
                        )
                );

        try {
            assertTrue(databaseReadStarted.await(
                    5, TimeUnit.SECONDS
            ));

            // Another transaction commits and invalidates the cache.
            availabilityCache.evict(serviceId, date);

        } finally {
            allowCalculationToContinue.countDown();
        }

        request.get(5, TimeUnit.SECONDS);

            // Known limitation:
            // An in-flight calculation can repopulate the cache
            // with stale availability after eviction.
            // Redis TTL limits the duration of this inconsistency.
        assertNotNull(cache.get());

    }

    @Test
    void shouldNotReturnCachedSlotsForToday() {
        Long serviceId = 1L;

        LocalDate today = LocalDate.now(
                ZoneId.of("Europe/Berlin")
        );

        BookableService service = mock(BookableService.class);

        when(service.isActive()).thenReturn(true);

        when(serviceService.getServiceById(serviceId))
                .thenReturn(Optional.of(service));

        List<AvailabilitySlot> slots =
                bookingAvailabilityService.getAvailability(
                        serviceId,
                        today
                );

        assertEquals(18, slots.size());

        assertTrue(slots.stream()
                .noneMatch(AvailabilitySlot::available));

        verifyNoInteractions(availabilityCache);
        verifyNoInteractions(bookingRepository);
    }

}
