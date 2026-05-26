package com.devicelk.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Ensures every request has an X-Correlation-Id. Generated if absent, propagated downstream,
 * echoed on the response, and surfaced in MDC so the structured logger emits it on every line.
 *
 * Runs first (order -3) so all later filters can rely on the ID being present.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HEADER, correlationId)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        // Echo on the response. beforeCommit fires before headers flush, which is the only
        // safe place to mutate response headers in WebFlux.
        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        // MDC must be set on the reactor context, not the thread, since reactive chains
        // hop event-loop threads. We set it for the synchronous part and rely on Micrometer
        // Tracing's ObservationThreadLocalAccessor (auto-configured) to carry it through.
        MDC.put(MDC_KEY, correlationId);
        return chain.filter(mutatedExchange)
                .contextWrite(ctx -> ctx.put(MDC_KEY, correlationId))
                .doFinally(s -> MDC.remove(MDC_KEY));
    }

    @Override
    public int getOrder() {
        return -3;
    }
}
