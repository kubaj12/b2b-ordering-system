ALTER TABLE identity_user
	ADD CONSTRAINT identity_user_id_role_uq UNIQUE (id, role);

ALTER TABLE identity_invitation
	ADD CONSTRAINT identity_invitation_id_role_uq UNIQUE (id, role);

CREATE TABLE customer_profile (
	user_id UUID PRIMARY KEY,
	user_role VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
	company_name VARCHAR(255) NOT NULL,
	nip VARCHAR(32) NOT NULL,
	billing_street VARCHAR(255) NOT NULL,
	billing_building_number VARCHAR(32) NOT NULL,
	billing_unit_number VARCHAR(32),
	billing_postal_code VARCHAR(6) NOT NULL,
	billing_city VARCHAR(120) NOT NULL,
	billing_country CHAR(2) NOT NULL DEFAULT 'PL',
	phone VARCHAR(32),
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT customer_profile_user_role_customer
		CHECK (user_role = 'CUSTOMER'),
	CONSTRAINT customer_profile_user_fk
		FOREIGN KEY (user_id, user_role) REFERENCES identity_user (id, role)
		ON DELETE RESTRICT,
	CONSTRAINT customer_profile_company_name_nonblank
		CHECK (BTRIM(company_name) <> ''),
	CONSTRAINT customer_profile_nip_canonical
		CHECK (nip ~ '^[0-9]{10}$'),
	CONSTRAINT customer_profile_nip_uq UNIQUE (nip),
	CONSTRAINT customer_profile_billing_street_nonblank
		CHECK (BTRIM(billing_street) <> ''),
	CONSTRAINT customer_profile_billing_building_number_nonblank
		CHECK (BTRIM(billing_building_number) <> ''),
	CONSTRAINT customer_profile_billing_unit_number_nonblank
		CHECK (billing_unit_number IS NULL OR BTRIM(billing_unit_number) <> ''),
	CONSTRAINT customer_profile_billing_postal_code_polish
		CHECK (billing_postal_code ~ '^[0-9]{2}-[0-9]{3}$'),
	CONSTRAINT customer_profile_billing_city_nonblank
		CHECK (BTRIM(billing_city) <> ''),
	CONSTRAINT customer_profile_billing_country_poland
		CHECK (billing_country = 'PL'),
	CONSTRAINT customer_profile_phone_nonblank
		CHECK (phone IS NULL OR BTRIM(phone) <> ''),
	CONSTRAINT customer_profile_updated_at_chronological
		CHECK (updated_at >= created_at)
);

COMMENT ON TABLE customer_profile IS
	'Internal staff-only purchaser and billing data. The primary key makes the profile one-to-one with a CUSTOMER identity account.';

COMMENT ON COLUMN customer_profile.nip IS
	'Canonical Polish NIP: exactly ten digits, without spaces or separators. Checksum validation is performed by the customer application boundary.';

COMMENT ON COLUMN customer_profile.billing_country IS
	'ISO 3166-1 alpha-2 country code fixed to PL.';

CREATE TABLE customer_invitation_data (
	invitation_id UUID PRIMARY KEY,
	invitation_role VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
	company_name VARCHAR(255) NOT NULL,
	nip VARCHAR(32) NOT NULL,
	billing_street VARCHAR(255) NOT NULL,
	billing_building_number VARCHAR(32) NOT NULL,
	billing_unit_number VARCHAR(32),
	billing_postal_code VARCHAR(6) NOT NULL,
	billing_city VARCHAR(120) NOT NULL,
	billing_country CHAR(2) NOT NULL DEFAULT 'PL',
	phone VARCHAR(32),
	created_at TIMESTAMPTZ(6) NOT NULL,
	updated_at TIMESTAMPTZ(6) NOT NULL,
	CONSTRAINT customer_invitation_data_role_customer
		CHECK (invitation_role = 'CUSTOMER'),
	CONSTRAINT customer_invitation_data_invitation_fk
		FOREIGN KEY (invitation_id, invitation_role) REFERENCES identity_invitation (id, role)
		ON DELETE RESTRICT,
	CONSTRAINT customer_invitation_data_company_name_nonblank
		CHECK (BTRIM(company_name) <> ''),
	CONSTRAINT customer_invitation_data_nip_canonical
		CHECK (nip ~ '^[0-9]{10}$'),
	CONSTRAINT customer_invitation_data_nip_uq UNIQUE (nip),
	CONSTRAINT customer_invitation_data_billing_street_nonblank
		CHECK (BTRIM(billing_street) <> ''),
	CONSTRAINT customer_invitation_data_billing_building_number_nonblank
		CHECK (BTRIM(billing_building_number) <> ''),
	CONSTRAINT customer_invitation_data_billing_unit_number_nonblank
		CHECK (billing_unit_number IS NULL OR BTRIM(billing_unit_number) <> ''),
	CONSTRAINT customer_invitation_data_billing_postal_code_polish
		CHECK (billing_postal_code ~ '^[0-9]{2}-[0-9]{3}$'),
	CONSTRAINT customer_invitation_data_billing_city_nonblank
		CHECK (BTRIM(billing_city) <> ''),
	CONSTRAINT customer_invitation_data_billing_country_poland
		CHECK (billing_country = 'PL'),
	CONSTRAINT customer_invitation_data_phone_nonblank
		CHECK (phone IS NULL OR BTRIM(phone) <> ''),
	CONSTRAINT customer_invitation_data_updated_at_chronological
		CHECK (updated_at >= created_at)
);

COMMENT ON TABLE customer_invitation_data IS
	'Staff-only company and billing payload held for a CUSTOMER invitation and copied to customer_profile during atomic acceptance. Resend must transfer the payload by removing it from the revoked invitation before inserting it for the replacement invitation.';

COMMENT ON COLUMN customer_invitation_data.nip IS
	'Canonical Polish NIP: exactly ten digits, without spaces or separators. Checksum validation is performed by the customer application boundary.';

COMMENT ON COLUMN customer_invitation_data.billing_country IS
	'ISO 3166-1 alpha-2 country code fixed to PL.';
