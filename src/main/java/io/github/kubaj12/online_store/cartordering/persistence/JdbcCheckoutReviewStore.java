package io.github.kubaj12.online_store.cartordering.persistence;

import io.github.kubaj12.online_store.cartordering.application.CheckoutReviewStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for immutable, customer-bound checkout review snapshots. */
@Repository
public class JdbcCheckoutReviewStore implements CheckoutReviewStore {
    private final JdbcTemplate jdbc;

    public JdbcCheckoutReviewStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void invalidateActiveForCart(java.util.UUID cartId, Instant now) {
        var timestamp = timestamp(now);
        jdbc.update("UPDATE cart_ordering_checkout_review SET invalidated_at = ?, updated_at = ? " +
                "WHERE cart_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL",
                timestamp, timestamp, cartId);
    }

    @Override
    public void save(ReviewSnapshot review) {
        jdbc.update("""
                INSERT INTO cart_ordering_checkout_review(id, cart_id, customer_id, token_hash, cart_revision,
                    created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, review.id(), review.cartId(), review.customerId(), review.tokenHash(), review.cartRevision(),
                timestamp(review.createdAt()), timestamp(review.createdAt()), timestamp(review.expiresAt()));
        for (var item : review.items()) {
            jdbc.update("""
                    INSERT INTO cart_ordering_checkout_review_item(review_id, sku_id, quantity,
                        reviewed_unit_net_price, reviewed_vat_rate, price_fingerprint, vat_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, review.id(), item.skuId(), item.quantity(), item.unitNetPrice(), item.vatRate(),
                    item.priceFingerprint(), item.vatFingerprint());
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
