package com.universidad.poc.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cliente del Event Bus compartido por todos los servicios en modo COREOGRAFIA.
 *
 */
public class EventBusClient {

    private static final Logger log = LoggerFactory.getLogger(EventBusClient.class);

    private final RestClient http;
    private final String busUrl;
    private final String selfUrl;
    private final String serviceName;

    public EventBusClient(RestClient http, String busUrl, String selfUrl, String serviceName) {
        this.http = http;
        this.busUrl = busUrl;
        this.selfUrl = selfUrl;
        this.serviceName = serviceName;
    }

    public void subscribeAll(String... types) {
        Thread t = new Thread(() -> {
            for (String type : types) {
                boolean ok = false;
                for (int intento = 1; intento <= 30 && !ok; intento++) {
                    try {
                        http.post().uri(busUrl + "/subscribe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new Dtos.Subscription(type, selfUrl + "/events"))
                            .retrieve().toBodilessEntity();
                        log.info("[coreografia] suscrito a {}", type);
                        ok = true;
                    } catch (Exception e) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!ok) {
                    log.error("[coreografia] NO se pudo suscribir a {} tras 30 intentos. "
                            + "Verifique que el Event Bus este en {}", type, busUrl);
                }
            }
        }, serviceName + "-subscriber");
        t.setDaemon(true);
        t.start();
    }

    public void publish(String type, Dtos.EventEnvelope origen, Map<String, Object> nuevosCampos) {
        Map<String, Object> payload = new HashMap<>();
        if (origen != null && origen.payload() != null) {
            payload.putAll(origen.payload());      // hereda el contexto del pedido
        }
        payload.remove("reason");                  // el motivo de un fallo previo no se hereda
        if (nuevosCampos != null) {
            payload.putAll(nuevosCampos);          // los campos nuevos mandan
        }

        String sagaId = origen != null ? origen.sagaId() : null;
        String orderId = origen != null ? origen.orderId() : null;
        publishRaw(type, sagaId, orderId, payload);
    }

    public void publishRaw(String type, String sagaId, String orderId, Map<String, Object> payload) {
        try {
            http.post().uri(busUrl + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Dtos.EventEnvelope(UUID.randomUUID().toString(), type, sagaId, orderId,
                        payload, Instant.now()))
                .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.error("[coreografia] no se pudo publicar {}: {}", type, e.getMessage());
        }
    }
}
