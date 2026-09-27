ALTER TABLE customer_invitation_data
	DROP CONSTRAINT customer_invitation_data_nip_uq;

CREATE INDEX customer_invitation_data_nip_idx
	ON customer_invitation_data (nip);

COMMENT ON INDEX customer_invitation_data_nip_idx IS
	'Supports serialized live-NIP availability checks while allowing expired and revoked invitations to retain their payload for resend and audit history.';
