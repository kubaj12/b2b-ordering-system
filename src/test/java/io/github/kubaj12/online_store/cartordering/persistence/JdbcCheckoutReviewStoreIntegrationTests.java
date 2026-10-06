package io.github.kubaj12.online_store.cartordering.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kubaj12.online_store.cartordering.application.CheckoutReviewStore;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCheckoutReviewStoreIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final Instant CREATED = Instant.parse("2026-10-06T10:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcCheckoutReviewStore reviews;

    @Test
    void supersedesPriorReviewWhilePreservingRevisionAndCommercialSnapshots() {
        UUID customer = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(customer, "review@example.test", "CUSTOMER", CREATED);
        jdbc.update("""
                INSERT INTO customer_profile(user_id, company_name, nip, billing_street, billing_building_number,
                    billing_postal_code, billing_city, created_at, updated_at)
                VALUES (?, 'Review Company', '1111111111', 'Ulica', '1', '00-001', 'Warszawa', ?, ?)
                """, customer, at(CREATED), at(CREATED));
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",
                product, "Produkt testowy", "Test", at(CREATED), at(CREATED));
        jdbc.update("""
                INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,available_quantity,is_active,created_at,updated_at)
                VALUES(?,?,?,10.00,23.00,5,TRUE,?,?)
                """, sku, product, "REVIEW-SKU", at(CREATED), at(CREATED));
        jdbc.update("""
                INSERT INTO cart_ordering_cart(id,customer_id,status,revision,created_at,updated_at)
                VALUES(?,?,'ACTIVE',3,?,?)
                """, cart, customer, at(CREATED), at(CREATED));
        jdbc.update("""
                INSERT INTO cart_ordering_cart_item(cart_id,sku_id,quantity,created_at,updated_at)
                VALUES(?,?,2,?,?)
                """, cart, sku, at(CREATED), at(CREATED));

        UUID firstReviewId = UUID.randomUUID();
        Instant firstExpires = CREATED.plusSeconds(1800);
        reviews.save(snapshot(firstReviewId, cart, customer, sku, 3, CREATED, firstExpires,
                "10.00", "23.00", (byte) 1));

        Instant updatedAt = CREATED.plusSeconds(60);
        jdbc.update("UPDATE cart_ordering_cart SET revision=4, updated_at=? WHERE id=?", at(updatedAt), cart);
        jdbc.update("UPDATE catalog_sku SET base_net_price=15.00, vat_rate=8.00, updated_at=? WHERE id=?",
                at(updatedAt), sku);
        UUID secondReviewId = UUID.randomUUID();
        reviews.invalidateActiveForCart(cart, updatedAt);
        reviews.save(snapshot(secondReviewId, cart, customer, sku, 4, updatedAt, updatedAt.plusSeconds(1800),
                "15.00", "8.00", (byte) 2));

        var first = jdbc.queryForMap("SELECT cart_revision FROM cart_ordering_checkout_review WHERE id = ?", firstReviewId);
        assertThat(first.get("cart_revision")).isEqualTo(3L);
        var invalidatedAt = jdbc.queryForObject("""
                SELECT invalidated_at FROM cart_ordering_checkout_review WHERE id = ?
                """, (rs, row) -> rs.getObject("invalidated_at", OffsetDateTime.class).toInstant(), firstReviewId);
        assertThat(invalidatedAt).isEqualTo(updatedAt);
        var firstItem = jdbc.queryForMap("SELECT quantity, reviewed_unit_net_price, reviewed_vat_rate FROM " +
                "cart_ordering_checkout_review_item WHERE review_id = ? AND sku_id = ?", firstReviewId, sku);
        assertThat(firstItem.get("quantity")).isEqualTo(2);
        assertThat((BigDecimal) firstItem.get("reviewed_unit_net_price")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) firstItem.get("reviewed_vat_rate")).isEqualByComparingTo("23.00");
        var second = jdbc.queryForMap("SELECT cart_revision, invalidated_at FROM cart_ordering_checkout_review WHERE id = ?", secondReviewId);
        assertThat(second.get("cart_revision")).isEqualTo(4L);
        assertThat(second.get("invalidated_at")).isNull();
        var secondItem = jdbc.queryForMap("SELECT reviewed_unit_net_price, reviewed_vat_rate FROM " +
                "cart_ordering_checkout_review_item WHERE review_id = ? AND sku_id = ?", secondReviewId, sku);
        assertThat((BigDecimal) secondItem.get("reviewed_unit_net_price")).isEqualByComparingTo("15.00");
        assertThat((BigDecimal) secondItem.get("reviewed_vat_rate")).isEqualByComparingTo("8.00");
    }

    private static CheckoutReviewStore.ReviewSnapshot snapshot(UUID id, UUID cart, UUID customer, UUID sku,
            long revision, Instant created, Instant expires, String net, String vat, byte marker) {
        var fingerprint = new byte[32];
        java.util.Arrays.fill(fingerprint, marker);
        var item = new CheckoutReviewStore.ReviewedItem(sku, 2, new BigDecimal(net), new BigDecimal(vat), fingerprint, fingerprint);
        var tokenHash = new byte[32];
        java.util.Arrays.fill(tokenHash, (byte) (marker + 10));
        return new CheckoutReviewStore.ReviewSnapshot(id, cart, customer, tokenHash, revision, created, expires, List.of(item));
    }

    private static OffsetDateTime at(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
}
