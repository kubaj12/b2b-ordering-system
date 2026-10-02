CREATE TABLE cart_ordering_cart (
	id UUID PRIMARY KEY,
	customer_id UUID NOT NULL,
	status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
	revision BIGINT NOT NULL DEFAULT 0,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	completed_at TIMESTAMPTZ(6),
	CONSTRAINT cart_ordering_cart_customer_fk
		FOREIGN KEY (customer_id) REFERENCES customer_profile (user_id) ON DELETE RESTRICT,
	CONSTRAINT cart_ordering_cart_status_valid
		CHECK (status IN ('ACTIVE', 'COMPLETED')),
	CONSTRAINT cart_ordering_cart_revision_nonnegative CHECK (revision >= 0),
	CONSTRAINT cart_ordering_cart_updated_at_chronological CHECK (updated_at >= created_at),
	CONSTRAINT cart_ordering_cart_lifecycle_consistent CHECK (
		(status = 'ACTIVE' AND completed_at IS NULL)
		OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= created_at)
	),
	CONSTRAINT cart_ordering_cart_completion_not_after_update CHECK (
		completed_at IS NULL OR completed_at <= updated_at
	),
	CONSTRAINT cart_ordering_cart_id_customer_uq UNIQUE (id, customer_id)
);

CREATE UNIQUE INDEX cart_ordering_one_active_cart_per_customer_uq
	ON cart_ordering_cart (customer_id) WHERE status = 'ACTIVE';

CREATE INDEX cart_ordering_cart_customer_updated_idx
	ON cart_ordering_cart (customer_id, updated_at DESC);

CREATE TABLE cart_ordering_cart_item (
	cart_id UUID NOT NULL,
	sku_id UUID NOT NULL,
	quantity INTEGER NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT cart_ordering_cart_item_pk PRIMARY KEY (cart_id, sku_id),
	CONSTRAINT cart_ordering_cart_item_cart_fk
		FOREIGN KEY (cart_id) REFERENCES cart_ordering_cart (id) ON DELETE RESTRICT,
	CONSTRAINT cart_ordering_cart_item_sku_fk
		FOREIGN KEY (sku_id) REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT cart_ordering_cart_item_quantity_positive CHECK (quantity > 0),
	CONSTRAINT cart_ordering_cart_item_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE INDEX cart_ordering_cart_item_sku_idx
	ON cart_ordering_cart_item (sku_id);

CREATE TABLE cart_ordering_checkout_review (
	id UUID PRIMARY KEY,
	cart_id UUID NOT NULL,
	customer_id UUID NOT NULL,
	token_hash BYTEA NOT NULL,
	cart_revision BIGINT NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	expires_at TIMESTAMPTZ(6) NOT NULL,
	consumed_at TIMESTAMPTZ(6),
	invalidated_at TIMESTAMPTZ(6),
	CONSTRAINT cart_ordering_checkout_review_cart_customer_fk
		FOREIGN KEY (cart_id, customer_id)
		REFERENCES cart_ordering_cart (id, customer_id) ON DELETE RESTRICT,
	CONSTRAINT cart_ordering_checkout_review_token_hash_uq UNIQUE (token_hash),
	CONSTRAINT cart_ordering_checkout_review_token_hash_length
		CHECK (OCTET_LENGTH(token_hash) = 32),
	CONSTRAINT cart_ordering_checkout_review_cart_revision_nonnegative
		CHECK (cart_revision >= 0),
	CONSTRAINT cart_ordering_checkout_review_updated_at_chronological
		CHECK (updated_at >= created_at),
	CONSTRAINT cart_ordering_checkout_review_expiry_chronological
		CHECK (expires_at > created_at),
	CONSTRAINT cart_ordering_checkout_review_consumption_consistent
		CHECK (consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at < expires_at)),
	CONSTRAINT cart_ordering_checkout_review_consumption_not_after_update
		CHECK (consumed_at IS NULL OR consumed_at <= updated_at),
	CONSTRAINT cart_ordering_checkout_review_invalidation_consistent
		CHECK (invalidated_at IS NULL OR invalidated_at >= created_at),
	CONSTRAINT cart_ordering_checkout_review_invalidation_not_after_update
		CHECK (invalidated_at IS NULL OR invalidated_at <= updated_at),
	CONSTRAINT cart_ordering_checkout_review_terminal_state_exclusive
		CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);

CREATE INDEX cart_ordering_checkout_review_cart_created_idx
	ON cart_ordering_checkout_review (cart_id, created_at DESC);

CREATE INDEX cart_ordering_checkout_review_expiry_idx
	ON cart_ordering_checkout_review (expires_at)
	WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE INDEX cart_ordering_checkout_review_cart_invalidated_idx
	ON cart_ordering_checkout_review (cart_id, invalidated_at DESC)
	WHERE invalidated_at IS NOT NULL;

CREATE TABLE cart_ordering_checkout_review_item (
	review_id UUID NOT NULL,
	sku_id UUID NOT NULL,
	quantity INTEGER NOT NULL,
	reviewed_unit_net_price NUMERIC(12, 2) NOT NULL,
	reviewed_vat_rate NUMERIC(5, 2) NOT NULL,
	price_fingerprint BYTEA NOT NULL,
	vat_fingerprint BYTEA NOT NULL,
	CONSTRAINT cart_ordering_checkout_review_item_pk PRIMARY KEY (review_id, sku_id),
	CONSTRAINT cart_ordering_checkout_review_item_review_fk
		FOREIGN KEY (review_id) REFERENCES cart_ordering_checkout_review (id) ON DELETE RESTRICT,
	CONSTRAINT cart_ordering_checkout_review_item_sku_fk
		FOREIGN KEY (sku_id) REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT cart_ordering_checkout_review_item_quantity_positive CHECK (quantity > 0),
	CONSTRAINT cart_ordering_checkout_review_item_price_nonnegative CHECK (reviewed_unit_net_price >= 0),
	CONSTRAINT cart_ordering_checkout_review_item_vat_range
		CHECK (reviewed_vat_rate >= 0 AND reviewed_vat_rate <= 100),
	CONSTRAINT cart_ordering_checkout_review_item_price_fingerprint_length
		CHECK (OCTET_LENGTH(price_fingerprint) = 32),
	CONSTRAINT cart_ordering_checkout_review_item_vat_fingerprint_length
		CHECK (OCTET_LENGTH(vat_fingerprint) = 32)
);

CREATE INDEX cart_ordering_checkout_review_item_sku_idx
	ON cart_ordering_checkout_review_item (sku_id);

COMMENT ON COLUMN cart_ordering_cart.revision IS
	'Monotonic cart content revision. Every successful line insert, update, or delete must lock the cart row and advance this value in the same transaction.';

COMMENT ON TABLE cart_ordering_cart_item IS
	'One row per cart/SKU line. The 500 distinct-line maximum is enforced by cart mutation services while holding a lock on the parent cart row; PostgreSQL row checks cannot express this aggregate bound safely.';

COMMENT ON COLUMN cart_ordering_checkout_review.token_hash IS
	'SHA-256 digest of an unpredictable, single-use checkout token; raw tokens are never persisted.';

COMMENT ON TABLE cart_ordering_checkout_review IS
	'A customer/cart-bound one-time review. Issuance must snapshot the current cart revision and item fingerprints; consumption must lock/claim the row and validate customer, expiry, unused state, active cart status, and unchanged revision in the order transaction. Superseded reviews are marked invalidated_at and remain distinct from successfully consumed tokens.';

COMMENT ON TABLE cart_ordering_checkout_review_item IS
	'Immutable reviewed commercial snapshot, one row per reviewed cart SKU. Price and VAT fingerprints are SHA-256 digests of canonical effective-price and VAT inputs; submission re-resolves current values and requires both snapshots/fingerprints to match. The 500-line cap is enforced by service under the cart lock.';
