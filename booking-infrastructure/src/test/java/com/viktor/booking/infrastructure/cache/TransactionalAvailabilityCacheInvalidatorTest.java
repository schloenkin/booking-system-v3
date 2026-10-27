package com.viktor.booking.infrastructure.cache;

import com.viktor.booking.application.cache.AvailabilityCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDate;

class TransactionalAvailabilityCacheInvalidatorTest {

    @Test
    void shouldEvictCacheOnlyAfterCommit() {

        // Arrange: creating Mock Redis-cach
        AvailabilityCache cache = mock(AvailabilityCache.class);

        TransactionalAvailabilityCacheInvalidator invalidator =
                new TransactionalAvailabilityCacheInvalidator(cache);

        Long serviceId = 1L;
        LocalDate date = LocalDate.of(2026, 10, 27);

        // Testing active Spring transaction
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            // Act: registering deleting of cach
            invalidator.evictAfterCommit(serviceId, date);

            // Assert: before COMMIT cach ist not deleted yet
            verify(cache, never()).evict(serviceId, date);

            // Trigger the afterCommit callbacks.
            TransactionSynchronizationUtils.triggerAfterCommit();

            // Assert: after COMMIT cach is being deleted
            verify(cache, times(1)).evict(serviceId, date);

        } finally {
            // Clear a transaction state
            TransactionSynchronizationManager.clear();
        }
    }
    @Test
    void shouldNotEvictCacheAfterRollback() {

        // Arrange: create a mocked cache.
        AvailabilityCache cache = mock(AvailabilityCache.class);

        TransactionalAvailabilityCacheInvalidator invalidator =
                new TransactionalAvailabilityCacheInvalidator(cache);

        Long serviceId = 1L;
        LocalDate date = LocalDate.of(2026, 10, 27);

        // Simulate an active Spring transaction.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            // Register cache invalidation.
            invalidator.evictAfterCommit(serviceId, date);

            // Simulate a transaction rollback.
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK
            );

            // Verify that the cache has not been invalidated.
            verify(cache, never()).evict(serviceId, date);

        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void shouldThrowExceptionWhenNoTransactionIsActive() {

        // Arrange
        AvailabilityCache cache = mock(AvailabilityCache.class);

        TransactionalAvailabilityCacheInvalidator invalidator =
                new TransactionalAvailabilityCacheInvalidator(cache);

        Long serviceId = 1L;
        LocalDate date = LocalDate.of(2026, 10, 27);

        // Act & Assert
        assertThrows(
                IllegalStateException.class,
                () -> invalidator.evictAfterCommit(serviceId, date)
        );

        verify(cache, never()).evict(serviceId, date);
    }

    @Test
    void shouldNotPropagateCacheFailureAfterCommit() {

        AvailabilityCache cache = mock(AvailabilityCache.class);

        TransactionalAvailabilityCacheInvalidator invalidator =
                new TransactionalAvailabilityCacheInvalidator(cache);

        Long serviceId = 1L;
        LocalDate date = LocalDate.of(2026, 10, 27);

        doThrow(new RuntimeException("Redis is unavailable"))
                .when(cache).evict(serviceId, date);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            invalidator.evictAfterCommit(serviceId, date);

            assertDoesNotThrow(
                    TransactionSynchronizationUtils::triggerAfterCommit
            );

            verify(cache, times(1)).evict(serviceId, date);

        } finally {
            TransactionSynchronizationManager.clear();
        }
    }


}
