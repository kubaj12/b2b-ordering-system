CREATE TABLE catalog_image_cleanup (
	storage_key VARCHAR(160) PRIMARY KEY,
	created_at TIMESTAMPTZ(6) NOT NULL,
	attempts INTEGER NOT NULL DEFAULT 0,
	last_attempt_at TIMESTAMPTZ(6),
	CONSTRAINT catalog_image_cleanup_attempts_nonnegative CHECK (attempts >= 0),
	CONSTRAINT catalog_image_cleanup_storage_key_nonblank CHECK (BTRIM(storage_key) <> '')
);

COMMENT ON TABLE catalog_image_cleanup IS
	'Committed image references awaiting physical deletion. Rows remain until storage cleanup succeeds.';
