package com.universidad.poc.payment;

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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChoreographyHandler {

    private static final Logger log = LoggerFactory.getLogger(ChoreographyHandler.class);

    private final EventBusClient bus;
    private final FailureSimulator failures;
    /** sagaId -> paymentId, para saber que compensar cuando llegue un evento de fallo */
    private final Map<String, String> paymentsBySaga = new ConcurrentHashMap<>();

    public ChoreographyHandler(RestClient http, FailureSimulator failures,
                               @Value("${poc.event-bus-url}") String busUrl,
                               @Value("${poc.self-url}") String selfUrl) {
        this.failures = failures;
        this.bus = new EventBusClient(http, busUrl, selfUrl, "payment-service");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        bus.subscribeAll("ORDER_CREATED", "INVENTORY_FAILED", "SHIPPING_FAILED");
    }

    public void handle(Dtos.EventEnvelope ev) {
        switch (ev.type()) {
            case "ORDER_CREATED" -> onOrderCreated(ev);
            case "INVENTORY_FAILED", "SHIPPING_FAILED" -> onDownstreamFailure(ev);
            default -> { }
        }
    }

    private void onOrderCreated(Dtos.EventEnvelope ev) {
        double amount = ((Number) ev.payload().getOrDefault("amount", 0)).doubleValue();
        log.info("[coreografia] reaccion a ORDER_CREATED saga={} -> intentando cobrar {}", ev.sagaId(), amount);
        try {
            failures.apply();
            if (failures.isBusinessFailure()) {
                throw new IllegalStateException("Fondos insuficientes");
            }
            String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
            paymentsBySaga.put(ev.sagaId(), paymentId);
            log.info("[coreografia] COBRO OK saga={} paymentId={}", ev.sagaId(), paymentId);
            // El evento arrastra el contexto del pedido para que Inventory sepa que reservar.
            bus.publish("PAYMENT_CHARGED", ev, Map.of("paymentId", paymentId));
        } catch (Exception e) {
            log.error("[coreografia] COBRO FALLIDO saga={} motivo={}", ev.sagaId(), e.getMessage());
            bus.publish("PAYMENT_FAILED", ev, Map.of("reason", e.getMessage()));
        }
    }

    private void onDownstreamFailure(Dtos.EventEnvelope ev) {
        String paymentId = paymentsBySaga.remove(ev.sagaId());
        if (paymentId == null) {
            return;   // no hay nada que compensar en este servicio
        }
        log.info("[coreografia] reaccion a {} saga={} -> REEMBOLSANDO {} <<< COMPENSACION",
                ev.type(), ev.sagaId(), paymentId);
        bus.publish("PAYMENT_REFUNDED", ev, Map.of("paymentId", paymentId));
    }
}
