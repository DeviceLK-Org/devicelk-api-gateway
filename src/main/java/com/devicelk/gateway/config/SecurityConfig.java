package com.devicelk.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // @Value("${app.cors.allowed-origins:http://localhost:3000}")
    // private List<String> allowedOrigins;

    // @Value("${app.security.public-paths:#{/api/auth/**,/actuator/health,/actuator/health/**,/actuator/info,/actuator/prometheus,/fallback/**}}")
    // private List<String> publicPaths;

    @Value("#{'${app.cors.allowed-origins:http://localhost:3000}'.split(',')}")
    private List<String> allowedOrigins;

    @Value("#{'${app.security.public-paths:/api/auth/**,/actuator/health,/actuator/health/**,/actuator/info,/actuator/prometheus,/fallback/**}'.split(',')}")
    private List<String> publicPaths;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                // CORS handled here so preflights short-circuit before any auth rule runs.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Stateless JWT — no session, no CSRF token to track.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(publicPaths.toArray(new String[0])).permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()

                        // Write operations on products are admin-only.
                        // .pathMatchers(HttpMethod.POST, "/api/products/**").hasRole("ADMIN")
                        // .pathMatchers(HttpMethod.PUT, "/api/products/**").hasRole("ADMIN")
                        // .pathMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                        // .pathMatchers(HttpMethod.GET, "/api/products/**").hasAnyRole("USER", "ADMIN")

                        .pathMatchers(HttpMethod.GET, "/api/inventory/**").hasAnyRole("USER","ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/inventory/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/inventory/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/inventory/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/inventory/**").hasRole("ADMIN")



                        .pathMatchers("/api/ai/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/api/ingest/**").hasRole("ADMIN")

                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter())))
                .build();
    }

    /**
     * Keycloak places roles under realm_access.roles (a nested claim), not under the standard
     * `scope`/`scp` claim. The default JwtGrantedAuthoritiesConverter only reads top-level
     * scope claims, so we provide a custom converter that walks into realm_access.
     */
    @Bean
    public ReactiveJwtAuthenticationConverterAdapter reactiveJwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        // sub is the canonical user id in Keycloak tokens
        delegate.setPrincipalClaimName("sub");
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Reads roles from Keycloak's realm_access.roles claim and emits ROLE_-prefixed authorities
     * so Spring's hasRole("ADMIN") / hasAuthority("ROLE_ADMIN") helpers match correctly.
     */
    static class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null) {
                return Collections.emptyList();
            }
            Object rolesRaw = realmAccess.get("roles");
            if (!(rolesRaw instanceof Collection<?> roles)) {
                return Collections.emptyList();
            }
            return ((Collection<String>) roles).stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());
        }
    }
}
