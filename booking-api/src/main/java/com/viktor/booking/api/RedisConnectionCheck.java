
package com.viktor.booking.api;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RedisConnectionCheck {

    @Bean
    @ConditionalOnProperty(
            name = "booking.redis.check-enabled",
            havingValue = "true"
    )
    public ApplicationRunner checkRedis(
            StringRedisTemplate redisTemplate
    ) {
        return args -> {

            String key = "booking:v3:test";
            String value = "Hello from Spring Boot v3";

            redisTemplate.opsForValue()
                    .set(key, value, Duration.ofSeconds(60));

            String result = redisTemplate.opsForValue().get(key);

            System.out.println("Redis result: " + result);

            if (!value.equals(result)) {
                throw new IllegalStateException(
                        "Redis read/write check failed"
                );
            }
        };
    }
}
