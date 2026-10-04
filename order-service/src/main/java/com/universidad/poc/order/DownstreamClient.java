package com.universidad.poc.order;

import com.universidad.poc.common.Dtos;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Cliente hacia los servicios downstream.
 */
@Component
public class DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);

    private final RestClient http;
    private final String paymentUrl;
    private final String inventoryUrl;
    private final String shippingUrl;

    public DownstreamClient(RestClient http,
                            @Value("${poc.payment-url}") String paymentUrl,
                            @Value("${poc.inventory-url}") String inventoryUrl,
                            @Value("${poc.shipping-url}") String shippingUrl) {
        this.http = http;
        this.paymentUrl = paymentUrl;
        this.inventoryUrl = inventoryUrl;
        this.shippingUrl = shippingUrl;
    }

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    @Bulkhead(name = "paymentService")
    public Dtos.PaymentResponse charge(Dtos.PaymentRequest req) {
        log.info("  -> POST {}/payments", paymentUrl);
        return http.post().uri(paymentUrl + "/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(Dtos.PaymentResponse.class);
    }

    /** Fallback: se ejecuta cuando el circuito esta ABIERTO o cuando la llamada falla. */
    private Dtos.PaymentResponse paymentFallback(Dtos.PaymentRequest req, Throwable t) {
        if (t instanceof HttpClientErrorException business) {
            // 4xx = la regla de negocio dijo que no. El servicio esta sano.
            log.warn("  !! paymentService rechazo la operacion por negocio: {}", business.getStatusCode());
            throw new BusinessFailureException("payment-service", extractReason(business));
        }
        log.error("  !! FALLBACK paymentService activado: {}", describe(t));
        throw new DownstreamUnavailableException("payment-service", describe(t), isCircuitOpen(t));
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
    @Retry(name = "inventoryService")
    @Bulkhead(name = "inventoryService")
    public Dtos.ReservationResponse reserve(Dtos.ReserveRequest req) {
        log.info("  -> POST {}/inventory/reserve", inventoryUrl);
        return http.post().uri(inventoryUrl + "/inventory/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(Dtos.ReservationResponse.class);
    }

    private Dtos.ReservationResponse inventoryFallback(Dtos.ReserveRequest req, Throwable t) {
        if (t instanceof HttpClientErrorException business) {
            // 4xx = la regla de negocio dijo que no. El servicio esta sano.
            log.warn("  !! inventoryService rechazo la operacion por negocio: {}", business.getStatusCode());
            throw new BusinessFailureException("inventory-service", extractReason(business));
        }
        log.error("  !! FALLBACK inventoryService activado: {}", describe(t));
        throw new DownstreamUnavailableException("inventory-service", describe(t), isCircuitOpen(t));
    }

    @CircuitBreaker(name = "shippingService", fallbackMethod = "shippingFallback")
    @Retry(name = "shippingService")
    @Bulkhead(name = "shippingService")
    public Dtos.ShippingResponse schedule(Dtos.ShippingRequest req) {
        log.info("  -> POST {}/shipping/schedule", shippingUrl);
        return http.post().uri(shippingUrl + "/shipping/schedule")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(Dtos.ShippingResponse.class);
    }

    private Dtos.ShippingResponse shippingFallback(Dtos.ShippingRequest req, Throwable t) {
        if (t instanceof HttpClientErrorException business) {
            // 4xx = la regla de negocio dijo que no. El servicio esta sano.
            log.warn("  !! shippingService rechazo la operacion por negocio: {}", business.getStatusCode());
            throw new BusinessFailureException("shipping-service", extractReason(business));
        }
        log.error("  !! FALLBACK shippingService activado: {}", describe(t));
        throw new DownstreamUnavailableException("shipping-service", describe(t), isCircuitOpen(t));
    }

    public void refundPayment(String paymentId, String sagaId) {
        log.info("  <- COMPENSANDO: POST {}/payments/{}/refund", paymentUrl, paymentId);
        http.post().uri(paymentUrl + "/payments/" + paymentId + "/refund?sagaId=" + sagaId)
                .retrieve().toBodilessEntity();
    }

    public void releaseInventory(String reservationId, String sagaId) {
        log.info("  <- COMPENSANDO: POST {}/inventory/release", inventoryUrl);
        http.post().uri(inventoryUrl + "/inventory/release?reservationId=" + reservationId + "&sagaId=" + sagaId)
                .retrieve().toBodilessEntity();
    }

    public void cancelShipping(String shipmentId, String sagaId) {
        log.info("  <- COMPENSANDO: DELETE {}/shipping/{}", shippingUrl, shipmentId);
        http.delete().uri(shippingUrl + "/shipping/" + shipmentId + "?sagaId=" + sagaId)
                .retrieve().toBodilessEntity();
    }

    public Map<?, ?> setFailMode(String service, String mode, Integer latencyMs) {
        String url = switch (service) {
            case "payment" -> paymentUrl;
            case "inventory" -> inventoryUrl;
            case "shipping" -> shippingUrl;
            default -> throw new IllegalArgumentException("Servicio desconocido: " + service);
        };
        return http.post().uri(url + "/admin/fail-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Dtos.FailMode(mode, latencyMs))
                .retrieve().body(Map.class);
    }

    private static String extractReason(HttpClientErrorException e) {
        try {
            var body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("error") != null) {
                StringBuilder sb = new StringBuilder(String.valueOf(body.get("error")));
                if (body.get("available") != null) {
                    sb.append(" (solicitado=").append(body.get("requested"))
                      .append(", disponible=").append(body.get("available")).append(")");
                }
                return sb.toString();
            }
        } catch (Exception ignored) { }
        return e.getStatusCode().toString();
    }

    private static boolean isCircuitOpen(Throwable t) {
        return t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException;
    }

    private static String describe(Throwable t) {
        if (t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            return "CIRCUITO ABIERTO: la llamada ni siquiera se intento";
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
