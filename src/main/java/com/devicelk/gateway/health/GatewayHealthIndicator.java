package com.devicelk.gateway.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Custom health indicator that surfaces the gateway's two hard dependencies — Keycloak
 * (for token verification) and Redis (for rate-limiting state) — under /actuator/health.
 *
 * Reports DOWN if either probe fails or times out. Spring Boot's built-in Redis indicator
 * also exists; we keep this combined indicator so a single "gateway" component shows up
 * in dashboards instead of forcing operators to look at two unrelated lines.
 */
@Component("gateway")
public class GatewayHealthIndicator implements ReactiveHealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient = WebClient.builder().build();
    private final ReactiveStringRedisTemplate redis;
    private final String keycloakUrl;

    public GatewayHealthIndicator(ReactiveStringRedisTemplate redis,
                                  @Value("${app.health.keycloak-url}") String keycloakUrl) {
        this.redis = redis;
        this.keycloakUrl = keycloakUrl;
    }

    @Override
    public Mono<Health> health() {
        Mono<String> keycloak = webClient.get()
                .uri(keycloakUrl)
                .retrieve()
                // 2xx and 4xx both prove the server is reachable. We only care about connectivity,
                // not whether the realm metadata endpoint requires auth.
                .toBodilessEntity()
                .timeout(PROBE_TIMEOUT)
                .map(r -> "UP")
                .onErrorResume(ex -> Mono.just("DOWN: " + ex.getClass().getSimpleName()));

        Mono<String> redisPing = redis.getConnectionFactory().getReactiveConnection().ping()
                .timeout(PROBE_TIMEOUT)
                .map(pong -> "UP")
                .onErrorResume(ex -> Mono.just("DOWN: " + ex.getClass().getSimpleName()));

        return Mono.zip(keycloak, redisPing).map(t -> {
            boolean ok = t.getT1().equals("UP") && t.getT2().equals("UP");
            Health.Builder b = ok ? Health.up() : Health.down();
            return b.withDetail("keycloak", t.getT1())
                    .withDetail("redis", t.getT2())
                    .build();
        });
    }
}
