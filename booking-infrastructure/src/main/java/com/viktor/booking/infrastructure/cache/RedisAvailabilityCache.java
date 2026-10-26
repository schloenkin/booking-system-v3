package com.viktor.booking.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viktor.booking.application.cache.AvailabilityCache;
import com.viktor.booking.application.query.AvailabilitySlot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class RedisAvailabilityCache implements AvailabilityCache {
    private static final Logger log =
            LoggerFactory.getLogger(RedisAvailabilityCache.class);

    private static final Duration TTL = Duration.ofSeconds(30);

    private static final TypeReference<List<AvailabilitySlot>> SLOT_LIST_TYPE =
            new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisAvailabilityCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<List<AvailabilitySlot>> get(
            Long serviceId,
            LocalDate date
    ) {
        String json = redisTemplate.opsForValue()
                .get(createKey(serviceId, date));

        if (json == null) {
            return Optional.empty();
        }

        try {
            List<AvailabilitySlot> slots =
                    objectMapper.readValue(json, SLOT_LIST_TYPE);

            return Optional.of(slots);

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize availability cache", e
            );
        }
    }

    @Override
    public void put(
            Long serviceId,
            LocalDate date,
            List<AvailabilitySlot> slots
    ) {
        try {
            String json = objectMapper.writeValueAsString(slots);

            redisTemplate.opsForValue().set(
                    createKey(serviceId, date),
                    json,
                    TTL
            );

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize availability cache", e
            );
        }
    }

    @Override
    public void evict(
            Long serviceId,
            LocalDate date
    ) {
        redisTemplate.delete(createKey(serviceId, date));
    }

    private String createKey(Long serviceId, LocalDate date) {
        return "booking:availability:" + serviceId + ":" + date;
    }
}
