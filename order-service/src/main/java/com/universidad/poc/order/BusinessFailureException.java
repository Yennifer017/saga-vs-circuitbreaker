package com.universidad.poc.order;

/**
 * Fallo de NEGOCIO (4xx): sin stock, sin fondos, sin cobertura.
 */
public class BusinessFailureException extends RuntimeException {

    private final String service;

    public BusinessFailureException(String service, String detail) {
        super("Regla de negocio rechazada por " + service + ": " + detail);
        this.service = service;
    }

    public String service() {
        return service;
    }
}
