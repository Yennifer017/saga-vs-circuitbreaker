package com.universidad.poc.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onUnavailable(DownstreamUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Servicio no disponible, intente mas tarde",
                "service", e.service(),
                "detail", e.getMessage()));
    }

    @ExceptionHandler(BusinessFailureException.class)
    public ResponseEntity<Map<String, Object>> onBusiness(BusinessFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Operacion rechazada por una regla de negocio",
                "service", e.service(),
                "detail", e.getMessage()));
    }
}
