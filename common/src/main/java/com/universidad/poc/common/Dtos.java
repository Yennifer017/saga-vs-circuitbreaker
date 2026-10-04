package com.universidad.poc.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTOs compartidos por todos los microservicios del PoC.
 */
public final class Dtos {

    private Dtos() { }

    // ---------- Peticiones de negocio ----------

    public record PurchaseRequest(String customerId, String sku, int quantity, double amount) { }
    public record PaymentRequest(String sagaId, String orderId, String customerId, double amount) { }
    public record ReserveRequest(String sagaId, String orderId, String sku, int quantity) { }
    public record ShippingRequest(String sagaId, String orderId, String customerId) { }

    // ---------- Respuestas de negocio ----------

    public record PaymentResponse(String paymentId, String status, double amount) { }
    public record ReservationResponse(String reservationId, String sku, int quantity, int remainingStock) { }
    public record ShippingResponse(String shipmentId, String status, String trackingCode) { }
    public record OrderResponse(String orderId, String status) { }

    // ---------- Resultado de la Saga ----------

    public record SagaStep(String step, String status, String detail, Instant at) {
        public static SagaStep of(String step, String status, String detail) {
            return new SagaStep(step, status, detail, Instant.now());
        }
    }

    public record SagaResult(String sagaId, String orderId, String approach, String status,
                             String failedStep, String failureType, String reason,
                             List<SagaStep> trace) { }

    // ---------- Coreografia: sobre de evento ----------

    public record EventEnvelope(String eventId, String type, String sagaId, String orderId,
                                Map<String, Object> payload, Instant at) { }
    public record Subscription(String type, String callbackUrl) { }

    // ---------- Utilidades de simulacion de fallos ----------
    public record FailMode(String mode, Integer latencyMs) { }
}
