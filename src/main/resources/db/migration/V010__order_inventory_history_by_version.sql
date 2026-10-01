ALTER TABLE inventory_change
	ADD COLUMN inventory_version BIGINT NOT NULL DEFAULT 0,
	ADD CONSTRAINT inventory_change_inventory_version_nonnegative
		CHECK (inventory_version >= 0);

WITH ordered_changes AS (
	SELECT id, ROW_NUMBER() OVER (
		PARTITION BY sku_id ORDER BY changed_at ASC, id ASC
	) AS assigned_version
	FROM inventory_change
)
UPDATE inventory_change history
SET inventory_version = ordered_changes.assigned_version
FROM ordered_changes
WHERE history.id = ordered_changes.id;

UPDATE catalog_sku sku
SET inventory_version = GREATEST(
	sku.inventory_version,
	COALESCE((SELECT MAX(history.inventory_version)
		FROM inventory_change history WHERE history.sku_id = sku.id), 0)
);

DROP INDEX inventory_change_sku_changed_at_idx;
CREATE INDEX inventory_change_sku_version_idx
	ON inventory_change (sku_id, inventory_version DESC, changed_at DESC, id DESC);

COMMENT ON COLUMN inventory_change.inventory_version IS
	'The SKU inventory version produced by this change; orders manual history by the serialized SKU update sequence.';
