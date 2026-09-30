ALTER TABLE catalog_sku
	ADD COLUMN inventory_version BIGINT NOT NULL DEFAULT 0,
	ADD CONSTRAINT catalog_sku_inventory_version_nonnegative
		CHECK (inventory_version >= 0);

CREATE TABLE inventory_change (
	id UUID PRIMARY KEY,
	sku_id UUID NOT NULL,
	previous_quantity INTEGER NOT NULL,
	new_quantity INTEGER NOT NULL,
	acting_user_id UUID NOT NULL,
	changed_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT inventory_change_sku_fk
		FOREIGN KEY (sku_id) REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT inventory_change_acting_user_fk
		FOREIGN KEY (acting_user_id) REFERENCES identity_user (id) ON DELETE RESTRICT,
	CONSTRAINT inventory_change_previous_quantity_nonnegative
		CHECK (previous_quantity >= 0),
	CONSTRAINT inventory_change_new_quantity_nonnegative
		CHECK (new_quantity >= 0)
);

CREATE INDEX inventory_change_sku_changed_at_idx
	ON inventory_change (sku_id, changed_at DESC, id DESC);

CREATE INDEX inventory_change_actor_changed_at_idx
	ON inventory_change (acting_user_id, changed_at DESC);

COMMENT ON COLUMN catalog_sku.inventory_version IS
	'Monotonic optimistic concurrency version for all inventory writers, including manual adjustments and order submission.';

COMMENT ON TABLE inventory_change IS
	'Append-only history of successful manual inventory quantity changes; changed_at is an absolute UTC instant.';
