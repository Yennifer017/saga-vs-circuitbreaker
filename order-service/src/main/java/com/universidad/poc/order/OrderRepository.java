package com.universidad.poc.order;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class OrderRepository {

    public record Order(String orderId, String customerId, String sku, int quantity,
                        double amount, String status) { }

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public Order create(String orderId, String customerId, String sku, int qty, double amount) {
        Order o = new Order(orderId, customerId, sku, qty, amount, "PENDING");
        orders.put(orderId, o);
        return o;
    }

    public void updateStatus(String orderId, String status) {
        Order o = orders.get(orderId);
        if (o != null) {
            orders.put(orderId, new Order(o.orderId(), o.customerId(), o.sku(), o.quantity(), o.amount(), status));
        }
    }

    public Order find(String orderId) {
        return orders.get(orderId);
    }

    public Collection<Order> all() {
        return orders.values();
    }
}
