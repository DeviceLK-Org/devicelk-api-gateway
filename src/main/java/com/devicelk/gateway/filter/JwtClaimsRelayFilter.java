package com.devicelk.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * After JWT validation, extract the trusted user identity from the token and stamp it onto
 * the request as plain headers (X-User-Id, X-User-Email, X-User-Roles), then strip the raw
 * Authorization header. Downstream services consume the headers and never see the JWT —
 * which means the JWT signing key never leaves the gateway/Keycloak boundary.
 *
 * Order -2: must run after Spring Security's auth filter (which is HIGHEST_PRECEDENCE+1 area)
 * so the security context is already populated, and before the routing filter forwards the request.
 */
@Component
public class JwtClaimsRelayFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(token -> mutate(exchange, token))
                // Unauthenticated public routes (auth, fallback, health) just pass through.
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @SuppressWarnings("unchecked")
    private ServerWebExchange mutate(ServerWebExchange exchange, JwtAuthenticationToken token) {
        Jwt jwt = token.getToken();
        String sub = jwt.getSubject() == null ? "" : jwt.getSubject();
        String email = jwt.getClaimAsString("email");

        String rolesCsv = "";
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            rolesCsv = String.join(",", (Collection<String>) roles);
        }

        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .header(USER_ID, sub)
                .header(USER_ROLES, rolesCsv)
                // Strip the bearer token before forwarding. Downstream services trust the
                // X-User-* headers because the gateway is the only ingress that can set them.
                .headers(h -> h.remove(HttpHeaders.AUTHORIZATION));

        if (email != null) {
            builder.header(USER_EMAIL, email);
        }

        return exchange.mutate().request(builder.build()).build();
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
