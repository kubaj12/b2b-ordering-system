package io.github.kubaj12.online_store.inventory.application;

import java.util.UUID;
import io.github.kubaj12.online_store.shared.auditing.AuditEventType;
import io.github.kubaj12.online_store.shared.auditing.AuditField;
import io.github.kubaj12.online_store.shared.auditing.AuditTargetType;

public final class InventoryAudit {
    private InventoryAudit() { }
    public static final AuditField<Integer> QUANTITY = AuditField.integer("quantity", 0, Integer.MAX_VALUE);
    public static final AuditEventType<UUID> QUANTITY_CHANGED = new AuditEventType<>(
            "inventory.quantity.changed", AuditTargetType.uuid("catalog.sku"), QUANTITY);
}
