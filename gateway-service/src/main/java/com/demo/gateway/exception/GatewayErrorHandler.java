package com.demo.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.DefaultErrorWebExceptionHandler;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.DefaultErrorWebExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom global error handler for the reactive gateway.
 *
 * Catches errors not handled by route-level circuit breakers:
 * - Route not found (404)
 * - Upstream connection refused (503)
 * - Gateway timeout (504)
 *
 * Returns a consistent JSON error body for all gateway-level errors.
 */
@Component
public class GatewayErrorHandler extends DefaultErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    public GatewayErrorHandler(ErrorAttributes errorAttributes,
                                WebProperties webProperties,
                                ApplicationContext applicationContext) {
        super(errorAttributes, webProperties.getResources(),
                new org.springframework.boot.autoconfigure.web.ErrorProperties(),
                applicationContext);
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Map<String, Object> errorAttributes = getErrorAttributes(request,
                ErrorAttributeOptions.defaults());

        int statusCode = (int) errorAttributes.getOrDefault("status", 500);
        String message = (String) errorAttributes.getOrDefault("message", "An error occurred");
        String path = (String) errorAttributes.getOrDefault("path", request.path());

        log.error("Gateway error: status={} path={} message={}", statusCode, path, message);

        Map<String, Object> body = new HashMap<>();
        body.put("status", statusCode);
        body.put("error", HttpStatus.resolve(statusCode) != null
                ? HttpStatus.resolve(statusCode).getReasonPhrase()
                : "Error");
        body.put("message", message);
        body.put("path", path);
        body.put("timestamp", Instant.now().toString());

        return ServerResponse
                .status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body));
    }
}
