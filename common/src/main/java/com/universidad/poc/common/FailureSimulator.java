package com.universidad.poc.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permite forzar el comportamiento de un servicio en caliente para las pruebas de fallos,
 * sin reiniciar nada. Se controla con POST /admin/fail-mode.
 *
 */
public class FailureSimulator {

    private static final Logger log = LoggerFactory.getLogger(FailureSimulator.class);

    private volatile String mode = "none";
    private volatile int latencyMs = 3000;
    private final String serviceName;

    public FailureSimulator(String serviceName) {
        this.serviceName = serviceName;
    }

    public synchronized void set(String mode, Integer latencyMs) {
        this.mode = (mode == null || mode.isBlank()) ? "none" : mode.trim().toLowerCase();
        if (latencyMs != null && latencyMs > 0) {
            this.latencyMs = latencyMs;
        }
        log.warn("[{}] >>> FAIL-MODE cambiado a '{}' (latencia={}ms)", serviceName, this.mode, this.latencyMs);
    }

    public String mode() {
        return mode;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public boolean isBusinessFailure() {
        return "business".equals(mode);
    }

    public void apply() {
        switch (mode) {
            case "error" -> {
                log.error("[{}] fail-mode=error -> lanzando fallo tecnico simulado", serviceName);
                throw new IllegalStateException("Fallo tecnico simulado en " + serviceName);
            }
            case "slow" -> {
                log.warn("[{}] fail-mode=slow -> durmiendo {} ms", serviceName, latencyMs);
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            default -> { /* none | business: nada que hacer aqui */ }
        }
    }
}
