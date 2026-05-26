package com.devicelk.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds baseline security response headers. Registered at LOWEST_PRECEDENCE so the response
 * is fully formed (status, content-type, body buffer) before we stamp headers on, but the
 * actual write happens via beforeCommit so the headers land before flush.
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders h = exchange.getResponse().getHeaders();
            setIfAbsent(h, "X-Frame-Options", "DENY");
            setIfAbsent(h, "X-Content-Type-Options", "nosniff");
            setIfAbsent(h, "X-XSS-Protection", "1; mode=block");
            setIfAbsent(h, "Referrer-Policy", "strict-origin-when-cross-origin");
            setIfAbsent(h, "Content-Security-Policy", "default-src 'self'");

            // HSTS only when the original request was HTTPS — sending it over plain HTTP is
            // both spec-noncompliant and (during local dev) actively harmful since browsers
            // will refuse to load the site over HTTP for the next year.
            String scheme = exchange.getRequest().getURI().getScheme();
            String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
            boolean isHttps = "https".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(forwardedProto);
            if (isHttps) {
                setIfAbsent(h, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private static void setIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
