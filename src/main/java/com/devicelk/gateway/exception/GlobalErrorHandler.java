package com.devicelk.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catches anything that escapes the filter chain and renders it as a JSON error envelope.
 *
 * Ordered at -1 so this handler sits above Spring Boot's DefaultErrorWebExceptionHandler.
 */
@Component
@Order(-1)
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status;
        String message;

        if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            message = rse.getReason() != null ? rse.getReason() : ex.getMessage();
        } else if (ex instanceof AccessDeniedException) {
            status = HttpStatus.FORBIDDEN;
            message = "You do not have permission to access this resource";
        } else if (ex instanceof JwtValidationException) {
            status = HttpStatus.UNAUTHORIZED;
            message = "Invalid or expired token";
        } else {
            log.error("Unhandled gateway error on [{}] {}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    ex);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "An unexpected error occurred";
        }

        log.warn("Gateway error response: {} {} -> {} {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                status.value(),
                ex.getMessage());

        return writeJsonResponse(exchange, status, message);
    }

    private Mono<Void> writeJsonResponse(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", exchange.getRequest().getPath().value());

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
