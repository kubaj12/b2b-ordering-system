package io.github.kubaj12.online_store.cartordering.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.cartordering.application.CartStore;

/** PostgreSQL-backed cart lookup and race-safe lazy creation. */
@Repository
public class JdbcCartStore implements CartStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCartStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Cart findOrCreateActive(UUID customerId) {
        while (true) {
            Instant now = clock.instant();
            jdbc.update("""
                    INSERT INTO cart_ordering_cart(id, customer_id, status, revision, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', 0, ?, ?)
                    ON CONFLICT (customer_id) WHERE status = 'ACTIVE' DO NOTHING
                    """, UUID.randomUUID(), customerId, timestamp(now), timestamp(now));
            var carts = jdbc.query("""
                    SELECT id, customer_id, status, revision, created_at, updated_at, completed_at
                    FROM cart_ordering_cart
                    WHERE customer_id = ? AND status = 'ACTIVE'
                    FOR UPDATE
                    """, (rs, row) -> new Cart(rs.getObject("id", UUID.class),
                    rs.getObject("customer_id", UUID.class), Status.valueOf(rs.getString("status")),
                    rs.getLong("revision"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                    instant(rs.getObject("updated_at", OffsetDateTime.class)),
                    rs.getObject("completed_at", OffsetDateTime.class) == null ? null
                            : instant(rs.getObject("completed_at", OffsetDateTime.class))), customerId);
            if (!carts.isEmpty()) return carts.getFirst();
            // Checkout completed the conflicting cart between the insert and lookup.
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }
}
