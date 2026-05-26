package com.devicelk.gateway.resilience;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Targets for the CircuitBreaker gateway filter. When a service's breaker is OPEN, the
 * filter forwards to /fallback/{service-name} inside this same gateway — keeping the
 * client-facing contract (503 + structured body + Retry-After hint) consistent regardless
 * of whether the downstream is hard-down or just being shed.
 *
 * Retry-After values are advisory: pick them to roughly match the breaker's wait-in-open
 * duration so a polite client doesn't immediately re-trip the breaker.
 */
@RestController
@RequestMapping(value = "/fallback", produces = MediaType.APPLICATION_JSON_VALUE)
public class FallbackController {

    @RequestMapping(value = "/product-service", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<Map<String, Object>>> productService() {
        return unavailable("product-service", 30);
    }

    @RequestMapping(value = "/ai-service", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<Map<String, Object>>> aiService() {
        // Longer hint because the AI breaker waits 60s in open state.
        return unavailable("ai-service", 60);
    }

    @RequestMapping(value = "/ingestion-service", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<Map<String, Object>>> ingestionService() {
        return unavailable("ingestion-service", 30);
    }

    private static Mono<ResponseEntity<Map<String, Object>>> unavailable(String service, int retryAfter) {
        Map<String, Object> body = Map.of(
                "error", service + " unavailable",
                "retryAfter", retryAfter
        );
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(retryAfter))
                .body(body));
    }
}
