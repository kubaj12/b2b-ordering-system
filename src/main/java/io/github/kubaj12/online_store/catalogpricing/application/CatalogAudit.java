package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.util.UUID;
import io.github.kubaj12.online_store.shared.auditing.AuditEventType;
import io.github.kubaj12.online_store.shared.auditing.AuditField;
import io.github.kubaj12.online_store.shared.auditing.AuditTargetType;

/** Typed, non-sensitive audit metadata for catalog commercial terms. */
public final class CatalogAudit {
    private CatalogAudit() { }
    public static final AuditField<BigDecimal> BASE_NET_PRICE = AuditField.decimal("baseNetPrice", 2, BigDecimal.ZERO, new BigDecimal("9999999999.99"));
    public static final AuditField<BigDecimal> VAT_RATE = AuditField.decimal("vatRate", 2, BigDecimal.ZERO, new BigDecimal("100.00"));
    public static final AuditEventType<UUID> SKU_PRICING_CHANGED = new AuditEventType<>(
            "catalog.sku.pricing_changed", AuditTargetType.uuid("catalog.sku"), BASE_NET_PRICE, VAT_RATE);
}
