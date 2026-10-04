package com.universidad.poc.order;

public class DownstreamUnavailableException extends RuntimeException {

    private final String service;
    private final boolean circuitOpen;

    public DownstreamUnavailableException(String service, String detail, boolean circuitOpen) {
        super("Servicio no disponible: " + service + " (" + detail + ")");
        this.service = service;
        this.circuitOpen = circuitOpen;
    }

    public String service() {
        return service;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}
