package com.devicelk.gateway.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

/**
 * Structured request/response logger. Uses StructuredArguments (logstash-logback-encoder)
 * so fields are emitted as discrete JSON keys, not concatenated into the message string —
 * which means log aggregators can index them without parsing.
 *
 * Does NOT log request/response bodies: bodies routinely contain PII (emails, addresses),
 * and tee-ing them through DataBufferUtils is expensive and complicates back-pressure.
 *
 * Order -1: runs after JWT claims relay (order -2) so X-User-Id is already set, but before
 * the routing filter actually forwards.
 */
@Component
public class GlobalLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalLoggingFilter.class);

    private final MeterRegistry registry;

    public GlobalLoggingFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.nanoTime();
        long startWall = System.currentTimeMillis();
        String userId = request.getHeaders().getFirst(JwtClaimsRelayFilter.USER_ID);
        String correlationId = request.getHeaders().getFirst(CorrelationIdFilter.HEADER);

        if (userId != null) {
            MDC.put("userId", userId);
        }

        log.info("gateway.request",
                StructuredArguments.kv("method", request.getMethod().name()),
                StructuredArguments.kv("path", request.getPath().value()),
                StructuredArguments.kv("correlationId", correlationId),
                StructuredArguments.kv("userId", userId),
                StructuredArguments.kv("route", routeId(exchange)));

        return chain.filter(exchange)
                .doFinally(signal -> {
                    long elapsedNanos = System.nanoTime() - start;
                    long durationMs = System.currentTimeMillis() - startWall;
                    Integer status = exchange.getResponse().getStatusCode() == null
                            ? null : exchange.getResponse().getStatusCode().value();
                    String route = routeId(exchange);

                    log.info("gateway.response",
                            StructuredArguments.kv("method", request.getMethod().name()),
                            StructuredArguments.kv("path", request.getPath().value()),
                            StructuredArguments.kv("status", status),
                            StructuredArguments.kv("durationMs", durationMs),
                            StructuredArguments.kv("correlationId", correlationId),
                            StructuredArguments.kv("route", route));

                    Tags reqTags = Tags.of(
                            "route", route == null ? "unmatched" : route,
                            "method", request.getMethod().name(),
                            "status", status == null ? "0" : status.toString());
                    registry.counter("gateway.requests.total", reqTags).increment();

                    Timer.builder("gateway.request.duration")
                            .tag("route", route == null ? "unmatched" : route)
                            .register(registry)
                            .record(elapsedNanos, TimeUnit.NANOSECONDS);

                    MDC.remove("userId");
                });
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? null : route.getId();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
