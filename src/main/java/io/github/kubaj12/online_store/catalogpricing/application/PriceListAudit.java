package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.util.UUID;
import io.github.kubaj12.online_store.shared.auditing.*;

public final class PriceListAudit {
    private PriceListAudit() { }
    public static final AuditField<BigDecimal> PRICE = AuditField.decimal("netPrice",2,BigDecimal.ZERO,new BigDecimal("9999999999.99"));
    public static final AuditField<UUID> PRICE_LIST_ID = AuditField.uuid("priceListId");
    public static final AuditField<UUID> CUSTOMER_ID = AuditField.uuid("customerId");
    public static final AuditEventType<UUID> LIST_PRICE = new AuditEventType<>("catalog.price_list_price.changed", AuditTargetType.uuid("catalog.sku"), PRICE, PRICE_LIST_ID);
    public static final AuditEventType<UUID> CUSTOMER_PRICE = new AuditEventType<>("catalog.customer_price.changed", AuditTargetType.uuid("catalog.sku"), PRICE, CUSTOMER_ID);
    public static final AuditEventType<UUID> CUSTOMER_LIST_ASSIGNMENT = new AuditEventType<>("catalog.customer_price_list.assignment_changed", AuditTargetType.uuid("customer.profile"), PRICE_LIST_ID);
}
