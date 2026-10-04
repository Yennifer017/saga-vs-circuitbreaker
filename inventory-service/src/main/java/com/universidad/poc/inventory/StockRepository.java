package com.universidad.poc.inventory;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** "Base de datos" propia del Inventory Service. Stock inicial precargado para la demo. */
@Repository
public class StockRepository {

    private final Map<String, AtomicInteger> stock = new ConcurrentHashMap<>(Map.of(
            "SKU-LAPTOP", new AtomicInteger(10),
            "SKU-MOUSE",  new AtomicInteger(50),
            "SKU-RARO",   new AtomicInteger(1)    // producto escaso: provoca el fallo de negocio
    ));

    public int available(String sku) {
        return stock.computeIfAbsent(sku, k -> new AtomicInteger(0)).get();
    }

    /** Reserva atomica: devuelve false si no alcanza el stock. */
    public boolean reserve(String sku, int qty) {
        AtomicInteger counter = stock.computeIfAbsent(sku, k -> new AtomicInteger(0));
        synchronized (counter) {
            if (counter.get() < qty) {
                return false;
            }
            counter.addAndGet(-qty);
            return true;
        }
    }

    /** Devuelve el stock reservado (compensacion). */
    public void release(String sku, int qty) {
        stock.computeIfAbsent(sku, k -> new AtomicInteger(0)).addAndGet(qty);
    }

    public void set(String sku, int qty) {
        stock.computeIfAbsent(sku, k -> new AtomicInteger(0)).set(qty);
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        stock.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
