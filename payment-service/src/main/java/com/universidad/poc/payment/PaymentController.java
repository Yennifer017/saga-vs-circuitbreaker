package com.universidad.poc.payment;

import com.universidad.poc.common.Dtos;
import com.universidad.poc.common.FailureSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment Service: cobra al cliente (T2 de la Saga) y sabe deshacer el cobro (C2).
 */
@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final Map<String, Dtos.PaymentResponse> payments = new ConcurrentHashMap<>();
    private final FailureSimulator failures;
    private final ChoreographyHandler choreography;

    public PaymentController(FailureSimulator failures, ChoreographyHandler choreography) {
        this.failures = failures;
        this.choreography = choreography;
    }

    @PostMapping("/payments")
    public ResponseEntity<?> charge(@RequestBody Dtos.PaymentRequest req) {
        failures.apply();   // puede lanzar 500 o dormir, segun el fail-mode activo

        if (failures.isBusinessFailure()) {
            log.warn("PAGO RECHAZADO (fallo de negocio simulado) saga={} monto={}", req.sagaId(), req.amount());
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Fondos insuficientes", "sagaId", req.sagaId()));
        }

        String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        Dtos.PaymentResponse res = new Dtos.PaymentResponse(paymentId, "CHARGED", req.amount());
        payments.put(paymentId, res);
        log.info("COBRO OK      saga={} paymentId={} monto={}", req.sagaId(), paymentId, req.amount());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/payments/{id}/refund")
    public ResponseEntity<?> refund(@PathVariable String id, @RequestParam(required = false) String sagaId) {
        Dtos.PaymentResponse p = payments.get(id);
        if (p == null) {
            // Idempotencia: compensar algo inexistente NO es un error en una Saga.
            log.warn("REEMBOLSO     paymentId={} no encontrado (idempotente, se acepta)", id);
            return ResponseEntity.ok(Map.of("paymentId", id, "status", "REFUNDED_NOOP"));
        }
        Dtos.PaymentResponse refunded = new Dtos.PaymentResponse(id, "REFUNDED", p.amount());
        payments.put(id, refunded);
        log.info("REEMBOLSO OK  saga={} paymentId={} monto={} <<< COMPENSACION", sagaId, id, p.amount());
        return ResponseEntity.ok(refunded);
    }

    @GetMapping("/payments")
    public Collection<Dtos.PaymentResponse> all() {
        return payments.values();
    }

    @PostMapping("/admin/fail-mode")
    public Map<String, Object> failMode(@RequestBody Dtos.FailMode fm) {
        failures.set(fm.mode(), fm.latencyMs());
        return Map.of("service", "payment-service", "mode", failures.mode(), "latencyMs", failures.latencyMs());
    }

    @GetMapping("/admin/fail-mode")
    public Map<String, Object> currentMode() {
        return Map.of("service", "payment-service", "mode", failures.mode(), "latencyMs", failures.latencyMs());
    }

    @PostMapping("/events")
    public ResponseEntity<Void> onEvent(@RequestBody Dtos.EventEnvelope ev) {
        choreography.handle(ev);
        return ResponseEntity.accepted().build();
    }
}
