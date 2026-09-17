package io.github.kubaj12.online_store.identityaccess.application;

import java.util.Optional;
import java.util.UUID;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

/** Read-only account data needed by browser authentication. */
public interface AuthenticationAccountStore {

	Optional<Credentials> findCredentialsByEmail(NormalizedEmail email);

	Optional<AccessSnapshot> findAccessById(UUID accountId);

	record Credentials(UUID id, String email, String passwordHash, String role, String status, long securityVersion) {
		@Override
		public String toString() {
			return "Credentials[<redacted>]";
		}
	}

	record AccessSnapshot(UUID id, String email, String role, String status, long securityVersion) {
		@Override
		public String toString() {
			return "AccessSnapshot[<redacted>]";
		}
	}
}
