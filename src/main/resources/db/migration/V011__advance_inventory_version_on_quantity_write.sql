-- Keep optimistic versions correct for every stock writer, not only the staff inventory adapter.
-- PostgreSQL's row update lock serializes concurrent writes to this SKU row. The version changes
-- on every quantity write, including an accepted no-op inline submission; price/catalog edits do
-- not affect it.
CREATE OR REPLACE FUNCTION advance_catalog_sku_inventory_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
	NEW.inventory_version := OLD.inventory_version + 1;
	RETURN NEW;
END;
$$;

CREATE TRIGGER catalog_sku_inventory_version_on_quantity_change
BEFORE UPDATE OF available_quantity ON catalog_sku
FOR EACH ROW
EXECUTE FUNCTION advance_catalog_sku_inventory_version();

COMMENT ON FUNCTION advance_catalog_sku_inventory_version() IS
	'Advances the SKU optimistic inventory version for every quantity-column update, including no-op staff submissions, regardless of application writer.';
