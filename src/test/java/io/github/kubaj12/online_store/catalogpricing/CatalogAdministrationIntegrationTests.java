package io.github.kubaj12.online_store.catalogpricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.catalogpricing.application.CatalogAdministrationService;
import io.github.kubaj12.online_store.catalogpricing.application.CatalogException;
import io.github.kubaj12.online_store.catalogpricing.persistence.JdbcCatalogStore;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogAdministrationIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final UUID ACTOR = UUID.fromString("01998e62-e700-7000-8000-000000000001");

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogAdministrationService catalog;
    @Autowired JdbcCatalogStore store;
    @Autowired TransactionTemplate transactions;

    private UUID productId;
    private UUID skuId;

    @BeforeEach
    void createCatalogFixture() {
        testClock().set(NOW);
        new IdentityDatabaseFixture(jdbc).insertActiveUser(ACTOR, "catalog.employee@example.test", "EMPLOYEE", NOW);
        productId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",
                productId, "Produkt", "Akcesoria", at(NOW), at(NOW));
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,is_active,created_at,updated_at) VALUES(?,?,?, ?, ?,TRUE,?,?)",
                skuId, productId, "SKU-1", new BigDecimal("10.00"), new BigDecimal("23.00"), at(NOW), at(NOW));
    }

    @Test
    void priceAndVatChangesPersistOldAndNewValuesWithActor() {
        catalog.changeBasePriceAndVat(skuId, new BigDecimal("12.35"), new BigDecimal("8.00"), ACTOR);

        var auditRow = jdbc.queryForMap("""
                SELECT acting_user_id, target_id, event_type,
                    change_metadata #>> '{baseNetPrice,from}' old_price,
                    change_metadata #>> '{baseNetPrice,to}' new_price,
                    change_metadata #>> '{vatRate,from}' old_vat,
                    change_metadata #>> '{vatRate,to}' new_vat
                FROM audit_event WHERE target_id=?
                """, skuId.toString());
        assertThat(auditRow)
                .containsEntry("acting_user_id", ACTOR)
                .containsEntry("target_id", skuId.toString())
                .containsEntry("event_type", "catalog.sku.pricing_changed")
                .containsEntry("old_price", "10.00")
                .containsEntry("new_price", "12.35")
                .containsEntry("old_vat", "23.00")
                .containsEntry("new_vat", "8.00");
    }

    @Test
    void descriptionLimitIsConsistentForCreationAndEditing() {
        String oversized = "x".repeat(10_001);
        assertThatThrownBy(() -> catalog.createProduct("New", oversized, "Category"))
                .isInstanceOf(CatalogException.class);
        assertThatThrownBy(() -> catalog.updateProduct(productId, "Existing", oversized, "Category"))
                .isInstanceOf(CatalogException.class);
    }

    @Test
    void skuActivationWaitsForConcurrentProductDeactivation() throws Exception {
        CountDownLatch deactivationHasProductLock = new CountDownLatch(1);
        CountDownLatch allowDeactivationToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var deactivation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE catalog_product SET is_active=FALSE WHERE id=?", productId);
                deactivationHasProductLock.countDown();
                await(allowDeactivationToFinish);
                jdbc.update("UPDATE catalog_sku SET is_active=FALSE WHERE product_id=?", productId);
            }));
            assertThat(deactivationHasProductLock.await(5, TimeUnit.SECONDS)).isTrue();

            var activation = executor.submit(() -> transactions.executeWithoutResult(status -> store.setSkuActivity(skuId, true, NOW)));
            awaitProductLockWait();
            allowDeactivationToFinish.countDown();
            deactivation.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> activation.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(CatalogException.class);

            assertThat(jdbc.queryForObject("SELECT is_active FROM catalog_product WHERE id=?", Boolean.class, productId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT is_active FROM catalog_sku WHERE id=?", Boolean.class, skuId)).isFalse();
        } finally {
            allowDeactivationToFinish.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitProductLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type='Lock' AND query LIKE '%FOR UPDATE OF p%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("SKU activation did not wait for the product row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test interleaving");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interleaving interrupted", exception);
        }
    }

    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
