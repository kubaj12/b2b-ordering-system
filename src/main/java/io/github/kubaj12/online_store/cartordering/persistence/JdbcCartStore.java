package io.github.kubaj12.online_store.cartordering.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
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

    @Override
    public List<Line> lines(UUID cartId) {
        return jdbc.query("""
                SELECT i.sku_id, s.code, p.name, i.quantity, s.available_quantity,
                       p.is_active AS product_active, s.is_active AS sku_active
                FROM cart_ordering_cart_item i
                JOIN catalog_sku s ON s.id = i.sku_id
                JOIN catalog_product p ON p.id = s.product_id
                WHERE i.cart_id = ? ORDER BY p.name, s.code
                """, (rs, row) -> new Line(rs.getObject("sku_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getInt("quantity"), rs.getInt("available_quantity"),
                rs.getBoolean("product_active"), rs.getBoolean("sku_active")), cartId);
    }

    @Override
    public void add(UUID cartId, UUID skuId, int quantity) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO cart_ordering_cart_item(cart_id, sku_id, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (cart_id, sku_id) DO UPDATE
                SET quantity = cart_ordering_cart_item.quantity + EXCLUDED.quantity, updated_at = EXCLUDED.updated_at
                """, cartId, skuId, quantity, timestamp(now), timestamp(now));
        touch(cartId, now);
    }

    @Override
    public void update(UUID cartId, UUID skuId, int quantity) {
        Instant now = clock.instant();
        jdbc.update("UPDATE cart_ordering_cart_item SET quantity = ?, updated_at = ? WHERE cart_id = ? AND sku_id = ?",
                quantity, timestamp(now), cartId, skuId);
        touch(cartId, now);
    }

    @Override
    public void remove(UUID cartId, UUID skuId) {
        jdbc.update("DELETE FROM cart_ordering_cart_item WHERE cart_id = ? AND sku_id = ?", cartId, skuId);
        touch(cartId, clock.instant());
    }

    private void touch(UUID cartId, Instant now) {
        jdbc.update("UPDATE cart_ordering_cart SET revision = revision + 1, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                timestamp(now), cartId);
    }

    public int countLines(UUID cartId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_ordering_cart_item WHERE cart_id = ?", Integer.class, cartId);
    }

    public Integer quantity(UUID cartId, UUID skuId) {
        return jdbc.query("SELECT quantity FROM cart_ordering_cart_item WHERE cart_id = ? AND sku_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, cartId, skuId);
    }

    @Override
    public Sellability sellability(UUID skuId) {
        return jdbc.query("""
                SELECT p.is_active, s.is_active, s.available_quantity FROM catalog_sku s
                JOIN catalog_product p ON p.id = s.product_id WHERE s.id = ?
                """, rs -> rs.next() ? new Sellability(true, rs.getBoolean(1), rs.getBoolean(2), rs.getInt(3))
                        : new Sellability(false, false, false, 0), skuId);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }
}
