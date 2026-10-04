package com.universidad.poc.inventory;

import com.universidad.poc.common.Dtos;
import com.universidad.poc.common.FailureSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory Service: descuenta stock (T3 de la Saga) y sabe devolverlo (C3).
 */
@RestController
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final StockRepository repo;
    private final FailureSimulator failures;
    private final ChoreographyHandler choreography;
    private final Map<String, Dtos.ReserveRequest> reservations = new ConcurrentHashMap<>();

    public InventoryController(StockRepository repo, FailureSimulator failures, ChoreographyHandler choreography) {
        this.repo = repo;
        this.failures = failures;
        this.choreography = choreography;
    }

    // ---------- Transaccion T3 ----------
    @PostMapping("/inventory/reserve")
    public ResponseEntity<?> reserve(@RequestBody Dtos.ReserveRequest req) {
        failures.apply();

        if (failures.isBusinessFailure() || !repo.reserve(req.sku(), req.quantity())) {
            int disponible = repo.available(req.sku());
            log.warn("SIN STOCK     saga={} sku={} pedido={} disponible={}",
                    req.sagaId(), req.sku(), req.quantity(), disponible);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Stock insuficiente",
                    "sku", req.sku(),
                    "requested", req.quantity(),
                    "available", disponible,
                    "sagaId", req.sagaId()));
        }

        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        reservations.put(reservationId, req);
        int restante = repo.available(req.sku());
        log.info("RESERVA OK    saga={} reservationId={} sku={} qty={} restante={}",
                req.sagaId(), reservationId, req.sku(), req.quantity(), restante);
        return ResponseEntity.ok(new Dtos.ReservationResponse(reservationId, req.sku(), req.quantity(), restante));
    }

    // ---------- Compensacion C3 ----------
    @PostMapping("/inventory/release")
    public ResponseEntity<?> release(@RequestParam String reservationId,
                                     @RequestParam(required = false) String sagaId) {
        Dtos.ReserveRequest r = reservations.remove(reservationId);
        if (r == null) {
            log.warn("LIBERACION    reservationId={} no encontrado (idempotente, se acepta)", reservationId);
            return ResponseEntity.ok(Map.of("reservationId", reservationId, "status", "RELEASED_NOOP"));
        }
        repo.release(r.sku(), r.quantity());
        log.info("LIBERACION OK saga={} reservationId={} sku={} qty={} stock={} <<< COMPENSACION",
                sagaId, reservationId, r.sku(), r.quantity(), repo.available(r.sku()));
        return ResponseEntity.ok(Map.of("reservationId", reservationId, "status", "RELEASED",
                "sku", r.sku(), "stock", repo.available(r.sku())));
    }

    @GetMapping("/inventory")
    public Map<String, Integer> stock() {
        return repo.snapshot();
    }

    /** Permite fijar el stock antes de una demo (ej. dejarlo en 0 para forzar el fallo). */
    @PostMapping("/inventory/stock")
    public Map<String, Integer> setStock(@RequestParam String sku, @RequestParam int qty) {
        repo.set(sku, qty);
        log.warn(">>> STOCK de {} fijado manualmente en {}", sku, qty);
        return repo.snapshot();
    }

    @PostMapping("/admin/fail-mode")
    public Map<String, Object> failMode(@RequestBody Dtos.FailMode fm) {
        failures.set(fm.mode(), fm.latencyMs());
        return Map.of("service", "inventory-service", "mode", failures.mode(), "latencyMs", failures.latencyMs());
    }

    @GetMapping("/admin/fail-mode")
    public Map<String, Object> currentMode() {
        return Map.of("service", "inventory-service", "mode", failures.mode(), "latencyMs", failures.latencyMs());
    }

    @PostMapping("/events")
    public ResponseEntity<Void> onEvent(@RequestBody Dtos.EventEnvelope ev) {
        choreography.handle(ev);
        return ResponseEntity.accepted().build();
    }
}
