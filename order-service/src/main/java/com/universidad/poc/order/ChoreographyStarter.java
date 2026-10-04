package com.universidad.poc.order;

import com.universidad.poc.common.Dtos;
import com.universidad.poc.common.EventBusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SAGA POR COREOGRAFIA
 */
@Component
public class ChoreographyStarter {

    private static final Logger log = LoggerFactory.getLogger(ChoreographyStarter.class);

    private final EventBusClient bus;
    private final OrderRepository orders;
    /** sagaId -> orderId, para saber a que orden aplica cada evento */
    private final Map<String, String> sagaToOrder = new java.util.concurrent.ConcurrentHashMap<>();

    public ChoreographyStarter(RestClient http, OrderRepository orders,
                               @Value("${poc.event-bus-url}") String busUrl,
                               @Value("${poc.self-url}") String selfUrl) {
        this.orders = orders;
        this.bus = new EventBusClient(http, busUrl, selfUrl, "order-service");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        bus.subscribeAll("PAYMENT_FAILED", "INVENTORY_FAILED", "SHIPPING_FAILED", "SHIPPING_SCHEDULED");
    }

    /** Punto de entrada de la Saga coreografiada: crear la orden y publicar el hecho. */
    public Dtos.SagaResult start(Dtos.PurchaseRequest req) {
        String sagaId = "SAGA-" + UUID.randomUUID().toString().substring(0, 8);
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        sagaToOrder.put(sagaId, orderId);
        orders.create(orderId, req.customerId(), req.sku(), req.quantity(), req.amount());

        log.info("══════════ INICIO SAGA {} (coreografia) orden={} ══════════", sagaId, orderId);
        log.info("[T1] Orden creada. Publico ORDER_CREATED y me desentiendo del resto del flujo.");

        bus.publishRaw("ORDER_CREATED", sagaId, orderId, Map.of(
                "customerId", req.customerId(),
                "sku", req.sku(),
                "quantity", req.quantity(),
                "amount", req.amount()));

        // En coreografia la respuesta es ASINCRONA: se acepta y el resultado
        // se consulta despues en GET /sagas/{sagaId}/events.
        return new Dtos.SagaResult(sagaId, orderId, "choreography", "ACCEPTED", null, null,
                "Saga iniciada de forma asincrona. Consulte GET /sagas/" + sagaId + "/events",
                java.util.List.of(Dtos.SagaStep.of("T1-createOrder", "OK", "ORDER_CREATED publicado")));
    }

    public void handle(Dtos.EventEnvelope ev) {
        String orderId = sagaToOrder.get(ev.sagaId());
        if (orderId == null) {
            return;
        }
        switch (ev.type()) {
            case "SHIPPING_SCHEDULED" -> {
                orders.updateStatus(orderId, "COMPLETED");
                log.info("══════════ SAGA {} COMPLETADA (coreografia) ══════════", ev.sagaId());
            }
            case "PAYMENT_FAILED", "INVENTORY_FAILED", "SHIPPING_FAILED" -> {
                // Auto-compensacion de ESTE servicio: cancelar su propia orden.
                orders.updateStatus(orderId, "CANCELLED");
                log.warn("[coreografia] reaccion a {} saga={} -> orden {} CANCELADA <<< COMPENSACION",
                        ev.type(), ev.sagaId(), orderId);
                bus.publishRaw("ORDER_CANCELLED", ev.sagaId(), orderId,
                        Map.of("reason", String.valueOf(ev.payload().getOrDefault("reason", "desconocido"))));
                log.warn("══════════ SAGA {} REVERTIDA (coreografia) ══════════", ev.sagaId());
            }
            default -> { }
        }
    }

}
