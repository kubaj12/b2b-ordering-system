package io.github.kubaj12.online_store.cartordering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port for server-generated one-time checkout reviews. */
public interface CheckoutReviewStore {
    record ReviewedItem(UUID skuId, int quantity, BigDecimal unitNetPrice, BigDecimal vatRate,
            byte[] priceFingerprint, byte[] vatFingerprint) { }
    record ReviewSnapshot(UUID id, UUID cartId, UUID customerId, byte[] tokenHash, long cartRevision,
            Instant createdAt, Instant expiresAt, List<ReviewedItem> items) { }

    void invalidateActiveForCart(UUID cartId, Instant now);
    /** Unconsumed, non-invalidated snapshots, scoped to the authenticated cart/customer. */
    List<ReviewSnapshot> activeForCart(UUID cartId, UUID customerId);
    void save(ReviewSnapshot review);
}
