package com.universidad.poc.order;

import com.universidad.poc.common.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final DownstreamClient client;
    private final OrderRepository orders;

    public SagaOrchestrator(DownstreamClient client, OrderRepository orders) {
        this.client = client;
        this.orders = orders;
    }

    public Dtos.SagaResult execute(Dtos.PurchaseRequest req) {
        String sagaId = "SAGA-" + UUID.randomUUID().toString().substring(0, 8);
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        List<Dtos.SagaStep> trace = new ArrayList<>();
        Deque<Runnable> compensations = new ArrayDeque<>();

        log.info("══════════ INICIO SAGA {} (orquestacion) orden={} ══════════", sagaId, orderId);

        try {
            // ---------- T1: crear la orden ----------
            orders.create(orderId, req.customerId(), req.sku(), req.quantity(), req.amount());
            trace.add(Dtos.SagaStep.of("T1-createOrder", "OK", "orden " + orderId + " creada en estado PENDING"));
            compensations.push(() -> {
                orders.updateStatus(orderId, "CANCELLED");
                log.info("  <- COMPENSANDO: orden {} marcada como CANCELLED", orderId);
            });
            log.info("[T1] Orden creada: {}", orderId);

            // ---------- T2: cobrar ----------
            Dtos.PaymentResponse pay = client.charge(
                    new Dtos.PaymentRequest(sagaId, orderId, req.customerId(), req.amount()));
            trace.add(Dtos.SagaStep.of("T2-charge", "OK", "cobro " + pay.paymentId() + " por " + pay.amount()));
            compensations.push(() -> client.refundPayment(pay.paymentId(), sagaId));
            log.info("[T2] Cobro realizado: {}", pay.paymentId());

            // ---------- T3: reservar stock ----------
            Dtos.ReservationResponse res = client.reserve(
                    new Dtos.ReserveRequest(sagaId, orderId, req.sku(), req.quantity()));
            trace.add(Dtos.SagaStep.of("T3-reserveStock", "OK",
                    "reserva " + res.reservationId() + ", stock restante " + res.remainingStock()));
            compensations.push(() -> client.releaseInventory(res.reservationId(), sagaId));
            log.info("[T3] Stock reservado: {}", res.reservationId());

            // ---------- T4: programar envio ----------
            Dtos.ShippingResponse shp = client.schedule(
                    new Dtos.ShippingRequest(sagaId, orderId, req.customerId()));
            trace.add(Dtos.SagaStep.of("T4-scheduleShipping", "OK",
                    "envio " + shp.shipmentId() + " tracking " + shp.trackingCode()));
            compensations.push(() -> client.cancelShipping(shp.shipmentId(), sagaId));
            log.info("[T4] Envio programado: {}", shp.shipmentId());

            // ---------- exito ----------
            orders.updateStatus(orderId, "COMPLETED");
            trace.add(Dtos.SagaStep.of("COMMIT", "OK", "orden completada"));
            log.info("══════════ SAGA {} COMPLETADA ══════════", sagaId);
            return new Dtos.SagaResult(sagaId, orderId, "orchestration", "COMPLETED", null, null, null, trace);

        } catch (Exception e) {
            // ---------- fallo: rollback en orden inverso ----------
            String failedStep = "T" + (trace.size() + 1);
            String reason = e.getMessage();
            String failureType = classify(e);
            log.error("!!!! FALLO en {} de la saga {} [{}]: {}", failedStep, sagaId, failureType, reason);
            trace.add(Dtos.SagaStep.of(failedStep, "FAILED", reason));

            log.warn("---------- INICIANDO COMPENSACIONES (orden inverso) ----------");
            int n = 0;
            while (!compensations.isEmpty()) {
                Runnable c = compensations.pop();
                n++;
                try {
                    c.run();
                    trace.add(Dtos.SagaStep.of("C" + (compensations.size() + 1), "COMPENSATED", "compensacion aplicada"));
                } catch (Exception ce) {
                    // Una compensacion que falla deja la Saga en estado inconsistente:
                    // en produccion esto va a una cola de intervencion manual.
                    log.error("  XX La compensacion #{} fallo: {}", n, ce.getMessage());
                    trace.add(Dtos.SagaStep.of("C" + (compensations.size() + 1), "COMPENSATION_FAILED", ce.getMessage()));
                }
            }
            orders.updateStatus(orderId, "CANCELLED");
            log.warn("══════════ SAGA {} REVERTIDA ({} compensaciones) ══════════", sagaId, n);
            return new Dtos.SagaResult(sagaId, orderId, "orchestration", "COMPENSATED",
                    failedStep, failureType, reason, trace);
        }
    }

    /** Clasifica la causa de la reversion; es lo que permite responder 409 o 503 segun el caso. */
    private static String classify(Exception e) {
        if (e instanceof BusinessFailureException) {
            return "BUSINESS";
        }
        if (e instanceof DownstreamUnavailableException d) {
            return d.isCircuitOpen() ? "CIRCUIT_OPEN" : "TECHNICAL";
        }
        return "TECHNICAL";
    }
}
