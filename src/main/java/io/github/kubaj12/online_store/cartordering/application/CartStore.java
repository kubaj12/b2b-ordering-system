package io.github.kubaj12.online_store.cartordering.application;

import java.time.Instant;
import java.util.UUID;

/** Persistence boundary for a customer's persistent cart lifecycle. */
public interface CartStore {
    enum Status { ACTIVE, COMPLETED }

    record Cart(UUID id, UUID customerId, Status status, long revision,
            Instant createdAt, Instant updatedAt, Instant completedAt) { }

    /** Returns the existing active cart, creating one atomically when none exists. */
    Cart findOrCreateActive(UUID customerId);
}
