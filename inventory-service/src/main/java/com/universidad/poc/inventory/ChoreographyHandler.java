package com.universidad.poc.inventory;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * COREOGRAFIA del Inventory Service.
 */
@Component
public class ChoreographyHandler {

    private static final Logger log = LoggerFactory.getLogger(ChoreographyHandler.class);

    private final EventBusClient bus;
    private final StockRepository repo;
    private final FailureSimulator failures;
    private final Map<String, Dtos.ReserveRequest> bySaga = new ConcurrentHashMap<>();

    public ChoreographyHandler(RestClient http, StockRepository repo, FailureSimulator failures,
                               @Value("${poc.event-bus-url}") String busUrl,
                               @Value("${poc.self-url}") String selfUrl) {
        this.repo = repo;
        this.failures = failures;
        this.bus = new EventBusClient(http, busUrl, selfUrl, "inventory-service");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        bus.subscribeAll("PAYMENT_CHARGED", "SHIPPING_FAILED");
    }

    public void handle(Dtos.EventEnvelope ev) {
        switch (ev.type()) {
            case "PAYMENT_CHARGED" -> onPaymentCharged(ev);
            case "SHIPPING_FAILED" -> onShippingFailed(ev);
            default -> { }
        }
    }

    private void onPaymentCharged(Dtos.EventEnvelope ev) {
        String sku = String.valueOf(ev.payload().getOrDefault("sku", "SKU-LAPTOP"));
        int qty = ((Number) ev.payload().getOrDefault("quantity", 1)).intValue();
        log.info("[coreografia] reaccion a PAYMENT_CHARGED saga={} -> reservando {} x {}", ev.sagaId(), qty, sku);
        try {
            failures.apply();
            if (failures.isBusinessFailure() || !repo.reserve(sku, qty)) {
                throw new IllegalStateException("Stock insuficiente de " + sku
                        + " (solicitado=" + qty + ", disponible=" + repo.available(sku) + ")");
            }
            bySaga.put(ev.sagaId(), new Dtos.ReserveRequest(ev.sagaId(), ev.orderId(), sku, qty));
            log.info("[coreografia] RESERVA OK saga={} restante={}", ev.sagaId(), repo.available(sku));
            bus.publish("INVENTORY_RESERVED", ev, Map.of("sku", sku, "quantity", qty));
        } catch (Exception e) {
            log.error("[coreografia] RESERVA FALLIDA saga={} motivo={}", ev.sagaId(), e.getMessage());
            // Publicar el fallo es lo que hace que Payment y Order se auto-compensen.
            bus.publish("INVENTORY_FAILED", ev, Map.of("reason", e.getMessage()));
        }
    }

    private void onShippingFailed(Dtos.EventEnvelope ev) {
        Dtos.ReserveRequest r = bySaga.remove(ev.sagaId());
        if (r == null) {
            return;
        }
        repo.release(r.sku(), r.quantity());
        log.info("[coreografia] reaccion a SHIPPING_FAILED saga={} -> LIBERANDO {} x {} <<< COMPENSACION",
                ev.sagaId(), r.quantity(), r.sku());
        bus.publish("INVENTORY_RELEASED", ev, Map.of("sku", r.sku(), "quantity", r.quantity()));
    }
}
