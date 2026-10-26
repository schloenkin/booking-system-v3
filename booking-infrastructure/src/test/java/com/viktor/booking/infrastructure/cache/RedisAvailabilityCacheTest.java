package com.viktor.booking.infrastructure.cache;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.viktor.booking.application.query.AvailabilitySlot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisAvailabilityCacheTest {

    @Test
    void shouldStoreReadAndEvictAvailability() {

        var redisConfig = new RedisStandaloneConfiguration(
                "localhost", 6380
        );

        var connectionFactory =
                new LettuceConnectionFactory(redisConfig);

        connectionFactory.afterPropertiesSet();

        try {
            var redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();

            var objectMapper = JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .build();

            var cache = new RedisAvailabilityCache(
                    redisTemplate,
                    objectMapper
            );

            Long serviceId = 99999L;
            LocalDate date = LocalDate.of(2099, 1, 1);

            List<AvailabilitySlot> slots = List.of(
                    new AvailabilitySlot(LocalTime.of(9, 0), true),
                    new AvailabilitySlot(LocalTime.of(9, 30), false)
            );

            cache.evict(serviceId, date);

            assertTrue(cache.get(serviceId, date).isEmpty());

            cache.put(serviceId, date, slots);

            assertEquals(
                    slots,
                    cache.get(serviceId, date).orElseThrow()
            );

            cache.evict(serviceId, date);

            assertTrue(cache.get(serviceId, date).isEmpty());

        } finally {
            connectionFactory.destroy();
        }
    }
}
