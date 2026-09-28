package io.github.kubaj12.online_store.catalogpricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.kubaj12.online_store.catalogpricing.application.CatalogAdministrationService;
import io.github.kubaj12.online_store.shared.auditing.AuditEvent;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class CatalogAuditRollbackIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final UUID ACTOR = UUID.fromString("01998e62-e700-7000-8000-000000000001");

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogAdministrationService catalog;
    @MockitoBean AuditEventRecorder audit;

    private UUID skuId;

    @BeforeEach
    void createCatalogFixture() {
        testClock().set(NOW);
        new IdentityDatabaseFixture(jdbc).insertActiveUser(ACTOR, "catalog.employee@example.test", "EMPLOYEE", NOW);
        UUID productId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",
                productId, "Produkt", "Akcesoria", at(NOW), at(NOW));
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                skuId, productId, "SKU-1", new BigDecimal("10.00"), new BigDecimal("23.00"), at(NOW), at(NOW));
    }

    @Test
    void auditFailureRollsBackPriceAndVatUpdate() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(AuditEvent.class));

        assertThatThrownBy(() -> catalog.changeBasePriceAndVat(skuId,
                new BigDecimal("15.00"), new BigDecimal("5.00"), ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        assertThat(jdbc.queryForObject("SELECT base_net_price FROM catalog_sku WHERE id=?", BigDecimal.class, skuId))
                .isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT vat_rate FROM catalog_sku WHERE id=?", BigDecimal.class, skuId))
                .isEqualByComparingTo("23.00");
    }

    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
