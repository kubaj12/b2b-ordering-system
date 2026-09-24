CREATE FUNCTION polish_nip_is_valid(candidate TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
RETURN CASE WHEN candidate ~ '^[0-9]{10}$' THEN (
		SUBSTRING(candidate, 1, 1)::INTEGER * 6
		+ SUBSTRING(candidate, 2, 1)::INTEGER * 5
		+ SUBSTRING(candidate, 3, 1)::INTEGER * 7
		+ SUBSTRING(candidate, 4, 1)::INTEGER * 2
		+ SUBSTRING(candidate, 5, 1)::INTEGER * 3
		+ SUBSTRING(candidate, 6, 1)::INTEGER * 4
		+ SUBSTRING(candidate, 7, 1)::INTEGER * 5
		+ SUBSTRING(candidate, 8, 1)::INTEGER * 6
		+ SUBSTRING(candidate, 9, 1)::INTEGER * 7
	) % 11 = SUBSTRING(candidate, 10, 1)::INTEGER ELSE FALSE END;

ALTER TABLE customer_profile
	DROP CONSTRAINT customer_profile_phone_nonblank;

ALTER TABLE customer_invitation_data
	DROP CONSTRAINT customer_invitation_data_phone_nonblank;

WITH normalized AS (
	SELECT user_id,
		CASE
			WHEN compact_phone LIKE '+48%' THEN compact_phone
			WHEN compact_phone LIKE '0048%' THEN '+48' || SUBSTRING(compact_phone, 5)
			WHEN compact_phone !~ '^\+' THEN '+48' || compact_phone
			ELSE compact_phone
		END AS canonical_phone
	FROM customer_profile
	CROSS JOIN LATERAL (
		SELECT REGEXP_REPLACE(BTRIM(phone), '[[:space:]()\-]', '', 'g') AS compact_phone
	) compacted
	WHERE phone IS NOT NULL
)
UPDATE customer_profile profile
SET phone = normalized.canonical_phone
FROM normalized
WHERE profile.user_id = normalized.user_id
	AND normalized.canonical_phone ~ '^\+48[0-9]{9}$';

WITH normalized AS (
	SELECT invitation_id,
		CASE
			WHEN compact_phone LIKE '+48%' THEN compact_phone
			WHEN compact_phone LIKE '0048%' THEN '+48' || SUBSTRING(compact_phone, 5)
			WHEN compact_phone !~ '^\+' THEN '+48' || compact_phone
			ELSE compact_phone
		END AS canonical_phone
	FROM customer_invitation_data
	CROSS JOIN LATERAL (
		SELECT REGEXP_REPLACE(BTRIM(phone), '[[:space:]()\-]', '', 'g') AS compact_phone
	) compacted
	WHERE phone IS NOT NULL
)
UPDATE customer_invitation_data invitation_data
SET phone = normalized.canonical_phone
FROM normalized
WHERE invitation_data.invitation_id = normalized.invitation_id
	AND normalized.canonical_phone ~ '^\+48[0-9]{9}$';

ALTER TABLE customer_profile
	ADD CONSTRAINT customer_profile_nip_checksum
		CHECK (polish_nip_is_valid(nip)) NOT VALID,
	ADD CONSTRAINT customer_profile_phone_polish
		CHECK (phone IS NULL OR phone ~ '^\+48[0-9]{9}$') NOT VALID;

ALTER TABLE customer_invitation_data
	ADD CONSTRAINT customer_invitation_data_nip_checksum
		CHECK (polish_nip_is_valid(nip)) NOT VALID,
	ADD CONSTRAINT customer_invitation_data_phone_polish
		CHECK (phone IS NULL OR phone ~ '^\+48[0-9]{9}$') NOT VALID;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM customer_profile WHERE NOT polish_nip_is_valid(nip)) THEN
		ALTER TABLE customer_profile VALIDATE CONSTRAINT customer_profile_nip_checksum;
	END IF;
	IF NOT EXISTS (SELECT 1 FROM customer_profile WHERE phone IS NOT NULL AND phone !~ '^\+48[0-9]{9}$') THEN
		ALTER TABLE customer_profile VALIDATE CONSTRAINT customer_profile_phone_polish;
	END IF;
	IF NOT EXISTS (SELECT 1 FROM customer_invitation_data WHERE NOT polish_nip_is_valid(nip)) THEN
		ALTER TABLE customer_invitation_data VALIDATE CONSTRAINT customer_invitation_data_nip_checksum;
	END IF;
	IF NOT EXISTS (SELECT 1 FROM customer_invitation_data WHERE phone IS NOT NULL AND phone !~ '^\+48[0-9]{9}$') THEN
		ALTER TABLE customer_invitation_data VALIDATE CONSTRAINT customer_invitation_data_phone_polish;
	END IF;
END
$$;

COMMENT ON FUNCTION polish_nip_is_valid(TEXT) IS
	'Validates the canonical ten-digit representation and official weighted checksum of a Polish NIP.';
COMMENT ON COLUMN customer_profile.phone IS
	'Optional canonical Polish telephone number: +48 followed by nine digits.';
COMMENT ON COLUMN customer_invitation_data.phone IS
	'Optional canonical Polish telephone number: +48 followed by nine digits.';

COMMENT ON CONSTRAINT customer_profile_nip_checksum ON customer_profile IS
	'New writes are checked. If unvalidated after upgrade, find legacy rows with NOT polish_nip_is_valid(nip), correct them from an authoritative source, then VALIDATE CONSTRAINT.';
COMMENT ON CONSTRAINT customer_profile_phone_polish ON customer_profile IS
	'Common legacy formats are normalized. If unvalidated after upgrade, find remaining noncanonical phones, correct them without inventing data, then VALIDATE CONSTRAINT.';
COMMENT ON CONSTRAINT customer_invitation_data_nip_checksum ON customer_invitation_data IS
	'New writes are checked. If unvalidated after upgrade, find legacy rows with NOT polish_nip_is_valid(nip), correct them from an authoritative source, then VALIDATE CONSTRAINT.';
COMMENT ON CONSTRAINT customer_invitation_data_phone_polish ON customer_invitation_data IS
	'Common legacy formats are normalized. If unvalidated after upgrade, find remaining noncanonical phones, correct them without inventing data, then VALIDATE CONSTRAINT.';
