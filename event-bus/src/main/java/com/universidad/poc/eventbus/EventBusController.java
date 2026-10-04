package com.universidad.poc.eventbus;

import com.universidad.poc.common.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class EventBusController {

    private static final Logger log = LoggerFactory.getLogger(EventBusController.class);

    private final Map<String, List<String>> subscriptions = new ConcurrentHashMap<>();
    private final List<Dtos.EventEnvelope> journal = new CopyOnWriteArrayList<>();
    private final ExecutorService delivery = Executors.newFixedThreadPool(8);
    private final RestClient http;

    public EventBusController(RestClient http) {
        this.http = http;
    }

    @PostMapping(value = "/subscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> subscribe(@RequestBody Dtos.Subscription sub) {
        subscriptions.computeIfAbsent(sub.type(), k -> new CopyOnWriteArrayList<>());
        List<String> targets = subscriptions.get(sub.type());
        if (!targets.contains(sub.callbackUrl())) {
            targets.add(sub.callbackUrl());
            log.info("SUSCRIPCION  {} -> {}", sub.type(), sub.callbackUrl());
        }
        return Map.of("type", sub.type(), "subscribers", targets.size());
    }

    @PostMapping(value = "/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> publish(@RequestBody Dtos.EventEnvelope in) {
        Dtos.EventEnvelope ev = new Dtos.EventEnvelope(
                in.eventId() == null ? UUID.randomUUID().toString() : in.eventId(),
                in.type(), in.sagaId(), in.orderId(),
                in.payload() == null ? Map.of() : in.payload(),
                Instant.now());
        journal.add(ev);

        List<String> targets = subscriptions.getOrDefault(ev.type(), List.of());
        log.info("EVENTO       {} (saga={}) -> {} suscriptor(es)", ev.type(), ev.sagaId(), targets.size());

        for (String url : targets) {
            delivery.submit(() -> {
                try {
                    http.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ev)
                        .retrieve()
                        .toBodilessEntity();
                } catch (Exception e) {
                    log.error("  entrega fallida de {} a {}: {}", ev.type(), url, e.getMessage());
                }
            });
        }
        return Map.of("eventId", ev.eventId(), "type", ev.type(), "delivered", targets.size());
    }

    @GetMapping("/events")
    public List<Dtos.EventEnvelope> events(@RequestParam(required = false) String sagaId) {
        if (sagaId == null) {
            return journal;
        }
        return journal.stream().filter(e -> sagaId.equals(e.sagaId())).toList();
    }

    @GetMapping("/subscriptions")
    public Map<String, List<String>> subscriptions() {
        return subscriptions;
    }

    @DeleteMapping("/events")
    public Map<String, Object> clear() {
        journal.clear();
        return Map.of("cleared", true);
    }
}
