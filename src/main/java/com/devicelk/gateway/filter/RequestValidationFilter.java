package com.devicelk.gateway.filter;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Cheap upfront validation. Catches obvious junk before it reaches downstream services or
 * gets buffered through the rest of the filter chain.
 *
 *  - 413 when Content-Length declares more than 10 MB. Won't catch chunked uploads that
 *    omit the header (those will hit Netty's max-in-memory-size if not streamed); for
 *    those a separate streaming guard would be needed.
 *  - 400 on POST/PUT without Content-Type. Forces clients to be explicit so downstreams
 *    don't have to guess between JSON and form.
 *  - Emits a request-size histogram so we can spot pathologically large payloads.
 */
@Component
public class RequestValidationFilter implements GlobalFilter, Ordered {

    private static final long MAX_BODY_BYTES = 10L * 1024 * 1024;

    private final DistributionSummary requestSize;

    public RequestValidationFilter(MeterRegistry registry) {
        this.requestSize = DistributionSummary.builder("gateway.request.size.bytes")
                .description("Declared Content-Length of incoming requests")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        long contentLength = headers.getContentLength();

        if (contentLength > 0) {
            requestSize.record(contentLength);
        }

        if (contentLength > MAX_BODY_BYTES) {
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }

        HttpMethod method = request.getMethod();
        if ((HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method))
                && headers.getContentType() == null
                // Allow empty bodies through (e.g. a PUT with no payload — odd but legal)
                && contentLength > 0) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // After correlation/JWT/logging but before routing.
        return 0;
    }
}
