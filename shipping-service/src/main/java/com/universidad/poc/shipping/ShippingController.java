package com.universidad.poc.shipping;

import com.universidad.poc.common.Dtos;
import com.universidad.poc.common.FailureSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class ShippingController {

    private static final Logger log = LoggerFactory.getLogger(ShippingController.class);

    private final Map<String, Dtos.ShippingResponse> shipments = new ConcurrentHashMap<>();
    private final FailureSimulator failures;
    private final ChoreographyHandler choreography;

    public ShippingController(FailureSimulator failures, ChoreographyHandler choreography) {
        this.failures = failures;
        this.choreography = choreography;
    }

    @PostMapping("/shipping/schedule")
    public ResponseEntity<?> schedule(@RequestBody Dtos.ShippingRequest req) {
        failures.apply();

        if (failures.isBusinessFailure()) {
            log.warn("ENVIO RECHAZADO (fallo de negocio simulado) saga={}", req.sagaId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No hay cobertura para la direccion", "sagaId", req.sagaId()));
        }

        String shipmentId = "SHP-" + UUID.randomUUID().toString().substring(0, 8);
        String tracking = "TRK" + (int) (Math.random() * 900000 + 100000);
        Dtos.ShippingResponse res = new Dtos.ShippingResponse(shipmentId, "SCHEDULED", tracking);
        shipments.put(shipmentId, res);
        log.info("ENVIO OK      saga={} shipmentId={} tracking={}", req.sagaId(), shipmentId, tracking);
        return ResponseEntity.ok(res);
    }

    @DeleteMapping("/shipping/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestParam(required = false) String sagaId) {
        Dtos.ShippingResponse s = shipments.get(id);
        if (s == null) {
            log.warn("CANCELACION   shipmentId={} no encontrado (idempotente, se acepta)", id);
            return ResponseEntity.ok(Map.of("shipmentId", id, "status", "CANCELLED_NOOP"));
        }
        Dtos.ShippingResponse cancelled = new Dtos.ShippingResponse(id, "CANCELLED", s.trackingCode());
        shipments.put(id, cancelled);
        log.info("ENVIO CANCELADO saga={} shipmentId={} <<< COMPENSACION", sagaId, id);
        return ResponseEntity.ok(cancelled);
    }

    @GetMapping("/shipping")
    public Collection<Dtos.ShippingResponse> all() {
        return shipments.values();
    }

    @PostMapping("/admin/fail-mode")
    public Map<String, Object> failMode(@RequestBody Dtos.FailMode fm) {
        failures.set(fm.mode(), fm.latencyMs());
        return Map.of("service", "shipping-service", "mode", failures.mode(), "latencyMs", failures.latencyMs());
    }

    @GetMapping("/admin/fail-mode")
    public Map<String, Object> currentMode() {
        return Map.of("service", "shipping-service", "mode", failures.mode(), "latencyMs", failures.latencyMs());
    }

    @PostMapping("/events")
    public ResponseEntity<Void> onEvent(@RequestBody Dtos.EventEnvelope ev) {
        choreography.handle(ev);
        return ResponseEntity.accepted().build();
    }
}
