CREATE TABLE audit_event (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	event_type VARCHAR(100) NOT NULL,
	target_type VARCHAR(80) NOT NULL,
	target_id VARCHAR(160) NOT NULL,
	acting_user_id UUID NOT NULL,
	occurred_at TIMESTAMPTZ(6) NOT NULL,
	change_metadata JSONB NOT NULL,
	CONSTRAINT audit_event_event_type_format
		CHECK (event_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+$'),
	CONSTRAINT audit_event_target_type_format
		CHECK (target_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_-]*)*$'),
	CONSTRAINT audit_event_target_id_format
		CHECK (
			target_id ~ '^[1-9][0-9]*$'
			OR target_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
		),
	CONSTRAINT audit_event_change_metadata_object
		CHECK (JSONB_TYPEOF(change_metadata) = 'object'),
	CONSTRAINT audit_event_change_metadata_size
		CHECK (OCTET_LENGTH(change_metadata::TEXT) <= 16384)
);

CREATE INDEX audit_event_target_occurred_at_idx
	ON audit_event (target_type, target_id, occurred_at DESC, id DESC);

CREATE INDEX audit_event_actor_occurred_at_idx
	ON audit_event (acting_user_id, occurred_at DESC, id DESC);

CREATE INDEX audit_event_type_occurred_at_idx
	ON audit_event (event_type, occurred_at DESC, id DESC);

COMMENT ON TABLE audit_event IS
	'Append-only security and business change events recorded in the transaction that made the change.';

COMMENT ON COLUMN audit_event.acting_user_id IS
	'Logical reference to a user. The identity migration will add the foreign key once the user table exists.';

COMMENT ON COLUMN audit_event.target_id IS
	'Canonical UUID or positive numeric internal identifier; never a public token or credential.';
