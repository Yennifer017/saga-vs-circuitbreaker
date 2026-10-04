package com.universidad.poc.order;

import com.universidad.poc.common.Dtos;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.*;

@RestController
public class OrderController {

    private final SagaOrchestrator orchestrator;
    private final ChoreographyStarter choreography;
    private final OrderRepository orders;
    private final DownstreamClient client;
    private final CircuitBreakerRegistry cbRegistry;
    private final RestClient http;
    private final String busUrl;

    public OrderController(SagaOrchestrator orchestrator, ChoreographyStarter choreography,
                           OrderRepository orders, DownstreamClient client,
                           CircuitBreakerRegistry cbRegistry, RestClient http,
                           @org.springframework.beans.factory.annotation.Value("${poc.event-bus-url}") String busUrl) {
        this.orchestrator = orchestrator;
        this.choreography = choreography;
        this.orders = orders;
        this.client = client;
        this.cbRegistry = cbRegistry;
        this.http = http;
        this.busUrl = busUrl;
    }

    // ═══════════════ Saga ═══════════════

    @PostMapping("/orders")
    public ResponseEntity<Dtos.SagaResult> createOrder(
            @RequestBody Dtos.PurchaseRequest req,
            @RequestParam(defaultValue = "orchestration") String mode) {

        Dtos.SagaResult result = "choreography".equalsIgnoreCase(mode)
                ? choreography.start(req)
                : orchestrator.execute(req);

        // El codigo HTTP distingue POR QUE se revirtio la Saga:
        //   409 Conflict            -> regla de negocio (el sistema esta sano)
        //   503 Service Unavailable -> dependencia caida o Circuit Breaker abierto
        HttpStatus status = switch (result.status()) {
            case "COMPLETED" -> HttpStatus.CREATED;
            case "ACCEPTED"  -> HttpStatus.ACCEPTED;
            default -> "BUSINESS".equals(result.failureType())
                    ? HttpStatus.CONFLICT
                    : HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(result);
    }

    @DeleteMapping("/orders/{id}")
    public ResponseEntity<?> cancelOrder(@PathVariable String id) {
        OrderRepository.Order o = orders.find(id);
        if (o == null) {
            return ResponseEntity.ok(Map.of("orderId", id, "status", "CANCELLED_NOOP"));
        }
        orders.updateStatus(id, "CANCELLED");
        return ResponseEntity.ok(Map.of("orderId", id, "status", "CANCELLED"));
    }

    @GetMapping("/orders")
    public Collection<OrderRepository.Order> all() {
        return orders.all();
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<?> one(@PathVariable String id) {
        OrderRepository.Order o = orders.find(id);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @GetMapping("/sagas/{sagaId}/events")
    public Object sagaEvents(@PathVariable String sagaId) {
        return http.get().uri(busUrl + "/events?sagaId=" + sagaId).retrieve().body(List.class);
    }

    @PostMapping("/events")
    public ResponseEntity<Void> onEvent(@RequestBody Dtos.EventEnvelope ev) {
        choreography.handle(ev);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/circuit-breakers")
    public List<Map<String, Object>> circuitBreakers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CircuitBreaker cb : cbRegistry.getAllCircuitBreakers()) {
            CircuitBreaker.Metrics m = cb.getMetrics();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", cb.getName());
            row.put("state", cb.getState().name());
            row.put("failureRate", String.format("%.1f%%", m.getFailureRate()));
            row.put("slowCallRate", String.format("%.1f%%", m.getSlowCallRate()));
            row.put("bufferedCalls", m.getNumberOfBufferedCalls());
            row.put("successfulCalls", m.getNumberOfSuccessfulCalls());
            row.put("failedCalls", m.getNumberOfFailedCalls());
            row.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> String.valueOf(r.get("name"))));
        return out;
    }

    @PostMapping("/circuit-breakers/reset")
    public List<Map<String, Object>> reset() {
        cbRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        return circuitBreakers();
    }

    @PostMapping("/admin/fail-mode/{service}")
    public Object failMode(@PathVariable String service,
                           @RequestParam String mode,
                           @RequestParam(required = false) Integer latencyMs) {
        return client.setFailMode(service, mode, latencyMs);
    }
}
