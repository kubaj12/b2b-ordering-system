package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

public interface InitialAdminAccountStore {

	boolean existsByEmail(NormalizedEmail email);

	boolean insertIfAbsent(UUID id, NormalizedEmail email, String passwordHash, Instant now);

}
