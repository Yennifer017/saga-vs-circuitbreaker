package com.universidad.poc.order;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Imprime en consola cada TRANSICION DE ESTADO de los Circuit Breakers.
 */
@Component
public class CircuitBreakerEventLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerEventLogger.class);

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerEventLogger(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        registry.getAllCircuitBreakers().forEach(cb -> {
            cb.getEventPublisher()
              .onStateTransition(e -> log.warn(
                      "***** CIRCUIT BREAKER [{}] : {} -> {} *****",
                      cb.getName(),
                      e.getStateTransition().getFromState(),
                      e.getStateTransition().getToState()))
              .onCallNotPermitted(e -> log.error(
                      "***** CIRCUIT BREAKER [{}] ABIERTO: llamada RECHAZADA sin intentarla *****",
                      cb.getName()))
              .onError(e -> log.error(
                      "      [{}] llamada fallida ({}) - tasa de fallo actual {}%",
                      cb.getName(),
                      e.getThrowable().getClass().getSimpleName(),
                      String.format("%.1f", cb.getMetrics().getFailureRate())))
              .onSlowCallRateExceeded(e -> log.error(
                      "***** CIRCUIT BREAKER [{}] : umbral de llamadas LENTAS superado ({}%) *****",
                      cb.getName(), e.getSlowCallRate()));
            log.info("Circuit Breaker registrado: {} (estado inicial {})", cb.getName(), cb.getState());
        });
    }
}
