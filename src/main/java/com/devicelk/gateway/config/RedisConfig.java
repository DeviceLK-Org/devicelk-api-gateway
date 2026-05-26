package com.devicelk.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Reactive Redis wiring. The connection factory itself is auto-configured by
 * spring-boot-starter-data-redis-reactive from spring.data.redis.* properties;
 * we only need a ReactiveStringRedisTemplate for the health indicator's PING probe.
 *
 * The RequestRateLimiter filter has its own RedisRateLimiter bean that the gateway
 * starter wires up automatically — no additional config needed here.
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
