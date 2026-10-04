package com.universidad.poc.shipping;

import com.universidad.poc.common.Dtos;
import com.universidad.poc.common.EventBusClient;
import com.universidad.poc.common.FailureSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * COREOGRAFIA del Shipping Service.
 */
@Component
public class ChoreographyHandler {

    private static final Logger log = LoggerFactory.getLogger(ChoreographyHandler.class);

    private final EventBusClient bus;
    private final FailureSimulator failures;

    public ChoreographyHandler(RestClient http, FailureSimulator failures,
                               @Value("${poc.event-bus-url}") String busUrl,
                               @Value("${poc.self-url}") String selfUrl) {
        this.failures = failures;
        this.bus = new EventBusClient(http, busUrl, selfUrl, "shipping-service");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        bus.subscribeAll("INVENTORY_RESERVED");
    }

    public void handle(Dtos.EventEnvelope ev) {
        if (!"INVENTORY_RESERVED".equals(ev.type())) {
            return;
        }
        log.info("[coreografia] reaccion a INVENTORY_RESERVED saga={} -> programando envio", ev.sagaId());
        try {
            failures.apply();
            if (failures.isBusinessFailure()) {
                throw new IllegalStateException("No hay cobertura para la direccion");
            }
            String shipmentId = "SHP-" + UUID.randomUUID().toString().substring(0, 8);
            log.info("[coreografia] ENVIO OK saga={} shipmentId={}", ev.sagaId(), shipmentId);
            bus.publish("SHIPPING_SCHEDULED", ev, Map.of("shipmentId", shipmentId));
        } catch (Exception e) {
            log.error("[coreografia] ENVIO FALLIDO saga={} motivo={}", ev.sagaId(), e.getMessage());
            bus.publish("SHIPPING_FAILED", ev, Map.of("reason", e.getMessage()));
        }
    }
}
