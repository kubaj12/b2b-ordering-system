CREATE TABLE identity_user (
	id UUID PRIMARY KEY,
	email VARCHAR(254) NOT NULL,
	password_hash VARCHAR(255) NOT NULL,
	role VARCHAR(16) NOT NULL,
	status VARCHAR(16) NOT NULL,
	security_version BIGINT NOT NULL DEFAULT 0,
	last_login_at TIMESTAMPTZ(6),
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT identity_user_email_canonical
		CHECK (
			email = LOWER(BTRIM(email))
			AND BTRIM(email) <> ''
			AND email !~ '\s'
		),
	CONSTRAINT identity_user_email_uq UNIQUE (email),
	CONSTRAINT identity_user_password_hash_nonblank
		CHECK (BTRIM(password_hash) <> ''),
	CONSTRAINT identity_user_role_valid
		CHECK (role IN ('CUSTOMER', 'EMPLOYEE', 'ADMIN')),
	CONSTRAINT identity_user_status_valid
		CHECK (status IN ('ACTIVE', 'BLOCKED')),
	CONSTRAINT identity_user_security_version_nonnegative
		CHECK (security_version >= 0),
	CONSTRAINT identity_user_updated_at_chronological
		CHECK (updated_at >= created_at),
	CONSTRAINT identity_user_last_login_at_chronological
		CHECK (last_login_at IS NULL OR last_login_at >= created_at)
);

ALTER TABLE audit_event
	ADD CONSTRAINT audit_event_acting_user_id_fk
	FOREIGN KEY (acting_user_id) REFERENCES identity_user (id)
	ON DELETE RESTRICT
	NOT VALID;

DO $$
BEGIN
	IF NOT EXISTS (
		SELECT 1
		FROM audit_event audit
		LEFT JOIN identity_user actor ON actor.id = audit.acting_user_id
		WHERE actor.id IS NULL
	) THEN
		ALTER TABLE audit_event VALIDATE CONSTRAINT audit_event_acting_user_id_fk;
	END IF;
END
$$;

COMMENT ON COLUMN audit_event.acting_user_id IS
	'Physical reference to identity_user(id) for new audit events. Schemas upgraded from V002 may retain an unvalidated foreign key when historical actor UUIDs have no matching account.';

CREATE TABLE identity_session (
	id UUID PRIMARY KEY,
	session_id_hash BYTEA NOT NULL,
	user_id UUID NOT NULL,
	security_version BIGINT NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	last_seen_at TIMESTAMPTZ(6) NOT NULL,
	expires_at TIMESTAMPTZ(6) NOT NULL,
	revoked_at TIMESTAMPTZ(6),
	CONSTRAINT identity_session_session_id_hash_uq UNIQUE (session_id_hash),
	CONSTRAINT identity_session_session_id_hash_length
		CHECK (OCTET_LENGTH(session_id_hash) = 32),
	CONSTRAINT identity_session_user_id_fk
		FOREIGN KEY (user_id) REFERENCES identity_user (id)
		ON DELETE RESTRICT,
	CONSTRAINT identity_session_security_version_nonnegative
		CHECK (security_version >= 0),
	CONSTRAINT identity_session_updated_at_chronological
		CHECK (updated_at >= created_at),
	CONSTRAINT identity_session_last_seen_at_chronological
		CHECK (last_seen_at >= created_at),
	CONSTRAINT identity_session_lifecycle_timestamps_not_after_updated_at
		CHECK (
			updated_at < created_at
			OR (
				last_seen_at <= updated_at
				AND (revoked_at IS NULL OR revoked_at <= updated_at)
			)
		),
	CONSTRAINT identity_session_expires_at_chronological
		CHECK (expires_at > created_at),
	CONSTRAINT identity_session_revoked_at_chronological
		CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX identity_session_user_id_idx
	ON identity_session (user_id);

CREATE INDEX identity_session_expires_at_idx
	ON identity_session (expires_at);

COMMENT ON COLUMN identity_session.session_id_hash IS
	'SHA-256 digest of the servlet session identifier; never the raw session id.';

CREATE TABLE identity_invitation (
	id UUID PRIMARY KEY,
	email VARCHAR(254) NOT NULL,
	role VARCHAR(16) NOT NULL,
	status VARCHAR(16) NOT NULL,
	token_hash BYTEA NOT NULL,
	invited_by_user_id UUID NOT NULL,
	accepted_user_id UUID,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	expires_at TIMESTAMPTZ(6) NOT NULL,
	accepted_at TIMESTAMPTZ(6),
	revoked_at TIMESTAMPTZ(6),
	CONSTRAINT identity_invitation_email_canonical
		CHECK (
			email = LOWER(BTRIM(email))
			AND BTRIM(email) <> ''
			AND email !~ '\s'
		),
	CONSTRAINT identity_invitation_role_valid
		CHECK (role IN ('CUSTOMER', 'EMPLOYEE')),
	CONSTRAINT identity_invitation_status_valid
		CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
	CONSTRAINT identity_invitation_token_hash_uq UNIQUE (token_hash),
	CONSTRAINT identity_invitation_token_hash_length
		CHECK (OCTET_LENGTH(token_hash) = 32),
	CONSTRAINT identity_invitation_invited_by_user_id_fk
		FOREIGN KEY (invited_by_user_id) REFERENCES identity_user (id)
		ON DELETE RESTRICT,
	CONSTRAINT identity_invitation_accepted_user_id_fk
		FOREIGN KEY (accepted_user_id) REFERENCES identity_user (id)
		ON DELETE RESTRICT,
	CONSTRAINT identity_invitation_updated_at_chronological
		CHECK (updated_at >= created_at),
	CONSTRAINT identity_invitation_expires_at_chronological
		CHECK (expires_at > created_at),
	CONSTRAINT identity_invitation_lifecycle_timestamps_not_after_updated_at
		CHECK (
			(accepted_at IS NULL OR accepted_at <= updated_at)
			AND (revoked_at IS NULL OR revoked_at <= updated_at)
		),
	CONSTRAINT identity_invitation_lifecycle_consistent
		CHECK (
			status NOT IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')
			OR
			(
				status IN ('PENDING', 'EXPIRED')
				AND accepted_user_id IS NULL
				AND accepted_at IS NULL
				AND revoked_at IS NULL
			)
			OR (
				status = 'ACCEPTED'
				AND accepted_user_id IS NOT NULL
				AND accepted_at IS NOT NULL
				AND revoked_at IS NULL
				AND created_at <= accepted_at
				AND accepted_at < expires_at
			)
			OR (
				status = 'REVOKED'
				AND accepted_user_id IS NULL
				AND accepted_at IS NULL
				AND revoked_at IS NOT NULL
				AND revoked_at >= created_at
			)
		)
);

CREATE UNIQUE INDEX identity_invitation_pending_email_uq
	ON identity_invitation (email)
	WHERE status = 'PENDING';

CREATE INDEX identity_invitation_expires_at_idx
	ON identity_invitation (expires_at);

CREATE INDEX identity_invitation_invited_by_user_id_idx
	ON identity_invitation (invited_by_user_id);

CREATE INDEX identity_invitation_accepted_user_id_idx
	ON identity_invitation (accepted_user_id);

COMMENT ON COLUMN identity_invitation.token_hash IS
	'SHA-256 digest of an unpredictable invitation token; never the raw link or token.';

COMMENT ON TABLE identity_invitation IS
	'Invitation issuance supplies seven-day expiry using the injectable clock. Validity requires expires_at > :now; equality means expired. Expiration does not automatically change status. Issuance must revoke or expire an older pending invitation before inserting its replacement. Lifecycle checks protect row consistency; later services must use conditional updates and transactions to enforce one-way transitions, single use, acceptance/resend serialization, and account creation. Customer billing payload belongs in the Phase 3 customer migration, referencing invitation IDs.';

CREATE TABLE identity_password_reset_token (
	id UUID PRIMARY KEY,
	user_id UUID NOT NULL,
	token_hash BYTEA NOT NULL,
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	expires_at TIMESTAMPTZ(6) NOT NULL,
	consumed_at TIMESTAMPTZ(6),
	revoked_at TIMESTAMPTZ(6),
	CONSTRAINT identity_password_reset_token_user_id_fk
		FOREIGN KEY (user_id) REFERENCES identity_user (id)
		ON DELETE RESTRICT,
	CONSTRAINT identity_password_reset_token_token_hash_uq UNIQUE (token_hash),
	CONSTRAINT identity_password_reset_token_token_hash_length
		CHECK (OCTET_LENGTH(token_hash) = 32),
	CONSTRAINT identity_password_reset_token_updated_at_chronological
		CHECK (updated_at >= created_at),
	CONSTRAINT identity_password_reset_token_expires_at_chronological
		CHECK (expires_at > created_at),
	CONSTRAINT identity_reset_token_lifecycle_not_after_updated_at
		CHECK (
			(consumed_at IS NULL OR consumed_at <= updated_at)
			AND (revoked_at IS NULL OR revoked_at <= updated_at)
		),
	CONSTRAINT identity_password_reset_token_lifecycle_consistent
		CHECK (
			(consumed_at IS NULL OR revoked_at IS NULL)
			AND (consumed_at IS NULL OR (created_at <= consumed_at AND consumed_at < expires_at))
			AND (revoked_at IS NULL OR revoked_at >= created_at)
		)
);

CREATE INDEX identity_password_reset_token_user_id_idx
	ON identity_password_reset_token (user_id);

CREATE INDEX identity_password_reset_token_expires_at_idx
	ON identity_password_reset_token (expires_at);

COMMENT ON COLUMN identity_password_reset_token.token_hash IS
	'SHA-256 digest of an unpredictable password-reset token; never the raw link or token.';

COMMENT ON TABLE identity_password_reset_token IS
	'Password-reset issuance supplies one-hour expiry using the injectable clock. Validity requires expires_at > :now; equality means expired. Lifecycle checks protect row consistency; later services must use conditional updates and transactions to enforce single use and password replacement.';

CREATE TABLE identity_login_throttle (
	identity_hash BYTEA NOT NULL,
	source_address INET NOT NULL,
	failed_attempts INTEGER NOT NULL DEFAULT 0,
	window_started_at TIMESTAMPTZ(6) NOT NULL,
	last_failed_at TIMESTAMPTZ(6),
	blocked_until TIMESTAMPTZ(6),
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	expires_at TIMESTAMPTZ(6) NOT NULL,
	PRIMARY KEY (identity_hash, source_address),
	CONSTRAINT identity_login_throttle_identity_hash_length
		CHECK (OCTET_LENGTH(identity_hash) = 32),
	CONSTRAINT identity_login_throttle_source_address_host
		CHECK (
			(FAMILY(source_address) = 4 AND MASKLEN(source_address) = 32)
			OR (FAMILY(source_address) = 6 AND MASKLEN(source_address) = 128)
		),
	CONSTRAINT identity_login_throttle_failed_attempts_nonnegative
		CHECK (failed_attempts >= 0),
	CONSTRAINT identity_login_throttle_failure_state_consistent
		CHECK (
			(failed_attempts = 0 AND last_failed_at IS NULL)
			OR (failed_attempts > 0 AND last_failed_at IS NOT NULL)
		),
	CONSTRAINT identity_login_throttle_updated_at_chronological
		CHECK (updated_at >= created_at),
	CONSTRAINT identity_login_throttle_window_started_at_chronological
		CHECK (window_started_at >= created_at),
	CONSTRAINT identity_login_throttle_last_failed_at_chronological
		CHECK (last_failed_at IS NULL OR last_failed_at >= window_started_at),
	CONSTRAINT identity_login_throttle_lifecycle_not_after_updated_at
		CHECK (
			updated_at < created_at
			OR (
				window_started_at <= updated_at
				AND (last_failed_at IS NULL OR last_failed_at <= updated_at)
			)
		),
	CONSTRAINT identity_login_throttle_expires_at_chronological
		CHECK (expires_at > created_at),
	CONSTRAINT identity_login_throttle_blocked_until_chronological
		CHECK (
			blocked_until IS NULL
			OR (
				blocked_until >= window_started_at
				AND expires_at >= blocked_until
			)
		)
);

CREATE INDEX identity_login_throttle_expires_at_idx
	ON identity_login_throttle (expires_at);

COMMENT ON COLUMN identity_login_throttle.identity_hash IS
	'SHA-256 digest of the normalized attempted identity. It supports nonexistent-account attempts and has no account foreign key.';

COMMENT ON TABLE identity_login_throttle IS
	'Later login handling must derive a trusted request source and atomically upsert the composite key. Thresholds, windows, and response behavior belong to the login-throttling implementation.';
