package com.devicelk.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Gateway-level wiring: rate-limiter key resolvers, circuit-breaker per-instance defaults,
 * and shared Micrometer registry customization.
 *
 * Routes themselves live in application.yml; this file holds the beans those routes reference
 * by SpEL (e.g. {@code key-resolver: "#{@userKeyResolver}"}) and the per-instance circuit-breaker
 * config that Spring Cloud's CircuitBreaker filter uses when it sees {@code name: foo-cb}.
 */
@Configuration
public class GatewayConfig {

    private static final String USER_HEADER = "X-User-Id";

    /**
     * Rate-limit by authenticated user (X-User-Id is set by JwtClaimsRelayFilter after JWT validation).
     * If the header is missing (unauthenticated traffic that somehow reached a rate-limited route),
     * fall back to the remote IP so we don't accidentally bypass the limiter entirely.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
            if (userId != null && !userId.isBlank()) {
                return Mono.just(userId);
            }
            return Mono.just(remoteIp(exchange));
        };
    }

    /** IP-based fallback resolver for unauthenticated routes (e.g. auth proxy if ever rate-limited). */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(remoteIp(exchange));
    }

    private static String remoteIp(org.springframework.web.server.ServerWebExchange exchange) {
        // Honour XFF first (gateway is typically behind a TLS-terminating LB in prod)
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * Per-route circuit-breaker tuning. The values mirror resilience4j.* in application.yml so
     * the breaker behaves identically whether triggered via the filter or referenced directly.
     * Keep one config per service rather than a single global default — AI is slower than products
     * and needs a looser failure threshold and longer wait state.
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> {
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(30))
                            .permittedNumberOfCallsInHalfOpenState(3)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(5))
                            .build())
                    .build(), "product-service-cb", "ingestion-service-cb");

            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(5)
                            .failureRateThreshold(40)
                            .waitDurationInOpenState(Duration.ofSeconds(60))
                            .permittedNumberOfCallsInHalfOpenState(2)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(10))
                            .build())
                    .build(), "ai-service-cb");
        };
    }

    /** Tags every metric in the registry with the application name. */
    @Bean
    public io.micrometer.core.instrument.config.MeterFilter commonTags() {
    return io.micrometer.core.instrument.config.MeterFilter.commonTags(
        io.micrometer.core.instrument.Tags.of(
            "application", "devicelk-api-gateway",
            "environment", "${spring.profiles.active:default}",
            "instance", "${HOSTNAME:unknown}"
        )
    );
}

    /**
     * Publishes a numeric gauge per breaker — 0=CLOSED, 1=OPEN, 2=HALF_OPEN — under the metric
     * name {@code gateway.circuit.breaker.state}. Resilience4j already exposes its own
     * resilience4j.circuitbreaker.state metric but it's one time-series per (name, state) pair;
     * a single numeric gauge is easier to alert on ("breaker not closed for > N seconds").
     *
     * Returns a bean of a marker type so the gauge registration runs once at startup.
     */
    @Bean
    public CircuitBreakerStateGaugePublisher circuitBreakerStateGauges(
            CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
        return new CircuitBreakerStateGaugePublisher(registry, meterRegistry);
    }

    static class CircuitBreakerStateGaugePublisher {
        CircuitBreakerStateGaugePublisher(CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
            // Register a gauge for each currently-known breaker plus any added later (e.g. on
            // first call through a route — the filter creates breakers lazily).
            registry.getAllCircuitBreakers().forEach(cb -> register(meterRegistry, cb));
            registry.getEventPublisher().onEntryAdded(ev -> register(meterRegistry, ev.getAddedEntry()));
        }

        private static void register(MeterRegistry meterRegistry, CircuitBreaker cb) {
            Gauge.builder("gateway.circuit.breaker.state", cb, c -> switch (c.getState()) {
                        case CLOSED, METRICS_ONLY -> 0d;
                        case OPEN, FORCED_OPEN -> 1d;
                        case HALF_OPEN -> 2d;
                        case DISABLED -> -1d;
                    })
                    .tag("service", cb.getName())
                    .description("0=closed, 1=open, 2=half-open")
                    .register(meterRegistry);
        }
    }
}
