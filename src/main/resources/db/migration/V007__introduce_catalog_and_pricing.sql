CREATE TABLE catalog_product (
	id UUID PRIMARY KEY,
	name VARCHAR(255) NOT NULL,
	description TEXT NOT NULL DEFAULT '',
	category VARCHAR(120) NOT NULL,
	is_active BOOLEAN NOT NULL DEFAULT FALSE,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_product_name_nonblank CHECK (BTRIM(name) <> ''),
	CONSTRAINT catalog_product_category_nonblank CHECK (BTRIM(category) <> ''),
	CONSTRAINT catalog_product_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_product_category_idx ON catalog_product (category);
CREATE INDEX catalog_product_active_name_idx ON catalog_product (name) WHERE is_active;

CREATE TABLE catalog_sku (
	id UUID PRIMARY KEY,
	product_id UUID NOT NULL,
	code VARCHAR(80) NOT NULL,
	base_net_price NUMERIC(12, 2) NOT NULL,
	base_currency CHAR(3) NOT NULL DEFAULT 'PLN',
	vat_rate NUMERIC(5, 2) NOT NULL,
	available_quantity INTEGER NOT NULL DEFAULT 0,
	is_active BOOLEAN NOT NULL DEFAULT FALSE,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_sku_code_uq UNIQUE (code),
	CONSTRAINT catalog_sku_code_nonblank CHECK (BTRIM(code) <> ''),
	CONSTRAINT catalog_sku_product_fk FOREIGN KEY (product_id)
		REFERENCES catalog_product (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_sku_base_net_price_nonnegative CHECK (base_net_price >= 0),
	CONSTRAINT catalog_sku_base_currency_pln CHECK (base_currency = 'PLN'),
	CONSTRAINT catalog_sku_vat_rate_range CHECK (vat_rate >= 0 AND vat_rate <= 100),
	CONSTRAINT catalog_sku_available_quantity_nonnegative CHECK (available_quantity >= 0),
	CONSTRAINT catalog_sku_updated_at_chronological CHECK (updated_at >= created_at),
	CONSTRAINT catalog_sku_id_product_uq UNIQUE (id, product_id)
);

CREATE INDEX catalog_sku_product_id_idx ON catalog_sku (product_id);
CREATE INDEX catalog_sku_active_idx ON catalog_sku (is_active) WHERE is_active;

CREATE TABLE catalog_attribute_definition (
	id UUID PRIMARY KEY,
	name VARCHAR(120) NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_attribute_definition_name_uq UNIQUE (name),
	CONSTRAINT catalog_attribute_definition_name_nonblank CHECK (BTRIM(name) <> ''),
	CONSTRAINT catalog_attribute_definition_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE TABLE catalog_attribute_value (
	id UUID PRIMARY KEY,
	definition_id UUID NOT NULL,
	value VARCHAR(120) NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_attribute_value_definition_fk FOREIGN KEY (definition_id)
		REFERENCES catalog_attribute_definition (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_attribute_value_value_nonblank CHECK (BTRIM(value) <> ''),
	CONSTRAINT catalog_attribute_value_definition_value_uq UNIQUE (definition_id, value),
	CONSTRAINT catalog_attribute_value_id_definition_uq UNIQUE (id, definition_id),
	CONSTRAINT catalog_attribute_value_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE TABLE catalog_sku_attribute_assignment (
	sku_id UUID NOT NULL,
	attribute_definition_id UUID NOT NULL,
	attribute_value_id UUID NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_sku_attribute_assignment_pk PRIMARY KEY (sku_id, attribute_definition_id),
	CONSTRAINT catalog_sku_attribute_assignment_sku_fk FOREIGN KEY (sku_id)
		REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_sku_attribute_assignment_value_fk FOREIGN KEY (attribute_value_id, attribute_definition_id)
		REFERENCES catalog_attribute_value (id, definition_id) ON DELETE RESTRICT
);

CREATE INDEX catalog_sku_attribute_value_idx ON catalog_sku_attribute_assignment (attribute_value_id);

CREATE TABLE catalog_price_list (
	id UUID PRIMARY KEY,
	name VARCHAR(160) NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_price_list_name_uq UNIQUE (name),
	CONSTRAINT catalog_price_list_name_nonblank CHECK (BTRIM(name) <> ''),
	CONSTRAINT catalog_price_list_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE TABLE catalog_price_list_item (
	price_list_id UUID NOT NULL,
	sku_id UUID NOT NULL,
	net_price NUMERIC(12, 2) NOT NULL,
	currency CHAR(3) NOT NULL DEFAULT 'PLN',
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_price_list_item_pk PRIMARY KEY (price_list_id, sku_id),
	CONSTRAINT catalog_price_list_item_list_fk FOREIGN KEY (price_list_id)
		REFERENCES catalog_price_list (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_price_list_item_sku_fk FOREIGN KEY (sku_id)
		REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_price_list_item_price_nonnegative CHECK (net_price >= 0),
	CONSTRAINT catalog_price_list_item_currency_pln CHECK (currency = 'PLN'),
	CONSTRAINT catalog_price_list_item_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_price_list_item_sku_idx ON catalog_price_list_item (sku_id);

CREATE TABLE catalog_customer_price_list_assignment (
	customer_id UUID PRIMARY KEY,
	price_list_id UUID NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_customer_price_list_customer_fk FOREIGN KEY (customer_id)
		REFERENCES customer_profile (user_id) ON DELETE RESTRICT,
	CONSTRAINT catalog_customer_price_list_list_fk FOREIGN KEY (price_list_id)
		REFERENCES catalog_price_list (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_customer_price_list_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_customer_price_list_list_idx ON catalog_customer_price_list_assignment (price_list_id);

CREATE TABLE catalog_customer_specific_price (
	customer_id UUID NOT NULL,
	sku_id UUID NOT NULL,
	net_price NUMERIC(12, 2) NOT NULL,
	currency CHAR(3) NOT NULL DEFAULT 'PLN',
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_customer_specific_price_pk PRIMARY KEY (customer_id, sku_id),
	CONSTRAINT catalog_customer_specific_price_customer_fk FOREIGN KEY (customer_id)
		REFERENCES customer_profile (user_id) ON DELETE RESTRICT,
	CONSTRAINT catalog_customer_specific_price_sku_fk FOREIGN KEY (sku_id)
		REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_customer_specific_price_price_nonnegative CHECK (net_price >= 0),
	CONSTRAINT catalog_customer_specific_price_currency_pln CHECK (currency = 'PLN'),
	CONSTRAINT catalog_customer_specific_price_updated_at_chronological CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_customer_specific_price_sku_idx ON catalog_customer_specific_price (sku_id);

CREATE TABLE catalog_sku_image_metadata (
	sku_id UUID PRIMARY KEY,
	storage_key VARCHAR(512) NOT NULL,
	content_type VARCHAR(100) NOT NULL,
	byte_size BIGINT NOT NULL,
	width INTEGER,
	height INTEGER,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT catalog_sku_image_sku_fk FOREIGN KEY (sku_id)
		REFERENCES catalog_sku (id) ON DELETE RESTRICT,
	CONSTRAINT catalog_sku_image_storage_key_uq UNIQUE (storage_key),
	CONSTRAINT catalog_sku_image_storage_key_nonblank CHECK (BTRIM(storage_key) <> ''),
	CONSTRAINT catalog_sku_image_content_type_nonblank CHECK (BTRIM(content_type) <> ''),
	CONSTRAINT catalog_sku_image_byte_size_positive CHECK (byte_size > 0),
	CONSTRAINT catalog_sku_image_dimensions_positive CHECK (
		(width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
	),
	CONSTRAINT catalog_sku_image_updated_at_chronological CHECK (updated_at >= created_at)
);

COMMENT ON TABLE catalog_sku_image_metadata IS
	'Image metadata and opaque storage reference for a SKU. Image binaries live in configured durable storage, not PostgreSQL.';
COMMENT ON COLUMN catalog_sku.base_net_price IS
	'Current net unit price in PLN, stored to two decimal places.';
COMMENT ON COLUMN catalog_sku.vat_rate IS
	'Current VAT percentage, stored to two decimal places and constrained to 0 through 100.';
COMMENT ON COLUMN catalog_sku.available_quantity IS
	'The sole current stock quantity for this SKU; it cannot be negative.';
