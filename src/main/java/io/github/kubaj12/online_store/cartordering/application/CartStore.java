package io.github.kubaj12.online_store.cartordering.application;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

/** Persistence boundary for a customer's persistent cart lifecycle. */
public interface CartStore {
    enum Status { ACTIVE, COMPLETED }

    record Cart(UUID id, UUID customerId, Status status, long revision,
            Instant createdAt, Instant updatedAt, Instant completedAt) { }
    record Line(UUID skuId, String skuCode, String productName, int quantity,
            int availableQuantity, boolean productActive, boolean skuActive) { }

    /** Returns the existing active cart, creating one atomically when none exists. */
    Cart findOrCreateActive(UUID customerId);
    List<Line> lines(UUID cartId);
    void add(UUID cartId, UUID skuId, int quantity);
    void update(UUID cartId, UUID skuId, int quantity);
    void remove(UUID cartId, UUID skuId);
    int countLines(UUID cartId);
    Integer quantity(UUID cartId, UUID skuId);
    Sellability sellability(UUID skuId);
    record Sellability(boolean found, boolean productActive, boolean skuActive, int stock) { }
}
