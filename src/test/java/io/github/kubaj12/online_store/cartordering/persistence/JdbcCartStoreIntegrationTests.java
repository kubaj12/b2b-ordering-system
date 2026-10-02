package io.github.kubaj12.online_store.cartordering.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

class JdbcCartStoreIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void createsReplacementWhenCheckoutCompletesTheExistingCartBeforeLookup() {
        UUID customerId = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(customerId, "cart@example.test", "CUSTOMER", NOW);
        jdbc.update("""
                INSERT INTO customer_profile(user_id, company_name, nip, billing_street,
                        billing_building_number, billing_postal_code, billing_city, created_at, updated_at)
                VALUES (?, 'Cart Company', '1111111111', 'Ulica', '1', '00-001', 'Warszawa', ?, ?)
                """, customerId, at(NOW), at(NOW));

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UUID firstCartId = new JdbcCartStore(jdbc, clock).findOrCreateActive(customerId).id();
        AtomicBoolean completeBeforeLookup = new AtomicBoolean(true);
        JdbcTemplate interleavedJdbc = new JdbcTemplate(dataSource) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (completeBeforeLookup.getAndSet(false)) {
                    jdbc.update("""
                            UPDATE cart_ordering_cart
                            SET status = 'COMPLETED', completed_at = ?, updated_at = ?
                            WHERE id = ?
                            """, at(NOW), at(NOW), firstCartId);
                }
                return super.query(sql, rowMapper, args);
            }
        };

        var replacement = new JdbcCartStore(interleavedJdbc, clock).findOrCreateActive(customerId);

        assertThat(replacement.id()).isNotEqualTo(firstCartId);
        assertThat(replacement.customerId()).isEqualTo(customerId);
        assertThat(replacement.status()).isEqualTo(io.github.kubaj12.online_store.cartordering.application.CartStore.Status.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT status FROM cart_ordering_cart WHERE id = ?", String.class, firstCartId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_ordering_cart WHERE customer_id = ? AND status = 'ACTIVE'",
                Integer.class, customerId)).isEqualTo(1);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
