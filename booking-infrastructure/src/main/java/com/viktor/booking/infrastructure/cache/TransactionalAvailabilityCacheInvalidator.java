package com.viktor.booking.infrastructure.cache;

import com.viktor.booking.application.cache.AvailabilityCache;
import com.viktor.booking.application.cache.AvailabilityCacheInvalidator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

@Component
public class TransactionalAvailabilityCacheInvalidator
        implements AvailabilityCacheInvalidator {

    private final AvailabilityCache availabilityCache;
    private static final Logger log =
            LoggerFactory.getLogger(TransactionalAvailabilityCacheInvalidator.class);

    public TransactionalAvailabilityCacheInvalidator(
            AvailabilityCache availabilityCache
    ) {
        this.availabilityCache = availabilityCache;
    }

    @Override
    public void evictAfterCommit(Long serviceId, LocalDate date) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Availability cache invalidation requires an active transaction"
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            availabilityCache.evict(serviceId, date);
                        } catch (RuntimeException ex) {
                            log.error(
                                    "Failed to invalidate availability cache after commit. serviceId={}, date={}",
                                    serviceId,
                                    date,
                                    ex
                            );
                        }
                    }
                }
        );
    }
}
