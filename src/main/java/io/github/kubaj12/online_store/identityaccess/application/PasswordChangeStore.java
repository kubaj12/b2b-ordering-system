package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordChangeStore {
    Optional<LockedAccount> lockActivePassword(UUID accountId);
    void replacePassword(UUID accountId, String passwordHash, Instant now);

    record LockedAccount(String passwordHash, long securityVersion) { }
}
